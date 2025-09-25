package br.com.lolmatchmaking.backend.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import br.com.lolmatchmaking.backend.service.QueueService;
import br.com.lolmatchmaking.backend.service.AcceptanceService;
import br.com.lolmatchmaking.backend.service.MatchmakingOrchestrator;
import br.com.lolmatchmaking.backend.service.DraftFlowService;
import org.springframework.lang.NonNull;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CoreWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CoreWebSocketHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String FIELD_SUMMONER_NAME = "summonerName";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_PLAYER_ID = "playerId";
    private static final String FIELD_MATCH_ID = "matchId";

    private final QueueService queueService;
    private final AcceptanceService acceptanceService;
    private final SessionRegistry sessionRegistry;
    private final MatchmakingOrchestrator matchmakingOrchestrator;
    private final DraftFlowService draftFlowService;
    private final MatchmakingWebSocketService webSocketService;

    // sessionId -> player info (JSON raw)
    private final Map<String, JsonNode> identifiedPlayers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        log.info("Cliente conectado: {}", session.getId());
        sessionRegistry.add(session);
        webSocketService.addSession(session.getId(), session);
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        JsonNode root = mapper.readTree(message.getPayload());
        String type = root.path("type").asText();
        switch (type) {
            case "identify_player" -> handleIdentify(session, root);
            case "ping" -> session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            case "join_queue" -> handleJoinQueue(session, root);
            case "leave_queue" -> handleLeaveQueue(session, root);
            case "accept_match" -> handleAcceptDecline(session, root, true);
            case "decline_match" -> handleAcceptDecline(session, root, false);
            case "acceptance_status" -> handleAcceptanceStatus(session, root);
            case "get_queue_status" -> handleGetQueueStatus(session, root);
            case "draft_action" -> handleDraftAction(session, root);
            case "draft_confirm" -> handleDraftConfirm(session, root);
            case "draft_snapshot" -> handleDraftSnapshot(session, root);
            default -> session.sendMessage(new TextMessage("{\"error\":\"unknown_type\"}"));
        }
    }

    private static final String FIELD_CUSTOM_LP = "customLp";

    private void handleIdentify(WebSocketSession session, JsonNode root) throws IOException {
        JsonNode playerData = root.path("playerData");
        if (playerData.isMissingNode()) {
            session.sendMessage(
                    new TextMessage("{\"type\":\"player_identified\",\"success\":false,\"error\":\"Dados ausentes\"}"));
            return;
        }
        identifiedPlayers.put(session.getId(), playerData);
        session.sendMessage(new TextMessage("{\"type\":\"player_identified\",\"success\":true}"));
        // tentar reemitir match_found se estiver em aceitação
        long queuePlayerId = playerData.path("queuePlayerId").asLong(-1);
        if (queuePlayerId > 0) {
            matchmakingOrchestrator.reemitIfInAcceptance(queuePlayerId, session);
        }
        // reemitir snapshot de draft se player estiver em uma
        String summonerName = playerData.path(FIELD_SUMMONER_NAME).asText(null);
        if (summonerName != null) {
            draftFlowService.reemitIfPlayerInDraft(summonerName, session);
            draftFlowService.reemitIfPlayerGameReady(summonerName, session);
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        identifiedPlayers.remove(session.getId());
        sessionRegistry.remove(session.getId());
        webSocketService.removeSession(session.getId());
        log.info("Cliente desconectado: {} - status {}", session.getId(), status);
        // NÃO remover da fila aqui (mesma regra do Node)
    }

    private void handleJoinQueue(WebSocketSession session, JsonNode root) throws IOException {
        JsonNode data = root.path("data");
        String summonerName = data.path(FIELD_SUMMONER_NAME).asText(null);
        if (summonerName == null) {
            session.sendMessage(new TextMessage(
                    "{\"type\":\"join_queue_result\",\"success\":false,\"error\":\"summonerName required\"}"));
            return;
        }
        queueService.joinQueue(summonerName, data.path("region").asText("br1"), data.path(FIELD_PLAYER_ID).asLong(0),
                data.has(FIELD_CUSTOM_LP) && !data.get(FIELD_CUSTOM_LP).isNull() ? data.get(FIELD_CUSTOM_LP).asInt()
                        : null,
                data.path("primaryLane").asText(null), data.path("secondaryLane").asText(null));
        session.sendMessage(new TextMessage("{\"type\":\"join_queue_result\",\"success\":true}"));
        broadcastQueueUpdate();
    }

    private void handleLeaveQueue(WebSocketSession session, JsonNode root) throws IOException {
        String summonerName = root.path("data").path(FIELD_SUMMONER_NAME).asText(null);
        if (summonerName != null) {
            queueService.leaveQueue(summonerName);
            session.sendMessage(new TextMessage("{\"type\":\"leave_queue_result\",\"success\":true}"));
            broadcastQueueUpdate();
        } else {
            session.sendMessage(new TextMessage(
                    "{\"type\":\"leave_queue_result\",\"success\":false,\"error\":\"summonerName required\"}"));
        }
    }

    private void handleAcceptDecline(WebSocketSession session, JsonNode root, boolean accept) throws IOException {
        long queuePlayerId = root.path("data").path("queuePlayerId").asLong(-1);
        if (queuePlayerId <= 0) {
            session.sendMessage(new TextMessage(
                    "{\"type\":\"accept_match_result\",\"success\":false,\"error\":\"queuePlayerId invalid\"}"));
            return;
        }
        if (accept)
            acceptanceService.accept(queuePlayerId);
        else
            acceptanceService.decline(queuePlayerId);
        session.sendMessage(
                new TextMessage("{\"type\":\"accept_match_result\",\"success\":true,\"accepted\":" + accept + "}"));
    }

    private void handleAcceptanceStatus(WebSocketSession session, JsonNode root) throws IOException {
        long matchTempId = root.path("data").path("matchTempId").asLong(-1);
        if (matchTempId <= 0) {
            session.sendMessage(new TextMessage(
                    "{\"type\":\"acceptance_status\",\"success\":false,\"error\":\"matchTempId invalid\"}"));
            return;
        }
        var status = acceptanceService.status(matchTempId);
        session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "acceptance_status",
                "success", true,
                FIELD_STATUS, status))));
    }

    private void broadcastQueueUpdate() {
        var status = queueService.queueStatus(null);
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "type", "queue_status",
                    FIELD_STATUS, status));
            sessionRegistry.all().forEach(ws -> {
                try {
                    ws.sendMessage(new TextMessage(json));
                } catch (Exception e) {
                    log.warn("Falha enviando queue_status para {}", ws.getId(), e);
                }
            });
        } catch (Exception e) {
            log.error("Erro broadcast queue_status", e);
        }
    }

    private void handleGetQueueStatus(WebSocketSession session, JsonNode root) throws IOException {
        String currentSummoner = null;
        JsonNode dataNode = root.path("data");
        if (!dataNode.isMissingNode()) {
            currentSummoner = dataNode.path(FIELD_SUMMONER_NAME).asText(null);
        }
        var status = queueService.queueStatus(currentSummoner);
        session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "queue_status",
                FIELD_STATUS, status))));
    }

    private void handleDraftAction(WebSocketSession session, JsonNode root) throws IOException {
        JsonNode data = root.path("data");
        long matchId = data.path(FIELD_MATCH_ID).asLong(-1);
        int actionIndex = data.path("actionIndex").asInt(-1);
        String championId = data.path("championId").asText(null);
        String byPlayer = data.path(FIELD_PLAYER_ID).asText(null);
        if (matchId <= 0 || actionIndex < 0 || championId == null || byPlayer == null) {
            session.sendMessage(new TextMessage("{\"type\":\"draft_action_result\",\"success\":false}"));
            return;
        }
        boolean ok = draftFlowService.processAction(matchId, actionIndex, championId, byPlayer);
        session.sendMessage(new TextMessage("{\"type\":\"draft_action_result\",\"success\":" + ok + "}"));
    }

    private void handleDraftConfirm(WebSocketSession session, JsonNode root) throws IOException {
        JsonNode data = root.path("data");
        long matchId = data.path(FIELD_MATCH_ID).asLong(-1);
        String playerId = data.path(FIELD_PLAYER_ID).asText(null);
        if (matchId <= 0 || playerId == null) {
            session.sendMessage(new TextMessage("{\"type\":\"draft_confirm_result\",\"success\":false}"));
            return;
        }
        draftFlowService.confirmDraft(matchId, playerId);
        session.sendMessage(new TextMessage("{\"type\":\"draft_confirm_result\",\"success\":true}"));
    }

    private void handleDraftSnapshot(WebSocketSession session, JsonNode root) throws IOException {
        long matchId = root.path("data").path(FIELD_MATCH_ID).asLong(-1);
        if (matchId <= 0) {
            session.sendMessage(new TextMessage("{\"type\":\"draft_snapshot\",\"success\":false}"));
            return;
        }
        var snap = draftFlowService.snapshot(matchId);
        session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "draft_snapshot",
                "success", true,
                "snapshot", snap))));
    }
}
