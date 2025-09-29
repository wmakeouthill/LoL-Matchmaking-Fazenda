package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.service.LCUService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/lcu")
@RequiredArgsConstructor
public class LCUController {

    private final LCUService lcuService;

    // Constantes para evitar duplicação
    private static final String SUCCESS = "success";
    private static final String ERROR = "error";
    private static final String TIMESTAMP = "timestamp";

    /**
     * GET /api/lcu/status
     * Obtém status de conexão com o LCU
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getLCUStatus() {
        try {
            boolean isConnected = lcuService.isConnected();
            Map<String, Object> status = lcuService.getStatus();

            Map<String, Object> response = new HashMap<>();
            response.put("isConnected", isConnected);
            response.put("status", status);
            response.put(TIMESTAMP, System.currentTimeMillis());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ Erro ao obter status do LCU", e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("isConnected", false);
            errorResponse.put(ERROR, e.getMessage());
            errorResponse.put(TIMESTAMP, System.currentTimeMillis());

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
        }
    }

    /**
     * GET /api/lcu/current-summoner
     * Obtém dados do invocador atual conectado no LCU
     */
    @GetMapping("/current-summoner")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getCurrentSummoner() {
        // Primary attempt via LCUService (may use direct LCU or gateway RPC). If that
        // yields no data, try a direct gateway RPC as a last resort so the frontend
        // receives data when the Electron gateway is available.
        return lcuService.getCurrentSummoner()
                .thenCompose(summonerData -> {
                    if (summonerData != null) {
                        Map<String, Object> response = new HashMap<>();
                        response.put(SUCCESS, true);
                        response.put("summoner", summonerData);
                        // Also include nested shape expected by some frontend callers
                        response.put("data", Map.of("summoner", summonerData));
                        response.put(TIMESTAMP, System.currentTimeMillis());
                        return CompletableFuture.completedFuture(ResponseEntity.ok(response));
                    }

                    // Fallback: try gateway RPC directly
                    return CompletableFuture.supplyAsync(() -> {
                        Map<String, Object> response = new HashMap<>();
                        try {
                            br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService ws = org.springframework.web.context.ContextLoader
                                    .getCurrentWebApplicationContext()
                                    .getBean(br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService.class);
                            try {
                                com.fasterxml.jackson.databind.JsonNode r = ws.requestLcuFromAnyClient("GET",
                                        "/lol-summoner/v1/current-summoner", null, 5000);
                                if (r != null) {
                                    com.fasterxml.jackson.databind.JsonNode body = r.has("body") ? r.get("body") : r;
                                    response.put(SUCCESS, true);
                                    response.put("summoner", body);
                                    // Provide nested data shape as well
                                    response.put("data", Map.of("summoner", body));
                                    response.put(TIMESTAMP, System.currentTimeMillis());
                                    return ResponseEntity.ok(response);
                                }
                            } catch (Exception e) {
                                log.debug("Gateway direct RPC failed: {}", e.getMessage());
                            }
                        } catch (Exception e) {
                            log.debug("No gateway bean available: {}", e.getMessage());
                        }

                        response.put(SUCCESS, false);
                        response.put(ERROR, "LCU não conectado ou dados não disponíveis");
                        response.put(TIMESTAMP, System.currentTimeMillis());
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                    });
                })
                .exceptionally(ex -> {
                    log.error("❌ Erro ao obter dados do invocador atual", ex);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put(SUCCESS, false);
                    errorResponse.put(ERROR, ex.getMessage());
                    errorResponse.put(TIMESTAMP, System.currentTimeMillis());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                });
    }

