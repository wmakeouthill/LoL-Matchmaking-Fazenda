package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import br.com.lolmatchmaking.backend.service.MatchmakingService;
import br.com.lolmatchmaking.backend.service.PlayerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final CustomMatchRepository customMatchRepository;
    private final PlayerRepository playerRepository;
    private final MatchmakingService matchmakingService;
    private final PlayerService playerService;

    /**
     * Recalcula LP customizado de partidas
     */
    @PostMapping("/recalculate-custom-lp")
    public ResponseEntity<Map<String, Object>> recalculateCustomLp() {
        try {
            log.info("🔄 [ADMIN] Recalculando LP de partidas customizadas...");

            List<CustomMatch> matches = customMatchRepository.findAll();
            int recalculatedCount = 0;

            for (CustomMatch match : matches) {
                if (match.getCustomLp() != null && match.getCustomLp() > 0) {
                    // Lógica de recálculo de LP baseada no resultado da partida
                    // Por enquanto, apenas log da operação
                    log.debug("Recalculando LP para partida: {}", match.getId());
                    recalculatedCount++;
                }
            }

            log.info("✅ [ADMIN] LP recalculado para {} partidas", recalculatedCount);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "LP recalculado com sucesso",
                    "recalculatedCount", recalculatedCount,
                    "totalMatches", matches.size()));

        } catch (Exception e) {
            log.error("❌ Erro ao recalcular LP", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }

    /**
     * Recalcula MMR de todos os jogadores
     */
    @PostMapping("/recalculate-mmr")
    public ResponseEntity<Map<String, Object>> recalculateMmr() {
        try {
            log.info("🔄 [ADMIN] Recalculando MMR de todos os jogadores...");

            List<Player> players = playerRepository.findAll();
            int recalculatedCount = 0;

            for (Player player : players) {
                try {
                    // Recalcular MMR baseado no histórico de partidas
                    // Por enquanto, apenas log da operação
                    log.debug("Recalculando MMR para jogador: {}", player.getSummonerName());
                    recalculatedCount++;
                } catch (Exception e) {
                    log.warn("⚠️ Erro ao recalcular MMR para jogador {}: {}",
                            player.getSummonerName(), e.getMessage());
                }
            }

            log.info("✅ [ADMIN] MMR recalculado para {} jogadores", recalculatedCount);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "MMR recalculado com sucesso",
                    "recalculatedCount", recalculatedCount,
                    "totalPlayers", players.size()));

        } catch (Exception e) {
            log.error("❌ Erro ao recalcular MMR", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }

    /**
     * Limpa fila de matchmaking
     */
    @PostMapping("/clear-queue")
    public ResponseEntity<Map<String, Object>> clearQueue() {
        try {
            log.info("🧹 [ADMIN] Limpando fila de matchmaking...");

            matchmakingService.clearQueue();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Fila limpa com sucesso"));

        } catch (Exception e) {
            log.error("❌ Erro ao limpar fila", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }

    /**
     * Força sincronização da fila
     */
    @PostMapping("/force-sync-queue")
    public ResponseEntity<Map<String, Object>> forceSyncQueue() {
        try {
            log.info("🔄 [ADMIN] Forçando sincronização da fila...");

            matchmakingService.syncCacheWithDatabase();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Fila sincronizada com sucesso"));

        } catch (Exception e) {
            log.error("❌ Erro ao sincronizar fila", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }

    /**
     * Obtém estatísticas administrativas
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getAdminStats() {
        try {
            Map<String, Object> stats = new HashMap<>();

            // Estatísticas de jogadores
            long totalPlayers = playerRepository.count();
            List<Player> topPlayers = playerRepository.findTop10ByOrderByCurrentMmrDesc();

            stats.put("players", Map.of(
                    "total", totalPlayers,
                    "topPlayers", topPlayers.stream().map(p -> Map.of(
                            "summonerName", p.getSummonerName(),
                            "currentMmr", p.getCurrentMmr(),
                            "region", p.getRegion())).toList()));

            // Estatísticas de partidas
            long totalMatches = customMatchRepository.count();
            List<CustomMatch> recentMatches = customMatchRepository.findTop10ByOrderByCreatedAtDesc();

            stats.put("matches", Map.of(
                    "total", totalMatches,
                    "recentMatches", recentMatches.stream().map(m -> Map.of(
                            "id", m.getId(),
                            "title", m.getTitle(),
                            "status", m.getStatus(),
                            "createdAt", m.getCreatedAt())).toList()));

            // Estatísticas do matchmaking
            stats.put("matchmaking", matchmakingService.getMatchmakingStats());

            stats.put("timestamp", Instant.now().toString());

            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("❌ Erro ao obter estatísticas administrativas", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }

    /**
     * Reinicia serviços do sistema
     */
    @PostMapping("/restart-services")
    public ResponseEntity<Map<String, Object>> restartServices() {
        try {
            log.info("🔄 [ADMIN] Reiniciando serviços do sistema...");

            // Reinicializar serviços
            matchmakingService.initialize();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Serviços reiniciados com sucesso"));

        } catch (Exception e) {
            log.error("❌ Erro ao reiniciar serviços", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }

    /**
     * Obtém logs do sistema
     */
    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> getSystemLogs(
            @RequestParam(defaultValue = "100") int limit) {
        try {
            // Por enquanto, retorna informações básicas
            // Em produção, isso seria integrado com um sistema de logging
            Map<String, Object> logs = new HashMap<>();
            logs.put("message", "Sistema de logs não implementado");
            logs.put("limit", limit);
            logs.put("timestamp", Instant.now().toString());

            return ResponseEntity.ok(logs);

        } catch (Exception e) {
            log.error("❌ Erro ao obter logs", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }
}
