package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.dto.PlayerDTO;
import br.com.lolmatchmaking.backend.service.PlayerService;
import br.com.lolmatchmaking.backend.service.RiotAPIService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/player")
@RequiredArgsConstructor
public class PlayerController {

    private final PlayerService playerService;
    private final RiotAPIService riotAPIService;

    /**
     * GET /api/player/current-details
     * Obtém detalhes do jogador atual
     */
    @GetMapping("/current-details")
    public ResponseEntity<Map<String, Object>> getCurrentPlayerDetails() {
        try {
            log.info("🔍 Obtendo detalhes do jogador atual");

            // Placeholder - implementar lógica real baseada no LCU
            Map<String, Object> response = Map.of(
                    "success", true,
                    "player", Map.of(
                            "summonerName", "TestPlayer",
                            "region", "br1",
                            "currentMmr", 1000,
                            "peakMmr", 1200,
                            "gamesPlayed", 0,
                            "wins", 0,
                            "losses", 0,
                            "winRate", 0.0));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("❌ Erro ao obter detalhes do jogador atual", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/player/register
     * Registra um novo jogador
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerPlayer(@RequestBody PlayerRegistrationRequest request) {
        try {
            log.info("➕ Registrando jogador: {}", request.getSummonerName());

            // Validar dados
            if (request.getSummonerName() == null || request.getSummonerName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Nome do invocador é obrigatório"));
            }

            if (request.getRegion() == null || request.getRegion().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Região é obrigatória"));
            }

            // Registrar jogador
            PlayerDTO player = playerService.registerPlayer(
                    request.getSummonerName(),
                    request.getRegion(),
                    request.getGameName(),
                    request.getTagLine());

            if (player != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "player", player));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Erro ao registrar jogador"));
            }

        } catch (Exception e) {
            log.error("❌ Erro ao registrar jogador", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/player/{playerId}
     * Obtém detalhes de um jogador específico
     */
    @GetMapping("/{playerId}")
    public ResponseEntity<Map<String, Object>> getPlayer(@PathVariable Long playerId) {
        try {
            log.info("🔍 Obtendo jogador: {}", playerId);

            PlayerDTO player = playerService.getPlayerById(playerId);
            if (player != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "player", player));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter jogador", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/player/{playerId}/stats
     * Obtém estatísticas de um jogador
     */
    @GetMapping("/{playerId}/stats")
    public ResponseEntity<Map<String, Object>> getPlayerStats(@PathVariable Long playerId) {
        try {
            log.info("📊 Obtendo estatísticas do jogador: {}", playerId);

            PlayerService.PlayerStats serviceStats = playerService.getPlayerStats(playerId);
            if (serviceStats != null) {
                PlayerStats stats = new PlayerStats();
                stats.setGamesPlayed(serviceStats.getGamesPlayed());
                stats.setWins(serviceStats.getWins());
                stats.setLosses(serviceStats.getLosses());
                stats.setWinRate(serviceStats.getWinRate());
                stats.setCurrentMmr(serviceStats.getCurrentMmr());
                stats.setPeakMmr(serviceStats.getPeakMmr());
                stats.setWinStreak(serviceStats.getWinStreak());
                stats.setCustomGamesPlayed(serviceStats.getCustomGamesPlayed());
                stats.setCustomWins(serviceStats.getCustomWins());
                stats.setCustomLosses(serviceStats.getCustomLosses());
                
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "stats", stats));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter estatísticas do jogador", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/stats/leaderboard
     * Obtém leaderboard de jogadores
     */
    @GetMapping("/leaderboard")
    public ResponseEntity<Map<String, Object>> getLeaderboard(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit) {
        try {
            log.info("🏆 Obtendo leaderboard (page: {}, limit: {})", page, limit);

            List<PlayerDTO> leaderboard = playerService.getLeaderboard(page, limit);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "leaderboard", leaderboard,
                    "page", page,
                    "limit", limit,
                    "total", leaderboard.size()));

        } catch (Exception e) {
            log.error("❌ Erro ao obter leaderboard", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/summoner/{displayName}
     * Busca invocador por nome de exibição
     */
    @GetMapping("/summoner/{displayName}")
    public ResponseEntity<Map<String, Object>> getSummonerByDisplayName(@PathVariable String displayName) {
        try {
            log.info("🔍 Buscando invocador: {}", displayName);

            Map<String, Object> summonerData = riotAPIService.getSummonerByDisplayName(displayName, "br1");
            if (summonerData != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "summoner", summonerData));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao buscar invocador", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/player/details/{displayName}
     * Obtém detalhes de jogador por nome de exibição
     */
    @GetMapping("/details/{displayName}")
    public ResponseEntity<Map<String, Object>> getPlayerDetails(@PathVariable String displayName) {
        try {
            log.info("🔍 Obtendo detalhes do jogador: {}", displayName);

            PlayerDTO player = playerService.getPlayerByDisplayName(displayName);
            if (player != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "player", player));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter detalhes do jogador", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/player/refresh-by-display-name
     * Atualiza dados do jogador via Riot API
     */
    @PostMapping("/refresh-by-display-name")
    public ResponseEntity<Map<String, Object>> refreshPlayerByDisplayName(@RequestBody RefreshPlayerRequest request) {
        try {
            log.info("🔄 Atualizando jogador: {}", request.getDisplayName());

            PlayerDTO player = playerService.refreshPlayerByDisplayName(
                    request.getDisplayName(),
                    request.getRegion());

            if (player != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "player", player));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Jogador não encontrado"));
            }

        } catch (Exception e) {
            log.error("❌ Erro ao atualizar jogador", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // DTOs
    @Data
    public static class PlayerRegistrationRequest {
        private String summonerName;
        private String region;
        private String gameName;
        private String tagLine;
    }

    @Data
    public static class RefreshPlayerRequest {
        private String displayName;
        private String region;
    }

    @Data
    public static class PlayerStats {
        private int gamesPlayed;
        private int wins;
        private int losses;
        private double winRate;
        private int currentMmr;
        private int peakMmr;
        private int winStreak;
        private int customGamesPlayed;
        private int customWins;
        private int customLosses;
        private double customWinRate;
        private int customLp;
    }
}
