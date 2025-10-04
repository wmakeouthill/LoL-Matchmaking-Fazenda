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
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameInProgressService {

    private final CustomMatchRepository customMatchRepository;
    @SuppressWarnings("unused")
    private final MatchmakingWebSocketService webSocketService;
    @SuppressWarnings("unused")
    private final DiscordService discordService;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final br.com.lolmatchmaking.backend.websocket.SessionRegistry sessionRegistry;

    // scheduler for monitoring
    private ScheduledExecutorService scheduler;

    // Configurações
    private static final long MONITORING_INTERVAL_MS = 5000; // 5 segundos
    private static final long GAME_TIMEOUT_MS = 3600000; // 1 hora

    // Cache de jogos ativos
    private final Map<Long, GameData> activeGames = new ConcurrentHashMap<>();

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

            GameData gameData = new GameData(
                    matchId,
                    "in_progress",
                    LocalDateTime.now(),
                    team1,
                    team2,
                    draftResults);

            activeGames.put(matchId, gameData);

            // Atualizar status da partida
            match.setStatus("in_progress");
            match.setUpdatedAt(Instant.now());
            customMatchRepository.save(match);

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

            GameData gameData = activeGames.get(matchId);
            if (gameData == null) {
                log.warn("⚠️ Jogo não encontrado para finalização: {}", matchId);
                return;
            }

            int duration = (int) java.time.Duration.between(gameData.getStartedAt(), LocalDateTime.now()).getSeconds();

            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match != null) {
                match.setStatus("completed");
                match.setWinnerTeam(winnerTeam);
                match.setActualWinner(winnerTeam);
                match.setActualDuration(duration);
                match.setCompletedAt(Instant.now());
                match.setUpdatedAt(Instant.now());
                customMatchRepository.save(match);
            }

            activeGames.remove(matchId);
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

            activeGames.remove(matchId);

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
     * Verifica jogos expirados
     */
    private void checkExpiredGames() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(GAME_TIMEOUT_MS / 1000);

            // iterate and collect expired keys to avoid concurrent modification in complex
            // logic
            List<Long> expired = new ArrayList<>();
            for (Map.Entry<Long, GameData> entry : activeGames.entrySet()) {
                GameData gameData = entry.getValue();
                if (gameData.getStartedAt().isBefore(cutoff)) {
                    log.warn("⏰ Jogo expirado: {}", entry.getKey());
                    expired.add(entry.getKey());
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
     * Obtém número de jogos ativos
     */
    @SuppressWarnings("unused")
    public int getActiveGamesCount() {
        return activeGames.size();
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

            // ✅ CRÍTICO: Enviar para TODOS os jogadores via WebSocket usando
            // SessionRegistry
            int sentCount = 0;
            for (org.springframework.web.socket.WebSocketSession ws : sessionRegistry.all()) {
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
            log.info("║  📡 [GameInProgress] BROADCAST game_started                   ║");
            log.info("╚════════════════════════════════════════════════════════════════╝");
            log.info("✅ Evento enviado para {} sessões WebSocket", sentCount);

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
}
