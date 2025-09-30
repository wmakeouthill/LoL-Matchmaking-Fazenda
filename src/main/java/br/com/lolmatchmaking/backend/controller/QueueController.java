package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.dto.QueueStatusDTO;
import br.com.lolmatchmaking.backend.service.QueueManagementService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
                    "isActive", status.isActive()));

        } catch (Exception e) {
            log.error("❌ Erro ao obter status da fila", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/queue/join
     * Entra na fila de matchmaking
     */
    @PostMapping("/join")
    public ResponseEntity<Map<String, Object>> joinQueue(@RequestBody JoinQueueRequest request) {
        try {
            log.info("➕ Jogador entrando na fila: {}", request.getSummonerName());

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
     */
    @PostMapping("/leave")
    public ResponseEntity<Map<String, Object>> leaveQueue(@RequestBody LeaveQueueRequest request) {
        try {
            log.info("➖ Jogador saindo da fila: {}", request.getSummonerName());

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
     */
    @PostMapping("/force-sync")
    public ResponseEntity<Map<String, Object>> forceSyncQueue() {
        try {
            log.info("🔄 Forçando sincronização da fila");

            // Forçar sincronização
            queueManagementService.loadQueueFromDatabase();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Sincronização da fila concluída"));

        } catch (Exception e) {
            log.error("❌ Erro ao sincronizar fila", e);
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