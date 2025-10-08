package br.com.lolmatchmaking.backend.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ✅ MELHORADO: Registry para mapear sessões WebSocket
 * - sessionId → WebSocketSession (para broadcast geral)
 * - summonerName → sessionId (para envio específico por jogador)
 */
@Component
public class SessionRegistry {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // ✅ NOVO: Mapeamento summonerName → sessionId para envio específico
    private final Map<String, String> playerToSession = new ConcurrentHashMap<>();

    public void add(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    /**
     * ✅ NOVO: Registra associação summonerName → sessionId
     */
    public void registerPlayer(String summonerName, String sessionId) {
        if (summonerName != null && sessionId != null) {
            String normalizedName = summonerName.toLowerCase();
            playerToSession.put(normalizedName, sessionId);
            System.out.println("🔗 [SessionRegistry] REGISTRADO: " + normalizedName + " → " + sessionId);
            System.out.println("📊 [SessionRegistry] Total mapeamentos: " + playerToSession.size());
            System.out.println("📊 [SessionRegistry] Mapeamentos atuais: " + playerToSession);
        }
    }

    /**
     * ✅ NOVO: Remove associação summonerName → sessionId
     */
    public void unregisterPlayer(String summonerName) {
        if (summonerName != null) {
            playerToSession.remove(summonerName.toLowerCase());
        }
    }

    public void remove(String id) {
        sessions.remove(id);
        // Remover também do mapa playerToSession
        playerToSession.values().removeIf(sessionId -> sessionId.equals(id));
    }

    public WebSocketSession get(String id) {
        return sessions.get(id);
    }

    /**
     * ✅ NOVO: Busca sessão por summonerName
     */
    public Optional<WebSocketSession> getByPlayer(String summonerName) {
        if (summonerName == null)
            return Optional.empty();

        String sessionId = playerToSession.get(summonerName.toLowerCase());
        if (sessionId == null)
            return Optional.empty();

        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * ✅ NOVO: Busca sessões de múltiplos jogadores
     * Usado para enviar eventos apenas para jogadores específicos (ex: os 10 da
     * partida)
     */
    public Collection<WebSocketSession> getByPlayers(Collection<String> summonerNames) {
        if (summonerNames == null || summonerNames.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        return summonerNames.stream()
                .map(this::getByPlayer)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(java.util.stream.Collectors.toList());
    }

    public Collection<WebSocketSession> all() {
        return sessions.values();
    }
}
