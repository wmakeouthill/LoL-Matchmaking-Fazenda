package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.service.LCUService;
import br.com.lolmatchmaking.backend.util.SummonerAuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.databind.JsonNode;

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
     * Obtém dados do invocador atual conectado no LCU (via registry por
     * summonerName)
     */
    @GetMapping("/current-summoner")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getCurrentSummoner(HttpServletRequest request) {
        // Extrair summonerName do header X-Summoner-Name
        String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
        log.info("🎮 [LCU] /current-summoner para summonerName='{}'", summonerName);

        // Primary attempt via LCUService with summonerName (routes via registry)
        return lcuService.getCurrentSummoner(summonerName)
                .thenCompose(summonerData -> {
                    if (summonerData != null) {
                        Map<String, Object> response = new HashMap<>();
                        response.put(SUCCESS, true);
                        response.put("summoner", summonerData);
                        // Also include nested shape expected by some frontend callers
                        response.put("data", Map.of("summoner", summonerData));
                        response.put(TIMESTAMP, System.currentTimeMillis());
                        log.debug("✅ [LCU] Summoner obtido via registry para '{}'", summonerName);
                        return CompletableFuture.completedFuture(ResponseEntity.ok(response));
                    }

                    // Fallback: try gateway RPC directly (when registry has no connection)
                    log.warn("⚠️ [LCU] Registry sem conexão para '{}', tentando fallback via gateway RPC",
                            summonerName);
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
                                    log.debug("✅ [LCU] Fallback gateway RPC OK para '{}'", summonerName);
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
                    log.error("❌ Erro ao obter dados do invocador atual para '{}'", summonerName, ex);
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

    /**
     * GET /api/lcu/match-history
     * Obtém histórico de partidas do LCU
     * ✅ MODIFICADO: Valida header X-Summoner-Name e roteia para LCU correto
     */
    @GetMapping("/match-history")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getMatchHistory(
            HttpServletRequest request) {
        try {
            // 🔒 Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("📜 [{}] Obtendo histórico de partidas via LCU", summonerName);

            return lcuService.getMatchHistory(summonerName)
                    .thenApply(matchHistory -> {
                        Map<String, Object> response = new HashMap<>();
                        if (matchHistory != null) {
                            response.put(SUCCESS, true);

                            // Extract games array from matchHistory.games.games structure
                            List<Object> games = new ArrayList<>();
                            if (matchHistory.has("games") && matchHistory.get("games").has("games")) {
                                JsonNode gamesNode = matchHistory.get("games").get("games");
                                if (gamesNode.isArray()) {
                                    gamesNode.forEach(game -> games.add(game));
                                }
                            }

                            // Ensure frontend finds 'matches' array (legacy name) and nested shape
                            response.put("matches", games);
                            response.put("matchHistory", matchHistory);
                            response.put("data", Map.of("matches", games, "matchHistory", matchHistory));
                            response.put(TIMESTAMP, System.currentTimeMillis());

                            log.info("✅ [{}] Histórico de partidas obtido: {} jogos", summonerName, games.size());
                            return ResponseEntity.ok(response);
                        } else {
                            log.warn("⚠️ [{}] Histórico não disponível", summonerName);
                            response.put(SUCCESS, false);
                            response.put(ERROR, "Histórico não disponível");
                            response.put(TIMESTAMP, System.currentTimeMillis());
                            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                        }
                    })
                    .exceptionally(ex -> {
                        log.error("❌ [{}] Erro ao obter histórico de partidas", summonerName, ex);
                        Map<String, Object> errorResponse = new HashMap<>();
                        errorResponse.put(SUCCESS, false);
                        errorResponse.put(ERROR, ex.getMessage());
                        errorResponse.put(TIMESTAMP, System.currentTimeMillis());
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                    });

        } catch (Exception e) {
            log.error("❌ Erro ao validar header para match-history", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put(SUCCESS, false);
            errorResponse.put(ERROR, e.getMessage());
            errorResponse.put(TIMESTAMP, System.currentTimeMillis());
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse));
        }
    }

    /**
     * GET /api/lcu/game-status
     * Obtém status do jogo atual
     * ✅ MODIFICADO: Valida header X-Summoner-Name e roteia para LCU correto
     */
    @GetMapping("/game-status")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getCurrentGameStatus(
            HttpServletRequest request) {
        try {
            // 🔒 Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("🎮 [{}] Obtendo status do jogo via LCU", summonerName);

            return lcuService.getCurrentGameStatus(summonerName)
                    .thenApply(gameStatus -> {
                        Map<String, Object> response = new HashMap<>();
                        if (gameStatus != null) {
                            response.put(SUCCESS, true);
                            response.put("gameStatus", gameStatus);
                            response.put("data", Map.of("gameStatus", gameStatus));
                            response.put(TIMESTAMP, System.currentTimeMillis());

                            log.info("✅ [{}] Status do jogo obtido", summonerName);
                            return ResponseEntity.ok(response);
                        } else {
                            log.warn("⚠️ [{}] Status do jogo não disponível", summonerName);
                            response.put(SUCCESS, false);
                            response.put(ERROR, "Status do jogo não disponível");
                            response.put(TIMESTAMP, System.currentTimeMillis());
                            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                        }
                    })
                    .exceptionally(ex -> {
                        log.error("❌ [{}] Erro ao obter status do jogo", summonerName, ex);
                        Map<String, Object> errorResponse = new HashMap<>();
                        errorResponse.put(SUCCESS, false);
                        errorResponse.put(ERROR, ex.getMessage());
                        errorResponse.put(TIMESTAMP, System.currentTimeMillis());
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                    });

        } catch (Exception e) {
            log.error("❌ Erro ao validar header para game-status", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put(SUCCESS, false);
            errorResponse.put(ERROR, e.getMessage());
            errorResponse.put(TIMESTAMP, System.currentTimeMillis());
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse));
        }
    }

    /**
     * GET /api/lcu/game-data
     * Obtém dados da partida atual
     * ✅ MODIFICADO: Valida header X-Summoner-Name e roteia para LCU correto
     */
    @GetMapping("/game-data")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getCurrentGameData(
            HttpServletRequest request) {
        try {
            // 🔒 Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("📊 [{}] Obtendo dados da partida via LCU", summonerName);

            return lcuService.getCurrentGameData(summonerName)
                    .thenApply(gameData -> {
                        Map<String, Object> response = new HashMap<>();
                        if (gameData != null) {
                            response.put(SUCCESS, true);
                            response.put("gameData", gameData);
                            response.put("data", Map.of("gameData", gameData));
                            response.put(TIMESTAMP, System.currentTimeMillis());

                            log.info("✅ [{}] Dados da partida obtidos", summonerName);
                            return ResponseEntity.ok(response);
                        } else {
                            log.warn("⚠️ [{}] Dados da partida não disponíveis", summonerName);
                            response.put(SUCCESS, false);
                            response.put(ERROR, "Dados da partida não disponíveis");
                            response.put(TIMESTAMP, System.currentTimeMillis());
                            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                        }
                    })
                    .exceptionally(ex -> {
                        log.error("❌ [{}] Erro ao obter dados da partida", summonerName, ex);
                        Map<String, Object> errorResponse = new HashMap<>();
                        errorResponse.put(SUCCESS, false);
                        errorResponse.put(ERROR, ex.getMessage());
                        errorResponse.put(TIMESTAMP, System.currentTimeMillis());
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                    });

        } catch (Exception e) {
            log.error("❌ Erro ao validar header para game-data", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put(SUCCESS, false);
            errorResponse.put(ERROR, e.getMessage());
            errorResponse.put(TIMESTAMP, System.currentTimeMillis());
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse));
        }
    }

    /**
     * POST /api/lcu/create-lobby
     * Cria um lobby personalizado via LCU
     * ⚠️ Sistema cria lobby em LCU específico (não precisa de header individual)
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
     * ✅ MODIFICADO: Valida header X-Summoner-Name e roteia para LCU correto
     */
    @PostMapping("/accept-match")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> acceptMatch(
            HttpServletRequest request) {
        try {
            // 🔒 Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("✅ [{}] Aceitando partida via LCU", summonerName);

            return lcuService.sendLCUCommand(summonerName, "/lol-matchmaking/v1/ready-check/accept", "POST", null)
                    .thenApply(success -> {
                        Map<String, Object> response = new HashMap<>();
                        if (Boolean.TRUE.equals(success)) {
                            response.put(SUCCESS, true);
                            response.put("message", "Partida aceita");
                            log.info("✅ [{}] Partida aceita com sucesso", summonerName);
                            return ResponseEntity.ok(response);
                        } else {
                            log.warn("⚠️ [{}] Falha ao aceitar partida", summonerName);
                            response.put(SUCCESS, false);
                            response.put(ERROR, "Falha ao aceitar partida");
                            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
                        }
                    })
                    .exceptionally(ex -> {
                        log.error("❌ [{}] Erro ao aceitar partida", summonerName, ex);
                        Map<String, Object> errorResponse = new HashMap<>();
                        errorResponse.put(SUCCESS, false);
                        errorResponse.put(ERROR, ex.getMessage());
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                    });

        } catch (Exception e) {
            log.error("❌ Erro ao validar header para accept-match", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put(SUCCESS, false);
            errorResponse.put(ERROR, e.getMessage());
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse));
        }
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

            // ✅ CORREÇÃO: Detectar se configuração vem do Electron
            // Se tem password (lockfile) e port > 0, provavelmente é do Electron
            boolean isFromElectron = !password.isEmpty() && port > 0 && "127.0.0.1".equals(host);

            boolean configured;
            if (isFromElectron) {
                log.info("🔧 Configuração LCU detectada como vinda do Electron - usando método sem validação");
                configured = lcuService.configureWithoutValidation(host, port, protocol, password);
            } else {
                log.info("🔧 Configuração LCU manual - usando método com validação");
                configured = lcuService.configure(host, port, protocol, password);
            }

            // Dev-only: if not configured but running in local/dev profile, accept and
            // persist configuration
            if (!configured) {
                String activeProfiles = System.getProperty("spring.profiles.active",
                        System.getenv("SPRING_PROFILES_ACTIVE"));
                if (activeProfiles != null && (activeProfiles.contains("local") || activeProfiles.contains("dev"))) {
                    log.warn(
                            "⚠️ Running in dev profile ({}). Accepting LCU configuration despite connectivity check failure.",
                            activeProfiles);
                    // Use configureWithoutValidation for dev profile fallback
                    configured = lcuService.configureWithoutValidation(host, port, protocol, password);
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
