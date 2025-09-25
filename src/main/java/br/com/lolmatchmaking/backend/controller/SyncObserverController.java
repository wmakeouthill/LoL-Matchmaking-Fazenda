package br.com.lolmatchmaking.backend.controller;

// ...existing code...
import br.com.lolmatchmaking.backend.service.SyncObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/internal/sync")
@RequiredArgsConstructor
public class SyncObserverController {

    private final SyncObserver syncObserver;

    /**
     * Retorna estatísticas básicas do observador de sincronização.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = syncObserver.getObserverStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * Força uma observação imediata dos eventos (executa observeEvents).
     */
    @PostMapping("/force")
    public ResponseEntity<String> forceObservation() {
        syncObserver.forceObservation();
        return ResponseEntity.ok("ok: forced observation");
    }

    /**
     * Limpa eventos processados (apaga processed events e caches internos do observer).
     */
    @PostMapping("/clear")
    public ResponseEntity<String> clearProcessed() {
        syncObserver.clearProcessedEvents();
        return ResponseEntity.ok("ok: cleared processed events");
    }
}
// ...existing code...
