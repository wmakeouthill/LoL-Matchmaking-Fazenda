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
import org.springframework.context.annotation.Lazy;
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
public class QueueManagementService {

    private final QueuePlayerRepository queuePlayerRepository;
    private final PlayerRepository playerRepository;
    private final CustomMatchRepository customMatchRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final DiscordService discordService;
    private final LCUService lcuService;
    private final LCUConnectionRegistry lcuConnectionRegistry;
    private final ObjectMapper objectMapper;
    private final MatchFoundService matchFoundService;
    private final PlayerService playerService;

    public QueueManagementService(
            QueuePlayerRepository queuePlayerRepository,
            PlayerRepository playerRepository,
            CustomMatchRepository customMatchRepository,
            MatchmakingWebSocketService webSocketService,
            DiscordService discordService,
            LCUService lcuService,
            LCUConnectionRegistry lcuConnectionRegistry,
            ObjectMapper objectMapper,
            @Lazy MatchFoundService matchFoundService,
            PlayerService playerService) {
        this.queuePlayerRepository = queuePlayerRepository;
        this.playerRepository = playerRepository;
        this.customMatchRepository = customMatchRepository;
        this.webSocketService = webSocketService;
        this.discordService = discordService;
        this.lcuService = lcuService;
        this.lcuConnectionRegistry = lcuConnectionRegistry;
        this.objectMapper = objectMapper;
        this.matchFoundService = matchFoundService;
        this.playerService = playerService;
    }

    // Cache local da fila para performance
    private final Map<String, QueuePlayer> queueCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSync = new ConcurrentHashMap<>();

    // Configurações
    private static final int MAX_QUEUE_SIZE = 20;
    private static final int MATCH_SIZE = 10;
    private static final long QUEUE_SYNC_INTERVAL = 10000; // 10 segundos
    private static final long MATCH_FOUND_TIMEOUT = 30000; // 30 segundos

    // Contador de bots
    private int botCounter = 0;

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
     * Carrega fila do banco de dados (sincroniza cache com banco)
     */
    @Transactional
    public void loadQueueFromDatabase() {
        try {
            List<QueuePlayer> activePlayers = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc();

            log.info("🔄 [QueueManagementService] Sincronizando cache com banco...");
            log.info("📊 [QueueManagementService] Banco: {} jogadores | Cache: {} jogadores",
                    activePlayers.size(), queueCache.size());

            queueCache.clear();
            for (QueuePlayer player : activePlayers) {
                // ✅ CORREÇÃO: Resetar acceptanceStatus para 0 (disponível) ao carregar do banco
                // Isso garante que jogadores salvos com status pendente voltem disponíveis
                player.setAcceptanceStatus(0);

                queueCache.put(player.getSummonerName(), player);
                log.debug("📥 Sincronizado: {} (status: {}, LP: {})",
                        player.getSummonerName(), player.getAcceptanceStatus(), player.getCustomLp());
            }

            log.info("✅ [QueueManagementService] Cache sincronizado: {} jogadores", queueCache.size());

            // ✅ Notificar todos os clientes sobre a atualização
            broadcastQueueUpdate();
        } catch (Exception e) {
            log.error("❌ Erro ao sincronizar cache com banco", e);
        }
    }

