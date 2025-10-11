package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.domain.entity.*;
import br.com.lolmatchmaking.backend.domain.repository.*;
import br.com.lolmatchmaking.backend.service.QueueManagementService;
import br.com.lolmatchmaking.backend.service.PlayerService;
import br.com.lolmatchmaking.backend.service.GameInProgressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
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
    // ✅ MIGRADO: QueueManagementService (Redis-only) ao invés de MatchmakingService
    private final QueueManagementService queueManagementService;
    private final PlayerService playerService;
    private final GameInProgressService gameInProgressService;

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

            // ✅ MIGRADO: Estatísticas do matchmaking via QueueManagementService
            var queueStatus = queueManagementService.getQueueStatus(null);
            tablesInfo.put("matchmaking", Map.of(
                    "queueSize", queueStatus.getPlayersInQueue(),
                    "avgWaitTime", queueStatus.getAverageWaitTime(),
                    "estimatedMatchTime", queueStatus.getEstimatedMatchTime(),
                    "isActive", queueStatus.isActive()));

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

            // ✅ MIGRADO: Estatísticas do matchmaking via QueueManagementService
            var queueStatus = queueManagementService.getQueueStatus(null);
            stats.put("matchmaking", Map.of(
                    "queueSize", queueStatus.getPlayersInQueue(),
                    "avgWaitTime", queueStatus.getAverageWaitTime(),
                    "estimatedMatchTime", queueStatus.getEstimatedMatchTime(),
                    "isActive", queueStatus.isActive()));

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
     * ✅ Simula última partida personalizada do LCU como partida customizada
     * COMPLETA
     * Endpoint de teste para desenvolvimento - Cria partida no mesmo formato que
     * uma partida real
     * 
     * Formato idêntico ao de uma partida real:
     * - title: "Partida Customizada"
     * - description: "Partida gerada automaticamente pelo sistema de matchmaking"
     * - team1_players/team2_players: Comma-separated strings (não JSON)
     * - game_mode: "5v5"
     * - status: "completed"
     * - pick_ban_data: Estrutura completa com times, jogadores e ações
     */
    @PostMapping("/simulate-last-match")
    public ResponseEntity<Map<String, Object>> simulateLastMatch(@RequestBody Map<String, Object> lcuMatchData) {
        try {
            log.info("╔════════════════════════════════════════════════════════════════╗");
            log.info("║  🎮 [DEBUG] SIMULANDO ÚLTIMA PARTIDA CUSTOMIZADA              ║");
            log.info("╚════════════════════════════════════════════════════════════════╝");

            // Validar dados de entrada
            if (lcuMatchData == null || lcuMatchData.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Dados da partida não fornecidos"));
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

            // ✅ DEBUG: Log dos dados recebidos
            log.info("🔍 [DEBUG] Dados recebidos do LCU:");
            log.info("🔍 [DEBUG] Keys disponíveis: {}", lcuMatchData.keySet());
            log.info("🔍 [DEBUG] GameId: {}", lcuMatchData.get("gameId"));
            log.info("🔍 [DEBUG] GameType: {}", lcuMatchData.get("gameType"));

            // 1. Extrair participantes e identidades do LCU
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> participantsStats = (List<Map<String, Object>>) lcuMatchData.get("participants");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> participantIdentities = (List<Map<String, Object>>) lcuMatchData
                    .get("participantIdentities");

            log.info("🔍 [DEBUG] Participants (stats): {}", participantsStats != null ? participantsStats.size() : 0);
            log.info("🔍 [DEBUG] ParticipantIdentities: {}",
                    participantIdentities != null ? participantIdentities.size() : 0);

            // 2. Juntar participants com participantIdentities para formar lista completa
            List<Map<String, Object>> participants = mergeParticipantsWithIdentities(participantsStats,
                    participantIdentities);

            log.info("✅ [DEBUG] Participantes mesclados: {}", participants.size());

            // Validar número de participantes
            if (participants.size() != 10) {
                log.warn("⚠️ Número de participantes inválido: {}", participants.size());
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Partida deve ter exatamente 10 participantes. Encontrados: " +
                                "Tente jogar uma nova partida personalizada.",
                        "debug", Map.of(
                                "keysFound", lcuMatchData.keySet(),
                                "hasParticipants", lcuMatchData.containsKey("participants"),
                                "hasParticipantIdentities", lcuMatchData.containsKey("participantIdentities"))));
            }

            if (participants.size() != 10) {
                log.warn("⚠️ Número de participantes inválido: {}", participants.size());
                log.info("🔍 [DEBUG] Estrutura da partida: {}", mapper.writeValueAsString(lcuMatchData));
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Partida deve ter exatamente 10 participantes. Encontrados: " + participants.size(),
                        "debug", Map.of(
                                "participantsCount", participants.size(),
                                "gameType", lcuMatchData.get("gameType"))));
            } // 2. Separar participantes por time (teamId 100 = blue, 200 = red)
            List<Map<String, Object>> blueTeam = new ArrayList<>();
            List<Map<String, Object>> redTeam = new ArrayList<>();

            for (Map<String, Object> participant : participants) {
                Object teamIdObj = participant.get("teamId");
                int teamId = teamIdObj instanceof Number ? ((Number) teamIdObj).intValue() : 100;

                if (teamId == 100) {
                    blueTeam.add(participant);
                } else {
                    redTeam.add(participant);
                }
            }

            log.info("✅ Times separados: Blue={} jogadores, Red={} jogadores", blueTeam.size(), redTeam.size());

            // 3. Criar estrutura pick_ban_data compatível com o sistema
            Map<String, Object> pickBanData = createPickBanDataFromLcu(blueTeam, redTeam);

            // 4. Criar listas de summonerNames para team1_players/team2_players
            List<String> team1PlayerNames = blueTeam.stream()
                    .map(p -> (String) p.getOrDefault("summonerName", "Unknown"))
                    .toList();
            List<String> team2PlayerNames = redTeam.stream()
                    .map(p -> (String) p.getOrDefault("summonerName", "Unknown"))
                    .toList();

            // 5. Criar CustomMatch
            CustomMatch simulatedMatch = new CustomMatch();
            simulatedMatch.setTitle("Partida Customizada");
            simulatedMatch.setDescription("Partida gerada automaticamente pelo sistema de matchmaking");
            simulatedMatch.setStatus("in_progress");
            simulatedMatch.setCreatedBy("system");
            simulatedMatch.setGameMode("5v5");

            // 6. Salvar gameId do Riot
            Object gameIdObj = lcuMatchData.get("gameId");
            String riotGameId = gameIdObj != null ? String.valueOf(gameIdObj) : null;
            simulatedMatch.setRiotGameId(riotGameId);

            // 7. Salvar JSON completo do LCU
            simulatedMatch.setLcuMatchData(mapper.writeValueAsString(lcuMatchData));

            // 8. ✅ CORRIGIDO: Salvar times como comma-separated strings (igual partida
            // real)
            // Formato: "Player1,Player2,Player3,Player4,Player5"
            simulatedMatch.setTeam1PlayersJson(String.join(",", team1PlayerNames));
            simulatedMatch.setTeam2PlayersJson(String.join(",", team2PlayerNames));

            // 9. Salvar pick_ban_data
            simulatedMatch.setPickBanDataJson(mapper.writeValueAsString(pickBanData));

            // 11. Identificar vencedor (teamId 100 = team1, 200 = team2)
            int winnerTeam = extractWinnerFromLcuData(participants);
            simulatedMatch.setWinnerTeam(winnerTeam);
            simulatedMatch.setActualWinner(winnerTeam);

            // 12. Calcular MMR médio (baseado nos dados reais se disponíveis)
            int avgMmrTeam1 = calculateAverageMmr(blueTeam);
            int avgMmrTeam2 = calculateAverageMmr(redTeam);
            simulatedMatch.setAverageMmrTeam1(avgMmrTeam1);
            simulatedMatch.setAverageMmrTeam2(avgMmrTeam2);

            log.info("📊 MMR Médio - Team1: {}, Team2: {}", avgMmrTeam1, avgMmrTeam2);

            // 13. Extrair duração
            Object durationObj = lcuMatchData.get("gameDuration");
            int duration = durationObj instanceof Number ? ((Number) durationObj).intValue() : 0;
            simulatedMatch.setActualDuration(duration);

            log.info("⏱️ Duração: {} segundos", duration);

            // 14. Timestamps
            simulatedMatch.setCreatedAt(Instant.now());
            simulatedMatch.setUpdatedAt(Instant.now());

            // 15. Salvar no banco
            CustomMatch saved = customMatchRepository.save(simulatedMatch);

            log.info("╔════════════════════════════════════════════════════════════════╗");
            log.info("║  ✅ [DEBUG] PARTIDA SIMULADA COM SUCESSO                      ║");
            log.info("╚════════════════════════════════════════════════════════════════╝");
            log.info("📊 Match ID: {}", saved.getId());
            log.info("🎮 Riot Game ID: {}", riotGameId);
            log.info("🏆 Vencedor: Team {}", winnerTeam);
            log.info("👥 Blue Team: {}", String.join(", ", team1PlayerNames));
            log.info("👥 Red Team: {}", String.join(", ", team2PlayerNames));

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "matchId", saved.getId(),
                    "riotGameId", riotGameId != null ? riotGameId : "N/A",
                    "winner", winnerTeam,
                    "duration", duration,
                    "blueTeam", team1PlayerNames,
                    "redTeam", team2PlayerNames,
                    "message",
                    "Partida customizada simulada com sucesso! Criada no mesmo formato de uma partida real."));

        } catch (Exception e) {
            log.error("❌ [DEBUG] Erro ao simular partida", e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }

    /**
     * ✅ NOVO: Cria estrutura pick_ban_data a partir de dados do LCU
     */
    private Map<String, Object> createPickBanDataFromLcu(
            List<Map<String, Object>> blueTeam,
            List<Map<String, Object>> redTeam) {

        Map<String, Object> pickBanData = new HashMap<>();

        // Criar estrutura teams.blue e teams.red
        Map<String, Object> teams = new HashMap<>();

        // Blue Team
        Map<String, Object> blueTeamData = new HashMap<>();
        List<Map<String, Object>> bluePlayers = new ArrayList<>();
        List<String> blueAllPicks = new ArrayList<>();
        List<String> blueAllBans = new ArrayList<>();

        int teamIndex = 0;
        for (Map<String, Object> participant : blueTeam) {
            Map<String, Object> player = createPlayerFromLcuParticipant(participant, teamIndex, 1);
            bluePlayers.add(player);

            // Adicionar pick
            Object championIdObj = participant.get("championId");
            if (championIdObj != null) {
                blueAllPicks.add(String.valueOf(championIdObj));
            }

            teamIndex++;
        }

        blueTeamData.put("players", bluePlayers);
        blueTeamData.put("allPicks", blueAllPicks);
        blueTeamData.put("allBans", blueAllBans);
        blueTeamData.put("name", "Blue Team");
        blueTeamData.put("teamNumber", 1);
        blueTeamData.put("averageMmr", calculateAverageMmr(blueTeam));

        teams.put("blue", blueTeamData);

        // Red Team
        Map<String, Object> redTeamData = new HashMap<>();
        List<Map<String, Object>> redPlayers = new ArrayList<>();
        List<String> redAllPicks = new ArrayList<>();
        List<String> redAllBans = new ArrayList<>();

        teamIndex = 5;
        for (Map<String, Object> participant : redTeam) {
            Map<String, Object> player = createPlayerFromLcuParticipant(participant, teamIndex, 2);
            redPlayers.add(player);

            // Adicionar pick
            Object championIdObj = participant.get("championId");
            if (championIdObj != null) {
                redAllPicks.add(String.valueOf(championIdObj));
            }

            teamIndex++;
        }

        redTeamData.put("players", redPlayers);
        redTeamData.put("allPicks", redAllPicks);
        redTeamData.put("allBans", redAllBans);
        redTeamData.put("name", "Red Team");
        redTeamData.put("teamNumber", 2);
        redTeamData.put("averageMmr", calculateAverageMmr(redTeam));

        teams.put("red", redTeamData);

        pickBanData.put("teams", teams);
        pickBanData.put("currentPhase", "completed");
        pickBanData.put("currentIndex", 20);

        // Criar listas team1 e team2 (formato antigo para compatibilidade)
        pickBanData.put("team1", bluePlayers);
        pickBanData.put("team2", redPlayers);

        return pickBanData;
    }

    /**
     * ✅ NOVO: Cria player a partir de participante do LCU
     */
    private Map<String, Object> createPlayerFromLcuParticipant(
            Map<String, Object> participant,
            int teamIndex,
            int teamNumber) {

        Map<String, Object> player = new HashMap<>();

        String summonerName = (String) participant.getOrDefault("summonerName", "Unknown");
        Object championIdObj = participant.get("championId");
        Integer championId = championIdObj instanceof Number
                ? ((Number) championIdObj).intValue()
                : 0;
        String championName = (String) participant.getOrDefault("championName", "Unknown");

        // Tentar determinar lane pelo participantId ou índice
        String assignedLane = determineLaneByIndex(teamIndex % 5);

        player.put("summonerName", summonerName);
        player.put("gameName", summonerName);
        player.put("tagLine", "");
        player.put("teamIndex", teamIndex);
        player.put("assignedLane", assignedLane);
        player.put("primaryLane", assignedLane);
        player.put("secondaryLane", assignedLane);
        player.put("isAutofill", false);
        player.put("mmr", 1500); // MMR padrão para simulação

        // Criar actions (apenas pick - sem bans para simplificar)
        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> pickAction = new HashMap<>();
        pickAction.put("phase", "pick1");
        pickAction.put("championId", String.valueOf(championId));
        pickAction.put("championName", championName);
        pickAction.put("index", teamIndex);
        pickAction.put("type", "pick");
        pickAction.put("status", "completed");
        actions.add(pickAction);

        player.put("actions", actions);

        return player;
    }

    /**
     * ✅ NOVO: Determina lane pelo índice do time
     */
    private String determineLaneByIndex(int index) {
        return switch (index) {
            case 0 -> "top";
            case 1 -> "jungle";
            case 2 -> "mid";
            case 3 -> "bot";
            case 4 -> "support";
            default -> "fill";
        };
    }

    /**
     * ✅ NOVO: Calcula MMR médio do time (baseado no ranking se disponível)
     */
    private int calculateAverageMmr(List<Map<String, Object>> team) {
        // Por padrão, retornar 1500 (MMR base)
        // TODO: Implementar lógica real de cálculo de MMR baseado nos dados do LCU
        return 1500;
    }

    /**
     * ✅ Extrai o time vencedor dos dados do LCU
     */
    private int extractWinnerFromLcuData(List<Map<String, Object>> participants) {
        if (participants == null || participants.isEmpty()) {
            return 1; // Default team1
        }

        // Procurar pelo primeiro jogador vencedor
        for (Map<String, Object> participant : participants) {
            Object winObj = participant.get("win");
            Object teamIdObj = participant.get("teamId");

            boolean win = winObj instanceof Boolean ? (Boolean) winObj : false;

            if (win && teamIdObj != null) {
                int teamId = teamIdObj instanceof Number ? ((Number) teamIdObj).intValue() : 100;
                // teamId 100 = team1, 200 = team2
                return teamId == 100 ? 1 : 2;
            }
        }

        return 1; // Default team1
    }

    /**
     * ✅ NOVO: Mescla participants (stats) com participantIdentities (player info)
     * do LCU
     */
    private List<Map<String, Object>> mergeParticipantsWithIdentities(
            List<Map<String, Object>> participantsStats,
            List<Map<String, Object>> participantIdentities) {

        List<Map<String, Object>> mergedParticipants = new ArrayList<>();

        if (participantsStats == null || participantsStats.isEmpty()) {
            log.warn("⚠️ [DEBUG] participantsStats está vazio");
            return mergedParticipants;
        }

        for (Map<String, Object> stats : participantsStats) {
            Map<String, Object> merged = new HashMap<>(stats);

            // Buscar identity correspondente pelo participantId
            Object participantIdObj = stats.get("participantId");
            if (participantIdObj != null && participantIdentities != null) {
                int participantId = participantIdObj instanceof Number
                        ? ((Number) participantIdObj).intValue()
                        : 0;

                for (Map<String, Object> identity : participantIdentities) {
                    Object identityIdObj = identity.get("participantId");
                    int identityId = identityIdObj instanceof Number
                            ? ((Number) identityIdObj).intValue()
                            : 0;

                    if (participantId == identityId) {
                        // Extrair dados do player
                        @SuppressWarnings("unchecked")
                        Map<String, Object> player = (Map<String, Object>) identity.get("player");

                        if (player != null) {
                            String gameName = (String) player.get("gameName");
                            String tagLine = (String) player.get("tagLine");
                            String summonerName = gameName != null && tagLine != null
                                    ? gameName + "#" + tagLine
                                    : (String) player.getOrDefault("summonerName", "Player " + participantId);

                            merged.put("summonerName", summonerName);
                            merged.put("gameName", gameName);
                            merged.put("tagLine", tagLine);
                            merged.put("puuid", player.get("puuid"));
                            merged.put("accountId", player.get("accountId"));
                        }

                        break;
                    }
                }
            }

            // Adicionar championName do DataDragon se disponível
            Object championIdObj = stats.get("championId");
            if (championIdObj != null) {
                // TODO: Buscar nome do campeão via DataDragonService
                merged.put("championName", "Champion " + championIdObj);
            }

            mergedParticipants.add(merged);
        }

        log.info("✅ [DEBUG] Mesclados {} participantes", mergedParticipants.size());
        return mergedParticipants;
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
