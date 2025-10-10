package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.QueuePlayer;
import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.repository.QueuePlayerRepository;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import br.com.lolmatchmaking.backend.dto.QueuePlayerInfoDTO;
import br.com.lolmatchmaking.backend.dto.MatchInfoDTO;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PostConstruct;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * ⚠️ SERVIÇO LEGADO - EM USO MAS DEVE SER MIGRADO
 * 
 * Este serviço ainda é usado por vários controllers mas mantém queueCache em
 * paralelo.
 * 
 * STATUS ATUAL:
 * - Usado por: AdminController, DebugController, SyncController,
 * MatchmakingOrchestrator
 * - Tem Redis: RedisQueueService
 * - Problema: queueCache em paralelo (22 usos)
 * 
 * MIGRAÇÃO RECOMENDADA:
 * - Migrar para QueueManagementService + RedisQueueService
 * - Remover MatchmakingService completamente
 * 
 * @deprecated Use QueueManagementService ao invés
 */
@Deprecated(forRemoval = true)
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchmakingService {

    private final QueuePlayerRepository queuePlayerRepository;
    private final PlayerRepository playerRepository;
    private final CustomMatchRepository customMatchRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final MatchFoundService matchFoundService;
    private final DraftService draftService;
    private final GameInProgressService gameInProgressService;

    // ✅ NOVO: Redis para cache distribuído
    private final RedisQueueService redisQueue;

    // Cache local da fila para performance (DEPRECIADO - usar Redis)
    /**
     * @deprecated Substituído por RedisQueueService (chave: queue:matchmaking)
     *             MOTIVO: ConcurrentHashMap perde dados em reinícios. Redis oferece
     *             persistência,
     *             performance superior (ZADD/ZRANGE O(log N)) e auto-limpeza com
     *             TTL.
     *             TODO: Remover após validar que todas as operações usam
     *             RedisQueueService.
     */
    @Deprecated(forRemoval = true)
    private final Map<Long, QueuePlayer> queueCache = new ConcurrentHashMap<>();
    private final AtomicLong nextMatchId = new AtomicLong(1);

    // Configurações
    private static final int MIN_PLAYERS_FOR_MATCH = 10;
    private static final int MAX_QUEUE_SIZE = 20;
    private static final long MATCHMAKING_INTERVAL_MS = 5000; // 5 segundos
    private static final long CACHE_SYNC_INTERVAL_MS = 5000; // 5 segundos

    // Atividades recentes da fila
    private final List<QueueActivity> recentActivities = new ArrayList<>();
    private static final int MAX_ACTIVITIES = 20;

    @Data
    @AllArgsConstructor
    public static class QueueActivity {
        private String id;
        private Instant timestamp;
        private String type; // player_joined, player_left, match_created, system_update, queue_cleared
        private String message;
        private String playerName;
        private String playerTag;
        private String lane;
    }

    @Data
    @AllArgsConstructor
    public static class QueueStatus {
        private int playersInQueue;
        private int averageWaitTime;
        private int estimatedMatchTime;
        private boolean isActive;
        private List<QueuePlayerInfoDTO> playersInQueueList;
        private List<QueueActivity> recentActivities;
        private boolean isCurrentPlayerInQueue;
    }

    @Data
    @AllArgsConstructor
    public static class QueuedPlayerInfo {
        private String summonerName;
        private String tagLine;
        private String primaryLane;
        private String secondaryLane;
        private String primaryLaneDisplay;
        private String secondaryLaneDisplay;
        private int mmr;
        private int queuePosition;
        private LocalDateTime joinTime;
        private boolean isCurrentPlayer;
    }

    /**
     * Inicializa o serviço de matchmaking
     */
    @PostConstruct
    public void initialize() {
        log.info("🚀 Inicializando MatchmakingService...");

        // Carregar fila do banco de dados
        loadQueueFromDatabase();

        // Adicionar atividades iniciais
        addActivity("system_update", "Sistema de matchmaking inicializado");
        addActivity("system_update", "Aguardando jogadores para a fila");

        log.info("✅ MatchmakingService inicializado com sucesso");
    }

    /**
     * Carrega jogadores da fila do banco de dados
     */
    private void loadQueueFromDatabase() {
        try {
            List<QueuePlayer> activePlayers = queuePlayerRepository.findByActiveTrue();

            for (QueuePlayer player : activePlayers) {
                queueCache.put(player.getId(), player);
            }

            log.info("📊 Carregados {} jogadores da fila persistente", queueCache.size());
        } catch (Exception e) {
            log.error("❌ Erro ao carregar fila do banco", e);
        }
    }

    /**
     * Adiciona jogador à fila
     */
    @Transactional
    public boolean addPlayerToQueue(Long playerId, String summonerName, String region,
            Integer customLp, String primaryLane, String secondaryLane) {
        try {
            log.info("➕ Adicionando jogador à fila: {}", summonerName);

            // Verificar se já está na fila
            if (isPlayerInQueue(playerId, summonerName)) {
                log.warn("⚠️ Jogador {} já está na fila", summonerName);
                return false;
            }

            // Verificar limite da fila
            long currentSize = redisQueue.getQueueSize();
            if (currentSize >= MAX_QUEUE_SIZE) {
                log.warn("⚠️ Fila cheia ({} jogadores)", MAX_QUEUE_SIZE);
                return false;
            }

            // Criar entrada na fila
            QueuePlayer queuePlayer = new QueuePlayer();
            queuePlayer.setPlayerId(playerId);
            queuePlayer.setSummonerName(summonerName);
            queuePlayer.setRegion(region);
            queuePlayer.setCustomLp(customLp != null ? customLp : 0);
            queuePlayer.setPrimaryLane(primaryLane);
            queuePlayer.setSecondaryLane(secondaryLane);
            queuePlayer.setJoinTime(Instant.now());
            queuePlayer.setQueuePosition((int) currentSize + 1);
            queuePlayer.setActive(true);
            queuePlayer.setAcceptanceStatus(0);

            // Salvar no banco
            queuePlayer = queuePlayerRepository.save(queuePlayer);

            // ✅ NOVO: Adicionar ao Redis
            redisQueue.add(queuePlayer);

            // Adicionar ao cache local (backward compatibility)
            queueCache.put(queuePlayer.getId(), queuePlayer);

            // Atualizar posições
            updateQueuePositions();

            // Adicionar atividade
            addActivity("player_joined",
                    String.format("%s entrou na fila", summonerName));

            // Broadcast atualização
            broadcastQueueUpdate();

            log.info("✅ {} entrou na fila (posição: {})", summonerName, queuePlayer.getQueuePosition());
            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao adicionar jogador à fila", e);
            return false;
        }
    }

    /**
     * Remove jogador da fila
     */
    @Transactional
    public boolean removePlayerFromQueue(Long playerId, String summonerName) {
        try {
            log.info("🔍 Removendo jogador da fila: {} (ID: {})", summonerName, playerId);

            QueuePlayer player = null;

            // Buscar por ID primeiro no cache local
            if (playerId != null) {
                player = queueCache.get(playerId);
            }

            // Se não encontrou por ID, buscar por nome
            if (player == null && summonerName != null) {
                player = queueCache.values().stream()
                        .filter(p -> p.getSummonerName().equals(summonerName))
                        .findFirst()
                        .orElse(null);
            }

            if (player == null) {
                log.warn("⚠️ Jogador não encontrado na fila: {} (ID: {})", summonerName, playerId);
                return false;
            }

            // Remover do banco
            queuePlayerRepository.deleteById(player.getId());

            // ✅ NOVO: Remover do Redis
            redisQueue.remove(player.getId());

            // Remover do cache local
            queueCache.remove(player.getId());

            // Atualizar posições
            updateQueuePositions();

            // Adicionar atividade
            addActivity("player_left",
                    String.format("%s saiu da fila", player.getSummonerName()));

            // Broadcast atualização
            broadcastQueueUpdate();

            log.info("✅ {} removido da fila", player.getSummonerName());
            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao remover jogador da fila", e);
            return false;
        }
    }

    /**
     * Verifica se jogador está na fila
     */
    public boolean isPlayerInQueue(Long playerId, String summonerName) {
        if (playerId != null && queueCache.containsKey(playerId)) {
            return true;
        }

        if (summonerName != null) {
            return queueCache.values().stream()
                    .anyMatch(p -> p.getSummonerName().equals(summonerName));
        }

        return false;
    }

    /**
     * Obtém status da fila
     */
    public QueueStatus getQueueStatus(String currentPlayerName) {
        try {
            List<QueuePlayer> activePlayers = new ArrayList<>(queueCache.values());
            int playersCount = activePlayers.size();

            // Construir lista de jogadores
            List<QueuePlayerInfoDTO> playersList = activePlayers.stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());

            // Marcar jogador atual se especificado
            if (currentPlayerName != null) {
                playersList = playersList.stream()
                        .map(player -> {
                            boolean isCurrent = player.getSummonerName().equals(currentPlayerName) ||
                                    (player.getTagLine() != null &&
                                            (player.getSummonerName() + "#" + player.getTagLine())
                                                    .equals(currentPlayerName));
                            player.setIsCurrentPlayer(isCurrent);
                            return player;
                        })
                        .collect(Collectors.toList());
            }

            return new QueueStatus(
                    playersCount,
                    calculateEstimatedWaitTime(),
                    playersCount >= MIN_PLAYERS_FOR_MATCH ? 60 : 120,
                    true,
                    playersList,
                    new ArrayList<>(recentActivities),
                    currentPlayerName != null && isPlayerInQueue(null, currentPlayerName));

        } catch (Exception e) {
            log.error("❌ Erro ao obter status da fila", e);
            return new QueueStatus(0, 0, 120, true, new ArrayList<>(), new ArrayList<>(), false);
        }
    }

    /**
     * Tenta formar uma partida usando Redis com distributed lock
     */
    public Optional<List<QueuePlayer>> tryFormMatch() {
        // ✅ NOVO: Executar com distributed lock para evitar race conditions
        boolean executed = redisQueue.lockWithDistributedLock(() -> {
            // Buscar jogadores elegíveis do Redis
            List<QueuePlayer> activePlayers = redisQueue.getEligible(MIN_PLAYERS_FOR_MATCH);

            if (activePlayers.size() < MIN_PLAYERS_FOR_MATCH) {
                log.debug("Aguardando jogadores: {}/10", activePlayers.size());
                return;
            }

            log.info("🎯 {} jogadores encontrados!", activePlayers.size());

            // Aplicar algoritmo de balanceamento avançado
            List<QueuePlayer> selected = findBalancedMatch(activePlayers);

            if (selected == null || selected.size() < MIN_PLAYERS_FOR_MATCH) {
                return;
            }

            log.info("🎮 Formando partida balanceada com {} jogadores", selected.size());

            // Criar partida no banco
            createMatch(selected);
        });

        if (!executed) {
            log.debug("⏭️ Outra instância está processando matchmaking, pulando...");
        }

        return Optional.empty(); // Retorno mantido para compatibilidade
    }

    /**
     * Algoritmo de balanceamento avançado para encontrar a melhor combinação de
     * jogadores
     */
    private List<QueuePlayer> findBalancedMatch(List<QueuePlayer> availablePlayers) {
        if (availablePlayers.size() < MIN_PLAYERS_FOR_MATCH) {
            return null;
        }

        // Ordenar jogadores por tempo de espera (prioridade para quem espera mais)
        availablePlayers.sort((p1, p2) -> {
            long waitTime1 = System.currentTimeMillis() - p1.getJoinTime().toEpochMilli();
            long waitTime2 = System.currentTimeMillis() - p2.getJoinTime().toEpochMilli();
            return Long.compare(waitTime2, waitTime1); // Maior tempo de espera primeiro
        });

        // Tentar diferentes combinações para encontrar o melhor balanceamento
        List<QueuePlayer> bestMatch = null;
        double bestBalanceScore = Double.MAX_VALUE;
        int maxAttempts = Math.min(50, availablePlayers.size() - MIN_PLAYERS_FOR_MATCH + 1);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            List<QueuePlayer> candidates = selectCandidates(availablePlayers, attempt);
            if (candidates.size() < MIN_PLAYERS_FOR_MATCH) {
                continue;
            }

            // Tentar diferentes divisões de equipes
            List<List<List<QueuePlayer>>> teamDivisions = generateTeamDivisions(candidates);

            for (List<List<QueuePlayer>> division : teamDivisions) {
                List<QueuePlayer> team1 = division.get(0);
                List<QueuePlayer> team2 = division.get(1);

                double balanceScore = calculateBalanceScore(team1, team2);

                if (balanceScore < bestBalanceScore) {
                    bestBalanceScore = balanceScore;
                    bestMatch = new ArrayList<>(candidates);
                }
            }
        }

        // Se não encontrou uma combinação balanceada, usar os primeiros 10 jogadores
        if (bestMatch == null) {
            bestMatch = availablePlayers.subList(0, Math.min(MIN_PLAYERS_FOR_MATCH, availablePlayers.size()));
        }

        return bestMatch;
    }

    /**
     * Seleciona candidatos para a partida baseado no índice de tentativa
     */
    private List<QueuePlayer> selectCandidates(List<QueuePlayer> availablePlayers, int attempt) {
        if (attempt == 0) {
            // Primeira tentativa: pegar os primeiros 10 jogadores (maior tempo de espera)
            return availablePlayers.subList(0, Math.min(MIN_PLAYERS_FOR_MATCH, availablePlayers.size()));
        } else {
            // Tentativas subsequentes: incluir jogadores com MMR similar
            int startIndex = Math.min(attempt * 2, availablePlayers.size() - MIN_PLAYERS_FOR_MATCH);
            int endIndex = Math.min(startIndex + MIN_PLAYERS_FOR_MATCH, availablePlayers.size());
            return availablePlayers.subList(startIndex, endIndex);
        }
    }

    /**
     * Gera diferentes divisões de equipes para testar
     */
    private List<List<List<QueuePlayer>>> generateTeamDivisions(List<QueuePlayer> players) {
        List<List<List<QueuePlayer>>> divisions = new ArrayList<>();

        // Divisão simples: primeiros 5 vs últimos 5
        List<QueuePlayer> team1 = players.subList(0, 5);
        List<QueuePlayer> team2 = players.subList(5, 10);
        divisions.add(Arrays.asList(team1, team2));

        // Divisão alternada: intercalar jogadores
        List<QueuePlayer> altTeam1 = new ArrayList<>();
        List<QueuePlayer> altTeam2 = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            if (i % 2 == 0) {
                altTeam1.add(players.get(i));
            } else {
                altTeam2.add(players.get(i));
            }
        }
        if (altTeam1.size() == 5 && altTeam2.size() == 5) {
            divisions.add(Arrays.asList(altTeam1, altTeam2));
        }

        return divisions;
    }

    /**
     * Calcula o score de balanceamento entre duas equipes
     * Score menor = melhor balanceamento
     */
    private double calculateBalanceScore(List<QueuePlayer> team1, List<QueuePlayer> team2) {
        if (team1.size() != 5 || team2.size() != 5) {
            return Double.MAX_VALUE;
        }

        // Calcular MMR médio de cada equipe
        double avgMmr1 = team1.stream().mapToInt(QueuePlayer::getCustomLp).average().orElse(0);
        double avgMmr2 = team2.stream().mapToInt(QueuePlayer::getCustomLp).average().orElse(0);

        // Diferença de MMR entre equipes
        double mmrDifference = Math.abs(avgMmr1 - avgMmr2);

        // Calcular distribuição de lanes
        double laneBalance = calculateLaneBalance(team1, team2);

        // Calcular tempo de espera médio
        double waitTimeBalance = calculateWaitTimeBalance(team1, team2);

        // Score final (pesos podem ser ajustados)
        return mmrDifference * 0.5 + laneBalance * 0.3 + waitTimeBalance * 0.2;
    }

    /**
     * Calcula o balanceamento de lanes entre as equipes
     */
    private double calculateLaneBalance(List<QueuePlayer> team1, List<QueuePlayer> team2) {
        Map<String, Long> lanes1 = team1.stream()
                .collect(Collectors.groupingBy(QueuePlayer::getPrimaryLane, Collectors.counting()));
        Map<String, Long> lanes2 = team2.stream()
                .collect(Collectors.groupingBy(QueuePlayer::getPrimaryLane, Collectors.counting()));

        // Penalizar se uma equipe tem muitos jogadores da mesma lane
        double penalty1 = lanes1.values().stream().mapToDouble(count -> Math.max(0, count - 2)).sum();
        double penalty2 = lanes2.values().stream().mapToDouble(count -> Math.max(0, count - 2)).sum();

        return penalty1 + penalty2;
    }

    /**
     * Calcula o balanceamento de tempo de espera entre as equipes
     */
    private double calculateWaitTimeBalance(List<QueuePlayer> team1, List<QueuePlayer> team2) {
        double avgWait1 = team1.stream()
                .mapToLong(p -> System.currentTimeMillis() - p.getJoinTime().toEpochMilli())
                .average().orElse(0);
        double avgWait2 = team2.stream()
                .mapToLong(p -> System.currentTimeMillis() - p.getJoinTime().toEpochMilli())
                .average().orElse(0);

        return Math.abs(avgWait1 - avgWait2) / 1000.0; // em segundos
    }

    /**
     * Encontra a melhor divisão de equipes para os jogadores selecionados
     */
    private List<List<QueuePlayer>> findBestTeamDivision(List<QueuePlayer> players) {
        if (players.size() != 10) {
            // Fallback: divisão simples
            return Arrays.asList(
                    players.subList(0, 5),
                    players.subList(5, 10));
        }

        List<List<QueuePlayer>> bestDivision = null;
        double bestScore = Double.MAX_VALUE;

        // Testar diferentes divisões
        List<List<List<QueuePlayer>>> divisions = generateTeamDivisions(players);

        for (List<List<QueuePlayer>> division : divisions) {
            List<QueuePlayer> team1 = division.get(0);
            List<QueuePlayer> team2 = division.get(1);

            double score = calculateBalanceScore(team1, team2);

            if (score < bestScore) {
                bestScore = score;
                bestDivision = division;
            }
        }

        return bestDivision != null ? bestDivision
                : Arrays.asList(
                        players.subList(0, 5),
                        players.subList(5, 10));
    }

    /**
     * Cria uma nova partida
     */
    @Transactional
    private void createMatch(List<QueuePlayer> players) {
        try {
            // Usar algoritmo de balanceamento para dividir as equipes
            List<List<QueuePlayer>> balancedTeams = findBestTeamDivision(players);
            List<QueuePlayer> team1 = balancedTeams.get(0);
            List<QueuePlayer> team2 = balancedTeams.get(1);

            // Criar partida
            CustomMatch match = new CustomMatch();
            match.setTitle("Partida Customizada");
            match.setDescription("Partida criada pelo sistema de matchmaking");
            match.setTeam1PlayersJson(team1.stream()
                    .map(QueuePlayer::getSummonerName)
                    .collect(Collectors.joining(",")));
            match.setTeam2PlayersJson(team2.stream()
                    .map(QueuePlayer::getSummonerName)
                    .collect(Collectors.joining(",")));
            match.setStatus("pending");
            match.setCreatedBy("system");
            match.setCreatedAt(Instant.now());
            match.setGameMode("5v5");
            match.setOwnerBackendId("spring-backend");
            match.setOwnerHeartbeat(System.currentTimeMillis());

            match = customMatchRepository.save(match);

            // Remover jogadores da fila
            for (QueuePlayer player : players) {
                queueCache.remove(player.getId());
                queuePlayerRepository.deleteById(player.getId());
            }

            // Atualizar posições
            updateQueuePositions();

            // Adicionar atividade
            addActivity("match_created",
                    String.format("Partida criada com %d jogadores", players.size()));

            // Notificar via WebSocket
            webSocketService.broadcastMatchFound(convertToMatchDTO(match));

            log.info("✅ Partida criada: ID {}", match.getId());

        } catch (Exception e) {
            log.error("❌ Erro ao criar partida", e);
        }
    }

    /**
     * Atualiza posições na fila
     */
    private void updateQueuePositions() {
        List<QueuePlayer> players = new ArrayList<>(queueCache.values());
        players.sort(Comparator.comparing(QueuePlayer::getJoinTime));

        for (int i = 0; i < players.size(); i++) {
            QueuePlayer player = players.get(i);
            player.setQueuePosition(i + 1);
            queuePlayerRepository.save(player);
        }
    }

    /**
     * Calcula tempo estimado de espera
     */
    private int calculateEstimatedWaitTime() {
        int playersCount = queueCache.size();
        if (playersCount >= MIN_PLAYERS_FOR_MATCH) {
            return 0; // Pronto para formar partida
        }

        // Estimativa baseada no número de jogadores
        return Math.max(60, (MIN_PLAYERS_FOR_MATCH - playersCount) * 30);
    }

    /**
     * Adiciona atividade à lista
     */
    private void addActivity(String type, String message) {
        addActivity(type, message, null, null, null);
    }

    private void addActivity(String type, String message, String playerName, String playerTag, String lane) {
        QueueActivity activity = new QueueActivity(
                UUID.randomUUID().toString(),
                Instant.now(),
                type,
                message,
                playerName,
                playerTag,
                lane);

        recentActivities.add(0, activity); // Adicionar no início

        // Manter apenas as últimas atividades
        if (recentActivities.size() > MAX_ACTIVITIES) {
            recentActivities.remove(recentActivities.size() - 1);
        }
    }

    /**
     * Converte QueuePlayer para DTO
     */
    private QueuePlayerInfoDTO convertToDTO(QueuePlayer player) {
        String[] nameParts = player.getSummonerName().split("#");
        String summonerName = nameParts[0];
        String tagLine = nameParts.length > 1 ? nameParts[1] : null;

        return QueuePlayerInfoDTO.builder()
                .id(player.getId())
                .summonerName(summonerName)
                .tagLine(tagLine)
                .region(player.getRegion())
                .customLp(player.getCustomLp())
                .mmr(player.getCustomLp() != null ? player.getCustomLp() : 0)
                .primaryLane(player.getPrimaryLane())
                .secondaryLane(player.getSecondaryLane())
                .joinTime(player.getJoinTime())
                .queuePosition(player.getQueuePosition())
                .isActive(player.getActive())
                .acceptanceStatus(player.getAcceptanceStatus())
                .isCurrentPlayer(false)
                .build();
    }

    /**
     * Converte CustomMatch para DTO
     */
    private MatchInfoDTO convertToMatchDTO(CustomMatch match) {
        return new MatchInfoDTO(
                match.getId(),
                match.getTitle(),
                match.getDescription(),
                Arrays.asList(match.getTeam1PlayersJson().split(",")),
                Arrays.asList(match.getTeam2PlayersJson().split(",")),
                match.getWinnerTeam(),
                match.getStatus(),
                match.getCreatedBy(),
                match.getCreatedAt(),
                match.getCompletedAt(),
                match.getGameMode(),
                match.getDuration(),
                match.getLpChangesJson(),
                match.getAverageMmrTeam1(),
                match.getAverageMmrTeam2(),
                null, // participantsData
                match.getRiotGameId(),
                match.getDetectedByLcu(),
                match.getNotes(),
                match.getCustomLp(),
                match.getUpdatedAt(),
                match.getPickBanDataJson(),
                null, // linkedResults
                match.getActualWinner(),
                match.getActualDuration(),
                match.getRiotId(),
                match.getMmrChangesJson(),
                match.getMatchLeader(),
                match.getOwnerBackendId(),
                match.getOwnerHeartbeat());
    }

    /**
     * Inicia o processamento de matchmaking
     */
    @Async
    public void startMatchmakingInterval() {
        while (true) {
            try {
                Thread.sleep(MATCHMAKING_INTERVAL_MS);

                // Sincronizar cache com banco
                syncCacheWithDatabase();

                // Tentar formar partida
                tryFormMatch();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("❌ Erro no processamento de matchmaking", e);
            }
        }
    }

    /**
     * Sincroniza cache com banco de dados
     */
    public void syncCacheWithDatabase() {
        try {
            List<QueuePlayer> dbPlayers = queuePlayerRepository.findByActiveTrue();
            Set<Long> dbIds = dbPlayers.stream()
                    .map(QueuePlayer::getId)
                    .collect(Collectors.toSet());

            // Remover do cache jogadores que não estão mais no banco
            queueCache.entrySet().removeIf(entry -> !dbIds.contains(entry.getKey()));

            // Adicionar ao cache jogadores novos do banco
            for (QueuePlayer player : dbPlayers) {
                queueCache.putIfAbsent(player.getId(), player);
            }

        } catch (Exception e) {
            log.error("❌ Erro ao sincronizar cache com banco", e);
        }
    }

    /**
     * Faz broadcast da atualização da fila
     */
    private void broadcastQueueUpdate() {
        try {
            QueueStatus status = getQueueStatus(null);
            webSocketService.broadcastQueueUpdate(
                    status.getPlayersInQueueList().stream()
                            .map(this::convertToQueuePlayerDTO)
                            .collect(Collectors.toList()));
        } catch (Exception e) {
            log.error("❌ Erro ao fazer broadcast da fila", e);
        }
    }

    private QueuePlayerInfoDTO convertToQueuePlayerDTO(QueuePlayerInfoDTO dto) {
        return dto; // Já é o tipo correto
    }

    /**
     * Obtém o número de jogadores na fila
     */
    public int getQueueSize() {
        return queueCache.size();
    }

    /**
     * Limpa a fila (para debug/admin)
     */
    @Transactional
    public void clearQueue() {
        try {
            queuePlayerRepository.deleteAll();
            queueCache.clear();

            addActivity("queue_cleared", "Fila limpa pelo sistema");
            broadcastQueueUpdate();

            log.info("🧹 Fila limpa");
        } catch (Exception e) {
            log.error("❌ Erro ao limpar fila", e);
        }
    }

    /**
     * Adiciona jogador à fila
     */
    public boolean joinQueue(String summonerName, String primaryLane, String secondaryLane) {
        try {
            // Verificar se já está na fila
            if (queueCache.values().stream().anyMatch(p -> p.getSummonerName().equals(summonerName))) {
                log.warn("⚠️ Jogador {} já está na fila", summonerName);
                return false;
            }

            // Buscar dados do jogador
            Optional<Player> playerOpt = playerRepository.findBySummonerName(summonerName);
            if (playerOpt.isEmpty()) {
                log.warn("⚠️ Jogador {} não encontrado", summonerName);
                return false;
            }

            Player player = playerOpt.get();

            // Criar entrada na fila
            QueuePlayer queuePlayer = new QueuePlayer();
            queuePlayer.setPlayerId(player.getId());
            queuePlayer.setSummonerName(summonerName);
            queuePlayer.setRegion(player.getRegion());
            queuePlayer.setCustomLp(player.getCustomLp());
            queuePlayer.setPrimaryLane(primaryLane);
            queuePlayer.setSecondaryLane(secondaryLane);
            queuePlayer.setJoinTime(Instant.now());
            queuePlayer.setQueuePosition(queueCache.size() + 1);
            queuePlayer.setActive(true);
            queuePlayer.setAcceptanceStatus(0);

            // Salvar no banco
            QueuePlayer savedPlayer = queuePlayerRepository.save(queuePlayer);

            // Adicionar ao cache
            queueCache.put(savedPlayer.getId(), savedPlayer);

            log.info("✅ Jogador {} adicionado à fila (Posição: {})", summonerName, savedPlayer.getQueuePosition());
            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao adicionar jogador à fila", e);
            return false;
        }
    }

    /**
     * Remove jogador da fila
     */
    public boolean leaveQueue(String summonerName) {
        try {
            Optional<QueuePlayer> playerOpt = queueCache.values().stream()
                    .filter(p -> p.getSummonerName().equals(summonerName))
                    .findFirst();

            if (playerOpt.isEmpty()) {
                log.warn("⚠️ Jogador {} não está na fila", summonerName);
                return false;
            }

            QueuePlayer player = playerOpt.get();

            // Marcar como inativo no banco
            player.setActive(false);
            player.setAcceptanceStatus(0);
            queuePlayerRepository.save(player);

            // Remover do cache
            queueCache.remove(player.getId());

            log.info("✅ Jogador {} removido da fila", summonerName);
            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao remover jogador da fila", e);
            return false;
        }
    }

    /**
     * Obtém estatísticas do matchmaking
     */
    public Map<String, Object> getMatchmakingStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeMatches", 0); // Não implementado ainda
        stats.put("queueSize", queueCache.size());
        stats.put("lastMatchCreated", "N/A");
        return stats;
    }
}
