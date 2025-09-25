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
     * Inicia um jogo após draft completo
     */
    @Transactional
    public void startGame(Long matchId, Map<String, Object> draftResults) {
        try {
            log.info("🎮 Iniciando jogo para partida {}", matchId);

            CustomMatch match = customMatchRepository.findById(matchId)
                    .orElseThrow(() -> new RuntimeException("Partida não encontrada: " + matchId));

            // Criar dados do jogo
            List<GamePlayer> team1 = createGamePlayers(draftResults, "team1", 1);
            List<GamePlayer> team2 = createGamePlayers(draftResults, "team2", 2);

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

            log.info("✅ Jogo iniciado para partida {}", matchId);

        } catch (Exception e) {
            log.error("❌ Erro ao iniciar jogo", e);
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

            // schedule with fixed delay; the task will call cancelGame via proxy to ensure @Transactional takes effect
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

            // iterate and collect expired keys to avoid concurrent modification in complex logic
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
}
