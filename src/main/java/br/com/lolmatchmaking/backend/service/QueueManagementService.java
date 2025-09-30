package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.*;
import br.com.lolmatchmaking.backend.domain.repository.*;
import br.com.lolmatchmaking.backend.dto.QueueStatusDTO;
import br.com.lolmatchmaking.backend.dto.QueuePlayerInfoDTO;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueManagementService {

    private final QueuePlayerRepository queuePlayerRepository;
    private final PlayerRepository playerRepository;
    private final CustomMatchRepository customMatchRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final DiscordService discordService;
    private final LCUService lcuService;
    private final ObjectMapper objectMapper;

    // Cache local da fila para performance
    private final Map<String, QueuePlayer> queueCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSync = new ConcurrentHashMap<>();

    // Configurações
    private static final int MAX_QUEUE_SIZE = 20;
    private static final int MATCH_SIZE = 10;
    private static final long QUEUE_SYNC_INTERVAL = 10000; // 10 segundos
    private static final long MATCH_FOUND_TIMEOUT = 30000; // 30 segundos

    /**
     * Inicializa o serviço de fila
     */
    public void initialize() {
        log.info("🔄 Inicializando QueueManagementService...");
        loadQueueFromDatabase();
        startQueueMonitoring();
        log.info("✅ QueueManagementService inicializado");
    }

    /**
     * Carrega fila do banco de dados
     */
    @Transactional
    public void loadQueueFromDatabase() {
        try {
            List<QueuePlayer> activePlayers = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc();

            log.info("📥 [QueueManagementService] Carregando fila do banco...");
            log.info("📥 [QueueManagementService] Jogadores ativos encontrados: {}", activePlayers.size());
            
            queueCache.clear();
            for (QueuePlayer player : activePlayers) {
                queueCache.put(player.getSummonerName(), player);
                log.info("📥 [QueueManagementService] Jogador carregado: {} (ID: {})", 
                        player.getSummonerName(), player.getId());
            }

            log.info("📥 [QueueManagementService] Fila carregada do banco: {} jogadores", queueCache.size());
        } catch (Exception e) {
            log.error("❌ Erro ao carregar fila do banco", e);
        }
    }

    /**
     * Adiciona jogador à fila
     */
    @Transactional
    public boolean addToQueue(String summonerName, String region, Long playerId,
            Integer customLp, String primaryLane, String secondaryLane) {
        try {
            log.info("➕ Adicionando jogador à fila: {}", summonerName);

            // Verificar se já está na fila
            if (queueCache.containsKey(summonerName)) {
                log.warn("⚠️ Jogador {} já está na fila", summonerName);
                return false;
            }

            // Verificar limite da fila
            if (queueCache.size() >= MAX_QUEUE_SIZE) {
                log.warn("⚠️ Fila cheia ({} jogadores)", MAX_QUEUE_SIZE);
                return false;
            }

            // Remover entrada anterior se existir
            queuePlayerRepository.findBySummonerName(summonerName).ifPresent(qp -> {
                queuePlayerRepository.delete(qp);
                queueCache.remove(summonerName);
            });

            // Criar nova entrada na fila
            QueuePlayer queuePlayer = QueuePlayer.builder()
                    .playerId(playerId != null ? playerId : 0L)
                    .summonerName(summonerName)
                    .region(region)
                    .customLp(customLp != null ? customLp : 0)
                    .primaryLane(primaryLane)
                    .secondaryLane(secondaryLane)
                    .joinTime(Instant.now())
                    .queuePosition(calculateNextPosition())
                    .active(true)
                    .acceptanceStatus(0)
                    .build();

            // Salvar no banco
            queuePlayer = queuePlayerRepository.save(queuePlayer);

            // Adicionar ao cache
            queueCache.put(summonerName, queuePlayer);

            // Atualizar posições
            updateQueuePositions();

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
    public boolean removeFromQueue(String summonerName) {
        try {
            QueuePlayer queuePlayer = queueCache.get(summonerName);
            if (queuePlayer == null) {
                log.warn("⚠️ Jogador {} não encontrado na fila", summonerName);
                return false;
            }

            // Remover do banco
            queuePlayerRepository.delete(queuePlayer);

            // Remover do cache
            queueCache.remove(summonerName);

            // Atualizar posições
            updateQueuePositions();

            // Broadcast atualização
            broadcastQueueUpdate();

            log.info("✅ {} saiu da fila", summonerName);
            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao remover jogador da fila", e);
            return false;
        }
    }

    /**
     * Obtém status da fila
     */
    public QueueStatusDTO getQueueStatus(String currentPlayerDisplayName) {
        List<QueuePlayer> activePlayers = new ArrayList<>(queueCache.values());
        activePlayers.sort(Comparator.comparing(QueuePlayer::getJoinTime));

        log.info("📊 [QueueManagementService] getQueueStatus - {} jogadores no cache", activePlayers.size());
        log.info("📊 [QueueManagementService] Jogadores: {}", 
                activePlayers.stream().map(p -> p.getSummonerName()).collect(Collectors.toList()));

        List<QueuePlayerInfoDTO> playersInQueueList = activePlayers.stream()
                .map(this::convertToQueuePlayerInfoDTO)
                .collect(Collectors.toList());

        // Marcar jogador atual se fornecido
        if (currentPlayerDisplayName != null) {
            log.info("📊 [QueueManagementService] Procurando jogador atual: {}", currentPlayerDisplayName);
            playersInQueueList.forEach(player -> {
                if (player.getSummonerName().equals(currentPlayerDisplayName) ||
                        player.getSummonerName().contains(currentPlayerDisplayName)) {
                    player.setIsCurrentPlayer(true);
                    log.info("📊 [QueueManagementService] Jogador atual marcado: {}", player.getSummonerName());
                }
            });
        }

        QueueStatusDTO status = QueueStatusDTO.builder()
                .playersInQueue(activePlayers.size())
                .playersInQueueList(playersInQueueList)
                .averageWaitTime(calculateAverageWaitTime(activePlayers))
                .estimatedMatchTime(calculateEstimatedMatchTime(activePlayers.size()))
                .isActive(true)
                .build();

        log.info("📊 [QueueManagementService] Status retornado: {} jogadores", status.getPlayersInQueue());
        return status;
    }

    /**
     * Processa a fila para encontrar partidas
     */
    @Scheduled(fixedRate = 5000) // Executa a cada 5 segundos
    public void processQueue() {
        try {
            if (queueCache.size() < MATCH_SIZE) {
                return;
            }

            log.info("🎯 Processando fila para encontrar partida ({} jogadores)", queueCache.size());

            // Obter jogadores ativos
            List<QueuePlayer> activePlayers = new ArrayList<>(queueCache.values());
            activePlayers.sort(Comparator.comparing(QueuePlayer::getJoinTime));

            // Selecionar 10 jogadores mais antigos
            List<QueuePlayer> selectedPlayers = activePlayers.stream()
                    .limit(MATCH_SIZE)
                    .collect(Collectors.toList());

            // Balancear equipes por MMR e lanes
            List<QueuePlayer> balancedTeams = balanceTeamsByMMRAndLanes(selectedPlayers);

            if (balancedTeams.size() == MATCH_SIZE) {
                // Criar partida
                createMatch(balancedTeams);
            }

        } catch (Exception e) {
            log.error("❌ Erro ao processar fila", e);
        }
    }

    /**
     * Balanceia equipes por MMR e preferências de lane
     */
    private List<QueuePlayer> balanceTeamsByMMRAndLanes(List<QueuePlayer> players) {
        if (players.size() != MATCH_SIZE) {
            return new ArrayList<>();
        }

        // Ordenar por MMR (maior primeiro)
        players.sort((a, b) -> Integer.compare(
                b.getCustomLp() != null ? b.getCustomLp() : 0,
                a.getCustomLp() != null ? a.getCustomLp() : 0));

        // Mapeamento de lanes para posições no time
        Map<String, Map<String, Integer>> laneToTeamIndex = Map.of(
                "TOP", Map.of("team1", 0, "team2", 5),
                "JUNGLE", Map.of("team1", 1, "team2", 6),
                "MIDDLE", Map.of("team1", 2, "team2", 7),
                "ADC", Map.of("team1", 3, "team2", 8),
                "SUPPORT", Map.of("team1", 4, "team2", 9));

        // Controle de lanes ocupadas
        Map<String, Map<String, Boolean>> laneAssignments = new HashMap<>();
        for (String lane : Arrays.asList("TOP", "JUNGLE", "MIDDLE", "ADC", "SUPPORT")) {
            laneAssignments.put(lane, Map.of("team1", false, "team2", false));
        }

        List<QueuePlayer> balancedTeams = new ArrayList<>(Collections.nCopies(MATCH_SIZE, null));

        // Primeira passada: atribuir lanes primárias por prioridade de MMR
        for (int i = 0; i < players.size(); i++) {
            QueuePlayer player = players.get(i);
            String primaryLane = player.getPrimaryLane() != null ? player.getPrimaryLane() : "FILL";
            String secondaryLane = player.getSecondaryLane() != null ? player.getSecondaryLane() : "FILL";

            boolean assigned = false;

            // Tentar lane primária
            if (!primaryLane.equals("FILL") && laneAssignments.containsKey(primaryLane)) {
                if (!laneAssignments.get(primaryLane).get("team1")) {
                    int teamIndex = laneToTeamIndex.get(primaryLane).get("team1");
                    balancedTeams.set(teamIndex, player);
                    laneAssignments.get(primaryLane).put("team1", true);
                    assigned = true;
                } else if (!laneAssignments.get(primaryLane).get("team2")) {
                    int teamIndex = laneToTeamIndex.get(primaryLane).get("team2");
                    balancedTeams.set(teamIndex, player);
                    laneAssignments.get(primaryLane).put("team2", true);
                    assigned = true;
                }
            }

            // Tentar lane secundária se primária não disponível
            if (!assigned && !secondaryLane.equals("FILL") && laneAssignments.containsKey(secondaryLane)) {
                if (!laneAssignments.get(secondaryLane).get("team1")) {
                    int teamIndex = laneToTeamIndex.get(secondaryLane).get("team1");
                    balancedTeams.set(teamIndex, player);
                    laneAssignments.get(secondaryLane).put("team1", true);
                    assigned = true;
                } else if (!laneAssignments.get(secondaryLane).get("team2")) {
                    int teamIndex = laneToTeamIndex.get(secondaryLane).get("team2");
                    balancedTeams.set(teamIndex, player);
                    laneAssignments.get(secondaryLane).put("team2", true);
                    assigned = true;
                }
            }

            // Autofill se necessário
            if (!assigned) {
                for (String lane : Arrays.asList("TOP", "JUNGLE", "MIDDLE", "ADC", "SUPPORT")) {
                    if (!laneAssignments.get(lane).get("team1")) {
                        int teamIndex = laneToTeamIndex.get(lane).get("team1");
                        balancedTeams.set(teamIndex, player);
                        laneAssignments.get(lane).put("team1", true);
                        assigned = true;
                        break;
                    } else if (!laneAssignments.get(lane).get("team2")) {
                        int teamIndex = laneToTeamIndex.get(lane).get("team2");
                        balancedTeams.set(teamIndex, player);
                        laneAssignments.get(lane).put("team2", true);
                        assigned = true;
                        break;
                    }
                }
            }
        }

        return balancedTeams.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Cria uma nova partida
     */
    @Transactional
    public void createMatch(List<QueuePlayer> players) {
        try {
            if (players.size() != MATCH_SIZE) {
                log.warn("⚠️ Número incorreto de jogadores para criar partida: {}", players.size());
                return;
            }

            // Separar em dois times
            List<QueuePlayer> team1 = players.subList(0, 5);
            List<QueuePlayer> team2 = players.subList(5, 10);

            // Criar partida no banco
            CustomMatch match = CustomMatch.builder()
                    .title("Partida Customizada")
                    .description("Partida gerada automaticamente pelo sistema de matchmaking")
                    .team1PlayersJson(convertPlayersToJson(team1))
                    .team2PlayersJson(convertPlayersToJson(team2))
                    .status("match_found")
                    .createdBy("system")
                    .gameMode("5v5")
                    .averageMmrTeam1(calculateAverageMMR(team1))
                    .averageMmrTeam2(calculateAverageMMR(team2))
                    .createdAt(Instant.now())
                    .build();

            match = customMatchRepository.save(match);

            // Remover jogadores da fila
            for (QueuePlayer player : players) {
                queueCache.remove(player.getSummonerName());
                queuePlayerRepository.delete(player);
            }

            // Atualizar posições
            updateQueuePositions();

            // Notificar match found
            notifyMatchFound(match, team1, team2);

            log.info("✅ Partida criada: ID {}", match.getId());

        } catch (Exception e) {
            log.error("❌ Erro ao criar partida", e);
        }
    }

    /**
     * Notifica que uma partida foi encontrada
     */
    private void notifyMatchFound(CustomMatch match, List<QueuePlayer> team1, List<QueuePlayer> team2) {
        try {
            Map<String, Object> matchFoundData = new HashMap<>();
            matchFoundData.put("type", "match_found");
            matchFoundData.put("matchId", match.getId());
            matchFoundData.put("team1",
                    team1.stream().map(this::convertToQueuePlayerInfoDTO).collect(Collectors.toList()));
            matchFoundData.put("team2",
                    team2.stream().map(this::convertToQueuePlayerInfoDTO).collect(Collectors.toList()));
            matchFoundData.put("averageMmrTeam1", match.getAverageMmrTeam1());
            matchFoundData.put("averageMmrTeam2", match.getAverageMmrTeam2());

            // Broadcast via WebSocket
            webSocketService.broadcastToAll("match_found", matchFoundData);

            log.info("📢 Match found notificado para {} jogadores", team1.size() + team2.size());

        } catch (Exception e) {
            log.error("❌ Erro ao notificar match found", e);
        }
    }

    /**
     * Verifica se jogador pode entrar na fila (LCU + Discord + Canal)
     */
    public boolean canPlayerJoinQueue(String summonerName) {
        try {
            // Verificar se LCU está conectado
            if (!lcuService.isConnected()) {
                log.debug("⚠️ LCU não conectado para {}", summonerName);
                return false;
            }

            // Verificar se Discord bot está ativo
            // TODO: Implementar verificação do Discord bot
            // if (!discordService.isBotActive()) {
            // log.debug("⚠️ Discord bot não ativo para {}", summonerName);
            // return false;
            // }

            // Verificar se jogador está no canal monitorado
            // TODO: Implementar verificação do canal monitorado
            // if (!discordService.isPlayerInMonitoredChannel(summonerName)) {
            // log.debug("⚠️ Jogador {} não está no canal monitorado", summonerName);
            // return false;
            // }

            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao verificar se jogador pode entrar na fila", e);
            return false;
        }
    }

    // Métodos auxiliares

    private int calculateNextPosition() {
        return queueCache.size() + 1;
    }

    private void updateQueuePositions() {
        List<QueuePlayer> sortedPlayers = queueCache.values().stream()
                .sorted(Comparator.comparing(QueuePlayer::getJoinTime))
                .collect(Collectors.toList());

        for (int i = 0; i < sortedPlayers.size(); i++) {
            QueuePlayer player = sortedPlayers.get(i);
            player.setQueuePosition(i + 1);
            queuePlayerRepository.save(player);
        }
    }

    private long calculateAverageWaitTime(List<QueuePlayer> players) {
        if (players.isEmpty())
            return 0;

        return players.stream()
                .mapToLong(p -> ChronoUnit.SECONDS.between(p.getJoinTime(), Instant.now()))
                .sum() / players.size();
    }

    private long calculateEstimatedMatchTime(int queueSize) {
        if (queueSize >= MATCH_SIZE)
            return 0;
        return (MATCH_SIZE - queueSize) * 30; // 30 segundos por jogador faltante
    }

    private int calculateAverageMMR(List<QueuePlayer> players) {
        return (int) players.stream()
                .mapToInt(p -> p.getCustomLp() != null ? p.getCustomLp() : 0)
                .average()
                .orElse(0);
    }

    private String convertPlayersToJson(List<QueuePlayer> players) {
        try {
            List<String> playerNames = players.stream()
                    .map(QueuePlayer::getSummonerName)
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(playerNames);
        } catch (JsonProcessingException e) {
            log.error("❌ Erro ao converter jogadores para JSON", e);
            return "[]";
        }
    }

    private QueuePlayerInfoDTO convertToQueuePlayerInfoDTO(QueuePlayer player) {
        return QueuePlayerInfoDTO.builder()
                .id(player.getId())
                .summonerName(player.getSummonerName())
                .region(player.getRegion())
                .mmr(player.getCustomLp() != null ? player.getCustomLp() : 0)
                .primaryLane(player.getPrimaryLane())
                .secondaryLane(player.getSecondaryLane())
                .queuePosition(player.getQueuePosition())
                .joinTime(player.getJoinTime())
                .isCurrentPlayer(false)
                .build();
    }

    private void startQueueMonitoring() {
        // O monitoramento é feito via @Scheduled no método processQueue()
        log.info("🔄 Monitoramento da fila iniciado");
    }

    private void broadcastQueueUpdate() {
        try {
            QueueStatusDTO status = getQueueStatus(null);
            webSocketService.broadcastQueueUpdate(status.getPlayersInQueueList());
        } catch (Exception e) {
            log.error("❌ Erro ao fazer broadcast da atualização da fila", e);
        }
    }
}
