package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.dto.QueueStatusDTO;
import br.com.lolmatchmaking.backend.service.QueueManagementService;
import br.com.lolmatchmaking.backend.util.SummonerAuthUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueManagementService queueManagementService;
    private final ObjectMapper objectMapper;
    private final br.com.lolmatchmaking.backend.service.UnifiedLogService unifiedLogService;
    private final br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService webSocketService;
    private final br.com.lolmatchmaking.backend.websocket.SessionRegistry sessionRegistry;

    // ✅ NOVO: Dependências Redis para obter informações da sessão
    private final br.com.lolmatchmaking.backend.service.redis.RedisWebSocketSessionService redisWSSession;
    private final br.com.lolmatchmaking.backend.service.LCUService lcuService;

    /**
     * GET /api/queue/status
     * Obtém status da fila
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getQueueStatus(
            @RequestParam(required = false) String currentPlayerDisplayName) {
        try {
            log.info("📊 Obtendo status da fila (currentPlayer: {})", currentPlayerDisplayName);

            QueueStatusDTO status = queueManagementService.getQueueStatus(currentPlayerDisplayName);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "playersInQueue", status.getPlayersInQueue(),
                    "playersInQueueList", status.getPlayersInQueueList(),
                    "averageWaitTime", status.getAverageWaitTime(),
                    "estimatedMatchTime", status.getEstimatedMatchTime(),
                    "isActive", status.isActive(),
                    "isCurrentPlayerInQueue", status.isCurrentPlayerInQueue()));

        } catch (Exception e) {
            log.error("❌ Erro ao obter status da fila", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/queue/join
     * Entra na fila de matchmaking
     * ✅ MODIFICADO: Valida header X-Summoner-Name
     */
    @PostMapping("/join")
    public ResponseEntity<Map<String, Object>> joinQueue(
            @RequestBody JoinQueueRequest request,
            HttpServletRequest httpRequest) {
        try {
            // ✅ Validar header X-Summoner-Name
            String authenticatedSummoner = SummonerAuthUtil.getSummonerNameFromRequest(httpRequest);
            log.info("➕ [{}] Jogador entrando na fila: {}", authenticatedSummoner, request.getSummonerName());

            // ✅ NOVO: LOG DETALHADO DA VINCULAÇÃO PLAYER-SESSÃO (FRONTEND → BACKEND)
            log.info("🔗 [Player-Sessions] ===== FRONTEND → BACKEND: ENTRADA NA FILA =====");
            log.info("🔗 [Player-Sessions] [BACKEND] Summoner: {}", request.getSummonerName());
            log.info("🔗 [Player-Sessions] [BACKEND] Region: {}", request.getRegion());
            log.info("🔗 [Player-Sessions] [BACKEND] GameName: {}", request.getGameName());
            log.info("🔗 [Player-Sessions] [BACKEND] TagLine: {}", request.getTagLine());
            log.info("🔗 [Player-Sessions] [BACKEND] PUUID: {}", request.getPuuid());
            log.info("🔗 [Player-Sessions] [BACKEND] Summoner ID: {}", request.getSummonerId());
            log.info("🔗 [Player-Sessions] [BACKEND] Profile Icon: {}", request.getProfileIconId());
            log.info("🔗 [Player-Sessions] [BACKEND] Level: {}", request.getSummonerLevel());
            log.info("🔗 [Player-Sessions] [BACKEND] Primary Lane: {}", request.getPrimaryLane());
            log.info("🔗 [Player-Sessions] [BACKEND] Secondary Lane: {}", request.getSecondaryLane());
            log.info("🔗 [Player-Sessions] [BACKEND] Custom LP: {}", request.getCustomLp());
            log.info("🔗 [Player-Sessions] [BACKEND] Client IP: {}", httpRequest.getRemoteAddr());
            log.info("🔗 [Player-Sessions] [BACKEND] User Agent: {}", httpRequest.getHeader("User-Agent"));
            log.info("🔗 [Player-Sessions] ======================================================");

            // ✅ NOVO: Enviar log para Electron se houver sessões registradas
            if (unifiedLogService.hasRegisteredPlayerSessionLogSessions()) {
                unifiedLogService.sendPlayerSessionInfoLog("[Player-Sessions] [FRONTEND→BACKEND]",
                        "===== FRONTEND → BACKEND: ENTRADA NA FILA =====");
                unifiedLogService.sendPlayerSessionInfoLog("[Player-Sessions] [FRONTEND→BACKEND]",
                        "Summoner: %s | Region: %s | PUUID: %s",
                        request.getSummonerName(), request.getRegion(), request.getPuuid());
                unifiedLogService.sendPlayerSessionInfoLog("[Player-Sessions] [FRONTEND→BACKEND]",
                        "GameName: %s | TagLine: %s | Level: %s",
                        request.getGameName(), request.getTagLine(), request.getSummonerLevel());
                unifiedLogService.sendPlayerSessionInfoLog("[Player-Sessions] [FRONTEND→BACKEND]",
                        "======================================================");
            }

            // ✅ Validar se summonerName do body corresponde ao header
            if (!authenticatedSummoner.equalsIgnoreCase(request.getSummonerName())) {
                log.warn("⚠️ Tentativa de entrar na fila com summonerName diferente do autenticado: {} != {}",
                        authenticatedSummoner, request.getSummonerName());
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error",
                                "Nome do invocador não corresponde ao jogador autenticado"));
            }

            // Validar dados
            if (request.getSummonerName() == null || request.getSummonerName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Nome do invocador é obrigatório"));
            }

            if (request.getRegion() == null || request.getRegion().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Região é obrigatória"));
            }

            // Verificar se pode entrar na fila (LCU + Discord + Canal)
            if (!queueManagementService.canPlayerJoinQueue(request.getSummonerName())) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error",
                                "Não é possível entrar na fila. Verifique se o LCU está conectado, o Discord bot está ativo e você está no canal monitorado"));
            }

            // ✅ NOVO: Enviar diretamente para Electron via WebSocket (COMUNICAÇÃO DIRETA)
            log.info("🔗 [Player-Sessions] [BACKEND] Enviando solicitação direta para Electron via WebSocket...");
            // ✅ CORRIGIDO: Usar apenas Redis (sem HashMap local)
            // Enviar dados via Redis para comunicação segura e distribuída
            webSocketService.sendDirectToElectronViaRedis("queue_entry_request", request.getSummonerName(),
                    "queue_entry", request);

            // ✅ NOVO: Aguardar vinculação (Electron proativo responde)
            boolean sessionBound = waitForSessionBinding(request.getSummonerName(), 3000); // 3 segundos timeout

            if (!sessionBound) {
                log.error("❌ [Player-Sessions] [BACKEND] Electron não respondeu à solicitação direta para {}",
                        request.getSummonerName());
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error",
                                "Electron não respondeu. Verifique se o Electron está conectado e o LCU está ativo."));
            }

            // ✅ NOVO: Obter informações da sessão Electron vinculada
            String sessionId = getSessionIdForPlayer(request.getSummonerName());
            String puuid = getPuuidForPlayer(request.getSummonerName());
            Map<String, Object> lcuData = getLcuDataForPlayer(request.getSummonerName());

            log.info(
                    "✅ [Player-Sessions] [BACKEND] Electron respondeu e vinculação confirmada para {} (sessionId: {}, puuid: {})",
                    request.getSummonerName(), sessionId, puuid);

            // ✅ CORRIGIDO: Entrar na fila COM informações da sessão Electron
            boolean success = queueManagementService.addToQueueWithSession(
                    request.getSummonerName(),
                    request.getRegion(),
                    request.getPlayerId(),
                    request.getCustomLp(),
                    request.getPrimaryLane(),
                    request.getSecondaryLane(),
                    sessionId, // ✅ Sessão Electron vinculada
                    puuid, // ✅ PUUID da sessão LCU
                    lcuData); // ✅ Dados completos do LCU

            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Jogador adicionado à fila com sucesso"));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Erro ao entrar na fila"));
            }

        } catch (Exception e) {
            log.error("❌ Erro ao entrar na fila", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/queue/leave
     * Sai da fila de matchmaking
     * ✅ MODIFICADO: Valida header X-Summoner-Name
     */
    @PostMapping("/leave")
    public ResponseEntity<Map<String, Object>> leaveQueue(
            @RequestBody LeaveQueueRequest request,
            HttpServletRequest httpRequest) {
        try {
            // ✅ Validar header X-Summoner-Name
            String authenticatedSummoner = SummonerAuthUtil.getSummonerNameFromRequest(httpRequest);
            log.info("➖ [{}] Jogador saindo da fila: {}", authenticatedSummoner, request.getSummonerName());

            // ✅ Validar se summonerName do body corresponde ao header
            if (!authenticatedSummoner.equalsIgnoreCase(request.getSummonerName())) {
                log.warn("⚠️ Tentativa de sair da fila com summonerName diferente do autenticado: {} != {}",
                        authenticatedSummoner, request.getSummonerName());
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error",
                                "Nome do invocador não corresponde ao jogador autenticado"));
            }

            // Validar dados
            if (request.getSummonerName() == null || request.getSummonerName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Nome do invocador é obrigatório"));
            }

            // Sair da fila
            boolean success = queueManagementService.removeFromQueue(request.getSummonerName());

            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Jogador removido da fila com sucesso"));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Jogador não encontrado na fila"));
            }

        } catch (Exception e) {
            log.error("❌ Erro ao sair da fila", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/queue/force-sync
     * Força sincronização da fila
     * ✅ MODIFICADO: Valida header X-Summoner-Name (Admin endpoint)
     */
    @PostMapping("/force-sync")
    public ResponseEntity<Map<String, Object>> forceSyncQueue(HttpServletRequest request) {
        try {
            // ✅ Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("🔄 [{}] [ADMIN] Forçando sincronização da fila", summonerName);

            // Forçar sincronização
            queueManagementService.loadQueueFromDatabase();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Sincronização da fila concluída"));

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Header X-Summoner-Name ausente em requisição de force-sync");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "error", "Header X-Summoner-Name obrigatório"));
        } catch (Exception e) {
            log.error("❌ Erro ao sincronizar fila", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/queue/add-bot
     * Adiciona um bot à fila para testes
     * ✅ MODIFICADO: Valida header X-Summoner-Name (Admin endpoint)
     */
    @PostMapping("/add-bot")
    public ResponseEntity<Map<String, Object>> addBotToQueue(HttpServletRequest request) {
        try {
            // ✅ Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("🤖 [{}] [ADMIN] Adicionando bot à fila", summonerName);

            queueManagementService.addBotToQueue();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Bot adicionado à fila com sucesso"));

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Header X-Summoner-Name ausente em requisição de add-bot");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "error", "Header X-Summoner-Name obrigatório"));
        } catch (Exception e) {
            log.error("❌ Erro ao adicionar bot à fila", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/queue/reset-bot-counter
     * Reseta o contador de bots
     * ✅ MODIFICADO: Valida header X-Summoner-Name (Admin endpoint)
     */
    @PostMapping("/reset-bot-counter")
    public ResponseEntity<Map<String, Object>> resetBotCounter(HttpServletRequest request) {
        try {
            // ✅ Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("🔄 [{}] [ADMIN] Resetando contador de bots", summonerName);

            queueManagementService.resetBotCounter();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Contador de bots resetado com sucesso"));

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Header X-Summoner-Name ausente em requisição de reset-bot-counter");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "error", "Header X-Summoner-Name obrigatório"));
        } catch (Exception e) {
            log.error("❌ Erro ao resetar contador de bots", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/queue/refresh
     * Recarrega a fila do banco (sincroniza cache)
     * ✅ MODIFICADO: Valida header X-Summoner-Name (Admin endpoint)
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshQueue(HttpServletRequest request) {
        try {
            // ✅ Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("🔄 [{}] [ADMIN] Sincronizando fila com banco de dados", summonerName);

            queueManagementService.loadQueueFromDatabase();
            return ResponseEntity.ok(Map.of("success", true, "message", "Fila sincronizada com sucesso"));

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Header X-Summoner-Name ausente em requisição de refresh");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "error", "Header X-Summoner-Name obrigatório"));
        } catch (Exception e) {
            log.error("❌ Erro ao sincronizar fila", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * ✅ NOVO: GET /api/queue/my-active-match
     * Busca partida ativa (draft ou in_progress) do jogador logado
     */
    @GetMapping("/my-active-match")
    public ResponseEntity<Map<String, Object>> getMyActiveMatch(
            @RequestParam(required = true) String summonerName,
            HttpServletRequest httpRequest) {
        try {
            // 🔒 Autenticação via header
            String authenticatedSummoner = SummonerAuthUtil.getSummonerNameFromRequest(httpRequest);

            // 🔍 Validação de ownership
            if (!authenticatedSummoner.equalsIgnoreCase(summonerName)) {
                log.warn("⚠️ [{}] Tentativa de acessar partida ativa de outro jogador: {}",
                        authenticatedSummoner, summonerName);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("success", false, "error",
                                "Nome do invocador não corresponde ao jogador autenticado"));
            }

            log.info("🔍 [{}] Buscando partida ativa para jogador: {}", authenticatedSummoner, summonerName);

            if (summonerName == null || summonerName.trim().isEmpty()) {
                log.warn("⚠️ [{}] SummonerName não fornecido", authenticatedSummoner);
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "summonerName é obrigatório"));
            }

            Map<String, Object> activeMatch = queueManagementService.getActiveMatchForPlayer(summonerName);

            if (activeMatch == null || activeMatch.isEmpty()) {
                log.info("✅ [{}] Nenhuma partida ativa encontrada para: {}", authenticatedSummoner, summonerName);
                return ResponseEntity.notFound().build();
            }

            log.info("✅ [{}] Partida ativa encontrada - ID: {}, Status: {}",
                    authenticatedSummoner, activeMatch.get("id"), activeMatch.get("status"));

            // ✅ DEBUG: Verificar se phases está sendo enviado
            if (activeMatch.containsKey("phases")) {
                Object phases = activeMatch.get("phases");
                if (phases instanceof List<?>) {
                    log.info("🔍 [QueueController] phases no Map: {} elementos", ((List<?>) phases).size());
                } else {
                    log.warn("⚠️ [QueueController] phases existe mas não é List: type={}",
                            phases != null ? phases.getClass().getSimpleName() : "NULL");
                }
            } else {
                log.warn("⚠️ [QueueController] phases NÃO existe no Map! Keys: {}", activeMatch.keySet());
            }

            // ✅ Serializar para JSON e logar (para ver EXATAMENTE o que vai ser enviado)
            try {
                String jsonResponse = objectMapper.writeValueAsString(activeMatch);
                log.info("🔍 [QueueController] JSON que será enviado (primeiros 500 chars): {}",
                        jsonResponse.length() > 500 ? jsonResponse.substring(0, 500) + "..." : jsonResponse);

                // Verificar se "phases" aparece no JSON
                if (jsonResponse.contains("\"phases\"")) {
                    log.info("✅ [QueueController] 'phases' ENCONTRADO no JSON serializado");
                } else {
                    log.warn("⚠️ [QueueController] 'phases' NÃO ENCONTRADO no JSON serializado!");
                }
            } catch (Exception e) {
                log.error("❌ [QueueController] Erro ao serializar response para debug", e);
            }

            return ResponseEntity.ok(activeMatch);

        } catch (Exception e) {
            log.error("❌ Erro ao buscar partida ativa: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * ✅ NOVO: Aguarda vinculação player-sessão com timeout
     */
    private boolean waitForSessionBinding(String summonerName, long timeoutMs) {
        try {
            String normalizedName = summonerName.toLowerCase().trim();
            long startTime = System.currentTimeMillis();

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                // Verificar se há uma sessão vinculada para este jogador via Redis
                Optional<String> sessionIdOpt = redisWSSession.getSessionBySummoner(normalizedName);

                if (sessionIdOpt.isPresent()) {
                    String sessionId = sessionIdOpt.get();
                    WebSocketSession session = webSocketService.getSession(sessionId);
                    
                    if (session != null && session.isOpen()) {
                        log.info("✅ [Player-Sessions] [BACKEND] Vinculação encontrada: {} → {}", normalizedName,
                                session.getId());
                        return true;
                    }
                }

                // Aguardar 100ms antes da próxima verificação
                Thread.sleep(100);
            }

            log.warn("⚠️ [Player-Sessions] [BACKEND] Timeout aguardando vinculação para {}", normalizedName);
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ [Player-Sessions] [BACKEND] Interrompido aguardando vinculação para {}", summonerName, e);
            return false;
        } catch (Exception e) {
            log.error("❌ [Player-Sessions] [BACKEND] Erro aguardando vinculação para {}", summonerName, e);
            return false;
        }
    }

    // DTOs
    @Data
    public static class JoinQueueRequest {
        private Long playerId;
        private String summonerName;
        private String region;
        private Integer customLp;
        private String primaryLane;
        private String secondaryLane;

        // ✅ NOVO: Campos adicionais para logs detalhados
        private String gameName;
        private String tagLine;
        private String puuid;
        private String summonerId;
        private String profileIconId;
        private Integer summonerLevel;
    }

    @Data
    public static class LeaveQueueRequest {
        private Long playerId;
        private String summonerName;
    }

    /**
     * ✅ NOVO: Obter sessionId da sessão Electron vinculada
     */
    private String getSessionIdForPlayer(String summonerName) {
        try {
            String normalizedName = summonerName.toLowerCase().trim();
            Optional<String> sessionIdOpt = redisWSSession.getSessionBySummoner(normalizedName);
            return sessionIdOpt.orElse(null);
        } catch (Exception e) {
            log.error("❌ [Player-Sessions] [BACKEND] Erro ao obter sessionId para {}", summonerName, e);
            return null;
        }
    }

    /**
     * ✅ NOVO: Obter PUUID da sessão LCU vinculada via LCUService
     */
    private String getPuuidForPlayer(String summonerName) {
        try {
            String normalizedName = summonerName.toLowerCase().trim();
            // Buscar PUUID via LCUService (que tem acesso ao Redis)
            Optional<String> puuidOpt = lcuService.getPuuidForSummoner(normalizedName);
            return puuidOpt.orElse(null);
        } catch (Exception e) {
            log.error("❌ [Player-Sessions] [BACKEND] Erro ao obter PUUID para {}", summonerName, e);
            return null;
        }
    }

    /**
     * ✅ NOVO: Obter dados completos do LCU da sessão vinculada via LCUService
     */
    private Map<String, Object> getLcuDataForPlayer(String summonerName) {
        try {
            String normalizedName = summonerName.toLowerCase().trim();
            // Buscar dados completos do LCU via LCUService
            Optional<Map<String, Object>> lcuDataOpt = lcuService.getLcuDataForSummoner(normalizedName);
            return lcuDataOpt.orElse(Collections.emptyMap());
        } catch (Exception e) {
            log.error("❌ [Player-Sessions] [BACKEND] Erro ao obter dados LCU para {}", summonerName, e);
            return Collections.emptyMap();
        }
    }
}