package br.com.lolmatchmaking.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry para gerenciar múltiplas conexões LCU simultâneas.
 * Cada jogador (identificado por summonerName) pode ter sua própria conexão
 * LCU.
 * 
 * Arquitetura:
 * - Electron 1 (Jogador A) → LCU A → Registrado como "summonerA"
 * - Electron 2 (Jogador B) → LCU B → Registrado como "summonerB"
 * 
 * Quando o backend precisa fazer uma requisição LCU, ele:
 * 1. Extrai summonerName do header X-Summoner-Name
 * 2. Busca a conexão LCU registrada para esse summonerName
 * 3. Usa essa conexão para fazer a requisição
 */
@Slf4j
@Service
public class LCUConnectionRegistry {

    /**
     * Estrutura que armazena informações de uma conexão LCU
     */
    public static class LCUConnectionInfo {
        private final String summonerName;
        private final String sessionId;
        private final String host;
        private final int port;
        private final String authToken;
        private final long registeredAt;
        private long lastActivityAt;

        public LCUConnectionInfo(String summonerName, String sessionId, String host, int port, String authToken) {
            this.summonerName = summonerName;
            this.sessionId = sessionId;
            this.host = host;
            this.port = port;
            this.authToken = authToken;
            this.registeredAt = System.currentTimeMillis();
            this.lastActivityAt = System.currentTimeMillis();
        }

        public String getSummonerName() {
            return summonerName;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getAuthToken() {
            return authToken;
        }

        public long getRegisteredAt() {
            return registeredAt;
        }

        public long getLastActivityAt() {
            return lastActivityAt;
        }

        public void updateActivity() {
            this.lastActivityAt = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("LCUConnection[summoner=%s, session=%s, host=%s:%d, registered=%d]",
                    summonerName, sessionId, host, port, registeredAt);
        }
    }

    // Mapeia summonerName → LCUConnectionInfo
    private final Map<String, LCUConnectionInfo> connectionsBySummoner = new ConcurrentHashMap<>();

    // Mapeia sessionId → summonerName (para lookup reverso)
    private final Map<String, String> summonerBySession = new ConcurrentHashMap<>();

    /**
     * Registra uma nova conexão LCU para um jogador
     * 
     * @param summonerName Nome do invocador (normalizado em lowercase)
     * @param sessionId    ID da sessão WebSocket do Electron
     * @param host         Host do LCU (geralmente "127.0.0.1")
     * @param port         Porta do LCU
     * @param authToken    Token de autenticação do LCU
     */
    public void registerConnection(String summonerName, String sessionId, String host, int port, String authToken) {
        if (summonerName == null || summonerName.trim().isEmpty()) {
            log.warn("⚠️ [LCURegistry] Tentativa de registrar conexão sem summonerName");
            return;
        }

        String normalizedName = summonerName.toLowerCase().trim();
        LCUConnectionInfo connection = new LCUConnectionInfo(normalizedName, sessionId, host, port, authToken);

        // Remover conexão antiga se existir
        LCUConnectionInfo oldConnection = connectionsBySummoner.put(normalizedName, connection);
        summonerBySession.put(sessionId, normalizedName);

        if (oldConnection != null) {
            log.info("🔄 [LCURegistry] Substituindo conexão antiga de '{}' (session: {} → {})",
                    normalizedName, oldConnection.getSessionId(), sessionId);
        } else {
            log.info("✅ [LCURegistry] Nova conexão LCU registrada: '{}' (session: {}, {}:{})",
                    normalizedName, sessionId, host, port);
        }

        log.info("📊 [LCURegistry] Total de conexões ativas: {}", connectionsBySummoner.size());
    }

    /**
     * Busca a conexão LCU de um jogador específico
     * 
     * @param summonerName Nome do invocador
     * @return Optional com a conexão se encontrada
     */
    public Optional<LCUConnectionInfo> getConnection(String summonerName) {
        if (summonerName == null || summonerName.trim().isEmpty()) {
            return Optional.empty();
        }

        String normalizedName = summonerName.toLowerCase().trim();
        LCUConnectionInfo connection = connectionsBySummoner.get(normalizedName);

        if (connection != null) {
            connection.updateActivity();
            log.debug("🔍 [LCURegistry] Conexão LCU encontrada para '{}'", normalizedName);
        } else {
            log.warn("⚠️ [LCURegistry] Nenhuma conexão LCU encontrada para '{}'", normalizedName);
            log.warn("📋 [LCURegistry] Conexões disponíveis: {}", connectionsBySummoner.keySet());
        }

        return Optional.ofNullable(connection);
    }

    /**
     * Remove a conexão LCU de um jogador
     * 
     * @param summonerName Nome do invocador
     */
    public void unregisterConnection(String summonerName) {
        if (summonerName == null || summonerName.trim().isEmpty()) {
            return;
        }

        String normalizedName = summonerName.toLowerCase().trim();
        LCUConnectionInfo removed = connectionsBySummoner.remove(normalizedName);

        if (removed != null) {
            summonerBySession.remove(removed.getSessionId());
            log.info("🗑️ [LCURegistry] Conexão LCU removida: '{}' (session: {})",
                    normalizedName, removed.getSessionId());
            log.info("📊 [LCURegistry] Total de conexões ativas: {}", connectionsBySummoner.size());
        }
    }

    /**
     * Remove conexão por sessionId (quando WebSocket desconecta)
     * 
     * @param sessionId ID da sessão WebSocket
     */
    public void unregisterBySession(String sessionId) {
        String summonerName = summonerBySession.get(sessionId);
        if (summonerName != null) {
            unregisterConnection(summonerName);
        }
    }

    /**
     * Verifica se existe uma conexão LCU para um jogador
     * 
     * @param summonerName Nome do invocador
     * @return true se existe conexão ativa
     */
    public boolean hasConnection(String summonerName) {
        if (summonerName == null || summonerName.trim().isEmpty()) {
            return false;
        }
        return connectionsBySummoner.containsKey(summonerName.toLowerCase().trim());
    }

    /**
     * Retorna o número total de conexões ativas
     */
    public int getConnectionCount() {
        return connectionsBySummoner.size();
    }

    /**
     * Lista todas as conexões ativas (para debug/admin)
     */
    public Map<String, LCUConnectionInfo> getAllConnections() {
        return Map.copyOf(connectionsBySummoner);
    }

    /**
     * Remove todas as conexões (para testes/reset)
     */
    public void clearAll() {
        int count = connectionsBySummoner.size();
        connectionsBySummoner.clear();
        summonerBySession.clear();
        log.warn("🗑️ [LCURegistry] Todas as {} conexões foram removidas", count);
    }
}
