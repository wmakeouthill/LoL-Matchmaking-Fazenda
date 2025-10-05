package br.com.lolmatchmaking.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import br.com.lolmatchmaking.backend.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LCUService {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    // Optional injection of websocket service to perform gateway RPCs from async
    // threads - using @Lazy to break circular dependency
    @Lazy
    private final br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService websocketService;

    // Propriedades do LCU obtidas via AppProperties
    private int lcuPort;
    private String lcuPassword;
    private String lcuProtocol;
    private String lcuHost;
    private boolean preferGatewayRPC;

    private String lcuBaseUrl;
    private HttpClient lcuClient;
    private boolean isConnected = false;
    private String currentSummonerId;
    private String currentGameId;
    private final Map<String, Object> lcuStatus = new ConcurrentHashMap<>();
    // Track last time a gateway (Electron) reported successful LCU activity
    private Instant lastGatewaySeen = null;
    // TTL (ms) to consider gateway-reported connection as valid before falling back
    private static final long GATEWAY_CONNECTION_TTL_MS = 60_000; // 60 seconds

    /**
     * Inicializa as propriedades do LCU a partir do AppProperties
     */
    @PostConstruct
    public void initProperties() {
        this.lcuPort = appProperties.getLcu().getPort();
        this.lcuPassword = appProperties.getLcu().getPassword();
        this.lcuProtocol = appProperties.getLcu().getProtocol();
        this.lcuHost = appProperties.getLcu().getHost();
        this.preferGatewayRPC = appProperties.getLcu().isPreferGateway();

        log.info("🔧 LCU Properties inicializadas: host={}, port={}, protocol={}, preferGateway={}",
                lcuHost, lcuPort, lcuProtocol, preferGatewayRPC);
    }

    /**
     * Inicializa o serviço LCU
     */
    public void initialize() {
        log.info("🎮 Inicializando LCUService...");
        setupLCUClient();
        discoverLCUFromLockfile(); // Tentar descobrir via lockfile primeiro
        startLCUMonitoring();
        log.info("✅ LCUService inicializado");
    }

    /**
     * Debug helper: perform a direct GET to /lol-summoner/v1/current-summoner and
     * return a map with statusCode, headers and body or exception details. This
     * is intended for debugging from the frontend/devtools.
     */
    public synchronized Map<String, Object> debugDirectCurrentSummoner() {
        Map<String, Object> out = new HashMap<>();
        try {
            if (this.lcuClient == null) {
                setupLCUClient();
            }
            if (lcuPort == 0) {
                discoverLCUPort();
            }

            String url = (lcuProtocol != null ? lcuProtocol : "https") + "://"
                    + (lcuHost != null ? lcuHost : "127.0.0.1") + ":" + lcuPort
                    + "/lol-summoner/v1/current-summoner";

            HttpRequest.Builder builder = createLcuRequestBuilder(url, null);
            builder.timeout(Duration.ofSeconds(6));
            HttpRequest request = builder.GET().build();

            HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

            out.put("url", url);
            out.put("statusCode", response.statusCode());
            try {
                out.put("headers", response.headers().map());
            } catch (Exception ignored) {
            }
            out.put("body", response.body());
            out.put("host", lcuHost);
            out.put("port", lcuPort);
            out.put("protocol", lcuProtocol);
            return out;
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            out.put("error", true);
            out.put("exception", sw.toString());
            out.put("message", e.getMessage());
            out.put("host", lcuHost);
            out.put("port", lcuPort);
            out.put("protocol", lcuProtocol);
            return out;
        }
    }

    /**
     * Configura o cliente HTTP para LCU
     */
    private void setupLCUClient() {
        try {
            // Configurar SSL para ignorar certificados (necessário para LCU)
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            }, new java.security.SecureRandom());

            lcuClient = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            log.info("🔧 Cliente LCU configurado");
        } catch (Exception e) {
            log.error("❌ Erro ao configurar cliente LCU", e);
        }
    }

    /**
     * Helper: cria um HttpRequest.Builder com Authorization e Host header ajustado.
     * Quando estamos acessando via host.docker.internal, muitas vezes o LCU exige
     * que o header Host seja 127.0.0.1:<port> — isso permite que a requisição
     * seja aceita mesmo quando resolvida para outro IP.
     */
    private HttpRequest.Builder createLcuRequestBuilder(String targetUrl, Integer explicitPort) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(targetUrl));
        // Authorization
        if (this.lcuPassword != null) {
            builder.header("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(("riot:" + this.lcuPassword).getBytes()));
        }

        // Host override to satisfy LCU which often expects Host: 127.0.0.1:<port>
        try {
            String hostHeader;
            int portToUse = (explicitPort != null && explicitPort > 0) ? explicitPort : this.lcuPort;
            if ("host.docker.internal".equalsIgnoreCase(this.lcuHost) && portToUse > 0) {
                hostHeader = "127.0.0.1:" + portToUse;
            } else if (this.lcuHost != null) {
                hostHeader = this.lcuHost + (portToUse > 0 ? ":" + portToUse : "");
            } else {
                hostHeader = "127.0.0.1" + (portToUse > 0 ? ":" + portToUse : "");
            }
            builder.header("Host", hostHeader);
            // Also set Origin when talking through host.docker.internal so the LCU accepts
            // the request
            if ("host.docker.internal".equalsIgnoreCase(this.lcuHost) && portToUse > 0) {
                try {
                    String origin = (this.lcuProtocol != null ? this.lcuProtocol : "https") + "://127.0.0.1:"
                            + portToUse;
                    builder.header("Origin", origin);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        return builder;
    }

    /**
     * Monitora status do LCU
     */
    @Scheduled(fixedRate = 10000) // A cada 10 segundos
    @Async
    public void startLCUMonitoring() {
        try {
            checkLCUStatus();
        } catch (Exception e) {
            log.debug("LCU não disponível durante monitoramento: {}", e.getMessage());
        }
    }

    /**
     * Configura conexão com LCU em tempo de execução.
     * Se host for "auto", tenta host.docker.internal e depois 127.0.0.1
     */
    public synchronized boolean configure(String host, int port, String protocol, String password) {
        try {
            // ✅ Garantir cliente inicializado
            if (this.lcuClient == null) {
                setupLCUClient();
            }

            String[] hostsToTry;
            if (host == null || host.isBlank() || "auto".equalsIgnoreCase(host)) {
                hostsToTry = new String[] { "host.docker.internal", "127.0.0.1" };
            } else {
                hostsToTry = new String[] { host };
            }

            String originalHost = this.lcuHost;
            int originalPort = this.lcuPort;
            String originalProtocol = this.lcuProtocol;
            String originalPassword = this.lcuPassword;

            for (String h : hostsToTry) {
                // If host.docker.internal, check if it resolves first to avoid delays
                if ("host.docker.internal".equalsIgnoreCase(h)) {
                    try {
                        java.net.InetAddress addr = java.net.InetAddress.getByName(h);
                        log.debug("host.docker.internal resolves to {}", addr.getHostAddress());
                    } catch (Exception ex) {
                        log.debug("host.docker.internal não resolvível: {} - pular tentativa", ex.getMessage());
                        continue; // skip trying this host if it doesn't resolve
                    }
                }
                this.lcuHost = h;
                if (port > 0)
                    this.lcuPort = port;
                if (protocol != null && !protocol.isBlank())
                    this.lcuProtocol = protocol;
                if (password != null)
                    this.lcuPassword = password;

                log.info("🔧 Configurando LCU manualmente: {}:{} ({})", this.lcuHost, this.lcuPort, this.lcuProtocol);
                boolean ok = this.checkLCUStatus();
                if (ok) {
                    log.info("✅ LCU configurado com sucesso em {}:{}", this.lcuHost, this.lcuPort);
                    return true;
                }
            }

            // Restore if failed
            this.lcuHost = originalHost;
            this.lcuPort = originalPort;
            this.lcuProtocol = originalProtocol;
            this.lcuPassword = originalPassword;
            return false;
        } catch (Exception e) {
            log.error("❌ Erro ao configurar LCU", e);
            return false;
        }
    }

    /**
     * Verifica status do LCU
     */
    public boolean checkLCUStatus() {
        try {
            // ✅ Garantir cliente inicializado
            if (this.lcuClient == null) {
                setupLCUClient();
            }

            if (lcuPort == 0) {
                // Tentar descobrir a porta automaticamente
                discoverLCUPort();
            }

            // If a gateway recently reported the LCU as connected, consider it connected
            // for a short TTL to avoid flip-flopping while direct HTTP checks fail
            if (lastGatewaySeen != null
                    && Instant.now().isBefore(lastGatewaySeen.plusMillis(GATEWAY_CONNECTION_TTL_MS))) {
                // Respect the gateway-reported connection
                isConnected = true;
                lcuStatus.put("connected", true);
                if (currentSummonerId != null)
                    lcuStatus.put("summonerId", currentSummonerId);
                lcuStatus.put("port", lcuPort);
                lcuStatus.put("host", lcuHost);
                log.debug("LCU considered connected via gateway TTL ({} ms left)",
                        GATEWAY_CONNECTION_TTL_MS - (System.currentTimeMillis() - lastGatewaySeen.toEpochMilli()));
                return true;
            }

            if (lcuPort > 0) {
                lcuBaseUrl = lcuProtocol + "://" + lcuHost + ":" + lcuPort;
                String statusUrl = lcuBaseUrl + "/lol-summoner/v1/current-summoner";

                HttpRequest.Builder builder = createLcuRequestBuilder(statusUrl, lcuPort);
                builder.timeout(Duration.ofSeconds(5));
                HttpRequest request = builder.GET().build();

                HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode summonerData = objectMapper.readTree(response.body());
                    currentSummonerId = summonerData.get("summonerId").asText();
                    isConnected = true;

                    lcuStatus.put("connected", true);
                    lcuStatus.put("summonerId", currentSummonerId);
                    lcuStatus.put("port", lcuPort);
                    lcuStatus.put("host", lcuHost);

                    log.debug("✅ LCU conectado - {}:{} Summoner ID: {}", lcuHost, lcuPort, currentSummonerId);
                    return true;
                } else {
                    // Log status, headers and body to help debug 403/401 responses coming from the
                    // LCU
                    try {
                        log.warn("LCU check returned non-200 status {} for {}. headers={} body={}",
                                response.statusCode(), statusUrl, response.headers().map(), response.body());
                    } catch (Exception ex) {
                        log.warn("LCU check returned non-200 status {} for {} (failed to read body/headers)",
                                response.statusCode(), statusUrl, ex);
                    }
                }
            }
        } catch (Exception e) {
            // Log full stack for diagnostics
            log.warn("LCU not available during status check", e);
        }

        isConnected = false;
        lcuStatus.put("connected", false);
        lcuStatus.put("host", lcuHost);
        return false;
    }

    /**
     * Marca o LCU como conectado com informações vindas do gateway (Electron).
     * Usado quando o gateway proxy responde com sucesso para uma requisição LCU.
     */
    public synchronized void markConnectedFromGateway(String host, int port, String protocol, String password,
            String summonerId) {
        try {
            log.info("markConnectedFromGateway called with host={} port={} protocol={} summonerId={}", host, port,
                    protocol, summonerId);
            if (host != null && port > 0) {
                this.lcuHost = host;
                this.lcuPort = port;
            }
            if (protocol != null)
                this.lcuProtocol = protocol;
            if (password != null)
                this.lcuPassword = password;
            if (summonerId != null)
                this.currentSummonerId = summonerId;

            this.lcuBaseUrl = this.lcuProtocol + "://" + this.lcuHost + ":" + this.lcuPort;
            this.isConnected = true;
            // Record when a gateway last reported successful activity so checkLCUStatus
            // can honor the gateway state for a short TTL
            this.lastGatewaySeen = Instant.now();
            lcuStatus.put("connected", true);
            if (this.currentSummonerId != null)
                lcuStatus.put("summonerId", this.currentSummonerId);
            lcuStatus.put("port", this.lcuPort);
            lcuStatus.put("host", this.lcuHost);
            log.info("LCU marcado como conectado via gateway: {}:{} currentSummonerId={}", this.lcuHost, this.lcuPort,
                    this.currentSummonerId);
        } catch (Exception e) {
            log.error("Erro ao marcar LCU conectado via gateway", e);
        }
    }

    /**
     * Descobre a porta do LCU automaticamente
     */
    private void discoverLCUPort() {
        // Tentar portas comuns do LCU
        int[] commonPorts = { 2099, 2100, 2101, 2102, 2103, 2104, 2105, 2106, 2107, 2108, 2109, 2110 };

        for (int port : commonPorts) {
            try {
                String testUrl = lcuProtocol + "://" + lcuHost + ":" + port + "/lol-summoner/v1/current-summoner";
                HttpRequest.Builder testBuilder = createLcuRequestBuilder(testUrl, port);
                testBuilder.timeout(Duration.ofSeconds(2));
                HttpRequest testRequest = testBuilder.GET().build();

                HttpResponse<String> testResponse = lcuClient.send(testRequest, HttpResponse.BodyHandlers.ofString());

                if (testResponse.statusCode() == 200) {
                    lcuPort = port;
                    log.info("🔍 Porta LCU descoberta em {}: {}", lcuHost, port);
                    return;
                } else {
                    log.debug("Port test {} returned {} headers={}", testUrl, testResponse.statusCode(),
                            testResponse.headers().map());
                    log.trace("LCU response body: {}", testResponse.body());
                }
            } catch (Exception e) {
                log.debug("Port {} probe failed: {}", port, e.getMessage());
            }
        }
    }

    /**
     * Obtém dados do invocador atual
     * PRIORIDADE: Gateway RPC primeiro (para ambiente containerizado), HTTP direto
     * como fallback
     */
    public CompletableFuture<JsonNode> getCurrentSummoner() {
        return CompletableFuture.supplyAsync(() -> {
            // PRIORIDADE 1: Gateway RPC (principal para ambiente containerizado/Electron)
            try {
                log.debug("Attempting gateway RPC for /lol-summoner/v1/current-summoner");
                if (websocketService == null) {
                    log.warn("WebsocketService not available for gateway RPC (null)");
                } else {
                    JsonNode r = websocketService.requestLcuFromAnyClient("GET", "/lol-summoner/v1/current-summoner",
                            null,
                            5000);
                    if (r != null) {
                        log.info("Gateway RPC succeeded for current-summoner");
                        if (r.has("body"))
                            return r.get("body");
                        return r;
                    }
                    log.debug("Gateway RPC returned no result for current-summoner");
                }
            } catch (Exception e) {
                log.debug("Gateway RPC failed for current-summoner: {}", e.getMessage());
            }

            // PRIORIDADE 2: HTTP direto (fallback para ambiente local)
            if (isConnected) {
                try {
                    String url = lcuBaseUrl + "/lol-summoner/v1/current-summoner";
                    HttpRequest.Builder builder = createLcuRequestBuilder(url, null);
                    builder.timeout(Duration.ofSeconds(5));
                    HttpRequest request = builder.GET().build();

                    HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        log.info("LCU direct HTTP succeeded for current-summoner");
                        return objectMapper.readTree(response.body());
                    } else {
                        log.warn("LCU direct current-summoner returned non-200 {} for {}. headers={}",
                                response.statusCode(), url, response.headers().map());
                        log.debug("LCU response body: {}", response.body());
                    }
                } catch (Exception e) {
                    log.warn("LCU direct current-summoner failed: {}", e.getMessage());
                }
            }

            log.warn("Both gateway RPC and direct HTTP failed for current-summoner");
            return null;
        });
    }

    /**
     * Obtém histórico de partidas
     * PRIORIDADE: Gateway RPC primeiro (para ambiente containerizado), HTTP direto
     * como fallback
     */
    public CompletableFuture<JsonNode> getMatchHistory() {
        return CompletableFuture.supplyAsync(() -> {
            if (currentSummonerId == null) {
                log.debug("No currentSummonerId available for match-history");
                return null;
            }
            // Try several known match-history endpoints because different client versions
            // expose history under different paths. We'll attempt them in order and
            // return the first successful result.
            List<String> endpoints = Arrays.asList(
                    "/lol-match-history/v1/products/lol/current-summoner/matches?begIndex=0&endIndex=10",
                    "/lol-match-history/v1/products/lol/current-summoner/matches",
                    "/lol-match-history/v1/matches/current-summoner");

            // PRIORIDADE 1: Gateway RPC (principal para ambiente containerizado/Electron)
            try {
                if (websocketService != null) {
                    for (String ep : endpoints) {
                        try {
                            log.debug("LCU gateway RPC match-history trying {}", ep);
                            JsonNode r = websocketService.requestLcuFromAnyClient("GET", ep, null, 8000);
                            if (r != null) {
                                log.info("LCU gateway RPC match-history succeeded for {}", ep);
                                if (r.has("body"))
                                    return r.get("body");
                                return r;
                            }
                        } catch (Exception e) {
                            log.debug("Gateway RPC attempt for {} failed: {}", ep, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Gateway RPC match-history failed: {}", e.getMessage());
            }

            // PRIORIDADE 2: HTTP direto (fallback para ambiente local)
            if (isConnected) {
                for (String ep : endpoints) {
                    try {
                        String url = lcuBaseUrl + ep;
                        HttpRequest.Builder builder = createLcuRequestBuilder(url, null);
                        builder.timeout(Duration.ofSeconds(5));
                        HttpRequest request = builder.GET().build();

                        log.debug("LCU direct match-history trying {}", url);
                        HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                        if (response.statusCode() == 200) {
                            log.info("LCU direct match-history succeeded for {}", url);
                            return objectMapper.readTree(response.body());
                        } else {
                            log.warn("LCU direct match-history returned non-200 {} for {}. headers={}",
                                    response.statusCode(), url, response.headers().map());
                            log.debug("LCU response body for {}: {}", url, response.body());
                        }
                    } catch (Exception e) {
                        log.warn("LCU direct match-history attempt to {} failed: {}", ep, e.getMessage());
                    }
                }
            }

            log.warn("Both gateway RPC and direct HTTP failed for match-history");
            return null;
        });
    }

    /**
     * Busca detalhes completos de uma partida específica pelo gameId
     * Tenta vários endpoints conhecidos do LCU que retornam dados completos de
     * todos os jogadores
     */
    public CompletableFuture<JsonNode> getMatchDetails(Long gameId) {
        return CompletableFuture.supplyAsync(() -> {
            if (gameId == null) {
                log.warn("gameId is null, cannot fetch match details");
                return null;
            }

            List<String> endpoints = Arrays.asList(
                    "/lol-match-history/v1/games/" + gameId,
                    "/lol-match-history/v1/game-timelines/" + gameId,
                    "/lol-match-history/v3/matchlist/games/" + gameId);

            // PRIORIDADE 1: Gateway RPC
            try {
                if (websocketService != null) {
                    for (String ep : endpoints) {
                        try {
                            log.debug("LCU gateway RPC match-details trying {} for gameId={}", ep, gameId);
                            JsonNode r = websocketService.requestLcuFromAnyClient("GET", ep, null, 8000);
                            if (r != null) {
                                log.info("✅ LCU gateway RPC match-details succeeded for {}", ep);
                                if (r.has("body"))
                                    return r.get("body");
                                return r;
                            }
                        } catch (Exception e) {
                            log.debug("Gateway RPC attempt for {} failed: {}", ep, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Gateway RPC match-details failed: {}", e.getMessage());
            }

            // PRIORIDADE 2: HTTP direto (fallback)
            if (isConnected) {
                for (String ep : endpoints) {
                    try {
                        String url = lcuBaseUrl + ep;
                        HttpRequest.Builder builder = createLcuRequestBuilder(url, null);
                        builder.timeout(Duration.ofSeconds(5));
                        HttpRequest request = builder.GET().build();

                        log.debug("LCU direct match-details trying {}", url);
                        HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                        if (response.statusCode() == 200) {
                            log.info("✅ LCU direct match-details succeeded for {}", url);
                            return objectMapper.readTree(response.body());
                        }
                    } catch (Exception e) {
                        log.debug("LCU direct match-details attempt to {} failed: {}", ep, e.getMessage());
                    }
                }
            }

            log.warn("⚠️ Não foi possível buscar detalhes da partida {} via LCU", gameId);
            return null;
        });
    }

    /**
     * Obtém dados de ranked do jogador atual
     */
    public CompletableFuture<JsonNode> getCurrentSummonerRanked() {
        return CompletableFuture.supplyAsync(() -> {
            if (currentSummonerId == null) {
                log.debug("No currentSummonerId available for ranked stats");
                return null;
            }

            List<String> endpoints = Arrays.asList(
                    "/lol-ranked/v1/current-ranked-stats",
                    "/lol-ranked/v1/current-summoner",
                    "/lol-ranked/v1/ranked-stats/" + currentSummonerId);

            // PRIORIDADE 1: Gateway RPC
            try {
                if (websocketService != null) {
                    for (String ep : endpoints) {
                        try {
                            log.info("🔍 LCU gateway RPC ranked-stats trying {}", ep);
                            JsonNode r = websocketService.requestLcuFromAnyClient("GET", ep, null, 5000);
                            if (r != null) {
                                log.info("✅ LCU gateway RPC ranked-stats succeeded for {}: {}", ep, r);
                                if (r.has("body"))
                                    return r.get("body");
                                return r;
                            } else {
                                log.warn("❌ LCU gateway RPC ranked-stats returned null for {}", ep);
                            }
                        } catch (Exception e) {
                            log.warn("❌ Gateway RPC attempt for {} failed: {}", ep, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Gateway RPC ranked-stats failed: {}", e.getMessage());
            }

            // PRIORIDADE 2: HTTP direto
            if (isConnected) {
                for (String ep : endpoints) {
                    try {
                        String url = lcuBaseUrl + ep;
                        HttpRequest.Builder builder = createLcuRequestBuilder(url, null);
                        builder.timeout(Duration.ofSeconds(5));
                        HttpRequest request = builder.GET().build();

                        log.debug("LCU direct ranked-stats trying {}", url);
                        HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                        if (response.statusCode() == 200) {
                            log.info("LCU direct ranked-stats succeeded for {}", url);
                            return objectMapper.readTree(response.body());
                        } else {
                            log.warn("LCU direct ranked-stats returned non-200 {} for {}. headers={}",
                                    response.statusCode(), url, response.headers().map());
                        }
                    } catch (Exception e) {
                        log.warn("LCU direct ranked-stats attempt to {} failed: {}", ep, e.getMessage());
                    }
                }
            }

            log.warn("Both gateway RPC and direct HTTP failed for ranked-stats");
            return null;
        });
    }

    /**
     * Obtém status do jogo atual
     */
    public CompletableFuture<JsonNode> getCurrentGameStatus() {
        if (!isConnected) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = lcuBaseUrl + "/lol-gameflow/v1/gameflow-phase";
                HttpRequest.Builder builder = createLcuRequestBuilder(url, null);
                builder.timeout(Duration.ofSeconds(5));
                HttpRequest request = builder.GET().build();

                HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return objectMapper.readTree(response.body());
                } else {
                    log.warn("LCU getCurrentGameData unexpected status {} for {}", response.statusCode(), url);
                    log.debug("LCU response body: {}", response.body());
                }
            } catch (Exception e) {
                log.error("❌ Erro ao obter status do jogo", e);
            }
            return null;
        });
    }

    /**
     * Obtém dados da partida atual
     */
    public CompletableFuture<JsonNode> getCurrentGameData() {
        if (!isConnected) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = lcuBaseUrl + "/lol-gameflow/v1/session";
                HttpRequest.Builder builder = createLcuRequestBuilder(url, null);
                builder.timeout(Duration.ofSeconds(5));
                HttpRequest request = builder.GET().build();

                HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return objectMapper.readTree(response.body());
                } else {
                    log.warn("LCU getCurrentGameData unexpected status {} for {}. headers={}", response.statusCode(),
                            url, response.headers().map());
                    log.debug("LCU response body: {}", response.body());
                }
            } catch (Exception e) {
                log.warn("❌ Erro ao obter dados da partida", e);
            }
            return null;
        });
    }

    /**
     * Obtém estatísticas ranked locais via LCU
     * (/lol-ranked/v1/current-ranked-stats)
     * PRIORIDADE: Gateway RPC primeiro (para ambiente containerizado), HTTP direto
     * como fallback
     */
    public CompletableFuture<JsonNode> getRankedStats() {
        return CompletableFuture.supplyAsync(() -> {
            String ep = "/lol-ranked/v1/current-ranked-stats";

            // PRIORIDADE 1: Gateway RPC (principal para ambiente containerizado/Electron)
            try {
                br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService ws = org.springframework.web.context.ContextLoader
                        .getCurrentWebApplicationContext()
                        .getBean(br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService.class);
                JsonNode r = ws.requestLcuFromAnyClient("GET", ep, null, 4000);
                if (r != null) {
                    log.info("LCU gateway RPC ranked-stats succeeded");
                    if (r.has("body"))
                        return r.get("body");
                    return r;
                }
            } catch (Exception e) {
                log.debug("Gateway RPC ranked-stats failed: {}", e.getMessage());
            }

            // PRIORIDADE 2: HTTP direto (fallback para ambiente local)
            if (isConnected) {
                try {
                    String url = lcuBaseUrl + ep;
                    HttpRequest.Builder builder = createLcuRequestBuilder(url, null);
                    builder.timeout(Duration.ofSeconds(5));
                    HttpRequest request = builder.GET().build();

                    log.debug("LCU direct ranked-stats trying {}", url);
                    HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        log.info("LCU direct ranked-stats succeeded for {}", url);
                        return objectMapper.readTree(response.body());
                    } else {
                        log.warn("LCU direct ranked-stats returned non-200 {} for {}", response.statusCode(), url);
                        log.debug("LCU response body for ranked-stats {}: {}", url, response.body());
                    }
                } catch (Exception e) {
                    log.debug("LCU direct ranked-stats failed: {}", e.getMessage());
                }
            }

            log.warn("Both gateway RPC and direct HTTP failed for ranked-stats");
            return null;
        });
    }

    /**
     * Envia comando para o LCU
     */
    public CompletableFuture<Boolean> sendLCUCommand(String endpoint, String method, Object data) {
        if (!isConnected) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = lcuBaseUrl + endpoint;
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization",
                                "Basic " + Base64.getEncoder().encodeToString(("riot:" + lcuPassword).getBytes()))
                        .header("Content-Type", "application/json");

                if ("POST".equals(method) && data != null) {
                    String jsonData = objectMapper.writeValueAsString(data);
                    requestBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonData));
                } else if ("PUT".equals(method) && data != null) {
                    String jsonData = objectMapper.writeValueAsString(data);
                    requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(jsonData));
                } else if ("DELETE".equals(method)) {
                    requestBuilder.DELETE();
                } else {
                    requestBuilder.GET();
                }

                // use createLcuRequestBuilder to ensure Host header is correct when going
                // through host.docker.internal
                HttpRequest.Builder builder = createLcuRequestBuilder(url, null);
                // merge method and body
                if ("POST".equals(method) && data != null) {
                    String jsonData = objectMapper.writeValueAsString(data);
                    builder.POST(HttpRequest.BodyPublishers.ofString(jsonData));
                } else if ("PUT".equals(method) && data != null) {
                    String jsonData = objectMapper.writeValueAsString(data);
                    builder.PUT(HttpRequest.BodyPublishers.ofString(jsonData));
                } else if ("DELETE".equals(method)) {
                    builder.DELETE();
                } else {
                    builder.GET();
                }

                HttpRequest request = builder.timeout(Duration.ofSeconds(6)).build();
                HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return true;
                } else {
                    log.warn("LCU sendLCUCommand unexpected status {} for {}. headers={}", response.statusCode(), url,
                            response.headers().map());
                    log.debug("LCU response body: {}", response.body());
                    return false;
                }
            } catch (Exception e) {
                log.warn("❌ Erro ao enviar comando LCU", e);
                return false;
            }
        });
    }

    /**
     * Verifica se o LCU está conectado
     */
    public boolean isConnected() {
        return isConnected;
    }

    /**
     * Obtém status do LCU
     */
    public Map<String, Object> getStatus() {
        return new HashMap<>(lcuStatus);
    }

    /**
     * Obtém ID do invocador atual
     */
    public String getCurrentSummonerId() {
        return currentSummonerId;
    }

    /**
     * Obtém ID do jogo atual
     */
    public String getCurrentGameId() {
        return currentGameId;
    }

    /**
     * Descobre informações do LCU via lockfile
     */
    private void discoverLCUFromLockfile() {
        try {
            // Caminhos comuns do lockfile do League of Legends
            String[] possiblePaths = {
                    System.getProperty("user.home") + "/AppData/Local/Riot Games/League of Legends/lockfile",
                    "C:/Riot Games/League of Legends/lockfile",
                    System.getenv("LOCALAPPDATA") + "/Riot Games/League of Legends/lockfile"
            };

            for (String path : possiblePaths) {
                java.io.File lockfile = new java.io.File(path);
                if (lockfile.exists()) {
                    try {
                        String content = java.nio.file.Files.readString(lockfile.toPath());
                        String[] parts = content.split(":");
                        if (parts.length >= 5) {
                            lcuPort = Integer.parseInt(parts[2]);
                            lcuPassword = parts[3];
                            lcuProtocol = parts[4];

                            log.info("🔍 LCU descoberto via lockfile - Porta: {}, Protocolo: {}", lcuPort, lcuProtocol);
                            return;
                        }
                    } catch (Exception e) {
                        log.debug("Erro ao ler lockfile {}: {}", path, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Lockfile do LCU não encontrado: {}", e.getMessage());
        }
    }
}
