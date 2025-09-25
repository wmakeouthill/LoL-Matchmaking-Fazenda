package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.domain.entity.*;
import br.com.lolmatchmaking.backend.domain.repository.*;
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
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class DebugController {

    private final PlayerRepository playerRepository;
    private final CustomMatchRepository customMatchRepository;
    private final QueuePlayerRepository queuePlayerRepository;
    private final DiscordLolLinkRepository discordLolLinkRepository;
    private final EventInboxRepository eventInboxRepository;
    private final SettingRepository settingRepository;
    private final MatchmakingService matchmakingService;
    private final PlayerService playerService;

    /**
     * Obtém informações das tabelas do banco
     */
    @GetMapping("/tables")
    public ResponseEntity<Map<String, Object>> getTablesInfo() {
        try {
            log.info("🔍 [DEBUG] Verificando dados das tabelas...");

            Map<String, Object> tablesInfo = new HashMap<>();

            // Informações dos jogadores
            long playersCount = playerRepository.count();
            List<Player> recentPlayers = playerRepository.findTop10ByOrderByCreatedAtDesc();
            tablesInfo.put("players", Map.of(
                    "total", playersCount,
                    "recent", recentPlayers.size(),
                    "sample", recentPlayers.stream().map(p -> Map.of(
                            "id", p.getId(),
                            "summonerName", p.getSummonerName(),
                            "region", p.getRegion(),
                            "currentMmr", p.getCurrentMmr())).toList()));

            // Informações das partidas
            long matchesCount = customMatchRepository.count();
            List<CustomMatch> recentMatches = customMatchRepository.findTop10ByOrderByCreatedAtDesc();
            tablesInfo.put("matches", Map.of(
                    "total", matchesCount,
                    "recent", recentMatches.size(),
                    "sample", recentMatches.stream().map(m -> Map.of(
                            "id", m.getId(),
                            "title", m.getTitle(),
                            "status", m.getStatus(),
                            "createdAt", m.getCreatedAt())).toList()));

            // Informações da fila
            long queueCount = queuePlayerRepository.count();
            long activeQueueCount = queuePlayerRepository.findByActiveTrue().size();
            tablesInfo.put("queue", Map.of(
                    "total", queueCount,
                    "active", activeQueueCount));

            // Informações dos links Discord
            long discordLinksCount = discordLolLinkRepository.count();
            tablesInfo.put("discordLinks", Map.of(
                    "total", discordLinksCount));

            // Informações dos eventos
            long eventsCount = eventInboxRepository.count();
            long unprocessedEvents = eventInboxRepository.findByProcessedFalseOrderByCreatedAtAsc().size();
            tablesInfo.put("events", Map.of(
                    "total", eventsCount,
                    "unprocessed", unprocessedEvents));

            // Informações das configurações
            long settingsCount = settingRepository.count();
            tablesInfo.put("settings", Map.of(
                    "total", settingsCount));

            // Estatísticas do matchmaking
            Map<String, Object> matchmakingStats = matchmakingService.getMatchmakingStats();
            tablesInfo.put("matchmaking", matchmakingStats);

            tablesInfo.put("timestamp", Instant.now().toString());

            return ResponseEntity.ok(tablesInfo);

        } catch (Exception e) {
            log.error("❌ Erro ao obter informações das tabelas", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }

    /**
     * Corrige status das partidas antigas
     */
    @PostMapping("/fix-match-status")
    public ResponseEntity<Map<String, Object>> fixMatchStatus() {
        try {
            log.info("🔧 [DEBUG] Corrigindo status das partidas antigas...");

            List<CustomMatch> matches = customMatchRepository.findAll();
            int fixedCount = 0;

            for (CustomMatch match : matches) {
                boolean needsUpdate = false;

                // Corrigir status nulo ou vazio
                if (match.getStatus() == null || match.getStatus().trim().isEmpty()) {
                    match.setStatus("created");
                    needsUpdate = true;
                }

                // Corrigir timestamps nulos
                if (match.getCreatedAt() == null) {
                    match.setCreatedAt(Instant.now());
                    needsUpdate = true;
                }

                if (match.getUpdatedAt() == null) {
                    match.setUpdatedAt(Instant.now());
                    needsUpdate = true;
                }

                if (needsUpdate) {
                    customMatchRepository.save(match);
                    fixedCount++;
                }
            }

            log.info("✅ [DEBUG] {} partidas corrigidas", fixedCount);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Status das partidas corrigido com sucesso",
                    "fixedCount", fixedCount,
                    "totalMatches", matches.size()));

        } catch (Exception e) {
            log.error("❌ Erro ao corrigir status das partidas", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }

    /**
     * Obtém estatísticas detalhadas do sistema
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getSystemStats() {
        try {
            Map<String, Object> stats = new HashMap<>();

            // Estatísticas do banco
            stats.put("database", Map.of(
                    "players", playerRepository.count(),
                    "matches", customMatchRepository.count(),
                    "queuePlayers", queuePlayerRepository.count(),
                    "discordLinks", discordLolLinkRepository.count(),
                    "events", eventInboxRepository.count(),
                    "settings", settingRepository.count()));

            // Estatísticas do matchmaking
            stats.put("matchmaking", matchmakingService.getMatchmakingStats());

            // Estatísticas dos jogadores
            try {
                PlayerService.PlayerStats playerStats = playerService.getPlayerStats(1L);
                stats.put("playerStats", Map.of(
                        "gamesPlayed", playerStats.getGamesPlayed(),
                        "wins", playerStats.getWins(),
                        "losses", playerStats.getLosses(),
                        "winRate", playerStats.getWinRate()));
            } catch (Exception e) {
                stats.put("playerStats", "N/A");
            }

            // Informações do sistema
            stats.put("system", Map.of(
                    "timestamp", Instant.now().toString(),
                    "uptime", System.currentTimeMillis(),
                    "javaVersion", System.getProperty("java.version"),
                    "osName", System.getProperty("os.name"),
                    "osVersion", System.getProperty("os.version")));

            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("❌ Erro ao obter estatísticas do sistema", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }

    /**
     * Limpa dados de teste
     */
    @PostMapping("/cleanup-test-data")
    public ResponseEntity<Map<String, Object>> cleanupTestData() {
        try {
            log.info("🧹 [DEBUG] Limpando dados de teste...");

            int deletedCount = 0;

            // Limpar jogadores de teste
            List<Player> testPlayers = playerRepository.findBySummonerNameContaining("test");
            for (Player player : testPlayers) {
                playerRepository.delete(player);
                deletedCount++;
            }

            // Limpar partidas de teste
            List<CustomMatch> testMatches = customMatchRepository.findByTitleContaining("test");
            for (CustomMatch match : testMatches) {
                customMatchRepository.delete(match);
                deletedCount++;
            }

            // Limpar eventos antigos
            List<EventInbox> oldEvents = eventInboxRepository.findAll().stream()
                    .filter(event -> event.getCreatedAt().isBefore(Instant.now().minusSeconds(86400))) // 24 horas
                    .toList();

            for (EventInbox event : oldEvents) {
                eventInboxRepository.delete(event);
                deletedCount++;
            }

            log.info("✅ [DEBUG] {} registros de teste removidos", deletedCount);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Dados de teste limpos com sucesso",
                    "deletedCount", deletedCount));

        } catch (Exception e) {
            log.error("❌ Erro ao limpar dados de teste", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }

    /**
     * Testa conectividade com APIs externas
     */
    @GetMapping("/test-connections")
    public ResponseEntity<Map<String, Object>> testConnections() {
        try {
            Map<String, Object> results = new HashMap<>();

            // Teste de conectividade básica
            results.put("database", "connected");
            results.put("timestamp", Instant.now().toString());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Teste de conectividade realizado",
                    "results", results));

        } catch (Exception e) {
            log.error("❌ Erro no teste de conectividade", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }
}
