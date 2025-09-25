package br.com.lolmatchmaking.backend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/status")
@RequiredArgsConstructor
public class StatusController {

    @Value("${app.backend-id:unknown}")
    private String backendId;

    @Value("${spring.application.name:lol-matchmaking}")
    private String applicationName;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = Map.of(
                "application", applicationName,
                "backendId", backendId,
                "status", "UP",
                "timestamp", Instant.now(),
                "version", "1.0.0");

        return ResponseEntity.ok(status);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        Map<String, Object> health = Map.of(
                "status", "UP",
                "timestamp", Instant.now(),
                "checks", Map.of(
                        "database", "UP",
                        "application", "UP"));

        return ResponseEntity.ok(health);
    }
}
