package br.com.lolmatchmaking.backend.websocket;

import br.com.lolmatchmaking.backend.service.redis.RedisWebSocketSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * ✅ ZERO CACHE LOCAL: Registry para mapear sessões WebSocket
 * 
 * ANTES: ConcurrentHashMaps perdiam dados em reinícios do backend
 * DEPOIS: Redis mantém sessões, permite reconexão transparente
 * 
 * PADRÃO DE INTEGRAÇÃO:
 * 1. Redis é a ÚNICA fonte da verdade (registerPlayer, getByPlayer)
 * 2. ❌ REMOVIDO: ConcurrentHashMap local (causava sessões fantasmas)
 * 3. WebSocketSession não é serializável → Redis mantém apenas mapeamentos
 * (sessionId ↔ summonerName), WebSocketSession obtido via
 * MatchmakingWebSocketService
 * 
 * FLUXO DE RECONEXÃO:
 * - Backend reinicia → Redis mantém estado
 * - Electron reconecta → registerPlayer restaura do Redis
 * - Eventos chegam corretamente ao jogador
 * 
 * @see RedisWebSocketSessionService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRegistry {

    private final RedisWebSocketSessionService redisWSSession;
    private final RedisTemplate<String, Object> redisTemplate;
    private final br.com.lolmatchmaking.backend.service.UnifiedLogService unifiedLogService;
    private final org.springframework.context.ApplicationContext applicationContext;

    // ❌ REMOVIDO: Cache local que causava sessões fantasmas
    // WebSocketSession será obtido via MatchmakingWebSocketService quando
    // necessário

    // ✅ REMOVIDO: Substituído completamente por Redis
    // playerToSession agora está APENAS no Redis (single source of truth)

    /**
     * ❌ REMOVIDO: Não precisa mais adicionar no cache local
     * WebSocketSession é gerenciado pelo MatchmakingWebSocketService
     */
    public void add(WebSocketSession session) {
        // ✅ REDIS ONLY: Sessão será registrada quando summoner for identificado
        log.debug("🔄 [SessionRegistry] Sessão {} adicionada (sem cache local)", session.getId());
    }

    /**
     * ❌ REMOVIDO: Não há mais cache local para verificar
     */
    public boolean hasSession(String sessionId) {
        // ✅ REDIS ONLY: Verificar se existe no Redis
        return redisWSSession.getSummonerBySession(sessionId).isPresent();
    }

    /**
     * ✅ MIGRADO PARA REDIS: Registra associação summonerName → sessionId
     * 
     * CRÍTICO: Este método é chamado após identificação do jogador.
     * Redis mantém mapeamento para sobreviver a reinícios do backend.
     * 
     * @param summonerName Nome do invocador
     * @param sessionId    ID da sessão WebSocket
     */
    public void registerPlayer(String summonerName, String sessionId) {
        if (summonerName == null || sessionId == null) {
            log.warn("⚠️ [SessionRegistry] Tentativa de registrar com dados inválidos");
            return;
        }

        String normalizedName = summonerName.toLowerCase().trim();

        // ✅ CORRIGIDO: Verificar se já está registrado para evitar logs duplicados
        Optional<String> existingSessionId = redisWSSession.getSessionBySummoner(normalizedName);
        if (existingSessionId.isPresent() && existingSessionId.get().equals(sessionId)) {
            log.debug("🔄 [SessionRegistry] Jogador {} já está registrado com sessão {}, pulando registro duplicado",
                    normalizedName, sessionId);
            return;
        }

        // ✅ REDIS ONLY: Não há mais cache local para limpar
        // Redis já gerencia sessões antigas automaticamente

        // ✅ REDIS ONLY: WebSocketSession obtido via MatchmakingWebSocketService quando
        // necessário
        // Por enquanto, usar valores padrão (será melhorado posteriormente)
        String ipAddress = "unknown";
        String userAgent = "unknown";

        boolean registered = redisWSSession.registerSession(sessionId, normalizedName, ipAddress, userAgent);

        if (!registered) {
            log.error("❌ [SessionRegistry] Falha ao registrar no Redis: {} → {}", normalizedName, sessionId);
        }

        log.info("✅ [Player-Sessions] [ELECTRON→BACKEND] Jogador registrado: {} → {} (Redis: {})",
                normalizedName, sessionId, registered ? "OK" : "FALHOU");

        // ✅ NOVO: Enviar log para Electron se houver sessões registradas
        if (unifiedLogService.hasRegisteredPlayerSessionLogSessions()) {
            unifiedLogService.sendPlayerSessionInfoLog("[Player-Sessions] [ELECTRON→BACKEND]",
                    "Jogador registrado: %s → %s (Redis: %s)", normalizedName, sessionId, registered ? "OK" : "FALHOU");
        }
    }

    /**
     * ✅ MIGRADO PARA REDIS: Remove associação summonerName → sessionId
     */
    public void unregisterPlayer(String summonerName) {
        if (summonerName == null) {
            return;
        }

        String normalizedName = summonerName.toLowerCase().trim();

        // Buscar sessionId antes de remover
        Optional<String> sessionIdOpt = redisWSSession.getSessionBySummoner(normalizedName);

        if (sessionIdOpt.isPresent()) {
            // ✅ REDIS ONLY
            redisWSSession.removeSession(sessionIdOpt.get());
            log.info("🗑️ [SessionRegistry] Jogador desregistrado do Redis: {}", normalizedName);
        }
    }

    /**
     * ✅ MIGRADO PARA REDIS: Remove sessão WebSocket
     */
    public void remove(String id) {
        // ✅ REDIS ONLY
        redisWSSession.removeSession(id);
        // ❌ REMOVIDO: Não há mais cache local para remover

        log.info("🗑️ [SessionRegistry] Sessão removida: {}", id);
    }

    /**
     * ✅ NOVO: Remove sessão por summonerName (para limpeza de conflitos PUUID)
     */
    public void removeBySummoner(String summonerName) {
        if (summonerName == null || summonerName.isEmpty()) {
            return;
        }

        String normalizedName = summonerName.toLowerCase().trim();

        // Buscar sessionId no Redis
        Optional<String> sessionIdOpt = redisWSSession.getSessionBySummoner(normalizedName);
        if (sessionIdOpt.isPresent()) {
            String sessionId = sessionIdOpt.get();

            // Remover do Redis
            redisWSSession.removeSession(sessionId);

            // ❌ REMOVIDO: Não há mais cache local para remover

            log.info("🗑️ [SessionRegistry] Sessão removida por summoner: {} → sessionId={}",
                    normalizedName, sessionId);
        } else {
            log.debug("🔍 [SessionRegistry] Nenhuma sessão encontrada para summoner: {}", normalizedName);
        }
    }

    public WebSocketSession get(String id) {
        // ❌ REMOVIDO: Cache local não existe mais
        // TODO: Obter WebSocketSession via MatchmakingWebSocketService
        return null;
    }

    /**
     * ✅ MIGRADO PARA REDIS: Busca sessão por summonerName
     * 
     * CRÍTICO: Este método é usado para enviar eventos a jogadores específicos.
     * Redis permite encontrar jogador mesmo após backend reiniciar.
     * 
     * Exemplo:
     * - Backend precisa enviar "draft_confirmed" para Player5
     * - getByPlayer("Player5") → busca sessionId no Redis
     * - Retorna WebSocketSession para envio
     * 
     * @param summonerName Nome do invocador
     * @return Optional com WebSocketSession se online, empty se offline
     */
    public Optional<WebSocketSession> getByPlayer(String summonerName) {
        if (summonerName == null) {
            return Optional.empty();
        }

        String normalizedName = summonerName.toLowerCase().trim();

        // ✅ REDIS ONLY: Single source of truth (sem fallback HashMap!)
        Optional<String> sessionIdOpt = redisWSSession.getSessionBySummoner(normalizedName);

        if (sessionIdOpt.isPresent()) {
            String sessionId = sessionIdOpt.get();
            // ✅ NOVO: Obter WebSocketSession via MatchmakingWebSocketService
            WebSocketSession session = getWebSocketService().getSession(sessionId);

            if (session != null) {
                log.debug("🔍 [SessionRegistry] Sessão encontrada (Redis): {} → {}", normalizedName, sessionId);
                return Optional.of(session);
            } else {
                log.warn(
                        "⚠️ [SessionRegistry] SessionId no Redis mas WebSocketSession não existe localmente: {} (jogador offline?)",
                        sessionId);
                // Limpar entrada inválida do Redis
                redisWSSession.removeSession(sessionId);
            }
        }

        log.debug("❌ [SessionRegistry] Sessão NÃO encontrada para: {}", normalizedName);
        return Optional.empty();
    }

    /**
     * ✅ MIGRADO PARA REDIS: Busca sessões de múltiplos jogadores
     * 
     * Usado para enviar eventos apenas para jogadores específicos (ex: os 10 da
     * partida).
     * Redis garante que encontramos jogadores mesmo após reinício.
     * 
     * @param summonerNames Lista de nomes de invocadores
     * @return Collection de WebSocketSessions online
     */
    public Collection<WebSocketSession> getByPlayers(Collection<String> summonerNames) {
        if (summonerNames == null || summonerNames.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        return summonerNames.stream()
                .map(this::getByPlayer)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    /**
     * Retorna todas as sessões WebSocket ativas.
     * Usado para broadcast geral.
     */
    public Collection<WebSocketSession> all() {
        // ✅ NOVO: Obter todas as sessões via MatchmakingWebSocketService
        return getWebSocketService().getAllActiveSessions();
    }

    /**
     * ✅ NOVO: Obter MatchmakingWebSocketService via ApplicationContext
     */
    private MatchmakingWebSocketService getWebSocketService() {
        return applicationContext.getBean(MatchmakingWebSocketService.class);
    }

    /**
     * ✅ NOVO: Atualiza heartbeat de uma sessão.
     * Mantém sessão viva no Redis e extende TTL.
     * 
     * @param sessionId ID da sessão WebSocket
     */
    public void updateHeartbeat(String sessionId) {
        if (sessionId != null) {
            redisWSSession.updateHeartbeat(sessionId);
        }
    }

    /**
     * ✅ NOVO: Verifica se jogador está online.
     * 
     * @param summonerName Nome do invocador
     * @return true se online, false se offline
     */
    public boolean isPlayerOnline(String summonerName) {
        if (summonerName == null) {
            return false;
        }
        return redisWSSession.isPlayerOnline(summonerName.toLowerCase());
    }

    /**
     * ✅ NOVO: Invalida cache de verificação de sessões ativas via Redis.
     * 
     * Chamado quando uma nova sessão se conecta para garantir que
     * os @Scheduled tasks verifiquem novamente.
     * Usa Redis para notificar todos os serviços sem dependência circular.
     */
    public void invalidateSessionCache() {
        try {
            // ✅ NOVO: Usar Redis para invalidar cache em todos os serviços
            String cacheInvalidationKey = "cache:session_invalidation";
            long timestamp = System.currentTimeMillis();

            // Publicar timestamp de invalidação no Redis
            redisTemplate.opsForValue().set(cacheInvalidationKey, timestamp, Duration.ofMinutes(5));

            log.debug("🔄 [SessionRegistry] Cache de sessões invalidado via Redis (timestamp: {})", timestamp);
        } catch (Exception e) {
            log.error("❌ [SessionRegistry] Erro ao invalidar cache via Redis", e);
        }
    }

    /**
     * ✅ NOVO: Retorna o número de sessões WebSocket ativas.
     * 
     * Usado para verificar se há jogadores conectados antes de executar
     * processamento desnecessário (ex: @Scheduled tasks).
     * 
     * @return Número de sessões ativas
     */
    public int getActiveSessionCount() {
        try {
            // ✅ REDIS ONLY: Contar apenas sessões identificadas no Redis
            Map<String, Object> allClientInfo = redisWSSession.getAllClientInfo();
            int identifiedSessions = allClientInfo.size();

            log.debug("📊 [SessionRegistry] Sessões ativas: {} (Redis: {})",
                    identifiedSessions, identifiedSessions);

            return identifiedSessions;

        } catch (Exception e) {
            log.error("❌ [SessionRegistry] Erro ao contar sessões ativas", e);
            // ❌ REMOVIDO: Não há mais cache local para fallback
            return 0;
        }
    }

    /**
     * ✅ NOVO: Busca summonerName por sessionId.
     * Útil para identificar jogador a partir da sessão WebSocket.
     * 
     * @param sessionId ID da sessão WebSocket
     * @return Nome do invocador, ou null se não encontrado
     */
    public String getSummonerBySession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return redisWSSession.getSummonerBySession(sessionId).orElse(null);
    }
}
