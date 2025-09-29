package br.com.lolmatchmaking.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Lcu lcu = new Lcu();

    @Data
    public static class Lcu {
        private boolean preferGateway = true;
        private String host = "127.0.0.1";
        private String protocol = "https";
        private int port = 0;
        private String password = "";
    }
}