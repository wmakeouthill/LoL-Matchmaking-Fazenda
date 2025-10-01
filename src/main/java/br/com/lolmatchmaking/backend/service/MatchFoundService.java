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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MatchFoundService {

    private final QueuePlayerRepository queuePlayerRepository;
    private final CustomMatchRepository customMatchRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final QueueManagementService queueManagementService;
    private final DraftFlowService draftFlowService;

    // Constructor manual para @Lazy
    public MatchFoundService(
            QueuePlayerRepository queuePlayerRepository,
            CustomMatchRepository customMatchRepository,
            MatchmakingWebSocketService webSocketService,
            @Lazy QueueManagementService queueManagementService,
            DraftFlowService draftFlowService) {
        this.queuePlayerRepository = queuePlayerRepository;
        this.customMatchRepository = customMatchRepository;
        this.webSocketService = webSocketService;
        this.queueManagementService = queueManagementService;
        this.draftFlowService = draftFlowService;
    }

    // Tracking de partidas pendentes de aceitação
    private final Map<Long, MatchAcceptanceStatus> pendingMatches = new ConcurrentHashMap<>();

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

            for (QueuePlayer player : allPlayers) {
                player.setAcceptanceStatus(-1); // -1 = aguardando aceitação (para não ser selecionado novamente)
                queuePlayerRepository.save(player);
            }

            // Criar tracking local
            MatchAcceptanceStatus status = new MatchAcceptanceStatus();
            status.setMatchId(match.getId());
            status.setPlayers(allPlayers.stream().map(QueuePlayer::getSummonerName).collect(Collectors.toList()));
            status.setAcceptedPlayers(new HashSet<>());
            status.setDeclinedPlayers(new HashSet<>());
            status.setCreatedAt(Instant.now());
            status.setTeam1(team1.stream().map(QueuePlayer::getSummonerName).collect(Collectors.toList()));
            status.setTeam2(team2.stream().map(QueuePlayer::getSummonerName).collect(Collectors.toList()));

            pendingMatches.put(match.getId(), status);

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
            log.info("✅ [MatchFound] Jogador {} aceitou partida {}", summonerName, matchId);

            // Atualizar no banco
            queuePlayerRepository.findBySummonerName(summonerName).ifPresent(player -> {
                player.setAcceptanceStatus(1); // 1 = accepted
                queuePlayerRepository.save(player);
            });

            // Atualizar tracking local
            MatchAcceptanceStatus status = pendingMatches.get(matchId);
            if (status != null) {
                status.getAcceptedPlayers().add(summonerName);

                log.info("✅ [MatchFound] Match {} - {}/{} jogadores aceitaram",
                        matchId, status.getAcceptedPlayers().size(), status.getPlayers().size());

                // Notificar progresso
                notifyAcceptanceProgress(matchId, status);

            // Verificar se todos aceitaram
            if (status.getAcceptedPlayers().size() == status.getPlayers().size()) {
                    log.info("🎉 [MatchFound] TODOS OS JOGADORES ACEITARAM! Match {}", matchId);
                    handleAllPlayersAccepted(matchId);
                }
            } else {
                log.warn("⚠️ [MatchFound] Match {} não encontrado no tracking", matchId);
            }

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao aceitar partida", e);
        }
    }

    /**
     * Jogador recusa a partida
     */
    @Transactional
    public void declineMatch(Long matchId, String summonerName) {
        try {
            log.warn("❌ [MatchFound] Jogador {} recusou partida {}", summonerName, matchId);

            // Atualizar no banco
            queuePlayerRepository.findBySummonerName(summonerName).ifPresent(player -> {
                player.setAcceptanceStatus(2); // 2 = declined
                queuePlayerRepository.save(player);
            });

            // Cancelar partida
            handleMatchDeclined(matchId, summonerName);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao recusar partida", e);
        }
    }

    /**
     * Todos os jogadores aceitaram
     */
    @Transactional
    private void handleAllPlayersAccepted(Long matchId) {
        try {
            MatchAcceptanceStatus status = pendingMatches.get(matchId);
            if (status == null)
                return;

            // Atualizar status da partida
            customMatchRepository.findById(matchId).ifPresent(match -> {
                match.setStatus("accepted");
                customMatchRepository.save(match);
            });

            // ✅ Remover todos os jogadores da fila (agora vão para o draft)
            for (String playerName : status.getPlayers()) {
                queueManagementService.removeFromQueue(playerName);
                log.info("🗑️ [MatchFound] Jogador {} removido da fila - indo para draft", playerName);
            }

            // Notificar todos os jogadores
            notifyAllPlayersAccepted(matchId);

            // Iniciar draft após 3 segundos
            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    startDraft(matchId);
                }
            }, 3000);

            // Remover do tracking
            pendingMatches.remove(matchId);

            log.info("✅ [MatchFound] Partida {} aceita por todos - iniciando draft em 3s", matchId);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao processar aceitação completa", e);
        }
    }

    /**
     * Partida recusada
     */
    @Transactional
    private void handleMatchDeclined(Long matchId, String declinedPlayer) {
        try {
            MatchAcceptanceStatus status = pendingMatches.get(matchId);
            if (status == null)
                return;

            // Atualizar status da partida
            customMatchRepository.findById(matchId).ifPresent(match -> {
                match.setStatus("declined");
                customMatchRepository.save(match);
            });

            // ✅ Remover apenas jogador que recusou da fila
            queueManagementService.removeFromQueue(declinedPlayer);
            log.info("🗑️ [MatchFound] Jogador {} removido da fila por recusar", declinedPlayer);

            // ✅ Resetar status de aceitação dos outros jogadores (voltam ao normal na fila)
            for (String playerName : status.getPlayers()) {
                if (!playerName.equals(declinedPlayer)) {
                    queuePlayerRepository.findBySummonerName(playerName).ifPresent(player -> {
                        player.setAcceptanceStatus(0); // Resetar status
                        queuePlayerRepository.save(player);
                        log.info("🔄 [MatchFound] Jogador {} voltou ao estado normal na fila", playerName);
                    });
                }
            }

            // Notificar cancelamento
            notifyMatchCancelled(matchId, declinedPlayer);

            // Remover do tracking
            pendingMatches.remove(matchId);

            log.info("❌ [MatchFound] Partida {} cancelada - {} recusou", matchId, declinedPlayer);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao processar recusa", e);
        }
    }

    /**
     * Timeout de aceitação
     */
    @Scheduled(fixedRate = 1000) // Verifica a cada 1 segundo
    public void checkAcceptanceTimeouts() {
        try {
            Instant now = Instant.now();

            for (Map.Entry<Long, MatchAcceptanceStatus> entry : pendingMatches.entrySet()) {
                MatchAcceptanceStatus status = entry.getValue();
                long secondsElapsed = ChronoUnit.SECONDS.between(status.getCreatedAt(), now);

                if (secondsElapsed >= ACCEPTANCE_TIMEOUT_SECONDS) {
                    log.warn("⏰ [MatchFound] Timeout na partida {}", entry.getKey());
                    handleAcceptanceTimeout(entry.getKey());
                } else {
                    // Atualizar timer
                    int secondsRemaining = ACCEPTANCE_TIMEOUT_SECONDS - (int) secondsElapsed;
                    notifyTimerUpdate(entry.getKey(), secondsRemaining);
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
        try {
            MatchAcceptanceStatus status = pendingMatches.get(matchId);
            if (status == null)
                return;

            // Encontrar jogadores que não aceitaram
            List<String> notAcceptedPlayers = status.getPlayers().stream()
                    .filter(p -> !status.getAcceptedPlayers().contains(p))
                    .collect(Collectors.toList());

            if (!notAcceptedPlayers.isEmpty()) {
                log.warn("⏰ [MatchFound] Timeout - jogadores que não aceitaram: {}", notAcceptedPlayers);
                handleMatchDeclined(matchId, notAcceptedPlayers.get(0));
            }

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao processar timeout", e);
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
     */
    private void startDraft(Long matchId) {
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

            // Buscar nomes dos jogadores
            List<String> team1Names = parsePlayerNames(match.getTeam1PlayersJson());
            List<String> team2Names = parsePlayerNames(match.getTeam2PlayersJson());

            log.info("🎯 [MatchFound] Iniciando draft com {} jogadores no time 1 e {} no time 2",
                    team1Names.size(), team2Names.size());

            // Buscar jogadores completos para enviar no draft_starting
            List<QueuePlayer> team1Players = team1Names.stream()
                    .map(name -> queuePlayerRepository.findBySummonerName(name).orElse(null))
                    .filter(p -> p != null)
                    .collect(Collectors.toList());

            List<QueuePlayer> team2Players = team2Names.stream()
                    .map(name -> queuePlayerRepository.findBySummonerName(name).orElse(null))
                    .filter(p -> p != null)
                    .collect(Collectors.toList());

            // Mapeamento de lanes por posição
            String[] lanes = { "top", "jungle", "mid", "bot", "support" };

            // Converter para DTOs com lanes e posições
            List<QueuePlayerInfoDTO> team1DTOs = new ArrayList<>();
            for (int i = 0; i < team1Players.size(); i++) {
                team1DTOs.add(convertToDTO(team1Players.get(i), lanes[i], i, false));
            }

            List<QueuePlayerInfoDTO> team2DTOs = new ArrayList<>();
            for (int i = 0; i < team2Players.size(); i++) {
                team2DTOs.add(convertToDTO(team2Players.get(i), lanes[i], i + 5, false));
            }

            // Notificar início do draft com dados completos dos times
            Map<String, Object> draftData = new HashMap<>();
            draftData.put("type", "draft_starting");
            draftData.put("matchId", matchId);
            draftData.put("team1", team1DTOs);
            draftData.put("team2", team2DTOs);
            draftData.put("averageMmrTeam1", match.getAverageMmrTeam1());
            draftData.put("averageMmrTeam2", match.getAverageMmrTeam2());

            webSocketService.broadcastToAll("draft_starting", draftData);

            log.info("📢 [MatchFound] Draft starting enviado com {} jogadores no time 1 e {} no time 2",
                    team1DTOs.size(), team2DTOs.size());

            // ✅ Salvar dados completos dos times no pick_ban_data
            saveTeamsDataToPickBan(matchId, team1DTOs, team2DTOs);

            // Iniciar o DraftFlowService para gerenciar picks/bans
            draftFlowService.startDraft(matchId, team1Names, team2Names);
            log.info("✅ [MatchFound] DraftFlowService iniciado para partida {}", matchId);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao iniciar draft", e);
        }
    }

    /**
     * Salva dados completos dos times no pick_ban_data para uso no draft
     */
    private void saveTeamsDataToPickBan(Long matchId, List<QueuePlayerInfoDTO> team1, List<QueuePlayerInfoDTO> team2) {
        try {
            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match == null) {
                log.error("❌ [MatchFound] Partida {} não encontrada para salvar pick_ban_data", matchId);
                return;
            }

            // Criar estrutura com dados completos dos times
            Map<String, Object> pickBanData = new HashMap<>();
            pickBanData.put("team1Players", team1.stream()
                    .map(p -> Map.of(
                            "summonerName", p.getSummonerName(),
                            "playerId", p.getPlayerId(),
                            "mmr", p.getCustomLp() != null ? p.getCustomLp() : 0,
                            "primaryLane", p.getPrimaryLane() != null ? p.getPrimaryLane() : "fill",
                            "secondaryLane", p.getSecondaryLane() != null ? p.getSecondaryLane() : "fill",
                            "assignedLane", p.getAssignedLane() != null ? p.getAssignedLane() : "fill",
                            "teamIndex", p.getTeamIndex() != null ? p.getTeamIndex() : 0,
                            "isAutofill", p.getIsAutofill() != null ? p.getIsAutofill() : false))
                    .collect(Collectors.toList()));

            pickBanData.put("team2Players", team2.stream()
                    .map(p -> Map.of(
                            "summonerName", p.getSummonerName(),
                            "playerId", p.getPlayerId(),
                            "mmr", p.getCustomLp() != null ? p.getCustomLp() : 0,
                            "primaryLane", p.getPrimaryLane() != null ? p.getPrimaryLane() : "fill",
                            "secondaryLane", p.getSecondaryLane() != null ? p.getSecondaryLane() : "fill",
                            "assignedLane", p.getAssignedLane() != null ? p.getAssignedLane() : "fill",
                            "teamIndex", p.getTeamIndex() != null ? p.getTeamIndex() : 0,
                            "isAutofill", p.getIsAutofill() != null ? p.getIsAutofill() : false))
                    .collect(Collectors.toList()));

            // Salvar no banco
            match.setPickBanDataJson(new ObjectMapper().writeValueAsString(pickBanData));
            customMatchRepository.save(match);

            log.info("✅ [MatchFound] Dados dos times salvos em pick_ban_data para partida {}", matchId);

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
            // ✅ Mapeamento de lanes por posição
            String[] lanes = { "top", "jungle", "mid", "bot", "support" };

            Map<String, Object> data = new HashMap<>();
            data.put("type", "match_found");
            data.put("matchId", match.getId());

            // ✅ Enriquecer jogadores com metadados de posição
            List<QueuePlayerInfoDTO> team1DTOs = new ArrayList<>();
            for (int i = 0; i < team1.size(); i++) {
                team1DTOs.add(convertToDTO(team1.get(i), lanes[i], i, false));
            }

            List<QueuePlayerInfoDTO> team2DTOs = new ArrayList<>();
            for (int i = 0; i < team2.size(); i++) {
                team2DTOs.add(convertToDTO(team2.get(i), lanes[i], i + 5, false));
            }

            data.put("team1", team1DTOs);
            data.put("team2", team2DTOs);
            data.put("averageMmrTeam1", match.getAverageMmrTeam1());
            data.put("averageMmrTeam2", match.getAverageMmrTeam2());
            data.put("timeoutSeconds", ACCEPTANCE_TIMEOUT_SECONDS);

            webSocketService.broadcastToAll("match_found", data);

            log.info("📢 [MatchFound] Match found notificado para {} jogadores", team1.size() + team2.size());

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao notificar match found", e);
        }
    }

    private void notifyAcceptanceProgress(Long matchId, MatchAcceptanceStatus status) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("type", "acceptance_progress");
            data.put("matchId", matchId);
            data.put("acceptedCount", status.getAcceptedPlayers().size());
            data.put("totalPlayers", status.getPlayers().size());
            data.put("acceptedPlayers", new ArrayList<>(status.getAcceptedPlayers()));

            webSocketService.broadcastToAll("acceptance_progress", data);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao notificar progresso", e);
        }
    }

    private void notifyAllPlayersAccepted(Long matchId) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("type", "all_players_accepted");
            data.put("matchId", matchId);

            webSocketService.broadcastToAll("all_players_accepted", data);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao notificar aceitação completa", e);
        }
    }

    private void notifyMatchCancelled(Long matchId, String declinedPlayer) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("type", "match_cancelled");
            data.put("matchId", matchId);
            data.put("reason", "declined");
            data.put("declinedPlayer", declinedPlayer);

            webSocketService.broadcastToAll("match_cancelled", data);

        } catch (Exception e) {
            log.error("❌ [MatchFound] Erro ao notificar cancelamento", e);
        }
    }

    private void notifyTimerUpdate(Long matchId, int secondsRemaining) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("type", "acceptance_timer");
            data.put("matchId", matchId);
            data.put("secondsRemaining", secondsRemaining);

            webSocketService.broadcastToAll("acceptance_timer", data);

        } catch (Exception e) {
            // Log silencioso para não poluir
        }
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

    // Classe interna para tracking
    private static class MatchAcceptanceStatus {
        private Long matchId;
        private List<String> players;
        private Set<String> acceptedPlayers;
        private Set<String> declinedPlayers;
        private Instant createdAt;
        private List<String> team1;
        private List<String> team2;

        // Getters e Setters
        public Long getMatchId() {
            return matchId;
        }

        public void setMatchId(Long matchId) {
            this.matchId = matchId;
        }

        public List<String> getPlayers() {
            return players;
        }

        public void setPlayers(List<String> players) {
            this.players = players;
        }

        public Set<String> getAcceptedPlayers() {
            return acceptedPlayers;
        }

        public void setAcceptedPlayers(Set<String> acceptedPlayers) {
            this.acceptedPlayers = acceptedPlayers;
        }

        public Set<String> getDeclinedPlayers() {
            return declinedPlayers;
        }

        public void setDeclinedPlayers(Set<String> declinedPlayers) {
            this.declinedPlayers = declinedPlayers;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public List<String> getTeam1() {
            return team1;
        }

        public void setTeam1(List<String> team1) {
            this.team1 = team1;
        }

        public List<String> getTeam2() {
            return team2;
        }

        public void setTeam2(List<String> team2) {
            this.team2 = team2;
        }
    }
}