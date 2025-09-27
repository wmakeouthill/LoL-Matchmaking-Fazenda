package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.domain.entity.EventInbox;
import br.com.lolmatchmaking.backend.domain.repository.EventInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {
    private final EventInboxRepository inboxRepository;
    @Value("${app.backend.sync.enabled:true}")
    private boolean syncEnabled;
    private static final String KEY_SUCCESS = "success";
    private final br.com.lolmatchmaking.backend.service.MatchmakingService matchmakingService;

    @PostMapping("/event")
    @Transactional
    public ResponseEntity<Map<String, Object>> receiveEvent(@RequestBody Map<String, Object> body) {
        if (!syncEnabled) {
            return ResponseEntity.status(503).body(Map.of("success", false, "error", "sync disabled"));
        }

        String eventId = (String) body.getOrDefault("eventId", UUID.randomUUID().toString());
        if (inboxRepository.findByEventId(eventId).isPresent()) {
            return ResponseEntity.ok(Map.of("success", true, "deduped", true));
        }
        EventInbox inbox = EventInbox.builder()
                .eventId(eventId)
                .eventType((String) body.getOrDefault("type", "unknown"))
                .backendId((String) body.getOrDefault("backendId", "unknown"))
                .matchId(body.get("matchId") instanceof Number n ? n.longValue() : null)
                .payload(body.containsKey("data") ? body.get("data").toString() : null)
                .timestamp(System.currentTimeMillis())
                .build();
        inboxRepository.save(inbox);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * GET /api/sync/status?summonerName=...
     * Endpoint de compatibilidade para o frontend: retorna um status básico de
     * sincronização.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSyncStatus(@RequestParam String summonerName) {
        try {
            // Fallback simples: verificar se o jogador está na fila / em draft / em jogo
            String status = "none";

            // Verifica fila
            boolean inQueue = matchmakingService.isPlayerInQueue(null, summonerName);
            if (inQueue) {
                status = "in_queue";
            }

            // Verifica se em draft (reemitir se necessário é feito por WebSocket)
            // Tentativa leve: procurar drafts ativos pelo nome na DraftFlowService
            // (snapshot)
            // Se não for possível determinar aqui, retornar "none" ou o que for detectado
            // acima

            Map<String, Object> resp = Map.of("status", status);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "none", "error", e.getMessage()));
        }
    }
}