    /**
     * Adiciona jogador à fila
     */
    @Transactional
    public boolean addToQueue(String summonerName, String region, Long playerId,
            Integer customLp, String primaryLane, String secondaryLane) {
        try {
            log.info("➕ [addToQueue] Adicionando jogador à fila: {} (cache size antes: {})",
                    summonerName, queueCache.size());

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

            // ✅ NOVO: Buscar Player da tabela para pegar custom_mmr (current_mmr +
            // custom_lp)
            int finalMmr = 0;
            Optional<Player> playerOpt = playerRepository.findBySummonerNameIgnoreCase(summonerName);
            if (playerOpt.isPresent()) {
                Player player = playerOpt.get();
                // ✅ Usar custom_mmr que já é calculado (current_mmr + custom_lp)
                finalMmr = player.getCustomMmr() != null ? player.getCustomMmr() : 0;
                log.info("📊 Player encontrado: {} (current_mmr: {}, custom_lp: {}, custom_mmr: {})",
                        summonerName, player.getCurrentMmr(), player.getCustomLp(), finalMmr);
            } else {
                // Player não encontrado na tabela (pode ser bot ou jogador não logado ainda)
                // Usar apenas o customLp fornecido
                finalMmr = customLp != null ? customLp : 0;
                log.info("📊 Player não encontrado na tabela, usando MMR fornecido: {} ({})", summonerName, finalMmr);
            }

            // Criar nova entrada na fila
            QueuePlayer queuePlayer = QueuePlayer.builder()
                    .playerId(playerId != null ? playerId : 0L)
                    .summonerName(summonerName)
                    .region(region)
                    .customLp(finalMmr) // ✅ Usar MMR total calculado
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
            log.info("✅ [addToQueue] Jogador adicionado ao cache (cache size agora: {})", queueCache.size());

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
            log.debug("🔍 [Scheduled] Verificando fila... Cache: {} jogadores, MATCH_SIZE: {}",
                    queueCache.size(), MATCH_SIZE);

            if (queueCache.size() < MATCH_SIZE) {
                log.debug("⏭️ [Scheduled] Fila insuficiente ({} < {}), aguardando mais jogadores",
                        queueCache.size(), MATCH_SIZE);
                return;
            }

            log.info("🎯 Processando fila para encontrar partida ({} jogadores)", queueCache.size());

            // ✅ Log dos status de aceitação
            queueCache.values().forEach(
                    p -> log.debug("  - {}: acceptanceStatus = {}", p.getSummonerName(), p.getAcceptanceStatus()));

            // ✅ Obter APENAS jogadores disponíveis (não estão em partida pendente)
            List<QueuePlayer> activePlayers = new ArrayList<>(queueCache.values()).stream()
                    .filter(p -> p.getAcceptanceStatus() == 0) // 0 = disponível, -1 = aguardando aceitação
                    .sorted(Comparator.comparing(QueuePlayer::getJoinTime))
                    .collect(Collectors.toList());

            log.info("📊 Jogadores disponíveis para matchmaking: {}/{}", activePlayers.size(), queueCache.size());

            if (activePlayers.size() < MATCH_SIZE) {
                log.warn("⏳ Apenas {} jogadores disponíveis (outros aguardando aceitação)", activePlayers.size());
                return;
            }

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
     * Normaliza nomes de lanes para um formato padrão (minúsculo)
     */
    private String normalizeLane(String lane) {
        if (lane == null)
            return "fill";

        String normalized = lane.toLowerCase().trim();

        // Normalizar variações
        if (normalized.equals("adc") || normalized.equals("bot") || normalized.equals("bottom")) {
            return "bot";
        }
        if (normalized.equals("middle") || normalized.equals("mid")) {
            return "mid";
        }
        if (normalized.equals("top")) {
            return "top";
        }
        if (normalized.equals("jungle") || normalized.equals("jg")) {
            return "jungle";
        }
        if (normalized.equals("support") || normalized.equals("sup") || normalized.equals("supp")) {
            return "support";
        }

        return "fill";
    }

    /**
     * Balanceia equipes por MMR e preferências de lane
     */
    private List<QueuePlayer> balanceTeamsByMMRAndLanes(List<QueuePlayer> players) {
        if (players.size() != MATCH_SIZE) {
            return new ArrayList<>();
        }

        log.info("🎯 [Balanceamento] Iniciando balanceamento de {} jogadores", players.size());

        // ✅ CORREÇÃO: Ordenar por custom_mmr da tabela players (current_mmr +
        // custom_lp)
        // Para jogadores sem entrada na tabela (bots), usar customLp do QueuePlayer
        players.sort((a, b) -> {
            int mmrA = a.getCustomLp() != null ? a.getCustomLp() : 0;
            int mmrB = b.getCustomLp() != null ? b.getCustomLp() : 0;

            // ✅ NOVO: Buscar custom_mmr real da tabela players se existir
            Optional<Player> playerA = playerRepository.findBySummonerNameIgnoreCase(a.getSummonerName());
            if (playerA.isPresent() && playerA.get().getCustomMmr() != null) {
                mmrA = playerA.get().getCustomMmr();
            }

            Optional<Player> playerB = playerRepository.findBySummonerNameIgnoreCase(b.getSummonerName());
            if (playerB.isPresent() && playerB.get().getCustomMmr() != null) {
                mmrB = playerB.get().getCustomMmr();
            }

            return Integer.compare(mmrB, mmrA); // Maior MMR primeiro
        });

        // ✅ Log da ordem de prioridade por MMR
        log.info("📊 [Balanceamento] Ordem por MMR (maior para menor):");
        for (int i = 0; i < players.size(); i++) {
            QueuePlayer p = players.get(i);
            int mmr = p.getCustomLp() != null ? p.getCustomLp() : 0;
            Optional<Player> playerEntity = playerRepository.findBySummonerNameIgnoreCase(p.getSummonerName());
            if (playerEntity.isPresent() && playerEntity.get().getCustomMmr() != null) {
                mmr = playerEntity.get().getCustomMmr();
            }
            log.info("  {}. {} - MMR: {} - Primária: {} - Secundária: {}",
                    i + 1, p.getSummonerName(), mmr,
                    normalizeLane(p.getPrimaryLane()),
                    normalizeLane(p.getSecondaryLane()));
        }

        // Mapeamento de lanes para posições no time (usando minúsculo)
        Map<String, Map<String, Integer>> laneToTeamIndex = Map.of(
                "top", Map.of("team1", 0, "team2", 5),
                "jungle", Map.of("team1", 1, "team2", 6),
                "mid", Map.of("team1", 2, "team2", 7),
                "bot", Map.of("team1", 3, "team2", 8),
                "support", Map.of("team1", 4, "team2", 9));

        // Controle de lanes ocupadas (Maps mutáveis)
        Map<String, Map<String, Boolean>> laneAssignments = new HashMap<>();
        for (String lane : Arrays.asList("top", "jungle", "mid", "bot", "support")) {
            Map<String, Boolean> teamAssignments = new HashMap<>();
            teamAssignments.put("team1", false);
            teamAssignments.put("team2", false);
            laneAssignments.put(lane, teamAssignments);
        }

        List<QueuePlayer> balancedTeams = new ArrayList<>(Collections.nCopies(MATCH_SIZE, null));

        // ✅ Atribuir lanes por prioridade de MMR
        for (int i = 0; i < players.size(); i++) {
            QueuePlayer player = players.get(i);
            String primaryLane = normalizeLane(player.getPrimaryLane());
            String secondaryLane = normalizeLane(player.getSecondaryLane());

            boolean assigned = false;
            String assignedLane = null;
            String assignedTeam = null;

            // Tentar lane primária
            if (!primaryLane.equals("fill") && laneAssignments.containsKey(primaryLane)) {
                if (!laneAssignments.get(primaryLane).get("team1")) {
                    int teamIndex = laneToTeamIndex.get(primaryLane).get("team1");
                    balancedTeams.set(teamIndex, player);
                    laneAssignments.get(primaryLane).put("team1", true);
                    assigned = true;
                    assignedLane = primaryLane;
                    assignedTeam = "team1";
                } else if (!laneAssignments.get(primaryLane).get("team2")) {
                    int teamIndex = laneToTeamIndex.get(primaryLane).get("team2");
                    balancedTeams.set(teamIndex, player);
                    laneAssignments.get(primaryLane).put("team2", true);
                    assigned = true;
                    assignedLane = primaryLane;
                    assignedTeam = "team2";
                }
            }

            // Tentar lane secundária se primária não disponível
            if (!assigned && !secondaryLane.equals("fill") && laneAssignments.containsKey(secondaryLane)) {
                if (!laneAssignments.get(secondaryLane).get("team1")) {
                    int teamIndex = laneToTeamIndex.get(secondaryLane).get("team1");
                    balancedTeams.set(teamIndex, player);
                    laneAssignments.get(secondaryLane).put("team1", true);
                    assigned = true;
                    assignedLane = secondaryLane;
                    assignedTeam = "team1";
                } else if (!laneAssignments.get(secondaryLane).get("team2")) {
                    int teamIndex = laneToTeamIndex.get(secondaryLane).get("team2");
                    balancedTeams.set(teamIndex, player);
                    laneAssignments.get(secondaryLane).put("team2", true);
                    assigned = true;
                    assignedLane = secondaryLane;
                    assignedTeam = "team2";
                }
            }

            // Autofill se necessário
            if (!assigned) {
                for (String lane : Arrays.asList("top", "jungle", "mid", "bot", "support")) {
                    if (!laneAssignments.get(lane).get("team1")) {
                        int teamIndex = laneToTeamIndex.get(lane).get("team1");
                        balancedTeams.set(teamIndex, player);
                        laneAssignments.get(lane).put("team1", true);
                        assigned = true;
                        assignedLane = lane;
                        assignedTeam = "team1";
                        break;
                    } else if (!laneAssignments.get(lane).get("team2")) {
                        int teamIndex = laneToTeamIndex.get(lane).get("team2");
                        balancedTeams.set(teamIndex, player);
                        laneAssignments.get(lane).put("team2", true);
                        assigned = true;
                        assignedLane = lane;
                        assignedTeam = "team2";
                        break;
                    }
                }
            }

            // ✅ Log da atribuição
            String status = assignedLane.equals(primaryLane) ? "1ª LANE"
                    : assignedLane.equals(secondaryLane) ? "2ª LANE" : "AUTO-FILL";
            log.info("  ✅ {} → {} / {} → {}", player.getSummonerName(), assignedTeam, assignedLane, status);
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

            // ✅ NÃO remover jogadores aqui! Eles só devem ser removidos após aceitação
            // completa
            // O MatchFoundService gerencia o ciclo de vida:
            // - Se TODOS aceitam → MatchFoundService remove da fila
            // - Se ALGUÉM recusa → MatchFoundService remove apenas quem recusou, outros
            // voltam

            // Iniciar processo de aceitação via MatchFoundService
            matchFoundService.createMatchForAcceptance(match, team1, team2);

            log.info("✅ Partida criada: ID {} - aguardando aceitação (jogadores ainda na fila)", match.getId());

        } catch (Exception e) {
            log.error("❌ Erro ao criar partida", e);
        }
    }

    /**
     * Verifica se jogador pode entrar na fila (LCU + Discord + Canal)
     */
    public boolean canPlayerJoinQueue(String summonerName) {
        try {
            // ✅ CORREÇÃO: Verificar se LCU está disponível via gateway OU conexão direta
            // No Cloud Run, usamos gateway WebSocket do Electron, não HTTP direto
            boolean lcuAvailable = false;

            // Verificar se há conexão LCU registrada no registry para este jogador
            Optional<br.com.lolmatchmaking.backend.service.LCUConnectionRegistry.LCUConnectionInfo> lcuConnection = lcuConnectionRegistry
                    .getConnection(summonerName);

            if (lcuConnection.isPresent()) {
                log.debug("✅ LCU disponível via gateway para {}", summonerName);
                lcuAvailable = true;
            } else if (lcuService.isConnected()) {
                log.debug("✅ LCU disponível via conexão direta para {}", summonerName);
                lcuAvailable = true;
            }

            if (!lcuAvailable) {
                log.debug("⚠️ LCU não disponível para {} (sem gateway registrado e sem conexão direta)", summonerName);
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

    /**
     * Calcula a média de MMR de uma lista de jogadores
     * ✅ Usa custom_mmr da tabela players (current_mmr + custom_lp) quando
     * disponível
     */
    private int calculateAverageMMR(List<QueuePlayer> players) {
        return (int) players.stream()
                .mapToInt(p -> {
                    // ✅ Buscar custom_mmr real da tabela players
                    Optional<Player> playerOpt = playerRepository.findBySummonerNameIgnoreCase(p.getSummonerName());
                    if (playerOpt.isPresent() && playerOpt.get().getCustomMmr() != null) {
                        return playerOpt.get().getCustomMmr();
                    }
                    // ✅ Fallback: usar customLp do QueuePlayer (para bots)
                    return p.getCustomLp() != null ? p.getCustomLp() : 0;
                })
                .average()
                .orElse(0);
    }

    private String convertPlayersToJson(List<QueuePlayer> players) {
        // ✅ Retornar CSV simples, não JSON array (compatível com backend antigo)
        return players.stream()
                .map(QueuePlayer::getSummonerName)
                .collect(Collectors.joining(","));
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

    /**
     * Adiciona um bot à fila para testes
     */
    @Transactional
    public void addBotToQueue() {
        try {
            botCounter++;
            String botName = "Bot" + botCounter;

            // MMR aleatório entre 800 e 2000
            int randomMMR = 800 + (int) (Math.random() * 1200);

            // Lanes aleatórias
            String[] lanes = { "top", "jungle", "mid", "bot", "support" };
            String primaryLane = lanes[(int) (Math.random() * lanes.length)];
            String secondaryLane = lanes[(int) (Math.random() * lanes.length)];

            log.info("🤖 Criando bot: {} (MMR: {}, Lane: {})", botName, randomMMR, primaryLane);

            // Remover bot anterior se existir (com flush para garantir que foi deletado)
            queuePlayerRepository.findBySummonerName(botName).ifPresent(qp -> {
                log.info("🗑️ Removendo bot anterior: {}", botName);
                queueCache.remove(botName);
                queuePlayerRepository.delete(qp);
                queuePlayerRepository.flush(); // ✅ Forçar delete antes de criar novo
            });

            // Criar bot na fila
            QueuePlayer botPlayer = QueuePlayer.builder()
                    .playerId(-1L * botCounter) // ID negativo para bots
                    .summonerName(botName)
                    .region("br1")
                    .customLp(randomMMR)
                    .primaryLane(primaryLane)
                    .secondaryLane(secondaryLane)
                    .joinTime(Instant.now())
                    .queuePosition(calculateNextPosition())
                    .active(true)
                    .acceptanceStatus(0) // ✅ Disponível para matchmaking (vai auto-aceitar depois)
                    .build();

            // Salvar no banco
            botPlayer = queuePlayerRepository.save(botPlayer);

            // Adicionar ao cache
            queueCache.put(botName, botPlayer);

            // Atualizar posições
            updateQueuePositions();

            // Broadcast atualização
            broadcastQueueUpdate();

            log.info("✅ Bot {} adicionado à fila (posição: {}, MMR: {}, Lane: {})",
                    botName, botPlayer.getQueuePosition(), randomMMR, primaryLane);

        } catch (Exception e) {
            log.error("❌ Erro ao adicionar bot à fila", e);
            throw new RuntimeException("Erro ao adicionar bot à fila", e);
        }
    }

    /**
     * Reseta o contador de bots
     */
    public void resetBotCounter() {
        try {
            log.info("🔄 Resetando contador de bots (anterior: {})", botCounter);
            botCounter = 0;
            log.info("✅ Contador de bots resetado para 0");
        } catch (Exception e) {
            log.error("❌ Erro ao resetar contador de bots", e);
            throw new RuntimeException("Erro ao resetar contador de bots", e);
        }
    }

    /**
     * ✅ NOVO: Busca partida ativa (draft ou in_progress) do jogador
     * Usado para restaurar estado ao reabrir app
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getActiveMatchForPlayer(String summonerName) {
        try {
            log.debug("🔍 Buscando partida ativa para summonerName: {}", summonerName);

            // ✅ CORRIGIDO: Buscar diretamente pelo summonerName
            // Os campos team1_players/team2_players contêm summonerNames, não PUUIDs
            Optional<CustomMatch> activeMatchOpt = customMatchRepository
                    .findActiveMatchByPlayerPuuid(summonerName);

            if (activeMatchOpt.isEmpty()) {
                log.debug("✅ Nenhuma partida ativa encontrada para: {}", summonerName);
                return Collections.emptyMap();
            }

            CustomMatch match = activeMatchOpt.get();

            log.info("✅ Partida ativa encontrada - ID: {}, Status: {}, Title: {}",
                    match.getId(), match.getStatus(), match.getTitle());

            // 3. Converter para formato esperado pelo frontend
            Map<String, Object> response = new HashMap<>();
            response.put("id", match.getId());
            response.put("matchId", match.getId());
            response.put("status", match.getStatus());
            response.put("title", match.getTitle());
            response.put("createdAt", match.getCreatedAt());

            // 4. Adicionar dados específicos por status
            if ("draft".equals(match.getStatus())) {
                response.put("type", "draft");

                Object pickBanData = parseJsonSafely(match.getPickBanDataJson());
                response.put("draftState", pickBanData);

                // Extrair team1/team2 do pick_ban_data para consistência
                List<Map<String, Object>> team1Players = new ArrayList<>();
                List<Map<String, Object>> team2Players = new ArrayList<>();

                if (pickBanData instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pickBanMap = (Map<String, Object>) pickBanData;

                    // Tentar extrair de teams.blue/red primeiro
                    if (pickBanMap.containsKey("teams") && pickBanMap.get("teams") instanceof Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> teams = (Map<String, Object>) pickBanMap.get("teams");

                        if (teams.containsKey("blue") && teams.get("blue") instanceof Map<?, ?>) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> blueTeam = (Map<String, Object>) teams.get("blue");
                            if (blueTeam.containsKey("players") && blueTeam.get("players") instanceof List<?>) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> bluePlayers = (List<Map<String, Object>>) blueTeam
                                        .get("players");
                                team1Players = bluePlayers;
                            }
                        }

                        if (teams.containsKey("red") && teams.get("red") instanceof Map<?, ?>) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> redTeam = (Map<String, Object>) teams.get("red");
                            if (redTeam.containsKey("players") && redTeam.get("players") instanceof List<?>) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> redPlayers = (List<Map<String, Object>>) redTeam
                                        .get("players");
                                team2Players = redPlayers;
                            }
                        }
                    }

                    // Fallback: usar team1/team2 direto
                    if (team1Players.isEmpty() && pickBanMap.containsKey("team1")) {
                        Object t1 = pickBanMap.get("team1");
                        if (t1 instanceof List<?>) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> list1 = (List<Map<String, Object>>) t1;
                            team1Players = list1;
                        }
                    }
                    if (team2Players.isEmpty() && pickBanMap.containsKey("team2")) {
                        Object t2 = pickBanMap.get("team2");
                        if (t2 instanceof List<?>) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> list2 = (List<Map<String, Object>>) t2;
                            team2Players = list2;
                        }
                    }
                }

                response.put("team1", team1Players);
                response.put("team2", team2Players);

                log.debug("✅ Retornando draft - Team1: {} jogadores, Team2: {} jogadores",
                        team1Players.size(), team2Players.size());

            } else if ("in_progress".equals(match.getStatus())) {
                response.put("type", "game");

                // ✅ CORREÇÃO APRIMORADA: Extrair jogadores do pick_ban_data.teams
                Object pickBanData = parseJsonSafely(match.getPickBanDataJson());
                List<Map<String, Object>> team1Players = new ArrayList<>();
                List<Map<String, Object>> team2Players = new ArrayList<>();

                // Extrair jogadores da estrutura pick_ban_data
                if (pickBanData instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pickBanMap = (Map<String, Object>) pickBanData;

                    // Verificar se tem a estrutura teams.blue e teams.red
                    if (pickBanMap.containsKey("teams") && pickBanMap.get("teams") instanceof Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> teams = (Map<String, Object>) pickBanMap.get("teams");

                        // Extrair Blue Team (team1)
                        if (teams.containsKey("blue") && teams.get("blue") instanceof Map<?, ?>) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> blueTeam = (Map<String, Object>) teams.get("blue");
                            if (blueTeam.containsKey("players") && blueTeam.get("players") instanceof List<?>) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> bluePlayers = (List<Map<String, Object>>) blueTeam
                                        .get("players");
                                team1Players = bluePlayers;
                                log.debug("✅ Extraídos {} jogadores do Blue Team (teams.blue.players)",
                                        team1Players.size());
                            }
                        }

                        // Extrair Red Team (team2)
                        if (teams.containsKey("red") && teams.get("red") instanceof Map<?, ?>) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> redTeam = (Map<String, Object>) teams.get("red");
                            if (redTeam.containsKey("players") && redTeam.get("players") instanceof List<?>) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> redPlayers = (List<Map<String, Object>>) redTeam
                                        .get("players");
                                team2Players = redPlayers;
                                log.debug("✅ Extraídos {} jogadores do Red Team (teams.red.players)",
                                        team2Players.size());
                            }
                        }
                    }

                    // Fallback: tentar usar team1/team2 direto do pickBanData (compatibilidade)
                    if (team1Players.isEmpty() && pickBanMap.containsKey("team1")
                            && pickBanMap.get("team1") instanceof List<?>) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> t1 = (List<Map<String, Object>>) pickBanMap.get("team1");
                        team1Players = t1;
                        log.debug("✅ Usando team1 direto do pickBanData (fallback)");
                    }
                    if (team2Players.isEmpty() && pickBanMap.containsKey("team2")
                            && pickBanMap.get("team2") instanceof List<?>) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> t2 = (List<Map<String, Object>>) pickBanMap.get("team2");
                        team2Players = t2;
                        log.debug("✅ Usando team2 direto do pickBanData (fallback)");
                    }
                }

                response.put("team1", team1Players);
                response.put("team2", team2Players);
                response.put("pickBanData", pickBanData);
                response.put("startTime", match.getCreatedAt());
                response.put("sessionId", "restored-" + match.getId());
                response.put("gameId", String.valueOf(match.getId()));
                response.put("isCustomGame", true);

                log.info("✅ Retornando game in progress - Team1: {} jogadores, Team2: {} jogadores",
                        team1Players.size(), team2Players.size());
            }

            return response;

        } catch (Exception e) {
            log.error("❌ Erro ao buscar partida ativa para {}: {}", summonerName, e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * Helper: Parse JSON com tratamento de erros
     */
    private Object parseJsonSafely(String json) {
        if (json == null || json.isEmpty() || json.equals("null")) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            log.warn("⚠️ Erro ao parsear JSON: {}", e.getMessage());
            return json; // Retorna string original em caso de erro
        }
    }
}
