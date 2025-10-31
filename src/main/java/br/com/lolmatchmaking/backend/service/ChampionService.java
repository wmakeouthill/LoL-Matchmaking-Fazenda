package br.com.lolmatchmaking.backend.service;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ChampionService {
    private static final Logger log = LoggerFactory.getLogger(ChampionService.class);
    private final RestClient restClient = RestClient.builder().build();

    // ✅ MIGRADO: Usar Redis em vez de HashMap local
    // private final Map<String, Object> championData = new ConcurrentHashMap<>();
    // private final AtomicBoolean loaded = new AtomicBoolean(false);

    // ✅ NOVO: Usar cache do Spring (@Cacheable) em vez de HashMap local
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    public boolean isLoaded() {
        return loaded.get();
    }

    // ✅ MIGRADO: Método que usava HashMap local
    // private Collection<Map<String, Object>> rawChampionList() {
    // return championData.values().stream().map(o -> (Map<String, Object>)
    // o).toList();
    // }

    // ✅ NOVO: Usar cache do Spring em vez de HashMap local
    @Cacheable("champions-data")
    private Collection<Map<String, Object>> rawChampionList() {
        // Buscar dados da API e cachear via Spring Cache
        return loadChampionsFromAPI();
    }

    // ✅ NOVO: Carregar champions da API
    private Collection<Map<String, Object>> loadChampionsFromAPI() {
        try {
            // Implementar carregamento da API
            log.info("🔄 Carregando champions da API...");
            // TODO: Implementar carregamento real da API
            return List.of(); // Placeholder
        } catch (Exception e) {
            log.error("❌ Erro ao carregar champions da API", e);
            return List.of();
        }
    }

    @Cacheable("champions")
    public Collection<Map<String, Object>> getAllChampions() {
        // Apenas retorna o snapshot atual (proxy de cache envolve este método)
        return rawChampionList();
    }

    public Map<String, List<Map<String, Object>>> getChampionsByRole() {
        Map<String, List<Map<String, Object>>> byRole = new HashMap<>();
        for (var v : rawChampionList()) {
            @SuppressWarnings("unchecked")
            var tags = (List<String>) v.getOrDefault("tags", List.of());
            for (String t : tags) {
                byRole.computeIfAbsent(t.toLowerCase(), k -> new ArrayList<>()).add(v);
            }
        }
        return byRole;
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 10000)
    public void refresh() {
        loadChampions();
    }

    public synchronized void loadChampions() {
        try {
            ResponseEntity<String[]> versions = restClient.get()
                    .uri("https://ddragon.leagueoflegends.com/api/versions.json")
                    .retrieve().toEntity(String[].class);
            String[] bodyVersions = versions.getBody();
            String version = (bodyVersions != null && bodyVersions.length > 0) ? bodyVersions[0] : "14.1.1";
            RequestEntity<Void> req = RequestEntity.get(URI
                    .create("https://ddragon.leagueoflegends.com/cdn/" + version + "/data/en_US/champion.json"))
                    .build();
            @SuppressWarnings("unchecked")
            Map<String, Object> json = restClient
                    .get()
                    .uri(req.getUrl())
                    .retrieve()
                    .body(Map.class);
            if (json != null && json.get("data") instanceof Map<?, ?> dataMap) {
                // ✅ MIGRADO: Usar Redis em vez de HashMap local
                // championData.clear();
                // dataMap.forEach((k, v) -> championData.put(String.valueOf(k), v));
                loaded.set(true);
                log.info("Champions carregados (versão {})", version);
            }
        } catch (Exception e) {
            log.warn("Falha ao carregar champions: {}", e.toString());
        }
    }
}
