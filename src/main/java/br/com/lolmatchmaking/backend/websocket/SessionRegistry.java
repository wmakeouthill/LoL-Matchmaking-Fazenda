package br.com.lolmatchmaking.backend.websocket;

import br.com.lolmatchmaking.backend.service.redis.RedisWebSocketSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ✅ MIGRADO PARA REDIS: Registry para mapear sessões WebSocket
 * 
 * ANTES: ConcurrentHashMaps perdiam dados em reinícios do backend
 * DEPOIS: Redis mantém sessões, permite reconexão transparente
 * 
 * PADRÃO DE INTEGRAÇÃO:
 * 1. Redis é a fonte da verdade (registerPlayer, getByPlayer)
 * 2. ConcurrentHashMap mantido por backward compatibility (sessions)
 * 3. WebSocketSession não é serializável → só Redis mantém mapeamentos
 * (sessionId ↔ summonerName)
 * 
 * FLUXO DE RECONEXÃO:
 * - Backend reinicia → ConcurrentHashMaps vazios
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

    // Cache local (WebSocketSession não é serializável para Redis)
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // ✅ REMOVIDO: Substituído completamente por Redis
    // playerToSession agora está APENAS no Redis (single source of truth)

    public void add(WebSocketSession session) {
        sessions.put(session.getId(), session);
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

        String normalizedName = summonerName.toLowerCase();

        // ✅ REDIS FIRST: Fonte da verdade
        WebSocketSession session = sessions.get(sessionId);
        String ipAddress = "unknown";
        String userAgent = "unknown";

        if (session != null && session.getRemoteAddress() != null) {
            ipAddress = session.getRemoteAddress().getAddress().getHostAddress();
            if (session.getHandshakeHeaders() != null) {
                userAgent = session.getHandshakeHeaders().getFirst("User-Agent");
                if (userAgent == null)
                    userAgent = "unknown";
            }
        }

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

        String normalizedName = summonerName.toLowerCase();

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
        sessions.remove(id);

        log.info("🗑️ [SessionRegistry] Sessão removida: {}", id);
    }

    /**
     * ✅ NOVO: Remove sessão por summonerName (para limpeza de conflitos PUUID)
     */
    public void removeBySummoner(String summonerName) {
        if (summonerName == null || summonerName.isEmpty()) {
            return;
        }

        String normalizedName = summonerName.toLowerCase();

        // Buscar sessionId no Redis
        Optional<String> sessionIdOpt = redisWSSession.getSessionBySummoner(normalizedName);
        if (sessionIdOpt.isPresent()) {
            String sessionId = sessionIdOpt.get();

            // Remover do Redis
            redisWSSession.removeSession(sessionId);

            // Remover do cache local
            sessions.remove(sessionId);

            log.info("🗑️ [SessionRegistry] Sessão removida por summoner: {} → sessionId={}",
                    normalizedName, sessionId);
        } else {
            log.debug("🔍 [SessionRegistry] Nenhuma sessão encontrada para summoner: {}", normalizedName);
        }
    }

    public WebSocketSession get(String id) {
        return sessions.get(id);
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

        String normalizedName = summonerName.toLowerCase();

        // ✅ REDIS ONLY: Single source of truth (sem fallback HashMap!)
        Optional<String> sessionIdOpt = redisWSSession.getSessionBySummoner(normalizedName);

        if (sessionIdOpt.isPresent()) {
            String sessionId = sessionIdOpt.get();
            WebSocketSession session = sessions.get(sessionId);

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
        return sessions.values();
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
            // Contar sessões no cache local (WebSocketSession ativas)
            int localSessions = sessions.size();

            // Verificar se há sessões identificadas no Redis
            Map<String, Object> allClientInfo = redisWSSession.getAllClientInfo();
            int identifiedSessions = allClientInfo.size();

            // Retornar o maior valor (pode haver sessões não identificadas)
            int totalSessions = Math.max(localSessions, identifiedSessions);

            log.debug("📊 [SessionRegistry] Sessões ativas: {} (local: {}, identificadas: {})",
                    totalSessions, localSessions, identifiedSessions);

            return totalSessions;

        } catch (Exception e) {
            log.error("❌ [SessionRegistry] Erro ao contar sessões ativas", e);
            // Em caso de erro, retornar contagem local como fallback
            return sessions.size();
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
