package br.com.lolmatchmaking.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class StompWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable a simple in-memory message broker
        config.enableSimpleBroker("/topic", "/queue");
        
        // Set the application destination prefix
        config.setApplicationDestinationPrefixes("/app");
        
        log.info("📡 [StompWebSocketConfig] Message broker configurado: /topic, /queue");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Register STOMP endpoint
        registry.addEndpoint("/ws-stomp")
                .setAllowedOriginPatterns("*")
                .withSockJS();
        
        // Register STOMP endpoint without SockJS
        registry.addEndpoint("/ws-stomp")
                .setAllowedOriginPatterns("*");
        
        log.info("🔌 [StompWebSocketConfig] STOMP endpoints registrados: /ws-stomp");
    }
}
