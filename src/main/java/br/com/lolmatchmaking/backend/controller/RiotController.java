package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.service.RiotAPIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/riot")
@RequiredArgsConstructor
public class RiotController {

    private final RiotAPIService riotAPIService;

    /**
     * GET /api/riot/summoner/{summonerName}
     * Obtém dados do summoner por nome
     */
    @GetMapping("/summoner/{summonerName}")
    public ResponseEntity<Map<String, Object>> getSummonerByName(
            @PathVariable String summonerName,
            @RequestParam(defaultValue = "br1") String region) {
        try {
            log.info("🔍 Obtendo summoner: {} ({})", summonerName, region);

            if (!riotAPIService.isConfigured()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "API da Riot não configurada"));
            }

            var summoner = riotAPIService.getSummonerByName(summonerName, region);
            if (summoner.isPresent()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "summoner", summoner.get()));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter summoner", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/riot/summoner/puuid/{puuid}
     * Obtém dados do summoner por PUUID
     */
    @GetMapping("/summoner/puuid/{puuid}")
    public ResponseEntity<Map<String, Object>> getSummonerByPuuid(
            @PathVariable String puuid,
            @RequestParam(defaultValue = "br1") String region) {
        try {
            log.info("🔍 Obtendo summoner por PUUID: {} ({})", puuid, region);

            if (!riotAPIService.isConfigured()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "API da Riot não configurada"));
            }

            var summoner = riotAPIService.getSummonerByPUUID(puuid, region);
            if (summoner.isPresent()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "summoner", summoner.get()));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter summoner por PUUID", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/riot/ranked/{summonerId}
     * Obtém dados ranked do summoner
     */
    @GetMapping("/ranked/{summonerId}")
    public ResponseEntity<Map<String, Object>> getRankedData(
            @PathVariable String summonerId,
            @RequestParam(defaultValue = "br1") String region) {
        try {
            log.info("🏆 Obtendo dados ranked: {} ({})", summonerId, region);

            if (!riotAPIService.isConfigured()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "API da Riot não configurada"));
            }

            List<RiotAPIService.RankedData> rankedData = riotAPIService.getRankedData(summonerId, region);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "rankedData", rankedData,
                    "count", rankedData.size()));

        } catch (Exception e) {
            log.error("❌ Erro ao obter dados ranked", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/riot/account/{gameName}/{tagLine}
     * Obtém dados da conta por Riot ID
     */
    @GetMapping("/account/{gameName}/{tagLine}")
    public ResponseEntity<Map<String, Object>> getAccountByRiotId(
            @PathVariable String gameName,
            @PathVariable String tagLine,
            @RequestParam(defaultValue = "br1") String region) {
        try {
            log.info("🔍 Obtendo conta por Riot ID: {}#{} ({})", gameName, tagLine, region);

            if (!riotAPIService.isConfigured()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "API da Riot não configurada"));
            }

            var account = riotAPIService.getAccountByRiotId(gameName, tagLine, region);
            if (account.isPresent()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "account", account.get()));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter conta por Riot ID", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/riot/matches/{puuid}
     * Obtém histórico de partidas
     */
    @GetMapping("/matches/{puuid}")
    public ResponseEntity<Map<String, Object>> getMatchHistory(
            @PathVariable String puuid,
            @RequestParam(defaultValue = "br1") String region,
            @RequestParam(defaultValue = "20") int count) {
        try {
            log.info("📊 Obtendo histórico de partidas: {} ({})", puuid, region);

            if (!riotAPIService.isConfigured()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "API da Riot não configurada"));
            }

            List<String> matchIds = riotAPIService.getMatchHistory(puuid, region, count);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "matchIds", matchIds,
                    "count", matchIds.size()));

        } catch (Exception e) {
            log.error("❌ Erro ao obter histórico de partidas", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/riot/match/{matchId}
     * Obtém detalhes de uma partida
     */
    @GetMapping("/match/{matchId}")
    public ResponseEntity<Map<String, Object>> getMatchDetails(
            @PathVariable String matchId,
            @RequestParam(defaultValue = "br1") String region) {
        try {
            log.info("🎮 Obtendo detalhes da partida: {} ({})", matchId, region);

            if (!riotAPIService.isConfigured()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "API da Riot não configurada"));
            }

            Map<String, Object> matchDetails = riotAPIService.getMatchDetails(matchId, region);
            if (matchDetails != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "match", matchDetails));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter detalhes da partida", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/riot/status/{region}
     * Obtém status do servidor
     */
    @GetMapping("/status/{region}")
    public ResponseEntity<Map<String, Object>> getServerStatus(@PathVariable String region) {
        try {
            log.info("📡 Obtendo status do servidor: {}", region);

            if (!riotAPIService.isConfigured()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "API da Riot não configurada"));
            }

            Map<String, Object> status = riotAPIService.getServerStatus(region);
            if (status != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "status", status));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter status do servidor", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/riot/info
     * Obtém informações da API
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getApiInfo() {
        try {
            log.info("ℹ️ Obtendo informações da API");

            Map<String, Object> info = riotAPIService.getApiInfo();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "info", info));

        } catch (Exception e) {
            log.error("❌ Erro ao obter informações da API", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/riot/display-name/{displayName}
     * Obtém dados do summoner por display name (compatibilidade)
     */
    @GetMapping("/display-name/{displayName}")
    public ResponseEntity<Map<String, Object>> getSummonerByDisplayName(
            @PathVariable String displayName,
            @RequestParam(defaultValue = "br1") String region) {
        try {
            log.info("🔍 Obtendo summoner por display name: {} ({})", displayName, region);

            if (!riotAPIService.isConfigured()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "API da Riot não configurada"));
            }

            Map<String, Object> summonerData = riotAPIService.getSummonerByDisplayName(displayName, region);
            if (summonerData != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "summoner", summonerData));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter summoner por display name", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
