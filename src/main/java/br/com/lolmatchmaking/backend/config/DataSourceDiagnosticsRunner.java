package br.com.lolmatchmaking.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
public class DataSourceDiagnosticsRunner {

    private static final Pattern URL_PATTERN = Pattern.compile("jdbc:mysql://([^/:]+)(?::(\\d+))?/([^?]+).*");

    @Bean
    ApplicationRunner datasourceDiagnostics(DataSource dataSource,
                                            Environment env,
                                            @Value("${app.debug.log-datasource:false}") boolean logDs) {
        return args -> {
            if (!logDs) return;
            String url = env.getProperty("spring.datasource.url");
            String user = env.getProperty("spring.datasource.username");
            System.out.println("[DS-DIAG] URL resolvida: " + url);
            System.out.println("[DS-DIAG] Usuário resolvido: " + user);

            if (url != null) {
                Matcher m = URL_PATTERN.matcher(url);
                if (m.matches()) {
                    String host = m.group(1);
                    String portStr = m.group(2) != null ? m.group(2) : "3306";
                    int port = Integer.parseInt(portStr);
                    // Teste de socket antes de tentar autenticar
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(host, port), 3000);
                        System.out.println("[DS-DIAG] Socket conectado ao host " + host + ":" + port);
                    } catch (Exception ex) {
                        System.out.println("[DS-DIAG] Falha em conectar socket (antes de autenticar): " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
                    }
                }
            }

            // Tentativa controlada de obter conexão (sem manter aberta)
            try (Connection c = dataSource.getConnection()) {
                System.out.println("[DS-DIAG] Conexão JDBC obtida com sucesso. AutoCommit=" + c.getAutoCommit());
            } catch (SQLException e) {
                System.out.println("[DS-DIAG] Falha ao obter conexão JDBC: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                Throwable root = e.getCause();
                if (root != null) {
                    System.out.println("[DS-DIAG] Causa raiz: " + root.getClass().getSimpleName() + " - " + root.getMessage());
                }
            }
        };
    }
}

