package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.service.MatchmakingService;
import br.com.lolmatchmaking.backend.service.QueueService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;
    private final MatchmakingService matchmakingService;

    /**
     * GET /api/queue/status
     * Obtém status da fila
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getQueueStatus(
            @RequestParam(required = false) String currentPlayer) {
        try {
            log.info("📊 Obtendo status da fila (currentPlayer: {})", currentPlayer);

            MatchmakingService.QueueStatus status = matchmakingService.getQueueStatus(currentPlayer);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "status", status));

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

            // Entrar na fila
            boolean success = matchmakingService.addPlayerToQueue(
                    request.getPlayerId(),
                    request.getSummonerName(),
                    request.getRegion(),
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
            boolean success = matchmakingService.removePlayerFromQueue(
                    request.getPlayerId(),
                    request.getSummonerName());

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
            matchmakingService.syncCacheWithDatabase();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Sincronização da fila concluída"));

        } catch (Exception e) {
            log.error("❌ Erro ao sincronizar fila", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/queue/add-bot
     * Adiciona bot à fila (para debug/teste)
     */
    @PostMapping("/add-bot")
    public ResponseEntity<Map<String, Object>> addBotToQueue(@RequestBody AddBotRequest request) {
        try {
            log.info("🤖 Adicionando bot à fila: {}", request.getBotName());

            // Adicionar bot
            boolean success = matchmakingService.addPlayerToQueue(
                    request.getBotId(),
                    request.getBotName(),
                    request.getRegion(),
                    request.getCustomLp(),
                    request.getPrimaryLane(),
                    request.getSecondaryLane());

            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Bot adicionado à fila com sucesso"));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Erro ao adicionar bot à fila"));
            }

        } catch (Exception e) {
            log.error("❌ Erro ao adicionar bot à fila", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/queue/reset-bot-counter
     * Reseta contador de bots (para debug/teste)
     */
    @PostMapping("/reset-bot-counter")
    public ResponseEntity<Map<String, Object>> resetBotCounter() {
        try {
            log.info("🔄 Resetando contador de bots");

            // TODO: Implementar reset do contador de bots
            // matchmakingService.resetBotCounter();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Contador de bots resetado"));

        } catch (Exception e) {
            log.error("❌ Erro ao resetar contador de bots", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/queue/size
     * Obtém tamanho da fila
     */
    @GetMapping("/size")
    public ResponseEntity<Map<String, Object>> getQueueSize() {
        try {
            int size = matchmakingService.getQueueSize();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "size", size));

        } catch (Exception e) {
            log.error("❌ Erro ao obter tamanho da fila", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/queue/clear
     * Limpa a fila (para debug/admin)
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearQueue() {
        try {
            log.info("🧹 Limpando fila");

            matchmakingService.clearQueue();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Fila limpa com sucesso"));

        } catch (Exception e) {
            log.error("❌ Erro ao limpar fila", e);
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

    @Data
    public static class AddBotRequest {
        private Long botId;
        private String botName;
        private String region;
        private Integer customLp;
        private String primaryLane;
        private String secondaryLane;
    }
}