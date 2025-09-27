package br.com.lolmatchmaking.backend.websocket;

import br.com.lolmatchmaking.backend.dto.MatchInfoDTO;
import br.com.lolmatchmaking.backend.dto.QueuePlayerInfoDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchmakingWebSocketService extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ClientInfo> clientInfo = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastHeartbeat = new ConcurrentHashMap<>();
    // Track scheduled heartbeat monitor tasks so they can be cancelled when session closes
    private final Map<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final Map<String, List<String>> pendingEvents = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // New: pending LCU requests by requestId
    private final Map<String, CompletableFuture<JsonNode>> pendingLcuRequests = new ConcurrentHashMap<>();

    // Configurações
    private static final long HEARTBEAT_INTERVAL = 30000; // 30 segundos
    private static final long HEARTBEAT_TIMEOUT = 60000; // 60 segundos
    private static final int MAX_PENDING_EVENTS = 100;
    private static final long LCU_RPC_TIMEOUT_MS = 5000; // timeout padrão para RPC LCU

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);

        // Inicializar informações do cliente
        clientInfo.put(sessionId, new ClientInfo(sessionId, Instant.now()));
        lastHeartbeat.put(sessionId, Instant.now());
        pendingEvents.put(sessionId, new ArrayList<>());

        log.info("🔌 Cliente conectado: {} (Total: {})", sessionId, sessions.size());

        // Enviar eventos pendentes
        sendPendingEvents(sessionId);

        // Iniciar monitoramento de heartbeat
        startHeartbeatMonitoring(sessionId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        clientInfo.remove(sessionId);
        lastHeartbeat.remove(sessionId);
        pendingEvents.remove(sessionId);
        // Cancel heartbeat monitoring task if present
        try {
            ScheduledFuture<?> f = heartbeatTasks.remove(sessionId);
            if (f != null) f.cancel(true);
        } catch (Exception e) {
            log.warn("⚠️ Falha ao cancelar heartbeat task para {}", sessionId, e);
        }
        log.info("🔌 Cliente desconectado: {} (Status: {})", sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("❌ Erro de transporte WebSocket: {}", session.getId(), exception);
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        String payload = message.getPayload();

        try {
            JsonNode jsonMessage = objectMapper.readTree(payload);
            String messageType = jsonMessage.get("type").asText();

            switch (messageType) {
                case "heartbeat":
                    handleHeartbeat(sessionId);
                    break;
                case "ping":
                    // Respond with pong to keep client alive
                    try { sendMessage(sessionId, "pong", Map.of("ts", System.currentTimeMillis())); } catch (Exception e) { }
                    break;
                case "pong":
                    // Client acknowledges a server ping; treat as heartbeat
                    lastHeartbeat.put(sessionId, Instant.now());
                    break;
                case "identify":
                    handleIdentify(sessionId, jsonMessage);
                    break;
                case "join_queue":
                    handleJoinQueue(sessionId, jsonMessage);
                    break;
                case "leave_queue":
                    handleLeaveQueue(sessionId, jsonMessage);
                    break;
                case "accept_match":
                    handleAcceptMatch(sessionId, jsonMessage);
                    break;
                case "decline_match":
                    handleDeclineMatch(sessionId, jsonMessage);
                    break;
                case "draft_action":
                    handleDraftAction(sessionId, jsonMessage);
                    break;
                case "reconnect":
                    handleReconnect(sessionId, jsonMessage);
                    break;
                case "lcu_response":
                    handleLcuResponse(jsonMessage);
                    break;
                default:
                    log.warn("⚠️ Tipo de mensagem desconhecido: {}", messageType);
            }
        } catch (Exception e) {
            log.error("❌ Erro ao processar mensagem WebSocket", e);
            sendError(sessionId, "INVALID_MESSAGE", "Mensagem inválida");
        }
    }

    private void handleLcuResponse(JsonNode jsonMessage) {
        // Expected shape: { type: 'lcu_response', id: 'uuid', status: 200, body: {...} }
        try {
            String id = jsonMessage.has("id") ? jsonMessage.get("id").asText() : null;
            if (id == null) {
                log.warn("LCU response sem id");
                return;
            }
            CompletableFuture<JsonNode> fut = pendingLcuRequests.remove(id);
            if (fut == null) {
                log.warn("Sem request pendente para LCU id={}", id);
                return;
            }
            JsonNode status = jsonMessage.has("status") ? jsonMessage.get("status") : null;
            JsonNode body = jsonMessage.has("body") ? jsonMessage.get("body") : null;
            // Build a response node combining status and body
            ObjectNode resp = objectMapper.createObjectNode();
            if (status != null) resp.set("status", status);
            if (body != null) resp.set("body", body);
            fut.complete(resp);
        } catch (Exception e) {
            log.error("Erro ao processar lcu_response", e);
        }
    }

    /**
     * Envia uma request LCU para o cliente especificado e espera pela resposta (RPC)
     */
    public JsonNode requestLcu(String sessionId, String method, String path, JsonNode body, long timeoutMs) throws Exception {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("Sessão WebSocket não disponível: " + sessionId);
        }
        String id = UUID.randomUUID().toString();
        ObjectNode req = objectMapper.createObjectNode();
        req.put("type", "lcu_request");
        req.put("id", id);
        req.put("method", method == null ? "GET" : method);
        req.put("path", path == null ? "/" : path);
        if (body != null) req.set("body", body);

        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pendingLcuRequests.put(id, fut);

        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(req)));

        try {
            JsonNode resp = fut.get(timeoutMs <= 0 ? LCU_RPC_TIMEOUT_MS : timeoutMs, TimeUnit.MILLISECONDS);
            return resp;
        } catch (TimeoutException te) {
            pendingLcuRequests.remove(id);
            throw new RuntimeException("Timeout aguardando resposta LCU do cliente", te);
        } finally {
            pendingLcuRequests.remove(id);
        }
    }

    /**
     * Identifica um cliente
     */
    private void handleIdentify(String sessionId, JsonNode data) {
        try {
            String playerId = data.get("playerId").asText();
            String summonerName = data.get("summonerName").asText();

            ClientInfo info = clientInfo.get(sessionId);
            if (info != null) {
                info.setPlayerId(playerId);
                info.setSummonerName(summonerName);
                info.setIdentified(true);

                log.info("🆔 Cliente identificado: {} ({})", summonerName, playerId);
                sendMessage(sessionId, "identified", Map.of("success", true));
            }
        } catch (Exception e) {
            log.error("❌ Erro ao identificar cliente", e);
            sendError(sessionId, "IDENTIFY_FAILED", "Falha na identificação");
        }
    }

    /**
     * Processa heartbeat
     */
    private void handleHeartbeat(String sessionId) {
        lastHeartbeat.put(sessionId, Instant.now());
        sendMessage(sessionId, "heartbeat_ack", Map.of("timestamp", System.currentTimeMillis()));
    }

    /**
     * Processa join queue
     */
    private void handleJoinQueue(String sessionId, JsonNode data) {
        // Implementar lógica de join queue
        log.info("🎮 Cliente {} entrou na fila", sessionId);
        sendMessage(sessionId, "queue_joined", Map.of("success", true));
    }

    /**
     * Processa leave queue
     */
    private void handleLeaveQueue(String sessionId, JsonNode data) {
        // Implementar lógica de leave queue
        log.info("🎮 Cliente {} saiu da fila", sessionId);
        sendMessage(sessionId, "queue_left", Map.of("success", true));
    }

    /**
     * Processa accept match
     */
    private void handleAcceptMatch(String sessionId, JsonNode data) {
        // Implementar lógica de accept match
        log.info("✅ Cliente {} aceitou partida", sessionId);
        sendMessage(sessionId, "match_accepted", Map.of("success", true));
    }

    /**
     * Processa decline match
     */
    private void handleDeclineMatch(String sessionId, JsonNode data) {
        // Implementar lógica de decline match
        log.info("❌ Cliente {} recusou partida", sessionId);
        sendMessage(sessionId, "match_declined", Map.of("success", true));
    }

    /**
     * Processa draft action
     */
    private void handleDraftAction(String sessionId, JsonNode data) {
        // Implementar lógica de draft action
        log.info("🎯 Cliente {} executou ação de draft", sessionId);
        sendMessage(sessionId, "draft_action_processed", Map.of("success", true));
    }

    /**
     * Processa reconexão
     */
    private void handleReconnect(String sessionId, JsonNode data) {
        try {
            String playerId = data.get("playerId").asText();

            // Enviar eventos perdidos
            sendPendingEvents(sessionId);

            log.info("🔄 Cliente {} reconectado", playerId);
            sendMessage(sessionId, "reconnected", Map.of("success", true));
        } catch (Exception e) {
            log.error("❌ Erro ao processar reconexão", e);
            sendError(sessionId, "RECONNECT_FAILED", "Falha na reconexão");
        }
    }

    /**
     * Inicia monitoramento de heartbeat
     */
    private void startHeartbeatMonitoring(String sessionId) {
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                Instant lastHeartbeatTime = lastHeartbeat.get(sessionId);
                if (lastHeartbeatTime != null &&
                        Instant.now().minusMillis(HEARTBEAT_TIMEOUT).isAfter(lastHeartbeatTime)) {

                    log.warn("💔 Heartbeat timeout para cliente: {}", sessionId);
                    closeSession(sessionId);
                }
                else {
                    // Probe client with a ping to elicit a pong/heartbeat response
                    try {
                        sendMessage(sessionId, "ping", Map.of("ts", System.currentTimeMillis()));
                    } catch (Exception e) {
                        log.debug("⚠️ Falha ao enviar ping para {}: {}", sessionId, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("❌ Erro no monitoramento de heartbeat", e);
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);

        // Keep reference so we can cancel on close
        heartbeatTasks.put(sessionId, future);
    }

    /**
     * Envia eventos pendentes para um cliente
     */
    private void sendPendingEvents(String sessionId) {
        List<String> events = pendingEvents.get(sessionId);
        if (events != null && !events.isEmpty()) {
            for (String event : events) {
                sendMessage(sessionId, event, null);
            }
            events.clear();
        }
    }

    /**
     * Adiciona evento pendente
     */
    private void addPendingEvent(String sessionId, String event) {
        List<String> events = pendingEvents.get(sessionId);
        if (events != null) {
            if (events.size() >= MAX_PENDING_EVENTS) {
                events.remove(0); // Remove o mais antigo
            }
            events.add(event);
        }
    }

    /**
     * Fecha uma sessão
     */
    private void closeSession(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.error("❌ Erro ao fechar sessão", e);
            }
        }
        // Also cancel heartbeat task if present
        try {
            ScheduledFuture<?> f = heartbeatTasks.remove(sessionId);
            if (f != null) f.cancel(true);
        } catch (Exception e) {
            log.warn("⚠️ Erro ao cancelar heartbeat task no closeSession para {}", sessionId, e);
        }
    }

    /**
     * Envia mensagem para um cliente específico
     */
    public void sendMessage(String sessionId, String type, Object data) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                Map<String, Object> message = new HashMap<>();
                message.put("type", type);
                message.put("timestamp", System.currentTimeMillis());
                if (data != null) {
                    message.put("data", data);
                }

                String jsonMessage = objectMapper.writeValueAsString(message);
                session.sendMessage(new TextMessage(jsonMessage));
            } catch (Exception e) {
                log.error("❌ Erro ao enviar mensagem para {}", sessionId, e);
                // Adicionar como evento pendente
                addPendingEvent(sessionId, type);
            }
        }
    }

    /**
     * Envia erro para um cliente
     */
    private void sendError(String sessionId, String errorCode, String message) {
        Map<String, Object> error = Map.of(
                "error", errorCode,
                "message", message);
        sendMessage(sessionId, "error", error);
    }

    /**
     * Broadcast para todos os clientes
     */
    public void broadcastToAll(String eventType, Map<String, Object> data) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", eventType);
            message.put("data", data);
            message.put("timestamp", System.currentTimeMillis());

            String jsonMessage = objectMapper.writeValueAsString(message);
            broadcastToAll(jsonMessage);
        } catch (Exception e) {
            log.error("❌ Erro ao fazer broadcast", e);
        }
    }

    /**
     * Broadcast de atualização da fila
     */
    public void broadcastQueueUpdate(List<QueuePlayerInfoDTO> queueStatus) {
        try {
            Map<String, Object> message = Map.of(
                    "type", "queue_update",
                    "data", queueStatus,
                    "timestamp", System.currentTimeMillis());

            String jsonMessage = objectMapper.writeValueAsString(message);
            broadcastToAll(jsonMessage);

        } catch (Exception e) {
            log.error("❌ Erro ao fazer broadcast da fila", e);
        }
    }

    /**
     * Broadcast de partida encontrada
     */
    public void broadcastMatchFound(MatchInfoDTO matchInfo) {
        try {
            Map<String, Object> message = Map.of(
                    "type", "match_found",
                    "data", matchInfo,
                    "timestamp", System.currentTimeMillis());

            String jsonMessage = objectMapper.writeValueAsString(message);
            broadcastToAll(jsonMessage);

        } catch (Exception e) {
            log.error("❌ Erro ao fazer broadcast de partida encontrada", e);
        }
    }

    /**
     * Broadcast para todos os clientes conectados
     */
    private void broadcastToAll(String message) {
        sessions.values().removeIf(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                    return false;
                }
            } catch (Exception e) {
                log.error("❌ Erro ao enviar mensagem", e);
            }
            return true; // Remove sessões com erro
        });
    }

    /**
     * Obtém estatísticas do WebSocket
     */
    public Map<String, Object> getWebSocketStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalConnections", sessions.size());
        stats.put("identifiedClients", clientInfo.values().stream()
                .mapToInt(info -> info.isIdentified() ? 1 : 0)
                .sum());
        stats.put("lastHeartbeat", lastHeartbeat);
        return stats;
    }

    /**
     * Adiciona sessão (compatibilidade)
     */
    public void addSession(String sessionId, WebSocketSession session) {
        sessions.put(sessionId, session);
        log.debug("Sessão WebSocket adicionada: {}", sessionId);
    }

    /**
     * Remove sessão (compatibilidade)
     */
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        log.debug("Sessão WebSocket removida: {}", sessionId);
    }

    /**
     * Obtém contagem de sessões ativas
     */
    public int getActiveSessionsCount() {
        return sessions.size();
    }

    /**
     * Broadcast de partida aceita
     */
    public void broadcastMatchAccepted(String matchId) {
        Map<String, Object> data = Map.of("matchId", matchId);
        broadcastToAll("match_accepted", data);
    }

    /**
     * Broadcast de partida cancelada
     */
    public void broadcastMatchCancelled(String matchId, String reason) {
        Map<String, Object> data = Map.of("matchId", matchId, "reason", reason);
        broadcastToAll("match_cancelled", data);
    }

    /**
     * Classe para informações do cliente
     */
    public static class ClientInfo {
        private String sessionId;
        private String playerId;
        private String summonerName;
        private Instant connectedAt;
        private boolean identified;

        public ClientInfo(String sessionId, Instant connectedAt) {
            this.sessionId = sessionId;
            this.connectedAt = connectedAt;
            this.identified = false;
        }

        // Getters e Setters
        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getPlayerId() {
            return playerId;
        }

        public void setPlayerId(String playerId) {
            this.playerId = playerId;
        }

        public String getSummonerName() {
            return summonerName;
        }

        public void setSummonerName(String summonerName) {
            this.summonerName = summonerName;
        }

        public Instant getConnectedAt() {
            return connectedAt;
        }

        public void setConnectedAt(Instant connectedAt) {
            this.connectedAt = connectedAt;
        }

        public boolean isIdentified() {
            return identified;
        }

        public void setIdentified(boolean identified) {
            this.identified = identified;
        }
    }
}

