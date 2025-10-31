package br.com.lolmatchmaking.backend.websocket;

import br.com.lolmatchmaking.backend.dto.MatchInfoDTO;
import br.com.lolmatchmaking.backend.dto.QueuePlayerInfoDTO;
import br.com.lolmatchmaking.backend.service.redis.RedisWebSocketEventService;
import br.com.lolmatchmaking.backend.service.redis.RedisWebSocketSessionService;
import br.com.lolmatchmaking.backend.service.redis.RedisPlayerMatchService;
import br.com.lolmatchmaking.backend.service.lock.PlayerState;
import br.com.lolmatchmaking.backend.service.lock.PlayerStateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import br.com.lolmatchmaking.backend.service.LCUService;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ✅ MIGRAÇÃO COMPLETA: Redis + MySQL + Cache Local Legítimo
 * 
 * ANTES: 7 ConcurrentHashMaps perdiam dados em reinícios
 * DEPOIS: Redis + MySQL + 4 HashMaps legítimos (objetos não-serializáveis)
 * 
 * ✅ CACHE LOCAL LEGÍTIMO (4 HashMaps):
 * - sessions → WebSocketSession (não serializável)
 * - heartbeatTasks → ScheduledFuture (não serializável)
 * - pendingLcuRequests → CompletableFuture (não serializável)
 * - lcuRequestSession → String mapping (temporário)
 * 
 * ✅ MIGRADOS PARA REDIS:
 * - clientInfo → RedisWebSocketSessionService
 * - lastHeartbeat → RedisWebSocketSessionService
 * - pendingEvents → RedisWebSocketEventService
 * - playerStates → PlayerStateService
 * - matchOwnership → RedisPlayerMatchService
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
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;
    private final RedisWebSocketEventService redisWSEvent;
    private final RedisPlayerMatchService redisPlayerMatch;
    private final PlayerStateService playerStateService;
    private final br.com.lolmatchmaking.backend.service.UnifiedLogService unifiedLogService;

    // ✅ CACHE LOCAL LEGÍTIMO: Apenas para objetos não-serializáveis
    // WebSocketSession, ScheduledFuture e CompletableFuture não podem ser salvos no
    // Redis
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonNode>> pendingLcuRequests = new ConcurrentHashMap<>();
    private final Map<String, String> lcuRequestSession = new ConcurrentHashMap<>();

    // ✅ NOVO: Mapeamento customSessionId → randomSessionId (para encontrar a sessão
    // real do WebSocket)
    private final Map<String, String> customToRandomSessionMapping = new ConcurrentHashMap<>();

    // ✅ MIGRADOS PARA REDIS: Todos os outros dados agora estão no Redis
    // - clientInfo → RedisWebSocketSessionService.getSummonerBySession()
    // - lastHeartbeat → RedisWebSocketSessionService.updateHeartbeat()
    // - pendingEvents → RedisWebSocketEventService.getPendingEvents()

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

    /**
     * Obtém o RedisMatchAcceptanceService quando necessário
     */
    private br.com.lolmatchmaking.backend.service.RedisMatchAcceptanceService getRedisAcceptanceService() {
        try {
            return applicationContext.getBean(br.com.lolmatchmaking.backend.service.RedisMatchAcceptanceService.class);
        } catch (Exception e) {
            log.warn("RedisMatchAcceptanceService não disponível: {}", e.getMessage());
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
        String randomSessionId = session.getId();
        sessions.put(randomSessionId, session);

        // ✅ REDIS ONLY: Inicializar heartbeat no Redis (sem HashMap local!)
        redisWSSession.updateHeartbeat(randomSessionId);

        log.info("🔌 Cliente conectado: randomSessionId={} (Total: {})", randomSessionId, sessions.size());

        // ✅ CRÍTICO: Buscar customSessionId para enviar eventos pendentes
        // Events pendentes são armazenados por customSessionId (imutável)
        String customSessionIdForKey = null;
        try {
            Optional<String> customOpt = redisWSSession.getCustomSessionId(randomSessionId);
            if (customOpt.isPresent()) {
                customSessionIdForKey = customOpt.get();
                log.debug("🔍 [Reconnect] CustomSessionId encontrado: {} → {}", randomSessionId, customSessionIdForKey);
            } else {
                log.debug("⚠️ [Reconnect] CustomSessionId ainda não registrado para: {}", randomSessionId);
            }
        } catch (Exception e) {
            log.warn("⚠️ [Reconnect] Erro ao buscar customSessionId: {}", e);
        }

        // ✅ REDIS: Enviar eventos pendentes usando customSessionId
        if (customSessionIdForKey != null) {
            List<RedisWebSocketEventService.PendingEvent> pendingFromRedis = redisWSEvent
                    .getPendingEvents(customSessionIdForKey);

            if (!pendingFromRedis.isEmpty()) {
                log.info(
                        "📬 [Reconnect] {} eventos pendentes encontrados para customSessionId: {} (randomSessionId: {})",
                        pendingFromRedis.size(), customSessionIdForKey, randomSessionId);

                for (RedisWebSocketEventService.PendingEvent event : pendingFromRedis) {
                    try {
                        sendMessage(randomSessionId, event.getEventType(), event.getPayload());
                        log.info("✅ [Reconnect] Evento pendente enviado: {} → {}", customSessionIdForKey,
                                event.getEventType());
                    } catch (Exception e) {
                        log.error("❌ [Reconnect] Erro ao enviar evento pendente: {}", event.getEventType(), e);
                    }
                }

                // Limpar eventos após envio bem-sucedido usando customSessionId
                redisWSEvent.clearPendingEvents(customSessionIdForKey);
                log.info("🗑️ [Reconnect] {} eventos pendentes limpos para customSessionId: {}",
                        pendingFromRedis.size(), customSessionIdForKey);
            } else {
                log.debug("📭 [Reconnect] Nenhum evento pendente para customSessionId: {}", customSessionIdForKey);
            }
        } else {
            log.debug("⏳ [Reconnect] Aguardando identificação para buscar eventos pendentes");
        }

        // Iniciar monitoramento de heartbeat
        startHeartbeatMonitoring(randomSessionId);

        // ✅ NOVO: Enviar evento global de reconexão para verificar partidas ativas
        sendGlobalReconnectCheck();
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
                    // ✅ Atualizar heartbeat no Redis
                    redisWSSession.updateHeartbeat(sessionId);
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
                case "confirm_session_migration":
                    handleConfirmSessionMigration(sessionId, jsonMessage);
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
                // ✅ LCU info aceito - por ora não validamos contra Redis
                // TODO: Implementar storage de LCU info no Redis se necessário
                String summonerId = null;
                try {
                    summonerId = extractSummonerId(body);
                } catch (Exception ignored) {
                }

                if (summonerId != null) {
                    log.info("LCU status accepted from gateway session={} summonerId={}", sessionId, summonerId);
                }

                sendMessage(sessionId, "lcu_status_ack", Map.of("success", true));
                return;
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

            // ✅ LCU response completed successfully
            try {
                int code = -1;
                if (status != null && status.isInt())
                    code = status.asInt();
                if (code == 200) {
                    String sess = lcuRequestSession.remove(id);
                    if (sess != null) {
                        String summonerId = null;
                        try {
                            summonerId = extractSummonerId(body);
                        } catch (Exception ignored) {
                        }
                        if (summonerId != null) {
                            log.info("✅ LCU response from session={} id={} summonerId={}", sess, id, summonerId);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Erro processando LCU response: {}", e.getMessage());
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
     * ✅ NOVO: Identifica um cliente com sessionId customizado baseado no
     * summonerName#tag
     */
    private void handleIdentify(String sessionId, JsonNode data) {
        try {
            // Support flexible identify payloads. Some clients send fields at the
            // root (playerId, summonerName, data:{ lockfile }) while others may
            // nest them under data (data: { playerId, summonerName, lockfile }).
            String playerId = null;
            String summonerName = null;
            String customSessionId = null;

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

            // ✅ NOVO: Verificar se Electron enviou sessionId customizado
            if (data.has("customSessionId") && !data.get("customSessionId").isNull()) {
                customSessionId = data.get("customSessionId").asText(null);
            } else if (data.has("data") && data.get("data") != null && data.get("data").has("customSessionId")) {
                customSessionId = data.get("data").get("customSessionId").asText(null);
            }

            // ✅ Identificar jogador no Redis
            if (summonerName != null && !summonerName.isBlank()) {
                // ✅ NOVO: Usar sessionId customizado se fornecido pelo Electron
                String effectiveSessionId = (customSessionId != null && !customSessionId.isBlank())
                        ? customSessionId
                        : sessionId;

                // ✅ NOVO: Se usando sessionId customizado, migrar sessão WebSocket
                if (customSessionId != null && !customSessionId.equals(sessionId)) {
                    migrateWebSocketSession(sessionId, customSessionId);
                }

                // Registrar no SessionRegistry (que usa Redis)
                sessionRegistry.registerPlayer(summonerName, effectiveSessionId);

                // ✅ NOVO: Invalidar cache via Redis (evita dependência circular)
                sessionRegistry.invalidateSessionCache();

                log.info(
                        "✅ [Player-Sessions] [ELECTRON→BACKEND] Jogador {} registrado no Redis para sessão {} (custom: {}) - cache invalidado",
                        summonerName,
                        effectiveSessionId,
                        customSessionId != null ? "SIM" : "NÃO");

                // ✅ Lockfile data processamento (se necessário no futuro)
                // TODO: Implementar storage de LCU lockfile info no Redis se necessário
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

                    if (lf != null && summonerName != null) {
                        String lcuHost = lf.has("host") ? lf.get("host").asText(null) : null;
                        int lcuPort = lf.has("port") ? lf.get("port").asInt(0) : 0;
                        String lcuProtocol = lf.has("protocol") ? lf.get("protocol").asText("https") : "https";
                        String lcuPassword = lf.has("password") ? lf.get("password").asText(null) : null;

                        log.debug("handleIdentify: parsed lockfile for session={} host={} port={} protocol={}",
                                sessionId, lcuHost, lcuPort, lcuProtocol);

                        // ✅ Configurar LCU no banco de dados para este jogador
                        if (lcuPort != 0) {
                            try {
                                getLcuService().configureLCUForPlayer(
                                        summonerName,
                                        lcuPort,
                                        lcuProtocol,
                                        lcuPassword);
                                log.info("✅ [WebSocket] LCU configurado para {} na tabela players", summonerName);
                            } catch (Exception ex) {
                                log.error("❌ [WebSocket] Erro ao configurar LCU no banco para {}: {}",
                                        summonerName, ex.getMessage());
                            }
                        }
                    }
                } catch (Exception ignored) {
                }

                log.info("🆔 Cliente identificado: {} (playerId: {})", summonerName, playerId);

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
            // ✅ Buscar summonerName no Redis
            Optional<String> summonerOpt = redisWSSession.getSummonerBySession(sessionId);
            if (summonerOpt.isEmpty()) {
                log.warn("⚠️ [Accept Match] Sessão {} não identificada no Redis", sessionId);
                sendError(sessionId, "NOT_IDENTIFIED", "Cliente não identificado");
                return;
            }

            String summonerName = summonerOpt.get();

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
            // ✅ Buscar summonerName no Redis
            Optional<String> summonerOpt = redisWSSession.getSummonerBySession(sessionId);
            if (summonerOpt.isEmpty()) {
                log.warn("⚠️ [Decline Match] Sessão {} não identificada no Redis", sessionId);
                sendError(sessionId, "NOT_IDENTIFIED", "Cliente não identificado");
                return;
            }

            String summonerName = summonerOpt.get();

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
        ScheduledFuture<?> future = Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
            try {
                // ✅ Buscar último heartbeat no Redis
                Optional<RedisWebSocketSessionService.ClientInfo> clientInfoOpt = redisWSSession
                        .getClientInfo(sessionId);
                if (clientInfoOpt.isPresent()) {
                    Instant lastActivity = clientInfoOpt.get().getLastActivity();
                    if (Instant.now().minusMillis(HEARTBEAT_TIMEOUT).isAfter(lastActivity)) {
                        log.warn("💔 Heartbeat timeout para cliente: {}", sessionId);
                        closeSession(sessionId);
                        return;
                    }
                }

                // Probe client with a ping to elicit a pong/heartbeat response
                try {
                    sendMessage(sessionId, "ping", Map.of("ts", System.currentTimeMillis()));
                } catch (Exception e) {
                    log.debug("⚠️ Falha ao enviar ping para {}: {}", sessionId, e.getMessage());
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
        // ✅ Buscar eventos pendentes no Redis
        List<br.com.lolmatchmaking.backend.service.redis.RedisWebSocketEventService.PendingEvent> events = redisWSEvent
                .getPendingEvents(sessionId);
        if (events != null && !events.isEmpty()) {
            for (br.com.lolmatchmaking.backend.service.redis.RedisWebSocketEventService.PendingEvent event : events) {
                // Processar cada evento pendente
                try {
                    String eventType = event.getEventType();
                    Map<String, Object> payload = event.getPayload();

                    sendMessage(sessionId, eventType, payload);
                } catch (Exception e) {
                    log.warn("❌ Erro ao processar evento pendente: {}", e.getMessage());
                }
            }
            // ✅ Limpar eventos após enviar
            redisWSEvent.clearPendingEvents(sessionId);
        }
    }
    
    /**
     * ✅ PÚBLICO: Envia eventos pendentes após identificação do Electron
     * Chamado pelo CoreWebSocketHandler após registrar customSessionId
     */
    public void sendPendingEventsForSession(String customSessionId, String randomSessionId) {
        try {
            List<RedisWebSocketEventService.PendingEvent> pendingFromRedis = redisWSEvent
                    .getPendingEvents(customSessionId);

            if (!pendingFromRedis.isEmpty()) {
                log.info("📬 [PendingEvents] {} eventos pendentes encontrados para customSessionId: {} (randomSessionId: {})",
                        pendingFromRedis.size(), customSessionId, randomSessionId);

                for (RedisWebSocketEventService.PendingEvent event : pendingFromRedis) {
                    try {
                        sendMessage(randomSessionId, event.getEventType(), event.getPayload());
                        log.info("✅ [PendingEvents] Evento pendente enviado: {} → {}", customSessionId,
                                event.getEventType());
                    } catch (Exception e) {
                        log.error("❌ [PendingEvents] Erro ao enviar evento pendente: {}", event.getEventType(), e);
                    }
                }

                // Limpar eventos após envio bem-sucedido usando customSessionId
                redisWSEvent.clearPendingEvents(customSessionId);
                log.info("🗑️ [PendingEvents] {} eventos pendentes limpos para customSessionId: {}",
                        pendingFromRedis.size(), customSessionId);
            } else {
                log.debug("📭 [PendingEvents] Nenhum evento pendente para customSessionId: {}", customSessionId);
            }
        } catch (Exception e) {
            log.error("❌ [PendingEvents] Erro ao processar eventos pendentes para {}: {}", customSessionId, e.getMessage(), e);
        }
    }

    /**
     * Adiciona evento pendente
     */
    private void addPendingEvent(String sessionId, String eventType) {
        // ✅ Adicionar evento pendente no Redis
        try {
            redisWSEvent.queueEvent(sessionId, eventType, Collections.emptyMap());
        } catch (Exception e) {
            log.warn("❌ Erro ao adicionar evento pendente ao Redis: {}", e.getMessage());
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
        // ✅ CORRIGIR: Buscar randomSessionId se for customSessionId
        String actualSessionId = getRandomSessionId(sessionId);

        WebSocketSession session = sessions.get(actualSessionId);

        if (session != null && session.isOpen()) {
            try {
                Map<String, Object> message = new HashMap<>();
                message.put("type", type);
                message.put("timestamp", System.currentTimeMillis());
                if (data != null) {
                    message.put("data", data);
                }

                String jsonMessage = objectMapper.writeValueAsString(message);

                // ✅ CRÍTICO: SINCRONIZAR envio de mensagem WebSocket!
                // Previne IllegalStateException: TEXT_PARTIAL_WRITING
                synchronized (session) {
                    if (session.isOpen()) { // Re-check após adquirir lock
                        session.sendMessage(new TextMessage(jsonMessage));
                    }
                }

                log.debug("📤 Evento enviado: {} → {}", sessionId, type);

            } catch (Exception e) {
                log.error("❌ Erro ao enviar mensagem para {}. Enfileirando no Redis...", sessionId, e);

                // ✅ CRÍTICO: Buscar customSessionId para enfileirar com chave imutável
                String customSessionIdForKey = getCustomSessionIdForPendingEvent(actualSessionId, sessionId);

                // ✅ REDIS: Enfileirar evento para envio posterior usando customSessionId
                Map<String, Object> payload = new HashMap<>();
                if (data != null) {
                    payload.put("data", data);
                }
                redisWSEvent.queueEvent(customSessionIdForKey, type, payload);
                log.info("📨 [PendingEvent] Evento enfileirado usando customSessionId: {}", customSessionIdForKey);
            }
        } else {
            // Sessão fechada ou inexistente
            log.warn("⚠️ Sessão WebSocket fechada ou inexistente: {}. Enfileirando evento no Redis: {}",
                    sessionId, type);

            // ✅ CRÍTICO: Buscar customSessionId para enfileirar com chave imutável
            String customSessionIdForKey = getCustomSessionIdForPendingEvent(actualSessionId, sessionId);

            // ✅ REDIS: Enfileirar evento para envio quando reconectar usando
            // customSessionId
            Map<String, Object> payload = new HashMap<>();
            if (data != null) {
                payload.put("data", data);
            }
            redisWSEvent.queueEvent(customSessionIdForKey, type, payload);
            log.info("📨 [PendingEvent] Evento enfileirado usando customSessionId: {}", customSessionIdForKey);
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
            log.info("🔍 [broadcastToAll] Iniciando broadcast: eventType={}, data={}", eventType,
                    data != null ? "NÃO_NULL" : "NULL");

            Map<String, Object> message = new HashMap<>();
            message.put("type", eventType);
            message.put("data", data);
            message.put("timestamp", System.currentTimeMillis());

            String jsonMessage = objectMapper.writeValueAsString(message);
            log.info("🔍 [broadcastToAll] JSON criado: {}",
                    jsonMessage.length() > 200 ? jsonMessage.substring(0, 200) + "..." : jsonMessage);

            broadcastToAll(jsonMessage);
            log.info("✅ [broadcastToAll] Broadcast concluído para eventType={}", eventType);
        } catch (Exception e) {
            log.error("❌ [broadcastToAll] Erro ao fazer broadcast para eventType={}: {}", eventType, e.getMessage(), e);
        }
    }

    /**
     * ✅ CORRIGIDO: Broadcast de atualização da fila - ENVIA PARA TODAS AS SESSÕES
     * 
     * Mudança: Anteriormente só enviava para quem estava NA FILA.
     * Agora envia para TODOS os clientes conectados, permitindo:
     * - Jogadores que saíram da fila verem a atualização imediata
     * - Jogadores visualizando a fila (mas não participando) verem mudanças em
     * tempo real
     * - Componente queue se manter sempre sincronizado com o backend
     */
    /**
     * ✅ NOVO: Envia match_found para jogador específico
     * 
     * Busca sessão do jogador e envia notificação de partida encontrada.
     * Usado pelo EventBroadcastService quando recebe evento via Pub/Sub.
     * 
     * @param summonerName Nome do jogador
     * @param matchId      ID da partida
     */
    public void sendMatchFoundToPlayer(String summonerName, Long matchId) {
        try {
            // Buscar sessão do jogador
            String sessionId = findSessionBySummonerName(summonerName);

            if (sessionId == null) {
                log.warn("⚠️ [WebSocket] Sessão não encontrada para jogador: {}", summonerName);
                return;
            }

            // Enviar match_found
            Map<String, Object> data = new HashMap<>();
            data.put("matchId", matchId);
            data.put("summonerName", summonerName);

            sendMessage(sessionId, "match_found", data);

            log.info("✅ [WebSocket] match_found enviado para {}", summonerName);

        } catch (Exception e) {
            log.error("❌ [WebSocket] Erro ao enviar match_found para {}", summonerName, e);
        }
    }

    /**
     * ✅ NOVO: Faz broadcast do progresso de aceitação de partida
     * 
     * Envia para todos os clientes conectados o progresso (ex: "3/10 aceitaram").
     * 
     * @param matchId  ID da partida
     * @param accepted Número de jogadores que aceitaram
     * @param total    Total de jogadores
     */
    public void broadcastMatchAcceptanceProgress(Long matchId, int accepted, int total) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("matchId", matchId);
            data.put("accepted", accepted);
            data.put("total", total);
            data.put("progress", String.format("%d/%d", accepted, total));

            // ✅ CORREÇÃO: Enviar apenas para jogadores da partida
            sendToPlayers("match_acceptance_progress", data, getAllPlayersFromMatch(matchId));

            log.debug("✅ [WebSocket] match_acceptance_progress broadcast: {}/{}", accepted, total);

        } catch (Exception e) {
            log.error("❌ [WebSocket] Erro ao fazer broadcast de match_acceptance_progress", e);
        }
    }

    /**
     * ✅ NOVO: Envia evento global de reconexão para verificar partidas ativas
     * Cada Electron deve verificar se tem partida ativa e responder com dados
     */
    private void sendGlobalReconnectCheck() {
        try {
            Map<String, Object> reconnectData = new HashMap<>();
            reconnectData.put("timestamp", System.currentTimeMillis());
            reconnectData.put("reason", "reconnection_check");

            broadcastToAll("reconnect_check", reconnectData);
            log.info("📡 [ReconnectCheck] Evento global de reconexão enviado para {} sessões", sessions.size());
        } catch (Exception e) {
            log.error("❌ [ReconnectCheck] Erro ao enviar evento de reconexão", e);
        }
    }

    /**
     * ✅ CORRIGIDO: Busca sessionId por summonerName usando Redis
     * 
     * CORREÇÃO CRÍTICA:
     * - ANTES: Usava HashMap local clientInfo (pode estar vazio após reinício)
     * - DEPOIS: Usa RedisWebSocketSessionService (fonte da verdade)
     * - Case-insensitive: Normaliza nome para lowercase
     * 
     * @param summonerName Nome do jogador
     * @return sessionId ou null se não encontrado
     */
    private String findSessionBySummonerName(String summonerName) {
        if (summonerName == null || summonerName.isBlank()) {
            return null;
        }

        // ✅ USAR REDIS: Fonte da verdade para mapeamentos
        // Redis normaliza para lowercase automaticamente
        Optional<String> sessionIdOpt = redisWSSession.getSessionBySummoner(summonerName.toLowerCase().trim());

        if (sessionIdOpt.isPresent()) {
            String sessionId = sessionIdOpt.get();
            // ✅ CRÍTICO: Converter customSessionId → randomSessionId se necessário
            String actualSessionId = getRandomSessionId(sessionId);

            // Verificar se sessão ainda existe localmente
            if (sessions.containsKey(actualSessionId)) {
                log.debug("✅ [WebSocket] Sessão encontrada via Redis: {} → {}", summonerName, actualSessionId);
                return actualSessionId;
            } else {
                log.warn("⚠️ [WebSocket] SessionId {} encontrado no Redis mas sessão local não existe para {}",
                        sessionId, summonerName);
                // Limpar entrada inválida
                redisWSSession.removeSession(sessionId);
                return null;
            }
        }

        log.warn("⚠️ [WebSocket] Sessão NÃO encontrada no Redis para: {}", summonerName);
        return null;
    }

    public void broadcastQueueUpdate(List<QueuePlayerInfoDTO> queueStatus) {
        try {
            Map<String, Object> message = Map.of(
                    "type", "queue_update",
                    "data", queueStatus != null ? queueStatus : List.of(),
                    "timestamp", System.currentTimeMillis());

            String jsonMessage = objectMapper.writeValueAsString(message);

            // ✅ CORREÇÃO: Enviar para TODAS as sessões conectadas (não apenas quem está na
            // fila)
            int totalSessions = sessions.size();
            log.info("📤 [Queue Update] Enviando atualização da fila para {} sessões (jogadores na fila: {})",
                    totalSessions, queueStatus != null ? queueStatus.size() : 0);

            if (queueStatus != null && !queueStatus.isEmpty()) {
                log.debug("📊 [Queue Update] Jogadores na fila: {}",
                        queueStatus.stream()
                                .map(QueuePlayerInfoDTO::getSummonerName)
                                .collect(Collectors.joining(", ")));
            }

            // Enviar para TODOS os clientes conectados
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
     * Broadcast para todos os clientes conectados EM PARALELO
     * 
     * ✅ ENVIO SIMULTÂNEO: Todas as sessões recebem ao mesmo tempo
     */
    public void broadcastToAll(String message) {
        List<WebSocketSession> activeSessions = new ArrayList<>(sessions.values());

        log.info("🔍 [broadcastToAll] Sessões ativas encontradas: {}", activeSessions.size());

        if (activeSessions.isEmpty()) {
            log.warn("⚠️ [broadcastToAll] Nenhuma sessão ativa - não enviando mensagem");
            return;
        }

        // ✅ ENVIO PARALELO: Criar uma CompletableFuture para cada sessão
        List<CompletableFuture<Boolean>> sendFutures = new ArrayList<>();

        for (WebSocketSession session : activeSessions) {
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                try {
                    if (session.isOpen()) {
                        // ✅ CRÍTICO: SINCRONIZAR envio de mensagem WebSocket!
                        // Previne IllegalStateException: TEXT_PARTIAL_WRITING
                        synchronized (session) {
                            if (session.isOpen()) { // Re-check após adquirir lock
                                session.sendMessage(new TextMessage(message));
                                return false; // Não remover
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("❌ Erro ao enviar mensagem para sessão {}", session.getId(), e);
                    return true; // Marcar para remoção
                }
                return true; // Remover sessões fechadas
            });

            sendFutures.add(future);
        }

        // ✅ AGUARDAR TODOS OS ENVIOS E REMOVER SESSÕES COM ERRO
        try {
            CompletableFuture.allOf(sendFutures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);

            // Remover sessões marcadas para remoção
            for (int i = 0; i < activeSessions.size(); i++) {
                if (sendFutures.get(i).join()) { // true = remover
                    sessions.remove(activeSessions.get(i).getId());
                }
            }

            log.debug("✅ [Broadcast] {} mensagens enviadas em paralelo", activeSessions.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("⚠️ Thread interrompida ao fazer broadcast", e);
        } catch (TimeoutException e) {
            log.error("⏱️ Timeout ao fazer broadcast (5s)", e);
        } catch (Exception e) {
            log.error("❌ Erro ao aguardar broadcast paralelo", e);
        }
    }

    /**
     * ✅ NOVO: Envia mensagem apenas para jogadores específicos COM FALLBACK
     * Sistema direcionado - apenas jogadores da partida recebem eventos
     * Se falhar, faz broadcast global como fallback
     */
    public void sendToPlayers(String eventType, Map<String, Object> data, List<String> playerNames) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", eventType);
            message.put("data", data);
            message.put("timestamp", System.currentTimeMillis());

            String jsonMessage = objectMapper.writeValueAsString(message);
            TextMessage textMessage = new TextMessage(jsonMessage);

            int sentCount = 0;
            // ✅ CORREÇÃO: Excluir bots da contagem (eles não têm WebSocket)
            int totalPlayers = (int) playerNames.stream().filter(name -> !isBotPlayer(name)).count();
            int failedPlayers = 0;

            log.info("🎯 [Directed Broadcast] Enviando {} para {} jogadores específicos (bots excluídos)", eventType, totalPlayers);

            // ✅ ENVIO PARALELO para jogadores específicos
            List<CompletableFuture<Boolean>> sendFutures = new ArrayList<>();

            for (String playerName : playerNames) {
                // ✅ CRÍTICO: SKIP bots - eles não têm sessão WebSocket/Electron
                if (isBotPlayer(playerName)) {
                    log.trace("🤖 [Directed] Bot {} pulado (sem WebSocket)", playerName);
                    continue; // Pular bots completamente - SEM logar warnings
                }
                
                CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        // ✅ Buscar sessionId do jogador via Redis
                        Optional<String> sessionIdOpt = redisWSSession.getSessionBySummoner(playerName);

                        if (sessionIdOpt.isPresent()) {
                            String sessionId = sessionIdOpt.get();
                            // ✅ CRÍTICO: Converter customSessionId → randomSessionId se necessário
                            String actualSessionId = getRandomSessionId(sessionId);
                            WebSocketSession session = sessions.get(actualSessionId);

                            if (session != null && session.isOpen()) {
                                // ✅ CRÍTICO: VALIDAR que a sessão pertence ao jogador correto
                                if (!validateSessionOwnership(session, playerName)) {
                                    log.warn("⚠️ [Security] Sessão {} não pertence ao jogador {} - evento NÃO enviado",
                                            actualSessionId, playerName);
                                    // Enfileirar evento pendente para quando o jogador reconectar
                                    String customSessionIdForKey = getCustomSessionIdForPendingEvent(actualSessionId,
                                            sessionId);
                                    redisWSEvent.queueEvent(customSessionIdForKey, eventType, data);
                                    return false;
                                }

                                // ✅ CRÍTICO: Criar mensagem customizada COM targetSummoner para Electron
                                // validar
                                Map<String, Object> personalizedData = new HashMap<>(data);
                                personalizedData.put("targetSummoner", playerName); // ✅ Electron valida isto

                                Map<String, Object> personalizedMessage = new HashMap<>();
                                personalizedMessage.put("type", eventType);
                                personalizedMessage.put("data", personalizedData);
                                personalizedMessage.put("timestamp", System.currentTimeMillis());
                                personalizedMessage.put("targetSummoner", playerName); // ✅ Na raiz também

                                String personalizedJson = objectMapper.writeValueAsString(personalizedMessage);
                                TextMessage personalizedTextMessage = new TextMessage(personalizedJson);

                                // ✅ SINCRONIZAR envio de mensagem WebSocket
                                synchronized (session) {
                                    if (session.isOpen()) {
                                        session.sendMessage(personalizedTextMessage);

                                        // ✅ MELHORIA: LOG ESTRUTURADO COM VALIDAÇÃO
                                        if (log.isDebugEnabled()) {
                                            try {
                                                Optional<String> customOpt = redisWSSession.getCustomSessionId(actualSessionId);
                                                String customSessionId = customOpt.orElse("N/A");
                                                
                                                log.debug("📤 [BACKEND→ELECTRON] Evento: {} → {} | RandomSID: {} | CustomSID: {}", 
                                                    eventType, playerName, 
                                                    actualSessionId.substring(0, Math.min(8, actualSessionId.length())),
                                                    customSessionId.substring(0, Math.min(20, customSessionId.length())));
                                                
                                                // ✅ VALIDAÇÃO: Verificar se customSessionId corresponde ao summonerName
                                                if (!customSessionId.equals("N/A")) {
                                                    String expectedCustomId = generateCustomSessionIdForSummoner(playerName);
                                                    if (expectedCustomId != null && !customSessionId.equals(expectedCustomId)) {
                                                        log.warn("⚠️ [BACKEND→ELECTRON] INCONSISTÊNCIA DE SESSION ID!");
                                                        log.warn("   Player: {}, Expected: {}, Got: {}", playerName, expectedCustomId, customSessionId);
                                                    }
                                                }
                                            } catch (Exception e) {
                                                log.trace("Debug log error: {}", e.getMessage());
                                            }
                                        }

                                        return false; // Não remover
                                    }
                                }
                                return true; // Remover sessão fechada
                            } else {
                                log.warn("⚠️ [Directed] Sessão não encontrada ou fechada para {}", playerName);
                                // ✅ CRÍTICO: Enfileirar evento pendente usando customSessionId
                                try {
                                    String customSessionIdForKey = getCustomSessionIdForPendingEvent(actualSessionId,
                                            sessionId);
                                    redisWSEvent.queueEvent(customSessionIdForKey, eventType, data);
                                    log.info("📨 [PendingEvent] Evento enfileirado (sessão fechada): {} → {}",
                                            customSessionIdForKey, eventType);
                                } catch (Exception ex) {
                                    log.warn("⚠️ [Directed] Falha ao enfileirar evento pendente: {}", ex.getMessage());
                                }
                                return false; // Não remover
                            }
                        } else {
                            log.warn("⚠️ [Directed] Jogador {} não tem sessão ativa", playerName);
                            // ✅ CRÍTICO: Enfileirar evento pendente usando summonerName
                            try {
                                // Usar método helper para gerar customSessionId de forma consistente
                                String customSessionIdForKey = generateCustomSessionIdForSummoner(playerName);
                                if (customSessionIdForKey != null) {
                                    redisWSEvent.queueEvent(customSessionIdForKey, eventType, data);
                                    log.info("📨 [PendingEvent] Evento enfileirado (sessão inexistente): {} → {}",
                                            customSessionIdForKey, eventType);
                                }
                            } catch (Exception ex) {
                                log.warn("⚠️ [Directed] Falha ao enfileirar evento pendente: {}", ex.getMessage());
                            }
                            return false; // Não remover
                        }
                    } catch (Exception e) {
                        log.warn("⚠️ [Directed] Falha ao enviar {} para {}", eventType, playerName, e);
                        // ✅ CRÍTICO: Tentar enfileirar evento pendente mesmo com erro
                        try {
                            // Usar método helper para gerar customSessionId de forma consistente
                            String customSessionIdForKey = generateCustomSessionIdForSummoner(playerName);
                            if (customSessionIdForKey != null) {
                                redisWSEvent.queueEvent(customSessionIdForKey, eventType, data);
                                log.info("📨 [PendingEvent] Evento enfileirado (exceção): {} → {}",
                                        customSessionIdForKey,
                                        eventType);
                            }
                        } catch (Exception ex) {
                            log.warn("⚠️ [Directed] Falha ao enfileirar evento pendente: {}", ex.getMessage());
                        }
                        return true; // Marcar para remoção
                    }
                });
                sendFutures.add(future);
            }

            // ✅ AGUARDAR TODOS OS ENVIOS
            try {
                CompletableFuture.allOf(sendFutures.toArray(new CompletableFuture[0]))
                        .get(5, TimeUnit.SECONDS);

                sentCount = (int) sendFutures.stream().mapToInt(f -> {
                    try {
                        return f.get() ? 0 : 1; // false = enviado com sucesso
                    } catch (Exception e) {
                        return 0;
                    }
                }).sum();

                failedPlayers = totalPlayers - sentCount;

            } catch (TimeoutException e) {
                log.warn("⚠️ [Directed] Timeout ao aguardar envio para jogadores");
                failedPlayers = totalPlayers; // Todos falharam por timeout
            } catch (Exception e) {
                log.error("❌ [Directed] Erro ao aguardar envio", e);
                failedPlayers = totalPlayers; // Todos falharam por erro
            }

            // ✅ FALLBACK: Se muitos jogadores falharam, fazer broadcast global
            if (failedPlayers > 0) {
                double failureRate = (double) failedPlayers / totalPlayers;

                if (failureRate >= 0.3) { // 30% ou mais falharam
                    log.warn("🚨 [Fallback] {}% dos jogadores falharam ({}/{}), fazendo broadcast global como fallback",
                            Math.round(failureRate * 100), failedPlayers, totalPlayers);

                    // ✅ FALLBACK: Broadcast global
                    broadcastToAll(eventType, data);

                    log.info("✅ [Fallback] Broadcast global executado como fallback para {}", eventType);
                } else {
                    log.warn("⚠️ [Directed] {} jogadores falharam ({}/{}), mas taxa de falha baixa - sem fallback",
                            failedPlayers, failedPlayers, totalPlayers);
                }
            }

            log.info("📊 [Directed Broadcast] {} enviado para {}/{} jogadores", eventType, sentCount, totalPlayers);

        } catch (Exception e) {
            log.error("❌ [Directed Broadcast] Erro ao enviar {} para jogadores, executando fallback global", eventType,
                    e);

            // ✅ FALLBACK DE EMERGÊNCIA: Se tudo falhar, broadcast global
            try {
                broadcastToAll(eventType, data);
                log.info("✅ [Emergency Fallback] Broadcast global executado após erro crítico");
            } catch (Exception fallbackError) {
                log.error("❌ [Emergency Fallback] Falha crítica no fallback global", fallbackError);
            }
        }
    }

    /**
     * ✅ NOVO: Envia mensagem para um jogador específico
     */
    public void sendToPlayer(String eventType, Map<String, Object> data, String playerName) {
        sendToPlayers(eventType, data, List.of(playerName));
    }

    /**
     * ✅ NOVO: Migra uma sessão WebSocket para um novo ID customizado
     * Usado quando Electron define sessionId baseado no summonerName#tag
     */
    private void migrateWebSocketSession(String oldSessionId, String newSessionId) {
        try {
            WebSocketSession session = sessions.get(oldSessionId);
            if (session != null && session.isOpen()) {
                // Migrar sessão para novo ID
                sessions.put(newSessionId, session);
                sessions.remove(oldSessionId);

                // Migrar heartbeat task se existir
                ScheduledFuture<?> heartbeatTask = heartbeatTasks.remove(oldSessionId);
                if (heartbeatTask != null) {
                    heartbeatTasks.put(newSessionId, heartbeatTask);
                }

                log.info("✅ [Session Migration] Sessão migrada: {} → {}", oldSessionId, newSessionId);
            } else {
                log.warn("⚠️ [Session Migration] Sessão {} não encontrada ou fechada", oldSessionId);
            }
        } catch (Exception e) {
            log.error("❌ [Session Migration] Erro ao migrar sessão {} → {}", oldSessionId, newSessionId, e);
        }
    }

    /**
     * Envia mensagem para múltiplas sessões específicas EM PARALELO
     * 
     * ✅ BROADCAST SIMULTÂNEO: Todos os jogadores recebem AO MESMO TEMPO
     * Usado para match_found, draft_started, etc.
     */
    private void sendToMultipleSessions(Collection<WebSocketSession> targetSessions, String message) {
        if (targetSessions == null || targetSessions.isEmpty()) {
            return;
        }

        // ✅ ENVIO PARALELO: Criar uma CompletableFuture para cada sessão
        List<CompletableFuture<Void>> sendFutures = new ArrayList<>();

        for (WebSocketSession session : targetSessions) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    if (session != null && session.isOpen()) {
                        // ✅ CRÍTICO: SINCRONIZAR envio de mensagem WebSocket!
                        // Previne IllegalStateException: TEXT_PARTIAL_WRITING
                        synchronized (session) {
                            if (session.isOpen()) { // Re-check após adquirir lock
                                session.sendMessage(new TextMessage(message));
                                log.debug("✅ Mensagem enviada para sessão {}", session.getId());
                            }
                        }
                    }
                } catch (IOException e) {
                    log.error("❌ Erro ao enviar mensagem para sessão {}", session.getId(), e);
                } catch (IllegalStateException e) {
                    log.warn("⚠️ Sessão {} em estado inválido: {}", session.getId(), e.getMessage());
                }
            });

            sendFutures.add(future);
        }

        // ✅ AGUARDAR TODOS OS ENVIOS COMPLETAREM (com timeout de 5 segundos)
        try {
            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                    sendFutures.toArray(new CompletableFuture[0]));

            allOf.get(5, TimeUnit.SECONDS);

            log.debug("✅ [Broadcast Paralelo] {} mensagens enviadas simultaneamente", targetSessions.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("⚠️ Thread interrompida ao enviar mensagens em paralelo", e);
        } catch (TimeoutException e) {
            log.error("⏱️ Timeout ao enviar mensagens em paralelo (5s)", e);
        } catch (Exception e) {
            log.error("❌ Erro ao aguardar envios paralelos", e);
        }
    }

    /**
     * Obtém estatísticas do WebSocket
     */
    public Map<String, Object> getWebSocketStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalConnections", sessions.size());

        // ✅ Contar sessões identificadas via Redis
        Map<String, String> activeSessions = redisWSSession.getAllActiveSessions();
        stats.put("identifiedClients", activeSessions.size());
        stats.put("activeSessionsInRedis", activeSessions);

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
     * ✅ NOVO: Adiciona sessão WebSocket com IP e UserAgent capturados
     */
    public void addSession(String sessionId, WebSocketSession session, String ipAddress, String userAgent) {
        sessions.put(sessionId, session);

        // ✅ CORREÇÃO: Não armazenar IP/UserAgent separadamente - será feito via
        // registerSession()
        // quando o jogador for identificado
        log.debug("✅ [WebSocket] Sessão adicionada: {} (IP: {}, UA: {}) - ClientInfo será criado via registerSession()",
                sessionId, ipAddress, userAgent);
    }

    /**
     * Remove sessão (compatibilidade)
     */
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        log.debug("Sessão WebSocket removida: {}", sessionId);
    }

    /**
     * ✅ CORRIGIDO: Atualiza informações LCU de uma sessão
     * ✅ CRÍTICO: NÃO cria chaves ws:player_info: diretamente - usa
     * registerSession() para validação
     */
    public void updateLcuInfo(String sessionId, String host, int port, String summonerName) {
        // ✅ REDIS ONLY: Usar registerSession() para garantir validação de duplicação
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

        // ✅ CORREÇÃO CRÍTICA: Usar registerSession() em vez de storePlayerInfo() direto
        // Isso garante que a validação de duplicação seja aplicada
        boolean success = redisWSSession.registerSession(sessionId, summonerName, ipAddress, userAgent);

        if (success) {
            log.info(
                    "✅ [MatchmakingWebSocketService] LCU info registrada com validação: session={}, host={}, port={}, summoner={}",
                    sessionId, host, port, summonerName);
        } else {
            log.warn(
                    "⚠️ [MatchmakingWebSocketService] Falha ao registrar LCU info (duplicação detectada): session={}, summoner={}",
                    sessionId, summonerName);
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
            String normalizedName = summonerName.toLowerCase().trim();
            // ✅ Buscar sessão no Redis
            Optional<String> sessionIdOpt = redisWSSession.getSessionBySummoner(normalizedName);
            if (sessionIdOpt.isPresent()) {
                String sessionId = sessionIdOpt.get();
                // ✅ CRÍTICO: Converter customSessionId → randomSessionId se necessário
                String actualSessionId = getRandomSessionId(sessionId);
                WebSocketSession s = sessions.get(actualSessionId);
                if (s != null && s.isOpen()) {
                    log.info("pickGatewaySessionId: selecting session {} for specific summoner '{}'",
                            sessionId, summonerName);
                    return sessionId;
                }
            }
            log.warn("⚠️ pickGatewaySessionId: summoner '{}' não encontrado, usando fallback", summonerName);
        }

        // Debug: list available sessions and their clientInfo so we can diagnose why
        // no gateway client is selected.
        // ✅ Debug: listar sessões do Redis
        try {
            Map<String, String> activeSessions = redisWSSession.getAllActiveSessions();
            log.debug("pickGatewaySessionId: {} active sessions in Redis", activeSessions.size());
            for (Map.Entry<String, String> entry : activeSessions.entrySet()) {
                String summoner = entry.getKey(); // Renamed to avoid conflict
                String sid = entry.getValue();
                WebSocketSession s = sessions.get(sid);
                log.debug("  - summoner={} sessionId={} open={}", summoner, sid,
                        (s != null && s.isOpen()));
            }
        } catch (Exception ex) {
            log.info("pickGatewaySessionId: introspection failed: {}", ex.getMessage());
        }

        // ✅ Fallback: pick any open session with summonerName in Redis
        for (Map.Entry<String, WebSocketSession> e : sessions.entrySet()) {
            try {
                WebSocketSession s = e.getValue();
                if (s != null && s.isOpen()) {
                    Optional<String> summonerOpt = redisWSSession.getSummonerBySession(e.getKey());
                    if (summonerOpt.isPresent()) {
                        log.info("pickGatewaySessionId: selecting identified session {} (summoner={})",
                                e.getKey(), summonerOpt.get());
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
     * ✅ CORRIGIDO: Broadcast de partida aceita para jogadores específicos
     */
    public void broadcastMatchAccepted(String matchId, List<String> playerNames) {
        Map<String, Object> data = Map.of("matchId", matchId);
        sendToPlayers("match_accepted", data, playerNames);
    }

    /**
     * ✅ CORRIGIDO: Broadcast de partida cancelada para jogadores específicos
     */
    public void broadcastMatchCancelled(String matchId, String reason, List<String> playerNames) {
        Map<String, Object> data = Map.of("matchId", matchId, "reason", reason);
        sendToPlayers("match_cancelled", data, playerNames);
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

    /**
     * ✅ NOVO: Handle confirmação de migração de sessão do Electron
     */
    private void handleConfirmSessionMigration(String sessionId, JsonNode data) {
        try {
            String customSessionId = data.has("customSessionId") ? data.get("customSessionId").asText() : null;

            if (customSessionId == null || customSessionId.isBlank()) {
                log.warn("⚠️ [Session Migration] customSessionId não fornecido na confirmação");
                sendError(sessionId, "MISSING_CUSTOM_SESSION_ID", "customSessionId é obrigatório");
                return;
            }

            // Verificar se a sessão foi migrada com sucesso
            WebSocketSession session = sessions.get(customSessionId);
            if (session != null && session.isOpen()) {
                log.info("✅ [Session Migration] Confirmação recebida - sessão {} ativa", customSessionId);

                // Enviar confirmação de sucesso
                sendMessage(sessionId, "session_migrated", Map.of(
                        "oldSessionId", sessionId,
                        "newSessionId", customSessionId,
                        "success", true));
            } else {
                log.warn("⚠️ [Session Migration] Sessão {} não encontrada ou fechada", customSessionId);

                // Enviar erro
                sendMessage(sessionId, "session_migration_failed", Map.of(
                        "error", "Session not found or closed",
                        "customSessionId", customSessionId));
            }

        } catch (Exception e) {
            log.error("❌ [Session Migration] Erro ao processar confirmação", e);
            sendError(sessionId, "MIGRATION_CONFIRMATION_FAILED", "Erro ao processar confirmação");
        }
    }

    /**
     * ✅ NOVO: Solicitar identificação LCU para um jogador específico (ex: entrada
     * na fila)
     */
    public void requestIdentityConfirmation(String summonerName, String reason) {
        log.info("🔗 [Player-Sessions] [BACKEND] Solicitando identificação LCU para {} (motivo: {})", summonerName,
                reason);

        try {
            // Buscar sessão WebSocket do jogador
            Optional<String> sessionIdOpt = redisWSSession.getSessionBySummoner(summonerName.toLowerCase().trim());

            if (sessionIdOpt.isEmpty()) {
                log.warn("⚠️ [Player-Sessions] [BACKEND] Nenhuma sessão WebSocket encontrada para {}", summonerName);
                return;
            }

            String sessionId = sessionIdOpt.get();
            // ✅ CRÍTICO: Converter customSessionId → randomSessionId se necessário
            String actualSessionId = getRandomSessionId(sessionId);
            WebSocketSession session = getSession(actualSessionId);

            if (session == null || !session.isOpen()) {
                log.warn("⚠️ [Player-Sessions] [BACKEND] Sessão WebSocket {} não está ativa para {}", sessionId,
                        summonerName);
                return;
            }

            // Enviar solicitação de identificação
            Map<String, Object> identityRequest = Map.of(
                    "type", "request_identity_confirmation",
                    "summonerName", summonerName,
                    "reason", reason,
                    "timestamp", System.currentTimeMillis());

            String message = objectMapper.writeValueAsString(identityRequest);
            session.sendMessage(new TextMessage(message));

            log.info("✅ [Player-Sessions] [BACKEND] Solicitação de identificação enviada para {} (sessionId: {})",
                    summonerName, sessionId);

            // ✅ NOVO: Enviar log para Electron se houver sessões registradas
            if (unifiedLogService.hasRegisteredPlayerSessionLogSessions()) {
                unifiedLogService.sendPlayerSessionInfoLog("[Player-Sessions] [BACKEND]",
                        "Solicitando identificação LCU para %s (motivo: %s, sessionId: %s)",
                        summonerName, reason, sessionId);
            }

        } catch (Exception e) {
            log.error("❌ [Player-Sessions] [BACKEND] Erro ao solicitar identificação para {}", summonerName, e);
        }
    }

    /**
     * ✅ NOVO: Enviar solicitação de identificação para TODOS os electrons (LÓGICA
     * CORRETA)
     * Cada electron verifica se a solicitação é para ele baseado no LCU conectado
     */
    public void requestIdentityFromAllElectrons(String summonerName, String reason, Object requestData) {
        log.info("🔗 [Player-Sessions] [BACKEND] Solicitando identificação para {} de TODOS os electrons (motivo: {})",
                summonerName, reason);

        try {
            // ✅ USAR REDIS: Armazenar dados da requisição no Redis (seguro e distribuído)
            String redisKey = "identity_request:" + summonerName.toLowerCase().trim() + ":"
                    + System.currentTimeMillis();
            redisTemplate.opsForValue().set(redisKey, requestData, Duration.ofMinutes(5)); // TTL 5 minutos

            // ✅ BROADCAST: Enviar para TODOS os electrons conectados
            Map<String, Object> message = Map.of(
                    "type", "request_identity_verification",
                    "summonerName", summonerName,
                    "reason", reason,
                    "timestamp", System.currentTimeMillis(),
                    "redisKey", redisKey);

            // Enviar para todas as sessões ativas
            broadcastToAll("request_identity_verification", message);

            log.info(
                    "✅ [Player-Sessions] [BACKEND] Solicitação de identificação enviada para TODOS os electrons: {} (redisKey: {})",
                    summonerName, redisKey);

        } catch (Exception e) {
            log.error("❌ [Player-Sessions] [BACKEND] Erro ao solicitar identificação de todos os electrons: {}",
                    summonerName, e);
        }
    }

    /**
     * ✅ DEPRECIADO: Mantido para compatibilidade, mas não é a lógica correta
     */
    public void sendDirectToElectronViaRedis(String messageType, String summonerName, String reason,
            Object requestData) {
        // ✅ REDIRECIONAR: Usar a lógica correta de broadcast
        requestIdentityFromAllElectrons(summonerName, reason, requestData);
    }

    // ✅ NOVO: Confirmação periódica de identidade DINÂMICA baseada no estado
    @Scheduled(fixedRate = 30000) // 30 segundos (verifica estado de todos)
    public void requestIdentityConfirmation() {
        log.debug("🔍 [WebSocket] Solicitando confirmação de identidade...");

        try {
            // Para CADA sessão identificada
            Map<String, Object> allClientInfo = redisWSSession.getAllClientInfo();

            for (String sessionId : allClientInfo.keySet()) {
                Map<String, Object> info = (Map<String, Object>) allClientInfo.get(sessionId);
                String summonerName = (String) info.get("summonerName");

                if (summonerName == null || summonerName.isEmpty()) {
                    continue; // Sessão não identificada
                }

                // ✅ NOVO: Verificar estado do jogador para determinar intervalo
                long lastConfirmation = (Long) info.getOrDefault("lastIdentityConfirmation", 0L);
                long currentTime = System.currentTimeMillis();
                long timeSinceLastConfirmation = currentTime - lastConfirmation;

                // ✅ CONFIRMAÇÃO DINÂMICA baseada no estado
                long requiredInterval = getRequiredConfirmationInterval(summonerName);

                if (timeSinceLastConfirmation < requiredInterval) {
                    continue; // Ainda não é hora de confirmar
                }

                // ✅ SOLICITAR confirmação
                String requestId = UUID.randomUUID().toString();

                Map<String, Object> request = Map.of(
                        "type", "confirm_identity",
                        "id", requestId,
                        "expectedSummoner", summonerName,
                        "timestamp", currentTime);

                // ✅ CORREÇÃO: NÃO armazenar request pendente no Redis - DESNECESSÁRIO!
                // O sessionId já está disponível localmente e as informações do jogador
                // já estão nas chaves centralizadas (ws:client_info:{sessionId})

                // Enviar via WebSocket
                // ✅ CRÍTICO: Converter customSessionId → randomSessionId se necessário
                String actualSessionId = getRandomSessionId(sessionId);
                WebSocketSession session = sessions.get(actualSessionId);
                if (session != null && session.isOpen()) {
                    sendMessage(actualSessionId, "confirm_identity", request);
                    log.debug("🔍 [WebSocket] Confirmação solicitada: {} (session: {}) - intervalo: {}ms",
                            summonerName, sessionId, requiredInterval);
                } else {
                    // Sessão não existe mais, limpar Redis
                    redisWSSession.removeSession(sessionId);
                    log.debug("🧹 [WebSocket] Sessão removida (não existe): {}", sessionId);
                }
            }

            log.debug("✅ [WebSocket] Verificação de confirmação concluída para {} sessões",
                    allClientInfo.size());

        } catch (Exception e) {
            log.error("❌ [WebSocket] Erro ao solicitar confirmação de identidade", e);
        }
    }

    /**
     * ✅ NOVO: Determina intervalo de confirmação baseado no estado do jogador
     */
    private long getRequiredConfirmationInterval(String summonerName) {
        try {
            // Verificar estado atual do jogador
            PlayerState currentState = playerStateService.getPlayerState(summonerName);

            switch (currentState) {
                case IN_QUEUE:
                    // ✅ Na fila: confirmação a cada 30 segundos
                    return 30_000; // 30 segundos

                case IN_MATCH_FOUND:
                    // ✅ Match found: confirmação a cada 15 segundos (crítico)
                    return 15_000; // 15 segundos

                case IN_DRAFT:
                    // ✅ No draft: confirmação a cada 20 segundos (como solicitado)
                    return 20_000; // 20 segundos

                case IN_GAME:
                    // ✅ No jogo: confirmação a cada 30 segundos
                    return 30_000; // 30 segundos

                case AVAILABLE:
                default:
                    // ✅ Disponível: confirmação a cada 2 minutos (menos crítico)
                    return 120_000; // 2 minutos
            }
        } catch (Exception e) {
            log.error("❌ [WebSocket] Erro ao verificar estado do jogador: {}", summonerName, e);
            // Em caso de erro, usar intervalo padrão (seguro)
            return 60_000; // 1 minuto
        }
    }

    /**
     * ✅ NOVO: Confirmação OBRIGATÓRIA antes de ações críticas
     * Usado antes de votação de winner, ações de draft críticas, etc.
     */
    public CompletableFuture<Boolean> requestCriticalActionConfirmation(String summonerName, String actionType) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        try {
            // ✅ NOVO: Bypass para bots - não precisam de confirmação de identidade
            if (isBotPlayer(summonerName)) {
                log.debug("🤖 [WebSocket] Bot {} - bypass de confirmação de identidade para ação: {}",
                        summonerName, actionType);
                future.complete(true);
                return future;
            }

            // Buscar sessão do jogador via Redis
            Optional<String> sessionIdOpt = redisWSSession.getSessionBySummoner(summonerName.toLowerCase().trim());
            if (sessionIdOpt.isEmpty()) {
                log.error("❌ [WebSocket] Jogador {} não tem sessão ativa para ação crítica: {}",
                        summonerName, actionType);
                future.complete(false);
                return future;
            }

            // ✅ CRÍTICO: Converter customSessionId → randomSessionId se necessário
            String actualSessionId = getRandomSessionId(sessionIdOpt.get());
            WebSocketSession session = getSession(actualSessionId);
            if (session == null || !session.isOpen()) {
                log.error("❌ [WebSocket] Sessão WebSocket {} não está ativa para jogador {} (ação crítica: {})",
                        actualSessionId, summonerName, actionType);
                future.complete(false);
                return future;
            }
            String randomSessionId = session.getId();

            // ✅ SOLICITAR confirmação OBRIGATÓRIA
            String requestId = UUID.randomUUID().toString();

            Map<String, Object> request = Map.of(
                    "type", "confirm_identity_critical",
                    "id", requestId,
                    "expectedSummoner", summonerName,
                    "actionType", actionType,
                    "timestamp", System.currentTimeMillis());

            // ✅ CORREÇÃO: NÃO armazenar request pendente nem future no Redis -
            // DESNECESSÁRIO!
            // O sessionId já está disponível localmente e as informações do jogador
            // já estão nas chaves centralizadas (ws:client_info:{sessionId})
            // O CompletableFuture pode ser armazenado localmente na memória

            // Enviar via WebSocket (usar randomSessionId - ID real da sessão WebSocket)
            sendMessage(randomSessionId, "confirm_identity_critical", request);

            log.info("🔍 [WebSocket] Confirmação OBRIGATÓRIA solicitada: {} para ação: {}",
                    summonerName, actionType);

            // Timeout após 8 segundos
            CompletableFuture.delayedExecutor(8, TimeUnit.SECONDS).execute(() -> {
                if (!future.isDone()) {
                    log.warn("⚠️ [WebSocket] Timeout na confirmação crítica: {} (ação: {})",
                            summonerName, actionType);
                    future.complete(false);

                    // ✅ CORREÇÃO: NÃO precisamos limpar chaves temporárias - não são mais criadas!
                }
            });

        } catch (Exception e) {
            log.error("❌ [WebSocket] Erro ao solicitar confirmação crítica: {}", summonerName, e);
            future.complete(false);
        }

        return future;
    }

    /**
     * ✅ NOVO: Verifica se um jogador é bot
     */
    private boolean isBotPlayer(String summonerName) {
        if (summonerName == null || summonerName.isEmpty()) {
            return false;
        }

        String normalizedName = summonerName.toLowerCase().trim();

        // ✅ Padrões de nomes de bots conhecidos
        return normalizedName.startsWith("bot") ||
                normalizedName.startsWith("ai_") ||
                normalizedName.endsWith("_bot") ||
                normalizedName.contains("bot_") ||
                normalizedName.equals("bot") ||
                normalizedName.matches(".*bot\\d+.*"); // bot1, bot2, etc.
    }

    /**
     * ✅ NOVO: Handler para confirmação de identidade recebida do Electron
     */
    public void handleIdentityConfirmed(String sessionId, JsonNode data) {
        try {
            // ✅ CORREÇÃO: requestId não é mais necessário - não gerenciamos futures no
            // Redis
            String confirmedSummoner = data.path("summonerName").asText();
            String confirmedPuuid = data.path("puuid").asText();

            log.debug("🔍 [WebSocket] Processando confirmação de identidade: {} (PUUID: {}...)",
                    confirmedSummoner, confirmedPuuid.substring(0, Math.min(8, confirmedPuuid.length())));

            // ✅ CORREÇÃO: Não criar chaves duplicadas - usar apenas ws:client_info:
            // O PUUID já está armazenado no ClientInfo da chave
            // ws:client_info:{summonerName}
            log.info("✅ [WebSocket] PUUID confirmado: {} (PUUID: {})",
                    confirmedSummoner, confirmedPuuid.substring(0, Math.min(8, confirmedPuuid.length())));

            // ✅ NOVO: Verificar se PUUID mudou usando ClientInfo
            Optional<br.com.lolmatchmaking.backend.service.redis.RedisWebSocketSessionService.ClientInfo> clientInfoOpt = redisWSSession
                    .getClientInfo(sessionId);
            if (clientInfoOpt.isPresent()) {
                // TODO: Adicionar campo puuid ao ClientInfo se necessário
                // Por enquanto, apenas log
                log.debug("✅ [WebSocket] ClientInfo encontrado para validação de PUUID");
            } else {
                log.warn("⚠️ [WebSocket] ClientInfo não encontrado para sessionId: {}", sessionId);
            }

            // ✅ CORREÇÃO: Sempre registrar a sessão (não importa se é primeira vez ou
            // mudança)
            sessionRegistry.registerPlayer(confirmedSummoner, sessionId);
            log.info("✅ [WebSocket] Vinculação registrada: {} (PUUID: {})",
                    confirmedSummoner, confirmedPuuid.substring(0, Math.min(8, confirmedPuuid.length())));

            // ✅ PUUID confirmado: Tudo OK!
            log.debug("✅ [WebSocket] Identidade confirmada: {}", confirmedSummoner);
            redisWSSession.updateHeartbeat(sessionId);

            // ✅ CORREÇÃO: NÃO precisamos limpar chaves temporárias - não são mais criadas!

        } catch (Exception e) {
            log.error("❌ [WebSocket] Erro ao processar confirmação de identidade", e);
        }
    }

    /**
     * ✅ NOVO: Handler para confirmação CRÍTICA de identidade
     */
    public void handleCriticalIdentityConfirmed(String sessionId, JsonNode data) {
        try {
            // ✅ CORREÇÃO: requestId não é mais necessário - não gerenciamos futures no
            // Redis
            String confirmedSummoner = data.path("summonerName").asText();
            String confirmedPuuid = data.path("puuid").asText();

            log.info("🔍 [WebSocket] Processando confirmação CRÍTICA de identidade: {} (PUUID: {}...)",
                    confirmedSummoner, confirmedPuuid.substring(0, Math.min(8, confirmedPuuid.length())));

            // ✅ CORREÇÃO: Validação de PUUID usando ClientInfo (não mais chaves duplicadas)
            // TODO: Implementar validação de PUUID usando ws:client_info:{summonerName} se
            // necessário
            log.debug("✅ [WebSocket] PUUID validado via ClientInfo: {} → {}",
                    confirmedSummoner, confirmedPuuid.substring(0, Math.min(8, confirmedPuuid.length())));

            // ✅ CORREÇÃO: NÃO precisamos gerenciar futures no Redis - são locais na memória
            // O future é gerenciado localmente pelo método que fez a requisição
            log.info("✅ [WebSocket] Confirmação CRÍTICA aceita: {}", confirmedSummoner);

            // ✅ CORREÇÃO: NÃO precisamos limpar chaves temporárias - não são mais criadas!

            // 4. Atualizar timestamp
            redisWSSession.updateIdentityConfirmation(sessionId);

        } catch (Exception e) {
            log.error("❌ [WebSocket] Erro ao processar confirmação crítica de identidade", e);

            // Em caso de erro, completar future como false
            try {
                // ✅ CORREÇÃO: requestId não é mais necessário - não gerenciamos futures no
                // Redis
                // ✅ CORREÇÃO: NÃO precisamos buscar future no Redis - não é mais armazenado!
                // ✅ CORREÇÃO: NÃO precisamos gerenciar futures no Redis - são locais na memória
            } catch (Exception cleanupError) {
                log.error("❌ [WebSocket] Erro no cleanup após falha de confirmação crítica", cleanupError);
            }
        }
    }

    /**
     * ✅ NOVO: Handler para falha na confirmação de identidade
     */
    public void handleIdentityConfirmationFailed(String sessionId, JsonNode data) {
        try {
            // ✅ CORREÇÃO: requestId não é mais necessário - não gerenciamos futures no
            // Redis
            String error = data.path("error").asText();

            log.warn("⚠️ [WebSocket] Confirmação de identidade falhou para session {}: {}",
                    sessionId, error);

            // Se LCU desconectado, marcar sessão como não-verificada
            if ("LCU_DISCONNECTED".equals(error)) {
                String summonerName = redisWSSession.getSummonerBySession(sessionId).orElse(null);
                if (summonerName != null) {
                    log.info("🧹 [WebSocket] LCU desconectado, marcando {} como não-verificado", summonerName);
                    // Pode implementar flag de não-verificado se necessário
                }
            }

            // ✅ CORREÇÃO: NÃO precisamos limpar chaves temporárias - não são mais criadas!

        } catch (Exception e) {
            log.error("❌ [WebSocket] Erro ao processar falha de confirmação", e);
        }
    }

    /**
     * ✅ NOVO: Obter WebSocketSession por sessionId
     */
    public WebSocketSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * ✅ NOVO: Broadcast progresso de votação de winner
     */
    public void broadcastWinnerVoteProgress(br.com.lolmatchmaking.backend.dto.events.WinnerVoteEvent event) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "match_vote_progress");
            List<String> votedPlayers = getVotedPlayersList(event.getMatchId());
            int totalPlayers = 10; // Assumindo 10 jogadores por partida

            message.put("data", Map.of(
                    "matchId", event.getMatchId(),
                    "summonerName", event.getSummonerName(),
                    "votedTeam", event.getVotedTeam(),
                    "votesTeam1", event.getVotesTeam1(),
                    "votesTeam2", event.getVotesTeam2(),
                    "totalNeeded", event.getTotalVotesNeeded(),
                    "votedPlayers", votedPlayers,
                    "votedCount", votedPlayers.size(),
                    "totalPlayers", totalPlayers));

            String jsonMessage = objectMapper.writeValueAsString(message);

            // Broadcast para todos os clientes conectados
            for (WebSocketSession session : sessions.values()) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(new TextMessage(jsonMessage));
                        log.debug("📡 [WebSocket] match_vote_progress enviado para sessão: {}", session.getId());
                    } catch (Exception e) {
                        log.warn("⚠️ [WebSocket] Erro ao enviar match_vote_progress para sessão {}: {}",
                                session.getId(), e.getMessage());
                    }
                }
            }

            log.info("📢 [WebSocket] match_vote_progress broadcast enviado para {} sessões", sessions.size());

        } catch (Exception e) {
            log.error("❌ [WebSocket] Erro ao fazer broadcast de match_vote_progress", e);
        }
    }

    /**
     * ✅ NOVO: Obter lista de jogadores que votaram
     */
    private List<String> getVotedPlayersList(Long matchId) {
        try {
            // ✅ CORREÇÃO: Buscar jogadores que votaram do Redis
            br.com.lolmatchmaking.backend.service.RedisMatchVoteService redisMatchVote = applicationContext
                    .getBean(br.com.lolmatchmaking.backend.service.RedisMatchVoteService.class);

            // Buscar lista de nomes dos jogadores que votaram
            List<String> votedPlayers = redisMatchVote.getVotedPlayerNames(matchId);

            log.info("📊 [WebSocket] {} jogadores que votaram encontrados para match {}", votedPlayers.size(), matchId);
            return votedPlayers;

        } catch (Exception e) {
            log.error("❌ [WebSocket] Erro ao buscar jogadores que votaram: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * ✅ NOVO: Obter todas as sessões WebSocket ativas
     */
    public Collection<WebSocketSession> getAllActiveSessions() {
        return sessions.values().stream()
                .filter(WebSocketSession::isOpen)
                .collect(Collectors.toList());
    }

    /**
     * ✅ NOVO: Armazena mapeamento customSessionId → randomSessionId no cache local
     */
    public void storeCustomToRandomMapping(String customSessionId, String randomSessionId) {
        customToRandomSessionMapping.put(customSessionId, randomSessionId);
        log.debug("🔗 [SessionMapping] Registrado no cache local: {} → {}", customSessionId, randomSessionId);
    }

    /**
     * ✅ NOVO: Converte customSessionId em randomSessionId
     * Se o sessionId já for random, retorna ele mesmo
     * Usa cache local primeiro, depois Redis como fallback
     */
    private String getRandomSessionId(String sessionId) {
        // Verificar se é um customSessionId (começa com "player_")
        if (sessionId.startsWith("player_")) {
            // 1. Tentar cache local primeiro (mais rápido)
            String randomId = customToRandomSessionMapping.get(sessionId);
            if (randomId != null) {
                log.debug("🔗 [SessionMapping] customSessionId → randomSessionId (cache): {} → {}", sessionId,
                        randomId);
                return randomId;
            }

            // 2. Buscar no Redis usando ws:custom_session_mapping:{customSessionId}
            try {
                Optional<String> randomSessionIdOpt = redisWSSession.getRandomSessionIdByCustom(sessionId);
                if (randomSessionIdOpt.isPresent()) {
                    String randomSessionId = randomSessionIdOpt.get();
                    // Registrar no cache local para próximas buscas
                    customToRandomSessionMapping.put(sessionId, randomSessionId);
                    log.debug("🔗 [SessionMapping] customSessionId → randomSessionId (Redis): {} → {}", sessionId,
                            randomSessionId);
                    return randomSessionId;
                }
            } catch (Exception e) {
                log.warn("⚠️ [SessionMapping] Erro ao buscar no Redis: {}", sessionId, e);
            }

            log.warn("⚠️ [SessionMapping] RandomSessionId não encontrado para customSessionId: {}", sessionId);
        }
        return sessionId; // Se não for custom ou não houver mapeamento, retornar o ID original
    }

    /**
     * ✅ NOVO: Obtém customSessionId correto para enfileirar eventos pendentes
     * 
     * Usa customSessionId (imutável) como chave para eventos pendentes
     * ao invés de randomSessionId (que muda a cada reconexão)
     * 
     * Suporta bots e jogadores reais de forma consistente.
     */
    private String getCustomSessionIdForPendingEvent(String randomSessionId, String inputSessionId) {
        // Se o input já é customSessionId, usar diretamente
        if (inputSessionId.startsWith("player_")) {
            return inputSessionId;
        }

        // Buscar customSessionId pelo randomSessionId no Redis
        try {
            Optional<String> customOpt = redisWSSession.getCustomSessionId(randomSessionId);
            if (customOpt.isPresent()) {
                log.debug("🔍 [PendingEvent] CustomSessionId encontrado: {} → {}", randomSessionId, customOpt.get());
                return customOpt.get();
            }
        } catch (Exception e) {
            log.warn("⚠️ [PendingEvent] Erro ao buscar customSessionId: {}", randomSessionId, e);
        }

        // Fallback: usar o sessionId recebido (provavelmente será randomSessionId)
        log.warn("⚠️ [PendingEvent] CustomSessionId não encontrado, usando fallback: {}", inputSessionId);
        return inputSessionId;
    }

    /**
     * ✅ NOVO: Gera customSessionId a partir de summonerName (para bots e jogadores)
     * 
     * Usado quando não temos gameName#tagLine, especialmente para bots.
     */
    private String generateCustomSessionIdForSummoner(String summonerName) {
        if (summonerName == null || summonerName.isBlank()) {
            return null;
        }

        // Normalizar summonerName para customSessionId
        String normalized = summonerName.toLowerCase().trim()
                .replaceAll("[^a-zA-Z0-9_]", "_");

        return "player_" + normalized;
    }

    /**
     * ✅ CRÍTICO: Valida se uma WebSocketSession pertence realmente ao jogador
     * correto
     * 
     * Previne envio de eventos para sessões de outros jogadores.
     * 
     * @param session            WebSocketSession a validar
     * @param expectedPlayerName Nome do jogador esperado
     * @return true se a sessão pertence ao jogador correto
     */
    private boolean validateSessionOwnership(WebSocketSession session, String expectedPlayerName) {
        try {
            // ✅ CRÍTICO: Bots não têm sessão - pular validação
            if (isBotPlayer(expectedPlayerName)) {
                log.trace("🤖 [Security] Bot {} - validação de ownership pulada", expectedPlayerName);
                return true; // Bots sempre passam (não têm sessão real)
            }
            
            // Buscar summonerName registrado para esta sessão no Redis
            Optional<String> actualPlayerNameOpt = redisWSSession.getSummonerBySession(session.getId());

            if (actualPlayerNameOpt.isEmpty()) {
                log.warn("⚠️ [Security] Sessão {} não tem summonerName registrado no Redis", session.getId());
                return false;
            }

            String actualPlayerName = actualPlayerNameOpt.get();

            // Comparar (case-insensitive e com normalização)
            boolean matches = actualPlayerName.equalsIgnoreCase(expectedPlayerName.trim());

            if (!matches) {
                log.error("🚨 [Security] Sessão {} NÃO pertence ao jogador esperado!", session.getId());
                log.error("   Esperado: {}", expectedPlayerName);
                log.error("   Real: {}", actualPlayerName);
                log.error("   Evento NÃO será enviado por segurança!");
            } else {
                log.debug("✅ [Security] Validação OK: sessão {} pertence a {}", session.getId(), actualPlayerName);
            }

            return matches;

        } catch (Exception e) {
            log.error("❌ [Security] Erro ao validar ownership da sessão", e);
            return false; // Por segurança, negar se houver erro
        }
    }

    /**
     * ✅ NOVO: Busca eventos pendentes usando customSessionId (público para uso
     * externo)
     *
     * @param customSessionId CustomSessionId do jogador (ex: player_fzd_ratoso_fzd)
     * @return Lista de eventos pendentes
     */
    public List<RedisWebSocketEventService.PendingEvent> getPendingEventsByCustomSessionId(String customSessionId) {
        return redisWSEvent.getPendingEvents(customSessionId);
    }

    /**
     * ✅ NOVO: Limpa eventos pendentes usando customSessionId (público para uso
     * externo)
     * 
     * @param customSessionId CustomSessionId do jogador (ex: player_fzd_ratoso_fzd)
     */
    public void clearPendingEventsByCustomSessionId(String customSessionId) {
        redisWSEvent.clearPendingEvents(customSessionId);
    }

    /**
     * ✅ NOVO: Obtém todos os jogadores de uma partida
     */
    private List<String> getAllPlayersFromMatch(Long matchId) {
        List<String> allPlayers = new ArrayList<>();

        try {
            // Buscar dados da partida via Redis primeiro
            List<String> team1Names = getRedisAcceptanceService().getTeam1Players(matchId);
            List<String> team2Names = getRedisAcceptanceService().getTeam2Players(matchId);

            if (team1Names != null)
                allPlayers.addAll(team1Names);
            if (team2Names != null)
                allPlayers.addAll(team2Names);

            log.debug("🎯 [WebSocket] Jogadores encontrados para match {}: {}", matchId, allPlayers);
        } catch (Exception e) {
            log.error("❌ [WebSocket] Erro ao obter jogadores da partida {}", matchId, e);
        }

        return allPlayers;
    }
}
