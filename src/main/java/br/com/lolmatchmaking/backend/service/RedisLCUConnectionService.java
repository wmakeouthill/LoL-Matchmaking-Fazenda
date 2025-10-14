package br.com.lolmatchmaking.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * ⚡ Redis service para gerenciar conexões LCU de múltiplos jogadores
 * 
 * PROBLEMA SEM REDIS:
 * - Cada Electron tem sua própria conexão LCU (host:port único)
 * - ConcurrentHashMaps perdem todas as conexões se backend reiniciar
 * - Backend precisa fazer requisições LCU individuais para cada jogador
 * - Exemplo: "Abrir loja do LoL" → Backend precisa saber o LCU do jogador X
 * 
 * SOLUÇÃO COM REDIS:
 * - Conexões LCU persistem mesmo com reinício do backend
 * - Lookup por summonerName é instantâneo (O(1))
 * - TTL automático de 2 horas (conexões antigas expiram)
 * - Lookup reverso: sessionId → summonerName
 * 
 * ARQUITETURA:
 * - 1 Backend (Cloud Run)
 * - Múltiplos Electrons (1 por jogador)
 * - Cada Electron registra: { summonerName, sessionId, host, port, authToken }
 * - Backend busca conexão por summonerName quando precisa fazer requisição LCU
 * 
 * CHAVES REDIS:
 * - lcu:connection:{summonerName} → Hash {sessionId, host, port, authToken,
 * registeredAt}
 * - lcu:session:{sessionId} → String (summonerName) [lookup reverso]
 * 
 * TTL: 2 horas (tempo suficiente para qualquer sessão LCU)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisLCUConnectionService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CONNECTION_PREFIX = "lcu:connection:";
    // ✅ REMOVIDO: SESSION_PREFIX - usando sistema centralizado ws:session:{sessionId}
    private static final long TTL_SECONDS = 7200; // 2 horas

    /**
     * Informações de uma conexão LCU
     */
    public static class LCUConnectionInfo {
        private String summonerName;
        private String sessionId;
        private String host;
        private int port;
        private String authToken;
        private long registeredAt;
        private long lastActivityAt;

        public LCUConnectionInfo() {
        }

        public LCUConnectionInfo(String summonerName, String sessionId, String host,
                int port, String authToken, long registeredAt, long lastActivityAt) {
            this.summonerName = summonerName;
            this.sessionId = sessionId;
            this.host = host;
            this.port = port;
            this.authToken = authToken;
            this.registeredAt = registeredAt;
            this.lastActivityAt = lastActivityAt;
        }

        // Getters e setters
        public String getSummonerName() {
            return summonerName;
        }

        public void setSummonerName(String summonerName) {
            this.summonerName = summonerName;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
        }

        public long getRegisteredAt() {
            return registeredAt;
        }

        public void setRegisteredAt(long registeredAt) {
            this.registeredAt = registeredAt;
        }

        public long getLastActivityAt() {
            return lastActivityAt;
        }

        public void setLastActivityAt(long lastActivityAt) {
            this.lastActivityAt = lastActivityAt;
        }
    }

    // ========================================
    // REGISTRAR CONEXÃO LCU
    // ========================================

    /**
     * Registra uma conexão LCU no Redis
     * 
     * @param summonerName Nome do invocador (normalizado)
     * @param sessionId    ID da sessão WebSocket
     * @param host         Host do LCU (geralmente "127.0.0.1")
     * @param port         Porta do LCU (ex: 63021)
     * @param authToken    Token de autenticação do LCU
     * @return true se registrado com sucesso
     */
    public boolean registerConnection(String summonerName, String sessionId, String host,
            int port, String authToken) {
        try {
            if (summonerName == null || summonerName.trim().isEmpty()) {
                log.warn("⚠️ [RedisLCU] summonerName vazio, ignorando registro");
                return false;
            }

            String normalizedName = summonerName.toLowerCase().trim();
            long now = System.currentTimeMillis();

            // Criar estrutura de dados
            Map<String, Object> data = new HashMap<>();
            data.put("summonerName", normalizedName);
            data.put("sessionId", sessionId);
            data.put("host", host);
            data.put("port", port);
            data.put("authToken", authToken);
            data.put("registeredAt", now);
            data.put("lastActivityAt", now);

            // Salvar: lcu:connection:{summonerName}
            String connectionKey = CONNECTION_PREFIX + normalizedName;
            redisTemplate.opsForHash().putAll(connectionKey, data);
            redisTemplate.expire(connectionKey, TTL_SECONDS, TimeUnit.SECONDS);

            // ✅ CORREÇÃO: NÃO criar lcu:session:{sessionId} - DUPLICA
            // ws:session:{sessionId}
            // O sistema centralizado (RedisWebSocketSessionService) já cria
            // ws:session:{sessionId} → summonerName
            // Não precisamos de chaves duplicadas!

            log.info("✅ [RedisLCU] Conexão registrada: '{}' (session: {}, {}:{})",
                    normalizedName, sessionId, host, port);

            return true;

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao registrar conexão: summoner={}", summonerName, e);
            return false;
        }
    }

    // ========================================
    // BUSCAR CONEXÃO LCU
    // ========================================

    /**
     * Busca conexão LCU por summonerName (O(1) - instantâneo)
     * 
     * @param summonerName Nome do invocador
     * @return Optional com informações da conexão
     */
    public Optional<LCUConnectionInfo> getConnection(String summonerName) {
        try {
            if (summonerName == null || summonerName.trim().isEmpty()) {
                return Optional.empty();
            }

            String normalizedName = summonerName.toLowerCase().trim();
            String key = CONNECTION_PREFIX + normalizedName;

            // Buscar Hash do Redis
            Map<Object, Object> data = redisTemplate.opsForHash().entries(key);

            if (data.isEmpty()) {
                log.debug("📭 [RedisLCU] Conexão não encontrada: '{}'", normalizedName);
                return Optional.empty();
            }

            // Converter para LCUConnectionInfo
            LCUConnectionInfo info = new LCUConnectionInfo();
            info.setSummonerName((String) data.get("summonerName"));
            info.setSessionId((String) data.get("sessionId"));
            info.setHost((String) data.get("host"));

            Object portObj = data.get("port");
            info.setPort(portObj instanceof Integer ? (Integer) portObj : Integer.parseInt(portObj.toString()));

            info.setAuthToken((String) data.get("authToken"));

            Object regAtObj = data.get("registeredAt");
            info.setRegisteredAt(regAtObj instanceof Long ? (Long) regAtObj : Long.parseLong(regAtObj.toString()));

            Object actAtObj = data.get("lastActivityAt");
            info.setLastActivityAt(actAtObj instanceof Long ? (Long) actAtObj : Long.parseLong(actAtObj.toString()));

            // Atualizar lastActivityAt
            updateActivity(normalizedName);

            log.debug("🔍 [RedisLCU] Conexão encontrada: '{}'", normalizedName);
            return Optional.of(info);

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao buscar conexão: summoner={}", summonerName, e);
            return Optional.empty();
        }
    }

    /**
     * ✅ CORREÇÃO: Usar sistema centralizado em vez de chaves duplicadas
     * 
     * @param sessionId ID da sessão WebSocket
     * @return Optional com summonerName
     */
    public Optional<String> getSummonerBySession(String sessionId) {
        // ✅ CORREÇÃO: Delegar para o sistema centralizado
        // (RedisWebSocketSessionService)
        // que usa ws:session:{sessionId} em vez de lcu:session:{sessionId}
        log.debug("ℹ️ [RedisLCU] Busca de summoner por sessionId delegada ao sistema centralizado: {}", sessionId);
        return Optional.empty(); // TODO: Integrar com RedisWebSocketSessionService.getSummonerBySession()
    }

    // ========================================
    // ATUALIZAR ATIVIDADE
    // ========================================

    /**
     * Atualiza timestamp de última atividade (extend TTL)
     * 
     * @param summonerName Nome do invocador
     */
    public void updateActivity(String summonerName) {
        try {
            String normalizedName = summonerName.toLowerCase().trim();
            String key = CONNECTION_PREFIX + normalizedName;

            // Atualizar lastActivityAt
            long now = System.currentTimeMillis();
            redisTemplate.opsForHash().put(key, "lastActivityAt", now);

            // Estender TTL
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);

            log.debug("🔄 [RedisLCU] Atividade atualizada: '{}'", normalizedName);

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao atualizar atividade: summoner={}", summonerName, e);
        }
    }

    // ========================================
    // REMOVER CONEXÃO
    // ========================================

    /**
     * Remove conexão LCU do Redis
     * 
     * @param summonerName Nome do invocador
     */
    public void removeConnection(String summonerName) {
        try {
            String normalizedName = summonerName.toLowerCase().trim();

            // Buscar sessionId primeiro (para remover lookup reverso)
            String connectionKey = CONNECTION_PREFIX + normalizedName;
            Object sessionId = redisTemplate.opsForHash().get(connectionKey, "sessionId");

            // Remover conexão
            redisTemplate.delete(connectionKey);

            // ✅ CORREÇÃO: NÃO remover lcu:session:{sessionId} - usar sistema centralizado
            // O sistema centralizado (RedisWebSocketSessionService) gerencia
            // ws:session:{sessionId}

            log.info("🗑️ [RedisLCU] Conexão removida: '{}'", normalizedName);

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao remover conexão: summoner={}", summonerName, e);
        }
    }

    /**
     * Remove conexão por sessionId
     * 
     * @param sessionId ID da sessão WebSocket
     */
    public void removeConnectionBySession(String sessionId) {
        try {
            // Buscar summonerName primeiro
            Optional<String> summonerOpt = getSummonerBySession(sessionId);

            if (summonerOpt.isPresent()) {
                removeConnection(summonerOpt.get());
            } else {
                log.warn("⚠️ [RedisLCU] Session não encontrada para remover: {}", sessionId);
            }

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao remover por session: {}", sessionId, e);
        }
    }

    // ========================================
    // VERIFICAÇÕES E LISTAGEM
    // ========================================

    /**
     * Verifica se existe conexão para um jogador
     * 
     * @param summonerName Nome do invocador
     * @return true se existe
     */
    public boolean hasConnection(String summonerName) {
        try {
            String normalizedName = summonerName.toLowerCase().trim();
            String key = CONNECTION_PREFIX + normalizedName;
            Boolean exists = redisTemplate.hasKey(key);
            return exists != null && exists;
        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao verificar conexão: summoner={}", summonerName, e);
            return false;
        }
    }

    /**
     * Obtém todas as conexões ativas (para debugging)
     * 
     * @return Lista de summonerNames com conexão ativa
     */
    public List<String> getAllActiveSummoners() {
        try {
            Set<String> keys = redisTemplate.keys(CONNECTION_PREFIX + "*");

            if (keys == null) {
                return List.of();
            }

            List<String> summoners = new ArrayList<>();
            for (String key : keys) {
                String summonerName = key.substring(CONNECTION_PREFIX.length());
                summoners.add(summonerName);
            }

            log.debug("📋 [RedisLCU] Conexões ativas: {} jogadores", summoners.size());
            return summoners;

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao listar conexões", e);
            return List.of();
        }
    }

    /**
     * Obtém contagem de conexões ativas
     * 
     * @return Número de conexões
     */
    public int getActiveConnectionCount() {
        try {
            Set<String> keys = redisTemplate.keys(CONNECTION_PREFIX + "*");
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao contar conexões", e);
            return 0;
        }
    }

    /**
     * ✅ NOVO: Armazena informações completas da conexão LCU do jogador.
     * 
     * @param summonerName Nome do invocador
     * @param host         Host do LCU (ex: 127.0.0.1)
     * @param port         Porta do LCU (ex: 58472)
     * @param protocol     Protocolo (https)
     * @param authToken    Token de autenticação Base64
     * @return true se armazenou com sucesso, false caso contrário
     */
    public boolean storeLcuConnection(String summonerName, String host, int port, String protocol, String authToken) {
        try {
            if (summonerName == null || host == null || protocol == null || authToken == null) {
                log.warn("⚠️ [RedisLCU] Dados inválidos para storeLcuConnection");
                return false;
            }

            String normalizedSummoner = summonerName.toLowerCase().trim();
            String connectionKey = CONNECTION_PREFIX + normalizedSummoner;

            // Criar dados da conexão
            Map<String, Object> connectionData = new HashMap<>();
            connectionData.put("host", host);
            connectionData.put("port", port);
            connectionData.put("protocol", protocol);
            connectionData.put("authToken", authToken);
            connectionData.put("registeredAt", System.currentTimeMillis());

            // Armazenar no Redis com TTL
            redisTemplate.opsForHash().putAll(connectionKey, connectionData);
            redisTemplate.expire(connectionKey, TTL_SECONDS, TimeUnit.SECONDS);

            log.debug("✅ [RedisLCU] Conexão LCU armazenada: {} → {}:{}", normalizedSummoner, host, port);
            return true;

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao armazenar conexão LCU: {}", summonerName, e);
            return false;
        }
    }

    /**
     * Armazena último status LCU do jogador (lcu_status event).
     * 
     * Inclui: estado da conexão (conectado/desconectado), versão do LCU, etc.
     * Permite detectar mudanças de estado após backend restart.
     * 
     * @param summonerName Nome do invocador
     * @param lcuStatus    JSON com status do LCU
     * @return true se armazenou com sucesso, false caso contrário
     */
    public boolean storeLcuStatus(String summonerName, String lcuStatus) {
        try {
            if (summonerName == null || lcuStatus == null) {
                log.warn("⚠️ [RedisLCU] Dados inválidos para storeLcuStatus");
                return false;
            }

            String normalizedSummoner = summonerName.toLowerCase().trim();
            String key = CONNECTION_PREFIX + normalizedSummoner;

            // Armazena status no Hash existente (ou cria novo)
            redisTemplate.opsForHash().put(key, "lastStatus", lcuStatus);
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);

            log.debug("✅ [RedisLCU] LCU status armazenado: summonerName={}", normalizedSummoner);
            return true;

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao armazenar LCU status: summonerName={}", summonerName, e);
            return false;
        }
    }

    /**
     * Recupera último status LCU do jogador.
     * 
     * @param summonerName Nome do invocador
     * @return JSON com status do LCU, ou null se não encontrado
     */
    public String getLastLcuStatus(String summonerName) {
        try {
            if (summonerName == null) {
                return null;
            }

            String normalizedSummoner = summonerName.toLowerCase().trim();
            String key = CONNECTION_PREFIX + normalizedSummoner;

            Object statusObj = redisTemplate.opsForHash().get(key, "lastStatus");
            if (statusObj != null) {
                String status = statusObj.toString();
                log.debug("✅ [RedisLCU] LCU status recuperado: summonerName={}", normalizedSummoner);
                return status;
            } else {
                log.debug("⚠️ [RedisLCU] LCU status não encontrado: summonerName={}", normalizedSummoner);
                return null;
            }

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao recuperar LCU status: summonerName={}", summonerName, e);
            return null;
        }
    }

    /**
     * Remove último status LCU do jogador.
     * 
     * @param summonerName Nome do invocador
     * @return true se removeu com sucesso, false caso contrário
     */
    public boolean removeLastLcuStatus(String summonerName) {
        try {
            if (summonerName == null) {
                return false;
            }

            String normalizedSummoner = summonerName.toLowerCase().trim();
            String key = CONNECTION_PREFIX + normalizedSummoner;

            Long deleted = redisTemplate.opsForHash().delete(key, "lastStatus");
            if (deleted != null && deleted > 0) {
                log.debug("✅ [RedisLCU] LCU status removido: summonerName={}", normalizedSummoner);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao remover LCU status: summonerName={}", summonerName, e);
            return false;
        }
    }

    /**
     * ✅ NOVO: Remove uma conexão LCU
     * 
     * @param summonerName Nome do invocador
     * @return true se removeu com sucesso
     */
    public boolean unregisterConnection(String summonerName) {
        if (summonerName == null || summonerName.trim().isEmpty()) {
            return false;
        }

        String normalizedName = summonerName.toLowerCase().trim();
        String key = CONNECTION_PREFIX + normalizedName;

        try {
            log.info("🗑️ [RedisLCU] Removendo conexão: {}", normalizedName);

            // Buscar sessionId antes de deletar
            Object sessionIdObj = redisTemplate.opsForHash().get(key, "sessionId");
            String sessionId = sessionIdObj != null ? sessionIdObj.toString() : null;

            // Deletar conexão
            Boolean deleted = redisTemplate.delete(key);

            // ✅ CORREÇÃO: NÃO remover lcu:session:{sessionId} - usar sistema centralizado
            // O sistema centralizado (RedisWebSocketSessionService) gerencia ws:session:{sessionId}

            // Remover LCU status
            removeLastLcuStatus(normalizedName);

            log.info("✅ [RedisLCU] Conexão removida: {} (deleted: {})", normalizedName, deleted);
            return Boolean.TRUE.equals(deleted);

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao remover conexão: {}", normalizedName, e);
            return false;
        }
    }

    /**
     * ✅ NOVO: Remove todas as conexões (para reset/testes)
     */
    public void clearAll() {
        try {
            log.warn("🗑️ [RedisLCU] Removendo TODAS as conexões...");

            List<String> activeSummoners = getAllActiveSummoners();
            int count = activeSummoners.size();

            for (String summonerName : activeSummoners) {
                unregisterConnection(summonerName);
            }

            log.warn("✅ [RedisLCU] {} conexões removidas", count);

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao limpar todas as conexões", e);
        }
    }

    /**
     * Obtém métricas das conexões para debugging
     * 
     * @return Mapa com métricas
     */
    public Map<String, Object> getMetrics() {
        try {
            List<String> summoners = getAllActiveSummoners();

            return Map.of(
                    "activeConnections", summoners.size(),
                    "summoners", summoners,
                    "ttlSeconds", TTL_SECONDS);

        } catch (Exception e) {
            log.error("❌ [RedisLCU] Erro ao obter métricas", e);
            return Map.of("error", e.getMessage());
        }
    }
}
