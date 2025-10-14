package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.entity.QueuePlayer;
import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import br.com.lolmatchmaking.backend.domain.repository.QueuePlayerRepository;
import br.com.lolmatchmaking.backend.dto.QueuePlayerInfoDTO;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MatchFoundService {

    private final QueuePlayerRepository queuePlayerRepository;
    private final CustomMatchRepository customMatchRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final QueueManagementService queueManagementService;
    private final DraftFlowService draftFlowService;
    private final DiscordService discordService;

    // ✅ NOVO: Redis para aceitação distribuída
    private final RedisMatchAcceptanceService redisAcceptance;

    // ✅ NOVO: Redis para mapear jogadores → partidas ativas (OWNERSHIP)
    private final br.com.lolmatchmaking.backend.service.redis.RedisPlayerMatchService redisPlayerMatch;

    // ✅ NOVO: Services de Lock e Broadcasting
    private final br.com.lolmatchmaking.backend.service.lock.PlayerStateService playerStateService;
    private final br.com.lolmatchmaking.backend.service.lock.MatchOperationsLockService matchOpsLockService;
    private final br.com.lolmatchmaking.backend.service.lock.AcceptanceStatusLockService acceptanceStatusLockService;
    private final EventBroadcastService eventBroadcastService;

    // ✅ NOVO: RedisTemplate para throttling de retries
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    // ✅ NOVO: SessionRegistry para verificar conectividade
    private final br.com.lolmatchmaking.backend.websocket.SessionRegistry sessionRegistry;

    // Constructor manual para @Lazy
    public MatchFoundService(
            QueuePlayerRepository queuePlayerRepository,
            CustomMatchRepository customMatchRepository,
            MatchmakingWebSocketService webSocketService,
            @Lazy QueueManagementService queueManagementService,
            DraftFlowService draftFlowService,
            DiscordService discordService,
            RedisMatchAcceptanceService redisAcceptance,
            br.com.lolmatchmaking.backend.service.redis.RedisPlayerMatchService redisPlayerMatch,
            br.com.lolmatchmaking.backend.service.lock.PlayerStateService playerStateService,
            br.com.lolmatchmaking.backend.service.lock.MatchOperationsLockService matchOpsLockService,
            br.com.lolmatchmaking.backend.service.lock.AcceptanceStatusLockService acceptanceStatusLockService,
            EventBroadcastService eventBroadcastService,
            org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate,
            br.com.lolmatchmaking.backend.websocket.SessionRegistry sessionRegistry) {
        this.queuePlayerRepository = queuePlayerRepository;
        this.customMatchRepository = customMatchRepository;
        this.webSocketService = webSocketService;
        this.queueManagementService = queueManagementService;
        this.draftFlowService = draftFlowService;
        this.discordService = discordService;
        this.redisAcceptance = redisAcceptance;
        this.redisPlayerMatch = redisPlayerMatch;
        this.playerStateService = playerStateService;
        this.matchOpsLockService = matchOpsLockService;
        this.acceptanceStatusLockService = acceptanceStatusLockService;
        this.eventBroadcastService = eventBroadcastService;
        this.redisTemplate = redisTemplate;
        this.sessionRegistry = sessionRegistry;
    }

    // ✅ REMOVIDO: HashMap local removido
    // MySQL = Fonte da verdade (persistent storage)
    // Redis = Cache/estado volátil (performance, locks, aceitações temporárias)
    // SEMPRE validar contra MySQL antes de decisões críticas

    // Configurações
    private static final int ACCEPTANCE_TIMEOUT_SECONDS = 30;
    private static final int BOT_AUTO_ACCEPT_DELAY_MS = 2000; // Bots aceitam após 2 segundos

    /**
     * Cria uma partida para aceitação
     */
    @Transactional
    public void createMatchForAcceptance(CustomMatch match, List<QueuePlayer> team1, List<QueuePlayer> team2) {
        try {
            log.info("🎯 [MatchFound] Criando partida para aceitação: ID {}", match.getId());

            // Zerar status de aceitação de todos os jogadores
            List<QueuePlayer> allPlayers = new ArrayList<>();
            allPlayers.addAll(team1);
            allPlayers.addAll(team2);

            // ✅ CORREÇÃO: Atualizar apenas o status, sem fazer save (jogadores já estão na
            // sessão JPA)
            for (QueuePlayer player : allPlayers) {
                player.setAcceptanceStatus(-1); // -1 = aguardando aceitação (para não ser selecionado novamente)
                // NÃO fazer save aqui - os jogadores já estão gerenciados pelo contexto JPA
                // O flush acontecerá automaticamente no final da transação
            }

            // ✅ NOVO: Criar tracking no Redis (fonte da verdade)
            List<String> playerNames = allPlayers.stream()
                    .map(QueuePlayer::getSummonerName)
                    .toList();

            List<String> team1Names = team1.stream()
                    .map(QueuePlayer::getSummonerName)
                    .toList();

            List<String> team2Names = team2.stream()
                    .map(QueuePlayer::getSummonerName)
                    .toList();

            redisAcceptance.createPendingMatch(match.getId(), playerNames, team1Names, team2Names);
            log.info("✅ [MatchFound] Partida {} criada no Redis para aceitação (team1: {}, team2: {})",
                    match.getId(), team1Names.size(), team2Names.size());

            // ✅ REDIS: Cache de aceitações para performance
            // MySQL permanece como fonte da verdade para partidas/jogadores

            // ✅ VALIDAÇÃO: Verificar se dados foram salvos corretamente no Redis
            List<String> redisAllPlayers = redisAcceptance.getAllPlayers(match.getId());
            List<String> redisTeam1 = redisAcceptance.getTeam1Players(match.getId());
            List<String> redisTeam2 = redisAcceptance.getTeam2Players(match.getId());

            if (redisAllPlayers.size() != 10 || redisTeam1.size() != 5 || redisTeam2.size() != 5) {
                log.error("❌ [CRÍTICO] DADOS INCORRETOS NO REDIS!");
                log.error("  - AllPlayers: {} (esperado: 10)", redisAllPlayers.size());
                log.error("  - Team1: {} (esperado: 5)", redisTeam1.size());
                log.error("  - Team2: {} (esperado: 5)", redisTeam2.size());
            } else {
                log.info("✅ [VALIDAÇÃO REDIS] Dados salvos corretamente:");
                log.info("  ✅ AllPlayers: 10 | Team1: 5 | Team2: 5");
            }

            // ✅ NOVO: Registrar players → matchId (OWNERSHIP)
            log.info("📝 [OWNERSHIP] Registrando {} jogadores na partida {}", allPlayers.size(), match.getId());
            for (QueuePlayer player : allPlayers) {
                redisPlayerMatch.registerPlayerMatch(player.getSummonerName(), match.getId());
            }
            log.info("✅ [OWNERSHIP] Todos os jogadores registrados com sucesso");

            // Notificar match found
            notifyMatchFound(match, team1, team2);

            // Auto-aceitar bots após delay
            autoAcceptBots(match.getId(), allPlayers);

            log.info("✅ [MatchFound] Partida {} criada para aceitação (timeout: {}s)",
                    match.getId(), ACCEPTANCE_TIMEOUT_SECONDS);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao criar partida para aceitação", e);
        }
    }

    /**
     * Jogador aceita a partida
     */
    @Transactional
    public void acceptMatch(Long matchId, String summonerName) {
        try {
            // ✅ NOVO: VERIFICAR SE JOGADOR ESTÁ EM IN_MATCH_FOUND
            br.com.lolmatchmaking.backend.service.lock.PlayerState state = playerStateService
                    .getPlayerState(summonerName);
            if (state != br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_MATCH_FOUND) {
                // ✅ CORREÇÃO CRÍTICA: CLEANUP INTELIGENTE baseado no MySQL
                // Se estado não é IN_MATCH_FOUND, verificar se player realmente está na partida
                if (state == br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_QUEUE ||
                        state == br.com.lolmatchmaking.backend.service.lock.PlayerState.AVAILABLE) {
                    // Verificar no MySQL (fonte da verdade)
                    boolean reallyInMatch = customMatchRepository.findById(matchId)
                            .map(match -> {
                                String status = match.getStatus();
                                if (!"match_found".equalsIgnoreCase(status) &&
                                        !"accepting".equalsIgnoreCase(status)) {
                                    return false; // Partida não está mais aceitando
                                }

                                // ✅ CORREÇÃO: Verificar com CASE-INSENSITIVE
                                String team1 = match.getTeam1PlayersJson();
                                String team2 = match.getTeam2PlayersJson();
                                boolean inTeam1 = team1 != null
                                        && team1.toLowerCase().contains(summonerName.toLowerCase());
                                boolean inTeam2 = team2 != null
                                        && team2.toLowerCase().contains(summonerName.toLowerCase());
                                return inTeam1 || inTeam2;
                            })
                            .orElse(false);

                    if (reallyInMatch) {
                        log.warn("🧹 [acceptMatch] ESTADO INCONSISTENTE: {} está na partida no MySQL mas estado é {}",
                                summonerName, state);
                        log.warn("🧹 [acceptMatch] Corrigindo PlayerState para IN_MATCH_FOUND...");

                        // Corrigir estado (FORCE pois pode estar em IN_QUEUE ou AVAILABLE)
                        playerStateService.forceSetPlayerState(summonerName,
                                br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_MATCH_FOUND);

                        log.info("✅ [acceptMatch] PlayerState corrigido, permitindo aceitação agora");
                        // Continuar o fluxo normalmente

                    } else {
                        log.warn("❌ [acceptMatch] Jogador {} NÃO está na partida {} (estado: {})",
                                summonerName, matchId, state);
                        return;
                    }
                } else {
                    log.warn("⚠️ [MatchFound] Jogador {} não está em match_found (estado: {})",
                            summonerName, state);
                    return;
                }
            }

            log.info("✅ [MatchFound] Jogador {} aceitou partida {}", summonerName, matchId);

            // ✅ NOVO: Aceitar no Redis primeiro (fonte da verdade, com distributed lock)
            boolean redisSuccess = redisAcceptance.acceptMatch(matchId, summonerName);

            if (!redisSuccess) {
                log.warn("⚠️ [MatchFound] Falha ao aceitar match {} no Redis para {}", matchId, summonerName);
                return;
            }

            // ✅ NOVO: Atualizar no banco COM LOCK
            acceptanceStatusLockService.updateWithLock(summonerName, () -> {
                queuePlayerRepository.findBySummonerName(summonerName).ifPresent(player -> {
                    player.setAcceptanceStatus(1); // 1 = accepted
                    queuePlayerRepository.save(player);
                });
            });

            // ✅ REDIS ONLY: Buscar dados do Redis para notificação
            Set<String> acceptedPlayers = redisAcceptance.getAcceptedPlayers(matchId);
            List<String> allPlayers = redisAcceptance.getAllPlayers(matchId);

            if (acceptedPlayers != null && allPlayers != null) {
                log.info("✅ [MatchFound] Match {} - {}/{} jogadores aceitaram (Redis)",
                        matchId, acceptedPlayers.size(), allPlayers.size());

                // ✅ NOVO: PUBLICAR PROGRESSO DE ACEITAÇÃO (Redis Pub/Sub)
                eventBroadcastService.publishMatchAcceptance(
                        matchId, summonerName, acceptedPlayers.size(), allPlayers.size());

                // Notificar progresso (backward compatibility)
                notifyAcceptanceProgress(matchId, acceptedPlayers, allPlayers);
            }

            // ✅ NOVO: Verificar no Redis se todos aceitaram (fonte da verdade)
            if (redisAcceptance.checkAllAccepted(matchId)) {
                log.info("🎉 [MatchFound] TODOS OS JOGADORES ACEITARAM! Match {}", matchId);
                handleAllPlayersAccepted(matchId);
            }

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao aceitar partida", e);
        } finally {
            // ✅ SEMPRE LIBERAR LOCK
            matchOpsLockService.releaseAcceptanceLock(matchId, summonerName);
        }
    }

    /**
     * Jogador recusa a partida
     */
    @Transactional
    public void declineMatch(Long matchId, String summonerName) {
        // ✅ NOVO: ADQUIRIR LOCK DE ACEITAÇÃO INDIVIDUAL (previne múltiplas recusas)
        if (!matchOpsLockService.acquireAcceptanceLock(matchId, summonerName)) {
            log.warn("⚠️ [MatchFound] Jogador {} já está processando recusa de match {}",
                    summonerName, matchId);
            return;
        }

        try {
            // ✅ VALIDAÇÃO: Verificar se jogador REALMENTE está na partida (MySQL = fonte da
            // verdade)
            boolean reallyInMatch = customMatchRepository.findById(matchId)
                    .map(match -> {
                        String status = match.getStatus();
                        if (!"match_found".equalsIgnoreCase(status) &&
                                !"accepting".equalsIgnoreCase(status)) {
                            log.warn("⚠️ [declineMatch] Partida {} não está aceitando (status={})", matchId, status);
                            return false;
                        }

                        // ✅ CORREÇÃO: Verificar com CASE-INSENSITIVE
                        String team1 = match.getTeam1PlayersJson();
                        String team2 = match.getTeam2PlayersJson();
                        boolean inTeam1 = team1 != null
                                && team1.toLowerCase().contains(summonerName.toLowerCase());
                        boolean inTeam2 = team2 != null
                                && team2.toLowerCase().contains(summonerName.toLowerCase());
                        return inTeam1 || inTeam2;
                    })
                    .orElse(false);

            if (!reallyInMatch) {
                log.warn("❌ [declineMatch] Jogador {} NÃO está na partida {} ou partida não existe/não está aceitando",
                        summonerName, matchId);
                return;
            }

            log.warn("❌ [MatchFound] Jogador {} recusou partida {}", summonerName, matchId);

            // ✅ NOVO: Recusar no Redis primeiro (fonte da verdade, com distributed lock)
            boolean redisSuccess = redisAcceptance.declineMatch(matchId, summonerName);

            if (!redisSuccess) {
                log.warn("⚠️ [MatchFound] Falha ao recusar match {} no Redis para {}", matchId, summonerName);
                return;
            }

            // ✅ NOVO: Atualizar no banco COM LOCK
            acceptanceStatusLockService.updateWithLock(summonerName, () -> {
                queuePlayerRepository.findBySummonerName(summonerName).ifPresent(player -> {
                    player.setAcceptanceStatus(2); // 2 = declined
                    queuePlayerRepository.save(player);
                });
            });

            // ✅ NOVO: ATUALIZAR ESTADO PARA AVAILABLE (jogador volta para fila ou sai)
            playerStateService.setPlayerState(summonerName,
                    br.com.lolmatchmaking.backend.service.lock.PlayerState.AVAILABLE);

            // Cancelar partida
            handleMatchDeclined(matchId, summonerName);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao recusar partida", e);
        } finally {
            // ✅ SEMPRE LIBERAR LOCK
            matchOpsLockService.releaseAcceptanceLock(matchId, summonerName);
        }
    }

    /**
     * Todos os jogadores aceitaram
     */
    @Transactional
    private void handleAllPlayersAccepted(Long matchId) {
        // ✅ NOVO: ADQUIRIR LOCK DE PROCESSAMENTO
        // Previne múltiplas instâncias processando "todos aceitaram" simultaneamente
        if (!matchOpsLockService.acquireAllAcceptedProcessingLock(matchId)) {
            log.warn("⚠️ [MatchFound] Aceitação completa de match {} já está sendo processada", matchId);
            return;
        }

        try {
            // ✅ REDIS ONLY: Buscar dados do Redis (fonte da verdade)
            List<String> team1Names = redisAcceptance.getTeam1Players(matchId);
            List<String> team2Names = redisAcceptance.getTeam2Players(matchId);

            if (team1Names == null || team2Names == null || team1Names.isEmpty() || team2Names.isEmpty()) {
                log.error("❌ [MatchFound] Dados de times vazios no Redis para match {}", matchId);
                return;
            }

            // Atualizar status da partida
            customMatchRepository.findById(matchId).ifPresent(match -> {
                match.setStatus("accepted");
                customMatchRepository.save(match);
            });

            // ✅ CRÍTICO: Salvar dados completos no pick_ban_data ANTES de remover da fila!
            log.info("🎯 [MatchFound] Salvando dados completos no pick_ban_data antes de remover da fila...");

            // ✅ CRÍTICO: Buscar jogadores MANTENDO A ORDEM EXATA do matchmaking!
            // O matchmaking JÁ definiu a ordem correta (Top, Jungle, Mid, Bot, Support)
            // NÃO devemos reordenar aqui, pois isso bagunça a formação!

            // Buscar jogadores NA ORDEM EXATA dos nomes (direto do banco, sem cache local)
            List<QueuePlayer> team1Players = new ArrayList<>();
            for (String playerName : team1Names) {
                Optional<QueuePlayer> playerOpt = queuePlayerRepository.findBySummonerName(playerName);
                if (playerOpt.isPresent()) {
                    team1Players.add(playerOpt.get());
                } else {
                    log.warn("⚠️ [MatchFound] Jogador {} não encontrado no banco!", playerName);
                }
            }

            List<QueuePlayer> team2Players = new ArrayList<>();
            for (String playerName : team2Names) {
                Optional<QueuePlayer> playerOpt = queuePlayerRepository.findBySummonerName(playerName);
                if (playerOpt.isPresent()) {
                    team2Players.add(playerOpt.get());
                } else {
                    log.warn("⚠️ [MatchFound] Jogador {} não encontrado no banco!", playerName);
                }
            }

            log.info("✅ [MatchFound] Jogadores recuperados (MySQL): team1={}, team2={}",
                    team1Players.size(), team2Players.size());

            // ✅ LOG: Verificar ordem preservada do matchmaking
            log.info("🔍 [MatchFound] Team1 order check:");
            for (int i = 0; i < team1Players.size(); i++) {
                log.info("  [{}] {} (expected: {})", i, team1Players.get(i).getSummonerName(), team1Names.get(i));
            }

            // ✅ CRÍTICO: MANTER ordem exata do matchmaking!
            // As lanes JÁ foram atribuídas corretamente pelo matchmaking
            String[] lanes = { "top", "jungle", "mid", "bot", "support" };

            // Converter para DTOs com lanes e posições (SEM REORDENAR!)
            List<QueuePlayerInfoDTO> team1DTOs = new ArrayList<>();
            for (int i = 0; i < team1Players.size(); i++) {
                team1DTOs.add(convertToDTO(team1Players.get(i), lanes[i], i, false));
            }

            List<QueuePlayerInfoDTO> team2DTOs = new ArrayList<>();
            for (int i = 0; i < team2Players.size(); i++) {
                team2DTOs.add(convertToDTO(team2Players.get(i), lanes[i], i + 5, false));
            }

            // ✅ Salvar dados completos dos times no pick_ban_data AGORA (SÍNCRONO!)
            saveTeamsDataToPickBan(matchId, team1DTOs, team2DTOs);

            // ✅ CRÍTICO: Aguardar flush do JPA para garantir que dados foram persistidos
            customMatchRepository.flush();
            log.info("✅ [MatchFound] pick_ban_data FLUSHED para o MySQL - seguro iniciar draft");

            // ✅ NOVO: ATUALIZAR ESTADO DE TODOS PARA IN_DRAFT
            List<String> allPlayers = redisAcceptance.getAllPlayers(matchId);
            for (String playerName : allPlayers) {
                playerStateService.setPlayerState(playerName,
                        br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_DRAFT);
                log.info("✅ [Estado] Jogador {} → IN_DRAFT", playerName);
            }

            // ✅ Remover todos os jogadores da fila (APENAS MySQL, SEM atualizar
            // PlayerState!)
            for (String playerName : allPlayers) {
                // ✅ CRÍTICO: Deletar APENAS do MySQL, sem chamar removeFromQueue()
                // removeFromQueue() faria setPlayerState(AVAILABLE) e sobrescreveria IN_DRAFT!
                queuePlayerRepository.findBySummonerName(playerName).ifPresent(player -> {
                    queuePlayerRepository.delete(player);
                    log.info("🗑️ [MatchFound] Jogador {} removido da fila MySQL (PlayerState permanece IN_DRAFT)",
                            playerName);
                });
            }

            // ✅ NOVO: PUBLICAR draft_started VIA REDIS PUB/SUB
            eventBroadcastService.publishDraftStarted(matchId, allPlayers);

            // Notificar todos os jogadores
            notifyAllPlayersAccepted(matchId);

            // ✅ CORREÇÃO: Iniciar draft SÍNCRONAMENTE (não async!)
            // O pick_ban_data JÁ foi salvo e flushed, seguro executar
            startDraft(matchId);

            // ✅ REDIS ONLY: Limpar dados do Redis após processar
            redisAcceptance.clearMatch(matchId);

            log.info("✅ [MatchFound] Partida {} aceita por todos - iniciando draft IMEDIATAMENTE", matchId);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao processar aceitação completa", e);
        } finally {
            // ✅ SEMPRE LIBERAR LOCK
            matchOpsLockService.releaseAllAcceptedProcessingLock(matchId);
        }
    }

    /**
     * Partida recusada
     */
    @Transactional
    private void handleMatchDeclined(Long matchId, String declinedPlayer) {
        // ✅ NOVO: ADQUIRIR LOCK DE CANCELAMENTO
        if (!matchOpsLockService.acquireMatchCancelLock(matchId)) {
            log.warn("⚠️ [MatchFound] Cancelamento de match {} já está sendo processado", matchId);
            return;
        }

        try {
            // ✅ REDIS ONLY: Buscar jogadores do Redis
            List<String> allPlayers = redisAcceptance.getAllPlayers(matchId);
            if (allPlayers == null || allPlayers.isEmpty()) {
                log.warn("⚠️ [MatchFound] Sem dados no Redis para match {}", matchId);
                return;
            }

            // ✅ CORREÇÃO CRÍTICA: DELETAR partida do banco (não apenas mudar status)
            log.info("🗑️ [MatchFound] DELETANDO partida {} do banco de dados (recusada por {})", matchId,
                    declinedPlayer);
            customMatchRepository.deleteById(matchId);
            log.info("✅ [MatchFound] Partida {} EXCLUÍDA do banco de dados", matchId);

            // ✅ Remover APENAS jogador que recusou da fila
            queueManagementService.removeFromQueue(declinedPlayer);
            log.info("🗑️ [MatchFound] Jogador {} REMOVIDO da fila por recusar", declinedPlayer);

            // ✅ NOVO: Atualizar PlayerState para AVAILABLE para jogador que recusou
            try {
                playerStateService.setPlayerState(declinedPlayer,
                        br.com.lolmatchmaking.backend.service.lock.PlayerState.AVAILABLE);
                log.info("✅ [MatchFound] Estado de {} atualizado para AVAILABLE", declinedPlayer);
            } catch (Exception e) {
                log.error("❌ [MatchFound] Erro ao atualizar estado de {}: {}", declinedPlayer, e.getMessage());
            }

            // ✅ Resetar status de aceitação dos OUTROS jogadores (voltam ao normal na fila)
            for (String playerName : allPlayers) {
                if (!playerName.equals(declinedPlayer)) {
                    queuePlayerRepository.findBySummonerName(playerName).ifPresent(player -> {
                        player.setAcceptanceStatus(0); // Resetar status para voltar à fila normal
                        queuePlayerRepository.save(player);
                        log.info("🔄 [MatchFound] Jogador {} voltou ao estado normal na fila", playerName);
                    });

                    // ✅ NOVO: Atualizar PlayerState para IN_QUEUE para outros jogadores
                    try {
                        playerStateService.setPlayerState(playerName,
                                br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_QUEUE);
                        log.info("✅ [MatchFound] Estado de {} atualizado para IN_QUEUE", playerName);
                    } catch (Exception e) {
                        log.error("❌ [MatchFound] Erro ao atualizar estado de {}: {}", playerName, e.getMessage());
                    }
                }
            }

            // ✅ NOVO: Limpar Redis OWNERSHIP (player → match)
            log.info("🧹 [MatchFound] Limpando ownership de {} jogadores", allPlayers.size());
            for (String playerName : allPlayers) {
                try {
                    redisPlayerMatch.clearPlayerMatch(playerName);
                    log.info("✅ [MatchFound] Ownership de {} limpo", playerName);
                } catch (Exception e) {
                    log.error("❌ [MatchFound] Erro ao limpar ownership de {}: {}", playerName, e.getMessage());
                }
            }

            // Notificar cancelamento
            notifyMatchCancelled(matchId, declinedPlayer);

            // ✅ REDIS ONLY: Limpar dados do Redis (acceptance tracking)
            redisAcceptance.clearMatch(matchId);
            log.info("🧹 [MatchFound] Dados de aceitação limpos do Redis para match {}", matchId);

            log.info("✅ [MatchFound] Partida {} COMPLETAMENTE CANCELADA E EXCLUÍDA - {} recusou", matchId,
                    declinedPlayer);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao processar recusa", e);
        } finally {
            // ✅ SEMPRE LIBERAR LOCK
            matchOpsLockService.releaseMatchCancelLock(matchId);
        }
    }

    /**
     * Timeout de aceitação
     */
    @Scheduled(fixedRate = 1000) // Verifica a cada 1 segundo
    public void checkAcceptanceTimeouts() {
        try {
            // ✅ REDIS ONLY: Buscar matches pendentes do Redis
            List<Long> pendingMatchIds = redisAcceptance.getPendingMatches();

            if (pendingMatchIds.isEmpty()) {
                return;
            }

            log.debug("🔍 [MatchFound] Monitorando {} matches em aceitação", pendingMatchIds.size());

            Instant now = Instant.now();

            for (Long matchId : pendingMatchIds) {
                Instant createdAt = redisAcceptance.getMatchCreationTime(matchId);
                if (createdAt == null)
                    continue;

                long secondsElapsed = ChronoUnit.SECONDS.between(createdAt, now);

                if (secondsElapsed >= ACCEPTANCE_TIMEOUT_SECONDS) {
                    log.warn("⏰ [MatchFound] Timeout na partida {}", matchId);
                    handleAcceptanceTimeout(matchId);
                } else {
                    // ✅ CRÍTICO: Reenviar match_found para jogadores que não aceitaram
                    // Isso garante que TODOS vejam o modal, mesmo se houve falha de WebSocket
                    log.debug("🔄 [MatchFound] Match {} - {}s decorridos, verificando retry...",
                            matchId, secondsElapsed);
                    retryMatchFoundForPendingPlayers(matchId);

                    // Atualizar timer
                    int secondsRemaining = ACCEPTANCE_TIMEOUT_SECONDS - (int) secondsElapsed;
                    notifyTimerUpdate(matchId, secondsRemaining);
                }
            }

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao verificar timeouts", e);
        }
    }

    /**
     * Timeout de aceitação
     */
    @Transactional
    private void handleAcceptanceTimeout(Long matchId) {
        // ✅ NOVO: ADQUIRIR LOCK DE TIMEOUT PROCESSING
        if (!matchOpsLockService.acquireTimeoutProcessingLock(matchId)) {
            log.debug("⏭️ [MatchFound] Timeout de match {} já está sendo processado", matchId);
            return;
        }

        try {
            // ✅ REDIS ONLY: Buscar dados do Redis
            List<String> allPlayers = redisAcceptance.getAllPlayers(matchId);
            Set<String> acceptedPlayers = redisAcceptance.getAcceptedPlayers(matchId);

            if (allPlayers == null || allPlayers.isEmpty()) {
                return;
            }

            // Encontrar jogadores que não aceitaram
            List<String> notAcceptedPlayers = allPlayers.stream()
                    .filter(p -> acceptedPlayers == null || !acceptedPlayers.contains(p))
                    .collect(Collectors.toList());

            if (!notAcceptedPlayers.isEmpty()) {
                log.warn("⏰ [MatchFound] Timeout - jogadores que não aceitaram: {}", notAcceptedPlayers);
                handleMatchDeclined(matchId, notAcceptedPlayers.get(0));
            }

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao processar timeout", e);
        } finally {
            // ✅ SEMPRE LIBERAR LOCK
            matchOpsLockService.releaseTimeoutProcessingLock(matchId);
        }
    }

    /**
     * Auto-aceitar bots
     */
    private void autoAcceptBots(Long matchId, List<QueuePlayer> players) {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                for (QueuePlayer player : players) {
                    if (player.getSummonerName().startsWith("Bot")) {
                        log.info("🤖 [MatchFound] Auto-aceitando bot: {}", player.getSummonerName());
                        acceptMatch(matchId, player.getSummonerName());
                    }
                }
            }
        }, BOT_AUTO_ACCEPT_DELAY_MS);
    }

    /**
     * Inicia o draft
     * ✅ O pick_ban_data já está completo, apenas precisamos notificar e iniciar o
     * DraftFlowService
     */
    private void startDraft(Long matchId) {
        // ✅ NOVO: ADQUIRIR LOCK DE DRAFT START
        if (!matchOpsLockService.acquireDraftStartLock(matchId)) {
            log.warn("⚠️ [MatchFound] Draft de match {} já está sendo iniciado", matchId);
            return;
        }

        try {
            log.info("🎯 [MatchFound] Iniciando draft para partida {}", matchId);

            // Buscar dados completos da partida
            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match == null) {
                log.error("❌ [MatchFound] Partida {} não encontrada", matchId);
                return;
            }

            // Atualizar status para 'draft'
            match.setStatus("draft");
            customMatchRepository.save(match);
            log.info("✅ [MatchFound] Status da partida atualizado para 'draft'");

            // ✅ Ler dados completos do pick_ban_data que já foi salvo
            if (match.getPickBanDataJson() == null || match.getPickBanDataJson().isEmpty()) {
                log.error("❌ [MatchFound] pick_ban_data está vazio para partida {}", matchId);
                return;
            }

            try {
                ObjectMapper mapper = new ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> pickBanData = mapper.readValue(match.getPickBanDataJson(), Map.class);

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> team1Data = (List<Map<String, Object>>) pickBanData.get("team1");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> team2Data = (List<Map<String, Object>>) pickBanData.get("team2");

                if (team1Data == null || team2Data == null || team1Data.isEmpty() || team2Data.isEmpty()) {
                    log.error("❌ [MatchFound] Dados de times vazios no pick_ban_data para partida {}", matchId);
                    return;
                }

                log.info("✅ [MatchFound] Dados dos times carregados do pick_ban_data: team1={}, team2={}",
                        team1Data.size(), team2Data.size());

                // ✅ CRÍTICO: ORDENAR players por teamIndex ANTES de extrair nomes!
                // O pick_ban_data tem players com teamIndex, mas podem estar fora de ordem no
                // JSON
                team1Data.sort((a, b) -> {
                    Integer idxA = (Integer) a.getOrDefault("teamIndex", 999);
                    Integer idxB = (Integer) b.getOrDefault("teamIndex", 999);
                    return Integer.compare(idxA, idxB);
                });

                team2Data.sort((a, b) -> {
                    Integer idxA = (Integer) a.getOrDefault("teamIndex", 999);
                    Integer idxB = (Integer) b.getOrDefault("teamIndex", 999);
                    return Integer.compare(idxA, idxB);
                });

                // Extrair nomes dos jogadores para o DraftFlowService (JÁ ORDENADOS!)
                List<String> team1Names = team1Data.stream()
                        .map(p -> (String) p.get("summonerName"))
                        .collect(Collectors.toList());

                List<String> team2Names = team2Data.stream()
                        .map(p -> (String) p.get("summonerName"))
                        .collect(Collectors.toList());

                // ✅ LOG: Verificar ordem dos jogadores
                log.info("📋 [MatchFound] Team1 (ordenado por lane): {}", team1Names);
                log.info("📋 [MatchFound] Team2 (ordenado por lane): {}", team2Names);

                // ✅ CORREÇÃO CRÍTICA: Iniciar DraftFlowService PRIMEIRO para criar as 20 ações
                log.info("🎬 [MatchFound] Iniciando DraftFlowService para criar ações...");
                var draftState = draftFlowService.startDraft(matchId, team1Names, team2Names);
                log.info("✅ [MatchFound] DraftFlowService iniciado - {} ações criadas", draftState.getActions().size());

                // 🎮 INTEGRAÇÃO DISCORD: Criar canais e mover jogadores
                try {
                    log.info("🎮 [MatchFound] Criando canais Discord para match {}", matchId);
                    log.info("📋 [MatchFound] Blue Team ({}): {}", team1Names.size(), team1Names);
                    log.info("📋 [MatchFound] Red Team ({}): {}", team2Names.size(), team2Names);

                    DiscordService.DiscordMatch discordMatch = discordService.createMatchChannels(matchId, team1Names,
                            team2Names);

                    if (discordMatch != null) {
                        log.info("✅ [MatchFound] Canais Discord criados com sucesso");

                        // ✅ CORREÇÃO: Mover jogadores IMEDIATAMENTE (sem delay)
                        // Discord API é assíncrona, não há race condition
                        CompletableFuture.runAsync(() -> {
                            try {
                                log.info("🚚 [MatchFound] Movendo jogadores para canais de time...");
                                discordService.movePlayersToTeamChannels(matchId);
                                log.info("✅ [MatchFound] Jogadores movidos com sucesso");
                            } catch (Exception e) {
                                log.error("❌ [MatchFound] Erro ao mover jogadores: {}", e.getMessage());
                            }
                        });
                    } else {
                        log.warn("⚠️ [MatchFound] Falha ao criar canais Discord (retornou null)");
                    }
                } catch (Exception e) {
                    log.error("❌ [MatchFound] Erro na integração Discord: {}", e.getMessage(), e);
                }

                // ✅ Agora buscar as ações do DraftState recém-criado
                List<Map<String, Object>> actions = draftState.getActions().stream()
                        .map(action -> {
                            Map<String, Object> actionMap = new HashMap<>();
                            actionMap.put("index", action.index());
                            actionMap.put("type", action.type());
                            actionMap.put("team", action.team());
                            actionMap.put("championId", action.championId());
                            actionMap.put("byPlayer", action.byPlayer());
                            return actionMap;
                        })
                        .collect(Collectors.toList());

                Integer currentIndex = draftState.getCurrentIndex();

                // ✅ CRÍTICO: Calcular o jogador da vez inicial (ação 0)
                String currentPlayer = null;
                if (currentIndex < draftState.getActions().size()) {
                    var currentAction = draftState.getActions().get(currentIndex);
                    // O jogador da ação 0 é sempre do time 1, índice 0
                    if (currentAction.team() == 1 && !team1Names.isEmpty()) {
                        currentPlayer = team1Names.get(0);
                    } else if (currentAction.team() == 2 && !team2Names.isEmpty()) {
                        currentPlayer = team2Names.get(0);
                    }
                }

                // ✅ CRÍTICO: Buscar estrutura COMPLETA do MySQL (mesma que
                // getDraftDataForRestore)
                Map<String, Object> draftDataFromMySQL = draftFlowService.getDraftDataForRestore(matchId);

                // Notificar início do draft com dados completos (ESTRUTURA DO MySQL!)
                Map<String, Object> draftData = new HashMap<>(draftDataFromMySQL);
                draftData.put("matchId", matchId);
                draftData.put("id", matchId);
                draftData.put("timeRemaining", 30); // Timer inicial

                // ✅ COMPATIBILIDADE: Manter team1/team2 flat para frontend antigo
                draftData.put("team1", team1Data);
                draftData.put("team2", team2Data);
                draftData.put("averageMmrTeam1", match.getAverageMmrTeam1());
                draftData.put("averageMmrTeam2", match.getAverageMmrTeam2());

                // ✅ Log detalhado do que será enviado
                log.info("📢 [MatchFound] Enviando draft_starting via WebSocket (ESTRUTURA MySQL):");
                log.info("  - matchId: {}", matchId);
                log.info("  - teams.blue: {} jogadores", draftData.containsKey("teams") ? "SIM" : "NÃO");
                log.info("  - teams.red: {} jogadores", draftData.containsKey("teams") ? "SIM" : "NÃO");
                log.info("  - phases (flat): {} ações",
                        draftData.containsKey("phases") ? ((List<?>) draftData.get("phases")).size() : 0);
                log.info("  - currentIndex: {}", draftData.get("currentIndex"));
                log.info("  - currentPlayer: {}", draftData.get("currentPlayer"));
                log.info("  - timeRemaining: 30s (inicial)");

                // ✅ CRÍTICO: Enviar APENAS para os 10 jogadores da partida
                List<String> allPlayerNames = new ArrayList<>();
                allPlayerNames.addAll(team1Names);
                allPlayerNames.addAll(team2Names);

                log.info("🎯 [ENVIO INDIVIDUALIZADO] Enviando draft_starting APENAS para {} jogadores específicos:",
                        allPlayerNames.size());
                for (String playerName : allPlayerNames) {
                    log.info("  ✅ {}", playerName);
                }

                webSocketService.sendToPlayers("draft_starting", draftData, allPlayerNames);

                log.info(
                        "✅ [MatchFound] draft_starting enviado para {} jogadores (ESTRUTURA MySQL: teams.blue/red + phases flat)",
                        allPlayerNames.size());

            } catch (Exception e) {
                log.error("❌ [MatchFound] Erro ao parsear pick_ban_data", e);
            }

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao iniciar draft", e);
        } finally {
            // ✅ SEMPRE LIBERAR LOCK
            matchOpsLockService.releaseDraftStartLock(matchId);
        }
    }

    /**
     * Salva dados completos dos times no pick_ban_data para uso no draft
     * ✅ Formato idêntico ao backend antigo
     */
    private void saveTeamsDataToPickBan(Long matchId, List<QueuePlayerInfoDTO> team1, List<QueuePlayerInfoDTO> team2) {
        try {
            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match == null) {
                log.error("❌ [MatchFound] Partida {} não encontrada para salvar pick_ban_data", matchId);
                return;
            }

            // ✅ Criar estrutura EXATAMENTE como no backend antigo (team1/team2, não
            // team1Players/team2Players)
            Map<String, Object> pickBanData = new HashMap<>();

            // ✅ Construir objetos completos para team1
            List<Map<String, Object>> team1Objects = new ArrayList<>();
            for (QueuePlayerInfoDTO p : team1) {
                Map<String, Object> playerObj = new HashMap<>();
                playerObj.put("summonerName", p.getSummonerName());
                playerObj.put("assignedLane", p.getAssignedLane() != null ? p.getAssignedLane() : "fill");
                playerObj.put("teamIndex", p.getTeamIndex() != null ? p.getTeamIndex() : 0);
                playerObj.put("mmr", p.getCustomLp() != null ? p.getCustomLp() : 0);
                playerObj.put("primaryLane", p.getPrimaryLane() != null ? p.getPrimaryLane() : "fill");
                playerObj.put("secondaryLane", p.getSecondaryLane() != null ? p.getSecondaryLane() : "fill");
                playerObj.put("isAutofill", p.getIsAutofill() != null ? p.getIsAutofill() : false);
                playerObj.put("laneBadge", calculateLaneBadge(
                        p.getAssignedLane(),
                        p.getPrimaryLane(),
                        p.getSecondaryLane()));

                // ✅ Adicionar playerId se disponível (para jogadores reais, não bots)
                if (p.getPlayerId() != null) {
                    playerObj.put("playerId", p.getPlayerId());
                }

                team1Objects.add(playerObj);
            }

            // ✅ Construir objetos completos para team2
            List<Map<String, Object>> team2Objects = new ArrayList<>();
            for (QueuePlayerInfoDTO p : team2) {
                Map<String, Object> playerObj = new HashMap<>();
                playerObj.put("summonerName", p.getSummonerName());
                playerObj.put("assignedLane", p.getAssignedLane() != null ? p.getAssignedLane() : "fill");
                playerObj.put("teamIndex", p.getTeamIndex() != null ? p.getTeamIndex() : 0);
                playerObj.put("mmr", p.getCustomLp() != null ? p.getCustomLp() : 0);
                playerObj.put("primaryLane", p.getPrimaryLane() != null ? p.getPrimaryLane() : "fill");
                playerObj.put("secondaryLane", p.getSecondaryLane() != null ? p.getSecondaryLane() : "fill");
                playerObj.put("isAutofill", p.getIsAutofill() != null ? p.getIsAutofill() : false);
                playerObj.put("laneBadge", calculateLaneBadge(
                        p.getAssignedLane(),
                        p.getPrimaryLane(),
                        p.getSecondaryLane()));

                // ✅ Adicionar playerId se disponível (para jogadores reais, não bots)
                if (p.getPlayerId() != null) {
                    playerObj.put("playerId", p.getPlayerId());
                }

                team2Objects.add(playerObj);
            }

            // ✅ Salvar com nomes "team1" e "team2" (como no backend antigo)
            pickBanData.put("team1", team1Objects);
            pickBanData.put("team2", team2Objects);

            // Salvar no banco
            ObjectMapper mapper = new ObjectMapper();
            match.setPickBanDataJson(mapper.writeValueAsString(pickBanData));
            customMatchRepository.save(match);

            log.info("✅ [MatchFound] Dados dos times salvos em pick_ban_data para partida {} (team1: {}, team2: {})",
                    matchId, team1Objects.size(), team2Objects.size());

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao salvar pick_ban_data", e);
        }
    }

    /**
     * Helper para parsear nomes de jogadores do campo CSV
     */
    private List<String> parsePlayerNames(String playersCsv) {
        if (playersCsv == null || playersCsv.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(playersCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    // Métodos de notificação

    private void notifyMatchFound(CustomMatch match, List<QueuePlayer> team1, List<QueuePlayer> team2) {
        try {
            log.info("╔════════════════════════════════════════════════════════════════╗");
            log.info("║  📡 [NOTIFICAÇÃO] ENVIANDO MATCH_FOUND                        ║");
            log.info("╚════════════════════════════════════════════════════════════════╝");

            // ✅ VALIDAÇÃO CRÍTICA: Verificar times
            if (team1 == null || team2 == null || team1.size() != 5 || team2.size() != 5) {
                log.error("❌ [CRÍTICO] Times inválidos! team1={}, team2={}",
                        team1 != null ? team1.size() : "null",
                        team2 != null ? team2.size() : "null");
                return;
            }

            log.info("✅ [Validação] Team1: {} jogadores | Team2: {} jogadores", team1.size(), team2.size());

            // Mapeamento de lanes por posição (os jogadores já vêm ordenados por posição
            // do balanceamento)
            String[] lanes = { "top", "jungle", "mid", "bot", "support" };

            // ✅ CORREÇÃO: NÃO incluir "type", o broadcastToAll já adiciona
            Map<String, Object> data = new HashMap<>();
            data.put("matchId", match.getId());

            // ✅ Enriquecer jogadores com metadados de posição E determinar se é autofill
            List<QueuePlayerInfoDTO> team1DTOs = new ArrayList<>();
            for (int i = 0; i < team1.size(); i++) {
                QueuePlayer player = team1.get(i);
                String assignedLane = lanes[i];
                boolean isAutofill = determineIfAutofill(player, assignedLane);
                team1DTOs.add(convertToDTO(player, assignedLane, i, isAutofill));
            }

            List<QueuePlayerInfoDTO> team2DTOs = new ArrayList<>();
            for (int i = 0; i < team2.size(); i++) {
                QueuePlayer player = team2.get(i);
                String assignedLane = lanes[i];
                boolean isAutofill = determineIfAutofill(player, assignedLane);
                team2DTOs.add(convertToDTO(player, assignedLane, i + 5, isAutofill));
            }

            data.put("team1", team1DTOs);
            data.put("team2", team2DTOs);
            data.put("averageMmrTeam1", match.getAverageMmrTeam1());
            data.put("averageMmrTeam2", match.getAverageMmrTeam2());
            data.put("timeoutSeconds", ACCEPTANCE_TIMEOUT_SECONDS);

            // ✅ LOG DETALHADO: Mostrar EXATAMENTE quais jogadores vão receber a notificação
            log.info("📡 [Notificação] Enviando match_found para:");
            log.info("  🔵 TEAM 1 ({} jogadores):", team1.size());
            for (int i = 0; i < team1DTOs.size(); i++) {
                QueuePlayerInfoDTO dto = team1DTOs.get(i);
                log.info("    [{}] {} - {} - MMR: {}",
                        i, dto.getSummonerName(), dto.getAssignedLane(), dto.getMmr());
            }
            log.info("  🔴 TEAM 2 ({} jogadores):", team2.size());
            for (int i = 0; i < team2DTOs.size(); i++) {
                QueuePlayerInfoDTO dto = team2DTOs.get(i);
                log.info("    [{}] {} - {} - MMR: {}",
                        i, dto.getSummonerName(), dto.getAssignedLane(), dto.getMmr());
            }

            // ✅ CRÍTICO: Enviar APENAS para os 10 jogadores da partida
            List<String> allPlayerNames = new ArrayList<>();
            allPlayerNames.addAll(team1.stream().map(QueuePlayer::getSummonerName).toList());
            allPlayerNames.addAll(team2.stream().map(QueuePlayer::getSummonerName).toList());

            log.info("🎯 [ENVIO PARALELO] Preparando broadcast match_found para {} jogadores SIMULTANEAMENTE:",
                    allPlayerNames.size());
            for (String playerName : allPlayerNames) {
                log.info("  🎮 {}", playerName);
            }

            // ✅ NOVO: Log detalhado antes de chamar sendToPlayers
            log.info("🔍 [session-match-found] ===== MATCHFOUNDSERVICE.sendToPlayers CHAMADO =====");
            log.info("🔍 [session-match-found] MatchId: {}", match.getId());
            log.info("🔍 [session-match-found] Total de jogadores para enviar: {}", allPlayerNames.size());
            log.info("🔍 [session-match-found] Lista completa de summonerNames: {}", allPlayerNames);
            log.info("🔍 [session-match-found] =================================================");

            long startTime = System.currentTimeMillis();
            webSocketService.sendToPlayers("match_found", data, allPlayerNames);
            long elapsed = System.currentTimeMillis() - startTime;

            log.info("╔═══════════════════════════════════════════════════════════════════════╗");
            log.info("║  ✅ [BROADCAST PARALELO] MATCH_FOUND ENVIADO SIMULTANEAMENTE          ║");
            log.info("║  📊 {} jogadores receberam ao mesmo tempo em {}ms                     ║",
                    allPlayerNames.size(), elapsed);
            log.info("╚═══════════════════════════════════════════════════════════════════════╝");

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao notificar match found", e);
        }
    }

    /**
     * Determina se um jogador está em autofill ou pegou sua lane preferida
     */
    private boolean determineIfAutofill(QueuePlayer player, String assignedLane) {
        String primary = normalizeLane(player.getPrimaryLane());
        String secondary = normalizeLane(player.getSecondaryLane());
        String assigned = normalizeLane(assignedLane);

        // Se pegou lane primária ou secundária, não é autofill
        return !assigned.equals(primary) && !assigned.equals(secondary);
    }

    /**
     * Normaliza nomes de lanes para comparação
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

    private void notifyAcceptanceProgress(Long matchId, Set<String> acceptedPlayers, List<String> allPlayers) {
        try {
            // ✅ CORREÇÃO: Enviar apenas para os 10 jogadores da partida
            Map<String, Object> data = new HashMap<>();
            data.put("matchId", matchId);
            data.put("acceptedCount", acceptedPlayers.size());
            data.put("totalPlayers", allPlayers.size());
            data.put("acceptedPlayers", new ArrayList<>(acceptedPlayers));

            // Enviar apenas para os jogadores desta partida
            webSocketService.sendToPlayers("acceptance_progress", data, allPlayers);

            log.debug("📊 [MatchFound] Progresso enviado para {} jogadores da partida {} (Redis): {}/{}",
                    allPlayers.size(), matchId,
                    acceptedPlayers.size(), allPlayers.size());

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao notificar progresso", e);
        }
    }

    private void notifyAllPlayersAccepted(Long matchId) {
        try {
            // ✅ REDIS ONLY: Buscar jogadores do Redis
            List<String> allPlayers = redisAcceptance.getAllPlayers(matchId);
            if (allPlayers == null || allPlayers.isEmpty())
                return;

            // ✅ CORREÇÃO: Enviar apenas para os 10 jogadores da partida
            Map<String, Object> data = new HashMap<>();
            data.put("matchId", matchId);

            // Enviar apenas para os jogadores desta partida
            webSocketService.sendToPlayers("all_players_accepted", data, allPlayers);

            log.info(
                    "🎉 [MatchFound] Notificação de aceitação completa enviada para {} jogadores da partida {} (Redis)",
                    allPlayers.size(), matchId);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao notificar aceitação completa", e);
        }
    }

    private void notifyMatchCancelled(Long matchId, String declinedPlayer) {
        try {
            // ✅ REDIS ONLY: Buscar jogadores do Redis
            List<String> allPlayers = redisAcceptance.getAllPlayers(matchId);
            if (allPlayers == null || allPlayers.isEmpty())
                return;

            // ✅ CORREÇÃO: Enviar apenas para os 10 jogadores da partida
            Map<String, Object> data = new HashMap<>();
            data.put("matchId", matchId);
            data.put("reason", "declined");
            data.put("declinedPlayer", declinedPlayer);

            // Enviar apenas para os jogadores desta partida
            webSocketService.sendToPlayers("match_cancelled", data, allPlayers);

            log.warn("⚠️ [MatchFound] Cancelamento enviado para {} jogadores da partida {} (recusado por: {}) - Redis",
                    allPlayers.size(), matchId, declinedPlayer);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao notificar cancelamento", e);
        }
    }

    /**
     * ✅ NOVO: Reenviar match_found para jogadores que ainda não aceitaram
     * Garante que TODOS vejam o modal, mesmo se houve falha de WebSocket
     */
    private void retryMatchFoundForPendingPlayers(Long matchId) {
        try {
            // ✅ Buscar todos os jogadores da partida
            List<String> allPlayers = redisAcceptance.getAllPlayers(matchId);
            if (allPlayers == null || allPlayers.isEmpty()) {
                return;
            }

            // ✅ Buscar quem JÁ aceitou
            final Set<String> acceptedPlayers = redisAcceptance.getAcceptedPlayers(matchId) != null
                    ? redisAcceptance.getAcceptedPlayers(matchId)
                    : new HashSet<>();

            // ✅ Identificar jogadores PENDENTES (não aceitaram ainda)
            List<String> pendingPlayers = allPlayers.stream()
                    .filter(player -> !acceptedPlayers.contains(player))
                    .toList();

            if (pendingPlayers.isEmpty()) {
                log.debug("✅ [MatchFound-Retry] Match {} - todos aceitaram, sem retry necessário", matchId);
                return; // Todos já aceitaram
            }

            // ✅ LOG INFO: Importante para debug em produção!
            log.info("🔍 [MatchFound-Retry] Match {}: {}/{} jogadores pendentes",
                    matchId, pendingPlayers.size(), allPlayers.size());
            log.info("  📋 Pendentes: {}", pendingPlayers);
            log.info("  ✅ Aceitaram: {}", acceptedPlayers);

            // ✅ THROTTLE: Reenviar apenas a cada 5 segundos para não spammar
            String retryKey = "match_found_retry:" + matchId;
            Long lastRetrySec = redisTemplate.opsForValue()
                    .get(retryKey) != null ? (Long) redisTemplate.opsForValue().get(retryKey) : null;

            long nowSec = System.currentTimeMillis() / 1000;
            if (lastRetrySec != null && (nowSec - lastRetrySec) < 5) {
                return; // Reenviar apenas a cada 5s
            }

            // ✅ Marcar último retry
            redisTemplate.opsForValue().set(retryKey, nowSec,
                    java.time.Duration.ofSeconds(ACCEPTANCE_TIMEOUT_SECONDS + 10));

            // ✅ CRÍTICO: VALIDAR COM MYSQL ANTES DE RETRY
            // Previne loops infinitos de retry para matches fantasma
            Optional<CustomMatch> matchOpt = customMatchRepository.findById(matchId);

            if (matchOpt.isEmpty()) {
                log.warn("🧹 [CLEANUP] Match {} não existe no MySQL! Limpando Redis fantasma...", matchId);
                redisAcceptance.clearMatch(matchId);
                log.info("✅ [CLEANUP] Match fantasma {} removida do Redis", matchId);
                return; // ABORTAR retry
            }

            CustomMatch match = matchOpt.get();
            String status = match.getStatus();

            // Se já foi cancelada/completada no MySQL, limpar Redis
            if ("cancelled".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status) ||
                    "in_progress".equalsIgnoreCase(status) || "draft".equalsIgnoreCase(status)) {
                log.warn("🧹 [CLEANUP] Match {} já está em '{}' no MySQL! Limpando Redis de aceitação...",
                        matchId, status);
                redisAcceptance.clearMatch(matchId);
                log.info("✅ [CLEANUP] Match {} removida do Redis de aceitação", matchId);
                return; // ABORTAR retry
            }

            // ✅ Verificar ownership + acknowledgment de cada jogador pendente
            List<String> validPendingPlayers = new ArrayList<>();
            for (String player : pendingPlayers) {
                // ✅ OTIMIZAÇÃO: Verificar se jogador JÁ acknowledgou (já viu o modal)
                String ackKey = "match_found_ack:" + matchId + ":" + player.toLowerCase();
                Boolean hasAcked = (Boolean) redisTemplate.opsForValue().get(ackKey);
                if (Boolean.TRUE.equals(hasAcked)) {
                    log.debug("✅ [MatchFound] Jogador {} já acknowledged - pulando retry", player);
                    continue; // Pula este jogador
                }

                // ✅ CASE-INSENSITIVE ownership check
                boolean inTeam1 = match.getTeam1PlayersJson() != null &&
                        match.getTeam1PlayersJson().toLowerCase().contains(player.toLowerCase());
                boolean inTeam2 = match.getTeam2PlayersJson() != null &&
                        match.getTeam2PlayersJson().toLowerCase().contains(player.toLowerCase());

                if (inTeam1 || inTeam2) {
                    validPendingPlayers.add(player);
                } else {
                    log.warn("⚠️ [MatchFound] Jogador {} não pertence à partida {} (ownership fail)",
                            player, matchId);
                }
            }

            if (validPendingPlayers.isEmpty()) {
                log.info("⏭️ [MatchFound-Retry] Match {} - Todos os pendentes já acknowledged ou sem ownership",
                        matchId);
                return;
            }

            // ✅ Buscar dados completos da partida para reenviar
            List<String> team1Names = redisAcceptance.getTeam1Players(matchId);
            List<String> team2Names = redisAcceptance.getTeam2Players(matchId);

            // Montar payload igual ao original
            Map<String, Object> matchFoundData = buildMatchFoundPayload(
                    matchId, match, team1Names, team2Names);

            log.info("╔═══════════════════════════════════════════════════════════════════════╗");
            log.info("║  🔄 [RETRY] REENVIANDO MATCH_FOUND                                    ║");
            log.info("╚═══════════════════════════════════════════════════════════════════════╝");
            log.info("🔄 [MatchFound-Retry] Match {}: {} jogadores pendentes receberão match_found",
                    matchId, validPendingPlayers.size());
            for (String player : validPendingPlayers) {
                log.info("  📤 {}", player);
            }

            // ✅ BROADCAST PARALELO para jogadores pendentes
            webSocketService.sendToPlayers("match_found", matchFoundData, validPendingPlayers);

            log.info("✅ [MatchFound-Retry] ENVIADO para {} jogadores", validPendingPlayers.size());
            log.info("╚═══════════════════════════════════════════════════════════════════════╝");

        } catch (Exception e) {
            log.debug("❌ [MatchFound] Erro ao retry match_found", e);
        }
    }

    /**
     * ✅ NOVO: Constrói o payload completo do match_found para retry
     */
    private Map<String, Object> buildMatchFoundPayload(
            Long matchId,
            CustomMatch match,
            List<String> team1Names,
            List<String> team2Names) {

        Map<String, Object> data = new HashMap<>();
        data.put("matchId", matchId);
        data.put("averageMmrTeam1", match.getAverageMmrTeam1());
        data.put("averageMmrTeam2", match.getAverageMmrTeam2());
        data.put("timeoutSeconds", ACCEPTANCE_TIMEOUT_SECONDS);

        // ✅ Buscar dados dos jogadores do banco (para ter MMR, lanes, etc)
        List<QueuePlayerInfoDTO> team1DTOs = new ArrayList<>();
        List<QueuePlayerInfoDTO> team2DTOs = new ArrayList<>();

        String[] lanes = { "top", "jungle", "mid", "bot", "support" };

        // Team 1
        for (int i = 0; i < team1Names.size() && i < 5; i++) {
            String playerName = team1Names.get(i);
            Optional<QueuePlayer> playerOpt = queuePlayerRepository.findBySummonerName(playerName);

            if (playerOpt.isPresent()) {
                QueuePlayer player = playerOpt.get();
                String assignedLane = lanes[i];
                boolean isAutofill = determineIfAutofill(player, assignedLane);
                team1DTOs.add(convertToDTO(player, assignedLane, i, isAutofill));
            }
        }

        // Team 2
        for (int i = 0; i < team2Names.size() && i < 5; i++) {
            String playerName = team2Names.get(i);
            Optional<QueuePlayer> playerOpt = queuePlayerRepository.findBySummonerName(playerName);

            if (playerOpt.isPresent()) {
                QueuePlayer player = playerOpt.get();
                String assignedLane = lanes[i];
                boolean isAutofill = determineIfAutofill(player, assignedLane);
                team2DTOs.add(convertToDTO(player, assignedLane, i + 5, isAutofill));
            }
        }

        data.put("team1", team1DTOs);
        data.put("team2", team2DTOs);

        return data;
    }

    private void notifyTimerUpdate(Long matchId, int secondsRemaining) {
        try {
            // ✅ REDIS ONLY: Buscar jogadores do Redis
            List<String> allPlayers = redisAcceptance.getAllPlayers(matchId);
            if (allPlayers == null || allPlayers.isEmpty()) {
                return;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("matchId", matchId);
            data.put("secondsRemaining", secondsRemaining);

            // ✅ CRÍTICO: Enviar APENAS para os jogadores desta partida
            webSocketService.sendToPlayers("acceptance_timer", data, allPlayers);

        } catch (Exception e) {
            // Log silencioso para não poluir
        }
    }

    /**
     * ✅ NOVO: Retorna índice numérico da lane (para ordenação)
     */
    private int getLaneIndex(String lane) {
        if (lane == null)
            return 999;
        return switch (lane.toLowerCase()) {
            case "top" -> 0;
            case "jungle" -> 1;
            case "mid", "middle" -> 2;
            case "bot", "adc", "bottom" -> 3;
            case "support", "sup" -> 4;
            default -> 999;
        };
    }

    private QueuePlayerInfoDTO convertToDTO(QueuePlayer player, String assignedLane, int teamIndex,
            boolean isAutofill) {
        return QueuePlayerInfoDTO.builder()
                .id(player.getId())
                .playerId(player.getPlayerId())
                .summonerName(player.getSummonerName())
                .region(player.getRegion())
                .customLp(player.getCustomLp() != null ? player.getCustomLp() : 0)
                .mmr(player.getCustomLp() != null ? player.getCustomLp() : 0)
                .primaryLane(player.getPrimaryLane())
                .secondaryLane(player.getSecondaryLane())
                .assignedLane(assignedLane)
                .isAutofill(isAutofill)
                .teamIndex(teamIndex)
                .queuePosition(player.getQueuePosition())
                .joinTime(player.getJoinTime())
                .acceptanceStatus(player.getAcceptanceStatus())
                .isCurrentPlayer(false)
                .profileIconId(29) // Default icon
                .build();
    }

    // ✅ REMOVIDO: Classe MatchAcceptanceStatus - Redis é fonte única da verdade
    // Use redisAcceptance para todas as operações de aceitação

    /**
     * ✅ NOVO: Calcula o tipo de badge de lane para um jogador
     * 
     * @param assignedLane  Lane atribuída ao jogador
     * @param primaryLane   Lane primária preferida
     * @param secondaryLane Lane secundária preferida
     * @return "primary", "secondary" ou "autofill"
     */
    private static String calculateLaneBadge(String assignedLane, String primaryLane, String secondaryLane) {
        if (assignedLane == null || assignedLane.isEmpty()) {
            return "autofill";
        }

        String normalizedAssigned = normalizeLaneForBadge(assignedLane);
        String normalizedPrimary = normalizeLaneForBadge(primaryLane != null ? primaryLane : "");
        String normalizedSecondary = normalizeLaneForBadge(secondaryLane != null ? secondaryLane : "");

        if (normalizedAssigned.equals(normalizedPrimary)) {
            return "primary";
        } else if (normalizedAssigned.equals(normalizedSecondary)) {
            return "secondary";
        } else {
            return "autofill";
        }
    }

    /**
     * ✅ NOVO: Normaliza nome de lane para comparação de badge
     * 
     * @param lane Nome da lane
     * @return Lane normalizada (lowercase, adc -> bot)
     */
    private static String normalizeLaneForBadge(String lane) {
        if (lane == null)
            return "";
        String normalized = lane.toLowerCase().trim();
        return normalized.equals("adc") ? "bot" : normalized;
    }
}