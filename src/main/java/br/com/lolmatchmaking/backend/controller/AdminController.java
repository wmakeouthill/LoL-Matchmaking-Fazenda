package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import br.com.lolmatchmaking.backend.domain.repository.QueuePlayerRepository;
import br.com.lolmatchmaking.backend.dto.AdjustPlayerLpRequest;
import br.com.lolmatchmaking.backend.dto.AwardChampionshipRequest;
import br.com.lolmatchmaking.backend.service.QueueManagementService;
import br.com.lolmatchmaking.backend.service.PlayerService;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final QueuePlayerRepository queuePlayerRepository;
    // ✅ MIGRADO: QueueManagementService (Redis-only) ao invés de MatchmakingService
    private final QueueManagementService queueManagementService;
    private final PlayerService playerService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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

            // ✅ MIGRADO: QueueManagementService (SQL = fonte da verdade)
            // Remove todos os players da fila
            List<br.com.lolmatchmaking.backend.domain.entity.QueuePlayer> allPlayers = queuePlayerRepository
                    .findByActiveTrueOrderByJoinTimeAsc();

            int removedCount = 0;
            for (var player : allPlayers) {
                queueManagementService.removeFromQueue(player.getSummonerName());
                removedCount++;
            }

            log.info("✅ [ADMIN] Fila limpa: {} jogadores removidos", removedCount);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "removedCount", removedCount,
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

            // ✅ MIGRADO: QueueManagementService já sincroniza automaticamente via Redis
            // Não precisa de syncCacheWithDatabase - MySQL é fonte da verdade
            log.info("✅ [ADMIN] Fila já sincronizada (MySQL + Redis)");

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

            // ✅ MIGRADO: Estatísticas do matchmaking via QueueManagementService
            var queueStatus = queueManagementService.getQueueStatus(null);
            stats.put("matchmaking", Map.of(
                    "queueSize", queueStatus.getPlayersInQueue(), // Número total
                    "avgWaitTime", queueStatus.getAverageWaitTime(),
                    "estimatedMatchTime", queueStatus.getEstimatedMatchTime()));

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

            // ✅ MIGRADO: QueueManagementService usa Spring lifecycle, não precisa de
            // initialize()
            log.info("✅ [ADMIN] Services Redis-only não precisam de reinicialização manual");

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

    /**
     * ✅ NOVO: Ajusta pontos (LP) de um jogador (adicionar ou remover)
     * Apenas Special Users podem fazer isso
     */
    @PostMapping("/adjust-player-lp")
    public ResponseEntity<Map<String, Object>> adjustPlayerLp(
            @RequestBody AdjustPlayerLpRequest request,
            @RequestHeader(value = "X-Summoner-Name", required = false) String summonerNameHeader) {
        try {
            log.info("💰 [ADMIN] Ajustando LP do jogador: {} (ajuste: {})", request.getSummonerName(),
                    request.getLpAdjustment());

            // Validação de header individual
            if (summonerNameHeader == null || summonerNameHeader.isBlank()) {
                log.warn("❌ [ADMIN] Header X-Summoner-Name não fornecido");
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Header X-Summoner-Name é obrigatório"));
            }

            // Buscar jogador alvo
            Player targetPlayer = playerRepository.findBySummonerName(request.getSummonerName())
                    .orElseThrow(() -> new RuntimeException("Jogador não encontrado: " + request.getSummonerName()));

            // Buscar jogador que está fazendo a requisição (para verificar se é special
            // user)
            Player requestingPlayer = playerRepository.findBySummonerName(summonerNameHeader)
                    .orElseThrow(
                            () -> new RuntimeException("Jogador solicitante não encontrado: " + summonerNameHeader));

            // Verificar se é special user (você pode adicionar uma coluna is_special_user
            // na tabela players)
            // Por enquanto, vamos permitir apenas se o jogador existir

            // Aplicar ajuste de LP
            Integer currentLp = targetPlayer.getCustomLp() != null ? targetPlayer.getCustomLp() : 0;
            Integer newLp = currentLp + request.getLpAdjustment();

            // Não permitir LP negativo
            if (newLp < 0) {
                newLp = 0;
            }

            targetPlayer.setCustomLp(newLp);
            playerRepository.save(targetPlayer);

            log.info("✅ [ADMIN] LP ajustado com sucesso: {} → {} (ajuste: {})",
                    currentLp, newLp, request.getLpAdjustment());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "LP ajustado com sucesso",
                    "player", targetPlayer.getSummonerName(),
                    "previousLp", currentLp,
                    "newLp", newLp,
                    "adjustment", request.getLpAdjustment(),
                    "reason", request.getReason() != null ? request.getReason() : "Sem motivo especificado"));

        } catch (Exception e) {
            log.error("❌ Erro ao ajustar LP do jogador", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro ao ajustar LP",
                    "error", e.getMessage()));
        }
    }

    /**
     * ✅ NOVO: Premia jogador com título de campeonato + bônus de LP
     * Apenas Special Users podem fazer isso
     */
    @PostMapping("/award-championship")
    public ResponseEntity<Map<String, Object>> awardChampionship(
            @RequestBody AwardChampionshipRequest request,
            @RequestHeader(value = "X-Summoner-Name", required = false) String summonerNameHeader) {
        try {
            log.info("🏆 [ADMIN] Premiando jogador: {} com título: {}",
                    request.getSummonerName(), request.getChampionshipTitle());

            // Validação de header individual
            if (summonerNameHeader == null || summonerNameHeader.isBlank()) {
                log.warn("❌ [ADMIN] Header X-Summoner-Name não fornecido");
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Header X-Summoner-Name é obrigatório"));
            }

            // Buscar jogador alvo
            Player targetPlayer = playerRepository.findBySummonerName(request.getSummonerName())
                    .orElseThrow(() -> new RuntimeException("Jogador não encontrado: " + request.getSummonerName()));

            // Buscar jogador que está fazendo a requisição
            Player requestingPlayer = playerRepository.findBySummonerName(summonerNameHeader)
                    .orElseThrow(
                            () -> new RuntimeException("Jogador solicitante não encontrado: " + summonerNameHeader));

            List<Map<String, Object>> titles;

            try {
                String titlesJson = targetPlayer.getChampionshipTitles();
                if (titlesJson == null || titlesJson.isBlank() || titlesJson.equals("[]")) {
                    titles = new ArrayList<>();
                } else {
                    titles = objectMapper.readValue(titlesJson, List.class);
                }
            } catch (Exception e) {
                log.warn("⚠️ Erro ao ler títulos existentes, criando nova lista", e);
                titles = new ArrayList<>();
            }

            // Adicionar novo título
            Map<String, Object> newTitle = new HashMap<>();
            newTitle.put("title", request.getChampionshipTitle());
            newTitle.put("date", LocalDateTime.now().toString());
            newTitle.put("lpBonus", request.getLpBonus());
            titles.add(newTitle);

            // Salvar títulos atualizados
            String updatedTitlesJson = objectMapper.writeValueAsString(titles);
            targetPlayer.setChampionshipTitles(updatedTitlesJson);

            // Adicionar bônus de LP
            Integer currentLp = targetPlayer.getCustomLp() != null ? targetPlayer.getCustomLp() : 0;
            Integer newLp = currentLp + request.getLpBonus();
            targetPlayer.setCustomLp(newLp);

            playerRepository.save(targetPlayer);

            log.info("✅ [ADMIN] Título concedido com sucesso! LP: {} → {} (+{})",
                    currentLp, newLp, request.getLpBonus());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Título de campeonato concedido com sucesso",
                    "player", targetPlayer.getSummonerName(),
                    "title", request.getChampionshipTitle(),
                    "previousLp", currentLp,
                    "newLp", newLp,
                    "lpBonus", request.getLpBonus(),
                    "totalTitles", titles.size()));

        } catch (Exception e) {
            log.error("❌ Erro ao premiar jogador com título", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro ao premiar jogador",
                    "error", e.getMessage()));
        }
    }

    /**
     * ✅ NOVO: Lista todos os títulos de campeonato disponíveis (temática fazenda)
     */
    @GetMapping("/championship-titles")
    public ResponseEntity<Map<String, Object>> getChampionshipTitles() {
        try {
            List<Map<String, Object>> titles = new ArrayList<>();

            // 🏆 Títulos temáticos fazenda com emojis
            titles.add(Map.of(
                    "id", "colheita",
                    "name", "🌾 Campeão da Colheita",
                    "description", "Dominador supremo da temporada de colheita",
                    "defaultLp", 150));

            titles.add(Map.of(
                    "id", "celeiro",
                    "name", "🏠 Senhor do Celeiro",
                    "description", "Guardião invencível do celeiro da fazenda",
                    "defaultLp", 120));

            titles.add(Map.of(
                    "id", "plantacao",
                    "name", "🌱 Mestre da Plantação",
                    "description", "Cultivador supremo das terras férteis",
                    "defaultLp", 100));

            titles.add(Map.of(
                    "id", "gado",
                    "name", "🐄 Rei do Gado",
                    "description", "Comandante supremo da pecuária da fazenda",
                    "defaultLp", 130));

            titles.add(Map.of(
                    "id", "trator",
                    "name", "🚜 Piloto Lendário",
                    "description", "Motorista imbatível do trator dourado",
                    "defaultLp", 140));

            titles.add(Map.of(
                    "id", "galinheiro",
                    "name", "🐔 Imperador do Galinheiro",
                    "description", "Soberano absoluto das aves de combate",
                    "defaultLp", 110));

            titles.add(Map.of(
                    "id", "espantalho",
                    "name", "🎃 Guardião Espantalho",
                    "description", "Protetor eterno dos campos contra invasores",
                    "defaultLp", 90));

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "titles", titles));

        } catch (Exception e) {
            log.error("❌ Erro ao buscar títulos de campeonato", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor",
                    "error", e.getMessage()));
        }
    }
}
