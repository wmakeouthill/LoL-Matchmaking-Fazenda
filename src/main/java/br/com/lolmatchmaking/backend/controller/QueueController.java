package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.dto.QueueStatusDTO;
import br.com.lolmatchmaking.backend.service.QueueManagementService;
import br.com.lolmatchmaking.backend.util.SummonerAuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueManagementService queueManagementService;

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

            // Entrar na fila
            boolean success = queueManagementService.addToQueue(
                    request.getSummonerName(),
                    request.getRegion(),
                    request.getPlayerId(),
                    request.getCustomLp(),
                    request.getPrimaryLane(),
                    request.getSecondaryLane());

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

            return ResponseEntity.ok(activeMatch);

        } catch (Exception e) {
            log.error("❌ Erro ao buscar partida ativa: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
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
    }

    @Data
    public static class LeaveQueueRequest {
        private Long playerId;
        private String summonerName;
    }
}