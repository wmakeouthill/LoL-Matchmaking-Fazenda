package br.com.lolmatchmaking.backend.config;

import lombok.Data;

/**
 * Deprecated duplicate of properties class. Kept as plain POJO to avoid Spring
 * bean conflicts.
 */
@Deprecated
@Data
public class MatchmakingProperties {

    private QueueConfig queue = new QueueConfig();
    private DraftConfig draft = new DraftConfig();

    @Data
    public static class QueueConfig {
        private int timeoutMinutes = 10;
        private int maxPlayers = 10;
    }

    @Data
    public static class DraftConfig {
        private int pickTimeSeconds = 30;
        private int banTimeSeconds = 20;
    }
}
