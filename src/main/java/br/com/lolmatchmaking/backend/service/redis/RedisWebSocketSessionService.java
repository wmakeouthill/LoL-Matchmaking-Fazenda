package br.com.lolmatchmaking.backend.service.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 🚀 SERVIÇO REDIS - SESSÕES WEBSOCKET
 * 
 * Gerencia mapeamentos de sessões WebSocket em Redis para sobreviver a
 * reinícios do backend.
 * 
 * PROBLEMA RESOLVIDO:
 * - Backend reinicia → ConcurrentHashMaps zerados → Electrons reconectam mas
 * backend não sabe "quem é quem"
 * - Eventos não chegam aos jogadores corretos (ex: "draft_confirmed" para
 * Player5)
 * 
 * SOLUÇÃO:
 * - Armazena mapeamentos em Redis: sessionId ↔ summonerName
 * - Metadata de conexão: IP, connectedAt, lastHeartbeat
 * - Backend restaura estado instantaneamente após reinício
 * - Electrons reconectam e eventos chegam corretamente
 * 
 * CHAVES REDIS:
 * - ws:session:{sessionId} → summonerName (String)
 * - ws:player:{summonerName} → sessionId (String) [lookup reverso]
 * - ws:client:{sessionId} → ClientInfo (Hash: ip, connectedAt, lastActivity,
 * userAgent)
 * 
 * TTL: 6 horas (conexões WebSocket normalmente < 2h)
 * 
 * OPERAÇÕES:
 * - registerSession: Registra novo WebSocket (bidirecionalpairMapping)
 * - getSessionBySummoner: Busca sessionId por summonerName (enviar eventos)
 * - getSummonerBySession: Busca summonerName por sessionId (identificação)
 * - updateHeartbeat: Atualiza lastActivity (mantém sessão viva)
 * - removeSession: Remove sessão (disconnect)
 * - getAllActiveSessions: Lista todas as sessões ativas
 * 
 * @author LoL Matchmaking Backend Team
 * @since Migração Redis WebSockets
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisWebSocketSessionService {

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    // TTL para sessões WebSocket (6 horas - conexões normalmente < 2h)
    private static final long SESSION_TTL_SECONDS = 21600; // 6 horas

    // Prefixos de chaves
    private static final String SESSION_KEY_PREFIX = "ws:session:"; // sessionId → summonerName
    private static final String PLAYER_KEY_PREFIX = "ws:player:"; // summonerName → sessionId
    private static final String CLIENT_KEY_PREFIX = "ws:client:"; // sessionId → ClientInfo
    private static final String PLAYER_INFO_KEY_PREFIX = "ws:player_info:"; // sessionId → PlayerInfo (JSON)

    /**
     * Informações do cliente WebSocket
     */
    @Data
    @Builder
    public static class ClientInfo {
        private String sessionId;
        private String summonerName;
        private String ipAddress;
        private Instant connectedAt;
        private Instant lastActivity;
        private String userAgent;
    }

    /**
     * Registra nova sessão WebSocket com bidirectional mapping.
     * 
     * Cria 3 chaves Redis:
     * 1. ws:session:{sessionId} → summonerName (enviar eventos por sessionId)
     * 2. ws:player:{summonerName} → sessionId (buscar sessão por player)
     * 3. ws:client:{sessionId} → ClientInfo (metadata completa)
     * 
     * @param sessionId    ID único da sessão WebSocket
     * @param summonerName Nome do invocador
     * @param ipAddress    IP do cliente
     * @param userAgent    User-Agent do navegador/Electron
     * @return true se registrou com sucesso, false se já existia
     */
    public boolean registerSession(String sessionId, String summonerName, String ipAddress, String userAgent) {
        try {
            if (sessionId == null || sessionId.isBlank() || summonerName == null || summonerName.isBlank()) {
                log.warn(
                        "⚠️ [RedisWS] Tentativa de registrar sessão com dados inválidos: sessionId={}, summonerName={}",
                        sessionId, summonerName);
                return false;
            }

            String normalizedSummoner = normalizeSummonerName(summonerName);

            // 1. sessionId → summonerName
            RBucket<String> sessionBucket = redisson.getBucket(SESSION_KEY_PREFIX + sessionId);
            sessionBucket.set(normalizedSummoner, SESSION_TTL_SECONDS, TimeUnit.SECONDS);

            // 2. summonerName → sessionId (lookup reverso)
            RBucket<String> playerBucket = redisson.getBucket(PLAYER_KEY_PREFIX + normalizedSummoner);

            // Verificar se já existe sessão anterior
            String existingSessionId = playerBucket.get();
            if (existingSessionId != null && !existingSessionId.equals(sessionId)) {
                log.info("📱 [RedisWS] Jogador {} reconectando. Sessão anterior: {} → Nova: {}",
                        normalizedSummoner, existingSessionId, sessionId);

                // Limpar sessão anterior
                removeSession(existingSessionId);
            }

            playerBucket.set(sessionId, SESSION_TTL_SECONDS, TimeUnit.SECONDS);

            // 3. Metadata completa
            ClientInfo clientInfo = ClientInfo.builder()
                    .sessionId(sessionId)
                    .summonerName(normalizedSummoner)
                    .ipAddress(ipAddress)
                    .connectedAt(Instant.now())
                    .lastActivity(Instant.now())
                    .userAgent(userAgent)
                    .build();

            RMap<String, String> clientMap = redisson.getMap(CLIENT_KEY_PREFIX + sessionId);
            clientMap.put("sessionId", sessionId);
            clientMap.put("summonerName", normalizedSummoner);
            clientMap.put("ipAddress", ipAddress != null ? ipAddress : "unknown");
            clientMap.put("connectedAt", clientInfo.getConnectedAt().toString());
            clientMap.put("lastActivity", clientInfo.getLastActivity().toString());
            clientMap.put("userAgent", userAgent != null ? userAgent : "unknown");
            clientMap.expire(SESSION_TTL_SECONDS, TimeUnit.SECONDS);

            log.info("✅ [RedisWS] Sessão registrada: {} → {} (IP: {})",
                    sessionId, normalizedSummoner, ipAddress);

            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao registrar sessão: sessionId={}, summonerName={}",
                    sessionId, summonerName, e);
            return false;
        }
    }

    /**
     * Busca sessionId por summonerName.
     * CRÍTICO para enviar eventos ao jogador correto.
     * 
     * Exemplo de uso:
     * - Backend precisa enviar "draft_confirmed" para Player5
     * - Busca: sessionId = getSessionBySummoner("Player5")
     * - Envia evento via WebSocket usando sessionId
     * 
     * @param summonerName Nome do invocador
     * @return Optional com sessionId se encontrado, empty se offline
     */
    public Optional<String> getSessionBySummoner(String summonerName) {
        try {
            if (summonerName == null || summonerName.isBlank()) {
                return Optional.empty();
            }

            String normalizedSummoner = normalizeSummonerName(summonerName);
            RBucket<String> playerBucket = redisson.getBucket(PLAYER_KEY_PREFIX + normalizedSummoner);
            String sessionId = playerBucket.get();

            if (sessionId != null) {
                log.debug("🔍 [RedisWS] Sessão encontrada: {} → {}", normalizedSummoner, sessionId);
                return Optional.of(sessionId);
            }

            log.debug("❌ [RedisWS] Sessão NÃO encontrada para: {}", normalizedSummoner);
            return Optional.empty();

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao buscar sessão por summoner: {}", summonerName, e);
            return Optional.empty();
        }
    }

    /**
     * Busca summonerName por sessionId.
     * Usado para identificar quem enviou uma mensagem WebSocket.
     * 
     * @param sessionId ID da sessão WebSocket
     * @return Optional com summonerName se encontrado, empty se não identificado
     */
    public Optional<String> getSummonerBySession(String sessionId) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return Optional.empty();
            }

            RBucket<String> sessionBucket = redisson.getBucket(SESSION_KEY_PREFIX + sessionId);
            String summonerName = sessionBucket.get();

            if (summonerName != null) {
                log.debug("🔍 [RedisWS] Summoner encontrado: {} → {}", sessionId, summonerName);
                return Optional.of(summonerName);
            }

            log.debug("❌ [RedisWS] Summoner NÃO encontrado para sessão: {}", sessionId);
            return Optional.empty();

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao buscar summoner por sessão: {}", sessionId, e);
            return Optional.empty();
        }
    }

    /**
     * Busca metadata completa da conexão.
     * 
     * @param sessionId ID da sessão WebSocket
     * @return Optional com ClientInfo se encontrado, empty se não existe
     */
    public Optional<ClientInfo> getClientInfo(String sessionId) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return Optional.empty();
            }

            RMap<String, String> clientMap = redisson.getMap(CLIENT_KEY_PREFIX + sessionId);
            if (clientMap.isEmpty()) {
                return Optional.empty();
            }

            ClientInfo clientInfo = ClientInfo.builder()
                    .sessionId(clientMap.get("sessionId"))
                    .summonerName(clientMap.get("summonerName"))
                    .ipAddress(clientMap.get("ipAddress"))
                    .connectedAt(Instant.parse(clientMap.get("connectedAt")))
                    .lastActivity(Instant.parse(clientMap.get("lastActivity")))
                    .userAgent(clientMap.get("userAgent"))
                    .build();

            return Optional.of(clientInfo);

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao buscar client info: {}", sessionId, e);
            return Optional.empty();
        }
    }

    /**
     * Atualiza timestamp de última atividade (heartbeat).
     * Mantém sessão viva e extende TTL.
     * 
     * @param sessionId ID da sessão WebSocket
     * @return true se atualizou com sucesso, false se sessão não existe
     */
    public boolean updateHeartbeat(String sessionId) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return false;
            }

            RMap<String, String> clientMap = redisson.getMap(CLIENT_KEY_PREFIX + sessionId);
            if (clientMap.isEmpty()) {
                log.warn("⚠️ [RedisWS] Tentativa de heartbeat em sessão inexistente: {}", sessionId);
                return false;
            }

            // Atualizar lastActivity
            clientMap.put("lastActivity", Instant.now().toString());

            // Extender TTL de todas as chaves relacionadas
            clientMap.expire(SESSION_TTL_SECONDS, TimeUnit.SECONDS);

            String summonerName = clientMap.get("summonerName");
            if (summonerName != null) {
                redisson.getBucket(SESSION_KEY_PREFIX + sessionId).expire(SESSION_TTL_SECONDS, TimeUnit.SECONDS);
                redisson.getBucket(PLAYER_KEY_PREFIX + summonerName).expire(SESSION_TTL_SECONDS, TimeUnit.SECONDS);
            }

            log.debug("💓 [RedisWS] Heartbeat atualizado: {} (summoner: {})", sessionId, summonerName);
            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao atualizar heartbeat: {}", sessionId, e);
            return false;
        }
    }

    /**
     * Remove sessão WebSocket (disconnect).
     * Remove todas as 3 chaves Redis associadas.
     * 
     * @param sessionId ID da sessão WebSocket
     * @return true se removeu com sucesso, false se não existia
     */
    public boolean removeSession(String sessionId) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return false;
            }

            // Buscar summonerName antes de deletar
            Optional<String> summonerOpt = getSummonerBySession(sessionId);

            // Deletar chaves
            redisson.getBucket(SESSION_KEY_PREFIX + sessionId).delete();
            redisson.getMap(CLIENT_KEY_PREFIX + sessionId).delete();

            if (summonerOpt.isPresent()) {
                String summonerName = summonerOpt.get();
                redisson.getBucket(PLAYER_KEY_PREFIX + summonerName).delete();
                log.info("🗑️ [RedisWS] Sessão removida: {} ({})", sessionId, summonerName);
            } else {
                log.info("🗑️ [RedisWS] Sessão removida: {} (summoner desconhecido)", sessionId);
            }

            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao remover sessão: {}", sessionId, e);
            return false;
        }
    }

    /**
     * Lista todas as sessões ativas.
     * Útil para broadcast e monitoramento.
     * 
     * @return Map de sessionId → summonerName
     */
    public Map<String, String> getAllActiveSessions() {
        try {
            Map<String, String> sessions = new HashMap<>();

            // Buscar todas as chaves ws:session:*
            Iterable<String> keys = redisson.getKeys().getKeysByPattern(SESSION_KEY_PREFIX + "*");

            for (String key : keys) {
                String sessionId = key.substring(SESSION_KEY_PREFIX.length());
                RBucket<String> bucket = redisson.getBucket(key);
                String summonerName = bucket.get();

                if (summonerName != null) {
                    sessions.put(sessionId, summonerName);
                }
            }

            log.debug("📊 [RedisWS] Sessões ativas: {}", sessions.size());
            return sessions;

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao listar sessões ativas", e);
            return Collections.emptyMap();
        }
    }

    /**
     * Verifica se sessão existe e está ativa.
     * 
     * @param sessionId ID da sessão WebSocket
     * @return true se sessão existe, false caso contrário
     */
    public boolean hasSession(String sessionId) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return false;
            }

            return redisson.getBucket(SESSION_KEY_PREFIX + sessionId).isExists();

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao verificar existência de sessão: {}", sessionId, e);
            return false;
        }
    }

    /**
     * Verifica se jogador está online (tem sessão ativa).
     * 
     * @param summonerName Nome do invocador
     * @return true se jogador está online, false caso contrário
     */
    public boolean isPlayerOnline(String summonerName) {
        try {
            if (summonerName == null || summonerName.isBlank()) {
                return false;
            }

            String normalizedSummoner = normalizeSummonerName(summonerName);
            return redisson.getBucket(PLAYER_KEY_PREFIX + normalizedSummoner).isExists();

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao verificar se jogador está online: {}", summonerName, e);
            return false;
        }
    }

    /**
     * Armazena informações completas do jogador identificado (identify_player).
     * 
     * Inclui: summonerName, profileIconId, puuid, summonerId, etc.
     * Permite restaurar identificação após backend restart.
     * 
     * @param sessionId  ID da sessão WebSocket
     * @param playerInfo JSON com dados do jogador
     * @return true se armazenou com sucesso, false caso contrário
     */
    public boolean storePlayerInfo(String sessionId, String playerInfo) {
        try {
            if (sessionId == null || playerInfo == null) {
                log.warn("⚠️ [RedisWS] Dados inválidos para storePlayerInfo");
                return false;
            }

            String key = PLAYER_INFO_KEY_PREFIX + sessionId;
            RBucket<String> bucket = redisson.getBucket(key);
            bucket.set(playerInfo, SESSION_TTL_SECONDS, TimeUnit.SECONDS);

            log.debug("✅ [RedisWS] Player info armazenado: sessionId={}", sessionId);
            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao armazenar player info: sessionId={}", sessionId, e);
            return false;
        }
    }

    /**
     * Recupera informações completas do jogador identificado.
     * 
     * @param sessionId ID da sessão WebSocket
     * @return JSON com dados do jogador, ou null se não encontrado
     */
    public String getPlayerInfo(String sessionId) {
        try {
            if (sessionId == null) {
                return null;
            }

            String key = PLAYER_INFO_KEY_PREFIX + sessionId;
            RBucket<String> bucket = redisson.getBucket(key);
            String playerInfo = bucket.get();

            if (playerInfo != null) {
                log.debug("✅ [RedisWS] Player info recuperado: sessionId={}", sessionId);
            } else {
                log.debug("⚠️ [RedisWS] Player info não encontrado: sessionId={}", sessionId);
            }

            return playerInfo;

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao recuperar player info: sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * Remove informações do jogador identificado.
     * 
     * @param sessionId ID da sessão WebSocket
     * @return true se removeu com sucesso, false caso contrário
     */
    public boolean removePlayerInfo(String sessionId) {
        try {
            if (sessionId == null) {
                return false;
            }

            String key = PLAYER_INFO_KEY_PREFIX + sessionId;
            boolean deleted = redisson.getBucket(key).delete();

            if (deleted) {
                log.debug("✅ [RedisWS] Player info removido: sessionId={}", sessionId);
            }

            return deleted;

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao remover player info: sessionId={}", sessionId, e);
            return false;
        }
    }

    /**
     * Normaliza nome do invocador (lowercase, trim).
     * Garante consistência nos lookups.
     * 
     * @param summonerName Nome do invocador
     * @return Nome normalizado
     */
    private String normalizeSummonerName(String summonerName) {
        if (summonerName == null) {
            return "";
        }
        return summonerName.trim().toLowerCase();
    }
}
