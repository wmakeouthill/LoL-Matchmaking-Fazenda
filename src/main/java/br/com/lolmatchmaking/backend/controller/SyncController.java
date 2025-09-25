package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.domain.entity.EventInbox;
import br.com.lolmatchmaking.backend.domain.repository.EventInboxRepository;
import lombok.RequiredArgsConstructor;
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

    @PostMapping("/event")
    @Transactional
    public ResponseEntity<Map<String, Object>> receiveEvent(@RequestBody Map<String, Object> body) {
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
}
