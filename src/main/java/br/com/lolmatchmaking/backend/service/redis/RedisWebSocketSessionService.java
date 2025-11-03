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
    // ✅ REMOVIDO: Chaves antigas não são mais usadas - tudo via ws:client_info:
    // private static final String SESSION_KEY_PREFIX = "ws:session:";
    // private static final String PLAYER_KEY_PREFIX = "ws:player:";
    // private static final String CLIENT_KEY_PREFIX = "ws:client:";

    // ✅ NOVO: Unificação completa - uma chave por jogador com todos os dados
    private static final String CLIENT_INFO_UNIFIED_PREFIX = "ws:client_info:"; // summonerName → ClientInfo (TODOS OS
                                                                                // DADOS)
    private static final String SESSION_MAPPING_PREFIX = "ws:session_mapping:"; // randomSessionId → customSessionId
    private static final String CUSTOM_SESSION_MAPPING_PREFIX = "ws:custom_session_mapping:"; // customSessionId →
                                                                                              // randomSessionId

    /**
     * ✅ NOVO: Informações completas do cliente WebSocket (unificado)
     * Agora inclui TODOS os dados do jogador em uma única estrutura
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClientInfo {
        // Dados de sessão
        private String sessionId;
        private String summonerName;
        private String ipAddress;
        private Instant connectedAt;
        private Instant lastActivity;
        private String userAgent;

        // ✅ NOVO: Dados do jogador
        private String puuid;
        private String summonerId;
        private Integer profileIconId;
        private Integer summonerLevel;
        private String gameName;
        private String tagLine;
        private String tier;
        private String division;

        // ✅ NOVO: CustomSessionId (imutável, baseado no jogador)
        private String customSessionId;
    }

    /**
     * Registra nova sessão WebSocket com bidirectional mapping.
     * 
     * Cria 3 chaves Redis:
     * 1. ws:session:{sessionId} → summonerName (enviar eventos por sessionId)
     * 2. ws:player:{summonerName} → sessionId (buscar sessão por player)
     * 3. ws:client:{sessionId} → ClientInfo (metadata completa)
     * 
     * @param sessionId       ID único da sessão WebSocket (random)
     * @param summonerName    Nome do invocador
     * @param ipAddress       IP do cliente
     * @param userAgent       User-Agent do navegador/Electron
     * @param customSessionId CustomSessionId (opcional, adicionado depois via
     *                        updatePlayerData se não fornecido)
     * @return true se registrou com sucesso, false se já existia
     */
    public boolean registerSession(String sessionId, String summonerName, String ipAddress, String userAgent) {
        return registerSession(sessionId, summonerName, ipAddress, userAgent, null);
    }

    /**
     * ✅ NOVO: Sobrecarga com customSessionId
     */
    public boolean registerSession(String sessionId, String summonerName, String ipAddress, String userAgent,
            String customSessionId) {
        try {
            if (sessionId == null || sessionId.isBlank() || summonerName == null || summonerName.isBlank()) {
                log.warn(
                        "⚠️ [RedisWS] Tentativa de registrar sessão com dados inválidos: sessionId={}, summonerName={}",
                        sessionId, summonerName);
                return false;
            }

            String normalizedSummoner = normalizeSummonerName(summonerName);

            // ✅ CRÍTICO: VERIFICAR E REMOVER SESSÕES DUPLICADAS ANTES DE REGISTRAR
            // 1. Verificar se já existe sessão para este summoner via ws:client_info:
            String clientInfoKey = CLIENT_INFO_UNIFIED_PREFIX + normalizedSummoner;
            RBucket<ClientInfo> existingClientInfoBucket = redisson.getBucket(clientInfoKey);
            ClientInfo existingClientInfo = existingClientInfoBucket.get();

            if (existingClientInfo != null && existingClientInfo.getSessionId() != null
                    && !existingClientInfo.getSessionId().equals(sessionId)) {

                String oldSessionId = existingClientInfo.getSessionId();
                String oldCustomSessionId = existingClientInfo.getCustomSessionId();

                log.warn("🔄 [RedisWS] RECONEXÃO DETECTADA! Jogador {} já tem sessão ativa: {} (custom: {})",
                        normalizedSummoner, oldSessionId, oldCustomSessionId);

                // ✅ CRÍTICO: ATUALIZAR sessionId mantendo todos os dados do jogador
                // Princípio: Backend mantém UMA entrada por jogador, apenas atualiza o
                // sessionId
                log.info("🔄 [RedisWS] ATUALIZANDO sessionId: {} → {} (mantendo player_info)",
                        oldSessionId, sessionId);

                // Remover APENAS os mapeamentos antigos (não os dados do jogador)
                // 1. Remover mapeamento customSessionId → randomSessionId antigo (se existir)
                if (oldCustomSessionId != null && !oldCustomSessionId.isBlank()) {
                    String customMappingKey = CUSTOM_SESSION_MAPPING_PREFIX + oldCustomSessionId;
                    RBucket<String> customMappingBucket = redisson.getBucket(customMappingKey);
                    customMappingBucket.delete();
                    log.debug("🗑️ [RedisWS] Mapeamento customSessionId removido: {}", customMappingKey);
                }

                // 2. Remover mapeamento randomSessionId → customSessionId antigo (se existir)
                String sessionMappingKey = SESSION_MAPPING_PREFIX + oldSessionId;
                RBucket<String> sessionMappingBucket = redisson.getBucket(sessionMappingKey);
                sessionMappingBucket.delete();
                log.debug("🗑️ [RedisWS] Mapeamento sessionId removido: {}", sessionMappingKey);

                // ✅ Atualizar ClientInfo com novo sessionId mantendo TODOS os dados do jogador
                ClientInfo updatedClientInfo = ClientInfo.builder()
                        .sessionId(sessionId) // ← NOVO sessionId
                        .summonerName(existingClientInfo.getSummonerName()) // ← MANTER
                        .ipAddress(ipAddress) // ← ATUALIZAR (pode ter mudado)
                        .connectedAt(existingClientInfo.getConnectedAt()) // ← MANTER (primeira conexão)
                        .lastActivity(Instant.now()) // ← ATUALIZAR
                        .userAgent(userAgent) // ← ATUALIZAR (pode ter mudado)
                        .puuid(existingClientInfo.getPuuid()) // ← MANTER
                        .summonerId(existingClientInfo.getSummonerId()) // ← MANTER
                        .profileIconId(existingClientInfo.getProfileIconId()) // ← MANTER
                        .summonerLevel(existingClientInfo.getSummonerLevel()) // ← MANTER
                        .gameName(existingClientInfo.getGameName()) // ← MANTER
                        .tagLine(existingClientInfo.getTagLine()) // ← MANTER
                        .tier(existingClientInfo.getTier()) // ← MANTER
                        .division(existingClientInfo.getDivision()) // ← MANTER
                        .customSessionId(
                                customSessionId != null ? customSessionId : existingClientInfo.getCustomSessionId()) // ←
                                                                                                                     // ATUALIZAR
                                                                                                                     // se
                                                                                                                     // fornecido
                        .build();

                // Salvar ClientInfo atualizado
                RBucket<ClientInfo> clientInfoBucket = redisson.getBucket(clientInfoKey);
                clientInfoBucket.set(updatedClientInfo, Duration.ofHours(1));

                log.info("✅ [RedisWS] ClientInfo ATUALIZADO: {} → novo sessionId={}, dados do jogador PRESERVADOS",
                        normalizedSummoner, sessionId);

                // Retornar true indicando que foi uma atualização (não uma nova sessão)
                return true;
            }

            // 2. Verificar se este sessionId já está registrado para outro jogador
            // Buscar em todas as sessões para ver se já existe
            // (TODO: Otimizar isso com um índice reverso se necessário)

            // ✅ REGISTRAR: Nova sessão (primeira vez ou sem ClientInfo anterior)
            // ✅ NOVO: Usar apenas uma chave unificada ws:client_info:{summonerName}

            ClientInfo clientInfo = ClientInfo.builder()
                    .sessionId(sessionId)
                    .summonerName(summonerName) // ✅ CRÍTICO: Usar summonerName ORIGINAL, não normalizado
                    .ipAddress(ipAddress)
                    .connectedAt(Instant.now())
                    .lastActivity(Instant.now())
                    .userAgent(userAgent)
                    .customSessionId(customSessionId)
                    // Nota: puuid, summonerId, etc. serão atualizados depois via updatePlayerData()
                    .build();

            // ✅ CRÍTICO: Usar normalizedSummoner na CHAVE, mas summonerName original no
            // ClientInfo
            RBucket<ClientInfo> clientInfoBucket = redisson.getBucket(clientInfoKey);
            clientInfoBucket.set(clientInfo, Duration.ofHours(1));

            log.info("✅ [RedisWS] Sessão registrada: {} → {} (IP: {}) [chave: {}]",
                    sessionId, summonerName, ipAddress, normalizedSummoner);

            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao registrar sessão: sessionId={}, summonerName={}",
                    sessionId, summonerName, e);
            return false;
        }
    }

    /**
     * ✅ NOVO: Atualiza dados completos do jogador na sessão.
     * Chamado após electron_identify para incluir puuid, profileIconId, etc.
     * 
     * @param summonerName    Nome do invocador
     * @param puuid           PUUID do jogador
     * @param summonerId      Summoner ID
     * @param profileIconId   ID do ícone de perfil
     * @param summonerLevel   Nível do invocador
     * @param gameName        Game Name (nome do jogador)
     * @param tagLine         Tag Line
     * @param customSessionId CustomSessionId (imutável baseado no jogador)
     */
    public boolean updatePlayerData(String summonerName, String puuid, String summonerId,
            Integer profileIconId, Integer summonerLevel,
            String gameName, String tagLine, String customSessionId) {
        try {
            if (summonerName == null || summonerName.isBlank()) {
                return false;
            }

            String normalizedSummoner = normalizeSummonerName(summonerName);
            String clientInfoKey = CLIENT_INFO_UNIFIED_PREFIX + normalizedSummoner;
            RBucket<ClientInfo> clientInfoBucket = redisson.getBucket(clientInfoKey);

            ClientInfo existingClientInfo = clientInfoBucket.get();
            if (existingClientInfo == null) {
                log.warn("⚠️ [RedisWS] Tentativa de atualizar dados de sessão inexistente: {}", normalizedSummoner);
                return false;
            }

            // Atualizar dados do jogador mantendo dados de sessão
            ClientInfo updatedClientInfo = ClientInfo.builder()
                    .sessionId(existingClientInfo.getSessionId())
                    .summonerName(existingClientInfo.getSummonerName())
                    .ipAddress(existingClientInfo.getIpAddress())
                    .connectedAt(existingClientInfo.getConnectedAt())
                    .lastActivity(existingClientInfo.getLastActivity())
                    .userAgent(existingClientInfo.getUserAgent())
                    .puuid(puuid)
                    .summonerId(summonerId)
                    .profileIconId(profileIconId)
                    .summonerLevel(summonerLevel)
                    .gameName(gameName)
                    .tagLine(tagLine)
                    .customSessionId(
                            customSessionId != null ? customSessionId : existingClientInfo.getCustomSessionId())
                    .build();

            clientInfoBucket.set(updatedClientInfo, Duration.ofHours(1));
            log.info("✅ [RedisWS] Dados do jogador atualizados: {} (PUUID: {}...)",
                    normalizedSummoner, puuid != null ? puuid.substring(0, Math.min(8, puuid.length())) : "N/A");

            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao atualizar dados do jogador: {}", summonerName, e);
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
                // ✅ CRÍTICO: Sempre retornar randomSessionId (já está em clientInfo.sessionId)
                // customSessionId é usado apenas para lock/identificação, NÃO para envio
                // WebSocket
                String randomSessionId = clientInfo.getSessionId();

                // Log detalhado incluindo customSessionId se disponível
                String customSessionIdForLog = clientInfo.getCustomSessionId();
                if (customSessionIdForLog != null) {
                    log.debug("🔍 [RedisWS] Sessão encontrada: {} → randomSessionId={}, customSessionId={}",
                            normalizedSummoner, randomSessionId, customSessionIdForLog);
                } else {
                    log.debug("🔍 [RedisWS] Sessão encontrada: {} → randomSessionId={}",
                            normalizedSummoner, randomSessionId);
                }

                return Optional.of(randomSessionId);
            }

            log.warn("❌ [RedisWS] Sessão NÃO encontrada para: original=[{}], normalized=[{}], key=[{}]",
                    summonerName, normalizedSummoner, clientInfoKey);
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

            // ✅ REMOVIDO: Chaves antigas não são mais usadas - tudo via ws:client_info:
            // Não há fallback necessário, pois todas as sessões são migradas
            log.debug("⚠️ [RedisWS] ClientInfo não encontrado e não há fallback: {}", sessionId);
            return Optional.empty();

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

            // ✅ NOVO: Buscar ClientInfo via sessionId
            // Primeiro, precisamos encontrar o summonerName pelo sessionId
            Optional<String> summonerOpt = getSummonerBySession(sessionId);
            if (!summonerOpt.isPresent()) {
                log.warn("⚠️ [RedisWS] Tentativa de heartbeat em sessão inexistente: {}", sessionId);
                return false;
            }

            String summonerName = summonerOpt.get();
            String clientInfoKey = CLIENT_INFO_UNIFIED_PREFIX + summonerName;
            RBucket<ClientInfo> clientInfoBucket = redisson.getBucket(clientInfoKey);

            ClientInfo clientInfo = clientInfoBucket.get();
            if (clientInfo == null) {
                log.warn("⚠️ [RedisWS] ClientInfo não encontrado para heartbeat: {}", sessionId);
                return false;
            }

            // Atualizar lastActivity
            clientInfo.setLastActivity(Instant.now());
            clientInfoBucket.set(clientInfo, Duration.ofHours(1));

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
     * ✅ REMOVIDO: storePlayerInfo deprecated - use updatePlayerData em vez disso
     * Dados do jogador são agora armazenados diretamente no ClientInfo via
     * ws:client_info:{summonerName}
     */

    /**
     * ✅ REMOVIDO: getPlayerInfo deprecated - use getClientInfo em vez disso
     */

    /**
     * ✅ REMOVIDO: removePlayerInfo deprecated - dados são removidos automaticamente
     * pelo removeSession
     */

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
        // ✅ CRÍTICO: Usar mesma normalização do customSessionId
        // para garantir consistência nas chaves Redis
        return summonerName.trim().toLowerCase()
                .replaceAll("[^a-z0-9_]", "_");
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
     * ✅ NOVO: Armazena mapeamento sessionId ↔ customSessionId (bidirecional)
     * 
     * Cria 2 chaves:
     * 1. ws:session_mapping:{randomSessionId} → customSessionId
     * 2. ws:custom_session_mapping:{customSessionId} → randomSessionId
     */
    public boolean storeSessionMapping(String randomSessionId, String customSessionId) {
        try {
            if (randomSessionId == null || customSessionId == null) {
                log.warn("⚠️ [RedisWS] Dados inválidos para storeSessionMapping");
                return false;
            }

            // 1. Forward: randomSessionId → customSessionId
            String forwardKey = SESSION_MAPPING_PREFIX + randomSessionId;
            RBucket<String> forwardBucket = redisson.getBucket(forwardKey);
            forwardBucket.set(customSessionId, SESSION_TTL_SECONDS, TimeUnit.SECONDS);

            // 2. Reverse: customSessionId → randomSessionId
            String reverseKey = CUSTOM_SESSION_MAPPING_PREFIX + customSessionId;
            RBucket<String> reverseBucket = redisson.getBucket(reverseKey);
            reverseBucket.set(randomSessionId, SESSION_TTL_SECONDS, TimeUnit.SECONDS);

            log.info("✅ [RedisWS] Mapeamento bidirecional armazenado: {} ↔ {}", randomSessionId, customSessionId);
            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao armazenar mapeamento: {} → {}", randomSessionId, customSessionId, e);
            return false;
        }
    }

    /**
     * ✅ NOVO: Recupera customSessionId do mapeamento
     */
    public Optional<String> getCustomSessionId(String randomSessionId) {
        try {
            if (randomSessionId == null) {
                return Optional.empty();
            }

            String key = SESSION_MAPPING_PREFIX + randomSessionId;
            RBucket<String> bucket = redisson.getBucket(key);
            String customSessionId = bucket.get();

            if (customSessionId != null) {
                log.debug("✅ [RedisWS] CustomSessionId recuperado: {} → {}", randomSessionId, customSessionId);
                return Optional.of(customSessionId);
            } else {
                log.debug("⚠️ [RedisWS] Mapeamento não encontrado para: {}", randomSessionId);
                return Optional.empty();
            }

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao recuperar mapeamento: {}", randomSessionId, e);
            return Optional.empty();
        }
    }

    /**
     * ✅ NOVO: Limpa mappings antigos deste customSessionId
     * Remove mappings bidirecionais anteriores quando o jogador reconecta
     * 
     * Limpa:
     * 1. ws:session_mapping:* → customSessionId (mapeamento direto)
     * 2. ws:custom_session_mapping:{customSessionId} → randomSessionId antigo
     * (mapeamento reverso)
     * 
     * Isso previne acumulação de chaves quando o jogador reconecta
     */
    public void cleanupOldMappingsForPlayer(String customSessionId) {
        try {
            if (customSessionId == null || customSessionId.isBlank()) {
                return;
            }

            log.info("🧹 [RedisWS] Limpando mappings antigos para customSessionId: {}", customSessionId);

            int removedCount = 0;

            // 1. Limpar forward mappings (randomSessionId → customSessionId)
            Iterable<String> forwardKeys = redisson.getKeys().getKeysByPattern(SESSION_MAPPING_PREFIX + "*");
            for (String forwardKey : forwardKeys) {
                try {
                    RBucket<String> bucket = redisson.getBucket(forwardKey);
                    String storedCustomSessionId = bucket.get();
                    if (customSessionId.equals(storedCustomSessionId)) {
                        bucket.delete();
                        removedCount++;
                        log.debug("🗑️ [RedisWS] Forward mapping antigo removido: {}", forwardKey);
                    }
                } catch (Exception e) {
                    log.debug("⚠️ [RedisWS] Erro ao processar forward key: {}", forwardKey);
                }
            }

            // 2. Limpar reverse mapping antigo (customSessionId → randomSessionId antigo)
            String reverseKey = CUSTOM_SESSION_MAPPING_PREFIX + customSessionId;
            RBucket<String> reverseBucket = redisson.getBucket(reverseKey);
            if (reverseBucket.isExists()) {
                reverseBucket.delete();
                removedCount++;
                log.debug("🗑️ [RedisWS] Reverse mapping antigo removido: {}", reverseKey);
            }

            if (removedCount > 0) {
                log.info("✅ [RedisWS] {} mappings antigos removidos para customSessionId: {}", removedCount,
                        customSessionId);
            } else {
                log.debug("✅ [RedisWS] Nenhum mapping antigo encontrado para customSessionId: {}", customSessionId);
            }

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao limpar mappings antigos para customSessionId: {}", customSessionId, e);
        }
    }

    /**
     * ✅ NOVO: Busca randomSessionId atual por customSessionId (imutável)
     * 
     * Usado quando o sistema conhece o customSessionId e precisa encontrar
     * a sessão WebSocket atual (randomSessionId) para enviar mensagens.
     * 
     * @param customSessionId CustomSessionId (ex: player_fzd_ratoso_fzd)
     * @return Optional com randomSessionId atual se encontrado, empty se offline
     */
    public Optional<String> getRandomSessionIdByCustom(String customSessionId) {
        try {
            if (customSessionId == null || customSessionId.isBlank()) {
                return Optional.empty();
            }

            // Buscar no índice reverso ws:custom_session_mapping:{customSessionId}
            String reverseKey = CUSTOM_SESSION_MAPPING_PREFIX + customSessionId;
            RBucket<String> reverseBucket = redisson.getBucket(reverseKey);
            String randomSessionId = reverseBucket.get();

            if (randomSessionId != null) {
                log.debug("🔍 [RedisWS] RandomSessionId atual encontrado via customSessionId: {} → {}",
                        customSessionId, randomSessionId);
                return Optional.of(randomSessionId);
            }

            log.debug("❌ [RedisWS] RandomSessionId NÃO encontrado para customSessionId: {}", customSessionId);
            return Optional.empty();

        } catch (Exception e) {
            log.error("❌ [RedisWS] Erro ao buscar randomSessionId por customSessionId: {}", customSessionId, e);
            return Optional.empty();
        }
    }

}
