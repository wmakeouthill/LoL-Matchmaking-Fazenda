package br.com.lolmatchmaking.backend.websocket;

import br.com.lolmatchmaking.backend.service.redis.RedisWebSocketSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

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

        log.info("✅ [SessionRegistry] Jogador registrado: {} → {} (Redis: {})",
                normalizedName, sessionId, registered ? "OK" : "FALHOU");
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
