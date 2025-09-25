package br.com.lolmatchmaking.backend.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "matchmaking")
public class MatchmakingProperties {

    private Queue queue = new Queue();
    private Draft draft = new Draft();

    @Data
    public static class Queue {
        private int timeoutMinutes = 10;
        private int maxPlayers = 10;
    }

    @Data
    public static class Draft {
        private int pickTimeSeconds = 30;
        private int banTimeSeconds = 20;
    }
}
