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

    // ✅ NOVO: Lock service para prevenir múltiplas finalizações/cancelamentos
    private final br.com.lolmatchmaking.backend.service.lock.GameEndLockService gameEndLockService;

    // ✅ NOVO: PlayerStateService para cleanup inteligente
    private final br.com.lolmatchmaking.backend.service.lock.PlayerStateService playerStateService;

    // ✅ NOVO: PlayerLockService para limpeza de locks
    private final br.com.lolmatchmaking.backend.service.lock.PlayerLockService playerLockService;

    // ✅ NOVO: RedisTemplate para throttling de retries
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

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
     * ✅ CORREÇÃO: Adicionado lock distribuído para evitar race condition
     */
    @Transactional
    public void startGame(Long matchId) {
        String lockKey = "game_start_lock:" + matchId;

        try {
            log.info("╔════════════════════════════════════════════════════════════════╗");
            log.info("║  🎮 [GameInProgress] INICIANDO JOGO                           ║");
            log.info("╚════════════════════════════════════════════════════════════════╝");
            log.info("🎯 Match ID: {}", matchId);

            // ✅ CORREÇÃO: Verificar se já está in_progress para evitar race condition
            CustomMatch match = customMatchRepository.findById(matchId)
                    .orElseThrow(() -> new RuntimeException("Partida não encontrada: " + matchId));

            if ("in_progress".equals(match.getStatus())) {
                log.info("✅ [GameInProgress] Match {} já está in_progress - pulando inicialização", matchId);
                return;
            }

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
     * ✅ CORREÇÃO: Adicionado verificação de race condition
     */
    @Transactional
    public void startGame(Long matchId, Map<String, Object> draftResults) {
        try {
            log.info("🎮 [GameInProgress] Iniciando jogo com dados do draft...");

            CustomMatch match = customMatchRepository.findById(matchId)
                    .orElseThrow(() -> new RuntimeException("Partida não encontrada: " + matchId));

            // ✅ CORREÇÃO: Verificar se já está in_progress para evitar race condition
            if ("in_progress".equals(match.getStatus())) {
                log.info("✅ [GameInProgress] Match {} já está in_progress - pulando inicialização", matchId);
                return;
            }

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

            // ✅ CRÍTICO: Atualizar status E pickBanData no banco
            match.setStatus("in_progress");
            match.setUpdatedAt(Instant.now());

            // ✅ CORREÇÃO: NÃO sobrescrever pickBanDataJson - manter dados originais do
            // draft
            // O pickBanDataJson já contém todos os dados necessários do draft
            log.info("✅ [GameInProgress] pickBanData preservado no MySQL para match {} (dados originais mantidos)",
                    matchId);

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
        // 🔒 NOVO: ADQUIRIR LOCK DE FINALIZAÇÃO
        if (!gameEndLockService.acquireFinishLock(matchId)) {
            log.warn("⏭️ [GameEnd] Finalização de match {} já está sendo processada por outra instância", matchId);
            return; // Outra instância já está finalizando
        }

        try {
            log.info("🏁 Finalizando jogo para partida {} - motivo: {}", matchId, endReason);

            // ✅ NOVO: VALIDAR E CORRIGIR PlayerState de TODOS os players
            // CRÍTICO: Verificar no MySQL antes de finalizar
            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match == null) {
                log.warn("❌ [finishGame] Partida {} não encontrada no MySQL", matchId);
                return;
            }

            // Verificar se partida está realmente IN_PROGRESS
            if (!"in_progress".equalsIgnoreCase(match.getStatus())) {
                log.warn("❌ [finishGame] Partida {} não está in_progress (status: {})",
                        matchId, match.getStatus());
                return;
            }

            // ✅ CLEANUP INTELIGENTE: Corrigir PlayerState de todos os players
            List<String> allPlayers = new ArrayList<>();
            allPlayers.addAll(parsePlayerList(match.getTeam1PlayersJson()));
            allPlayers.addAll(parsePlayerList(match.getTeam2PlayersJson()));

            for (String playerName : allPlayers) {
                br.com.lolmatchmaking.backend.service.lock.PlayerState state = playerStateService
                        .getPlayerState(playerName);
                if (state != br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_GAME) {
                    log.warn("🧹 [finishGame] ESTADO INCONSISTENTE: {} está no game (MySQL) mas estado Redis é {}",
                            playerName, state);
                    // ✅ FORCE SET pois pode estar em qualquer estado
                    playerStateService.forceSetPlayerState(playerName,
                            br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_GAME);
                    log.info("✅ [finishGame] PlayerState de {} corrigido para IN_GAME", playerName);
                }
            }

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

            // ✅ Usar variável 'match' já definida acima (linha 237)
            // CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
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

            // ✅ NOVO: Limpar PlayerState de TODOS os jogadores PRIMEIRO
            List<String> allPlayersForCleanup = new ArrayList<>();
            allPlayersForCleanup.addAll(parsePlayerList(match.getTeam1PlayersJson()));
            allPlayersForCleanup.addAll(parsePlayerList(match.getTeam2PlayersJson()));

            for (String playerName : allPlayersForCleanup) {
                try {
                    playerStateService.setPlayerState(playerName,
                            br.com.lolmatchmaking.backend.service.lock.PlayerState.AVAILABLE);

                    // ✅ NOVO: Log específico para bots
                    if (isBotPlayer(playerName)) {
                        log.info("🤖 [finishGame] Estado de BOT {} limpo para AVAILABLE", playerName);
                    } else {
                        log.info("✅ [finishGame] Estado de {} limpo para AVAILABLE", playerName);
                    }
                } catch (Exception e) {
                    log.error("❌ [finishGame] Erro ao limpar estado de {}: {}", playerName, e.getMessage());
                }
            }

            // ✅ CRÍTICO: Limpar ownership de todos os jogadores
            log.info("🗑️ [OWNERSHIP] Limpando ownership de match {}", matchId);
            redisPlayerMatch.clearMatchPlayers(matchId);
            log.info("✅ [OWNERSHIP] Ownership limpo com sucesso");
            log.info("✅ [finishGame] Jogo finalizado no Redis para match {}", matchId);

            // ✅ NOVO: Mover espectadores e jogadores de volta, depois limpar canais Discord
            try {
                log.info("👥 [finishGame] Movendo espectadores de volta ao lobby - match {}", matchId);
                discordService.moveSpectatorsBackToLobby(matchId);

                // ✅ REDIS: 150ms (apenas garantir que moveSpectators terminou)
                Thread.sleep(150);

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
        } finally {
            // 🔓 SEMPRE LIBERAR LOCK
            gameEndLockService.releaseFinishLock(matchId);
            log.debug("🔓 [GameEnd] Lock de finalização liberado: matchId={}", matchId);
        }
    }

    /**
     * Cancela um jogo
     */
    @Transactional
    public void cancelGame(Long matchId, String reason) {
        // 🔒 NOVO: ADQUIRIR LOCK DE CANCELAMENTO
        if (!gameEndLockService.acquireCancelLock(matchId)) {
            log.warn("⏭️ [GameEnd] Cancelamento de match {} já está sendo processado por outra instância", matchId);
            return; // Outra instância já está cancelando
        }

        try {
            log.info("❌ Cancelando jogo para partida {}: {}", matchId, reason);

            // ✅ NOVO: Buscar jogadores ANTES de cancelar
            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            List<String> allPlayers = new ArrayList<>();
            if (match != null) {
                allPlayers.addAll(parsePlayerList(match.getTeam1PlayersJson()));
                allPlayers.addAll(parsePlayerList(match.getTeam2PlayersJson()));
                log.info("🎯 [cancelGame] {} jogadores para limpar estados", allPlayers.size());
            }

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

            // ✅ CORREÇÃO: DELETAR partida do banco (não apenas marcar como cancelled)
            if (match != null) {
                log.info("🗑️ [GameInProgress] DELETANDO partida {} do banco de dados (cancelada)", matchId);
                customMatchRepository.deleteById(matchId);
                log.info("✅ [GameInProgress] Partida {} EXCLUÍDA do banco de dados", matchId);
            }

            // ✅ NOVO: Limpar PlayerState de TODOS os jogadores
            for (String playerName : allPlayers) {
                try {
                    playerStateService.setPlayerState(playerName,
                            br.com.lolmatchmaking.backend.service.lock.PlayerState.AVAILABLE);
                    log.info("✅ [cancelGame] Estado de {} limpo para AVAILABLE", playerName);
                } catch (Exception e) {
                    log.error("❌ [cancelGame] Erro ao limpar estado de {}: {}", playerName, e.getMessage());
                }
            }

            // ✅ NOVO: Limpar locks de jogadores
            for (String playerName : allPlayers) {
                try {
                    if (playerLockService.getPlayerSession(playerName) != null) {
                        playerLockService.forceReleasePlayerLock(playerName);
                        log.info("🔓 [cancelGame] Lock de {} liberado", playerName);
                    }
                } catch (Exception e) {
                    log.error("❌ [cancelGame] Erro ao liberar lock de {}: {}", playerName, e.getMessage());
                }
            }

            // ✅ NOVO: Limpar RedisPlayerMatch ownership
            // Note: clearMatchPlayers valida MySQL antes de limpar
            redisPlayerMatch.clearMatchPlayers(matchId);
            log.info("✅ [cancelGame] Ownership limpo para match {}", matchId);

            // ✅ CRÍTICO: NOTIFICAR FRONTEND via WebSocket
            try {
                broadcastGameCancelled(matchId, allPlayers, reason);
                log.info("📢 [cancelGame] Evento game_cancelled enviado para {} jogadores", allPlayers.size());
            } catch (Exception e) {
                log.error("❌ [cancelGame] Erro ao enviar broadcast de cancelamento", e);
            }

            log.info("✅ Jogo cancelado para partida {}", matchId);

        } catch (Exception e) {
            log.error("❌ Erro ao cancelar jogo", e);
        } finally {
            // 🔓 SEMPRE LIBERAR LOCK
            gameEndLockService.releaseCancelLock(matchId);
            log.debug("🔓 [GameEnd] Lock de cancelamento liberado: matchId={}", matchId);
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
                    // ✅ NOVO: Retry game state para jogadores desconectados
                    retryGameStateForAllPlayers();
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
            // ✅ CRÍTICO: VALIDAR COM MYSQL ANTES DE CANCELAR
            // Previne loops infinitos de cancelamento de jogos fantasma
            Optional<CustomMatch> matchOpt = customMatchRepository.findById(matchId);

            if (matchOpt.isEmpty()) {
                log.warn("🧹 [CLEANUP] Jogo {} não existe no MySQL! Limpando Redis fantasma...", matchId);
                redisGameMonitoring.cancelGame(matchId);
                log.info("✅ [CLEANUP] Jogo fantasma {} removido do Redis", matchId);
                return; // NÃO chamar cancelGame completo
            }

            CustomMatch match = matchOpt.get();
            String status = match.getStatus();

            // Se já está cancelado/completado no MySQL, apenas limpar Redis
            if ("cancelled".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status)) {
                log.warn("🧹 [CLEANUP] Jogo {} já está {} no MySQL! Limpando Redis...", matchId, status);
                redisGameMonitoring.cancelGame(matchId);
                log.info("✅ [CLEANUP] Jogo {} removido do Redis (já {} no MySQL)", matchId, status);
                return; // NÃO reprocessar
            }

            // Se está realmente "in_progress" no MySQL, aí sim cancelar
            if ("in_progress".equalsIgnoreCase(status)) {
                log.info("✅ [Validação MySQL] Jogo {} confirmado como in_progress - prosseguindo cancelamento",
                        matchId);
                GameInProgressService proxy = applicationContext.getBean(GameInProgressService.class);
                proxy.cancelGame(matchId, "Jogo expirado por timeout");
            } else {
                log.warn("⚠️ [CLEANUP] Jogo {} tem status inesperado no MySQL: {} - limpando Redis", matchId, status);
                redisGameMonitoring.cancelGame(matchId);
            }

        } catch (Exception e) {
            log.error("❌ Erro ao processar jogo expirado: {}", matchId, e);
        }
    }

    /**
     * ✅ NOVO: Reenviar game state para TODOS os jogadores (retry para
     * desconectados)
     * Garante que ninguém fique sem ver a tela de game in progress
     */
    private void retryGameStateForAllPlayers() {
        try {
            // ✅ Buscar jogos ativos do MySQL
            List<CustomMatch> activeGames = customMatchRepository.findByStatus("in_progress");

            if (activeGames.isEmpty()) {
                return;
            }

            for (CustomMatch match : activeGames) {
                Long matchId = match.getId();

                // ✅ THROTTLE: Reenviar apenas a cada 5 segundos para não spammar
                String retryKey = "game_retry:" + matchId;
                Long lastRetrySec = redisTemplate.opsForValue()
                        .get(retryKey) != null ? (Long) redisTemplate.opsForValue().get(retryKey) : null;

                long nowSec = System.currentTimeMillis() / 1000;
                if (lastRetrySec != null && (nowSec - lastRetrySec) < 5) {
                    continue; // Reenviar apenas a cada 5s
                }

                // ✅ Marcar último retry
                redisTemplate.opsForValue().set(retryKey, nowSec, java.time.Duration.ofMinutes(10));

                // ✅ VALIDAÇÃO: Buscar todos os jogadores do MySQL (ownership)
                List<String> team1Players = parsePlayerNames(match.getTeam1PlayersJson());
                List<String> team2Players = parsePlayerNames(match.getTeam2PlayersJson());

                List<String> allPlayers = new ArrayList<>();
                allPlayers.addAll(team1Players);
                allPlayers.addAll(team2Players);

                if (allPlayers.isEmpty()) {
                    continue;
                }

                // ✅ CRÍTICO: NÃO parar retry apenas porque está "conectado"!
                // Estar conectado ≠ Estar vendo o game (evento pode ter sido perdido)
                // SEMPRE continuar enviando (com throttle 5s) até que o jogo termine
                log.debug("🔄 [GameInProgress] {} jogadores na partida - enviando retry (throttle 5s)",
                        allPlayers.size());

                // ✅ Validar ownership case-insensitive + verificar acknowledgment
                List<String> validPlayers = new ArrayList<>();
                for (String player : allPlayers) {
                    // ✅ OTIMIZAÇÃO: Verificar se jogador JÁ acknowledgou (já viu o game)
                    String ackKey = "game_ack:" + matchId + ":" + player.toLowerCase();
                    Boolean hasAcked = (Boolean) redisTemplate.opsForValue().get(ackKey);
                    if (Boolean.TRUE.equals(hasAcked)) {
                        log.debug("✅ [GameInProgress] Jogador {} já acknowledged - pulando retry", player);
                        continue; // Pula este jogador
                    }

                    boolean inTeam1 = match.getTeam1PlayersJson() != null &&
                            match.getTeam1PlayersJson().toLowerCase().contains(player.toLowerCase());
                    boolean inTeam2 = match.getTeam2PlayersJson() != null &&
                            match.getTeam2PlayersJson().toLowerCase().contains(player.toLowerCase());

                    if (inTeam1 || inTeam2) {
                        validPlayers.add(player);
                    }
                }

                if (!validPlayers.isEmpty()) {
                    log.debug("🔄 [GameInProgress] RETRY: Reenviando game_started para {} jogadores da partida {}",
                            validPlayers.size(), matchId);

                    // ✅ Montar payload do game (dados do pick_ban_data)
                    Map<String, Object> gameData = new HashMap<>();
                    gameData.put("matchId", matchId);
                    gameData.put("status", "in_progress");
                    gameData.put("startTime", match.getCreatedAt());

                    // ✅ Adicionar pick_ban_data completo
                    if (match.getPickBanDataJson() != null && !match.getPickBanDataJson().isEmpty()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> pickBanData = objectMapper.readValue(
                                    match.getPickBanDataJson(), Map.class);
                            gameData.put("pickBanData", pickBanData);
                        } catch (Exception e) {
                            log.warn("⚠️ [GameInProgress] Erro ao parsear pick_ban_data", e);
                        }
                    }

                    // ✅ CORREÇÃO: Enviar GLOBALMENTE para todos os Electrons (ping/pong)
                    // ✅ CORREÇÃO: Enviar para jogadores específicos da partida
                    List<String> allPlayersFromMatch = getAllPlayersFromMatch(matchId);
                    log.info(
                            "🔄 [GameInProgress] RETRY - Enviando game_started para {} jogadores (CustomSessionIds: {})",
                            allPlayersFromMatch.size(), allPlayersFromMatch);
                    webSocketService.sendToPlayers("game_started", gameData, allPlayersFromMatch);
                    log.info("✅ [GameInProgress] RETRY enviado para {} jogadores", allPlayersFromMatch.size());
                }
            }

        } catch (Exception e) {
            log.debug("❌ [GameInProgress] Erro ao retry game state", e);
        }
    }

    private List<String> parsePlayerNames(String csv) {
        if (csv == null || csv.isBlank())
            return new ArrayList<>();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
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

                log.info("✅ [GameInProgress] Jogador criado: {} - Lane: {} - Champion: {} (ID: {})",
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
            // ✅ CORREÇÃO: Enviar pick_ban_data COMPLETO em vez de JSON simplificado
            Map<String, Object> pickBanData = gameData.getDraftResults();

            // ✅ Adicionar metadados do jogo ao pick_ban_data existente
            Map<String, Object> gameDataMap = new HashMap<>(pickBanData);
            gameDataMap.put("matchId", matchId);
            gameDataMap.put("sessionId", "session_" + matchId);
            gameDataMap.put("status", "in_progress");
            gameDataMap.put("startTime", gameData.getStartedAt().toString());
            gameDataMap.put("isCustomGame", true);
            gameDataMap.put("originalMatchId", matchId); // ✅ CRÍTICO: Necessário para cancelamento
            gameDataMap.put("currentPhase", "completed"); // ✅ Draft já foi completado

            // ✅ CORREÇÃO: SEMPRE usar os dados processados com campeões (team1/team2 do
            // GameData)
            List<Map<String, Object>> team1Maps = gameData.getTeam1().stream()
                    .map(player -> gamePlayerToMap(player, gameDataMap))
                    .toList();
            List<Map<String, Object>> team2Maps = gameData.getTeam2().stream()
                    .map(player -> gamePlayerToMap(player, gameDataMap))
                    .toList();

            // ✅ Substituir team1/team2 com dados processados (incluem
            // championId/championName)
            gameDataMap.put("team1", team1Maps);
            gameDataMap.put("team2", team2Maps);

            // ✅ CORREÇÃO: Preservar dados de bans da estrutura teams.blue/red
            @SuppressWarnings("unchecked")
            Map<String, Object> teams = (Map<String, Object>) gameDataMap.get("teams");
            if (teams != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> blueTeam = (Map<String, Object>) teams.get("blue");
                @SuppressWarnings("unchecked")
                Map<String, Object> redTeam = (Map<String, Object>) teams.get("red");

                if (blueTeam != null && redTeam != null) {
                    // ✅ Preservar allBans de cada time
                    List<String> blueBans = (List<String>) blueTeam.get("allBans");
                    List<String> redBans = (List<String>) redTeam.get("allBans");

                    // ✅ Adicionar bans aos dados finais
                    gameDataMap.put("blueBans", blueBans != null ? blueBans : new ArrayList<>());
                    gameDataMap.put("redBans", redBans != null ? redBans : new ArrayList<>());

                    log.info("🔍 [GameInProgress] DEBUG - Bans preservados: Blue={}, Red={}",
                            gameDataMap.get("blueBans"), gameDataMap.get("redBans"));
                }
            }

            Map<String, Object> payload = Map.of(
                    "type", "game_started",
                    "matchId", matchId,
                    "gameData", gameDataMap);

            String json = objectMapper.writeValueAsString(payload);

            // ✅ CORREÇÃO: Enviar GLOBALMENTE para todos os Electrons (ping/pong)
            // ✅ CORREÇÃO: Enviar para jogadores específicos da partida
            List<String> allPlayers = getAllPlayersFromMatch(matchId);
            log.info("🎮 [GameInProgress] Enviando game_started para {} jogadores (CustomSessionIds: {})",
                    allPlayers.size(), allPlayers);
            webSocketService.sendToPlayers("game_started", payload, allPlayers);
            log.info("✅ [GameInProgress] game_started enviado com sucesso para {} jogadores", allPlayers.size());

            // ✅ DEBUG: Log dos dados sendo enviados
            log.info("🔍 [GameInProgress] DEBUG - Dados sendo enviados no game_started:");
            log.info("🔍 [GameInProgress] team1 size: {}",
                    gameDataMap.get("team1") != null ? ((List<?>) gameDataMap.get("team1")).size() : 0);
            log.info("🔍 [GameInProgress] team2 size: {}",
                    gameDataMap.get("team2") != null ? ((List<?>) gameDataMap.get("team2")).size() : 0);
            if (gameDataMap.get("team1") != null) {
                List<?> team1 = (List<?>) gameDataMap.get("team1");
                if (!team1.isEmpty()) {
                    Object firstPlayer = team1.get(0);
                    log.info("🔍 [GameInProgress] Primeiro jogador team1: {}", firstPlayer);
                }
            }
            if (gameDataMap.get("team2") != null) {
                List<?> team2 = (List<?>) gameDataMap.get("team2");
                if (!team2.isEmpty()) {
                    Object firstPlayer = team2.get(0);
                    log.info("🔍 [GameInProgress] Primeiro jogador team2: {}", firstPlayer);
                }
            }

            log.info("╔════════════════════════════════════════════════════════════════╗");
            log.info("║  📡 [GameInProgress] game_started ENVIADO GLOBALMENTE         ║");
            log.info("╚════════════════════════════════════════════════════════════════╝");
            log.info("✅ Evento enviado globalmente para todos os Electrons conectados");

        } catch (Exception e) {
            log.error("❌ [GameInProgress] Erro ao broadcast game_started", e);
        }
    }

    /**
     * ✅ NOVO: Broadcast evento game_cancelled para todos os jogadores
     */
    private void broadcastGameCancelled(Long matchId, List<String> allPlayers, String reason) {
        try {
            Map<String, Object> payload = Map.of(
                    "type", "match_cancelled",
                    "matchId", matchId,
                    "reason", reason != null ? reason : "Partida cancelada");

            String json = objectMapper.writeValueAsString(payload);

            log.info("📢 [GameInProgress] Enviando game_cancelled para {} jogadores:", allPlayers.size());
            for (String playerName : allPlayers) {
                log.info("  ✅ {}", playerName);
            }

            Collection<org.springframework.web.socket.WebSocketSession> playerSessions = sessionRegistry
                    .getByPlayers(allPlayers);

            int sentCount = 0;
            for (org.springframework.web.socket.WebSocketSession ws : playerSessions) {
                try {
                    if (ws.isOpen()) {
                        synchronized (ws) {
                            ws.sendMessage(new org.springframework.web.socket.TextMessage(json));
                        }
                        sentCount++;
                    }
                } catch (Exception e) {
                    log.warn("⚠️ [GameInProgress] Erro ao enviar game_cancelled para sessão", e);
                }
            }

            log.info("✅ [GameInProgress] game_cancelled enviado para {}/{} jogadores",
                    sentCount, allPlayers.size());

        } catch (Exception e) {
            log.error("❌ [GameInProgress] Erro ao broadcast game_cancelled", e);
        }
    }

    /**
     * ✅ NOVO: Converte GamePlayer para Map preservando dados originais
     */
    private Map<String, Object> gamePlayerToMap(GamePlayer player, Map<String, Object> gameDataMap) {
        Map<String, Object> map = new HashMap<>();
        map.put("summonerName", player.getSummonerName());
        map.put("assignedLane", player.getAssignedLane());
        map.put("championId", player.getChampionId());
        map.put("championName", player.getChampionName());
        map.put("teamIndex", player.getTeamIndex());
        map.put("isConnected", player.isConnected());

        // ✅ CORREÇÃO: Buscar dados originais para preservar laneBadge e outros campos
        try {
            // Buscar dados originais do pick_ban_data para preservar laneBadge
            @SuppressWarnings("unchecked")
            Map<String, Object> teams = (Map<String, Object>) gameDataMap.get("teams");
            if (teams != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> blueTeam = (Map<String, Object>) teams.get("blue");
                @SuppressWarnings("unchecked")
                Map<String, Object> redTeam = (Map<String, Object>) teams.get("red");

                List<Map<String, Object>> allPlayers = new ArrayList<>();
                if (blueTeam != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> bluePlayers = (List<Map<String, Object>>) blueTeam.get("players");
                    if (bluePlayers != null)
                        allPlayers.addAll(bluePlayers);
                }
                if (redTeam != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> redPlayers = (List<Map<String, Object>>) redTeam.get("players");
                    if (redPlayers != null)
                        allPlayers.addAll(redPlayers);
                }

                // Encontrar o jogador original pelo summonerName
                for (Map<String, Object> originalPlayer : allPlayers) {
                    String originalSummonerName = (String) originalPlayer.get("summonerName");
                    if (player.getSummonerName().equals(originalSummonerName)) {
                        // ✅ Preservar dados originais importantes
                        map.put("laneBadge", originalPlayer.get("laneBadge"));
                        map.put("mmr", originalPlayer.get("mmr"));
                        map.put("gameName", originalPlayer.get("gameName"));
                        map.put("tagLine", originalPlayer.get("tagLine"));
                        map.put("primaryLane", originalPlayer.get("primaryLane"));
                        map.put("secondaryLane", originalPlayer.get("secondaryLane"));
                        map.put("isAutofill", originalPlayer.get("isAutofill"));
                        map.put("playerId", originalPlayer.get("playerId"));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [GameInProgress] Erro ao preservar dados originais do jogador: {}", e.getMessage());
        }

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

    /**
     * ✅ NOVO: Verifica se um jogador é bot
     */
    private boolean isBotPlayer(String summonerName) {
        if (summonerName == null || summonerName.isEmpty()) {
            return false;
        }

        String normalizedName = summonerName.toLowerCase().trim();

        // ✅ Padrões de nomes de bots conhecidos
        return normalizedName.startsWith("bot") ||
                normalizedName.startsWith("ai_") ||
                normalizedName.endsWith("_bot") ||
                normalizedName.contains("bot_") ||
                normalizedName.equals("bot") ||
                normalizedName.matches(".*bot\\d+.*"); // bot1, bot2, etc.
    }

    /**
     * ✅ NOVO: Obtém todos os jogadores de uma partida
     * ✅ CORREÇÃO: Suporta tanto CSV quanto JSON
     */
    private List<String> getAllPlayersFromMatch(Long matchId) {
        List<String> allPlayers = new ArrayList<>();

        try {
            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match != null) {
                // Extrair jogadores dos times
                String team1Json = match.getTeam1PlayersJson();
                String team2Json = match.getTeam2PlayersJson();

                // Tentar parse como JSON primeiro
                try {
                    if (team1Json != null && !team1Json.isEmpty()) {
                        com.fasterxml.jackson.databind.JsonNode team1Node = objectMapper.readTree(team1Json);
                        if (team1Node.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode playerNode : team1Node) {
                                if (playerNode.has("summonerName")) {
                                    allPlayers.add(playerNode.get("summonerName").asText());
                                }
                            }
                        }
                    }

                    if (team2Json != null && !team2Json.isEmpty()) {
                        com.fasterxml.jackson.databind.JsonNode team2Node = objectMapper.readTree(team2Json);
                        if (team2Node.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode playerNode : team2Node) {
                                if (playerNode.has("summonerName")) {
                                    allPlayers.add(playerNode.get("summonerName").asText());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Se falhar como JSON, tentar como CSV
                    log.debug("⚠️ [GameInProgress] Não é JSON válido, tentando CSV: {}", e.getMessage());
                    if (team1Json != null && !team1Json.isBlank()) {
                        allPlayers.addAll(parsePlayerNames(team1Json));
                    }
                    if (team2Json != null && !team2Json.isBlank()) {
                        allPlayers.addAll(parsePlayerNames(team2Json));
                    }
                }
            }

            log.debug("🎯 [GameInProgress] Jogadores encontrados: {}", allPlayers);
        } catch (Exception e) {
            log.error("❌ [GameInProgress] Erro ao obter jogadores da partida", e);
        }

        return allPlayers;
    }
}
