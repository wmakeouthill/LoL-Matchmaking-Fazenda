package br.com.lolmatchmaking.backend.websocket;

import br.com.lolmatchmaking.backend.dto.MatchInfoDTO;
import br.com.lolmatchmaking.backend.dto.QueuePlayerInfoDTO;
import br.com.lolmatchmaking.backend.service.redis.RedisWebSocketEventService;
import br.com.lolmatchmaking.backend.service.redis.RedisWebSocketSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import br.com.lolmatchmaking.backend.service.LCUService;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ⚠️ MIGRAÇÃO PARCIAL PARA REDIS - EM PROGRESSO
 * 
 * ANTES: 7 ConcurrentHashMaps perdiam dados em reinícios
 * STATUS: Tem Redis mas ainda usa 4 HashMaps @Deprecated ativamente
 * 
 * MIGRAÇÃO REDIS (PARCIAL):
 * - ✅ Sessions → RedisWebSocketSessionService (OK)
 * - ⚠️ clientInfo → RedisWebSocketSessionService (AINDA USA HashMap - 20 usos)
 * - ⚠️ lastHeartbeat → RedisWebSocketSessionService (AINDA USA HashMap - 2
 * usos)
 * - ⚠️ pendingEvents → RedisWebSocketEventService (AINDA USA HashMap - 2 usos)
 * - ⚠️ pendingLcuRequests → RedisWebSocketEventService (AINDA USA HashMap - 5
 * usos)
 * - ⚠️ lcuRequestSession → RedisWebSocketEventService (AINDA USA HashMap - 2
 * usos)
 * - ✅ heartbeatTasks → Local (ScheduledFuture não serializável - OK manter)
 * 
 * TODO: Completar migração removendo os 4 HashMaps @Deprecated que ainda são
 * usados
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchmakingWebSocketService extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final SessionRegistry sessionRegistry;

    // ✅ NOVO: Redis services
    private final RedisWebSocketSessionService redisWSSession;
    private final RedisWebSocketEventService redisWSEvent;

    // Cache local (WebSocketSession não é serializável)
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // ✅ DEPRECIADO: Migrado para Redis
    /**
     * @deprecated Substituído por RedisWebSocketSessionService (chave:
     *             ws:client:{sessionId})
     */
    @Deprecated(forRemoval = true)
    private final Map<String, ClientInfo> clientInfo = new ConcurrentHashMap<>();

    /**
     * @deprecated Substituído por RedisWebSocketSessionService.updateHeartbeat()
     */
    @Deprecated(forRemoval = true)
    private final Map<String, Instant> lastHeartbeat = new ConcurrentHashMap<>();

    // Heartbeat tasks (não precisam persistir)
    private final Map<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();

    /**
     * @deprecated Substituído por RedisWebSocketEventService (chave:
     *             ws:pending:{sessionId})
     */
    @Deprecated(forRemoval = true)
    private final Map<String, List<String>> pendingEvents = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /**
     * @deprecated Substituído por RedisWebSocketEventService (chave:
     *             ws:lcu_request:{requestId})
     */
    @Deprecated(forRemoval = true)
    private final Map<String, CompletableFuture<JsonNode>> pendingLcuRequests = new ConcurrentHashMap<>();

    /**
     * @deprecated Substituído por RedisWebSocketEventService (chave:
     *             ws:lcu_request:{requestId})
     */
    @Deprecated(forRemoval = true)
    private final Map<String, String> lcuRequestSession = new ConcurrentHashMap<>();

    /**
     * Obtém o LCUService quando necessário para evitar dependência circular
     */
    private LCUService getLcuService() {
        return applicationContext.getBean(LCUService.class);
    }

    /**
     * Obtém o MatchFoundService quando necessário para evitar dependência circular
     */
    private br.com.lolmatchmaking.backend.service.MatchFoundService getMatchFoundService() {
        return applicationContext.getBean(br.com.lolmatchmaking.backend.service.MatchFoundService.class);
    }

    /**
     * Obtém o DiscordService quando necessário
     */
    private Object getDiscordService() {
        try {
            return applicationContext.getBean("discordService");
        } catch (Exception e) {
            log.warn("DiscordService não disponível: {}", e.getMessage());
            return null;
        }
    }

    // Configurações
    private static final long HEARTBEAT_INTERVAL = 60000; // 60 segundos - menos agressivo
    private static final long HEARTBEAT_TIMEOUT = 120000; // 120 segundos - mais tolerante
    private static final int MAX_PENDING_EVENTS = 100;
    private static final long LCU_RPC_TIMEOUT_MS = 5000; // timeout padrão para RPC LCU

    /**
     * ✅ MIGRADO PARA REDIS: Conexão WebSocket estabelecida
     * 
     * CRÍTICO: Envia eventos pendentes do Redis após reconexão.
     * Garante que eventos como "match_found" não sejam perdidos.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);

        // ✅ REDIS ONLY: Inicializar heartbeat no Redis (sem HashMap local!)
        redisWSSession.updateHeartbeat(sessionId);

        log.info("🔌 Cliente conectado: {} (Total: {})", sessionId, sessions.size());

        // ✅ REDIS ONLY: Enviar eventos pendentes (reconexão)
        // Single source of truth - sem fallback HashMap!
        List<RedisWebSocketEventService.PendingEvent> pendingFromRedis = redisWSEvent.getPendingEvents(sessionId);

        if (!pendingFromRedis.isEmpty()) {
            log.info("📬 [REDIS] {} eventos pendentes encontrados para sessão: {}",
                    pendingFromRedis.size(), sessionId);

            for (RedisWebSocketEventService.PendingEvent event : pendingFromRedis) {
                try {
                    sendMessage(sessionId, event.getEventType(), event.getPayload());
                    log.info("✅ [REDIS] Evento pendente enviado: {} → {}", sessionId, event.getEventType());
                } catch (Exception e) {
                    log.error("❌ [REDIS] Erro ao enviar evento pendente: {}", event.getEventType(), e);
                }
            }

            // Limpar eventos após envio bem-sucedido
            redisWSEvent.clearPendingEvents(sessionId);
        }

        // Iniciar monitoramento de heartbeat
        startHeartbeatMonitoring(sessionId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);

        // ✅ REDIS: Limpeza automática por TTL (não precisa remover manualmente)
        // Cancel heartbeat monitoring task if present
        try {
            ScheduledFuture<?> f = heartbeatTasks.remove(sessionId);
            if (f != null)
                f.cancel(true);
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
                    try {
                        sendMessage(sessionId, "pong", Map.of("ts", System.currentTimeMillis()));
                    } catch (Exception e) {
                    }
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
                case "lcu_status":
                    handleLcuStatus(sessionId, jsonMessage);
                    break;
                case "discord_users":
                    // Discord users update - just acknowledge, no special handling needed
                    log.debug("📡 Discord users update received for session: {}", sessionId);
                    break;
                case "discord_users_online":
                    // Discord users online update - just acknowledge, no special handling needed
                    log.debug("📡 Discord users online update received for session: {}", sessionId);
                    break;
                case "get_discord_users":
                    handleGetDiscordUsers(sessionId);
                    break;
                default:
                    log.warn("⚠️ Tipo de mensagem desconhecido: {}", messageType);
            }
        } catch (Exception e) {
            log.error("❌ Erro ao processar mensagem WebSocket", e);
            sendError(sessionId, "INVALID_MESSAGE", "Mensagem inválida");
        }
    }

    private void handleLcuStatus(String sessionId, JsonNode jsonMessage) {
        try {
            JsonNode data = jsonMessage.has("data") ? jsonMessage.get("data") : null;
            if (data == null) {
                sendMessage(sessionId, "lcu_status_ack", Map.of("success", false, "error", "data required"));
                return;
            }
            int status = data.has("status") && data.get("status").isInt() ? data.get("status").asInt() : -1;
            JsonNode body = data.has("body") ? data.get("body") : null;

            if (status == 200) {
                ClientInfo info = clientInfo.get(sessionId);
                if (info != null && info.getLcuHost() != null && info.getLcuPort() > 0) {
                    String summonerId = null;
                    try {
                        summonerId = extractSummonerId(body);
                    } catch (Exception ignored) {
                    }
                    log.info("LCU status from gateway session={} summonerId={}", sessionId, summonerId);
                    getLcuService().markConnectedFromGateway(info.getLcuHost(), info.getLcuPort(),
                            info.getLcuProtocol(),
                            info.getLcuPassword(), summonerId);
                    sendMessage(sessionId, "lcu_status_ack", Map.of("success", true));
                    log.info("LCU status accepted from gateway session={}", sessionId);
                    return;
                }
            }

            sendMessage(sessionId, "lcu_status_ack", Map.of("success", false));
        } catch (Exception e) {
            log.error("Erro ao processar lcu_status", e);
            sendMessage(sessionId, "lcu_status_ack", Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Handle incoming lcu_response from Electron main process gateway.
     * Called by CoreWebSocketHandler when it receives lcu_response messages.
     */
    public void handleLcuResponse(JsonNode jsonMessage) {
        // Expected shape: { type: 'lcu_response', id: 'uuid', status: 200, body: {...}
        // }
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
            if (status != null)
                resp.set("status", status);
            if (body != null)
                resp.set("body", body);
            fut.complete(resp);

            // If successful response, mark backend LCU as connected using client-provided
            // lockfile info
            try {
                int code = -1;
                if (status != null && status.isInt())
                    code = status.asInt();
                if (code == 200) {
                    String sess = lcuRequestSession.remove(id);
                    if (sess != null) {
                        ClientInfo info = clientInfo.get(sess);
                        if (info != null && info.getLcuHost() != null) {
                            String summonerId = null;
                            try {
                                summonerId = extractSummonerId(body);
                            } catch (Exception ignored) {
                            }
                            log.info("LCU response from session={} id={} summonerId={}", sess, id, summonerId);
                            getLcuService().markConnectedFromGateway(info.getLcuHost(), info.getLcuPort(),
                                    info.getLcuProtocol(), info.getLcuPassword(), summonerId);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Erro marcando LCU conectado via gateway: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("Erro ao processar lcu_response", e);
        }
    }

    /**
     * Tenta extrair um identificador de summoner de diferentes formatos de resposta
     */
    private String extractSummonerId(JsonNode node) {
        if (node == null || node.isNull())
            return null;
        try {
            // If it's a text node, return it
            if (node.isTextual())
                return node.asText();

            // Common fields used by LCU or other endpoints
            if (node.has("summonerId") && !node.get("summonerId").isNull())
                return node.get("summonerId").asText();
            if (node.has("id") && !node.get("id").isNull())
                return node.get("id").asText();
            if (node.has("puuid") && !node.get("puuid").isNull())
                return node.get("puuid").asText();
            if (node.has("accountId") && !node.get("accountId").isNull())
                return node.get("accountId").asText();
            if (node.has("displayName") && !node.get("displayName").isNull())
                return node.get("displayName").asText();

            // Some LCU responses may not populate displayName but provide gameName and
            // tagLine separately. Construct displayName as gameName#tagLine in that
            // case so other parts of the system can use a consistent identifier.
            if (node.has("gameName") && !node.get("gameName").isNull() && node.has("tagLine")
                    && !node.get("tagLine").isNull()) {
                try {
                    String gn = node.get("gameName").asText();
                    String tl = node.get("tagLine").asText();
                    if (gn != null && !gn.isBlank() && tl != null && !tl.isBlank()) {
                        return gn + "#" + tl;
                    }
                } catch (Exception ignored) {
                }
            }

            // Recurse into child objects/arrays to try to find an id
            for (JsonNode child : node) {
                String s = extractSummonerId(child);
                if (s != null)
                    return s;
            }
        } catch (Exception e) {
            // best-effort only
            log.debug("Erro extraindo summonerId: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Envia uma request LCU para o cliente especificado e espera pela resposta
     * (RPC)
     */
    public JsonNode requestLcu(String sessionId, String method, String path, JsonNode body, long timeoutMs)
            throws Exception {
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
        if (body != null)
            req.set("body", body);

        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pendingLcuRequests.put(id, fut);

        // Log outgoing RPC to help diagnostics
        try {
            log.info("Sending lcu_request id={} method={} path={} -> session={}", id, method, path, sessionId);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(req)));
        } catch (Exception e) {
            pendingLcuRequests.remove(id);
            log.error("Failed to send lcu_request id={} to session={}: {}", id, sessionId, e.getMessage());
            throw e;
        }

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
            // Support flexible identify payloads. Some clients send fields at the
            // root (playerId, summonerName, data:{ lockfile }) while others may
            // nest them under data (data: { playerId, summonerName, lockfile }).
            String playerId = null;
            String summonerName = null;

            if (data.has("playerId") && !data.get("playerId").isNull()) {
                playerId = data.get("playerId").asText(null);
            } else if (data.has("data") && data.get("data") != null && data.get("data").has("playerId")) {
                playerId = data.get("data").get("playerId").asText(null);
            }

            if (data.has("summonerName") && !data.get("summonerName").isNull()) {
                summonerName = data.get("summonerName").asText(null);
            } else if (data.has("data") && data.get("data") != null && data.get("data").has("summonerName")) {
                summonerName = data.get("data").get("summonerName").asText(null);
            }

            ClientInfo info = clientInfo.get(sessionId);
            if (info != null) {
                if (playerId != null)
                    info.setPlayerId(playerId);
                if (summonerName != null)
                    info.setSummonerName(summonerName);
                info.setIdentified(true);

                // If identify included lockfile data, capture it for later.
                // Accept multiple shapes: message.data.lockfile, message.data.data.lockfile,
                // or message.lockfile (less common).
                try {
                    JsonNode lf = null;
                    if (data.has("data") && data.get("data") != null) {
                        JsonNode d = data.get("data");
                        if (d.has("lockfile"))
                            lf = d.get("lockfile");
                        else if (d.has("data") && d.get("data") != null && d.get("data").has("lockfile"))
                            lf = d.get("data").get("lockfile");
                    }
                    if (lf == null && data.has("lockfile"))
                        lf = data.get("lockfile");

                    if (lf != null) {
                        if (lf.has("host"))
                            info.setLcuHost(lf.get("host").asText(null));
                        if (lf.has("port"))
                            info.setLcuPort(lf.get("port").asInt(0));
                        if (lf.has("protocol"))
                            info.setLcuProtocol(lf.get("protocol").asText(null));
                        if (lf.has("password"))
                            info.setLcuPassword(lf.get("password").asText(null));
                        // Debug: log parsed lockfile info
                        log.debug("handleIdentify: parsed lockfile for session={} host={} port={} protocol={}",
                                sessionId, info.getLcuHost(), info.getLcuPort(), info.getLcuProtocol());

                        // ✅ NOVO: Configurar LCU no banco de dados para este jogador
                        if (summonerName != null && info.getLcuPort() != 0) {
                            try {
                                getLcuService().configureLCUForPlayer(
                                        summonerName,
                                        info.getLcuPort(),
                                        info.getLcuProtocol() != null ? info.getLcuProtocol() : "https",
                                        info.getLcuPassword());
                                log.info("✅ [WebSocket] LCU configurado para {} na tabela players", summonerName);
                            } catch (Exception ex) {
                                log.error("❌ [WebSocket] Erro ao configurar LCU no banco para {}: {}",
                                        summonerName, ex.getMessage());
                            }
                        }
                    }
                } catch (Exception ignored) {
                }

                log.info("🆔 Cliente identificado: {} ({})", info.getSummonerName(), info.getPlayerId());

                // ✅ NOVO: Registrar jogador no SessionRegistry para envio específico
                if (summonerName != null) {
                    sessionRegistry.registerPlayer(summonerName, sessionId);
                    log.info("✅ [WebSocket] Jogador {} registrado na sessão {}", summonerName, sessionId);
                }

                sendMessage(sessionId, "identified", Map.of("success", true));
            }
        } catch (Exception e) {
            log.error("❌ Erro ao identificar cliente", e);
            sendError(sessionId, "IDENTIFY_FAILED", "Falha na identificação");
        }
    }

    /**
     * ✅ MIGRADO PARA REDIS: Processa heartbeat
     * Atualiza timestamp no Redis e extende TTL da sessão.
     */
    private void handleHeartbeat(String sessionId) {
        // ✅ REDIS ONLY: Atualizar heartbeat e extender TTL (sem HashMap!)
        redisWSSession.updateHeartbeat(sessionId);

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
    /**
     * Processa accept match
     */
    private void handleAcceptMatch(String sessionId, JsonNode data) {
        try {
            ClientInfo info = clientInfo.get(sessionId);
            if (info == null || info.getSummonerName() == null) {
                log.warn("⚠️ [Accept Match] Sessão {} não identificada", sessionId);
                sendError(sessionId, "NOT_IDENTIFIED", "Cliente não identificado");
                return;
            }

            String summonerName = info.getSummonerName();

            // Extrair matchId do payload
            Long matchId = null;
            if (data.has("matchId") && !data.get("matchId").isNull()) {
                matchId = data.get("matchId").asLong();
            } else if (data.has("data") && data.get("data").has("matchId")) {
                matchId = data.get("data").get("matchId").asLong();
            }

            if (matchId == null) {
                log.warn("⚠️ [Accept Match] matchId não fornecido por {}", summonerName);
                sendError(sessionId, "MISSING_MATCH_ID", "matchId é obrigatório");
                return;
            }

            log.info("✅ [Accept Match] Jogador {} (sessão {}) aceitou partida {}",
                    summonerName, sessionId, matchId);

            // Chamar o serviço correto
            getMatchFoundService().acceptMatch(matchId, summonerName);

            // Confirmar apenas para este jogador
            sendMessage(sessionId, "match_accepted", Map.of(
                    "success", true,
                    "matchId", matchId,
                    "summonerName", summonerName));

        } catch (Exception e) {
            log.error("❌ Erro ao processar aceitação de partida", e);
            sendError(sessionId, "ACCEPT_FAILED", "Erro ao aceitar partida");
        }
    }

    /**
     * Processa decline match
     */
    private void handleDeclineMatch(String sessionId, JsonNode data) {
        try {
            ClientInfo info = clientInfo.get(sessionId);
            if (info == null || info.getSummonerName() == null) {
                log.warn("⚠️ [Decline Match] Sessão {} não identificada", sessionId);
                sendError(sessionId, "NOT_IDENTIFIED", "Cliente não identificado");
                return;
            }

            String summonerName = info.getSummonerName();

            // Extrair matchId do payload
            Long matchId = null;
            if (data.has("matchId") && !data.get("matchId").isNull()) {
                matchId = data.get("matchId").asLong();
            } else if (data.has("data") && data.get("data").has("matchId")) {
                matchId = data.get("data").get("matchId").asLong();
            }

            if (matchId == null) {
                log.warn("⚠️ [Decline Match] matchId não fornecido por {}", summonerName);
                sendError(sessionId, "MISSING_MATCH_ID", "matchId é obrigatório");
                return;
            }

            log.warn("❌ [Decline Match] Jogador {} (sessão {}) recusou partida {}",
                    summonerName, sessionId, matchId);

            // Chamar o serviço correto
            getMatchFoundService().declineMatch(matchId, summonerName);

            // Confirmar apenas para este jogador
            sendMessage(sessionId, "match_declined", Map.of(
                    "success", true,
                    "matchId", matchId,
                    "summonerName", summonerName,
                    "reason", "player_declined"));

        } catch (Exception e) {
            log.error("❌ Erro ao processar recusa de partida", e);
            sendError(sessionId, "DECLINE_FAILED", "Erro ao recusar partida");
        }
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
                } else {
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
            if (f != null)
                f.cancel(true);
        } catch (Exception e) {
            log.warn("⚠️ Erro ao cancelar heartbeat task no closeSession para {}", sessionId, e);
        }
    }

    /**
     * ✅ MIGRADO PARA REDIS: Envia mensagem para um cliente específico
     * 
     * CRÍTICO: Se envio falhar (desconectado), enfileira evento no Redis.
     * Garante que eventos não sejam perdidos durante desconexões.
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

                log.debug("📤 Evento enviado: {} → {}", sessionId, type);

            } catch (Exception e) {
                log.error("❌ Erro ao enviar mensagem para {}. Enfileirando no Redis...", sessionId, e);

                // ✅ REDIS: Enfileirar evento para envio posterior
                Map<String, Object> payload = new HashMap<>();
                if (data != null) {
                    payload.put("data", data);
                }
                redisWSEvent.queueEvent(sessionId, type, payload);

                // Backward compatibility: cache local
                addPendingEvent(sessionId, type);
            }
        } else {
            // Sessão fechada ou inexistente
            log.warn("⚠️ Sessão WebSocket fechada ou inexistente: {}. Enfileirando evento no Redis: {}",
                    sessionId, type);

            // ✅ REDIS: Enfileirar evento para envio quando reconectar
            Map<String, Object> payload = new HashMap<>();
            if (data != null) {
                payload.put("data", data);
            }
            redisWSEvent.queueEvent(sessionId, type, payload);

            // Backward compatibility: cache local
            addPendingEvent(sessionId, type);
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
     * Broadcast de atualização da fila - ENVIA APENAS PARA JOGADORES NA FILA
     */
    public void broadcastQueueUpdate(List<QueuePlayerInfoDTO> queueStatus) {
        try {
            Map<String, Object> message = Map.of(
                    "type", "queue_update",
                    "data", queueStatus,
                    "timestamp", System.currentTimeMillis());

            String jsonMessage = objectMapper.writeValueAsString(message);

            // ✅ CORREÇÃO: Enviar apenas para jogadores que ESTÃO NA FILA
            if (queueStatus != null && !queueStatus.isEmpty()) {
                Set<String> playersInQueue = queueStatus.stream()
                        .map(QueuePlayerInfoDTO::getSummonerName)
                        .filter(name -> name != null && !name.isEmpty())
                        .collect(Collectors.toSet());

                log.info("🔍 [Queue Update] Jogadores na fila: {}", playersInQueue);

                Collection<WebSocketSession> queueSessions = sessionRegistry.getByPlayers(playersInQueue);

                log.info("📤 [Queue Update] Enviando para {} sessões (de {} jogadores na fila)",
                        queueSessions.size(), playersInQueue.size());

                if (queueSessions.size() != playersInQueue.size()) {
                    log.warn("⚠️ [Queue Update] ATENÇÃO: {} jogadores na fila mas apenas {} sessões encontradas!",
                            playersInQueue.size(), queueSessions.size());
                }

                sendToMultipleSessions(queueSessions, jsonMessage);
            } else {
                // Se a fila está vazia, não precisa enviar para ninguém
                log.debug("📭 [Queue Update] Fila vazia, nenhuma mensagem enviada");
            }

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
     * Envia mensagem para jogadores específicos (usado para eventos de partida)
     */
    public void sendToPlayers(String messageType, Map<String, Object> data, List<String> summonerNames) {
        try {
            Map<String, Object> message = new HashMap<>(data);
            message.put("type", messageType);
            message.put("timestamp", System.currentTimeMillis());

            String jsonMessage = objectMapper.writeValueAsString(message);

            Collection<WebSocketSession> playerSessions = sessionRegistry.getByPlayers(summonerNames);

            log.debug("📤 [SendToPlayers] Enviando '{}' para {} jogadores (sessões encontradas: {})",
                    messageType, summonerNames.size(), playerSessions.size());

            sendToMultipleSessions(playerSessions, jsonMessage);

        } catch (Exception e) {
            log.error("❌ Erro ao enviar mensagem para jogadores específicos", e);
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
     * Envia mensagem para múltiplas sessões específicas
     */
    private void sendToMultipleSessions(Collection<WebSocketSession> targetSessions, String message) {
        if (targetSessions == null || targetSessions.isEmpty()) {
            return;
        }

        for (WebSocketSession session : targetSessions) {
            try {
                if (session != null && session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                }
            } catch (IOException e) {
                log.error("❌ Erro ao enviar mensagem para sessão {}", session.getId(), e);
            }
        }
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
     * ✅ MIGRADO PARA REDIS: Atualiza informações LCU de uma sessão
     * (para integração com CoreWebSocketHandler)
     */
    public void updateLcuInfo(String sessionId, String host, int port, String summonerName) {
        // ✅ REDIS ONLY: Armazenar informações do jogador (sem HashMap!)
        WebSocketSession session = sessions.get(sessionId);
        String ipAddress = "unknown";
        String userAgent = "unknown";

        if (session != null && session.getRemoteAddress() != null) {
            ipAddress = session.getRemoteAddress().getAddress().getHostAddress();
            if (session.getHandshakeHeaders() != null) {
                userAgent = session.getHandshakeHeaders().getFirst("User-Agent");
                if (userAgent == null)
                    userAgent = "unknown";
            }
        }

        // Criar JSON com os dados do jogador
        try {
            Map<String, Object> playerInfo = new HashMap<>();
            playerInfo.put("summonerName", summonerName);
            playerInfo.put("ipAddress", ipAddress);
            playerInfo.put("userAgent", userAgent);
            playerInfo.put("host", host);
            playerInfo.put("port", port);

            String playerInfoJson = objectMapper.writeValueAsString(playerInfo);
            redisWSSession.storePlayerInfo(sessionId, playerInfoJson);

            log.info(
                    "🔧 [MatchmakingWebSocketService] LCU info atualizada no Redis: session={}, host={}, port={}, summoner={}",
                    sessionId, host, port, summonerName);
        } catch (Exception e) {
            log.error("❌ [MatchmakingWebSocketService] Erro ao serializar playerInfo: {}", e.getMessage());
        }
    }

    /**
     * Obtém contagem de sessões ativas
     */
    public int getActiveSessionsCount() {
        return sessions.size();
    }

    /**
     * Pick a connected client session that has LCU lockfile info attached.
     * Returns the sessionId or null if none available.
     */
    public String pickGatewaySessionId() {
        return pickGatewaySessionId(null);
    }

    /**
     * Pick a connected client session that has LCU lockfile info attached,
     * optionally filtering by summonerName.
     * 
     * @param summonerName Optional - if provided, will prefer the session for this
     *                     summoner
     * @return sessionId or null if none available
     */
    public String pickGatewaySessionId(String summonerName) {
        // Se summonerName fornecido, tentar encontrar sessão específica primeiro
        if (summonerName != null && !summonerName.isEmpty()) {
            String normalizedName = summonerName.toLowerCase();
            for (Map.Entry<String, WebSocketSession> e : sessions.entrySet()) {
                try {
                    WebSocketSession s = e.getValue();
                    if (s != null && s.isOpen()) {
                        ClientInfo info = clientInfo.get(e.getKey());
                        if (info != null && info.getSummonerName() != null
                                && info.getSummonerName().toLowerCase().equals(normalizedName)
                                && info.getLcuHost() != null && info.getLcuPort() > 0) {
                            log.info(
                                    "pickGatewaySessionId: selecting session {} for specific summoner '{}' (host={} port={})",
                                    e.getKey(), summonerName, info.getLcuHost(), info.getLcuPort());
                            return e.getKey();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            log.warn("⚠️ pickGatewaySessionId: summoner '{}' não encontrado, usando fallback", summonerName);
        }

        // Debug: list available sessions and their clientInfo so we can diagnose why
        // no gateway client is selected.
        try {
            for (Map.Entry<String, WebSocketSession> e : sessions.entrySet()) {
                try {
                    String sid = e.getKey();
                    WebSocketSession s = e.getValue();
                    ClientInfo info = clientInfo.get(sid);
                    if (info == null) {
                        log.info("pickGatewaySessionId: session={} open={} info=null", sid, s != null && s.isOpen());
                    } else {
                        log.info("pickGatewaySessionId: session={} open={} info={}/{}:{} protocol={} identified={}",
                                sid, s != null && s.isOpen(), info.getPlayerId(), info.getSummonerName(),
                                info.getLcuPort(), info.getLcuHost(), info.isIdentified());
                    }
                } catch (Exception ex) {
                    log.info("pickGatewaySessionId: failed to inspect session {}: {}", e.getKey(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.info("pickGatewaySessionId: introspection failed: {}", ex.getMessage());
        }

        for (Map.Entry<String, WebSocketSession> e : sessions.entrySet()) {
            try {
                WebSocketSession s = e.getValue();
                if (s != null && s.isOpen()) {
                    ClientInfo info = clientInfo.get(e.getKey());
                    if (info != null && info.getLcuHost() != null && info.getLcuPort() > 0) {
                        log.info("pickGatewaySessionId: selecting session {} (host={} port={})", e.getKey(),
                                info.getLcuHost(), info.getLcuPort());
                        return e.getKey();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // Fallback: if no client advertised lockfile info, pick any identified client
        for (Map.Entry<String, WebSocketSession> e : sessions.entrySet()) {
            try {
                WebSocketSession s = e.getValue();
                if (s != null && s.isOpen()) {
                    ClientInfo info = clientInfo.get(e.getKey());
                    if (info != null && info.isIdentified()) {
                        log.info("pickGatewaySessionId: no lockfile clients found, selecting identified session {}",
                                e.getKey());
                        return e.getKey();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Convenience: request LCU via any available gateway client. Will throw if no
     * client
     * is available or the RPC fails.
     */
    public JsonNode requestLcuFromAnyClient(String method, String path, JsonNode body, long timeoutMs)
            throws Exception {
        String sessionId = pickGatewaySessionId();
        if (sessionId == null) {
            throw new IllegalStateException("No gateway client available");
        }
        return requestLcu(sessionId, method, path, body, timeoutMs);
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
        // LCU lockfile info (optional)
        private String lcuHost;
        private int lcuPort;
        private String lcuProtocol;
        private String lcuPassword;

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

        public String getLcuHost() {
            return lcuHost;
        }

        public void setLcuHost(String lcuHost) {
            this.lcuHost = lcuHost;
        }

        public int getLcuPort() {
            return lcuPort;
        }

        public void setLcuPort(int lcuPort) {
            this.lcuPort = lcuPort;
        }

        public String getLcuProtocol() {
            return lcuProtocol;
        }

        public void setLcuProtocol(String lcuProtocol) {
            this.lcuProtocol = lcuProtocol;
        }

        public String getLcuPassword() {
            return lcuPassword;
        }

        public void setLcuPassword(String lcuPassword) {
            this.lcuPassword = lcuPassword;
        }
    }

    /**
     * Envia mensagem para todos os clientes conectados (broadcast)
     */
    public void broadcastMessage(String type, Object data) {
        log.debug("📡 Broadcasting message type: {} to {} sessions", type, sessions.size());

        for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
            WebSocketSession session = entry.getValue();
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
                    log.error("❌ Erro ao enviar broadcast para sessão {}", entry.getKey(), e);
                }
            }
        }
    }

    /**
     * Handle get_discord_users request from frontend
     */
    private void handleGetDiscordUsers(String sessionId) {
        try {
            log.debug("📡 [WebSocket] Handling get_discord_users request from session: {}", sessionId);

            Object discordService = getDiscordService();
            if (discordService == null) {
                log.warn("⚠️ [WebSocket] DiscordService não disponível");
                sendMessage(sessionId, "discord_users", Collections.emptyList());
                return;
            }

            // Usar reflection para chamar o método getUsersInChannel
            java.lang.reflect.Method getUsersMethod = discordService.getClass().getMethod("getUsersInChannel");
            Object users = getUsersMethod.invoke(discordService);

            log.debug("📡 [WebSocket] Sending {} Discord users to session: {}",
                    users instanceof List ? ((List<?>) users).size() : "unknown", sessionId);

            sendMessage(sessionId, "discord_users", Map.of("users", users));

        } catch (Exception e) {
            log.error("❌ [WebSocket] Erro ao obter usuários Discord", e);
            sendMessage(sessionId, "discord_users", Collections.emptyList());
        }
    }
}
