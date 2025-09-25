package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.service.DataDragonService;
import br.com.lolmatchmaking.backend.service.DataDragonService.ChampionData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/champions")
@RequiredArgsConstructor
public class ChampionController {

    private final DataDragonService dataDragonService;

    /**
     * GET /api/champions
     * Obtém todos os campeões
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllChampions() {
        try {
            log.info("🔍 Obtendo todos os campeões");

            List<ChampionData> champions = dataDragonService.getAllChampions();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "champions", champions,
                    "count", champions.size()));

        } catch (Exception e) {
            log.error("❌ Erro ao obter campeões", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/champions/{championId}
     * Obtém dados de um campeão específico
     */
    @GetMapping("/{championId}")
    public ResponseEntity<Map<String, Object>> getChampionById(@PathVariable Integer championId) {
        try {
            log.info("🔍 Obtendo campeão: {}", championId);

            ChampionData champion = dataDragonService.getChampionById(championId);
            if (champion != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "champion", champion));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter campeão", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/champions/name/{championName}
     * Obtém dados de um campeão por nome
     */
    @GetMapping("/name/{championName}")
    public ResponseEntity<Map<String, Object>> getChampionByName(@PathVariable String championName) {
        try {
            log.info("🔍 Obtendo campeão por nome: {}", championName);

            ChampionData champion = dataDragonService.getChampionByName(championName);
            if (champion != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "champion", champion));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter campeão por nome", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/champions/role/{role}
     * Obtém campeões por role/tag
     */
    @GetMapping("/role/{role}")
    public ResponseEntity<Map<String, Object>> getChampionsByRole(@PathVariable String role) {
        try {
            log.info("🔍 Obtendo campeões por role: {}", role);

            List<ChampionData> champions = dataDragonService.getChampionsByRole(role);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "champions", champions,
                    "role", role,
                    "count", champions.size()));

        } catch (Exception e) {
            log.error("❌ Erro ao obter campeões por role", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/champions/search
     * Busca campeões por nome
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchChampions(@RequestParam String q) {
        try {
            log.info("🔍 Buscando campeões: {}", q);

            if (q == null || q.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Query de busca é obrigatória"));
            }

            List<ChampionData> champions = dataDragonService.searchChampions(q);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "champions", champions,
                    "query", q,
                    "count", champions.size()));

        } catch (Exception e) {
            log.error("❌ Erro ao buscar campeões", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/champions/{championId}/image
     * Obtém URL da imagem do campeão
     */
    @GetMapping("/{championId}/image")
    public ResponseEntity<Map<String, Object>> getChampionImage(@PathVariable Integer championId) {
        try {
            log.info("🖼️ Obtendo imagem do campeão: {}", championId);

            String imageUrl = dataDragonService.getChampionImageUrl(championId);
            if (imageUrl != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "championId", championId,
                        "imageUrl", imageUrl));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter imagem do campeão", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/champions/{championId}/exists
     * Verifica se o campeão existe
     */
    @GetMapping("/{championId}/exists")
    public ResponseEntity<Map<String, Object>> championExists(@PathVariable Integer championId) {
        try {
            log.info("❓ Verificando se campeão existe: {}", championId);

            boolean exists = dataDragonService.championExists(championId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "championId", championId,
                    "exists", exists));

        } catch (Exception e) {
            log.error("❌ Erro ao verificar existência do campeão", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/champions/summary
     * Obtém resumo dos campeões
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getChampionsSummary() {
        try {
            log.info("📊 Obtendo resumo dos campeões");

            Map<String, Object> summary = Map.of(
                    "totalChampions", dataDragonService.getChampionsCount(),
                    "version", dataDragonService.getCurrentVersion(),
                    "language", dataDragonService.getCurrentLanguage());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "summary", summary));

        } catch (Exception e) {
            log.error("❌ Erro ao obter resumo dos campeões", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/champions/reload
     * Recarrega dados dos campeões
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadChampions() {
        try {
            log.info("🔄 Recarregando dados dos campeões");

            dataDragonService.reloadChampions();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Dados dos campeões recarregados com sucesso"));

        } catch (Exception e) {
            log.error("❌ Erro ao recarregar campeões", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}