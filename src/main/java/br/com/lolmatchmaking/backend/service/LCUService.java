package br.com.lolmatchmaking.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LCUService {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${app.lcu.port:0}")
    private int lcuPort;

    @Value("${app.lcu.password:}")
    private String lcuPassword;

    @Value("${app.lcu.protocol:https}")
    private String lcuProtocol;

    private String lcuBaseUrl;
    private HttpClient lcuClient;
    private boolean isConnected = false;
    private String currentSummonerId;
    private String currentGameId;
    private final Map<String, Object> lcuStatus = new ConcurrentHashMap<>();

    /**
     * Inicializa o serviço LCU
     */
    public void initialize() {
        log.info("🎮 Inicializando LCUService...");
        setupLCUClient();
        startLCUMonitoring();
        log.info("✅ LCUService inicializado");
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
     * Monitora status do LCU
     */
    @Scheduled(fixedRate = 10000) // A cada 10 segundos
    @Async
    public void startLCUMonitoring() {
        while (true) {
            try {
                checkLCUStatus();
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("LCU não disponível: {}", e.getMessage());
            }
        }
    }

    /**
     * Verifica status do LCU
     */
    public boolean checkLCUStatus() {
        try {
            if (lcuPort == 0) {
                // Tentar descobrir a porta automaticamente
                discoverLCUPort();
            }

            if (lcuPort > 0) {
                lcuBaseUrl = lcuProtocol + "://127.0.0.1:" + lcuPort;
                String statusUrl = lcuBaseUrl + "/lol-summoner/v1/current-summoner";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(statusUrl))
                        .header("Authorization",
                                "Basic " + Base64.getEncoder().encodeToString(("riot:" + lcuPassword).getBytes()))
                        .timeout(Duration.ofSeconds(5))
                        .build();

                HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode summonerData = objectMapper.readTree(response.body());
                    currentSummonerId = summonerData.get("summonerId").asText();
                    isConnected = true;

                    lcuStatus.put("connected", true);
                    lcuStatus.put("summonerId", currentSummonerId);
                    lcuStatus.put("port", lcuPort);

                    log.debug("✅ LCU conectado - Summoner ID: {}", currentSummonerId);
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("LCU não disponível: {}", e.getMessage());
        }

        isConnected = false;
        lcuStatus.put("connected", false);
        return false;
    }

    /**
     * Descobre a porta do LCU automaticamente
     */
    private void discoverLCUPort() {
        // Tentar portas comuns do LCU
        int[] commonPorts = { 2099, 2100, 2101, 2102, 2103, 2104, 2105, 2106, 2107, 2108, 2109, 2110 };

        for (int port : commonPorts) {
            try {
                String testUrl = lcuProtocol + "://127.0.0.1:" + port + "/lol-summoner/v1/current-summoner";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(testUrl))
                        .header("Authorization",
                                "Basic " + Base64.getEncoder().encodeToString(("riot:" + lcuPassword).getBytes()))
                        .timeout(Duration.ofSeconds(2))
                        .build();

                HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    lcuPort = port;
                    log.info("🔍 Porta LCU descoberta: {}", port);
                    return;
                }
            } catch (Exception e) {
                // Porta não disponível, continuar tentando
            }
        }
    }

    /**
     * Obtém dados do invocador atual
     */
    public CompletableFuture<JsonNode> getCurrentSummoner() {
        if (!isConnected) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = lcuBaseUrl + "/lol-summoner/v1/current-summoner";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization",
                                "Basic " + Base64.getEncoder().encodeToString(("riot:" + lcuPassword).getBytes()))
                        .build();

                HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return objectMapper.readTree(response.body());
                }
            } catch (Exception e) {
                log.error("❌ Erro ao obter dados do invocador", e);
            }
            return null;
        });
    }

    /**
     * Obtém histórico de partidas
     */
    public CompletableFuture<JsonNode> getMatchHistory() {
        if (!isConnected || currentSummonerId == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = lcuBaseUrl + "/lol-match-history/v1/matches/" + currentSummonerId;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization",
                                "Basic " + Base64.getEncoder().encodeToString(("riot:" + lcuPassword).getBytes()))
                        .build();

                HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return objectMapper.readTree(response.body());
                }
            } catch (Exception e) {
                log.error("❌ Erro ao obter histórico de partidas", e);
            }
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
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization",
                                "Basic " + Base64.getEncoder().encodeToString(("riot:" + lcuPassword).getBytes()))
                        .build();

                HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return objectMapper.readTree(response.body());
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
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization",
                                "Basic " + Base64.getEncoder().encodeToString(("riot:" + lcuPassword).getBytes()))
                        .build();

                HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return objectMapper.readTree(response.body());
                }
            } catch (Exception e) {
                log.error("❌ Erro ao obter dados da partida", e);
            }
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

                HttpRequest request = requestBuilder.build();
                HttpResponse<String> response = lcuClient.send(request, HttpResponse.BodyHandlers.ofString());

                return response.statusCode() >= 200 && response.statusCode() < 300;
            } catch (Exception e) {
                log.error("❌ Erro ao enviar comando LCU", e);
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
    public Map<String, Object> getLCUStatus() {
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
}