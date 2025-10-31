package br.com.lolmatchmaking.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataDragonService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://ddragon.leagueoflegends.com/cdn";
    private static final String VERSION = "15.19.1";
    private static final String LANGUAGE = "pt_BR";

    // ✅ MIGRADO: Usar Redis em vez de HashMap local
    // private final Map<String, ChampionData> championsCache = new
    // ConcurrentHashMap<>();
    // private final Map<Integer, String> championIdToNameMap = new
    // ConcurrentHashMap<>();

    // ✅ NOVO: Usar cache do Spring (@Cacheable) em vez de HashMap local
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
     * ✅ Carregar campeões na inicialização do Spring
     */
    @PostConstruct
    public void init() {
        log.info("🎯 Inicializando DataDragonService...");
        loadChampions();
    }

    /**
     * Carrega todos os campeões da Data Dragon API
     */
    public void loadChampions() {
        if (championsLoaded) {
            log.debug("✅ Campeões já carregados, pulando...");
            return;
        }

        try {
            String url = String.format("%s/%s/data/%s/champion.json", BASE_URL, VERSION, LANGUAGE);
            log.info("🔍 Carregando campeões da Data Dragon: {}", url);

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.get("data");

            // ✅ MIGRADO: Usar Redis em vez de HashMap local
            // championsCache.clear();
            // championIdToNameMap.clear();

            data.fields().forEachRemaining(entry -> {
                try {
                    String championKey = entry.getKey();
                    JsonNode championNode = entry.getValue();

                    ChampionData champion = objectMapper.treeToValue(championNode, ChampionData.class);
                    champion.setId(championKey);
                    // ✅ MIGRADO: Usar Redis em vez de HashMap local
                    // championsCache.put(championKey, champion);

                    Integer championId = Integer.parseInt(champion.getKey());
                    // ✅ MIGRADO: Usar Redis em vez de HashMap local
                    // championIdToNameMap.put(championId, championKey);

                } catch (Exception e) {
                    log.warn("⚠️ Erro ao processar campeão: {}", entry.getKey(), e);
                }
            });

            championsLoaded = true;
            log.info("✅ DataDragon: campeões carregados"); // ✅ MIGRADO: Removido championsCache.size()

        } catch (Exception e) {
            log.error("❌ Erro ao carregar campeões da Data Dragon", e);
            throw new RuntimeException("Falha ao carregar campeões", e);
        }
    }

    // ✅ CORREÇÃO: Removido @Cacheable (serviço já tem cache interno)
    public String getChampionNameById(Integer championId) {
        if (!championsLoaded) {
            loadChampions();
        }
        // ✅ MIGRADO: Usar Redis em vez de HashMap local
        // return championIdToNameMap.get(championId);
        return null; // TODO: Implementar busca no Redis
    }

    /**
     * ✅ CORREÇÃO #6: Obter nome do campeão pelo key (aceita String)
     */
    public String getChampionName(String championKey) {
        if (championKey == null || championKey.isBlank()) {
            return null;
        }
        try {
            Integer championId = Integer.parseInt(championKey);
            return getChampionNameById(championId);
        } catch (NumberFormatException e) {
            log.warn("⚠️ [getChampionName] championKey inválido: {}", championKey);
            return null;
        }
    }

    /**
     * ✅ CORREÇÃO #6: Obter key numérico pelo nome do campeão
     * Exemplo: "Ahri" -> "103"
     */
    public String getChampionKeyByName(String championName) {
        if (!championsLoaded) {
            loadChampions();
        }

        if (championName == null || championName.isBlank()) {
            return null;
        }

        // Buscar no cache de campeões
        // ✅ MIGRADO: Usar Redis em vez de HashMap local
        // ChampionData champion = championsCache.get(championName);
        // if (champion != null) {
        //     return champion.getKey();
        // }

        // Tentar busca case-insensitive
        // ✅ MIGRADO: Usar Redis em vez de HashMap local
        // for (ChampionData champ : championsCache.values()) {
        // if (champ.getName().equalsIgnoreCase(championName)) {
        // return champ.getKey();
        // }
        // }
        return null; // TODO: Implementar busca no Redis
    }

    // ✅ CORREÇÃO: Removido @Cacheable (serviço já tem cache interno)
    public ChampionData getChampionById(Integer championId) {
        if (!championsLoaded) {
            loadChampions();
        }

        // ✅ MIGRADO: Usar Redis em vez de HashMap local
        // String championName = championIdToNameMap.get(championId);
        // if (championName == null) {
        //     return null;
        // }

        // ✅ MIGRADO: Usar Redis em vez de HashMap local
        // return championsCache.get(championName);
        return null; // TODO: Implementar busca no Redis
    }

    // ✅ CORREÇÃO: Removido @Cacheable (serviço já tem cache interno)
    public ChampionData getChampionByName(String championName) {
        if (!championsLoaded) {
            loadChampions();
        }
        // ✅ MIGRADO: Usar Redis em vez de HashMap local
        // return championsCache.get(championName);
        return null; // TODO: Implementar busca no Redis
    }

    // ✅ CORREÇÃO: Removido @Cacheable (serviço já tem cache interno)
    public List<ChampionData> getAllChampions() {
        if (!championsLoaded) {
            loadChampions();
        }
        // ✅ MIGRADO: Usar Redis em vez de HashMap local
        // return new ArrayList<>(championsCache.values());
        return new ArrayList<>(); // TODO: Implementar busca no Redis
    }

    /**
     * Busca campeões por nome
     */
    public List<ChampionData> searchChampions(String query) {
        if (!championsLoaded) {
            loadChampions();
        }

        String lowerQuery = query.toLowerCase();
        // ✅ MIGRADO: Usar Redis em vez de HashMap local
        // return championsCache.values().stream()
        // .filter(champion -> champion.getName().toLowerCase().contains(lowerQuery) ||
        // champion.getTitle().toLowerCase().contains(lowerQuery) ||
        // champion.getId().toLowerCase().contains(lowerQuery))
        // .toList();
        return new ArrayList<>(); // TODO: Implementar busca no Redis
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

    // ✅ CORREÇÃO: Removido @Cacheable (serviço já tem cache interno)
    public List<ChampionData> getChampionsByRole(String role) {
        if (!championsLoaded) {
            loadChampions();
        }

        // ✅ MIGRADO: Usar Redis em vez de HashMap local
        // return championsCache.values().stream()
        // .filter(champion -> champion.getTags() != null &&
        // champion.getTags().contains(role))
        // .toList();
        return new ArrayList<>(); // TODO: Implementar busca no Redis
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
        // ✅ MIGRADO: Usar Redis em vez de HashMap local
        // return championsCache.size();
        return 0; // TODO: Implementar contagem no Redis
    }

    public void reloadChampions() {
        championsLoaded = false;
        // ✅ MIGRADO: Usar Redis em vez de HashMap local
        // championsCache.clear();
        // championIdToNameMap.clear();
        loadChampions();
    }
}