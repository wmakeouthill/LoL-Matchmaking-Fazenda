package br.com.lolmatchmaking.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.concurrent.CompletableFuture;

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
    public void onApplicationEvent(ApplicationReadyEvent event) {
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

            // Iniciar Electron se possível
            startElectronIfAvailable();

        } catch (Exception e) {
            log.error("❌ Erro durante ApplicationReadyEvent", e);
        }
    }

    private void startElectronIfAvailable() {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("🔍 Verificando se pode iniciar Electron...");

                // Usar o diretório atual do Spring Boot (onde está o package.json)
                String userDir = System.getProperty("user.dir");
                log.info("📁 Diretório atual: {}", userDir);

                // Verificar se package.json existe
                java.io.File packageJson = new java.io.File(userDir, "package.json");
                if (!packageJson.exists()) {
                    log.warn("📦 package.json não encontrado em: {}", userDir);
                    log.info("💡 Execute 'npm install' para instalar as dependências do Electron");
                    return;
                }

                log.info("📦 package.json encontrado - aguardando frontend estar pronto...");

                // Aguardar o frontend estar disponível
                String port = environment.getProperty("server.port", "8080");
                String frontendUrl = "http://localhost:" + port + "/";

                if (waitForFrontendToBeReady(frontendUrl)) {
                    log.info("✅ Frontend está pronto! Iniciando Electron...");

                    // Aguardar mais 2 segundos para garantir estabilidade
                    Thread.sleep(2000);

                    // Executar npm run dev:electron no diretório atual
                    ProcessBuilder pb = new ProcessBuilder();
                    pb.directory(new java.io.File(userDir));
                    pb.command("cmd", "/c", "npm run dev:electron");

                    // Redirecionar saída para logs
                    pb.redirectErrorStream(true);

                    Process process = pb.start();

                    log.info("🚀 Electron iniciado! PID: {} - aguarde a janela abrir", process.pid());
                    log.info("💡 Se o Electron não abrir, execute manualmente: npm run dev:electron");

                    // Monitorar processo em thread separada
                    CompletableFuture.runAsync(() -> {
                        try {
                            int exitCode = process.waitFor();
                            if (exitCode != 0) {
                                log.warn("⚠️ Electron terminou com código: {}", exitCode);
                            } else {
                                log.info("✅ Electron encerrado normalmente");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.debug("Monitoramento do Electron interrompido");
                        }
                    });
                } else {
                    log.warn("⏰ Timeout aguardando frontend - Electron não será iniciado automaticamente");
                    log.info("💡 Acesse o frontend diretamente em: {}", frontendUrl);
                    log.info("💡 Para usar o Electron, execute: npm run dev:electron");
                }

            } catch (Exception e) {
                log.info("ℹ️ Electron não pôde ser iniciado automaticamente: {}", e.getMessage());
                String port = environment.getProperty("server.port", "8080");
                log.info("💡 Acesse o frontend diretamente em: http://localhost:{}/", port);
                log.info("💡 Para usar o Electron, execute: npm install && npm run dev:electron");
            }
        });
    }

    private boolean waitForFrontendToBeReady(String url) {
        int maxAttempts = 30; // 30 tentativas = ~30 segundos
        int attemptDelayMs = 1000; // 1 segundo entre tentativas

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("🔍 Verificando frontend (tentativa {}/{}): {}", attempt, maxAttempts, url);

                java.net.URL frontendUrl = new java.net.URL(url);
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) frontendUrl.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000); // 5 segundos timeout
                connection.setReadTimeout(5000);

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    // Verificar se realmente tem conteúdo (não apenas uma página de erro)
                    try (java.io.InputStream inputStream = connection.getInputStream()) {
                        byte[] buffer = new byte[1024];
                        int bytesRead = inputStream.read(buffer);
                        String content = new String(buffer, 0, Math.max(0, bytesRead));

                        if (content.toLowerCase().contains("<html") || content.toLowerCase().contains("<!doctype")) {
                            log.info("✅ Frontend respondendo com conteúdo HTML válido!");
                            return true;
                        } else {
                            log.debug("⚠️ Frontend respondeu mas sem conteúdo HTML válido");
                        }
                    }
                } else {
                    log.debug("⚠️ Frontend retornou código: {}", responseCode);
                }
                connection.disconnect();

            } catch (Exception e) {
                log.debug("⚠️ Tentativa {}: {}", attempt, e.getMessage());
            }

            try {
                Thread.sleep(attemptDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Verificação do frontend interrompida");
                return false;
            }
        }

        log.warn("⏰ Timeout: Frontend não ficou pronto em {} segundos", maxAttempts);
        return false;
    }
}
