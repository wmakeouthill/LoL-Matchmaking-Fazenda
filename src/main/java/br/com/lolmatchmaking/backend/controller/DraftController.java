package br.com.lolmatchmaking.backend.controller;

import lombok.RequiredArgsConstructor;
import br.com.lolmatchmaking.backend.service.DraftService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DraftController {
    private final DraftService draftService;
    private final br.com.lolmatchmaking.backend.service.DraftFlowService draftFlowService;
    private static final String KEY_ERROR = "error";

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
        return ResponseEntity.ok(Map.of("success", true));
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
        return ResponseEntity.ok(Map.of("success", true));
    }
}
