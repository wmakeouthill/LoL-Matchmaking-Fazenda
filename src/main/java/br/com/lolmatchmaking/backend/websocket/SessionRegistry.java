package br.com.lolmatchmaking.backend.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void add(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    public void remove(String id) {
        sessions.remove(id);
    }

    public WebSocketSession get(String id) {
        return sessions.get(id);
    }

    public Collection<WebSocketSession> all() {
        return sessions.values();
    }
}
