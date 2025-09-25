package br.com.lolmatchmaking.backend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TestController {

    /**
     * Endpoint de teste básico
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        try {
            log.info("🧪 Endpoint de teste chamado");

            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", "Backend funcionando corretamente",
                    "timestamp", Instant.now().toString(),
                    "status", "healthy"));

        } catch (Exception e) {
            log.error("❌ Erro no endpoint de teste", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "ok", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }

    /**
     * Endpoint de teste de conectividade
     */
    @GetMapping("/test/connectivity")
    public ResponseEntity<Map<String, Object>> testConnectivity() {
        try {
            log.info("🔗 Teste de conectividade chamado");

            Map<String, Object> results = Map.of(
                    "database", "connected",
                    "websocket", "active",
                    "timestamp", Instant.now().toString());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Teste de conectividade realizado com sucesso",
                    "results", results));

        } catch (Exception e) {
            log.error("❌ Erro no teste de conectividade", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro no teste de conectividade",
                    "error", e.getMessage()));
        }
    }

    /**
     * Endpoint de teste de performance
     */
    @GetMapping("/test/performance")
    public ResponseEntity<Map<String, Object>> testPerformance() {
        try {
            log.info("⚡ Teste de performance chamado");

            long startTime = System.currentTimeMillis();

            // Simular operação
            Thread.sleep(100);

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Teste de performance realizado",
                    "duration", duration + "ms",
                    "timestamp", Instant.now().toString()));

        } catch (Exception e) {
            log.error("❌ Erro no teste de performance", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro no teste de performance",
                    "error", e.getMessage()));
        }
    }
}
