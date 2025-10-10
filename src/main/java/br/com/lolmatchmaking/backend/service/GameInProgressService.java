package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameInProgressService {

    private final CustomMatchRepository customMatchRepository;
    @SuppressWarnings("unused")
    private final MatchmakingWebSocketService webSocketService;
    @SuppressWarnings("unused")
    private final DiscordService discordService;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final br.com.lolmatchmaking.backend.websocket.SessionRegistry sessionRegistry;
    private final LPCalculationService lpCalculationService;

    // ✅ NOVO: Redis para monitoramento distribuído de jogos
    private final RedisGameMonitoringService redisGameMonitoring;
    
    // ✅ NOVO: Redis para ownership (limpar quando jogo termina)
    private final br.com.lolmatchmaking.backend.service.redis.RedisPlayerMatchService redisPlayerMatch;

    // scheduler for monitoring
    private ScheduledExecutorService scheduler;

    // Configurações
    private static final long MONITORING_INTERVAL_MS = 5000; // 5 segundos
    private static final long GAME_TIMEOUT_MS = 3600000; // 1 hora

    // ✅ REMOVIDO: HashMap local removido - Redis é fonte única da verdade
    // Use redisGameMonitoring para todas as operações de jogos em progresso

    @Data
    @RequiredArgsConstructor
    public static class GameData {
        private final Long matchId;
        private final String status;
        private final LocalDateTime startedAt;
        private final List<GamePlayer> team1;
        private final List<GamePlayer> team2;
        private final Map<String, Object> draftResults;
    }

    @Data
    @AllArgsConstructor
    public static class GamePlayer {
        private String summonerName;
        private String assignedLane;
        private Integer championId;
        private String championName;
        private int teamIndex;
        private boolean isConnected;
    }

    /**
     * Inicializa o serviço
     */
    @PostConstruct
    public void initialize() {
        log.info("🎮 Inicializando GameInProgressService...");

        // Inicializar o scheduler de forma assíncrona para não bloquear a inicialização
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(2000); // Aguarda 2s para garantir que o contexto está totalmente carregado
                startGameMonitoring();
                log.info("✅ GameInProgressService - Monitoramento de jogos iniciado");
            } catch (Exception e) {
                log.error("❌ Erro ao iniciar monitoramento de jogos", e);
            }
        });

        log.info("✅ GameInProgressService inicializado com sucesso");
    }

    /**
     * ✅ NOVO: Sobrecarga - Inicia jogo buscando dados do pick_ban_data
     */
    @Transactional
    public void startGame(Long matchId) {
        try {
            log.info("╔════════════════════════════════════════════════════════════════╗");
            log.info("║  🎮 [GameInProgress] INICIANDO JOGO                           ║");
            log.info("╚════════════════════════════════════════════════════════════════╝");
            log.info("🎯 Match ID: {}", matchId);

            CustomMatch match = customMatchRepository.findById(matchId)
                    .orElseThrow(() -> new RuntimeException("Partida não encontrada: " + matchId));

            // ✅ CRÍTICO: Parsear pick_ban_data (fonte de verdade)
            if (match.getPickBanDataJson() == null || match.getPickBanDataJson().isEmpty()) {
                throw new RuntimeException("pick_ban_data não encontrado para match " + matchId);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> pickBanData = objectMapper.readValue(match.getPickBanDataJson(), Map.class);

            log.info("✅ [GameInProgress] pick_ban_data parseado com sucesso");

            // ✅ Delegar para método existente
            startGame(matchId, pickBanData);

        } catch (Exception e) {
            log.error("❌ [GameInProgress] Erro ao iniciar jogo", e);
            throw new RuntimeException("Erro ao iniciar jogo: " + e.getMessage(), e);
        }
    }

    /**
     * Inicia um jogo após draft completo
     */
    @Transactional
    public void startGame(Long matchId, Map<String, Object> draftResults) {
        try {
            log.info("🎮 [GameInProgress] Iniciando jogo com dados do draft...");

            CustomMatch match = customMatchRepository.findById(matchId)
                    .orElseThrow(() -> new RuntimeException("Partida não encontrada: " + matchId));

            // ✅ CRÍTICO: Extrair teams.blue/red da estrutura hierárquica
            @SuppressWarnings("unchecked")
            Map<String, Object> teams = (Map<String, Object>) draftResults.get("teams");

            List<GamePlayer> team1;
            List<GamePlayer> team2;

            if (teams != null && teams.containsKey("blue") && teams.containsKey("red")) {
                log.info("✅ [GameInProgress] Usando estrutura teams.blue/red");

                // ✅ CORREÇÃO: blue/red são objetos com {players, allBans, allPicks}
                @SuppressWarnings("unchecked")
                Map<String, Object> blueTeamObj = (Map<String, Object>) teams.get("blue");
                @SuppressWarnings("unchecked")
                Map<String, Object> redTeamObj = (Map<String, Object>) teams.get("red");

                // ✅ Extrair a lista de players de dentro do objeto
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> blueTeam = (List<Map<String, Object>>) blueTeamObj.get("players");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> redTeam = (List<Map<String, Object>>) redTeamObj.get("players");

                log.info("✅ [GameInProgress] Blue team: {} players, Red team: {} players",
                        blueTeam != null ? blueTeam.size() : 0,
                        redTeam != null ? redTeam.size() : 0);

                // ✅ Criar GamePlayers com championId/championName extraídos das actions
                team1 = createGamePlayersWithChampions(blueTeam, draftResults);
                team2 = createGamePlayersWithChampions(redTeam, draftResults);
            } else {
                // ✅ FALLBACK: Usar estrutura antiga team1/team2
                log.warn("⚠️ [GameInProgress] Estrutura teams.blue/red não encontrada, usando fallback");
                team1 = createGamePlayers(draftResults, "team1", 1);
                team2 = createGamePlayers(draftResults, "team2", 2);
            }

            // ✅ REDIS ONLY: Iniciar monitoramento no Redis (fonte única da verdade)
            redisGameMonitoring.startMonitoring(matchId);
            log.info("✅ [GameInProgress] Monitoramento iniciado no Redis para match {}", matchId);

            // Criar dados do jogo (apenas para broadcast, não mais armazenado localmente)
            GameData gameData = new GameData(
                    matchId,
                    "in_progress",
                    LocalDateTime.now(),
                    team1,
                    team2,
                    draftResults);

            // Atualizar status da partida no banco
            match.setStatus("in_progress");
            match.setUpdatedAt(Instant.now());
            customMatchRepository.save(match);

            // ✅ Atualizar estatísticas no Redis
            Map<String, Object> gameStats = new HashMap<>();
            gameStats.put("team1Size", team1.size());
            gameStats.put("team2Size", team2.size());
            gameStats.put("startedAt", System.currentTimeMillis());
            redisGameMonitoring.updateGameStats(matchId, gameStats);

            // ✅ NOVO: Broadcast game_started
            broadcastGameStarted(matchId, gameData);

            log.info("╔════════════════════════════════════════════════════════════════╗");
            log.info("║  ✅ [GameInProgress] JOGO INICIADO COM SUCESSO                ║");
            log.info("╚════════════════════════════════════════════════════════════════╝");

        } catch (Exception e) {
            log.error("❌ [GameInProgress] Erro ao iniciar jogo", e);
            throw new RuntimeException("Erro ao iniciar jogo: " + e.getMessage(), e);
        }
    }

    /**
     * Finaliza um jogo
     */
    @Transactional
    public void finishGame(Long matchId, Integer winnerTeam, String endReason) {
        try {
            log.info("🏁 Finalizando jogo para partida {} - motivo: {}", matchId, endReason);

            // ✅ REDIS ONLY: Buscar dados do Redis
            Map<String, Object> gameStats = redisGameMonitoring.getGameStats(matchId);
            if (gameStats == null || gameStats.isEmpty()) {
                log.warn("⚠️ Jogo não encontrado no Redis para finalização: {}", matchId);
                return;
            }

            // Calcular duração baseado no Redis
            Long startedAtMillis = (Long) gameStats.get("startedAt");
            int duration = startedAtMillis != null
                    ? (int) ((System.currentTimeMillis() - startedAtMillis) / 1000)
                    : 0;

            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match != null) {
                match.setStatus("completed");
                match.setWinnerTeam(winnerTeam);
                match.setActualWinner(winnerTeam);
                match.setActualDuration(duration);
                match.setCompletedAt(Instant.now());
                match.setUpdatedAt(Instant.now());

                // ✅ NOVO: Calcular LP changes para todos os jogadores
                if (winnerTeam != null && winnerTeam > 0) {
                    try {
                        log.info("🔄 Calculando LP changes para a partida {}", matchId);

                        // Extrair listas de jogadores dos times
                        List<String> team1Players = parsePlayerList(match.getTeam1PlayersJson());
                        List<String> team2Players = parsePlayerList(match.getTeam2PlayersJson());

                        // Calcular mudanças de LP
                        Map<String, Integer> lpChanges = lpCalculationService.calculateMatchLPChanges(
                                team1Players,
                                team2Players,
                                winnerTeam);

                        // Salvar LP changes na partida
                        if (!lpChanges.isEmpty()) {
                            match.setLpChangesJson(objectMapper.writeValueAsString(lpChanges));

                            log.info("✅ LP changes calculados: {} jogadores afetados",
                                    lpChanges.size());
                        }
                    } catch (Exception lpError) {
                        log.error("❌ Erro ao calcular LP changes: {}", lpError.getMessage(), lpError);
                        // Não falhar a finalização por erro no cálculo de LP
                    }
                } else {
                    log.warn("⚠️ Time vencedor não definido, LP não será calculado");
                }

                customMatchRepository.save(match);
            }

            // ✅ REDIS ONLY: Finalizar no Redis (fonte única da verdade)
            String winningTeam = winnerTeam != null ? "team" + winnerTeam : "draw";
            redisGameMonitoring.finishGame(matchId, winningTeam);

            // ✅ CRÍTICO: Limpar ownership de todos os jogadores
            log.info("🗑️ [OWNERSHIP] Limpando ownership de match {}", matchId);
            redisPlayerMatch.clearMatchPlayers(matchId);
            log.info("✅ [OWNERSHIP] Ownership limpo com sucesso");
            log.info("✅ [finishGame] Jogo finalizado no Redis para match {}", matchId);

            // ✅ NOVO: Mover espectadores e jogadores de volta, depois limpar canais Discord
            try {
                log.info("👥 [finishGame] Movendo espectadores de volta ao lobby - match {}", matchId);
                discordService.moveSpectatorsBackToLobby(matchId);

                // Aguardar 1 segundo antes de deletar canais
                Thread.sleep(1000);

                log.info("🧹 [finishGame] Limpando canais Discord do match {}", matchId);
                discordService.deleteMatchChannels(matchId, true); // true = mover jogadores de volta
            } catch (Exception e) {
                log.error("❌ [finishGame] Erro ao limpar canais Discord: {}", e.getMessage());
            }

            // ✅ FIX: Deletar registro da partida do banco de dados após finalização
            try {
                log.info("🗑️ [finishGame] Deletando registro do match {} do banco de dados", matchId);
                customMatchRepository.deleteById(matchId);
                log.info("✅ [finishGame] Match {} deletado com sucesso do banco", matchId);
            } catch (Exception e) {
                log.error("❌ [finishGame] Erro ao deletar match {} do banco: {}", matchId, e.getMessage(), e);
            }

            log.info("✅ Jogo finalizado para partida {}: Team {} venceu - motivo: {}", matchId, winnerTeam, endReason);

        } catch (Exception e) {
            log.error("❌ Erro ao finalizar jogo", e);
        }
    }

    /**
     * Cancela um jogo
     */
    @Transactional
    public void cancelGame(Long matchId, String reason) {
        try {
            log.info("❌ Cancelando jogo para partida {}: {}", matchId, reason);

            // ✅ REDIS ONLY: Cancelar no Redis
            redisGameMonitoring.cancelGame(matchId);
            log.info("✅ [cancelGame] Jogo cancelado no Redis para match {}", matchId);

            // ✅ NOVO: Limpar canais Discord e mover jogadores de volta
            try {
                log.info("🧹 [cancelGame] Limpando canais Discord do match {}", matchId);
                discordService.deleteMatchChannels(matchId, true); // true = mover jogadores de volta
            } catch (Exception e) {
                log.error("❌ [cancelGame] Erro ao limpar canais Discord: {}", e.getMessage());
            }

            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match != null) {
                match.setStatus("cancelled");
                match.setUpdatedAt(Instant.now());
                customMatchRepository.save(match);
            }

            log.info("✅ Jogo cancelado para partida {}", matchId);

        } catch (Exception e) {
            log.error("❌ Erro ao cancelar jogo", e);
        }
    }

    /**
     * Cria jogadores do jogo
     */
    private List<GamePlayer> createGamePlayers(Map<String, Object> draftData, String teamKey, int teamIndex) {
        List<GamePlayer> players = new ArrayList<>();

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> teamData = (List<Map<String, Object>>) draftData.get(teamKey);

            if (teamData != null) {
                for (Map<String, Object> playerData : teamData) {
                    GamePlayer player = new GamePlayer(
                            (String) playerData.get("summonerName"),
                            (String) playerData.getOrDefault("assignedLane", "fill"),
                            (Integer) playerData.get("championId"),
                            (String) playerData.get("championName"),
                            teamIndex,
                            true);
                    players.add(player);
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Erro ao criar jogadores do time {}", teamKey, e);
        }

        return players;
    }

    /**
     * Inicia monitoramento de jogos
     */
    public void startGameMonitoring() {
        // initialize scheduler if needed and schedule the checkExpiredGames task
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "game-monitoring-thread");
                t.setDaemon(true);
                return t;
            });

            // schedule with fixed delay; the task will call cancelGame via proxy to ensure
            // @Transactional takes effect
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    checkExpiredGames();
                } catch (Exception e) {
                    log.error("❌ Erro no monitoramento de jogos agendado", e);
                }
            }, MONITORING_INTERVAL_MS, MONITORING_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    public void shutdownScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    /**
     * ✅ REDIS ONLY: Verifica jogos expirados
     */
    private void checkExpiredGames() {
        try {
            long cutoffMillis = System.currentTimeMillis() - GAME_TIMEOUT_MS;

            // ✅ REDIS ONLY: Buscar jogos ativos do Redis
            List<Long> activeGameIds = redisGameMonitoring.getActiveGames();

            List<Long> expired = new ArrayList<>();
            for (Long matchId : activeGameIds) {
                Map<String, Object> gameStats = redisGameMonitoring.getGameStats(matchId);
                if (gameStats != null) {
                    Long startedAt = (Long) gameStats.get("startedAt");
                    if (startedAt != null && startedAt < cutoffMillis) {
                        log.warn("⏰ Jogo expirado (Redis): {}", matchId);
                        expired.add(matchId);
                    }
                }
            }

            for (Long matchId : expired) {
                // call cancelGame via proxy to ensure transactional behavior
                cancelExpiredMatch(matchId);
            }

        } catch (Exception e) {
            log.error("❌ Erro ao verificar jogos expirados", e);
        }
    }

    private void cancelExpiredMatch(Long matchId) {
        try {
            GameInProgressService proxy = applicationContext.getBean(GameInProgressService.class);
            proxy.cancelGame(matchId, "Jogo expirado por timeout");
        } catch (Exception e) {
            log.error("❌ Erro ao cancelar jogo expirado: {}", matchId, e);
        }
    }

    /**
     * ✅ REDIS ONLY: Busca contagem de jogos ativos do Redis (fonte única da
     * verdade)
     */
    @SuppressWarnings("unused")
    public int getActiveGamesCount() {
        // ✅ REDIS ONLY: Buscar do Redis (fonte única da verdade)
        List<Long> activeGameIds = redisGameMonitoring.getActiveGames();
        int redisCount = activeGameIds.size();

        log.debug("📊 [GameInProgress] Jogos ativos (Redis): {}", redisCount);

        return redisCount;
    }

    /**
     * ✅ NOVO: Cria GamePlayers com championId/championName extraídos das actions
     */
    private List<GamePlayer> createGamePlayersWithChampions(
            List<Map<String, Object>> teamData,
            Map<String, Object> pickBanData) {

        List<GamePlayer> players = new ArrayList<>();

        try {
            // ✅ Extrair lista de actions
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> actions = (List<Map<String, Object>>) pickBanData.get("actions");

            if (actions == null) {
                log.warn("⚠️ [GameInProgress] Nenhuma action encontrada em pick_ban_data");
                actions = new ArrayList<>();
            }

            for (Map<String, Object> playerData : teamData) {
                String summonerName = (String) playerData.get("summonerName");
                String assignedLane = (String) playerData.getOrDefault("assignedLane", "fill");
                Integer teamIndex = (Integer) playerData.get("teamIndex");

                // ✅ CRÍTICO: Buscar pick do jogador nas actions
                Integer championId = null;
                String championName = null;

                // 1. Tentar buscar nas actions do próprio jogador (estrutura teams.blue/red)
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> playerActions = (List<Map<String, Object>>) playerData.get("actions");

                if (playerActions != null && !playerActions.isEmpty()) {
                    for (Map<String, Object> action : playerActions) {
                        String actionType = (String) action.get("type");
                        if ("pick".equals(actionType)) {
                            Object champIdObj = action.get("championId");
                            if (champIdObj != null) {
                                championId = champIdObj instanceof String
                                        ? Integer.parseInt((String) champIdObj)
                                        : (Integer) champIdObj;
                            }
                            championName = (String) action.get("championName");
                            break;
                        }
                    }
                }

                // 2. Fallback: Buscar na lista global de actions
                if (championId == null && actions != null) {
                    for (Map<String, Object> action : actions) {
                        String actionType = (String) action.get("type");
                        String byPlayer = (String) action.get("byPlayer");

                        if ("pick".equals(actionType) && summonerName.equals(byPlayer)) {
                            Object champIdObj = action.get("championId");
                            if (champIdObj != null) {
                                championId = champIdObj instanceof String
                                        ? Integer.parseInt((String) champIdObj)
                                        : (Integer) champIdObj;
                            }
                            championName = (String) action.get("championName");
                            break;
                        }
                    }
                }

                // ✅ Criar GamePlayer
                GamePlayer player = new GamePlayer(
                        summonerName,
                        assignedLane,
                        championId,
                        championName,
                        teamIndex != null ? teamIndex : 0,
                        true // isConnected
                );

                players.add(player);

                log.debug("✅ [GameInProgress] Jogador criado: {} - Lane: {} - Champion: {} (ID: {})",
                        summonerName, assignedLane, championName, championId);
            }

            log.info("✅ [GameInProgress] {} jogadores criados com campeões", players.size());

        } catch (Exception e) {
            log.error("❌ [GameInProgress] Erro ao criar jogadores com campeões", e);
        }

        return players;
    }

    /**
     * ✅ NOVO: Broadcast evento game_started para todos os jogadores
     */
    private void broadcastGameStarted(Long matchId, GameData gameData) {
        try {
            // ✅ Converter GameData para formato esperado pelo frontend
            Map<String, Object> gameDataMap = new HashMap<>();
            gameDataMap.put("matchId", matchId);
            gameDataMap.put("sessionId", "session_" + matchId);
            gameDataMap.put("status", "in_progress");
            gameDataMap.put("startTime", gameData.getStartedAt().toString());
            gameDataMap.put("isCustomGame", true);
            gameDataMap.put("originalMatchId", matchId); // ✅ CRÍTICO: Necessário para cancelamento

            // ✅ Converter GamePlayers para Maps
            List<Map<String, Object>> team1Maps = gameData.getTeam1().stream()
                    .map(this::gamePlayerToMap)
                    .toList();
            List<Map<String, Object>> team2Maps = gameData.getTeam2().stream()
                    .map(this::gamePlayerToMap)
                    .toList();

            gameDataMap.put("team1", team1Maps);
            gameDataMap.put("team2", team2Maps);
            gameDataMap.put("pickBanData", gameData.getDraftResults());

            Map<String, Object> payload = Map.of(
                    "type", "game_started",
                    "matchId", matchId,
                    "gameData", gameDataMap);

            String json = objectMapper.writeValueAsString(payload);

            // ✅ CRÍTICO: Enviar APENAS para os 10 jogadores da partida
            List<String> allPlayerNames = new ArrayList<>();
            allPlayerNames.addAll(gameData.getTeam1().stream().map(GamePlayer::getSummonerName).toList());
            allPlayerNames.addAll(gameData.getTeam2().stream().map(GamePlayer::getSummonerName).toList());

            log.info("🎯 [ENVIO INDIVIDUALIZADO] Enviando game_started APENAS para {} jogadores específicos:", allPlayerNames.size());
            for (String playerName : allPlayerNames) {
                log.info("  ✅ {}", playerName);
            }

            Collection<org.springframework.web.socket.WebSocketSession> playerSessions = sessionRegistry.getByPlayers(allPlayerNames);
            
            int sentCount = 0;
            for (org.springframework.web.socket.WebSocketSession ws : playerSessions) {
                try {
                    if (ws.isOpen()) {
                        ws.sendMessage(new org.springframework.web.socket.TextMessage(json));
                        sentCount++;
                    }
                } catch (Exception e) {
                    log.warn("⚠️ [GameInProgress] Erro ao enviar game_started para sessão", e);
                }
            }

            log.info("╔════════════════════════════════════════════════════════════════╗");
            log.info("║  📡 [GameInProgress] game_started ENVIADO APENAS PARA 10 JOGADORES║");
            log.info("╚════════════════════════════════════════════════════════════════╝");
            log.info("✅ Evento enviado para {} jogadores (de {} esperados)", sentCount, allPlayerNames.size());

        } catch (Exception e) {
            log.error("❌ [GameInProgress] Erro ao broadcast game_started", e);
        }
    }

    /**
     * ✅ NOVO: Converte GamePlayer para Map
     */
    private Map<String, Object> gamePlayerToMap(GamePlayer player) {
        Map<String, Object> map = new HashMap<>();
        map.put("summonerName", player.getSummonerName());
        map.put("assignedLane", player.getAssignedLane());
        map.put("championId", player.getChampionId());
        map.put("championName", player.getChampionName());
        map.put("teamIndex", player.getTeamIndex());
        map.put("isConnected", player.isConnected());
        return map;
    }

    /**
     * Parseia a string JSON de jogadores e retorna uma lista de nomes
     * 
     * @param playersJson String contendo nomes separados por vírgula ou JSON array
     * @return Lista de nomes dos jogadores
     */
    private List<String> parsePlayerList(String playersJson) {
        try {
            if (playersJson == null || playersJson.trim().isEmpty()) {
                return Collections.emptyList();
            }

            String trimmed = playersJson.trim();

            // Tentar parsear como JSON array primeiro
            if (trimmed.startsWith("[")) {
                com.fasterxml.jackson.databind.JsonNode playersNode = objectMapper.readTree(trimmed);
                List<String> playerNames = new ArrayList<>();

                if (playersNode.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode playerNode : playersNode) {
                        if (playerNode.isTextual()) {
                            playerNames.add(playerNode.asText());
                        }
                    }
                }

                return playerNames;
            }

            // Se não for JSON, tratar como comma-separated string
            return Arrays.stream(trimmed.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("❌ Erro ao parsear lista de jogadores: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
