package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.service.MatchFoundService;
import br.com.lolmatchmaking.backend.service.GameInProgressService;
import br.com.lolmatchmaking.backend.service.DraftService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/match")
@RequiredArgsConstructor
public class MatchController {

    private final MatchFoundService matchFoundService;
    private final GameInProgressService gameInProgressService;
    private final DraftService draftService;

    /**
     * POST /api/match/accept
     * Aceita uma partida encontrada
     */
    @PostMapping("/accept")
    public ResponseEntity<Map<String, Object>> acceptMatch(@RequestBody AcceptMatchRequest request) {
        try {
            log.info("✅ Aceitando partida: {} (jogador: {})", request.getMatchId(), request.getPlayerName());

            if (request.getMatchId() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "ID da partida é obrigatório"));
            }

            if (request.getPlayerName() == null || request.getPlayerName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Nome do jogador é obrigatório"));
            }

            boolean success = matchFoundService.acceptMatch(
                    request.getMatchId(),
                    request.getPlayerName());

            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Partida aceita com sucesso"));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Erro ao aceitar partida"));
            }

        } catch (Exception e) {
            log.error("❌ Erro ao aceitar partida", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/match/decline
     * Recusa uma partida encontrada
     */
    @PostMapping("/decline")
    public ResponseEntity<Map<String, Object>> declineMatch(@RequestBody DeclineMatchRequest request) {
        try {
            log.info("❌ Recusando partida: {} (jogador: {})", request.getMatchId(), request.getPlayerName());

            if (request.getMatchId() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "ID da partida é obrigatório"));
            }

            if (request.getPlayerName() == null || request.getPlayerName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Nome do jogador é obrigatório"));
            }

            boolean success = matchFoundService.declineMatch(
                    request.getMatchId(),
                    request.getPlayerName());

            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Partida recusada com sucesso"));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Erro ao recusar partida"));
            }

        } catch (Exception e) {
            log.error("❌ Erro ao recusar partida", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/matchmaking/check-acceptance
     * Verifica status de aceitação de uma partida
     */
    @GetMapping("/check-acceptance")
    public ResponseEntity<Map<String, Object>> checkAcceptanceStatus(
            @RequestParam Long matchId) {
        try {
            log.info("🔍 Verificando status de aceitação: {}", matchId);

            Map<String, Object> status = matchFoundService.getAcceptanceStatus(matchId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "status", status));

        } catch (Exception e) {
            log.error("❌ Erro ao verificar status de aceitação", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/match/draft-action
     * Executa ação no draft
     */
    @PostMapping("/draft-action")
    public ResponseEntity<Map<String, Object>> draftAction(@RequestBody DraftActionRequest request) {
        try {
            log.info("🎯 Ação no draft: {} (partida: {})", request.getActionType(), request.getMatchId());

            if (request.getMatchId() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "ID da partida é obrigatório"));
            }

            if (request.getActionType() == null || request.getActionType().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Tipo de ação é obrigatório"));
            }

            boolean success = draftService.processDraftAction(
                    request.getMatchId(),
                    request.getActionType(),
                    request.getChampionId(),
                    request.getPlayerName(),
                    request.getActionIndex());

            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Ação do draft processada com sucesso"));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Erro ao processar ação do draft"));
            }

        } catch (Exception e) {
            log.error("❌ Erro ao processar ação do draft", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/match/{matchId}/draft-session
     * Obtém sessão de draft
     */
    @GetMapping("/{matchId}/draft-session")
    public ResponseEntity<Map<String, Object>> getDraftSession(@PathVariable Long matchId) {
        try {
            log.info("🎯 Obtendo sessão de draft: {}", matchId);

            Map<String, Object> session = draftService.getDraftSession(matchId);

            if (session != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "session", session));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter sessão de draft", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/match/finish
     * Finaliza uma partida
     */
    @PostMapping("/finish")
    public ResponseEntity<Map<String, Object>> finishMatch(@RequestBody FinishMatchRequest request) {
        try {
            log.info("🏁 Finalizando partida: {} (vencedor: {})", request.getMatchId(), request.getWinnerTeam());

            if (request.getMatchId() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "ID da partida é obrigatório"));
            }

            if (request.getWinnerTeam() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Time vencedor é obrigatório"));
            }

            gameInProgressService.finishGame(
                    request.getMatchId(),
                    request.getWinnerTeam(),
                    request.getEndReason());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Partida finalizada com sucesso"));

        } catch (Exception e) {
            log.error("❌ Erro ao finalizar partida", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/match/cancel
     * Cancela uma partida
     */
    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancelMatch(@RequestBody CancelMatchRequest request) {
        try {
            log.info("❌ Cancelando partida: {} (motivo: {})", request.getMatchId(), request.getReason());

            if (request.getMatchId() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "ID da partida é obrigatório"));
            }

            gameInProgressService.cancelGame(
                    request.getMatchId(),
                    request.getReason());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Partida cancelada com sucesso"));

        } catch (Exception e) {
            log.error("❌ Erro ao cancelar partida", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // DTOs
    @Data
    public static class AcceptMatchRequest {
        private Long matchId;
        private String playerName;
    }

    @Data
    public static class DeclineMatchRequest {
        private Long matchId;
        private String playerName;
    }

    @Data
    public static class DraftActionRequest {
        private Long matchId;
        private String actionType;
        private String championId;
        private String playerName;
        private Integer actionIndex;
    }

    @Data
    public static class FinishMatchRequest {
        private Long matchId;
        private Integer winnerTeam;
        private String endReason;
    }

    @Data
    public static class CancelMatchRequest {
        private Long matchId;
        private String reason;
    }
}