package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.dto.MatchDTO;
import br.com.lolmatchmaking.backend.dto.PlayerDTO;
import br.com.lolmatchmaking.backend.domain.repository.MatchRepository;
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
}
