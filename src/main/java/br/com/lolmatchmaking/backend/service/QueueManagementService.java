package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.*;
import br.com.lolmatchmaking.backend.domain.repository.*;
import br.com.lolmatchmaking.backend.dto.QueueStatusDTO;
import br.com.lolmatchmaking.backend.dto.QueuePlayerInfoDTO;
import br.com.lolmatchmaking.backend.service.lock.PlayerState;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.time.Duration;

@Slf4j
@Service
public class QueueManagementService {

    private final QueuePlayerRepository queuePlayerRepository;
    private final PlayerRepository playerRepository;
    private final CustomMatchRepository customMatchRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final DiscordService discordService;

    // ✅ NOVO: Redis cache para performance de listagem
    private final br.com.lolmatchmaking.backend.service.redis.RedisQueueCacheService redisQueueCache;
    private final LCUService lcuService;
    private final LCUConnectionRegistry lcuConnectionRegistry;
    private final ObjectMapper objectMapper;
    private final br.com.lolmatchmaking.backend.mapper.UnifiedMatchDataMapper matchDataMapper;
    private final MatchFoundService matchFoundService;
    private final PlayerService playerService;

    // ✅ NOVO: Services de Lock e Broadcasting
    private final br.com.lolmatchmaking.backend.service.lock.MatchmakingLockService matchmakingLockService;
    private final br.com.lolmatchmaking.backend.service.lock.PlayerStateService playerStateService;
    private final EventBroadcastService eventBroadcastService;
    private final br.com.lolmatchmaking.backend.service.redis.RedisPlayerMatchService redisPlayerMatch;
    private final RedisDraftFlowService redisDraftFlow;
    private final DraftFlowService draftFlowService;
    private final br.com.lolmatchmaking.backend.websocket.SessionRegistry sessionRegistry;
    private final RedisTemplate<String, Object> redisTemplate;

    // ✅ Construtor com injeção de dependências
    public QueueManagementService(
            QueuePlayerRepository queuePlayerRepository,
            PlayerRepository playerRepository,
            CustomMatchRepository customMatchRepository,
            MatchmakingWebSocketService webSocketService,
            DiscordService discordService,
            br.com.lolmatchmaking.backend.service.redis.RedisQueueCacheService redisQueueCache,
            LCUService lcuService,
            LCUConnectionRegistry lcuConnectionRegistry,
            ObjectMapper objectMapper,
            br.com.lolmatchmaking.backend.mapper.UnifiedMatchDataMapper matchDataMapper,
            @Lazy MatchFoundService matchFoundService,
            PlayerService playerService,
            br.com.lolmatchmaking.backend.service.lock.MatchmakingLockService matchmakingLockService,
            br.com.lolmatchmaking.backend.service.lock.PlayerStateService playerStateService,
            EventBroadcastService eventBroadcastService,
            br.com.lolmatchmaking.backend.service.redis.RedisPlayerMatchService redisPlayerMatchService,
            RedisDraftFlowService redisDraftFlowService,
            @Lazy DraftFlowService draftFlowService,
            br.com.lolmatchmaking.backend.websocket.SessionRegistry sessionRegistry,
            RedisTemplate<String, Object> redisTemplate) {
        this.queuePlayerRepository = queuePlayerRepository;
        this.playerRepository = playerRepository;
        this.customMatchRepository = customMatchRepository;
        this.webSocketService = webSocketService;
        this.discordService = discordService;
        this.redisQueueCache = redisQueueCache;
        this.lcuService = lcuService;
        this.lcuConnectionRegistry = lcuConnectionRegistry;
        this.objectMapper = objectMapper;
        this.matchDataMapper = matchDataMapper;
        this.matchFoundService = matchFoundService;
        this.playerService = playerService;
        this.matchmakingLockService = matchmakingLockService;
        this.playerStateService = playerStateService;
        this.eventBroadcastService = eventBroadcastService;
        this.redisPlayerMatch = redisPlayerMatchService;
        this.redisDraftFlow = redisDraftFlowService;
        this.draftFlowService = draftFlowService;
        this.sessionRegistry = sessionRegistry;
        this.redisTemplate = redisTemplate;
    }

    // ✅ REMOVIDO: HashMaps locais removidos - SQL é fonte da verdade
    // Use queuePlayerRepository para todas as operações de fila

    // Configurações
    private static final int MAX_QUEUE_SIZE = 30;
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

            log.info("🔄 [QueueManagementService] Carregando fila do banco...");
            log.info("📊 [QueueManagementService] Banco: {} jogadores ativos",
                    activePlayers.size());

            // ✅ SQL ONLY: Resetar acceptanceStatus para 0 (disponível) ao carregar do banco
            for (QueuePlayer player : activePlayers) {
                // Isso garante que jogadores salvos com status pendente voltem disponíveis
                player.setAcceptanceStatus(0);
                queuePlayerRepository.save(player);

                log.debug("📥 Carregado: {} (status: {}, LP: {})",
                        player.getSummonerName(), player.getAcceptanceStatus(), player.getCustomLp());
            }

            log.info("✅ [QueueManagementService] Fila carregada do SQL: {} jogadores", activePlayers.size());

