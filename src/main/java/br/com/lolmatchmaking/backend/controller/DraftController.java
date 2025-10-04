package br.com.lolmatchmaking.backend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import br.com.lolmatchmaking.backend.service.DraftService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DraftController {
    private final DraftService draftService;
    private final br.com.lolmatchmaking.backend.service.DraftFlowService draftFlowService;
    private static final String KEY_ERROR = "error";
    private static final String KEY_SUCCESS = "success";

    record ConfirmSyncRequest(Long matchId, String playerId, Integer actionIndex) {
    }

    @PostMapping("/draft/sync-confirm")
    public ResponseEntity<Map<String, Object>> confirmSync(@RequestBody ConfirmSyncRequest req) {
        if (req.matchId() == null || req.playerId() == null || req.actionIndex() == null) {
            return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, "Parâmetros obrigatórios ausentes"));
        }
        draftService.confirmSync(req.matchId(), req.playerId(), req.actionIndex());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    record ConfirmDraftRequest(Long matchId, String playerId) {
    }

    @PostMapping("/match/confirm-draft")
    public ResponseEntity<Map<String, Object>> confirmDraft(@RequestBody ConfirmDraftRequest req) {
        if (req.matchId() == null || req.playerId() == null) {
            return ResponseEntity.badRequest().body(Map.of(KEY_ERROR, "matchId e playerId são obrigatórios"));
        }
        draftService.confirmDraft(req.matchId(), req.playerId());
        return ResponseEntity.ok(Map.of(KEY_SUCCESS, true));
    }

    @GetMapping("/match/{matchId}/draft-session")
    public ResponseEntity<Map<String, Object>> getDraftSession(@PathVariable Long matchId) {
        Map<String, Object> snapshot = draftFlowService.snapshot(matchId);
        if (snapshot.containsKey("exists") && !Boolean.TRUE.equals(snapshot.get("exists"))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(snapshot);
    }

    @GetMapping("/match/{matchId}/confirmation-status")
    public ResponseEntity<Map<String, Object>> confirmationStatus(@PathVariable Long matchId) {
        return ResponseEntity.ok(Map.of("confirmationData", draftService.confirmationStatus(matchId)));
    }

    record ChangePickRequest(Long matchId, String playerId, String championId) {
    }

    @PostMapping("/match/change-pick")
    public ResponseEntity<Map<String, Object>> changePick(@RequestBody ChangePickRequest req) {
        if (req.matchId() == null || req.playerId() == null || req.championId() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of(KEY_ERROR, "matchId, playerId e championId são obrigatórios"));
        }
        draftService.changePick(req.matchId(), req.playerId(), req.championId());
        return ResponseEntity.ok(Map.of(KEY_SUCCESS, true));
    }

    // ✅ NOVO: Endpoint para editar picks no modal de confirmação
    record ChangePickPathRequest(String playerId, Integer championId, Boolean confirmed) {
    }

    @PostMapping("/draft/{matchId}/changePick")
    public ResponseEntity<Map<String, Object>> changePickPath(
            @PathVariable Long matchId,
            @RequestBody ChangePickPathRequest req) {
        log.info("🔄 [DraftController] changePick chamado: matchId={}, playerId={}, championId={}",
                matchId, req.playerId(), req.championId());

        if (req.playerId() == null || req.championId() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of(KEY_ERROR, "playerId e championId são obrigatórios"));
        }

        draftService.changePick(matchId, req.playerId(), String.valueOf(req.championId()));
        return ResponseEntity.ok(Map.of(KEY_SUCCESS, true));
    }

    // ✅ CORREÇÃO #1: Endpoint para processar ações de draft (pick/ban)
    record DraftActionRequest(Long matchId, Integer actionIndex, String championId, String playerId, String action) {
    }

    @PostMapping("/match/draft-action")
    public ResponseEntity<Map<String, Object>> processDraftAction(@RequestBody DraftActionRequest req) {
        log.info("\n╔════════════════════════════════════════════════════════════════╗");
        log.info("║  🎯 [DraftController] REQUISIÇÃO RECEBIDA                     ║");
        log.info("╚════════════════════════════════════════════════════════════════╝");
        log.info("📥 POST /match/draft-action");
        log.info("📋 Match ID: {}", req.matchId());
        log.info("📋 Action Index: {}", req.actionIndex());
        log.info("📋 Champion ID: {}", req.championId());
        log.info("📋 Player ID: {}", req.playerId());
        log.info("📋 Action: {}", req.action());

        if (req.matchId() == null || req.actionIndex() == null || req.championId() == null
                || req.playerId() == null) {
            log.warn("⚠️ [DraftController] Requisição inválida - campos obrigatórios faltando");
            log.info("════════════════════════════════════════════════════════════════\n");
            return ResponseEntity.badRequest().body(Map.of(
                    KEY_SUCCESS, false,
                    KEY_ERROR, "matchId, actionIndex, championId e playerId são obrigatórios"));
        }

        try {
            log.info("🔄 [DraftController] Chamando DraftFlowService.processAction()...");

            // ✅ Chamar DraftFlowService.processAction()
            boolean success = draftFlowService.processAction(
                    req.matchId(),
                    req.actionIndex(),
                    req.championId(),
                    req.playerId());

            if (success) {
                log.info("╔════════════════════════════════════════════════════════════════╗");
                log.info("║  ✅ [DraftController] AÇÃO PROCESSADA COM SUCESSO             ║");
                log.info("╚════════════════════════════════════════════════════════════════╝\n");
                return ResponseEntity.ok(Map.of(KEY_SUCCESS, true));
            } else {
                log.warn("╔════════════════════════════════════════════════════════════════╗");
                log.warn("║  ❌ [DraftController] AÇÃO REJEITADA                          ║");
                log.warn("╚════════════════════════════════════════════════════════════════╝");
                log.warn("⚠️ Motivos possíveis:");
                log.warn("   - Campeão já utilizado");
                log.warn("   - Jogador incorreto para esta ação");
                log.warn("   - Ação fora de ordem");
                log.info("════════════════════════════════════════════════════════════════\n");
                return ResponseEntity.badRequest().body(Map.of(
                        KEY_SUCCESS, false,
                        KEY_ERROR, "Ação inválida: campeão já utilizado, jogador errado ou fora de ordem"));
            }
        } catch (Exception e) {
            log.error("╔════════════════════════════════════════════════════════════════╗");
            log.error("║  💥 [DraftController] ERRO AO PROCESSAR                       ║");
            log.error("╚════════════════════════════════════════════════════════════════╝");
            log.error("❌ Exceção: {}", e.getMessage(), e);
            log.info("════════════════════════════════════════════════════════════════\n");
            return ResponseEntity.status(500).body(Map.of(
                    KEY_SUCCESS, false,
                    KEY_ERROR, "Erro ao processar ação: " + e.getMessage()));
        }
    }

    // ✅ NOVO: Endpoint para confirmação final individual (TODOS os 10 jogadores)
    record ConfirmFinalDraftRequest(String playerId) {
    }

    @PostMapping("/match/{matchId}/confirm-final-draft")
    public ResponseEntity<Map<String, Object>> confirmFinalDraft(
            @PathVariable Long matchId,
            @RequestBody ConfirmFinalDraftRequest req) {

        log.info("╔════════════════════════════════════════════════════════════════╗");
        log.info("║  ✅ [DraftController] CONFIRMAÇÃO FINAL RECEBIDA              ║");
        log.info("╚════════════════════════════════════════════════════════════════╝");
        log.info("📥 POST /match/{}/confirm-final-draft", matchId);
        log.info("👤 Player ID: {}", req.playerId());

        if (req.playerId() == null || req.playerId().trim().isEmpty()) {
            log.warn("⚠️ [DraftController] playerId não fornecido");
            return ResponseEntity.badRequest().body(Map.of(
                    KEY_SUCCESS, false,
                    KEY_ERROR, "playerId é obrigatório"));
        }

        try {
            // ✅ Chamar DraftFlowService para registrar confirmação individual
            Map<String, Object> result = draftFlowService.confirmFinalDraft(matchId, req.playerId());

            boolean allConfirmed = (boolean) result.getOrDefault("allConfirmed", false);
            int confirmedCount = (int) result.getOrDefault("confirmedCount", 0);
            int totalPlayers = (int) result.getOrDefault("totalPlayers", 10);

            log.info("✅ [DraftController] Confirmação registrada: {}/{} jogadores confirmaram",
                    confirmedCount, totalPlayers);

            if (allConfirmed) {
                log.info("╔════════════════════════════════════════════════════════════════╗");
                log.info("║  🎮 [DraftController] TODOS CONFIRMARAM - INICIANDO JOGO      ║");
                log.info("╚════════════════════════════════════════════════════════════════╝");
            }

            return ResponseEntity.ok(Map.of(
                    KEY_SUCCESS, true,
                    "allConfirmed", allConfirmed,
                    "confirmedCount", confirmedCount,
                    "totalPlayers", totalPlayers,
                    "message", allConfirmed
                            ? "Todos confirmaram! Iniciando jogo..."
                            : String.format("Confirmado! Aguardando %d jogadores...", totalPlayers - confirmedCount)));

        } catch (Exception e) {
            log.error("╔════════════════════════════════════════════════════════════════╗");
            log.error("║  💥 [DraftController] ERRO AO CONFIRMAR                       ║");
            log.error("╚════════════════════════════════════════════════════════════════╝");
            log.error("❌ Exceção: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    KEY_SUCCESS, false,
                    KEY_ERROR, "Erro ao confirmar: " + e.getMessage()));
        }
    }

    // ✅ NOVO: Endpoint para cancelar partida em progresso
    @DeleteMapping("/match/{matchId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelMatch(@PathVariable Long matchId) {
        log.info("╔════════════════════════════════════════════════════════════════╗");
        log.info("║  ❌ [DraftController] CANCELANDO PARTIDA                      ║");
        log.info("╚════════════════════════════════════════════════════════════════╝");
        log.info("🎯 Match ID: {}", matchId);

        try {
            draftFlowService.cancelMatch(matchId);

            log.info("✅ [DraftController] Partida cancelada com sucesso");
            return ResponseEntity.ok(Map.of(
                    KEY_SUCCESS, true,
                    "message", "Partida cancelada com sucesso"));

        } catch (Exception e) {
            log.error("❌ [DraftController] Erro ao cancelar partida: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    KEY_SUCCESS, false,
                    KEY_ERROR, "Erro ao cancelar partida: " + e.getMessage()));
        }
    }
}
