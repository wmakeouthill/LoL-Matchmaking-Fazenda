package br.com.lolmatchmaking.backend.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/lcu")
public class LCUDiagController {

    /**
     * POST /api/lcu/diagnose
     * Body: { host?:string, port:int, protocol?:string, password:string }
     * This endpoint performs direct HTTPS requests to the LCU endpoints from the
     * backend
     * (container) to help debug connectivity/auth problems. It will try the
     * provided host
     * and also host.docker.internal and 127.0.0.1 when host is 'auto'.
     */
    @PostMapping("/diagnose")
    public ResponseEntity<Map<String, Object>> diagnose(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new HashMap<>();
        try {
            String hostParam = String.valueOf(body.getOrDefault("host", "auto"));
            int port = Integer.parseInt(String.valueOf(body.getOrDefault("port", 0)));
            String protocol = String.valueOf(body.getOrDefault("protocol", "https"));
            String password = String.valueOf(body.getOrDefault("password", ""));

            if (port <= 0 || password.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "missing port or password"));
            }

            String[] hostsToTry;
            if (hostParam == null || hostParam.isBlank() || "auto".equalsIgnoreCase(hostParam)) {
                hostsToTry = new String[] { "host.docker.internal", "127.0.0.1" };
            } else {
                hostsToTry = new String[] { hostParam };
            }

            Map<String, Object> results = new HashMap<>();

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            for (String h : hostsToTry) {
                String base = protocol + "://" + h + ":" + port;
                String url = base + "/lol-summoner/v1/current-summoner";
                Map<String, Object> attempt = new HashMap<>();
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(6))
                            .header("Authorization",
                                    "Basic " + java.util.Base64.getEncoder()
                                            .encodeToString(("riot:" + password).getBytes()))
                            .GET()
                            .build();

                    HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
                    attempt.put("statusCode", r.statusCode());
                    attempt.put("body", r.body());
                } catch (Exception e) {
                    attempt.put("exception", e.getClass().getName());
                    attempt.put("message", e.getMessage());
                }
                results.put(h, attempt);
            }

            resp.put("results", results);
            resp.put("portsTested", port);
            return ResponseEntity.ok(resp);
        } catch (Exception ex) {
            log.error("Error in LCU diagnose", ex);
            return ResponseEntity.status(500).body(Map.of("error", ex.getMessage()));
        }
    }
}
