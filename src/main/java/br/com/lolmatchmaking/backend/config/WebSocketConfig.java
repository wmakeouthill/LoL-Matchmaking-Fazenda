package br.com.lolmatchmaking.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.lang.NonNull;

import br.com.lolmatchmaking.backend.websocket.CoreWebSocketHandler;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;

@Slf4j
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

        private final CoreWebSocketHandler coreWebSocketHandler;
        private final MatchmakingWebSocketService matchmakingWebSocketService;

        public WebSocketConfig(CoreWebSocketHandler coreWebSocketHandler,
                        MatchmakingWebSocketService matchmakingWebSocketService) {
                this.coreWebSocketHandler = coreWebSocketHandler;
                this.matchmakingWebSocketService = matchmakingWebSocketService;
        }

        @Override
        public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
                // ✅ CORREÇÃO COMPLETA: WebSocket simples sem SockJS que estava causando
                // conflito
                registry.addHandler(coreWebSocketHandler, "/api/ws")
                                .setAllowedOriginPatterns("*"); // Usar allowedOriginPatterns em vez de allowedOrigins

                // ✅ OPCIONAL: WebSocket alternativo sem /api (se necessário)
                registry.addHandler(coreWebSocketHandler, "/ws")
                                .setAllowedOriginPatterns("*");

                // Handler para clientes Electron (RPC LCU) em /client-ws (com interceptor de
                // autenticação)
                registry.addHandler(matchmakingWebSocketService, "/client-ws")
                                .addInterceptors(new br.com.lolmatchmaking.backend.config.WebSocketAuthInterceptor())
                                .setAllowedOriginPatterns("*");

                log.info("🔌 WebSocket registrado em: /api/ws e /ws");
        }

        @Bean
        public ServletServerContainerFactoryBean createWebSocketContainer() {
                ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
                container.setMaxTextMessageBufferSize(4194304); // 4MB
                container.setMaxBinaryMessageBufferSize(4194304); // 4MB
                // ✅ CORREÇÃO: Aumentar timeout para 10 minutos (Cloud Run suporta até 60 min)
                // Electron envia heartbeat a cada 60s, então 10min (600s) é seguro
                container.setMaxSessionIdleTimeout(600000L); // 600 segundos = 10 minutos
                log.info("🔧 WebSocket container configurado: buffers 4MB, timeout 10min");
                return container;
        }
}