            // ✅ Notificar todos os clientes sobre a atualização
            broadcastQueueUpdate();
        } catch (Exception e) {
            log.error("❌ Erro ao carregar fila do banco", e);
        }
    }

    /**
     * Adiciona jogador à fila COM informações da sessão Electron
     */
    @Transactional
    public boolean addToQueueWithSession(String summonerName, String region, Long playerId,
            Integer customLp, String primaryLane, String secondaryLane,
            String sessionId, String puuid, Map<String, Object> lcuData) {

        log.info(
                "🔗 [Player-Sessions] [QUEUE] Adicionando jogador à fila COM sessão Electron: {} (sessionId: {}, puuid: {})",
                summonerName, sessionId, puuid);

        // ✅ NOVO: Validar se sessão Electron está ativa
        if (sessionId == null || sessionId.isEmpty()) {
            log.error("❌ [Player-Sessions] [QUEUE] SessionId é obrigatório para entrada na fila: {}", summonerName);
            return false;
        }

        if (puuid == null || puuid.isEmpty()) {
            log.error("❌ [Player-Sessions] [QUEUE] PUUID é obrigatório para entrada na fila: {}", summonerName);
            return false;
        }

        // ✅ NOVO: Log dos dados LCU recebidos
        if (lcuData != null && !lcuData.isEmpty()) {
            log.info("🔗 [Player-Sessions] [QUEUE] Dados LCU recebidos para {}: {}", summonerName, lcuData.keySet());
        }

        // ✅ NOVO: Armazenar informações da sessão no Redis para validação futura
        storeSessionInfoInRedis(summonerName, sessionId, puuid, lcuData);

        // Chamar método original com validações adicionais
        return addToQueue(summonerName, region, playerId, customLp, primaryLane, secondaryLane);
    }

    /**
     * Adiciona jogador à fila (método original)
     */
    @Transactional
    public boolean addToQueue(String summonerName, String region, Long playerId,
            Integer customLp, String primaryLane, String secondaryLane) {
        try {
            // ✅ NOVO: VERIFICAR SE JOGADOR PODE ENTRAR NA FILA
            if (!playerStateService.canJoinQueue(summonerName)) {
                br.com.lolmatchmaking.backend.service.lock.PlayerState currentState = playerStateService
                        .getPlayerState(summonerName);
                // Sempre corrigir o Redis para refletir o MySQL
                if (currentState == PlayerState.IN_QUEUE) {
                    boolean reallyInQueue = queuePlayerRepository.findBySummonerName(summonerName)
                            .map(qp -> qp.getActive() != null && qp.getActive())
                            .orElse(false);
                    if (!reallyInQueue) {
                        boolean inActiveMatch = customMatchRepository.findActiveMatchByPlayerPuuid(summonerName)
                                .isPresent();
                        if (inActiveMatch) {
                            log.error(
                                    "🚨 [addToQueue] Tentativa de cleanup de estado mas jogador {} está em partida ativa no MySQL! NUNCA limpar estado!",
                                    summonerName);
                            return false;
                        }
                        log.warn(
                                "🧹 [addToQueue] ESTADO FANTASMA detectado: {} tem estado IN_QUEUE mas NÃO está no MySQL!",
                                summonerName);
                        log.warn("🧹 [addToQueue] Corrigindo Redis para refletir MySQL...");
                        playerStateService.forceSetPlayerState(summonerName, PlayerState.AVAILABLE);
                        log.info("✅ [addToQueue] PlayerState FORÇADAMENTE limpo, permitindo entrar na fila agora");
                    } else {
                        log.warn("❌ [addToQueue] Jogador {} JÁ está na fila no MySQL (estado: {})", summonerName,
                                currentState);
                        return false;
                    }
                } else {
                    // ✅ Verificar se o estado reflete a realidade do MySQL
                    boolean inActiveMatch = customMatchRepository.findActiveMatchByPlayerPuuid(summonerName)
                            .isPresent();
                    if (inActiveMatch) {
                        // ✅ MySQL confirma: jogador TEM partida ativa, estado Redis está correto
                        log.error(
                                "🚨 [addToQueue] Jogador {} está em estado {} e em partida ativa no MySQL. NUNCA limpar estado!",
                                summonerName, currentState);
                        return false;
                    }

                    // ❌ ESTADO FANTASMA: Redis diz estado={}, mas MySQL não tem partida ativa!
                    log.warn(
                            "🧹 [addToQueue] ESTADO FANTASMA detectado: {} tem estado {} mas NÃO está em partida ativa no MySQL!",
                            summonerName, currentState);
                    log.warn("🧹 [addToQueue] Corrigindo Redis para refletir MySQL...");
                    playerStateService.forceSetPlayerState(summonerName, PlayerState.AVAILABLE);
                    log.info("✅ [addToQueue] PlayerState FORÇADAMENTE limpo, permitindo entrar na fila agora");
                    // ✅ Agora pode continuar e adicionar à fila
                }
            }

            // ✅ NOVO: VERIFICAR COOLDOWN - Evitar múltiplas partidas em sequência rápida
            // Considerando draft de até 10 minutos + margem de segurança de 5 minutos
            java.time.Instant cooldownSince = java.time.Instant.now().minus(15, java.time.temporal.ChronoUnit.MINUTES);
            Optional<br.com.lolmatchmaking.backend.domain.entity.CustomMatch> recentMatchOpt = customMatchRepository
                    .findRecentMatchByPlayer(summonerName, cooldownSince);

            if (recentMatchOpt.isPresent()) {
                br.com.lolmatchmaking.backend.domain.entity.CustomMatch recentMatch = recentMatchOpt.get();
                long minutesAgo = java.time.Duration.between(recentMatch.getCreatedAt(), java.time.Instant.now())
                        .toMinutes();
                log.warn(
                        "⏰ [addToQueue] Jogador {} tem partida recente (matchId={}, status={}, criada há {}min) - aplicando cooldown de 15min",
                        summonerName, recentMatch.getId(), recentMatch.getStatus(), minutesAgo);
                return false;
            }

            // ✅ SQL ONLY: Buscar fila do banco
            List<QueuePlayer> currentQueue = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc();

            // 🧹 CLEANUP CRÍTICO: GARANTIR que player só tem UMA entrada na fila
            List<QueuePlayer> duplicates = currentQueue.stream()
                    .filter(qp -> qp.getSummonerName().equalsIgnoreCase(summonerName))
                    .toList();

            if (duplicates.size() > 1) {
                log.error("🚨 [addToQueue] DUPLICAÇÃO DETECTADA! {} tem {} entradas na fila!",
                        summonerName, duplicates.size());

                // LIMPAR TODAS as entradas duplicadas
                for (QueuePlayer dupe : duplicates) {
                    queuePlayerRepository.delete(dupe);
                }
                queuePlayerRepository.flush();

                log.info("🧹 [addToQueue] Todas as {} entradas duplicadas removidas para {}",
                        duplicates.size(), summonerName);

                // Recarregar fila após limpeza
                currentQueue = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc();
            }

            log.info("➕ [addToQueue] Adicionando jogador à fila: {} (fila atual: {} jogadores)",
                    summonerName, currentQueue.size());

            // Verificar se já está na fila
            boolean alreadyInQueue = currentQueue.stream()
                    .anyMatch(p -> p.getSummonerName().equals(summonerName));

            if (alreadyInQueue) {
                log.warn("⚠️ Jogador {} já está na fila", summonerName);
                return false;
            }

            // Verificar limite da fila
            if (currentQueue.size() >= MAX_QUEUE_SIZE) {
                log.warn("⚠️ Fila cheia ({} jogadores)", MAX_QUEUE_SIZE);
                return false;
            }

            // Remover entrada anterior se existir
            queuePlayerRepository.findBySummonerName(summonerName).ifPresent(qp -> {
                queuePlayerRepository.delete(qp);
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
            log.info("✅ [addToQueue] Jogador salvo no SQL");

            // ✅ NOVO: ATUALIZAR ESTADO PARA IN_QUEUE
            if (!playerStateService.setPlayerState(summonerName, PlayerState.IN_QUEUE)) {
                log.error("❌ [addToQueue] Falha ao atualizar estado para IN_QUEUE, revertendo...");
                queuePlayerRepository.delete(queuePlayer);
                return false;
            }

            // Atualizar posições
            updateQueuePositions();

            // ✅ NOVO: INVALIDAR CACHE REDIS
            redisQueueCache.clearCache();

            // ✅ NOVO: PUBLICAR EVENTO DE JOGADOR ENTROU (Redis Pub/Sub)
            eventBroadcastService.publishPlayerJoinedQueue(summonerName);

            // ✅ NOVO: PUBLICAR ATUALIZAÇÃO COMPLETA DA FILA (Redis Pub/Sub)
            QueueStatusDTO status = getQueueStatus(null);
            eventBroadcastService.publishQueueUpdate(status);

            log.info("✅ {} entrou na fila EM TEMPO REAL (posição: {})", summonerName, queuePlayer.getQueuePosition());

            // ✅ CRÍTICO: Se fila chegou a 10+ jogadores, processar IMEDIATAMENTE!
            // Não esperar os 5 segundos do @Scheduled
            // Recarregar fila para ter contagem atualizada
            int queueSize = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc().size();
            if (queueSize >= MATCH_SIZE) {
                log.info("🎯 [TRIGGER IMEDIATO] Fila chegou a {} jogadores - processando partida AGORA!",
                        queueSize);
                // ✅ Chamar processQueue de forma assíncrona para não bloquear a resposta HTTP
                CompletableFuture.runAsync(() -> {
                    try {
                        processQueue();
                    } catch (Exception e) {
                        log.error("❌ Erro ao processar fila imediatamente", e);
                    }
                });
            }

            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao adicionar jogador à fila", e);
            // ✅ ROLLBACK: Remover estado se falhou
            playerStateService.setPlayerState(summonerName, PlayerState.AVAILABLE);
            return false;
        }
    }

    /**
     * Remove jogador da fila
     */
    @Transactional
    public boolean removeFromQueue(String summonerName) {
        try {
            // Buscar do banco
            QueuePlayer queuePlayer = queuePlayerRepository.findBySummonerName(summonerName).orElse(null);
            if (queuePlayer == null) {
                log.warn("⚠️ Jogador {} não encontrado na fila, verificando locks/estado...", summonerName);
                // Cleanup inteligente: se não está no MySQL, limpar locks/estado Redis
                playerStateService.forceSetPlayerState(summonerName, PlayerState.AVAILABLE);
                redisQueueCache.clearCache();
                log.info("✅ [removeFromQueue] Cleanup inteligente: locks/estado limpos para {} (não estava na fila)",
                        summonerName);
                // Publicar evento de saída e atualização da fila
                eventBroadcastService.publishPlayerLeftQueue(summonerName);
                QueueStatusDTO status = getQueueStatus(null);
                eventBroadcastService.publishQueueUpdate(status);
                return true;
            }

            // Remover do banco
            queuePlayerRepository.delete(queuePlayer);
            log.info("✅ [removeFromQueue] Jogador removido do SQL: {}", summonerName);

            // Atualizar estado para AVAILABLE
            playerStateService.setPlayerState(summonerName, PlayerState.AVAILABLE);

            // Atualizar posições
            updateQueuePositions();

            // Invalidar cache Redis
            redisQueueCache.clearCache();

            // ✅ NOVO: LIMPAR INFORMAÇÕES DA SESSÃO QUANDO JOGADOR SAI DA FILA
            clearSessionInfoFromRedis(summonerName);

            // Publicar evento de saída e atualização da fila
            eventBroadcastService.publishPlayerLeftQueue(summonerName);
            QueueStatusDTO status = getQueueStatus(null);
            eventBroadcastService.publishQueueUpdate(status);

            log.info("✅ {} saiu da fila EM TEMPO REAL", summonerName);
            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao remover jogador da fila", e);
            return false;
        }
    }

    /**
     * ✅ SQL ONLY: Obtém status da fila do banco
     */
    public QueueStatusDTO getQueueStatus(String currentPlayerDisplayName) {
        // Tentar buscar do cache Redis primeiro (performance)
        QueueStatusDTO cachedStatus = redisQueueCache.getCachedQueueStatus();

        boolean isCurrentPlayerInQueue = false;

        if (cachedStatus != null) {
            log.debug("⚡ [QueueManagementService] Status retornado do cache Redis (rápido)");

            // Marcar jogador atual se fornecido
            if (currentPlayerDisplayName != null) {
                isCurrentPlayerInQueue = cachedStatus.getPlayersInQueueList().stream()
                        .anyMatch(player -> player.getSummonerName().equals(currentPlayerDisplayName) ||
                                player.getSummonerName().contains(currentPlayerDisplayName));
                cachedStatus.getPlayersInQueueList().forEach(player -> {
                    if (player.getSummonerName().equals(currentPlayerDisplayName) ||
                            player.getSummonerName().contains(currentPlayerDisplayName)) {
                        player.setIsCurrentPlayer(true);
                    }
                });
            }
            cachedStatus.setIsCurrentPlayerInQueue(isCurrentPlayerInQueue);
            return cachedStatus;
        }

        // Cache miss: Buscar do SQL e cachear
        log.info("🔄 [QueueManagementService] Cache miss - buscando do SQL");
        List<QueuePlayer> activePlayers = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc();

        log.info("📊 [QueueManagementService] getQueueStatus - {} jogadores no SQL", activePlayers.size());
        log.info("📊 [QueueManagementService] Jogadores: {}",
                activePlayers.stream().map(p -> p.getSummonerName()).collect(Collectors.toList()));

        List<QueuePlayerInfoDTO> playersInQueueList = activePlayers.stream()
                .map(this::convertToQueuePlayerInfoDTO)
                .collect(Collectors.toList());

        if (currentPlayerDisplayName != null) {
            log.info("📊 [QueueManagementService] Procurando jogador atual: {}", currentPlayerDisplayName);
            isCurrentPlayerInQueue = playersInQueueList.stream()
                    .anyMatch(player -> player.getSummonerName().equals(currentPlayerDisplayName) ||
                            player.getSummonerName().contains(currentPlayerDisplayName));
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
                .isCurrentPlayerInQueue(isCurrentPlayerInQueue)
                .build();

        redisQueueCache.cacheQueueStatus(status);
        log.info("✅ [QueueManagementService] Status cacheado no Redis");

        log.info("📊 [QueueManagementService] Status retornado: {} jogadores", status.getPlayersInQueue());
        return status;
    }

    /**
     * Processa a fila para encontrar partidas
     */
    @Scheduled(fixedRate = 5000) // Executa a cada 5 segundos
    public void processQueue() {
        // ✅ NOVO: VERIFICAR SE HÁ SESSÕES ATIVAS ANTES DE PROCESSAR
        if (!hasActiveSessions()) {
            log.debug("⏭️ [Scheduled] Nenhuma sessão ativa, pulando processamento da fila...");
            return;
        }

        // ✅ NOVO: ADQUIRIR LOCK DISTRIBUÍDO
        // Previne múltiplas instâncias processarem fila simultaneamente
        if (!matchmakingLockService.acquireProcessLock()) {
            log.debug("⏭️ [Scheduled] Outra instância está processando fila, pulando...");
            return;
        }

        try {
            // ✅ SQL ONLY: Buscar fila do banco
            List<QueuePlayer> allPlayers = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc();

            log.debug("🔍 [Scheduled] Verificando fila... SQL: {} jogadores, MATCH_SIZE: {}",
                    allPlayers.size(), MATCH_SIZE);

            if (allPlayers.size() < MATCH_SIZE) {
                log.debug("⏭️ [Scheduled] Fila insuficiente ({} < {}), aguardando mais jogadores",
                        allPlayers.size(), MATCH_SIZE);
                return;
            }

            log.info("🎯 Processando fila para encontrar partida ({} jogadores)", allPlayers.size());

            // ✅ Log dos status de aceitação
            allPlayers.forEach(
                    p -> log.debug("  - {}: acceptanceStatus = {}", p.getSummonerName(), p.getAcceptanceStatus()));

            // ✅ NOVO: Filtrar jogadores por PlayerState (deve estar IN_QUEUE ou AVAILABLE)
            List<QueuePlayer> activePlayers = allPlayers.stream()
                    .filter(p -> {
                        // Filtro antigo: acceptanceStatus
                        if (p.getAcceptanceStatus() != 0) {
                            return false;
                        }

                        // ✅ NOVO: Verificar PlayerState COM CLEANUP INTELIGENTE
                        br.com.lolmatchmaking.backend.service.lock.PlayerState state = playerStateService
                                .getPlayerState(p.getSummonerName());

                        // Aceitar apenas jogadores AVAILABLE ou IN_QUEUE
                        // Rejeitar jogadores em IN_MATCH_FOUND, IN_DRAFT, IN_GAME
                        if (state.isInMatch()) {
                            // ✅ CLEANUP INTELIGENTE: Jogador está na FILA (MySQL) mas estado diz que está
                            // em PARTIDA
                            // Isso é INCONSISTÊNCIA! MySQL diz que está na fila, Redis diz que está em
                            // partida
                            log.warn(
                                    "🧹 [processQueue] ESTADO INCONSISTENTE: {} está na FILA (MySQL) mas estado Redis é {}",
                                    p.getSummonerName(), state);
                            log.warn(
                                    "🧹 [processQueue] Player está em queue_players (active=true) - Corrigindo PlayerState...");

                            // ✅ CORRIGIR: Player está na fila do MySQL, então estado deveria ser IN_QUEUE
                            playerStateService.forceSetPlayerState(p.getSummonerName(), PlayerState.IN_QUEUE);
                            log.info("✅ [processQueue] PlayerState de {} FORÇADAMENTE corrigido para IN_QUEUE",
                                    p.getSummonerName());

                            // ✅ AGORA ACEITAR: Player agora pode ser incluído no matchmaking
                            return true;
                        }

                        return true;
                    })
                    .sorted(Comparator.comparing(QueuePlayer::getJoinTime))
                    .collect(Collectors.toList());

            log.info("📊 Jogadores disponíveis para matchmaking: {}/{}", activePlayers.size(), allPlayers.size());

            if (activePlayers.size() < MATCH_SIZE) {
                log.warn("⏳ Apenas {} jogadores disponíveis (outros aguardando aceitação ou em partida)",
                        activePlayers.size());
                return;
            }

            // Selecionar 10 jogadores mais antigos (FIFO)
            List<QueuePlayer> selectedPlayers = activePlayers.stream()
                    .limit(MATCH_SIZE)
                    .collect(Collectors.toList());

            // ✅ NOVO: DOUBLE-CHECK - Verificar se todos ainda estão disponíveis
            boolean allStillAvailable = selectedPlayers.stream()
                    .allMatch(p -> !playerStateService.isInMatch(p.getSummonerName()));

            if (!allStillAvailable) {
                log.warn("⚠️ Alguns jogadores já não estão mais disponíveis, abortando criação de partida");
                return;
            }

            // ✅ CRÍTICO: MARCAR JOGADORES COMO "EM PROCESSAMENTO" IMEDIATAMENTE
            // Isso previne outra instância de pegar os mesmos jogadores
            // AINDA DENTRO DO LOCK!
            log.info("🔒 [FIFO] Marcando {} jogadores como EM PROCESSAMENTO (ainda dentro do lock)",
                    selectedPlayers.size());
            for (QueuePlayer player : selectedPlayers) {
                player.setAcceptanceStatus(-1); // -1 = em processamento
                queuePlayerRepository.save(player);
            }

            // ✅ FLUSH para garantir que SQL foi atualizado ANTES de liberar lock
            queuePlayerRepository.flush();
            log.info("✅ [FIFO] Jogadores marcados e flush realizado - seguro para liberar lock");

            // Balancear equipes por MMR e lanes
            List<QueuePlayer> balancedTeams = balanceTeamsByMMRAndLanes(selectedPlayers);

            if (balancedTeams.size() == MATCH_SIZE) {
                // ✅ Criar partida (com locks e state management)
                createMatch(balancedTeams);
            } else {
                // ❌ Balanceamento falhou - reverter status
                log.error("❌ [FIFO] Balanceamento falhou, revertendo status dos jogadores");
                for (QueuePlayer player : selectedPlayers) {
                    player.setAcceptanceStatus(0);
                    queuePlayerRepository.save(player);
                }
                queuePlayerRepository.flush();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao processar fila", e);
        } finally {
            // ✅ SEMPRE LIBERAR LOCK
            matchmakingLockService.releaseProcessLock();
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
            String status = assignedLane != null && assignedLane.equals(primaryLane) ? "1ª LANE"
                    : assignedLane != null && assignedLane.equals(secondaryLane) ? "2ª LANE" : "AUTO-FILL";
            log.info("  ✅ {} → {} / {} → {}", player.getSummonerName(), assignedTeam, assignedLane, status);
        }

        // ✅ CRÍTICO: Verificar se todos os 10 slots foram preenchidos
        List<QueuePlayer> result = balancedTeams.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (result.size() != MATCH_SIZE) {
            log.error("❌ [CRÍTICO] Balanceamento FALHOU! Apenas {} jogadores de {} foram atribuídos!",
                    result.size(), MATCH_SIZE);
            log.error("❌ Posições vazias detectadas no balanceamento!");

            // Log quais posições ficaram vazias
            for (int i = 0; i < balancedTeams.size(); i++) {
                if (balancedTeams.get(i) == null) {
                    String lane = i == 0 || i == 5 ? "top"
                            : i == 1 || i == 6 ? "jungle"
                                    : i == 2 || i == 7 ? "mid"
                                            : i == 3 || i == 8 ? "bot"
                                                    : "support";
                    String team = i < 5 ? "team1" : "team2";
                    log.error("  ❌ Posição {} vazia: {} / {}", i, team, lane);
                }
            }

            // ⛔ NÃO retornar lista incompleta - retornar vazio para cancelar criação
            return new ArrayList<>();
        }

        log.info("✅ [Balanceamento] Sucesso! {} jogadores balanceados corretamente", result.size());
        return result;
    }

    /**
     * Cria uma nova partida
     */
    @Transactional
    public void createMatch(List<QueuePlayer> players) {
        try {
            log.info("╔════════════════════════════════════════════════════════════════╗");
            log.info("║  🎯 [CRIAÇÃO DE PARTIDA] INICIANDO                            ║");
            log.info("╚════════════════════════════════════════════════════════════════╝");

            // ✅ VALIDAÇÃO CRÍTICA: DEVE TER EXATAMENTE 10 JOGADORES
            if (players.size() != MATCH_SIZE) {
                log.error("❌ [CRÍTICO] Número INCORRETO de jogadores: {} (esperado: {})",
                        players.size(), MATCH_SIZE);
                log.error("❌ [CRÍTICO] PARTIDA NÃO CRIADA - ABORTANDO!");
                return;
            }

            log.info("✅ [Validação] {} jogadores confirmados", players.size());

            // ✅ NOVO: VERIFICAR SE TODOS OS JOGADORES AINDA ESTÃO DISPONÍVEIS
            List<String> playerNames = players.stream()
                    .map(QueuePlayer::getSummonerName)
                    .collect(Collectors.toList());

            // ✅ PROTEÇÃO 0: VERIFICAR SE TODOS AINDA ESTÃO NA FILA (MySQL)
            // CRÍTICO: Jogador pode ter saído DURANTE o processamento!
            for (String playerName : playerNames) {
                Optional<QueuePlayer> stillInQueue = queuePlayerRepository.findBySummonerName(playerName);
                if (stillInQueue.isEmpty() || !stillInQueue.get().getActive()) {
                    log.error(
                            "❌ [CRÍTICO] Jogador {} NÃO está mais na fila! Saiu durante processamento. ABORTANDO criação",
                            playerName);
                    // Reverter acceptance_status dos que ainda estão na fila
                    for (String pn : playerNames) {
                        queuePlayerRepository.findBySummonerName(pn).ifPresent(qp -> {
                            qp.setAcceptanceStatus(0); // Voltar para normal
                            queuePlayerRepository.save(qp);
                        });
                    }
                    return;
                }
            }

            log.info("✅ [Validação] Todos os {} jogadores AINDA estão na fila", playerNames.size());

            // ✅ PROTEÇÃO 1: Verificar PlayerState
            for (String playerName : playerNames) {
                if (playerStateService.isInMatch(playerName)) {
                    log.error("❌ [CRÍTICO] Jogador {} já está em partida! ABORTANDO criação", playerName);
                    // Reverter acceptance_status
                    for (String pn : playerNames) {
                        queuePlayerRepository.findBySummonerName(pn).ifPresent(qp -> {
                            qp.setAcceptanceStatus(0);
                            queuePlayerRepository.save(qp);
                        });
                    }
                    return;
                }
            }

            // ✅ PROTEÇÃO 2: Verificar se jogador já está em outra partida COM CLEANUP
            // INTELIGENTE
            for (String playerName : playerNames) {
                Long existingMatchId = redisPlayerMatch.getCurrentMatch(playerName);
                if (existingMatchId != null) {
                    log.warn("🚨 [createMatch] Redis diz: {} está na partida {}. Verificando MySQL...",
                            playerName, existingMatchId);

                    // ✅ CLEANUP INTELIGENTE: Verificar se essa partida REALMENTE existe no MySQL
                    Optional<CustomMatch> existingMatch = customMatchRepository.findById(existingMatchId);

                    boolean isActiveMatch = existingMatch.isPresent() &&
                            ("match_found".equalsIgnoreCase(existingMatch.get().getStatus()) ||
                                    "accepting".equalsIgnoreCase(existingMatch.get().getStatus()));

                    if (!isActiveMatch) {
                        // ❌ INCONSISTÊNCIA: Redis diz "em partida", MySQL diz "não existe" ou
                        // "finalizada"
                        log.warn("🧹 [createMatch] PARTIDA FANTASMA detectada: Redis={}, MySQL={}",
                                existingMatchId,
                                existingMatch.map(m -> m.getStatus()).orElse("NÃO EXISTE"));
                        log.warn("🧹 [createMatch] Verificando se pode limpar ownership do RedisPlayerMatchService...");
                        // Só limpar se MySQL diz que a partida está finalizada/cancelada
                        if (existingMatch.isPresent()) {
                            String status = existingMatch.get().getStatus();
                            if ("completed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status)) {
                                redisPlayerMatch.clearPlayerMatch(playerName);
                                log.info("✅ [createMatch] Ownership limpo para {} (match finalizada/cancelada)",
                                        playerName);
                            } else {
                                log.info("⏳ [createMatch] Ownership NÃO limpo para {} (match ainda ativa)", playerName);
                            }
                        } else {
                            // Se não existe no MySQL, pode limpar
                            redisPlayerMatch.clearPlayerMatch(playerName);
                            log.info("✅ [createMatch] Ownership limpo para {} (match não existe)", playerName);
                        }
                    } else {
                        // ✅ Partida REALMENTE existe e está ativa - ABORTAR é correto
                        log.error(
                                "❌ [CRÍTICO] Jogador {} JÁ está registrado em partida {} ATIVA (status={})! ABORTANDO",
                                playerName, existingMatchId, existingMatch.get().getStatus());
                        // Reverter estados E acceptance_status
                        for (String pn : playerNames) {
                            playerStateService.forceSetPlayerState(pn, PlayerState.IN_QUEUE);
                            queuePlayerRepository.findBySummonerName(pn).ifPresent(qp -> {
                                qp.setAcceptanceStatus(0);
                                queuePlayerRepository.save(qp);
                            });
                        }
                        return;
                    }
                }
            }

            log.info("✅ [Validação] Todos os jogadores disponíveis e sem partida ativa");

            // ✅ LOG DETALHADO: Mostrar TODOS os 10 jogadores antes de separar
            log.info("📋 [Formação] Lista completa de jogadores balanceados:");
            for (int i = 0; i < players.size(); i++) {
                QueuePlayer p = players.get(i);
                String lane = i % 5 == 0 ? "TOP"
                        : i % 5 == 1 ? "JUNGLE"
                                : i % 5 == 2 ? "MID"
                                        : i % 5 == 3 ? "BOT"
                                                : "SUPPORT";
                String team = i < 5 ? "TEAM1" : "TEAM2";
                log.info("  [{}] {} - {} / {} - MMR: {}",
                        i, p.getSummonerName(), team, lane, p.getCustomLp());
            }

            // Separar em dois times
            List<QueuePlayer> team1 = players.subList(0, 5);
            List<QueuePlayer> team2 = players.subList(5, 10);

            // ✅ VALIDAÇÃO: Garantir que ambos os times têm 5 jogadores
            if (team1.size() != 5 || team2.size() != 5) {
                log.error("❌ [CRÍTICO] Times mal formados! team1={}, team2={}", team1.size(), team2.size());
                return;
            }

            // ✅ CRÍTICO: VALIDAR SESSÕES WEBSOCKET ANTES DE CRIAR MATCH
            // MySQL é fonte da verdade, mas sem sessão = broadcast impossível
            // ⚠️ BOTS são exceção: não têm sessão WebSocket (são do backend)
            log.info("🔍 [Validação] Verificando se JOGADORES HUMANOS têm sessão WebSocket ativa...");
            int sessionsFound = 0;
            int botsFound = 0;
            List<String> humanPlayersWithoutSession = new ArrayList<>();

            for (String playerName : playerNames) {
                // ✅ BOTS não precisam de sessão WebSocket (auto-accept via backend)
                if (playerName.startsWith("Bot")) {
                    botsFound++;
                    log.debug("  🤖 {} é bot - não precisa de sessão", playerName);
                    continue;
                }

                Optional<org.springframework.web.socket.WebSocketSession> sessionOpt = sessionRegistry
                        .getByPlayer(playerName);
                if (sessionOpt.isPresent()) {
                    sessionsFound++;
                    log.debug("  ✅ {} tem sessão ativa", playerName);
                } else {
                    humanPlayersWithoutSession.add(playerName);
                    log.warn("  ❌ {} (HUMANO) NÃO tem sessão WebSocket ativa!", playerName);
                }
            }

            log.info("📊 [Validação Sessões] Humanos: {}/{} com sessão | Bots: {}/{}",
                    sessionsFound, playerNames.size() - botsFound, botsFound, playerNames.size());

            // Se ALGUM jogador HUMANO não tem sessão, ABORTAR criação
            if (!humanPlayersWithoutSession.isEmpty()) {
                log.error("❌ [CRÍTICO] {} jogadores HUMANOS SEM sessão WebSocket! ABORTANDO criação de match",
                        humanPlayersWithoutSession.size());
                log.error("  Jogadores humanos sem sessão: {}", humanPlayersWithoutSession);
                log.error("  ⚠️ MySQL não será poluído com match que falharia no broadcast!");

                // Reverter acceptance_status para permitir novo matchmaking
                for (String pn : playerNames) {
                    queuePlayerRepository.findBySummonerName(pn).ifPresent(qp -> {
                        qp.setAcceptanceStatus(0); // Voltar para disponível
                        queuePlayerRepository.save(qp);
                    });
                }
                queuePlayerRepository.flush();

                log.info("✅ Jogadores revertidos para disponíveis - aguardando reconexão");
                return; // ABORTAR
            }

            log.info("✅ [Validação] Todos os jogadores HUMANOS têm sessão WebSocket ativa - PROSSEGUINDO");

            // ✅ CORREÇÃO: Garantir que todos estão em IN_QUEUE primeiro (fluxo correto)
            for (String playerName : playerNames) {
                PlayerState currentState = playerStateService.getPlayerState(playerName);
                if (currentState != PlayerState.IN_QUEUE) {
                    log.info("🔄 [PlayerState] Colocando {} em IN_QUEUE (estava em {})", playerName, currentState);
                    if (!playerStateService.setPlayerState(playerName, PlayerState.IN_QUEUE)) {
                        log.error("❌ [CRÍTICO] Falha ao colocar {} em IN_QUEUE, ABORTANDO criação", playerName);
                        // Rollback: Voltar acceptance_status
                        for (String pn : playerNames) {
                            queuePlayerRepository.findBySummonerName(pn).ifPresent(qp -> {
                                qp.setAcceptanceStatus(0);
                                queuePlayerRepository.save(qp);
                            });
                        }
                        return;
                    }
                }
            }

            // ✅ NOVO: ATUALIZAR ESTADO DE TODOS PARA IN_MATCH_FOUND (agora válido: IN_QUEUE
            // → IN_MATCH_FOUND)
            for (String playerName : playerNames) {
                if (!playerStateService.setPlayerState(playerName, PlayerState.IN_MATCH_FOUND)) {
                    log.error("❌ [CRÍTICO] Falha ao atualizar estado de {}, ABORTANDO criação", playerName);
                    // Rollback: Voltar estados E acceptance_status
                    for (String pn : playerNames) {
                        playerStateService.forceSetPlayerState(pn, PlayerState.IN_QUEUE);
                        queuePlayerRepository.findBySummonerName(pn).ifPresent(qp -> {
                            qp.setAcceptanceStatus(0);
                            queuePlayerRepository.save(qp);
                        });
                    }
                    return;
                }
            }

            log.info("✅ [Estado] Todos os jogadores marcados como IN_MATCH_FOUND");

            log.info("✅ [Times] Team1: {} jogadores | Team2: {} jogadores", team1.size(), team2.size());

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

            log.info("✅ Partida criada no banco: ID {}", match.getId());

            // ✅ NOVO: CRIAR pick_ban_data IMEDIATAMENTE com estrutura unificada
            log.info("📋 [MATCH_FOUND] Criando pick_ban_data unificado para partida {}", match.getId());
            try {
                br.com.lolmatchmaking.backend.dto.UnifiedMatchDataDTO unifiedDTO = matchDataMapper.toUnifiedDTO(match,
                        team1, team2, "match_found");

                String pickBanJson = matchDataMapper.toJson(unifiedDTO);
                match.setPickBanDataJson(pickBanJson);
                customMatchRepository.save(match);

                log.info("✅ [MATCH_FOUND] pick_ban_data criado com estrutura unificada (phase: match_found)");
                log.info("✅ [MATCH_FOUND] Times salvos: Blue={} jogadores, Red={} jogadores",
                        unifiedDTO.getTeams().getBlue().getPlayers().size(),
                        unifiedDTO.getTeams().getRed().getPlayers().size());
            } catch (Exception e) {
                log.error("❌ [MATCH_FOUND] Erro ao criar pick_ban_data", e);
            }

            // ✅ NÃO remover jogadores aqui! Eles só devem ser removidos após aceitação
            // completa
            // O MatchFoundService gerencia o ciclo de vida:
            // - Se TODOS aceitam → MatchFoundService remove da fila
            // - Se ALGUÉM recusa → MatchFoundService remove apenas quem recusou, outros
            // voltam

            // ✅ NOVO: PUBLICAR match_found VIA REDIS PUB/SUB
            // Garante que TODOS os 10 jogadores recebem notificação SIMULTANEAMENTE
            eventBroadcastService.publishMatchFound(match.getId(), playerNames);

            log.info("📢 [Broadcasting] match_found publicado via Pub/Sub para {} jogadores", playerNames.size());

            // Iniciar processo de aceitação via MatchFoundService
            matchFoundService.createMatchForAcceptance(match, team1, team2);

            log.info("✅ Partida criada: ID {} - aguardando aceitação (jogadores em IN_MATCH_FOUND)", match.getId());

        } catch (Exception e) {
            log.error("❌ Erro ao criar partida", e);
            // ✅ ROLLBACK: Voltar estados para IN_QUEUE
            List<String> playerNames = players.stream()
                    .map(QueuePlayer::getSummonerName)
                    .collect(Collectors.toList());
            // ✅ REVERTER estados (FORCE pois pode estar em IN_MATCH_FOUND)
            for (String pn : playerNames) {
                playerStateService.forceSetPlayerState(pn, PlayerState.IN_QUEUE);
            }
        }
    }

    /**
     * Verifica se jogador pode entrar na fila (LCU + Discord + Canal)
     */
    public boolean canPlayerJoinQueue(String summonerName) {
        log.info("🔍 [canPlayerJoinQueue] Verificando se {} pode entrar na fila", summonerName);
        try {
            // ✅ CORREÇÃO: Verificar se LCU está disponível via gateway OU conexão direta
            // No Cloud Run, usamos gateway WebSocket do Electron, não HTTP direto
            boolean lcuAvailable = false;

            // Verificar se há conexão LCU registrada no registry para este jogador
            Optional<br.com.lolmatchmaking.backend.service.LCUConnectionRegistry.LCUConnectionInfo> lcuConnection = lcuConnectionRegistry
                    .getConnection(summonerName);

            log.info("🔍 [canPlayerJoinQueue] Gateway registry check: present={}", lcuConnection.isPresent());

            if (lcuConnection.isPresent()) {
                log.info("✅ [canPlayerJoinQueue] LCU disponível via gateway para {}", summonerName);
                lcuAvailable = true;
            } else {
                boolean directConnected = lcuService.isConnected();
                log.info("🔍 [canPlayerJoinQueue] Direct LCU check: connected={}", directConnected);
                if (directConnected) {
                    log.info("✅ [canPlayerJoinQueue] LCU disponível via conexão direta para {}", summonerName);
                    lcuAvailable = true;
                }
            }

            if (!lcuAvailable) {
                log.warn(
                        "⚠️ [canPlayerJoinQueue] LCU não disponível para {} (sem gateway registrado e sem conexão direta)",
                        summonerName);
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

            log.info("✅ [canPlayerJoinQueue] {} PODE entrar na fila!", summonerName);
            return true;

        } catch (Exception e) {
            log.error("❌ [canPlayerJoinQueue] Erro ao verificar se jogador pode entrar na fila: {}", e.getMessage(), e);
            return false;
        }
    }

    // Métodos auxiliares

    private int calculateNextPosition() {
        // ✅ SQL ONLY: Buscar tamanho da fila do banco
        long queueSize = queuePlayerRepository.countByActiveTrue();
        return (int) queueSize + 1;
    }

    private void updateQueuePositions() {
        // ✅ SQL ONLY: Buscar fila do banco e atualizar posições
        List<QueuePlayer> sortedPlayers = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc();

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

    /**
     * ✅ NOVO: Parseia CSV de nomes de jogadores para List<String>
     */
    private List<String> parsePlayersFromJson(String playersJson) {
        if (playersJson == null || playersJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(playersJson.split(",\\s*"));
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

    // ✅ NOVO: Cache infinito - só invalida quando alguém conecta
    private volatile boolean hasActiveSessionsCache = false;
    private volatile boolean cacheInitialized = false;
    private volatile long lastCacheInvalidationCheck = 0;

    /**
     * ✅ NOVO: Verifica se há sessões WebSocket ativas com cache infinito
     * 
     * Cache infinito: se não tem sessões, não verifica mais até alguém conectar
     * Só verifica novamente quando cache é invalidado via Redis
     */
    private boolean hasActiveSessions() {
        try {
            // ✅ VERIFICAR REDIS: Se cache foi invalidado por nova conexão
            boolean cacheInvalidated = checkRedisCacheInvalidation();

            // ✅ CACHE INFINITO: Se já verificou e não havia sessões, não verificar mais
            if (cacheInitialized && !hasActiveSessionsCache && !cacheInvalidated) {
                return false; // Retorna cache (sem log, sem verificação)
            }

            // Verificar se há sessões WebSocket ativas (primeira vez ou após invalidação)
            int activeSessions = sessionRegistry.getActiveSessionCount();

            // Atualizar cache
            cacheInitialized = true;
            hasActiveSessionsCache = (activeSessions > 0);

            if (activeSessions > 0) {
                log.info("✅ [QueueManagement] {} sessões ativas encontradas - sistema acordou", activeSessions);
                return true;
            }

            // ✅ Log apenas uma vez quando sistema "dorme"
            log.info("💤 [Cloud Run] Sistema dormindo - 0 sessões ativas (instância pode hibernar)");
            return false;

        } catch (Exception e) {
            log.error("❌ [QueueManagement] Erro ao verificar sessões ativas", e);
            // Em caso de erro, assumir que há sessões (comportamento seguro)
            return true;
        }
    }

    /**
     * ✅ NOVO: Verifica se cache foi invalidado via Redis
     * 
     * @return true se cache foi invalidado por nova conexão
     */
    private boolean checkRedisCacheInvalidation() {
        try {
            String cacheInvalidationKey = "cache:session_invalidation";
            Object invalidationTimestamp = redisTemplate.opsForValue().get(cacheInvalidationKey);

            if (invalidationTimestamp != null) {
                long timestamp = Long.parseLong(invalidationTimestamp.toString());
                if (timestamp > lastCacheInvalidationCheck) {
                    lastCacheInvalidationCheck = timestamp;
                    log.debug("🔄 [QueueManagement] Cache invalidado via Redis (timestamp: {})", timestamp);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("Erro ao verificar invalidação de cache via Redis: {}", e.getMessage());
            return false;
        }
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
            log.info("✅ [addBotToQueue] Bot salvo no SQL: {}", botName);

            // ✅ CRÍTICO: SETAR PlayerState para IN_QUEUE
            // Sem isso, o bot é filtrado em processQueue() e nunca entra em partida!
            if (!playerStateService.setPlayerState(botName, PlayerState.IN_QUEUE)) {
                log.error("❌ [addBotToQueue] Falha ao atualizar PlayerState do bot, removendo...");
                queuePlayerRepository.delete(botPlayer);
                throw new RuntimeException("Falha ao setar PlayerState do bot");
            }
            log.info("✅ [addBotToQueue] PlayerState do bot {} setado para IN_QUEUE", botName);

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

            // ✅ CORREÇÃO: Verificar primeiro se jogador está na fila (queue_players)
            Optional<QueuePlayer> queuePlayer = queuePlayerRepository.findBySummonerName(summonerName);
            if (queuePlayer.isPresent() && queuePlayer.get().getActive()) {
                log.info("✅ [getActiveMatch] Jogador {} está na fila (queue_players)", summonerName);
                Map<String, Object> response = new HashMap<>();
                response.put("id", "queue-" + queuePlayer.get().getId());
                response.put("status", "in_queue");
                response.put("type", "queue");
                response.put("queuePosition", queuePlayer.get().getQueuePosition());
                response.put("joinTime", queuePlayer.get().getJoinTime());
                return response;
            }

            // ✅ CLEANUP INTELIGENTE: Verificar RedisPlayerMatchService primeiro
            Long redisMatchId = redisPlayerMatch.getCurrentMatch(summonerName);
            if (redisMatchId != null) {
                log.debug("🔍 [getActiveMatch] Redis diz: {} está na partida {}. Verificando MySQL...",
                        summonerName, redisMatchId);

                Optional<CustomMatch> redisMatch = customMatchRepository.findById(redisMatchId);
                boolean isRealMatch = redisMatch.isPresent() &&
                        (redisMatch.get().getStatus().equalsIgnoreCase("match_found") ||
                                redisMatch.get().getStatus().equalsIgnoreCase("accepting") ||
                                redisMatch.get().getStatus().equalsIgnoreCase("accepted") ||
                                redisMatch.get().getStatus().equalsIgnoreCase("pending") ||
                                redisMatch.get().getStatus().equalsIgnoreCase("draft") ||
                                redisMatch.get().getStatus().equalsIgnoreCase("in_progress"));

                if (isRealMatch) {
                    // ✅ Redis + MySQL concordam: player TEM partida ativa
                    log.info("✅ [getActiveMatch] Partida ativa encontrada via Redis: ID={}, Status={}",
                            redisMatchId, redisMatch.get().getStatus());

                    // ✅ CRÍTICO: REGISTRAR OWNERSHIP no Redis se ainda não estiver registrado!
                    // Quando jogador retorna via my-active-match, precisa estar registrado para
                    // fazer ações
                    Long currentOwnership = redisPlayerMatch.getCurrentMatch(summonerName);
                    if (currentOwnership == null || !currentOwnership.equals(redisMatchId)) {
                        log.warn(
                                "🧹 [getActiveMatch] Jogador {} retornando ao draft mas ownership não registrado! Registrando agora...",
                                summonerName);
                        redisPlayerMatch.registerPlayerMatch(summonerName, redisMatchId);
                        log.info("✅ [getActiveMatch] Ownership registrado: {} → match {}", summonerName, redisMatchId);
                    }

                    // ✅ Usar a partida encontrada no Redis
                    CustomMatch match = redisMatch.get();
                    // Continuar para montagem da resposta (código abaixo após o if
                    // activeMatchOpt.isEmpty())

                    // Montar resposta
                    Map<String, Object> response = new HashMap<>();
                    response.put("id", match.getId());
                    response.put("matchId", match.getId());
                    response.put("status", match.getStatus());
                    response.put("title", match.getTitle());
                    response.put("createdAt", match.getCreatedAt());

                    // Adicionar dados específicos por status
                    if ("draft".equalsIgnoreCase(match.getStatus())) {
                        response.put("type", "draft");

                        // ✅ CRÍTICO: RENOVAR ownership ao retornar para draft
                        redisPlayerMatch.registerPlayerMatch(summonerName, match.getId());
                        log.info("🔄 [getActiveMatch-Redis] Ownership renovado: {} → draft {}",
                                summonerName, match.getId());

                        // ✅ CRÍTICO: Buscar dados direto do MySQL (sempre fonte da verdade)
                        Map<String, Object> draftDataFromMySQL = draftFlowService.getDraftDataForRestore(match.getId());
                        response.putAll(draftDataFromMySQL);

                        log.info("✅ [getActiveMatch-Redis] Draft data do MySQL: {} keys",
                                draftDataFromMySQL.keySet());

                    } else if ("in_progress".equalsIgnoreCase(match.getStatus())) {
                        response.put("type", "game");

                        // ✅ CRÍTICO: Buscar dados COMPLETOS do pick_ban_data (igual ao draft!)
                        // E EXTRAIR campeões das actions para colocar direto nos players

                        if (match.getPickBanDataJson() != null && !match.getPickBanDataJson().isEmpty()) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> pickBanData = objectMapper.readValue(match.getPickBanDataJson(),
                                        Map.class);

                                // ✅ CRÍTICO: Extrair campeões das actions e adicionar nos players
                                if (pickBanData.containsKey("teams") && pickBanData.get("teams") instanceof Map<?, ?>) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> teams = (Map<String, Object>) pickBanData.get("teams");

                                    // Processar Blue e Red teams
                                    for (String teamSide : new String[] { "blue", "red" }) {
                                        Object teamObj = teams.get(teamSide);
                                        if (teamObj instanceof Map<?, ?>) {
                                            @SuppressWarnings("unchecked")
                                            Map<String, Object> team = (Map<String, Object>) teamObj;

                                            if (team.containsKey("players") && team.get("players") instanceof List<?>) {
                                                @SuppressWarnings("unchecked")
                                                List<Map<String, Object>> players = (List<Map<String, Object>>) team
                                                        .get("players");

                                                for (Map<String, Object> player : players) {
                                                    // Extrair pick das actions do player
                                                    if (player.containsKey("actions")
                                                            && player.get("actions") instanceof List<?>) {
                                                        @SuppressWarnings("unchecked")
                                                        List<Map<String, Object>> playerActions = (List<Map<String, Object>>) player
                                                                .get("actions");

                                                        for (Map<String, Object> action : playerActions) {
                                                            String actionType = (String) action.get("type");
                                                            if ("pick".equals(actionType)) {
                                                                // ✅ ADICIONAR championId e championName DIRETAMENTE no
                                                                // player
                                                                player.put("championId", action.get("championId"));
                                                                player.put("championName", action.get("championName"));
                                                                break;
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // ✅ Adicionar TUDO do pick_ban_data (agora com campeões extraídos!)
                                response.putAll(pickBanData);

                                log.info(
                                        "✅ [getActiveMatch-Redis] Game data do MySQL: {} keys (com campeões extraídos)",
                                        pickBanData.keySet());

                            } catch (Exception e) {
                                log.error("❌ [getActiveMatch-Redis] Erro ao parsear pick_ban_data para game", e);
                                // Fallback: pelo menos adicionar o JSON raw
                                response.put("pickBanData", match.getPickBanDataJson());
                            }
                        } else {
                            log.warn("⚠️ [getActiveMatch-Redis] pick_ban_data vazio para match {}", match.getId());
                        }

                        // ✅ ADICIONAL: Dados do jogo em progresso
                        response.put("startTime", match.getCreatedAt());
                        response.put("gameId", match.getId().toString());

                    } else if ("match_found".equalsIgnoreCase(match.getStatus()) ||
                            "accepting".equalsIgnoreCase(match.getStatus()) ||
                            "accepted".equalsIgnoreCase(match.getStatus()) ||
                            "pending".equalsIgnoreCase(match.getStatus())) {
                        response.put("type", "match_found");

                        List<String> team1NamesMatched = parsePlayersFromJson(match.getTeam1PlayersJson());
                        List<String> team2NamesMatched = parsePlayersFromJson(match.getTeam2PlayersJson());

                        response.put("team1", team1NamesMatched);
                        response.put("team2", team2NamesMatched);
                    }

                    return response;
                } else {
                    // ❌ INCONSISTÊNCIA: Redis diz "tem partida", MySQL diz "não" ou "finalizada"
                    log.warn("🧹 [getActiveMatch] PARTIDA FANTASMA: Redis={}, MySQL={}",
                            redisMatchId,
                            redisMatch.map(m -> m.getStatus()).orElse("NÃO EXISTE"));

                    // ✅ SÓ LIMPAR SE MYSQL CONFIRMA QUE NÃO EXISTE OU ESTÁ FINALIZADA
                    if (redisMatch.isPresent()) {
                        String status = redisMatch.get().getStatus();
                        if ("completed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status)) {
                            redisPlayerMatch.clearPlayerMatch(summonerName);
                            log.info("✅ [getActiveMatch] Ownership limpo para {} (match finalizada/cancelada)",
                                    summonerName);
                        } else {
                            log.info("⏳ [getActiveMatch] Ownership NÃO limpo para {} (match ainda ativa no MySQL)",
                                    summonerName);
                        }
                    } else {
                        // Se não existe no MySQL, pode limpar
                        redisPlayerMatch.clearPlayerMatch(summonerName);
                        log.info("✅ [getActiveMatch] Ownership limpo para {} (match não existe no MySQL)",
                                summonerName);
                    }
                }
            }

            // ✅ BUSCA NORMAL: Buscar diretamente pelo summonerName no MySQL
            // Os campos team1_players/team2_players contêm summonerNames, não PUUIDs
            Optional<CustomMatch> activeMatchOpt = customMatchRepository
                    .findActiveMatchByPlayerPuuid(summonerName);

            if (activeMatchOpt.isEmpty()) {
                log.debug("✅ Nenhuma partida ativa encontrada para: {}", summonerName);

                // ✅ CLEANUP INTELIGENTE: Se PlayerState indica que jogador está em partida,
                // mas MySQL não encontrou nada, LIMPAR estado (ghost state)
                br.com.lolmatchmaking.backend.service.lock.PlayerState currentState = playerStateService
                        .getPlayerState(summonerName);

                if (currentState != null &&
                        currentState != br.com.lolmatchmaking.backend.service.lock.PlayerState.AVAILABLE &&
                        currentState != br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_QUEUE) {

                    log.warn("🧹 [getActiveMatch] ESTADO FANTASMA: {} tem estado {} mas NÃO há partida ativa no MySQL!",
                            summonerName, currentState);
                    log.warn("🧹 [getActiveMatch] Limpando PlayerState e RedisPlayerMatch...");

                    // Limpar PlayerState
                    playerStateService.forceSetPlayerState(summonerName,
                            br.com.lolmatchmaking.backend.service.lock.PlayerState.AVAILABLE);

                    // Limpar RedisPlayerMatch ownership
                    redisPlayerMatch.clearPlayerMatch(summonerName);

                    log.info("✅ [getActiveMatch] Estado fantasma limpo para {}", summonerName);
                }

                return Collections.emptyMap();
            }

            CustomMatch match = activeMatchOpt.get();

            log.info("✅ Partida ativa encontrada - ID: {}, Status: {}, Title: {}",
                    match.getId(), match.getStatus(), match.getTitle());

            // ✅ CRÍTICO: REGISTRAR OWNERSHIP no Redis se ainda não estiver registrado!
            // Quando jogador retorna via my-active-match, precisa estar registrado para
            // fazer ações
            Long currentOwnership = redisPlayerMatch.getCurrentMatch(summonerName);
            if (currentOwnership == null || !currentOwnership.equals(match.getId())) {
                log.warn(
                        "🧹 [getActiveMatch-MySQL] Jogador {} retornando mas ownership não registrado! Registrando agora...",
                        summonerName);
                redisPlayerMatch.registerPlayerMatch(summonerName, match.getId());
                log.info("✅ [getActiveMatch-MySQL] Ownership registrado: {} → match {}", summonerName, match.getId());
            }

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

                // ✅ CRÍTICO: RENOVAR ownership ao retornar para draft
                redisPlayerMatch.registerPlayerMatch(summonerName, match.getId());
                log.info("🔄 [getActiveMatch-MySQL] Ownership renovado: {} → draft {}",
                        summonerName, match.getId());

                // ✅ CRÍTICO: Usar DraftFlowService.getDraftDataForRestore()
                // SEMPRE busca MySQL (fonte da verdade), retorna JSON completo
                Map<String, Object> draftData = draftFlowService.getDraftDataForRestore(match.getId());

                // ✅ Adicionar TODOS os dados do draft na response
                // pickBanData já vem no formato EXATO do MySQL (teams.blue/red, allBans,
                // allPicks)
                response.putAll(draftData);

                log.info("✅ [getActiveMatch-MySQL] Draft data do MySQL: {} keys",
                        draftData.keySet());

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
            } else if ("match_found".equalsIgnoreCase(match.getStatus()) ||
                    "accepting".equalsIgnoreCase(match.getStatus()) ||
                    "accepted".equalsIgnoreCase(match.getStatus()) ||
                    "pending".equalsIgnoreCase(match.getStatus())) {
                // ✅ MATCH FOUND: Partida aguardando aceitação ou já aceita
                response.put("type", "match_found");

                // Extrair jogadores dos times
                List<String> team1Players = parsePlayersFromJson(match.getTeam1PlayersJson());
                List<String> team2Players = parsePlayersFromJson(match.getTeam2PlayersJson());

                response.put("team1", team1Players);
                response.put("team2", team2Players);

                log.info("✅ Retornando match found (status={}) - Team1: {} jogadores, Team2: {} jogadores",
                        match.getStatus(), team1Players.size(), team2Players.size());
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

    /**
     * ✅ CORREÇÃO: REMOVIDO - Chaves queue_session_info desnecessárias
     * ✅ SISTEMA CENTRALIZADO: Usar apenas ws:client_info:{sessionId} e
     * ws:session:{sessionId}
     * 
     * As informações de sessão já são armazenadas pelas chaves centralizadas:
     * - ws:client_info:{sessionId} → ClientInfo completo (sessionId, summonerName,
     * puuid, etc.)
     * - ws:session:{sessionId} → summonerName (lookup por sessionId)
     * - ws:player:{summonerName} → sessionId (lookup reverso)
     * 
     * Não precisamos de chaves duplicadas!
     */
    private void storeSessionInfoInRedis(String summonerName, String sessionId, String puuid,
            Map<String, Object> lcuData) {
        // ✅ CORREÇÃO: Não criar chaves duplicadas - usar sistema centralizado
        log.debug("ℹ️ [Player-Sessions] [QUEUE] Sessão {} já registrada no sistema centralizado (ws:client_info:{})",
                summonerName, sessionId);
    }

    /**
     * ✅ CORREÇÃO: REMOVIDO - Não precisamos limpar chaves queue_session_info
     * ✅ SISTEMA CENTRALIZADO: Limpeza é feita automaticamente pelo
     * RedisWebSocketSessionService
     */
    private void clearSessionInfoFromRedis(String summonerName) {
        // ✅ CORREÇÃO: Não precisamos limpar chaves duplicadas - sistema centralizado
        // cuida disso
        log.debug("ℹ️ [Player-Sessions] [QUEUE] Limpeza de sessão {} delegada ao sistema centralizado",
                summonerName);
    }
}
