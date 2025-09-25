package br.com.lolmatchmaking.backend.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "discord.bot")
public class DiscordBotProperties {

    private String token;
    private boolean enabled = true;

    public boolean isConfigured() {
        return token != null && !token.trim().isEmpty();
    }
}
