package br.com.lolmatchmaking.backend.config;

import lombok.Data;

/**
 * Deprecated duplicate of properties class. Kept as plain POJO to avoid Spring
 * bean conflicts.
 */
@Deprecated
@Data
public class RiotApiProperties {
    private String key;
    private String baseUrl = "https://br1.api.riotgames.com";
    private String americasUrl = "https://americas.api.riotgames.com";
}
