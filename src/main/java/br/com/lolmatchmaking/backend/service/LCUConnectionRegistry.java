package br.com.lolmatchmaking.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * 
 * ⚡ AGORA USA REDIS para performance e resiliência:
 * - Conexões persistem mesmo com reinício do backend
 * - Lookup instantâneo (O(1))
 * - TTL automático (2 horas)
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class LCUConnectionRegistry {

    // ⚡ NOVO: Redis para persistence e performance
    private final RedisLCUConnectionService redisLCUConnection;

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

    // ✅ REMOVIDO: HashMaps locais removidos - Redis é fonte única da verdade
    // Use redisLCUConnection para todas as operações de conexões LCU

    /**
     * Registra uma nova conexão LCU para um jogador
     * 
     * ⚡ AGORA USA REDIS para persistência e performance
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

        // ⚡ REDIS ONLY: Registrar conexão (persistente, fonte única da verdade)
        boolean registered = redisLCUConnection.registerConnection(normalizedName, sessionId, host, port, authToken);

        if (!registered) {
            log.error("❌ [LCURegistry] Falha ao registrar conexão no Redis: '{}'", normalizedName);
            return;
        }

        log.info("✅ [LCURegistry] Conexão LCU registrada (REDIS): '{}' (session: {}, {}:{})",
                normalizedName, sessionId, host, port);
        log.info("📊 [LCURegistry] Total de conexões ativas (Redis): {}",
                redisLCUConnection.getActiveConnectionCount());
    }

    /**
     * Busca a conexão LCU de um jogador específico
     * 
     * ⚡ AGORA USA REDIS como fonte primária de dados
     * 
     * @param summonerName Nome do invocador
     * @return Optional com a conexão se encontrada
     */
    public Optional<LCUConnectionInfo> getConnection(String summonerName) {
        if (summonerName == null || summonerName.trim().isEmpty()) {
            return Optional.empty();
        }

        String normalizedName = summonerName.toLowerCase().trim();

        // ⚡ REDIS: Buscar primeiro no Redis (fonte da verdade)
        Optional<RedisLCUConnectionService.LCUConnectionInfo> redisInfo = redisLCUConnection
                .getConnection(normalizedName);

        if (redisInfo.isPresent()) {
            // Converter RedisLCUConnectionInfo para LCUConnectionInfo
            RedisLCUConnectionService.LCUConnectionInfo redis = redisInfo.get();
            LCUConnectionInfo connection = new LCUConnectionInfo(
                    redis.getSummonerName(),
                    redis.getSessionId(),
                    redis.getHost(),
                    redis.getPort(),
                    redis.getAuthToken());

            log.debug("🔍 [LCURegistry] Conexão LCU encontrada (REDIS): '{}'", normalizedName);
            return Optional.of(connection);
        } else {
            log.warn("⚠️ [LCURegistry] Nenhuma conexão LCU encontrada para '{}'", normalizedName);
            List<String> available = redisLCUConnection.getAllActiveSummoners();
            log.warn("📋 [LCURegistry] Conexões disponíveis (REDIS): {}", available);
            return Optional.empty();
        }
    }

    /**
     * ⚡ REDIS ONLY: Remove a conexão LCU de um jogador
     * 
     * @param summonerName Nome do invocador
     */
    public void unregisterConnection(String summonerName) {
        if (summonerName == null || summonerName.trim().isEmpty()) {
            return;
        }

        String normalizedName = summonerName.toLowerCase().trim();

        // ⚡ REDIS ONLY: Remover do Redis (fonte única da verdade)
        boolean removed = redisLCUConnection.unregisterConnection(normalizedName);

        if (removed) {
            log.info("🗑️ [LCURegistry] Conexão LCU removida (Redis): '{}'", normalizedName);
            log.info("📊 [LCURegistry] Total de conexões ativas (Redis): {}",
                    redisLCUConnection.getActiveConnectionCount());
        } else {
            log.warn("⚠️ [LCURegistry] Conexão não encontrada no Redis: '{}'", normalizedName);
        }
    }

    /**
     * ⚡ REDIS ONLY: Remove conexão por sessionId (quando WebSocket desconecta)
     * 
     * @param sessionId ID da sessão WebSocket
     */
    public void unregisterBySession(String sessionId) {
        // ⚡ REDIS ONLY: Buscar summonerName do Redis
        Optional<String> summonerNameOpt = redisLCUConnection.getSummonerBySession(sessionId);
        if (summonerNameOpt.isPresent()) {
            unregisterConnection(summonerNameOpt.get());
        } else {
            log.warn("⚠️ [LCURegistry] Nenhum summoner encontrado para sessionId: {}", sessionId);
        }
    }

    /**
     * ⚡ REDIS ONLY: Verifica se existe uma conexão LCU para um jogador
     * 
     * @param summonerName Nome do invocador
     * @return true se existe conexão ativa
     */
    public boolean hasConnection(String summonerName) {
        if (summonerName == null || summonerName.trim().isEmpty()) {
            return false;
        }
        return redisLCUConnection.hasConnection(summonerName.toLowerCase().trim());
    }

    /**
     * ⚡ REDIS ONLY: Retorna o número total de conexões ativas
     */
    public int getConnectionCount() {
        return redisLCUConnection.getActiveConnectionCount();
    }

    /**
     * ⚡ REDIS ONLY: Lista todas as conexões ativas (para debug/admin)
     */
    public Map<String, LCUConnectionInfo> getAllConnections() {
        // Converter de Redis para formato local
        Map<String, LCUConnectionInfo> result = new java.util.HashMap<>();
        List<String> activeSummoners = redisLCUConnection.getAllActiveSummoners();

        for (String summonerName : activeSummoners) {
            redisLCUConnection.getConnection(summonerName).ifPresent(redisInfo -> {
                LCUConnectionInfo localInfo = new LCUConnectionInfo(
                        redisInfo.getSummonerName(),
                        redisInfo.getSessionId(),
                        redisInfo.getHost(),
                        redisInfo.getPort(),
                        redisInfo.getAuthToken());
                result.put(summonerName, localInfo);
            });
        }

        return result;
    }

    /**
     * ⚡ REDIS ONLY: Remove todas as conexões (para testes/reset)
     */
    public void clearAll() {
        int count = redisLCUConnection.getActiveConnectionCount();
        redisLCUConnection.clearAll();
        log.warn("🗑️ [LCURegistry] Todas as {} conexões foram removidas do Redis", count);
    }
}
