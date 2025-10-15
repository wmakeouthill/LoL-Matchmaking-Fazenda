package br.com.lolmatchmaking.backend.service.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
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

    // ✅ CORRIGIDO: TTL de 1h30min (duração típica de sessão)
    // CRÍTICO: Sessão não deve persistir além da necessidade real
    // Sessão típica: < 1h, margem 30min = 1h30min total
    // Heartbeat estende TTL continuamente enquanto ativo
    // Monitor detecta desconexão em 30s (não espera TTL)
    private static final long SESSION_TTL_SECONDS = 5400; // 1h30min (90min)

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
    @NoArgsConstructor
    @AllArgsConstructor
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

            // ✅ CRÍTICO: VERIFICAR E REMOVER SESSÕES DUPLICADAS ANTES DE REGISTRAR
            // 1. Verificar se já existe sessão para este summoner
            RBucket<String> playerBucket = redisson.getBucket(PLAYER_KEY_PREFIX + normalizedSummoner);
            String existingSessionId = playerBucket.get();

            if (existingSessionId != null && !existingSessionId.equals(sessionId)) {
                log.warn("🚨 [RedisWS] SESSÃO DUPLICADA DETECTADA! Jogador {} já tem sessão ativa: {}",
                        normalizedSummoner, existingSessionId);

                // ✅ REMOVER: Sessão anterior completamente
                log.info("🗑️ [RedisWS] Removendo sessão anterior duplicada: {} (jogador: {})",
                        existingSessionId, normalizedSummoner);
                removeSession(existingSessionId);
            }

            // 2. Verificar se este sessionId já está registrado para outro jogador
            RBucket<String> sessionBucket = redisson.getBucket(SESSION_KEY_PREFIX + sessionId);
            String existingSummoner = sessionBucket.get();

            if (existingSummoner != null && !existingSummoner.equals(normalizedSummoner)) {
                log.warn("🚨 [RedisWS] SESSIONID DUPLICADO! Sessão {} já registrada para outro jogador: {}",
                        sessionId, existingSummoner);

                // ✅ REMOVER: Registro anterior desta sessão
                log.info("🗑️ [RedisWS] Removendo registro anterior da sessão {} (jogador anterior: {})",
                        sessionId, existingSummoner);
                removeSession(sessionId);
            }

            // ✅ REGISTRAR: Agora que limpamos duplicatas, registrar nova sessão
            // ✅ CORREÇÃO: NÃO criar chaves ws:session: e ws:player: - são redundantes!
            // A chave ws:client_info:{summonerName} já contém todas as informações
            // necessárias

            // ✅ CORREÇÃO: Metadata completa usando formato unificado (String/Object)
            ClientInfo clientInfo = ClientInfo.builder()
                    .sessionId(sessionId)
                    .summonerName(normalizedSummoner)
                    .ipAddress(ipAddress)
                    .connectedAt(Instant.now())
                    .lastActivity(Instant.now())
                    .userAgent(userAgent)
                    .build();

            // ✅ CORREÇÃO: Usar summonerName em vez de sessionId na chave
            String clientInfoKey = "ws:client_info:" + normalizedSummoner;
            RBucket<ClientInfo> clientInfoBucket = redisson.getBucket(clientInfoKey);
            clientInfoBucket.set(clientInfo, Duration.ofHours(1));

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

            // ✅ CORREÇÃO: Usar apenas chave unificada ws:client_info:{summonerName}
            String clientInfoKey = "ws:client_info:" + normalizedSummoner;
            RBucket<ClientInfo> clientInfoBucket = redisson.getBucket(clientInfoKey);
            ClientInfo clientInfo = clientInfoBucket.get();

            if (clientInfo != null && clientInfo.getSessionId() != null) {
                log.debug("🔍 [RedisWS] Sessão encontrada: {} → {}", normalizedSummoner, clientInfo.getSessionId());
                return Optional.of(clientInfo.getSessionId());
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

            // ✅ CORREÇÃO: Verificar se Redisson está ativo (evita erro durante shutdown)
            if (redisson.isShutdown() || redisson.isShuttingDown()) {
                log.debug("⚠️ [RedisWS] Redisson está desligado - retornando empty para sessionId: {}", sessionId);
                return Optional.empty();
            }

            // ✅ CORREÇÃO: Buscar em todas as chaves ws:client_info:* para encontrar o
            // sessionId
            Iterable<String> clientInfoKeys = redisson.getKeys().getKeysByPattern("ws:client_info:*");

            for (String clientInfoKey : clientInfoKeys) {
                RBucket<ClientInfo> clientInfoBucket = redisson.getBucket(clientInfoKey);
                ClientInfo clientInfo = clientInfoBucket.get();

                if (clientInfo != null && sessionId.equals(clientInfo.getSessionId())) {
                    String summonerName = clientInfo.getSummonerName();
                    log.debug("🔍 [RedisWS] Summoner encontrado: {} → {}", sessionId, summonerName);
                    return Optional.of(summonerName);
                }
            }

            log.debug("❌ [RedisWS] Summoner NÃO encontrado para sessão: {}", sessionId);
            return Optional.empty();

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao buscar summoner por sessão: {}", sessionId, e);
            return Optional.empty();
        }
    }

    /**
     * ✅ NOVO: Limpa chaves corrompidas por ClassLoader conflicts
     */
    private void cleanupCorruptedKeys(String sessionId) {
        try {
            // ✅ CORREÇÃO: Usar summonerName em vez de sessionId
            String summonerName = getSummonerBySession(sessionId).orElse(null);
            if (summonerName == null) {
                return; // Não há nada para limpar
            }

            String clientInfoKey = "ws:client_info:" + summonerName;
            RBucket<Object> bucket = redisson.getBucket(clientInfoKey);

            // Tentar acessar para detectar ClassCastException
            try {
                Object data = bucket.get();
                if (data != null && !(data instanceof ClientInfo)) {
                    log.warn("⚠️ [RedisWS] Dados corrompidos detectados - limpando: {}", clientInfoKey);
                    bucket.delete();
                }
            } catch (ClassCastException e) {
                log.warn("⚠️ [RedisWS] ClassCastException - limpando chave corrompida: {}", clientInfoKey);
                bucket.delete();
            }

        } catch (Exception e) {
            log.debug("⚠️ [RedisWS] Erro durante limpeza de chaves corrompidas: {}", e.getMessage());
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

            // ✅ CORREÇÃO: Verificar se Redisson está ativo (evita erro durante shutdown)
            if (redisson.isShutdown() || redisson.isShuttingDown()) {
                log.debug("⚠️ [RedisWS] Redisson está desligado - retornando empty para sessionId: {}", sessionId);
                return Optional.empty();
            }

            // ✅ CORREÇÃO: Buscar por sessionId primeiro, depois usar summonerName
            String summonerName = getSummonerBySession(sessionId).orElse(null);
            if (summonerName == null) {
                return Optional.empty();
            }

            String clientInfoKey = "ws:client_info:" + summonerName;
            RBucket<ClientInfo> clientInfoBucket = redisson.getBucket(clientInfoKey);

            try {
                ClientInfo clientInfo = clientInfoBucket.get();
                if (clientInfo != null) {
                    return Optional.of(clientInfo);
                }
            } catch (ClassCastException e) {
                // ✅ CORREÇÃO CRÍTICA: ClassLoader conflict (Spring Boot DevTools)
                log.warn(
                        "⚠️ [RedisWS] ClassCastException detectada (DevTools ClassLoader conflict) - limpando chave corrompida: {}",
                        clientInfoKey);
                try {
                    // Limpar chave corrompida
                    clientInfoBucket.delete();
                    log.info("✅ [RedisWS] Chave corrompida removida: {}", clientInfoKey);
                } catch (Exception cleanupError) {
                    log.warn("⚠️ [RedisWS] Erro ao limpar chave corrompida: {}", cleanupError.getMessage());
                }
            }

            // ✅ FALLBACK: Tentar a chave antiga (Hash) para compatibilidade
            RMap<String, String> clientMap = redisson.getMap(CLIENT_KEY_PREFIX + sessionId);
            if (clientMap.isEmpty()) {
                return Optional.empty();
            }

            ClientInfo fallbackClientInfo = ClientInfo.builder()
                    .sessionId(clientMap.get("sessionId"))
                    .summonerName(clientMap.get("summonerName"))
                    .ipAddress(clientMap.get("ipAddress"))
                    .connectedAt(Instant.parse(clientMap.get("connectedAt")))
                    .lastActivity(Instant.parse(clientMap.get("lastActivity")))
                    .userAgent(clientMap.get("userAgent"))
                    .build();

            return Optional.of(fallbackClientInfo);

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

            // ✅ CORREÇÃO: Extender TTL apenas da chave unificada
            clientMap.expire(SESSION_TTL_SECONDS, TimeUnit.SECONDS);

            String summonerName = clientMap.get("summonerName");
            // ✅ CORREÇÃO: NÃO estender TTL de chaves ws:session: e ws:player: - são
            // redundantes!

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

            // ✅ CORREÇÃO: Deletar apenas chave unificada
            if (summonerOpt.isPresent()) {
                String summonerName = summonerOpt.get();
                // Deletar apenas client_info usando summonerName
                redisson.getBucket("ws:client_info:" + summonerName).delete();
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
     * ✅ NOVO: Lista todas as informações de clientes ativos.
     * Útil para confirmação de identidade.
     * 
     * @return Map de sessionId → ClientInfo
     */
    public Map<String, Object> getAllClientInfo() {
        try {
            Map<String, Object> allClients = new HashMap<>();

            // ✅ CORREÇÃO: Buscar todas as chaves ws:client_info:*
            Iterable<String> keys = redisson.getKeys().getKeysByPattern("ws:client_info:*");

            for (String key : keys) {
                RBucket<ClientInfo> clientInfoBucket = redisson.getBucket(key);
                ClientInfo clientInfo = clientInfoBucket.get();

                if (clientInfo != null && clientInfo.getSessionId() != null) {
                    // Criar Map com informações do cliente
                    Map<String, Object> clientData = new HashMap<>();
                    clientData.put("summonerName", clientInfo.getSummonerName());
                    clientData.put("ip", clientInfo.getIpAddress());
                    clientData.put("connectedAt", clientInfo.getConnectedAt());
                    clientData.put("lastActivity", clientInfo.getLastActivity());
                    clientData.put("userAgent", clientInfo.getUserAgent());

                    allClients.put(clientInfo.getSessionId(), clientData);
                }
            }

            log.debug("✅ [RedisWSSession] {} clientes ativos listados", allClients.size());
            return allClients;

        } catch (Exception e) {
            log.error("❌ [RedisWSSession] Erro ao listar todos os clientes", e);
            return new HashMap<>();
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

            // ✅ CORREÇÃO: Buscar todas as chaves ws:client_info:*
            Iterable<String> keys = redisson.getKeys().getKeysByPattern("ws:client_info:*");

            for (String key : keys) {
                RBucket<ClientInfo> bucket = redisson.getBucket(key);
                ClientInfo clientInfo = bucket.get();

                if (clientInfo != null && clientInfo.getSessionId() != null) {
                    sessions.put(clientInfo.getSessionId(), clientInfo.getSummonerName());
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

            // ✅ CORREÇÃO: Verificar se sessionId existe em alguma chave ws:client_info:*
            return getSummonerBySession(sessionId).isPresent();

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
            // ✅ CORREÇÃO: Verificar se jogador tem chave ws:client_info:
            String clientInfoKey = "ws:client_info:" + normalizedSummoner;
            return redisson.getBucket(clientInfoKey).isExists();

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
     * ✅ CORRIGIDO: Remove TODAS as chaves relacionadas à sessão para evitar
     * duplicações
     * 
     * @param sessionId ID da sessão WebSocket
     * @return true se removeu com sucesso, false caso contrário
     */
    public boolean removePlayerInfo(String sessionId) {
        try {
            if (sessionId == null) {
                return false;
            }

            log.debug("🧹 [RedisWS] Iniciando limpeza completa para sessionId: {}", sessionId);

            // ✅ CRÍTICO: Buscar summonerName antes de remover para limpar mapeamentos
            Optional<String> summonerOpt = getSummonerBySession(sessionId);
            String summonerName = summonerOpt.orElse(null);

            int removedCount = 0;

            // 1. Remover ws:player_info:{sessionId}
            String playerInfoKey = PLAYER_INFO_KEY_PREFIX + sessionId;
            if (redisson.getBucket(playerInfoKey).delete()) {
                removedCount++;
                log.debug("✅ [RedisWS] Removido: {}", playerInfoKey);
            }

            // 2. ✅ CORREÇÃO: Remover ws:client_info:{summonerName} (formato unificado)
            if (summonerName != null) {
                String clientInfoKey = "ws:client_info:" + summonerName;
                if (redisson.getBucket(clientInfoKey).delete()) {
                    removedCount++;
                    log.debug("✅ [RedisWS] Removido: {}", clientInfoKey);
                }
            }

            // 3. ✅ CORREÇÃO: Remover ws:client:{sessionId} (formato antigo Hash - limpeza)
            String clientKey = "ws:client:" + sessionId;
            if (redisson.getBucket(clientKey).delete()) {
                removedCount++;
                log.debug("✅ [RedisWS] Removido: {}", clientKey);
            }

            // ✅ CORREÇÃO: NÃO remover chaves ws:session: e ws:player: - são redundantes!
            // Apenas a chave ws:client_info:{summonerName} é necessária

            // 6. Remover PUUID constraint se existir (se método estiver disponível)
            // ✅ COMENTADO: Método getPuuidBySession e constante PUUID_TO_PLAYER_KEY_PREFIX
            // não estão implementados
            // TODO: Implementar se necessário para limpeza completa de PUUID constraints
            /*
             * try {
             * String puuid = getPuuidBySession(sessionId);
             * if (puuid != null && !puuid.isEmpty()) {
             * String puuidKey = PUUID_TO_PLAYER_KEY_PREFIX + puuid;
             * if (redisson.getBucket(puuidKey).delete()) {
             * removedCount++;
             * log.debug("✅ [RedisWS] Removido PUUID constraint: {}", puuidKey);
             * }
             * }
             * } catch (Exception e) {
             * log.debug("⚠️ [RedisWS] Erro ao remover PUUID constraint: {}",
             * e.getMessage());
             * }
             */

            if (removedCount > 0) {
                log.info(
                        "✅ [RedisWS] Limpeza completa realizada: {} chaves removidas para sessionId={}, summonerName={}",
                        removedCount, sessionId, summonerName);
                return true;
            } else {
                log.debug("ℹ️ [RedisWS] Nenhuma chave encontrada para remover: sessionId={}", sessionId);
                return false;
            }

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

    /**
     * ✅ NOVO: Atualiza timestamp da última confirmação de identidade
     */
    public void updateIdentityConfirmation(String sessionId) {
        try {
            // ✅ CORREÇÃO: Usar summonerName em vez de sessionId
            String summonerName = getSummonerBySession(sessionId).orElse(null);
            if (summonerName == null) {
                return;
            }

            String clientInfoKey = "ws:client_info:" + summonerName;
            RBucket<ClientInfo> clientBucket = redisson.getBucket(clientInfoKey);

            Optional<ClientInfo> clientInfoOpt = Optional.ofNullable(clientBucket.get());
            if (clientInfoOpt.isPresent()) {
                ClientInfo clientInfo = clientInfoOpt.get();

                // Atualizar timestamp
                ClientInfo updatedClientInfo = ClientInfo.builder()
                        .sessionId(clientInfo.getSessionId())
                        .summonerName(clientInfo.getSummonerName())
                        .ipAddress(clientInfo.getIpAddress())
                        .connectedAt(clientInfo.getConnectedAt())
                        .lastActivity(Instant.now()) // lastActivity atualizado
                        .userAgent(clientInfo.getUserAgent())
                        .build();

                clientBucket.set(updatedClientInfo, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
                log.debug("✅ [RedisWSSession] Timestamp de confirmação atualizado: {}", sessionId);
            }

        } catch (Exception e) {
            log.error("❌ [RedisWSSession] Erro ao atualizar timestamp de confirmação: {}", sessionId, e);
        }
    }

    /**
     * ✅ CORREÇÃO: Método descontinuado - usar registerSession() em vez disso
     * Este método não deveria ser usado pois cria ClientInfo com summonerName
     * "unknown"
     */
    @Deprecated
    public void storeClientInfoDirect(String sessionId, String ipAddress, String userAgent) {
        log.warn("⚠️ [RedisWS] storeClientInfoDirect() é deprecated - usar registerSession() em vez disso");
        // Não fazer nada - deixar registerSession() gerenciar a criação
    }
}