    /**
     * DEBUG: Direct LCU connectivity check.
     * Returns statusCode, headers and body when performing a direct HTTP GET
     * against the LCU /lol-summoner/v1/current-summoner. Useful to debug
     * host/port/auth problems from the frontend developer tools.
     */
    @GetMapping("/debug-check")
    public ResponseEntity<Map<String, Object>> debugCheck() {
        try {
            Map<String, Object> resp = lcuService.debugDirectCurrentSummoner();
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("❌ Error in /api/lcu/debug-check", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/match-history")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getMatchHistory() {
        return lcuService.getMatchHistory()
                .thenApply(matchHistory -> {
                    Map<String, Object> response = new HashMap<>();
                    if (matchHistory != null) {
                        response.put(SUCCESS, true);
                        // Ensure frontend finds 'matches' array (legacy name) and nested shape
                        response.put("matches", matchHistory);
                        response.put("matchHistory", matchHistory);
                        response.put("data", Map.of("matches", matchHistory, "matchHistory", matchHistory));
                        response.put(TIMESTAMP, System.currentTimeMillis());
                        return ResponseEntity.ok(response);
                    } else {
                        response.put(SUCCESS, false);
                        response.put(ERROR, "Histórico não disponível");
                        response.put(TIMESTAMP, System.currentTimeMillis());
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                    }
                })
                .exceptionally(ex -> {
                    log.error("❌ Erro ao obter histórico de partidas", ex);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put(SUCCESS, false);
                    errorResponse.put(ERROR, ex.getMessage());
                    errorResponse.put(TIMESTAMP, System.currentTimeMillis());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                });
    }

    /**
     * GET /api/lcu/game-status
     * Obtém status do jogo atual
     */
    @GetMapping("/game-status")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getCurrentGameStatus() {
        return lcuService.getCurrentGameStatus()
                .thenApply(gameStatus -> {
                    Map<String, Object> response = new HashMap<>();
                    if (gameStatus != null) {
                        response.put(SUCCESS, true);
                        response.put("gameStatus", gameStatus);
                        response.put("data", Map.of("gameStatus", gameStatus));
                        response.put(TIMESTAMP, System.currentTimeMillis());
                        return ResponseEntity.ok(response);
                    } else {
                        response.put(SUCCESS, false);
                        response.put(ERROR, "Status do jogo não disponível");
                        response.put(TIMESTAMP, System.currentTimeMillis());
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                    }
                })
                .exceptionally(ex -> {
                    log.error("❌ Erro ao obter status do jogo", ex);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put(SUCCESS, false);
                    errorResponse.put(ERROR, ex.getMessage());
                    errorResponse.put(TIMESTAMP, System.currentTimeMillis());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                });
    }

    /**
     * GET /api/lcu/game-data
     * Obtém dados da partida atual
     */
    @GetMapping("/game-data")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getCurrentGameData() {
        return lcuService.getCurrentGameData()
                .thenApply(gameData -> {
                    Map<String, Object> response = new HashMap<>();
                    if (gameData != null) {
                        response.put(SUCCESS, true);
                        response.put("gameData", gameData);
                        response.put("data", Map.of("gameData", gameData));
                        response.put(TIMESTAMP, System.currentTimeMillis());
                        return ResponseEntity.ok(response);
                    } else {
                        response.put(SUCCESS, false);
                        response.put(ERROR, "Dados da partida não disponíveis");
                        response.put(TIMESTAMP, System.currentTimeMillis());
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                    }
                })
                .exceptionally(ex -> {
                    log.error("❌ Erro ao obter dados da partida", ex);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put(SUCCESS, false);
                    errorResponse.put(ERROR, ex.getMessage());
                    errorResponse.put(TIMESTAMP, System.currentTimeMillis());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                });
    }

    /**
     * POST /api/lcu/create-lobby
     * Cria um lobby personalizado via LCU
     */
    @PostMapping("/create-lobby")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> createLobby(
            @RequestBody Map<String, Object> request) {
        String gameMode = (String) request.getOrDefault("gameMode", "CLASSIC");

        return lcuService.sendLCUCommand("/lol-lobby/v2/lobby", "POST", Map.of(
                "customGameLobby", Map.of(
                        "configuration", Map.of(
                                "gameMode", gameMode,
                                "gameMutator", "",
                                "gameServerRegion", "",
                                "mapId", 11,
                                "mutators", Map.of("id", 1),
                                "teamSize", 5),
                        "lobbyName", "Custom Match",
                        "lobbyPassword", "")))
                .thenApply(success -> {
                    Map<String, Object> response = new HashMap<>();
                    if (Boolean.TRUE.equals(success)) {
                        response.put(SUCCESS, true);
                        response.put("message", "Lobby criado com sucesso");
                        return ResponseEntity.ok(response);
                    } else {
                        response.put(SUCCESS, false);
                        response.put(ERROR, "Falha ao criar lobby");
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
                    }
                }).exceptionally(ex -> {
                    log.error("❌ Erro ao criar lobby", ex);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put(SUCCESS, false);
                    errorResponse.put(ERROR, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                });
    }

    /**
     * POST /api/lcu/accept-match
     * Aceita uma partida encontrada via LCU
     */
    @PostMapping("/accept-match")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> acceptMatch() {
        return lcuService.sendLCUCommand("/lol-matchmaking/v1/ready-check/accept", "POST", null)
                .thenApply(success -> {
                    Map<String, Object> response = new HashMap<>();
                    if (Boolean.TRUE.equals(success)) {
                        response.put(SUCCESS, true);
                        response.put("message", "Partida aceita");
                        return ResponseEntity.ok(response);
                    } else {
                        response.put(SUCCESS, false);
                        response.put(ERROR, "Falha ao aceitar partida");
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
                    }
                })
                .exceptionally(ex -> {
                    log.error("❌ Erro ao aceitar partida", ex);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put(SUCCESS, false);
                    errorResponse.put(ERROR, ex.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                });
    }

    /**
     * POST /api/lcu/configure
     * Configura host/porta/protocolo/senha do LCU (suporte a Docker + Electron)
     */
    @PostMapping("/configure")
    public ResponseEntity<Map<String, Object>> configureLCU(@RequestBody Map<String, Object> body) {
        try {
            log.info("🔧 /api/lcu/configure called with body: {}", body);
            String host = (String) body.getOrDefault("host", "127.0.0.1");
            String protocol = (String) body.getOrDefault("protocol", "https");
            int port = Integer.parseInt(String.valueOf(body.getOrDefault("port", 0)));
            String password = (String) body.getOrDefault("password", "");

            boolean configured = lcuService.configure(host, port, protocol, password);

            // Dev-only: if not configured but running in local/dev profile, accept and
            // persist configuration
            if (!configured) {
                String activeProfiles = System.getProperty("spring.profiles.active",
                        System.getenv("SPRING_PROFILES_ACTIVE"));
                if (activeProfiles != null && (activeProfiles.contains("local") || activeProfiles.contains("dev"))) {
                    log.warn(
                            "⚠️ Running in dev profile ({}). Accepting LCU configuration despite connectivity check failure.",
                            activeProfiles);
                    // Persist the values in the service (best-effort)
                    lcuService.configure(host, port, protocol, password); // second call may still return false, but
                                                                          // will set values
                    configured = true;
                }
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", configured);
            resp.put("host", host);
            resp.put("port", port);
            resp.put("protocol", protocol);

            if (!configured) {
                Map<String, Object> statusDetails = lcuService.getStatus();
                log.warn("⚠️ /api/lcu/configure failed to configure LCU with host={} port={} protocol={}. status={}",
                        host, port,
                        protocol, statusDetails);
                resp.put("statusDetails", statusDetails);
                resp.put("success", false);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(resp);
            }
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("❌ Erro ao configurar LCU, body: {}", body, e);
            Map<String, Object> details = lcuService.getStatus();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage(), "statusDetails", details));
        }
    }
}

// ✅ NOVO: Controlador adicional para rotas sem /api (compatibilidade)
@Slf4j
@RestController
@RequestMapping("/lcu")
@RequiredArgsConstructor
class LCUCompatController {

    private final LCUService lcuService;

    /**
     * GET /lcu/status
     * Rota de compatibilidade para o frontend
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getLCUStatus() {
        try {
            boolean isConnected = lcuService.isConnected();
            Map<String, Object> status = lcuService.getStatus();

            Map<String, Object> response = new HashMap<>();
            response.put("isConnected", isConnected);
            response.put("status", status);
            response.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ Erro ao obter status do LCU", e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("isConnected", false);
            errorResponse.put("error", e.getMessage());
            errorResponse.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
        }
    }

    /**
     * POST /lcu/configure
     * Compat endpoint so preload/fallback URLs without /api also work
     */
    @PostMapping("/configure")
    public ResponseEntity<Map<String, Object>> configureLCUCompat(@RequestBody Map<String, Object> body) {
        try {
            log.info("🔧 /lcu/configure (compat) called with body: {}", body);
            String host = (String) body.getOrDefault("host", "127.0.0.1");
            String protocol = (String) body.getOrDefault("protocol", "https");
            int port = Integer.parseInt(String.valueOf(body.getOrDefault("port", 0)));
            String password = (String) body.getOrDefault("password", "");

            boolean configured = lcuService.configure(host, port, protocol, password);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", configured);
            resp.put("host", host);
            resp.put("port", port);
            resp.put("protocol", protocol);

            if (!configured) {
                // Dev-only fallback
                String activeProfiles = System.getProperty("spring.profiles.active",
                        System.getenv("SPRING_PROFILES_ACTIVE"));
                if (activeProfiles != null && (activeProfiles.contains("local") || activeProfiles.contains("dev"))) {
                    log.warn(
                            "⚠️ Running in dev profile ({}). Accepting LCU configuration despite connectivity check failure (compat endpoint).",
                            activeProfiles);
                    lcuService.configure(host, port, protocol, password);
                    configured = true;
                }

                Map<String, Object> statusDetails = lcuService.getStatus();
                log.warn(
                        "⚠️ /lcu/configure (compat) failed to configure LCU with host={} port={} protocol={}. status={}",
                        host, port,
                        protocol, statusDetails);
                resp.put("statusDetails", statusDetails);
                resp.put("success", false);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(resp);
            }
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("❌ Error in /lcu/configure (compat), body: {}", body, e);
            Map<String, Object> details = lcuService.getStatus();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage(), "statusDetails", details));
        }
    }

    /**
     * GET /lcu/current-summoner
     * Compatibility route (no /api prefix) that returns the same shape as
     * /api/lcu/current-summoner to support older preload code.
     */
    @GetMapping("/current-summoner")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getCurrentSummonerCompatSimple() {
        return lcuService.getCurrentSummoner()
                .thenApply(summonerData -> {
                    Map<String, Object> response = new HashMap<>();
                    if (summonerData != null) {
                        response.put("success", true);
                        response.put("summoner", summonerData);
                        response.put("data", Map.of("summoner", summonerData));
                        response.put("timestamp", System.currentTimeMillis());
                        return ResponseEntity.ok(response);
                    } else {
                        response.put("success", false);
                        response.put("error", "LCU não conectado ou dados não disponíveis");
                        response.put("timestamp", System.currentTimeMillis());
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                    }
                })
                .exceptionally(ex -> {
                    log.error("❌ Erro ao obter dados do invocador atual (compat)", ex);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("error", ex.getMessage());
                    errorResponse.put("timestamp", System.currentTimeMillis());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                });
    }
}
