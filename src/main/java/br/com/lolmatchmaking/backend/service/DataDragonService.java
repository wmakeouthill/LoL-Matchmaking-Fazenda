package br.com.lolmatchmaking.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataDragonService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://ddragon.leagueoflegends.com/cdn";
    private static final String VERSION = "15.14.1";
    private static final String LANGUAGE = "pt_BR";

    private final Map<String, ChampionData> championsCache = new ConcurrentHashMap<>();
    private final Map<Integer, String> championIdToNameMap = new ConcurrentHashMap<>();
    private boolean championsLoaded = false;

    @Data
    public static class ChampionData {
        private String id;
        private String key;
        private String name;
        private String title;
        private String blurb;
        private ChampionInfo info;
        private ChampionImage image;
        private List<String> tags;
        private String partype;
        private ChampionStats stats;

        @Data
        public static class ChampionInfo {
            private Integer attack;
            private Integer defense;
            private Integer magic;
            private Integer difficulty;
        }

        @Data
        public static class ChampionImage {
            private String full;
            private String sprite;
            private String group;
            private Integer x;
            private Integer y;
            private Integer w;
            private Integer h;
        }

        @Data
        public static class ChampionStats {
            private Double hp;
            private Double hpperlevel;
            private Double mp;
            private Double mpperlevel;
            private Double movespeed;
            private Double armor;
            private Double armorperlevel;
            private Double spellblock;
            private Double spellblockperlevel;
            private Double attackrange;
            private Double hpregen;
            private Double hpregenperlevel;
            private Double mpregen;
            private Double mpregenperlevel;
            private Double crit;
            private Double critperlevel;
            private Double attackdamage;
            private Double attackdamageperlevel;
            private Double attackspeedperlevel;
            private Double attackspeed;
        }
    }

    /**
     * Carrega todos os campeões da Data Dragon API
     */
    public void loadChampions() {
        if (championsLoaded) {
            return;
        }

        try {
            String url = String.format("%s/%s/data/%s/champion.json", BASE_URL, VERSION, LANGUAGE);
            log.info("🔍 Carregando campeões da Data Dragon: {}", url);

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.get("data");

            championsCache.clear();
            championIdToNameMap.clear();

            data.fields().forEachRemaining(entry -> {
                try {
                    String championKey = entry.getKey();
                    JsonNode championNode = entry.getValue();

                    ChampionData champion = objectMapper.treeToValue(championNode, ChampionData.class);
                    champion.setId(championKey);
                    championsCache.put(championKey, champion);

                    Integer championId = Integer.parseInt(champion.getKey());
                    championIdToNameMap.put(championId, championKey);

                } catch (Exception e) {
                    log.warn("⚠️ Erro ao processar campeão: {}", entry.getKey(), e);
                }
            });

            championsLoaded = true;
            log.info("✅ DataDragon: {} campeões carregados", championsCache.size());

        } catch (Exception e) {
            log.error("❌ Erro ao carregar campeões da Data Dragon", e);
            throw new RuntimeException("Falha ao carregar campeões", e);
        }
    }

    @Cacheable("champion-name-by-id")
    public String getChampionNameById(Integer championId) {
        if (!championsLoaded) {
            loadChampions();
        }
        return championIdToNameMap.get(championId);
    }

    @Cacheable("champion-data-by-id")
    public ChampionData getChampionById(Integer championId) {
        if (!championsLoaded) {
            loadChampions();
        }

        String championName = championIdToNameMap.get(championId);
        if (championName == null) {
            return null;
        }

        return championsCache.get(championName);
    }

    @Cacheable("champion-data-by-name")
    public ChampionData getChampionByName(String championName) {
        if (!championsLoaded) {
            loadChampions();
        }
        return championsCache.get(championName);
    }

    @Cacheable("all-champions")
    public List<ChampionData> getAllChampions() {
        if (!championsLoaded) {
            loadChampions();
        }
        return new ArrayList<>(championsCache.values());
    }

    /**
     * Busca campeões por nome
     */
    public List<ChampionData> searchChampions(String query) {
        if (!championsLoaded) {
            loadChampions();
        }

        String lowerQuery = query.toLowerCase();
        return championsCache.values().stream()
                .filter(champion -> champion.getName().toLowerCase().contains(lowerQuery) ||
                        champion.getTitle().toLowerCase().contains(lowerQuery) ||
                        champion.getId().toLowerCase().contains(lowerQuery))
                .toList();
    }

    /**
     * Obtém a versão atual do Data Dragon
     */
    public String getCurrentVersion() {
        return VERSION;
    }

    /**
     * Obtém o idioma atual
     */
    public String getCurrentLanguage() {
        return LANGUAGE;
    }

    @Cacheable("champions-by-role")
    public List<ChampionData> getChampionsByRole(String role) {
        if (!championsLoaded) {
            loadChampions();
        }

        return championsCache.values().stream()
                .filter(champion -> champion.getTags() != null && champion.getTags().contains(role))
                .toList();
    }

    public String getChampionImageUrl(Integer championId) {
        ChampionData champion = getChampionById(championId);
        if (champion == null || champion.getImage() == null) {
            return null;
        }
        return String.format("%s/%s/img/champion/%s", BASE_URL, VERSION, champion.getImage().getFull());
    }

    public boolean championExists(Integer championId) {
        return getChampionNameById(championId) != null;
    }

    public int getChampionsCount() {
        if (!championsLoaded) {
            loadChampions();
        }
        return championsCache.size();
    }

    public void reloadChampions() {
        championsLoaded = false;
        championsCache.clear();
        championIdToNameMap.clear();
        loadChampions();
    }
}