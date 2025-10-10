package br.com.lolmatchmaking.backend.websocket;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

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
import br.com.lolmatchmaking.backend.service.LCUConnectionRegistry;
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
    private final LCUConnectionRegistry lcuConnectionRegistry;
    private final br.com.lolmatchmaking.backend.service.PlayerService playerService;

    // sessionId -> player info (JSON raw)
    private final Map<String, JsonNode> identifiedPlayers = new ConcurrentHashMap<>();
    // sessionId -> last LCU status
    private final Map<String, JsonNode> lastLcuStatus = new ConcurrentHashMap<>();

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
            case "lcu_status" -> handleLcuStatus(session, root);
            case "register_lcu_connection" -> handleRegisterLcuConnection(session, root);
            case "lcu_response" -> handleLcuResponse(session, root);
            default -> session.sendMessage(new TextMessage("{\"error\":\"unknown_type\"}"));
        }
    }

    /**
     * Handler para registrar conexão LCU de um jogador
     * 
     * Payload esperado:
     * {
     * "type": "register_lcu_connection",
     * "summonerName": "PlayerOne",
     * "host": "127.0.0.1",
     * "port": 2999,
     * "authToken": "base64token"
     * }
     */
    private void handleRegisterLcuConnection(WebSocketSession session, JsonNode root) throws IOException {
        String summonerName = root.path(FIELD_SUMMONER_NAME).asText(null);
        String host = root.path("host").asText("127.0.0.1");
        int port = root.path("port").asInt(0);
        String authToken = root.path("authToken").asText(null);

        if (summonerName == null || summonerName.trim().isEmpty()) {
            log.warn("⚠️ [WS] register_lcu_connection sem summonerName");
            session.sendMessage(new TextMessage(
                    "{\"type\":\"lcu_connection_registered\",\"success\":false,\"error\":\"summonerName obrigatório\"}"));
            return;
        }

        if (port == 0 || authToken == null) {
            log.warn("⚠️ [WS] register_lcu_connection com dados incompletos: port={}, authToken={}",
                    port, authToken != null ? "present" : "missing");
            session.sendMessage(new TextMessage(
                    "{\"type\":\"lcu_connection_registered\",\"success\":false,\"error\":\"port e authToken obrigatórios\"}"));
            return;
        }

        // Registrar no registry
        lcuConnectionRegistry.registerConnection(
                summonerName,
                session.getId(),
                host,
                port,
                authToken);

        // IMPORTANTE: Também atualizar o MatchmakingWebSocketService para que
        // pickGatewaySessionId funcione
        webSocketService.updateLcuInfo(session.getId(), host, port, summonerName);

        // ✅ CRÍTICO: Registrar jogador no SessionRegistry para que receba broadcasts
        sessionRegistry.registerPlayer(summonerName, session.getId());

        log.info("✅ [WS] Conexão LCU registrada: '{}' (session: {}, {}:{})",
                summonerName, session.getId(), host, port);

        // ✅ NOVO: Buscar dados de ranked e salvar MMR no banco IMEDIATAMENTE
        CompletableFuture.runAsync(() -> {
            try {
                log.info("📊 [WS] Buscando dados de ranked para inicializar MMR: {}", summonerName);

                // Buscar ranked stats via RPC gateway
                JsonNode rankedData = webSocketService.requestLcu(
                        session.getId(),
                        "GET",
                        "/lol-ranked/v1/current-ranked-stats",
                        null,
                        5000);

                log.info("🔍 [WS] rankedData recebido: tipo={}",
                        rankedData != null ? rankedData.getNodeType() : "NULL");

                // ✅ CRÍTICO: rankedData vem em formato diferente do esperado
                // Pode vir como: { status: 200, body: { queues: [...] } } (wrapper)
                // Ou como: { queues: [...] } (direto)
                // Ou como: { highestRankedEntry: {...} } (formato alternativo)

                JsonNode actualData = rankedData;

                // Se vier wrapped em { status, body }, extrair body
                if (rankedData != null && rankedData.has("body")) {
                    actualData = rankedData.get("body");
                    log.info("🔍 [WS] Extraído 'body' do rankedData");
                }

                JsonNode soloQueueData = null;

                // Tentar formato 1: queues array
                if (actualData != null && actualData.has("queues") && actualData.get("queues").isArray()) {
                    log.info("🔍 [WS] Formato 'queues' detectado");
                    for (JsonNode queue : actualData.get("queues")) {
                        if (queue.has("queueType") && queue.get("queueType").asText().equals("RANKED_SOLO_5x5")) {
                            soloQueueData = queue;
                            break;
                        }
                    }
                }
                // Tentar formato 2: highestRankedEntry
                else if (actualData != null && actualData.has("highestRankedEntry")) {
                    log.info("🔍 [WS] Formato 'highestRankedEntry' detectado");
                    JsonNode entry = actualData.get("highestRankedEntry");
                    if (entry.has("queueType") && entry.get("queueType").asText().equals("RANKED_SOLO_5x5")) {
                        soloQueueData = entry;
                    }
                }

                if (soloQueueData != null) {
                    String tier = soloQueueData.get("tier").asText("UNRANKED");
                    String division = soloQueueData.get("division").asText("");
                    int lp = soloQueueData.get("leaguePoints").asInt(0);
                    int wins = soloQueueData.get("wins").asInt(0);
                    int losses = soloQueueData.get("losses").asInt(0);

                    log.info("🎯 [WS] Rank detectado: {} {} ({}LP, {}W/{}L)",
                            tier, division, lp, wins, losses);

                    // Calcular MMR (mesma fórmula do PlayerController)
                    int currentMmr = calculateMMRFromRank(tier, division, lp);
                    log.info("🔢 [WS] MMR calculado: {}", currentMmr);

                    // Salvar no banco via PlayerService
                    playerService.createOrUpdatePlayerOnLogin(
                            summonerName,
                            "br1",
                            currentMmr,
                            null,
                            null);

                    log.info("✅ [WS] Player atualizado no banco com MMR calculado: {}", currentMmr);
                } else {
                    log.warn("⚠️ [WS] Não foi possível extrair dados de ranked solo");
                }
            } catch (Exception e) {
                log.warn("⚠️ [WS] Erro ao buscar ranked/salvar MMR (não crítico): {}", e.getMessage());
            }
        });

        // ✅ CORREÇÃO: Enviar confirmação de registro para TODAS as sessões do jogador
        // (não apenas para a sessão gateway, mas também para a sessão do frontend)
        String confirmationMessage = "{\"type\":\"lcu_connection_registered\",\"success\":true,\"summonerName\":\""
                + summonerName + "\"}";

        // Enviar para a sessão atual (gateway do Electron)
        session.sendMessage(new TextMessage(confirmationMessage));

        // Enviar para todas as outras sessões do mesmo jogador (frontend)
        try {
            Map<String, Object> data = new HashMap<>();
            data.put(FIELD_SUMMONER_NAME, summonerName);
            webSocketService.sendToPlayers("lcu_connection_registered", data, List.of(summonerName));
            log.info("📡 [WS] Notificação lcu_connection_registered enviada para todas as sessões de: {}",
                    summonerName);
        } catch (Exception e) {
            log.warn("⚠️ [WS] Erro ao enviar broadcast de lcu_connection_registered: {}", e.getMessage());
        }
    }

    /**
     * Calcula MMR baseado em tier, division e LP
     */
    private int calculateMMRFromRank(String tier, String division, int lp) {
        int baseMmr = switch (tier.toUpperCase()) {
            case "IRON" -> 800;
            case "BRONZE" -> 1000;
            case "SILVER" -> 1200;
            case "GOLD" -> 1400;
            case "PLATINUM" -> 1700;
            case "EMERALD" -> 2000;
            case "DIAMOND" -> 2300;
            case "MASTER" -> 2600;
            case "GRANDMASTER" -> 2900;
            case "CHALLENGER" -> 3200;
            default -> 1200;
        };

        int rankBonus = switch (division.toUpperCase()) {
            case "IV" -> 0;
            case "III" -> 50;
            case "II" -> 100;
            case "I" -> 150;
            default -> 0;
        };

        int lpBonus = (int) Math.round(lp * 0.8);

        return baseMmr + rankBonus + lpBonus;
    }

    private void handleLcuStatus(WebSocketSession session, JsonNode root) throws IOException {
        JsonNode data = root.path("data");
        if (data.isMissingNode()) {
            session.sendMessage(
                    new TextMessage("{\"type\":\"lcu_status_ack\",\"success\":false,\"error\":\"data required\"}"));
            return;
        }
        lastLcuStatus.put(session.getId(), data);
        if (log.isDebugEnabled()) {
            log.debug("[WS] LCU status from {}: {}", session.getId(), data.toString());
        }
        session.sendMessage(new TextMessage("{\"type\":\"lcu_status_ack\",\"success\":true}"));
    }

    /**
     * Handler para resposta de lcu_request vinda do Electron main process
     * Encaminha para o MatchmakingWebSocketService que gerencia o RPC
     */
    private void handleLcuResponse(WebSocketSession session, JsonNode root) {
        String requestId = root.path("id").asText(null);
        if (requestId == null || requestId.isEmpty()) {
            log.warn("⚠️ [WS] lcu_response sem id recebida de session {}", session.getId());
            return;
        }

        log.info("📬 [WS] lcu_response id={} recebida de session {}, encaminhando para MatchmakingWebSocketService",
                requestId, session.getId());

        // Encaminhar para o serviço que gerencia as requisições pendentes
        webSocketService.handleLcuResponse(root);
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

        // ✅ CRÍTICO: Registrar jogador no SessionRegistry para que receba broadcasts
        String summonerName = playerData.path(FIELD_SUMMONER_NAME).asText(null);
        if (summonerName != null && !summonerName.isEmpty()) {
            sessionRegistry.registerPlayer(summonerName, session.getId());
        }

        session.sendMessage(new TextMessage("{\"type\":\"player_identified\",\"success\":true}"));
        // tentar reemitir match_found se estiver em aceitação
        long queuePlayerId = playerData.path("queuePlayerId").asLong(-1);
        if (queuePlayerId > 0) {
            matchmakingOrchestrator.reemitIfInAcceptance(queuePlayerId, session);
        }
        // reemitir snapshot de draft se player estiver em uma
        if (summonerName != null) {
            draftFlowService.reemitIfPlayerInDraft(summonerName, session);
            draftFlowService.reemitIfPlayerGameReady(summonerName, session);
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        identifiedPlayers.remove(session.getId());
        lastLcuStatus.remove(session.getId());
        sessionRegistry.remove(session.getId());
        webSocketService.removeSession(session.getId());

        // 🗑️ Remover conexão LCU do registry
        lcuConnectionRegistry.unregisterBySession(session.getId());

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
