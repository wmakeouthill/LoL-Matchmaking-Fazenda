package br.com.lolmatchmaking.backend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    /**
     * GET /api/health
     * Endpoint de saúde para verificação do frontend
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> health = Map.of(
                "status", "ok",
                "timestamp", Instant.now(),
                "service", "lol-matchmaking-backend"
        );

        return ResponseEntity.ok(health);
    }
}
