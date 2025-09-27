package br.com.lolmatchmaking.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;

@RestController
@RequestMapping("/internal")
public class LcuProxyController {

    private final MatchmakingWebSocketService wsService;

    public LcuProxyController(MatchmakingWebSocketService wsService) {
        this.wsService = wsService;
    }

    // POST /internal/lcu-proxy
    // body: { sessionId: string, method: 'GET', path: '/lol-summoner/v1/current-summoner', body?: {...} }
    @PostMapping("/lcu-proxy")
    public ResponseEntity<Object> proxy(@RequestBody JsonNode req) {
        String sessionId = req.has("sessionId") ? req.get("sessionId").asText(null) : null;
        String method = req.has("method") ? req.get("method").asText("GET") : "GET";
        String path = req.has("path") ? req.get("path").asText("/") : "/";
        JsonNode body = req.has("body") ? req.get("body") : null;
        if (sessionId == null) return ResponseEntity.badRequest().body("sessionId required");
        try {
            JsonNode resp = wsService.requestLcu(sessionId, method, path, body, 5000);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }
}

