package br.com.lolmatchmaking.backend.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/internal/logs")
public class FrontendLogController {

    private static final Path FRONTEND_LOG = Path.of("frontend.log");
    private static final int MAX_LOG_CHARS = 200_000; // safety limit

    @org.springframework.beans.factory.annotation.Value("${app.logging.frontend.enabled:true}")
    private boolean frontendLoggingEnabled;

    /**
     * Recebe logs do frontend (Electron) e anexa em logs/frontend.log
     * Body esperado: { "logs": "...", "level": "info", "meta": {...} }
     */
    @PostMapping("/frontend")
    public ResponseEntity<Map<String, Object>> saveFrontendLog(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        try {
            String clientIp = request.getRemoteAddr();
            String level = body != null && body.get("level") != null ? body.get("level").toString() : "INFO";
            Object logsObj = body != null ? (body.getOrDefault("logs", body.getOrDefault("message", body))) : "";
            String logsStr = logsObj == null ? "" : logsObj.toString();

            if (logsStr.length() > MAX_LOG_CHARS) {
                logsStr = logsStr.substring(0, MAX_LOG_CHARS) + "\n...[truncated]...";
            }

            String header = String.format("[%s] [%s] [%s] %n", Instant.now().toString(), clientIp, level);
            String entry = header + logsStr + System.lineSeparator() + "-----" + System.lineSeparator();

            if (!frontendLoggingEnabled) {
                return ResponseEntity.status(503).body(Map.of("ok", false, "error", "frontend logging disabled"));
            }

            try {
                Files.writeString(FRONTEND_LOG, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ioe) {
                log.error("Falha ao escrever frontend.log: {}", ioe.getMessage());
                return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", ioe.getMessage()));
            }

            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            log.error("Erro ao processar request de frontend log", e);
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }
}
