package br.com.lolmatchmaking.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            org.springframework.web.socket.WebSocketHandler wsHandler, Map<String, Object> attributes)
            throws Exception {
        try {
            String expected = System.getenv("WS_GATEWAY_TOKEN");
            if (expected == null || expected.isBlank()) {
                // no token configured -> allow (development-friendly)
                log.debug("WS auth: no WS_GATEWAY_TOKEN configured, allowing connection");
                return true;
            }

            var headers = request.getHeaders();
            var auth = headers.getFirst("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                log.warn("WS auth failed: missing Authorization header");
                return false;
            }
            String token = auth.substring("Bearer ".length());
            if (!expected.equals(token)) {
                log.warn("WS auth failed: invalid token");
                return false;
            }
            log.debug("WS auth success");
            return true;
        } catch (Exception e) {
            log.error("WS auth interceptor error", e);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            org.springframework.web.socket.WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
