package br.com.lolmatchmaking.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.lang.NonNull;

import br.com.lolmatchmaking.backend.websocket.CoreWebSocketHandler;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;

@Slf4j
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CoreWebSocketHandler coreWebSocketHandler;
    private final MatchmakingWebSocketService matchmakingWebSocketService;

    public WebSocketConfig(CoreWebSocketHandler coreWebSocketHandler, MatchmakingWebSocketService matchmakingWebSocketService) {
        this.coreWebSocketHandler = coreWebSocketHandler;
        this.matchmakingWebSocketService = matchmakingWebSocketService;
    }

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        // ✅ CORREÇÃO COMPLETA: WebSocket simples sem SockJS que estava causando conflito
        registry.addHandler(coreWebSocketHandler, "/api/ws")
                .setAllowedOriginPatterns("*"); // Usar allowedOriginPatterns em vez de allowedOrigins

        // ✅ OPCIONAL: WebSocket alternativo sem /api (se necessário)
        registry.addHandler(coreWebSocketHandler, "/ws")
                .setAllowedOriginPatterns("*");

        // Handler para clientes Electron (RPC LCU) em /client-ws
        registry.addHandler(matchmakingWebSocketService, "/client-ws")
                .setAllowedOriginPatterns("*");

        log.info("🔌 WebSocket registrado em: /api/ws e /ws");
    }
}
