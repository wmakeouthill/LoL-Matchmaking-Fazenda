package br.com.lolmatchmaking.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiotAPIService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${riot.api.key:}")
    private String apiKey;

    private static final Map<String, String> BASE_URLS = new HashMap<String, String>() {
        {
            put("br1", "https://br1.api.riotgames.com");
            put("eun1", "https://eun1.api.riotgames.com");
            put("euw1", "https://euw1.api.riotgames.com");
            put("jp1", "https://jp1.api.riotgames.com");
            put("kr", "https://kr.api.riotgames.com");
            put("la1", "https://la1.api.riotgames.com");
            put("la2", "https://la2.api.riotgames.com");
            put("na1", "https://na1.api.riotgames.com");
            put("oc1", "https://oc1.api.riotgames.com");
            put("tr1", "https://tr1.api.riotgames.com");
            put("ru", "https://ru.api.riotgames.com");
        }
    };

    private static final Map<String, String> REGIONAL_ROUTING = new HashMap<String, String>() {
        {
            put("br1", "americas");
            put("na1", "americas");
            put("la1", "americas");
            put("la2", "americas");
            put("kr", "asia");
            put("jp1", "asia");
            put("eun1", "europe");
            put("euw1", "europe");
            put("tr1", "europe");
            put("ru", "europe");
        }
    };

    private static final Map<String, String> REGIONAL_BASE_URLS = new HashMap<String, String>() {
        {
            put("americas", "https://americas.api.riotgames.com");
            put("asia", "https://asia.api.riotgames.com");
            put("europe", "https://europe.api.riotgames.com");
        }
    };

    @Data
    public static class SummonerData {
        private String id;
        private String accountId;
        private String puuid;
        private String name;
        private Integer profileIconId;
        private Long revisionDate;
        private Integer summonerLevel;
    }

    @Data
    public static class RankedData {
        private String leagueId;
        private String queueType;
        private String tier;
        private String rank;
        private String summonerId;
        private String summonerName;
        private Integer leaguePoints;
        private Integer wins;
        private Integer losses;
        private Boolean veteran;
        private Boolean inactive;
        private Boolean freshBlood;
        private Boolean hotStreak;
    }

    @Data
    public static class AccountData {
        private String puuid;
        private String gameName;
        private String tagLine;
    }

    /**
     * Verifica se a API está configurada
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * Obtém dados do summoner por nome
     */
    @Cacheable("summoner-by-name")
    public Optional<SummonerData> getSummonerByName(String summonerName, String region) {
        if (!isConfigured()) {
            log.warn("⚠️ API da Riot não configurada");
            return Optional.empty();
        }

        try {
            String url = String.format("%s/lol/summoner/v4/summoners/by-name/%s?api_key=%s",
                    BASE_URLS.get(region), summonerName, apiKey);

            log.debug("🔍 Buscando summoner: {} ({})", summonerName, region);

            String response = restTemplate.getForObject(url, String.class);
            if (response != null) {
                SummonerData summoner = objectMapper.readValue(response, SummonerData.class);
                return Optional.of(summoner);
            }

        } catch (Exception e) {
            log.warn("⚠️ Erro ao buscar summoner: {}", e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Obtém dados do summoner por PUUID
     */
    @Cacheable("summoner-by-puuid")
    public Optional<SummonerData> getSummonerByPUUID(String puuid, String region) {
        if (!isConfigured()) {
            return Optional.empty();
        }

        try {
            String url = String.format("%s/lol/summoner/v4/summoners/by-puuid/%s?api_key=%s",
                    BASE_URLS.get(region), puuid, apiKey);

            String response = restTemplate.getForObject(url, String.class);
            if (response != null) {
                SummonerData summoner = objectMapper.readValue(response, SummonerData.class);
                return Optional.of(summoner);
            }

        } catch (Exception e) {
            log.warn("⚠️ Erro ao buscar summoner por PUUID: {}", e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Obtém dados ranked do summoner
     */
    @Cacheable("ranked-data")
    public List<RankedData> getRankedData(String summonerId, String region) {
        if (!isConfigured()) {
            return new ArrayList<>();
        }

        try {
            String url = String.format("%s/lol/league/v4/entries/by-summoner/%s?api_key=%s",
                    BASE_URLS.get(region), summonerId, apiKey);

            String response = restTemplate.getForObject(url, String.class);
            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                List<RankedData> rankedData = new ArrayList<>();

                for (JsonNode entry : root) {
                    RankedData data = objectMapper.treeToValue(entry, RankedData.class);
                    rankedData.add(data);
                }

                return rankedData;
            }

        } catch (Exception e) {
            log.warn("⚠️ Erro ao buscar dados ranked: {}", e.getMessage());
        }

        return new ArrayList<>();
    }

    /**
     * Obtém dados da conta por Riot ID
     */
    @Cacheable("account-by-riot-id")
    public Optional<AccountData> getAccountByRiotId(String gameName, String tagLine, String region) {
        if (!isConfigured()) {
            return Optional.empty();
        }

        try {
            String regionalUrl = REGIONAL_BASE_URLS.get(REGIONAL_ROUTING.get(region));
            if (regionalUrl == null) {
                log.warn("⚠️ Região não suportada: {}", region);
                return Optional.empty();
            }

            String url = String.format("%s/riot/account/v1/accounts/by-riot-id/%s/%s?api_key=%s",
                    regionalUrl, gameName, tagLine, apiKey);

            String response = restTemplate.getForObject(url, String.class);
            if (response != null) {
                AccountData account = objectMapper.readValue(response, AccountData.class);
                return Optional.of(account);
            }

        } catch (Exception e) {
            log.warn("⚠️ Erro ao buscar conta por Riot ID: {}", e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Obtém dados do summoner por display name (compatibilidade)
     */
    public Map<String, Object> getSummonerByDisplayName(String displayName, String region) {
        try {
            // Tentar buscar por nome direto primeiro
            Optional<SummonerData> summoner = getSummonerByName(displayName, region);
            if (summoner.isPresent()) {
                Map<String, Object> result = new HashMap<>();
                SummonerData data = summoner.get();
                result.put("id", data.getId());
                result.put("puuid", data.getPuuid());
                result.put("name", data.getName());
                result.put("profileIconId", data.getProfileIconId());
                result.put("summonerLevel", data.getSummonerLevel());
                return result;
            }

            // Se não encontrou, tentar como Riot ID (gameName#tagLine)
            if (displayName.contains("#")) {
                String[] parts = displayName.split("#", 2);
                String gameName = parts[0];
                String tagLine = parts[1];

                Optional<AccountData> account = getAccountByRiotId(gameName, tagLine, region);
                if (account.isPresent()) {
                    // Buscar summoner pelo PUUID
                    Optional<SummonerData> summonerByPuuid = getSummonerByPUUID(account.get().getPuuid(), region);
                    if (summonerByPuuid.isPresent()) {
                        Map<String, Object> result = new HashMap<>();
                        SummonerData data = summonerByPuuid.get();
                        result.put("id", data.getId());
                        result.put("puuid", data.getPuuid());
                        result.put("name", data.getName());
                        result.put("profileIconId", data.getProfileIconId());
                        result.put("summonerLevel", data.getSummonerLevel());
                        result.put("gameName", account.get().getGameName());
                        result.put("tagLine", account.get().getTagLine());
                        return result;
                    }
                }
            }

        } catch (Exception e) {
            log.error("❌ Erro ao buscar summoner por display name", e);
        }

        return null;
    }

    /**
     * Obtém histórico de partidas
     */
    @Cacheable("match-history")
    public List<String> getMatchHistory(String puuid, String region, int count) {
        if (!isConfigured()) {
            return new ArrayList<>();
        }

        try {
            String regionalUrl = REGIONAL_BASE_URLS.get(REGIONAL_ROUTING.get(region));
            if (regionalUrl == null) {
                return new ArrayList<>();
            }

            String url = String.format("%s/lol/match/v5/matches/by-puuid/%s/ids?start=0&count=%d&api_key=%s",
                    regionalUrl, puuid, count, apiKey);

            String response = restTemplate.getForObject(url, String.class);
            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                List<String> matchIds = new ArrayList<>();

                for (JsonNode matchId : root) {
                    matchIds.add(matchId.asText());
                }

                return matchIds;
            }

        } catch (Exception e) {
            log.warn("⚠️ Erro ao buscar histórico de partidas: {}", e.getMessage());
        }

        return new ArrayList<>();
    }

    /**
     * Obtém detalhes de uma partida
     */
    @Cacheable("match-details")
    public Map<String, Object> getMatchDetails(String matchId, String region) {
        if (!isConfigured()) {
            return null;
        }

        try {
            String regionalUrl = REGIONAL_BASE_URLS.get(REGIONAL_ROUTING.get(region));
            if (regionalUrl == null) {
                return null;
            }

            String url = String.format("%s/lol/match/v5/matches/%s?api_key=%s",
                    regionalUrl, matchId, apiKey);

            String response = restTemplate.getForObject(url, String.class);
            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                return objectMapper.convertValue(root, Map.class);
            }

        } catch (Exception e) {
            log.warn("⚠️ Erro ao buscar detalhes da partida: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Obtém status do servidor
     */
    public Map<String, Object> getServerStatus(String region) {
        try {
            String url = String.format("%s/lol/status/v4/platform-data?api_key=%s",
                    BASE_URLS.get(region), apiKey);

            String response = restTemplate.getForObject(url, String.class);
            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                return objectMapper.convertValue(root, Map.class);
            }

        } catch (Exception e) {
            log.warn("⚠️ Erro ao buscar status do servidor: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Obtém informações da API
     */
    public Map<String, Object> getApiInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("configured", isConfigured());
        info.put("regions", BASE_URLS.keySet());
        info.put("regionalRouting", REGIONAL_ROUTING);
        return info;
    }
}