package br.com.lolmatchmaking.backend.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "riot.api")
public class RiotApiProperties {

    private String key;
    private String baseUrl = "https://br1.api.riotgames.com";
    private String americasUrl = "https://americas.api.riotgames.com";
    private boolean enabled = true;

    public boolean isConfigured() {
        return key != null && !key.trim().isEmpty();
    }
}
