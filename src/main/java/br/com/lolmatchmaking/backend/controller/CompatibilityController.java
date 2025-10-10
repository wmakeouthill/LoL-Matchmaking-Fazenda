package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.dto.MatchDTO;
import br.com.lolmatchmaking.backend.dto.PlayerDTO;
import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.MatchRepository;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import br.com.lolmatchmaking.backend.service.MatchHistoryService;
import br.com.lolmatchmaking.backend.service.PlayerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CompatibilityController {

    private final MatchHistoryService matchHistoryService;
    private final PlayerService playerService;
    private final MatchRepository matchRepository;
    private final PlayerRepository playerRepository;

    // GET /api/matches/recent
    @GetMapping("/matches/recent")
    public ResponseEntity<List<MatchDTO>> getRecentMatches(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit) {
        Page<MatchDTO> p = matchHistoryService.getRecentMatches(page, limit);
        return ResponseEntity.ok(p.getContent());
    }

    // DELETE /api/matches/{id}
    @DeleteMapping("/matches/{id}")
    public ResponseEntity<Map<String, Object>> deleteMatch(@PathVariable Long id) {
        try {
            if (!matchRepository.existsById(id)) {
                return ResponseEntity.notFound().build();
            }
            matchRepository.deleteById(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("Erro ao deletar partida {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // GET /api/matches/custom/{identifier}
    @GetMapping("/matches/custom/{identifier}")
    public ResponseEntity<Map<String, Object>> getCustomMatches(@PathVariable String identifier,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            Page<MatchDTO> p = matchHistoryService.getMatchesByPlayer(identifier, offset, limit);
            Map<String, Object> pagination = Map.of(
                    "page", p.getNumber(),
                    "size", p.getSize(),
                    "total", p.getTotalElements(),
                    "totalPages", p.getTotalPages());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "matches", p.getContent(),
                    "pagination", pagination));
        } catch (IllegalArgumentException iae) {
            log.warn("Cache not configured or unavailable, using no-cache fallback for matches by player: {}",
                    identifier);
            Page<MatchDTO> p = matchHistoryService.getMatchesByPlayerNoCache(identifier, offset, limit);
            Map<String, Object> pagination = Map.of(
                    "page", p.getNumber(),
                    "size", p.getSize(),
                    "total", p.getTotalElements(),
                    "totalPages", p.getTotalPages());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "matches", p.getContent(),
                    "pagination", pagination));
        }
    }

    // GET /api/matches/custom/{identifier}/count
    @GetMapping("/matches/custom/{identifier}/count")
    public ResponseEntity<Map<String, Object>> getCustomMatchesCount(@PathVariable String identifier) {
        try {
            Page<MatchDTO> p = matchHistoryService.getMatchesByPlayer(identifier, 0, 1);
            return ResponseEntity.ok(Map.of("success", true, "count", p.getTotalElements()));
        } catch (IllegalArgumentException iae) {
            log.warn("Cache missing - using no-cache fallback for matches count for: {}", identifier);
            Page<MatchDTO> p = matchHistoryService.getMatchesByPlayerNoCache(identifier, 0, 1);
            return ResponseEntity.ok(Map.of("success", true, "count", p.getTotalElements()));
        }
    }

    // GET /api/stats/participants-leaderboard
    @GetMapping("/stats/participants-leaderboard")
    public ResponseEntity<Map<String, Object>> getParticipantsLeaderboard(
            @RequestParam(defaultValue = "50") int limit) {
        List<PlayerDTO> leaderboard = playerService.getLeaderboard(0, limit);
        return ResponseEntity.ok(Map.of("success", true, "data", leaderboard));
    }

    // POST /api/stats/update-leaderboard
    @PostMapping("/stats/update-leaderboard")
    public ResponseEntity<Map<String, Object>> updateLeaderboardStats() {
        try {
            log.info("🔄 Atualizando estatísticas do leaderboard...");
            int updatedCount = playerService.updateAllPlayersCustomStats();
            log.info("✅ {} jogadores atualizados com sucesso", updatedCount);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Estatísticas atualizadas com sucesso",
                    "updatedPlayers", updatedCount));
        } catch (Exception e) {
            log.error("❌ Erro ao atualizar estatísticas do leaderboard", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // POST /api/stats/update-champion-stats?forceUpdate=true
    @PostMapping("/stats/update-champion-stats")
    public ResponseEntity<Map<String, Object>> updateChampionStats(
            @RequestParam(defaultValue = "false") boolean forceUpdate) {
        try {
            log.info("🔄 Atualizando estatísticas detalhadas de campeões... (forçar: {})", forceUpdate);

            // Atualizar estatísticas básicas primeiro
            int basicUpdated = playerService.updateAllPlayersCustomStats();
            log.info("✅ Estatísticas básicas: {} jogadores", basicUpdated);

            // Atualizar estatísticas detalhadas de campeões (custom + Riot API)
            // Usar repository diretamente para ter acesso ao tipo Instant
            List<Player> allPlayers = playerRepository.findAll();

            // Priorizar jogadores: sem dados primeiro, depois os mais antigos
            java.time.Instant fiveDaysAgo = java.time.Instant.now().minus(5, java.time.temporal.ChronoUnit.DAYS);

            List<Player> playersNeedingUpdate = allPlayers.stream()
                    .filter(p -> {
                        // Sempre atualizar se não tem dados ou dados estão antigos (> 5 dias)
                        if (p.getStatsLastUpdated() == null) {
                            return true;
                        }
                        return p.getStatsLastUpdated().isBefore(fiveDaysAgo);
                    })
                    .sorted((p1, p2) -> {
                        // Jogadores sem dados primeiro, depois por data mais antiga
                        if (p1.getStatsLastUpdated() == null && p2.getStatsLastUpdated() != null)
                            return -1;
                        if (p1.getStatsLastUpdated() != null && p2.getStatsLastUpdated() == null)
                            return 1;
                        if (p1.getStatsLastUpdated() == null && p2.getStatsLastUpdated() == null)
                            return 0;
                        return p1.getStatsLastUpdated().compareTo(p2.getStatsLastUpdated());
                    })
                    .toList();

            int championStatsUpdated = 0;
            int skippedRecent = allPlayers.size() - playersNeedingUpdate.size();

            log.info("📊 Total de jogadores: {}", allPlayers.size());
            log.info("📊 Jogadores precisando atualização: {}", playersNeedingUpdate.size());
            log.info("📊 Jogadores com dados recentes (< 5 dias): {}", skippedRecent);

            // Atualizar apenas jogadores que precisam (sem dados ou dados antigos)
            for (Player player : playersNeedingUpdate) {
                try {
                    // Sempre passa forceUpdate=false para respeitar a lógica de 5 dias no método
                    // O método já vai atualizar porque esses jogadores não têm dados ou estão com
                    // dados antigos
                    playerService.updatePlayerChampionStats(player.getSummonerName(), false);
                    championStatsUpdated++;
                } catch (Exception e) {
                    log.warn("⚠️ Erro ao atualizar stats de campeões para {}: {}",
                            player.getSummonerName(), e.getMessage());
                }
            }

            log.info("✅ {} jogadores atualizados, {} pulados (dados recentes)",
                    championStatsUpdated, skippedRecent);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Estatísticas de campeões atualizadas com sucesso",
                    "basicStatsUpdated", basicUpdated,
                    "championStatsUpdated", championStatsUpdated,
                    "skippedRecent", skippedRecent,
                    "forceUpdate", forceUpdate));
        } catch (Exception e) {
            log.error("❌ Erro ao atualizar estatísticas de campeões", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
