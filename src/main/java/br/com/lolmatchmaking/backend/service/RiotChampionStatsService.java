package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.dto.PlayerChampionStatsDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Serviço para buscar estatísticas de campeões da Riot API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiotChampionStatsService {

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final String RIOT_API_BASE_URL = "https://br1.api.riotgames.com";
    private static final String RIOT_AMERICAS_URL = "https://americas.api.riotgames.com";

    /**
     * Busca a Riot API Key da tabela settings
     */
    private String getRiotApiKey() {
        try {
            log.info("🔑 Buscando Riot API Key da tabela settings...");
            String apiKey = jdbcTemplate.queryForObject(
                    "SELECT value FROM settings WHERE settings_key = 'riot_api_key' LIMIT 1",
                    String.class);

            if (apiKey == null || apiKey.trim().isEmpty()) {
                log.warn("⚠️ Riot API Key não encontrada na tabela settings");
                return null;
            }

            // Remover aspas se existirem
            apiKey = apiKey.trim();
            if (apiKey.startsWith("\"") && apiKey.endsWith("\"")) {
                apiKey = apiKey.substring(1, apiKey.length() - 1);
            }

            log.info("✅ Riot API Key encontrada: {}***", apiKey.substring(0, Math.min(10, apiKey.length())));
            return apiKey;
        } catch (Exception e) {
            log.error("❌ Erro ao buscar Riot API Key: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Busca o PUUID correto da Riot API usando gameName#tagLine
     * 
     * @param summonerName Nome completo no formato "GameName#TAG"
     * @return PUUID correto da Riot API
     */
    public String getRiotPuuid(String summonerName) {
        log.info("🔍 Buscando PUUID correto da Riot API para: {}", summonerName);

        String apiKey = getRiotApiKey();
        if (apiKey == null) {
            log.warn("⚠️ Não é possível buscar PUUID sem Riot API Key");
            return null;
        }

        try {
            // Separar gameName e tagLine
            String[] parts = summonerName.split("#");
            if (parts.length != 2) {
                log.error("❌ Formato inválido de summoner name: {}. Esperado: GameName#TAG", summonerName);
                return null;
            }

            String gameName = parts[0].trim();
            String tagLine = parts[1].trim();

            // Chamar Riot Account API (americas)
            String url = UriComponentsBuilder
                    .fromHttpUrl(RIOT_AMERICAS_URL + "/riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}")
                    .buildAndExpand(gameName, tagLine)
                    .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Riot-Token", apiKey);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            log.debug("🌐 URL Account API: {}", url);
            String response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class).getBody();

            JsonNode account = objectMapper.readTree(response);
            String puuid = account.get("puuid").asText();

            log.info("✅ PUUID encontrado para {}: {}", summonerName,
                    puuid.substring(0, Math.min(20, puuid.length())) + "...");
            return puuid;

        } catch (Exception e) {
            log.error("❌ Erro ao buscar PUUID para {}: {}", summonerName, e.getMessage());
            return null;
        }
    }

    /**
     * Busca top 3 campeões de maestria do jogador
     */
    public List<PlayerChampionStatsDTO.ChampionMasteryStats> getTopMasteryChampions(String puuid) {
        log.info("🎮 Buscando top 3 maestria para PUUID: {}...", puuid.substring(0, Math.min(20, puuid.length())));

        String apiKey = getRiotApiKey();
        if (apiKey == null) {
            log.warn("⚠️ Não é possível buscar maestria sem Riot API Key");
            return Collections.emptyList();
        }

        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(RIOT_API_BASE_URL + "/lol/champion-mastery/v4/champion-masteries/by-puuid/{puuid}/top")
                    .queryParam("count", 3)
                    .buildAndExpand(puuid)
                    .toUriString();

            // Criar headers com API Key
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Riot-Token", apiKey);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            String response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class).getBody();

            JsonNode masteryArray = objectMapper.readTree(response);
            List<PlayerChampionStatsDTO.ChampionMasteryStats> masteryList = new ArrayList<>();

            for (JsonNode mastery : masteryArray) {
                masteryList.add(PlayerChampionStatsDTO.ChampionMasteryStats.builder()
                        .championId(mastery.get("championId").asInt())
                        .championName("Champion " + mastery.get("championId").asInt()) // Será convertido no frontend
                        .championLevel(mastery.get("championLevel").asInt())
                        .championPoints(mastery.get("championPoints").asLong())
                        .championPointsSinceLastLevel(mastery.has("championPointsSinceLastLevel")
                                ? mastery.get("championPointsSinceLastLevel").asLong()
                                : 0L)
                        .championPointsUntilNextLevel(mastery.has("championPointsUntilNextLevel")
                                ? mastery.get("championPointsUntilNextLevel").asLong()
                                : 0L)
                        .build());
            }

            log.info("✅ Top 3 maestria para PUUID: {}", masteryList.size());
            return masteryList;

        } catch (Exception e) {
            log.error("❌ Erro ao buscar maestria para PUUID {}: {}", puuid, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Busca top 5 campeões ranked (mais jogados + maior winrate)
     */
    public List<PlayerChampionStatsDTO.ChampionRankedStats> getTopRankedChampions(String puuid) {
        log.info("🏆 Buscando top 5 ranked para PUUID: {}...", puuid.substring(0, Math.min(20, puuid.length())));

        String apiKey = getRiotApiKey();
        if (apiKey == null) {
            log.warn("⚠️ Não é possível buscar stats ranked sem Riot API Key");
            return Collections.emptyList();
        }

        try {
            log.info("📊 Buscando match IDs ranked (queue 420)...");
            // 1. Buscar match IDs ranked (últimas 20 partidas - equilíbrio entre dados e
            // rate limit)
            String matchIdsUrl = UriComponentsBuilder
                    .fromHttpUrl(RIOT_AMERICAS_URL + "/lol/match/v5/matches/by-puuid/{puuid}/ids")
                    .queryParam("queue", 420) // 420 = Ranked Solo/Duo
                    .queryParam("start", 0)
                    .queryParam("count", 20)
                    .buildAndExpand(puuid)
                    .toUriString();

            // Criar headers com API Key
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Riot-Token", apiKey);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            log.debug("🌐 URL Match IDs: {}", matchIdsUrl);
            String[] matchIds = restTemplate.exchange(
                    matchIdsUrl,
                    HttpMethod.GET,
                    entity,
                    String[].class).getBody();

            if (matchIds == null || matchIds.length == 0) {
                log.info("ℹ️ Nenhuma partida ranked encontrada para PUUID {}", puuid);
                return Collections.emptyList();
            }

            log.info("✅ Encontradas {} partidas ranked", matchIds.length);

            // 2. Buscar detalhes das partidas e extrair campeões
            Map<Integer, ChampionRankedData> championStats = new HashMap<>();

            for (String matchId : matchIds) {
                try {
                    log.debug("📥 Processando match: {}", matchId);
                    String matchUrl = RIOT_AMERICAS_URL + "/lol/match/v5/matches/" + matchId;

                    String matchResponse = restTemplate.exchange(
                            matchUrl,
                            HttpMethod.GET,
                            entity,
                            String.class).getBody();

                    JsonNode match = objectMapper.readTree(matchResponse);

                    // Encontrar o participante
                    JsonNode participants = match.get("info").get("participants");
                    for (JsonNode participant : participants) {
                        String participantPuuid = participant.get("puuid").asText();
                        if (participantPuuid.equals(puuid)) {
                            int championId = participant.get("championId").asInt();
                            boolean win = participant.get("win").asBoolean();

                            championStats.computeIfAbsent(championId, k -> new ChampionRankedData(championId))
                                    .addGame(win);
                            break;
                        }
                    }

                    // Rate limiting - 10 requests per second (100ms entre cada)
                    Thread.sleep(100);

                } catch (Exception e) {
                    log.warn("⚠️ Erro ao processar match {}: {}", matchId, e.getMessage());
                }
            }

            // 3. Converter para DTO e ordenar por win rate e games played
            List<PlayerChampionStatsDTO.ChampionRankedStats> rankedList = championStats.values().stream()
                    .sorted(Comparator
                            .comparingDouble(ChampionRankedData::getWinRate).reversed()
                            .thenComparingInt(ChampionRankedData::getGamesPlayed).reversed())
                    .limit(5)
                    .map(data -> PlayerChampionStatsDTO.ChampionRankedStats.builder()
                            .championId(data.championId)
                            .championName("Champion " + data.championId) // Será convertido no frontend
                            .gamesPlayed(data.gamesPlayed)
                            .wins(data.wins)
                            .losses(data.losses)
                            .winRate(data.getWinRate())
                            .tier("RANKED_SOLO_5x5")
                            .build())
                    .collect(Collectors.toList());

            log.info("✅ Top 5 ranked para PUUID {}: {}", puuid, rankedList.size());
            return rankedList;

        } catch (Exception e) {
            log.error("❌ Erro ao buscar stats ranked para PUUID {}: {}", puuid, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Classe auxiliar para armazenar dados de campeões ranked
     */
    private static class ChampionRankedData {
        private final int championId;
        private int gamesPlayed = 0;
        private int wins = 0;
        private int losses = 0;

        public ChampionRankedData(int championId) {
            this.championId = championId;
        }

        public void addGame(boolean win) {
            gamesPlayed++;
            if (win) {
                wins++;
            } else {
                losses++;
            }
        }

        public double getWinRate() {
            return gamesPlayed > 0 ? (wins * 100.0 / gamesPlayed) : 0.0;
        }

        public int getGamesPlayed() {
            return gamesPlayed;
        }
    }
}
