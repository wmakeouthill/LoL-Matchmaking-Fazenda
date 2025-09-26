package br.com.lolmatchmaking.backend.config;

import br.com.lolmatchmaking.backend.service.LCUService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class LCUConfig {

    private final LCUService lcuService;

    /**
     * Inicializa o LCUService automaticamente quando a aplicação sobe
     */
    @Bean
    public ApplicationRunner initializeLCUService() {
        return args -> {
            try {
                log.info("🚀 Inicializando LCUService...");
                lcuService.initialize();
                log.info("✅ LCUService inicializado com sucesso");
            } catch (Exception e) {
                log.error("❌ Erro ao inicializar LCUService", e);
                // Não falhar a aplicação se LCU não estiver disponível
            }
        };
    }
}
