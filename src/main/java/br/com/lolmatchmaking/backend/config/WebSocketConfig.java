package br.com.lolmatchmaking.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.lang.NonNull;

import br.com.lolmatchmaking.backend.websocket.CoreWebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CoreWebSocketHandler coreWebSocketHandler;

    public WebSocketConfig(CoreWebSocketHandler coreWebSocketHandler) {
        this.coreWebSocketHandler = coreWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        registry.addHandler(coreWebSocketHandler, "/ws")
                .setAllowedOrigins("*");
    }
}
