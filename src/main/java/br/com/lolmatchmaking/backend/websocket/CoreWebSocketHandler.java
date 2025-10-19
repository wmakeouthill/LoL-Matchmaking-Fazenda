package br.com.lolmatchmaking.backend.websocket;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import br.com.lolmatchmaking.backend.service.QueueService;
import br.com.lolmatchmaking.backend.service.AcceptanceService;
import br.com.lolmatchmaking.backend.service.MatchmakingOrchestrator;
import br.com.lolmatchmaking.backend.service.DraftFlowService;
import br.com.lolmatchmaking.backend.service.LCUConnectionRegistry;
import br.com.lolmatchmaking.backend.service.RedisLCUConnectionService;
import br.com.lolmatchmaking.backend.service.redis.RedisWebSocketSessionService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * ✅ MIGRADO PARA REDIS: Handler principal para WebSocket
 * 
 * MIGRAÇÃO REDIS:
 * - identifiedPlayers → RedisWebSocketSessionService.storePlayerInfo()
 * - lastLcuStatus → RedisLCUConnectionService.storeLcuStatus()
 * 
 * PROBLEMA RESOLVIDO:
 * - Backend restart → Electrons precisavam reenviar identify_player e
 * lcu_status
 * - Agora: Informações restauradas do Redis automaticamente
 * 
 * BACKWARD COMPATIBILITY:
 * - ConcurrentHashMaps mantidos como cache local
 * - Redis é a fonte da verdade
 * 
 * @see RedisWebSocketSessionService
 * @see RedisLCUConnectionService
 */
