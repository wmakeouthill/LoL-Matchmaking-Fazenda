package br.com.lolmatchmaking.backend.config;

import lombok.Data;

/**
 * Deprecated duplicate of properties class. Kept as plain POJO to avoid Spring
 * bean conflicts.
 */
@Deprecated
@Data
public class DiscordBotProperties {
    private String token;
    private boolean enabled = true;
}
