package br.com.lolmatchmaking.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ApplicationStartupConfig implements ApplicationListener<ApplicationReadyEvent> {

    private final Environment environment;

    @Bean
    public ApplicationRunner applicationRunner() {
        return args -> {
            try {
                log.info("🚀 =================================================");
                log.info("🚀 SPRING BOOT APPLICATION RUNNER EXECUTADO");
                log.info("🚀 =================================================");

                // Log das configurações principais
                String port = environment.getProperty("server.port", "8080");
                String profile = environment.getProperty("spring.profiles.active", "default");

                log.info("🌐 Porta do servidor: {}", port);
                log.info("🔧 Profile ativo: {}", profile);

                log.info("✅ ApplicationRunner concluído com sucesso!");

            } catch (Exception e) {
                log.error("❌ Erro durante ApplicationRunner", e);
                throw e;
            }
        };
    }

    @Override
    public void onApplicationEvent(@org.springframework.lang.NonNull ApplicationReadyEvent event) {
        try {
            String port = environment.getProperty("server.port", "8080");

            log.info("🎉 =================================================");
            log.info("🎉 SPRING BOOT TOTALMENTE INICIALIZADO!");
            log.info("🎉 APPLICATION READY EVENT DISPARADO!");
            log.info("🎉 =================================================");

            // URLs importantes
            log.info("📡 API Base URL: http://localhost:{}/api", port);
            log.info("🔌 WebSocket URL: ws://localhost:{}/ws", port);
            log.info("🧪 Health Check: http://localhost:{}/api/test", port);
            log.info("🌐 Frontend URL: http://localhost:{}/", port);
            log.info("📋 OpenAPI/Swagger: http://localhost:{}/swagger-ui.html", port);

            log.info("🎉 SISTEMA PRONTO PARA USO!");
            log.info("🎉 =================================================");

        } catch (Exception e) {
            log.error("❌ Erro durante ApplicationReadyEvent", e);
        }
    }
}