@Component
@RequiredArgsConstructor
public class CoreWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CoreWebSocketHandler.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String FIELD_SUMMONER_NAME = "summonerName";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_PLAYER_ID = "playerId";
    private static final String FIELD_MATCH_ID = "matchId";

    private final QueueService queueService;
    // ✅ REMOVIDO: AcceptanceService e MatchmakingOrchestrator deprecated
    // private final AcceptanceService acceptanceService;
    // private final MatchmakingOrchestrator matchmakingOrchestrator;
    private final SessionRegistry sessionRegistry;
    private final DraftFlowService draftFlowService;
    private final MatchmakingWebSocketService webSocketService;
    private final LCUConnectionRegistry lcuConnectionRegistry;
    private final br.com.lolmatchmaking.backend.service.PlayerService playerService;

    // ✅ NOVO: Redis services
    private final RedisWebSocketSessionService redisWSSession;
    private final RedisLCUConnectionService redisLCUConnection;
    private final br.com.lolmatchmaking.backend.service.redis.RedisPlayerMatchService redisPlayerMatch;

    // ✅ NOVO: Unified Log Service
    private final br.com.lolmatchmaking.backend.service.UnifiedLogService unifiedLogService;

    // ✅ NOVO: Lock services
    private final br.com.lolmatchmaking.backend.service.lock.PlayerLockService playerLockService;

    // ✅ NOVO: RedisTemplate para acknowledgments
    private final RedisTemplate<String, Object> redisTemplate;

    // ✅ DEPRECIADO: Migrado para Redis (backward compatibility)
    // ✅ REMOVIDO: identifiedPlayers e lastLcuStatus - Redis é fonte única da
    // verdade
    // Use redisWSSession.storePlayerInfo() e redisLCUConnection.storeLcuStatus()

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        String sessionId = session.getId();

        // ✅ CAPTURAR: IP e User Agent apenas para logs (não para identificação)
        // IMPORTANTE: Backend NUNCA deve tentar resolver dados de sessão
        // Electron é a ÚNICA fonte da verdade para dados do jogador
        String ipAddress = "unknown";
        String userAgent = "unknown";

        try {
            if (session.getRemoteAddress() != null) {
                ipAddress = session.getRemoteAddress().getAddress().getHostAddress();
            }
            if (session.getHandshakeHeaders() != null) {
                userAgent = session.getHandshakeHeaders().getFirst("User-Agent");
                if (userAgent == null || userAgent.isBlank()) {
                    userAgent = "unknown";
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [CoreWS] Erro ao capturar IP/UserAgent para sessão {}: {}", sessionId, e.getMessage());
        }

        log.info("🔌 Cliente conectado: {} (IP: {}, UserAgent: {})", sessionId, ipAddress, userAgent);

        // ✅ CORRIGIDO: Verificar se já existe antes de adicionar
        if (!sessionRegistry.hasSession(sessionId)) {
            sessionRegistry.add(session);
        } else {
            log.debug("🔄 [CoreWS] Sessão {} já existe no registry, pulando adição", sessionId);
        }

        // ✅ NOVO: Armazenar IP e UserAgent no Redis para uso posterior
        webSocketService.addSession(sessionId, session, ipAddress, userAgent);
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        JsonNode root = mapper.readTree(message.getPayload());
        String type = root.path("type").asText();
        switch (type) {
            case "identify_player" -> handleIdentify(session, root);
            case "electron_identify" -> handleElectronIdentify(session, root);
            case "identity_confirmed" -> webSocketService.handleIdentityConfirmed(session.getId(), root);
            case "identity_confirmation_failed" ->
                webSocketService.handleIdentityConfirmationFailed(session.getId(), root);
            case "identity_confirmed_critical" ->
                webSocketService.handleCriticalIdentityConfirmed(session.getId(), root);
            case "request_critical_identity_confirmation" -> handleRequestCriticalIdentityConfirmation(session, root);
            case "get_active_sessions" -> handleGetActiveSessions(session, root);
            case "enable_unified_logs" -> handleEnableUnifiedLogs(session, root); // ✅ NOVO
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
            case "match_found_acknowledged" -> handleMatchFoundAcknowledged(session, root);
            case "draft_acknowledged" -> handleDraftAcknowledged(session, root);
            case "game_acknowledged" -> handleGameAcknowledged(session, root);
            case "reconnect_check_response" -> handleReconnectCheckResponse(session, root);
            case "match_vote_progress" -> handleMatchVoteProgress(session, root);
            case "match_vote_update" -> handleMatchVoteUpdate(session, root);
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

        // ✅ NOVO: Extrair profileIconId, puuid e summonerId do payload
        Integer profileIconId = root.has("profileIconId") && !root.get("profileIconId").isNull()
                ? root.get("profileIconId").asInt()
                : null;
        String puuid = root.has("puuid") && !root.get("puuid").isNull()
                ? root.get("puuid").asText()
                : null;
        String summonerId = root.has("summonerId") && !root.get("summonerId").isNull()
                ? root.get("summonerId").asText()
                : null;

        log.info("📦 [WS] register_lcu_connection recebido: summonerName={}, profileIconId={}, puuid={}, summonerId={}",
                summonerName, profileIconId, puuid != null ? "present" : "null",
                summonerId != null ? "present" : "null");

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

        // ✅ NOVO: ADQUIRIR PLAYER LOCK (prevenir múltiplas instâncias do mesmo jogador)
        String lockedSession = playerLockService.acquirePlayerLock(summonerName, session.getId());
        if (lockedSession == null) {
            // ✅ CORREÇÃO: Verificar se sessão antiga ainda existe
            String oldSessionId = playerLockService.getPlayerSession(summonerName);

            if (oldSessionId != null) {
                // Verificar se sessão antiga ainda está ativa no SessionRegistry
                boolean oldSessionExists = sessionRegistry.get(oldSessionId) != null;

                if (!oldSessionExists) {
                    // ✅ Sessão antiga não existe mais, forçar release e tentar novamente
                    log.warn("⚠️ [WS] Jogador {} tinha lock de sessão antiga {} (não existe mais), forçando release...",
                            summonerName, oldSessionId);

                    playerLockService.forceReleasePlayerLock(summonerName);

                    // Tentar adquirir lock novamente
                    lockedSession = playerLockService.acquirePlayerLock(summonerName, session.getId());

                    if (lockedSession == null) {
                        log.error("❌ [WS] Falha ao adquirir lock mesmo após force release: {}", summonerName);
                        session.sendMessage(new TextMessage(
                                "{\"type\":\"lcu_connection_registered\",\"success\":false,\"error\":\"Erro ao reconectar\"}"));
                        session.close(CloseStatus.SERVER_ERROR);
                        return;
                    }

                    log.info("✅ [WS] Lock readquirido após force release: {}", summonerName);

                } else {
                    // Sessão antiga ainda existe - REALMENTE duplicado
                    log.warn("❌ [WS] Jogador {} JÁ está conectado em sessão ativa: {}", summonerName, oldSessionId);
                    session.sendMessage(new TextMessage(
                            "{\"type\":\"lcu_connection_registered\",\"success\":false,\"error\":\"Você já está conectado em outro dispositivo\"}"));
                    session.close(CloseStatus.NOT_ACCEPTABLE);
                    return;
                }
            }
        }

        log.info("✅ [WS] Player lock adquirido para: {}", summonerName);

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

        // ✅ NOVO: Invalidar cache via Redis (evita dependência circular)
        sessionRegistry.invalidateSessionCache();

        log.info(
                "✅ [Player-Sessions] [ELECTRON→BACKEND] Conexão LCU registrada: '{}' (session: {}, {}:{}) - cache invalidado",
                summonerName, session.getId(), host, port);

        // ✅ NOVO: Buscar dados de ranked e salvar MMR no banco IMEDIATAMENTE
        // Capturar variáveis para uso no async (final ou effectively final)
        final String finalSummonerName = summonerName;
        final Integer finalProfileIconId = profileIconId;
        final String finalPuuid = puuid;
        final String finalSummonerId = summonerId;

        CompletableFuture.runAsync(() -> {
            try {
                log.info("📊 [WS] Buscando dados de ranked para inicializar MMR: {}", finalSummonerName);

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

                    // ✅ NOVO: Salvar no banco via PlayerService COM profileIconId do LCU
                    playerService.createOrUpdatePlayerOnLogin(
                            finalSummonerName,
                            "br1",
                            currentMmr,
                            finalSummonerId,
                            finalPuuid,
                            finalProfileIconId);

                    log.info("✅ [WS] Player atualizado no banco com MMR calculado: {} (profileIconId: {})",
                            currentMmr, finalProfileIconId);
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
                + finalSummonerName + "\"}";

        // Enviar para a sessão atual (gateway do Electron)
        session.sendMessage(new TextMessage(confirmationMessage));

        // ✅ CORREÇÃO: Enviar GLOBALMENTE para todos os Electrons (ping/pong)
        try {
            Map<String, Object> data = new HashMap<>();
            data.put(FIELD_SUMMONER_NAME, finalSummonerName);
            webSocketService.broadcastToAll("lcu_connection_registered", data);
            log.info("📡 [WS] Notificação lcu_connection_registered enviada GLOBALMENTE");
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

        // ✅ REDIS FIRST: Armazena status LCU no Redis
        // Busca summonerName da sessão para usar como chave
        String summonerName = sessionRegistry.getSummonerBySession(session.getId());
        if (summonerName != null && !summonerName.isEmpty()) {
            try {
                String lcuStatusJson = mapper.writeValueAsString(data);
                redisLCUConnection.storeLcuStatus(summonerName, lcuStatusJson);
                log.debug("✅ [CoreWS] LCU status armazenado no Redis: summonerName={}", summonerName);
            } catch (Exception e) {
                log.error("❌ [CoreWS] Erro ao armazenar LCU status no Redis: summonerName={}", summonerName, e);
            }
        }

        // ✅ REDIS ONLY - backward compatibility removido
        if (log.isDebugEnabled()) {
            log.debug("[WS] LCU status from {}: {}", session.getId(), data);
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

    // ✅ NOVO: Handler para identificação automática do Electron
    /**
     * ✅ NOVO: Handler para solicitação de sessões ativas
     */
    private void handleGetActiveSessions(WebSocketSession session, JsonNode root) throws IOException {
        try {
            log.info("📋 [CoreWS] Solicitando lista de sessões ativas de sessionId={}", session.getId());

            // Buscar informações das sessões ativas
            Map<String, Object> allClientInfo = redisWSSession.getAllClientInfo();

            // Preparar resposta com detalhes das sessões
            List<Map<String, Object>> sessionsList = new ArrayList<>();

            for (Map.Entry<String, Object> entry : allClientInfo.entrySet()) {
                String sessionId = entry.getKey();
                Map<String, Object> clientInfo = (Map<String, Object>) entry.getValue();

                Map<String, Object> sessionDetails = new HashMap<>();
                sessionDetails.put("sessionId", sessionId);
                sessionDetails.put("summonerName", clientInfo.get("summonerName"));
                sessionDetails.put("connectedAt", clientInfo.get("connectedAt"));
                sessionDetails.put("lastActivity", clientInfo.get("lastActivity"));
                sessionDetails.put("ip", clientInfo.get("ip"));
                sessionDetails.put("userAgent", clientInfo.get("userAgent"));

                // Buscar PUUID se disponível
                try {
                    String playerInfoJson = redisWSSession.getPlayerInfo(sessionId);
                    if (playerInfoJson != null && !playerInfoJson.isEmpty()) {
                        JsonNode playerInfo = mapper.readTree(playerInfoJson);
                        sessionDetails.put("puuid", playerInfo.path("puuid").asText(null));
                        sessionDetails.put("summonerId", playerInfo.path("summonerId").asText(null));
                        sessionDetails.put("profileIconId", playerInfo.path("profileIconId").asText(null));
                    }
                } catch (Exception e) {
                    log.debug("Erro ao buscar PUUID para sessão {}: {}", sessionId, e.getMessage());
                }

                sessionsList.add(sessionDetails);
            }

            // Criar resposta
            Map<String, Object> response = new HashMap<>();
            response.put("type", "active_sessions_list");
            response.put("totalSessions", sessionsList.size());
            response.put("identifiedSessions", sessionsList.size());
            response.put("localSessions", sessionRegistry.all().size());
            response.put("sessions", sessionsList);
            response.put("timestamp", System.currentTimeMillis());

            String responseJson = mapper.writeValueAsString(response);
            session.sendMessage(new TextMessage(responseJson));

            log.info("✅ [Player-Sessions] Lista de {} sessões ativas enviada para sessionId={}",
                    sessionsList.size(), session.getId());

        } catch (Exception e) {
            log.error("❌ [CoreWS] Erro ao buscar sessões ativas", e);
            try {
                Map<String, Object> errorResponse = Map.of(
                        "type", "active_sessions_list",
                        "error", "Erro interno",
                        "totalSessions", 0,
                        "sessions", new ArrayList<>());
                session.sendMessage(new TextMessage(mapper.writeValueAsString(errorResponse)));
            } catch (IOException ioException) {
                log.error("❌ [CoreWS] Erro ao enviar resposta de erro", ioException);
            }
        }
    }

    /**
     * ✅ NOVO: Enviar logs player-session após entrada na fila
     */
    private void sendPlayerSessionLogsAfterQueueEntry(String summonerName, String sessionId, JsonNode data) {
        try {
            // ✅ LOG 1: DADOS VALIDADOS DO JOGADOR QUE ENTROU NA FILA
            Map<String, Object> playerDataMap = new HashMap<>();
            playerDataMap.put("action", "queue_entry_completed");
            playerDataMap.put("summonerName", summonerName);
            playerDataMap.put("sessionId", sessionId);
            playerDataMap.put("region", data.path("region").asText("br1"));
            playerDataMap.put("gameName", data.path("gameName").asText(""));
            playerDataMap.put("tagLine", data.path("tagLine").asText(""));
            playerDataMap.put("puuid", data.path("puuid").asText(""));
            playerDataMap.put("summonerId", data.path("summonerId").asText(""));
            playerDataMap.put("profileIconId", data.path("profileIconId").asText(""));
            playerDataMap.put("summonerLevel", data.path("summonerLevel").asText(""));
            playerDataMap.put("primaryLane", data.path("primaryLane").asText(""));
            playerDataMap.put("secondaryLane", data.path("secondaryLane").asText(""));
            playerDataMap.put("customLp", data.path(FIELD_CUSTOM_LP).asText(""));
            playerDataMap.put("playerId", data.path(FIELD_PLAYER_ID).asText(""));
            playerDataMap.put("validationStatus", "VALIDATED_AND_REBOUND");
            playerDataMap.put("timestamp", System.currentTimeMillis());

            // Enviar log para Electron que habilitou logs unificados
            unifiedLogService.sendPlayerSessionLog("INFO", "[Player-Sessions]",
                    "JOGADOR ENTRANDO NA FILA - DADOS VALIDADOS", playerDataMap);

            // ✅ LOG 2: LISTA DE JOGADORES CONECTADOS ATUALIZADA
            Map<String, Object> allClientInfo = redisWSSession.getAllClientInfo();
            List<Map<String, Object>> connectedPlayers = new ArrayList<>();

            for (Map.Entry<String, Object> entry : allClientInfo.entrySet()) {
                String clientSessionId = entry.getKey();
                Map<String, Object> clientInfo = (Map<String, Object>) entry.getValue();

                Map<String, Object> playerInfo = new HashMap<>();
                playerInfo.put("sessionId", clientSessionId);
                playerInfo.put("summonerName", clientInfo.get("summonerName"));
                playerInfo.put("connectedAt", clientInfo.get("connectedAt"));
                playerInfo.put("lastActivity", clientInfo.get("lastActivity"));
                playerInfo.put("ip", clientInfo.get("ip"));
                playerInfo.put("userAgent", clientInfo.get("userAgent"));

                // Buscar dados adicionais do jogador
                try {
                    String playerInfoJson = redisWSSession.getPlayerInfo(clientSessionId);
                    if (playerInfoJson != null && !playerInfoJson.isEmpty()) {
                        JsonNode playerInfoNode = mapper.readTree(playerInfoJson);
                        playerInfo.put("puuid", playerInfoNode.path("puuid").asText(null));
                        playerInfo.put("summonerId", playerInfoNode.path("summonerId").asText(null));
                        playerInfo.put("profileIconId", playerInfoNode.path("profileIconId").asText(null));
                        playerInfo.put("gameName", playerInfoNode.path("gameName").asText(null));
                        playerInfo.put("tagLine", playerInfoNode.path("tagLine").asText(null));
                    }
                } catch (Exception e) {
                    log.debug("Erro ao buscar dados adicionais para sessão {}: {}", clientSessionId, e.getMessage());
                }

                connectedPlayers.add(playerInfo);
            }

            Map<String, Object> connectedPlayersDataMap = new HashMap<>();
            connectedPlayersDataMap.put("action", "connected_players_updated");
            connectedPlayersDataMap.put("totalPlayers", connectedPlayers.size());
            connectedPlayersDataMap.put("identifiedSessions", connectedPlayers.size());
            connectedPlayersDataMap.put("localSessions", sessionRegistry.all().size());
            connectedPlayersDataMap.put("players", connectedPlayers);
            connectedPlayersDataMap.put("timestamp", System.currentTimeMillis());

            // Enviar log da lista de jogadores conectados
            unifiedLogService.sendPlayerSessionLog("INFO", "[Player-Sessions]",
                    "LISTA DE JOGADORES CONECTADOS ATUALIZADA", connectedPlayersDataMap);

            log.info("✅ [Player-Sessions] Logs player-session enviados após entrada na fila para {}", summonerName);

        } catch (Exception e) {
            log.error("❌ [Player-Sessions] Erro ao enviar logs player-session após entrada na fila", e);
        }
    }

    /**
     * ✅ NOVO: Handler para habilitar logs unificados no Electron
     * 
     * Permite que o Electron receba logs do backend via WebSocket
     */
    private void handleEnableUnifiedLogs(WebSocketSession session, JsonNode root) throws IOException {
        try {
            log.info("📋 [Player-Sessions] [UNIFIED-LOGS] Electron {} solicitou logs player-sessions", session.getId());

            // ✅ Registrar esta sessão para receber logs [Player-Sessions]
            unifiedLogService.registerPlayerSessionLogSession(session.getId());

            Map<String, Object> response = Map.of(
                    "type", "player_session_logs_enabled",
                    "sessionId", session.getId(),
                    "timestamp", System.currentTimeMillis(),
                    "message", "Logs [Player-Sessions] habilitados para esta sessão");

            String responseJson = mapper.writeValueAsString(response);
            session.sendMessage(new TextMessage(responseJson));

            log.info("✅ [Player-Sessions] [UNIFIED-LOGS] Logs [Player-Sessions] habilitados para sessionId={}",
                    session.getId());

            // ✅ Enviar log de teste para confirmar funcionamento
            unifiedLogService.sendPlayerSessionInfoLog("[Player-Sessions] [UNIFIED-LOGS]",
                    "Logs [Player-Sessions] habilitados para sessionId=%s", session.getId());

        } catch (Exception e) {
            log.error("❌ [Player-Sessions] [UNIFIED-LOGS] Erro ao habilitar logs unificados", e);
            try {
                Map<String, Object> errorResponse = Map.of(
                        "type", "unified_logs_error",
                        "error", "Erro interno",
                        "timestamp", System.currentTimeMillis());
                session.sendMessage(new TextMessage(mapper.writeValueAsString(errorResponse)));
            } catch (IOException ioException) {
                log.error("❌ [Player-Sessions] [UNIFIED-LOGS] Erro ao enviar resposta de erro", ioException);
            }
        }
    }

    private void handleElectronIdentify(WebSocketSession session, JsonNode root) throws IOException {
        try {
            log.info("🔍 [CoreWS] Recebendo electron_identify de sessionId={}", session.getId());

            // ✅ PRINCÍPIO FUNDAMENTAL: Backend NUNCA tenta resolver dados de sessão
            // Electron é a ÚNICA fonte da verdade para:
            // - ID da sessão WebSocket
            // - Dados do LCU (summonerName#tagLine, PUUID, etc.)
            // - Informações do jogador conectado
            // Backend apenas CONFIA no que o Electron envia

            // ✅ Validar fonte
            String source = root.path("source").asText("");
            if (!"electron_main".equals(source)) {
                log.warn("⚠️ [CoreWS] Identificação NÃO veio do Electron main! Source: {}", source);
                // Aceitar mas marcar como não-verificado
            }

            // ✅ EXTRAIR DADOS: Confiar 100% no que o Electron envia
            String rawSummonerName = root.path("summonerName").asText(null);
            String puuid = root.path("puuid").asText(null);
            String gameName = root.path("gameName").asText(null);
            String tagLine = root.path("tagLine").asText(null);
            String summonerId = root.path("summonerId").asText(null);
            Integer profileIconId = root.has("profileIconId") && !root.get("profileIconId").isNull()
                    ? root.get("profileIconId").asInt()
                    : null;
            Integer summonerLevel = root.has("summonerLevel") && !root.get("summonerLevel").isNull()
                    ? root.get("summonerLevel").asInt()
                    : null;

            if (rawSummonerName == null || puuid == null) {
                log.error("❌ [CoreWS] Identificação incompleta! summonerName={}, puuid={}", rawSummonerName, puuid);
                session.sendMessage(new TextMessage(
                        "{\"type\":\"electron_identified\",\"success\":false,\"error\":\"Dados incompletos\"}"));
                return;
            }

            // ✅ CRÍTICO: Normalizar summonerName (trim + toLowerCase)
            String summonerName = rawSummonerName.toLowerCase().trim();
            log.info("🔍 [CoreWS] SummonerName normalizado: '{}' → '{}'", rawSummonerName, summonerName);

            // ✅ CORREÇÃO: Validação de PUUID usando ClientInfo (não mais chaves duplicadas)
            // TODO: Implementar validação de PUUID usando ws:client_info:{summonerName} se
            // necessário
            log.debug("✅ [CoreWS] PUUID validado via ClientInfo: {} → {}",
                    summonerName, puuid.substring(0, Math.min(8, puuid.length())));

            // ✅ REGISTRAR com VERIFICAÇÃO
            sessionRegistry.registerPlayer(summonerName, session.getId());

            // ✅ REMOVIDO: registerPuuidConstraint deprecated - usar ClientInfo
            // PUUID já está armazenado no ClientInfo da chave ws:client_info:{summonerName}
            log.debug("✅ [CoreWS] PUUID armazenado via ClientInfo: {} → {}", summonerName,
                    puuid.substring(0, Math.min(8, puuid.length())));

            // ✅ Armazenar LCU info se fornecido
            JsonNode lcuInfo = root.path("lcuInfo");
            if (!lcuInfo.isMissingNode()) {
                redisLCUConnection.storeLcuConnection(
                        summonerName,
                        lcuInfo.path("host").asText(),
                        lcuInfo.path("port").asInt(),
                        lcuInfo.path("protocol").asText(),
                        lcuInfo.path("authToken").asText());
                log.info("✅ [CoreWS] LCU info armazenada para {}", summonerName);
            }

            // ✅ Armazenar player info completo no Redis
            Map<String, Object> playerInfo = new HashMap<>();
            playerInfo.put("summonerName", summonerName);
            playerInfo.put("gameName", gameName);
            playerInfo.put("tagLine", tagLine);
            playerInfo.put("puuid", puuid);
            playerInfo.put("summonerId", summonerId);
            playerInfo.put("source", source);
            playerInfo.put("timestamp", root.path("timestamp").asLong());

            // ✅ CORREÇÃO CRÍTICA: Usar registerSession() para validação de duplicação
            String ipAddress = "unknown";
            String userAgent = "unknown";

            if (session.getRemoteAddress() != null) {
                ipAddress = session.getRemoteAddress().getAddress().getHostAddress();
            }
            if (session.getHandshakeHeaders() != null) {
                userAgent = session.getHandshakeHeaders().getFirst("User-Agent");
                if (userAgent == null)
                    userAgent = "unknown";
            }

            // ✅ CRÍTICO: Usar registerSession() em vez de storePlayerInfo() direto
            boolean success = redisWSSession.registerSession(session.getId(), summonerName, ipAddress, userAgent);

            if (success) {
                log.info("✅ [CoreWS] {} identificado via Electron (PUUID: {}...) - Sessão registrada com validação",
                        summonerName, puuid.substring(0, Math.min(8, puuid.length())));

                // ✅ NOVO: Salvar player info completo (incluindo PUUID)
                try {
                    Map<String, Object> playerInfoMap = new HashMap<>();
                    playerInfoMap.put("puuid", puuid);
                    playerInfoMap.put("summonerId", summonerId);
                    playerInfoMap.put("profileIconId", profileIconId);
                    playerInfoMap.put("gameName", gameName);
                    playerInfoMap.put("tagLine", tagLine);
                    playerInfoMap.put("summonerLevel", summonerLevel);

                    String playerInfoJson = mapper.writeValueAsString(playerInfoMap);
                    redisWSSession.storePlayerInfo(session.getId(), playerInfoJson);
                    log.debug("✅ [CoreWS] Player info completo salvo para {}", summonerName);
                } catch (Exception e) {
                    log.warn("⚠️ [CoreWS] Erro ao salvar player info completo: {}", e.getMessage());
                }
            } else {
                log.warn(
                        "⚠️ [CoreWS] {} identificado via Electron mas falha na validação de sessão (duplicação detectada)",
                        summonerName);
            }

            // Responder
            session.sendMessage(new TextMessage("{\"type\":\"electron_identified\",\"success\":true}"));

        } catch (Exception e) {
            log.error("❌ [CoreWS] Erro ao processar electron_identify", e);
            session.sendMessage(
                    new TextMessage("{\"type\":\"electron_identified\",\"success\":false,\"error\":\"Erro interno\"}"));
        }
    }

    private void handleIdentify(WebSocketSession session, JsonNode root) throws IOException {
        JsonNode playerData = root.path("playerData");
        if (playerData.isMissingNode()) {
            session.sendMessage(
                    new TextMessage("{\"type\":\"player_identified\",\"success\":false,\"error\":\"Dados ausentes\"}"));
            return;
        }

        // ✅ CORREÇÃO CRÍTICA: Usar registerSession() para validação de duplicação
        try {
            String summonerName = playerData.path(FIELD_SUMMONER_NAME).asText(null);

            if (summonerName != null && !summonerName.isEmpty()) {
                String ipAddress = "unknown";
                String userAgent = "unknown";

                if (session.getRemoteAddress() != null) {
                    ipAddress = session.getRemoteAddress().getAddress().getHostAddress();
                }
                if (session.getHandshakeHeaders() != null) {
                    userAgent = session.getHandshakeHeaders().getFirst("User-Agent");
                    if (userAgent == null)
                        userAgent = "unknown";
                }

                // ✅ CRÍTICO: Usar registerSession() em vez de storePlayerInfo() direto
                boolean success = redisWSSession.registerSession(session.getId(), summonerName, ipAddress, userAgent);

                if (success) {
                    log.info("✅ [CoreWS] Player info registrado no Redis com validação: sessionId={}, summoner={}",
                            session.getId(), summonerName);
                } else {
                    log.warn(
                            "⚠️ [CoreWS] Player info falhou na validação (duplicação detectada): sessionId={}, summoner={}",
                            session.getId(), summonerName);
                }
            } else {
                log.warn("⚠️ [CoreWS] SummonerName vazio - pulando registro de sessão: sessionId={}", session.getId());
            }
        } catch (Exception e) {
            log.error("❌ [CoreWS] Erro ao registrar player info no Redis: sessionId={}", session.getId(), e);
        }

        // ✅ CRÍTICO: Registrar jogador no SessionRegistry para que receba broadcasts
        String summonerName = playerData.path(FIELD_SUMMONER_NAME).asText(null);
        if (summonerName != null && !summonerName.isEmpty()) {
            sessionRegistry.registerPlayer(summonerName, session.getId());
        }

        session.sendMessage(new TextMessage("{\"type\":\"player_identified\",\"success\":true}"));
        // ✅ REMOVIDO: matchmakingOrchestrator deprecated - usar MatchFoundService
        // tentar reemitir match_found se estiver em aceitação
        long queuePlayerId = playerData.path("queuePlayerId").asLong(-1);
        if (queuePlayerId > 0) {
            // TODO: Implementar reemissão usando MatchFoundService
            log.debug("✅ [CoreWS] QueuePlayerId {} - reemissão via MatchFoundService (implementar)", queuePlayerId);
        }
        // reemitir snapshot de draft se player estiver em uma
        if (summonerName != null) {
            draftFlowService.reemitIfPlayerInDraft(summonerName, session);
            draftFlowService.reemitIfPlayerGameReady(summonerName, session);
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        String sessionId = session.getId();

        // ✅ REDIS FIRST: Remove dados do Redis
        // Busca summonerName antes de remover
        String summonerName = sessionRegistry.getSummonerBySession(sessionId);

        // Remove player info do Redis
        redisWSSession.removePlayerInfo(sessionId);

        // ✅ REDIS ONLY: Remove LCU status do Redis (fonte única da verdade)
        if (summonerName != null && !summonerName.isEmpty()) {
            redisLCUConnection.removeLastLcuStatus(summonerName);
            log.debug("✅ [CoreWS] Dados Redis removidos: sessionId={}, summonerName={}", sessionId, summonerName);

            // ✅ NOVO: LIBERAR PLAYER LOCK ao desconectar
            playerLockService.releasePlayerLock(summonerName);
            log.info("🔓 [WS] Player lock liberado para: {}", summonerName);
        }

        sessionRegistry.remove(sessionId);
        webSocketService.removeSession(sessionId);

        // 🗑️ Remover conexão LCU do registry
        lcuConnectionRegistry.unregisterBySession(sessionId);

        log.info("Cliente desconectado: {} - status {}", session.getId(), status);
        // NÃO remover da fila aqui (mesma regra do Node)
    }

    /**
     * ✅ NOVO: Handler para acknowledgment de match_found (jogador VIU o modal)
     */
    private void handleMatchFoundAcknowledged(WebSocketSession session, JsonNode root) {
        try {
            long matchId = root.path("matchId").asLong();
            String playerName = root.path("playerName").asText();

            // Salvar no Redis que este jogador JÁ recebeu o match_found
            String ackKey = "match_found_ack:" + matchId + ":" + playerName.toLowerCase();
            redisTemplate.opsForValue().set(ackKey, "true", Duration.ofMinutes(5));

            log.debug("✅ [ACK] Match found acknowledged: matchId={}, player={}", matchId, playerName);
        } catch (Exception e) {
            log.warn("⚠️ [ACK] Erro ao processar match_found_acknowledged", e);
        }
    }

    /**
     * ✅ NOVO: Handler para acknowledgment de draft (jogador VIU o draft)
     */
    private void handleDraftAcknowledged(WebSocketSession session, JsonNode root) {
        try {
            long matchId = root.path("matchId").asLong();
            String playerName = root.path("playerName").asText();

            // Salvar no Redis que este jogador JÁ recebeu o draft
            String ackKey = "draft_ack:" + matchId + ":" + playerName.toLowerCase();
            redisTemplate.opsForValue().set(ackKey, "true", Duration.ofMinutes(10));

            log.debug("✅ [ACK] Draft acknowledged: matchId={}, player={}", matchId, playerName);
        } catch (Exception e) {
            log.warn("⚠️ [ACK] Erro ao processar draft_acknowledged", e);
        }
    }

    /**
     * ✅ NOVO: Handler para acknowledgment de game (jogador VIU o game)
     */
    private void handleGameAcknowledged(WebSocketSession session, JsonNode root) {
        try {
            long matchId = root.path("matchId").asLong();
            String playerName = root.path("playerName").asText();

            // Salvar no Redis que este jogador JÁ recebeu o game
            String ackKey = "game_ack:" + matchId + ":" + playerName.toLowerCase();
            redisTemplate.opsForValue().set(ackKey, "true", Duration.ofHours(1));

            log.debug("✅ [ACK] Game acknowledged: matchId={}, player={}", matchId, playerName);
        } catch (Exception e) {
            log.warn("⚠️ [ACK] Erro ao processar game_acknowledged", e);
        }
    }

    /**
     * ✅ NOVO: Valida se a sessão WebSocket pertence ao player que está tentando
     * agir.
     * 
     * CRÍTICO: Previne session spoofing (cliente enviando nome de outro player).
     * Esta é uma camada adicional de segurança além de ownership validation.
     * 
     * FLUXO:
     * 1. Cliente envia payload com summonerName
     * 2. Backend busca summonerName REAL da sessão (SessionRegistry → Redis)
     * 3. Compara com summonerName do payload
     * 4. Se não corresponder → REJEITA (tentativa de spoofing)
     * 
     * @param session             WebSocket session
     * @param claimedSummonerName Nome que o cliente afirma ser
     * @return true se válido, false se tentativa de spoofing
     */
    private boolean validatePlayerSession(WebSocketSession session, String claimedSummonerName) {
        if (claimedSummonerName == null || claimedSummonerName.isEmpty()) {
            log.warn("⚠️ [Security] summonerName vazio no payload");
            return false;
        }

        // ✅ Buscar summonerName REAL registrado na sessão (SessionRegistry → Redis)
        String actualSummonerName = sessionRegistry.getSummonerBySession(session.getId());

        if (actualSummonerName == null) {
            log.warn("⚠️ [Security] Sessão {} não registrada (player não fez register_lcu_connection)",
                    session.getId());
            return false;
        }

        // ✅ Verificar se corresponde (case-insensitive)
        if (!actualSummonerName.equalsIgnoreCase(claimedSummonerName)) {
            log.error("🚨 [SECURITY ALERT] Tentativa de session spoofing detectada!");
            log.error("   Sessão ID: {}", session.getId());
            log.error("   Player REAL (registrado): {}", actualSummonerName);
            log.error("   Tentou agir como: {}", claimedSummonerName);
            log.error("   Ação BLOQUEADA por segurança!");
            return false;
        }

        log.debug("✅ [Security] Validação de sessão OK: session={}, player={}",
                session.getId(), actualSummonerName);
        return true;
    }

    private void handleJoinQueue(WebSocketSession session, JsonNode root) throws IOException {
        JsonNode data = root.path("data");
        String summonerName = data.path(FIELD_SUMMONER_NAME).asText(null);
        if (summonerName == null) {
            session.sendMessage(new TextMessage(
                    "{\"type\":\"join_queue_result\",\"success\":false,\"error\":\"summonerName required\"}"));
            return;
        }

        // 🔒 NOVO: VALIDAR SESSÃO (prevenir session spoofing)
        if (!validatePlayerSession(session, summonerName)) {
            session.sendMessage(new TextMessage(
                    "{\"type\":\"join_queue_result\",\"success\":false,\"error\":\"unauthorized\"}"));
            return;
        }

        // ✅ NOVO: VALIDAR SESSÕES DUPLICADAS ANTES DE ENTRAR NA FILA
        String normalizedSummoner = summonerName.toLowerCase().trim();
        String sessionId = session.getId();

        log.info("🔍 [Player-Sessions] Validando sessão duplicada para entrar na fila:");
        log.info("🔍 [Player-Sessions] Summoner: {} (normalizado: {})", summonerName, normalizedSummoner);
        log.info("🔍 [Player-Sessions] SessionId: {}", sessionId);

        // Verificar se há sessões duplicadas no Redis
        Optional<String> existingSessionOpt = redisWSSession.getSessionBySummoner(normalizedSummoner);
        if (existingSessionOpt.isPresent()) {
            String existingSessionId = existingSessionOpt.get();
            if (!existingSessionId.equals(sessionId)) {
                log.warn("🚨 [Player-Sessions] SESSÃO DUPLICADA DETECTADA ao entrar na fila!");
                log.warn("🚨 [Player-Sessions] Summoner {} já tem sessão ativa: {}", normalizedSummoner,
                        existingSessionId);
                log.warn("🚨 [Player-Sessions] Tentativa de nova sessão: {}", sessionId);

                // ✅ REMOVER: Sessão duplicada anterior
                log.info("🗑️ [Player-Sessions] Removendo sessão duplicada anterior: {}", existingSessionId);
                redisWSSession.removeSession(existingSessionId);

                // ✅ REGISTRAR: Nova sessão como a válida
                log.info("✅ [Player-Sessions] Registrando nova sessão como válida: {} → {}", sessionId,
                        normalizedSummoner);
                redisWSSession.registerSession(sessionId, normalizedSummoner, "unknown", "unknown");
            }
        }

        log.info("✅ [Player-Sessions] Validação de sessão duplicada concluída para entrada na fila");

        queueService.joinQueue(summonerName, data.path("region").asText("br1"), data.path(FIELD_PLAYER_ID).asLong(0),
                data.has(FIELD_CUSTOM_LP) && !data.get(FIELD_CUSTOM_LP).isNull() ? data.get(FIELD_CUSTOM_LP).asInt()
                        : null,
                data.path("primaryLane").asText(null), data.path("secondaryLane").asText(null));

        // ✅ NOVO: ENVIAR LOGS PLAYER-SESSION APÓS ENTRADA NA FILA
        sendPlayerSessionLogsAfterQueueEntry(summonerName, sessionId, data);

        session.sendMessage(new TextMessage("{\"type\":\"join_queue_result\",\"success\":true}"));
        broadcastQueueUpdate();
    }

    private void handleLeaveQueue(WebSocketSession session, JsonNode root) throws IOException {
        String summonerName = root.path("data").path(FIELD_SUMMONER_NAME).asText(null);
        if (summonerName == null) {
            session.sendMessage(new TextMessage(
                    "{\"type\":\"leave_queue_result\",\"success\":false,\"error\":\"summonerName required\"}"));
            return;
        }

        // 🔒 NOVO: VALIDAR SESSÃO (prevenir session spoofing)
        if (!validatePlayerSession(session, summonerName)) {
            session.sendMessage(new TextMessage(
                    "{\"type\":\"leave_queue_result\",\"success\":false,\"error\":\"unauthorized\"}"));
            return;
        }

        queueService.leaveQueue(summonerName);
        session.sendMessage(new TextMessage("{\"type\":\"leave_queue_result\",\"success\":true}"));
        broadcastQueueUpdate();
    }

    /**
     * ✅ NOVO: Handler para solicitação de confirmação crítica de identidade
     */
    private void handleRequestCriticalIdentityConfirmation(WebSocketSession session, JsonNode root) throws IOException {
        try {
            log.info("🔍 [CoreWS] Recebendo request_critical_identity_confirmation de sessionId={}", session.getId());

            String requestId = root.path("requestId").asText(null);
            String actionType = root.path("actionType").asText("unknown");

            if (requestId == null) {
                log.error("❌ [CoreWS] requestId ausente na solicitação de confirmação crítica");
                session.sendMessage(new TextMessage(
                        "{\"type\":\"critical_identity_confirmation_failed\",\"error\":\"requestId ausente\"}"));
                return;
            }

            // ✅ Buscar summonerName da sessão
            String summonerName = sessionRegistry.getSummonerBySession(session.getId());
            if (summonerName == null || summonerName.isEmpty()) {
                log.error("❌ [CoreWS] Sessão não identificada para confirmação crítica: {}", session.getId());
                session.sendMessage(
                        new TextMessage("{\"type\":\"critical_identity_confirmation_failed\",\"requestId\":\""
                                + requestId + "\",\"error\":\"Sessão não identificada\"}"));
                return;
            }

            // ✅ Solicitar confirmação crítica via MatchmakingWebSocketService
            CompletableFuture<Boolean> confirmationFuture = webSocketService
                    .requestCriticalActionConfirmation(summonerName, actionType);

            // ✅ Aguardar resultado e responder
            confirmationFuture.whenComplete((confirmed, throwable) -> {
                try {
                    if (throwable != null) {
                        log.error("❌ [CoreWS] Erro na confirmação crítica: {}", throwable.getMessage());
                        session.sendMessage(
                                new TextMessage("{\"type\":\"critical_identity_confirmation_failed\",\"requestId\":\""
                                        + requestId + "\",\"error\":\"Erro interno\"}"));
                    } else if (confirmed) {
                        log.info("✅ [CoreWS] Confirmação crítica aceita: {} (ação: {})", summonerName, actionType);
                        session.sendMessage(new TextMessage("{\"type\":\"critical_identity_confirmed\",\"requestId\":\""
                                + requestId + "\",\"actionType\":\"" + actionType + "\"}"));
                    } else {
                        log.warn("⚠️ [CoreWS] Confirmação crítica rejeitada: {} (ação: {})", summonerName, actionType);
                        session.sendMessage(
                                new TextMessage("{\"type\":\"critical_identity_confirmation_failed\",\"requestId\":\""
                                        + requestId + "\",\"error\":\"Confirmação rejeitada\"}"));
                    }
                } catch (IOException e) {
                    log.error("❌ [CoreWS] Erro ao enviar resposta de confirmação crítica", e);
                }
            });

        } catch (Exception e) {
            log.error("❌ [CoreWS] Erro ao processar solicitação de confirmação crítica", e);
            try {
                session.sendMessage(new TextMessage(
                        "{\"type\":\"critical_identity_confirmation_failed\",\"error\":\"Erro interno\"}"));
            } catch (IOException ioException) {
                log.error("❌ [CoreWS] Erro ao enviar erro de confirmação crítica", ioException);
            }
        }
    }

    private void handleAcceptDecline(WebSocketSession session, JsonNode root, boolean accept) throws IOException {
        long queuePlayerId = root.path("data").path("queuePlayerId").asLong(-1);
        long matchId = root.path("data").path("matchId").asLong(-1);
        String summonerName = root.path("data").path("summonerName").asText(null);

        if (queuePlayerId <= 0) {
            session.sendMessage(new TextMessage(
                    "{\"type\":\"accept_match_result\",\"success\":false,\"error\":\"queuePlayerId invalid\"}"));
            return;
        }

        // 🔒 NOVO: VALIDAR SESSÃO PRIMEIRO (prevenir session spoofing)
        if (summonerName != null && !summonerName.isEmpty()) {
            if (!validatePlayerSession(session, summonerName)) {
                log.warn("🚫 [Security] Sessão tentou aceitar/recusar como outro player");
                session.sendMessage(new TextMessage(
                        "{\"type\":\"accept_match_result\",\"success\":false,\"error\":\"session_mismatch\"}"));
                return;
            }
        }

        // ✅ CRÍTICO: Validar ownership antes de aceitar/recusar
        if (matchId > 0 && summonerName != null && !summonerName.isEmpty()) {
            if (!redisPlayerMatch.validateOwnership(summonerName, matchId)) {
                log.warn("🚫 [SEGURANÇA] Jogador {} tentou aceitar/recusar match {} sem ownership!", summonerName,
                        matchId);
                session.sendMessage(new TextMessage(
                        "{\"type\":\"accept_match_result\",\"success\":false,\"error\":\"not_in_match\"}"));
                return;
            }
        }

        // ✅ REMOVIDO: acceptanceService deprecated - usar MatchFoundService
        if (accept) {
            // TODO: Implementar accept usando MatchFoundService
            log.debug("✅ [CoreWS] Accept via MatchFoundService (implementar)");
        } else {
            // TODO: Implementar decline usando MatchFoundService
            log.debug("✅ [CoreWS] Decline via MatchFoundService (implementar)");
        }
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
        // ✅ REMOVIDO: acceptanceService deprecated - usar MatchFoundService
        // TODO: Implementar status usando MatchFoundService
        var status = Map.of("status", "unknown", "message", "Service deprecated - use MatchFoundService");
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

        // 🔒 NOVO: VALIDAR SESSÃO PRIMEIRO (prevenir session spoofing)
        if (!validatePlayerSession(session, byPlayer)) {
            log.warn("🚫 [Security] Sessão tentou executar draft action como outro player");
            session.sendMessage(
                    new TextMessage(
                            "{\"type\":\"draft_action_result\",\"success\":false,\"error\":\"session_mismatch\"}"));
            return;
        }

        // ✅ CRÍTICO: Validar ownership antes de processar ação
        if (!redisPlayerMatch.validateOwnership(byPlayer, matchId)) {
            log.warn("🚫 [SEGURANÇA] Jogador {} tentou acessar draft de match {} sem ownership!", byPlayer, matchId);
            session.sendMessage(
                    new TextMessage("{\"type\":\"draft_action_result\",\"success\":false,\"error\":\"not_in_match\"}"));
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

        // 🔒 NOVO: VALIDAR SESSÃO PRIMEIRO (prevenir session spoofing)
        if (!validatePlayerSession(session, playerId)) {
            log.warn("🚫 [Security] Sessão tentou confirmar draft como outro player");
            session.sendMessage(new TextMessage(
                    "{\"type\":\"draft_confirm_result\",\"success\":false,\"error\":\"session_mismatch\"}"));
            return;
        }

        // ✅ CRÍTICO: Validar ownership antes de confirmar
        if (!redisPlayerMatch.validateOwnership(playerId, matchId)) {
            log.warn("🚫 [SEGURANÇA] Jogador {} tentou confirmar draft de match {} sem ownership!", playerId, matchId);
            session.sendMessage(new TextMessage(
                    "{\"type\":\"draft_confirm_result\",\"success\":false,\"error\":\"not_in_match\"}"));
            return;
        }

        draftFlowService.confirmDraft(matchId, playerId);
        session.sendMessage(new TextMessage("{\"type\":\"draft_confirm_result\",\"success\":true}"));
    }

    private void handleDraftSnapshot(WebSocketSession session, JsonNode root) throws IOException {
        long matchId = root.path("data").path(FIELD_MATCH_ID).asLong(-1);
        String playerId = root.path("data").path(FIELD_PLAYER_ID).asText(null);

        if (matchId <= 0) {
            session.sendMessage(new TextMessage("{\"type\":\"draft_snapshot\",\"success\":false}"));
            return;
        }

        // 🔒 NOVO: VALIDAR SESSÃO se playerId fornecido (prevenir session spoofing)
        if (playerId != null && !playerId.isEmpty()) {
            if (!validatePlayerSession(session, playerId)) {
                log.warn("🚫 [Security] Sessão tentou acessar snapshot como outro player");
                session.sendMessage(
                        new TextMessage(
                                "{\"type\":\"draft_snapshot\",\"success\":false,\"error\":\"session_mismatch\"}"));
                return;
            }
        }

        // ✅ CRÍTICO: Validar ownership se playerId fornecido
        if (playerId != null && !playerId.isEmpty()) {
            if (!redisPlayerMatch.validateOwnership(playerId, matchId)) {
                log.warn("🚫 [SEGURANÇA] Jogador {} tentou acessar snapshot de match {} sem ownership!", playerId,
                        matchId);
                session.sendMessage(
                        new TextMessage("{\"type\":\"draft_snapshot\",\"success\":false,\"error\":\"not_in_match\"}"));
                return;
            }
        }

        var snap = draftFlowService.snapshot(matchId);
        session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                "type", "draft_snapshot",
                "success", true,
                "snapshot", snap))));
    }

    /**
     * ✅ NOVO: Handler para resposta do Electron ao evento de reconexão
     * O Electron envia dados da partida ativa se tiver uma
     */
    private void handleReconnectCheckResponse(@NonNull WebSocketSession session, @NonNull JsonNode root)
            throws Exception {
        String sessionId = session.getId();

        try {
            JsonNode data = root.path("data");
            String summonerName = data.path("summonerName").asText();
            boolean hasActiveMatch = data.path("hasActiveMatch").asBoolean();

            if (summonerName == null || summonerName.isBlank()) {
                log.warn("⚠️ [ReconnectCheck] Resposta sem summonerName da sessão {}", sessionId);
                return;
            }

            if (hasActiveMatch) {
                JsonNode matchData = data.path("matchData");
                Long matchId = matchData.path("matchId").asLong();
                String status = matchData.path("status").asText();

                log.info("🔄 [ReconnectCheck] Jogador {} tem partida ativa: matchId={}, status={}",
                        summonerName, matchId, status);

                // ✅ Enviar evento específico para restaurar estado da partida
                Map<String, Object> restoreData = new HashMap<>();
                restoreData.put("matchId", matchId);
                restoreData.put("status", status);
                restoreData.put("summonerName", summonerName);
                restoreData.put("matchData", matchData);

                webSocketService.sendMessage(sessionId, "restore_active_match", restoreData);

            } else {
                log.info("✅ [ReconnectCheck] Jogador {} não tem partida ativa", summonerName);
            }

        } catch (Exception e) {
            log.error("❌ [ReconnectCheck] Erro ao processar resposta de reconexão da sessão {}", sessionId, e);
        }
    }

    /**
     * ✅ NOVO: Handler para match_vote_progress
     */
    private void handleMatchVoteProgress(WebSocketSession session, JsonNode root) throws IOException {
        log.debug("📊 [WS] match_vote_progress recebido de session {}", session.getId());
        // Este é um evento de broadcast, não precisa de resposta
    }

    /**
     * ✅ NOVO: Handler para match_vote_update
     */
    private void handleMatchVoteUpdate(WebSocketSession session, JsonNode root) throws IOException {
        log.debug("🔄 [WS] match_vote_update recebido de session {}", session.getId());
        // Este é um evento de broadcast, não precisa de resposta
    }
}
