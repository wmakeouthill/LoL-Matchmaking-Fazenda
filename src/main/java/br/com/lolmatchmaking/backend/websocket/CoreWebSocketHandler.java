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

    // ✅ NOVO: Redis services
    private final RedisWebSocketSessionService redisWSSession;
    private final RedisLCUConnectionService redisLCUConnection;
    private final br.com.lolmatchmaking.backend.service.redis.RedisPlayerMatchService redisPlayerMatch;

    // ✅ NOVO: Lock services
    private final br.com.lolmatchmaking.backend.service.lock.PlayerLockService playerLockService;

    // ✅ NOVO: RedisTemplate para acknowledgments
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    // ✅ DEPRECIADO: Migrado para Redis (backward compatibility)
    // ✅ REMOVIDO: identifiedPlayers e lastLcuStatus - Redis é fonte única da
    // verdade
    // Use redisWSSession.storePlayerInfo() e redisLCUConnection.storeLcuStatus()

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
            case "electron_identify" -> handleElectronIdentify(session, root);
            case "identity_confirmed" -> webSocketService.handleIdentityConfirmed(session.getId(), root);
            case "identity_confirmation_failed" ->
                webSocketService.handleIdentityConfirmationFailed(session.getId(), root);
            case "identity_confirmed_critical" ->
                webSocketService.handleCriticalIdentityConfirmed(session.getId(), root);
            case "request_critical_identity_confirmation" -> handleRequestCriticalIdentityConfirmation(session, root);
            case "get_active_sessions" -> handleGetActiveSessions(session, root);
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

        log.info("✅ [WS] Conexão LCU registrada: '{}' (session: {}, {}:{})",
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

        // Enviar para todas as outras sessões do mesmo jogador (frontend)
        try {
            Map<String, Object> data = new HashMap<>();
            data.put(FIELD_SUMMONER_NAME, finalSummonerName);
            webSocketService.sendToPlayers("lcu_connection_registered", data, List.of(finalSummonerName));
            log.info("📡 [WS] Notificação lcu_connection_registered enviada para todas as sessões de: {}",
                    finalSummonerName);
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

            log.info("✅ [CoreWS] Lista de {} sessões ativas enviada para sessionId={}",
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

    private void handleElectronIdentify(WebSocketSession session, JsonNode root) throws IOException {
        try {
            log.info("🔍 [CoreWS] Recebendo electron_identify de sessionId={}", session.getId());

            // ✅ Validar fonte
            String source = root.path("source").asText("");
            if (!"electron_main".equals(source)) {
                log.warn("⚠️ [CoreWS] Identificação NÃO veio do Electron main! Source: {}", source);
                // Aceitar mas marcar como não-verificado
            }

            // ✅ Extrair dados
            String summonerName = root.path("summonerName").asText(null);
            String puuid = root.path("puuid").asText(null);
            String gameName = root.path("gameName").asText(null);
            String tagLine = root.path("tagLine").asText(null);
            String summonerId = root.path("summonerId").asText(null);

            if (summonerName == null || puuid == null) {
                log.error("❌ [CoreWS] Identificação incompleta! summonerName={}, puuid={}", summonerName, puuid);
                session.sendMessage(new TextMessage(
                        "{\"type\":\"electron_identified\",\"success\":false,\"error\":\"Dados incompletos\"}"));
                return;
            }

            // ✅ CRÍTICO: Validar constraint PUUID único via RedisPlayerMatchService
            if (!redisPlayerMatch.validatePuuidConstraint(summonerName, puuid)) {
                log.error("🚨 [CoreWS] PUUID CONFLITO! {} não pode ser vinculado ao PUUID {}", summonerName, puuid);
                session.sendMessage(new TextMessage(
                        "{\"type\":\"electron_identified\",\"success\":false,\"error\":\"PUUID já vinculado a outro jogador\"}"));
                return;
            }

            // ✅ REGISTRAR com VERIFICAÇÃO
            sessionRegistry.registerPlayer(summonerName, session.getId());

            // ✅ ARMAZENAR PUUID constraint no Redis (para validação futura)
            redisPlayerMatch.registerPuuidConstraint(summonerName, puuid);

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

            String playerInfoJson = mapper.writeValueAsString(playerInfo);
            redisWSSession.storePlayerInfo(session.getId(), playerInfoJson);

            log.info("✅ [CoreWS] {} identificado via Electron (PUUID: {}...)",
                    summonerName, puuid.substring(0, Math.min(8, puuid.length())));

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

        // ✅ REDIS ONLY: Armazena identificação completa no Redis (fonte única da
        // verdade)
        try {
            String playerInfoJson = mapper.writeValueAsString(playerData);
            redisWSSession.storePlayerInfo(session.getId(), playerInfoJson);
            log.debug("✅ [CoreWS] Player info armazenado no Redis: sessionId={}", session.getId());
        } catch (Exception e) {
            log.error("❌ [CoreWS] Erro ao armazenar player info no Redis: sessionId={}", session.getId(), e);
        }

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
            redisTemplate.opsForValue().set(ackKey, "true", java.time.Duration.ofMinutes(5));

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
            redisTemplate.opsForValue().set(ackKey, "true", java.time.Duration.ofMinutes(10));

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
            redisTemplate.opsForValue().set(ackKey, "true", java.time.Duration.ofHours(1));

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

        queueService.joinQueue(summonerName, data.path("region").asText("br1"), data.path(FIELD_PLAYER_ID).asLong(0),
                data.has(FIELD_CUSTOM_LP) && !data.get(FIELD_CUSTOM_LP).isNull() ? data.get(FIELD_CUSTOM_LP).asInt()
                        : null,
                data.path("primaryLane").asText(null), data.path("secondaryLane").asText(null));
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
}
