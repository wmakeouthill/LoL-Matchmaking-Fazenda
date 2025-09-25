package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.entity.QueuePlayer;
import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import br.com.lolmatchmaking.backend.domain.repository.QueuePlayerRepository;
import br.com.lolmatchmaking.backend.dto.MatchDTO;
import br.com.lolmatchmaking.backend.dto.MatchInfoDTO;
import br.com.lolmatchmaking.backend.dto.PlayerDTO;
import br.com.lolmatchmaking.backend.service.DraftService.DraftPlayer;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationContext;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchFoundService {

    private final CustomMatchRepository customMatchRepository;
    private final QueuePlayerRepository queuePlayerRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final DraftService draftService;
    private final DiscordService discordService;
    private final ApplicationContext applicationContext;

    // Configurações
    private static final long ACCEPTANCE_TIMEOUT_MS = 30000; // 30 segundos
    private static final long MONITORING_INTERVAL_MS = 1000; // 1 segundo

    // Cache de partidas pendentes de aceitação
    private final Map<Long, AcceptanceStatus> pendingMatches = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> matchCreationLocks = new ConcurrentHashMap<>();

    @Data
    @RequiredArgsConstructor
    public static class AcceptanceStatus {
        private final Long matchId;
        private final List<String> players;
        private final Set<String> acceptedPlayers = new HashSet<>();
        private final Set<String> declinedPlayers = new HashSet<>();
        private final LocalDateTime createdAt = LocalDateTime.now();
        private CompletableFuture<Void> timeoutFuture;
    }

    /**
     * Inicializa o serviço
     */
    public void initialize() {
        log.info("🎯 Inicializando MatchFoundService...");
        log.info("✅ MatchFoundService inicializado com sucesso");
    }

    /**
     * Cria uma partida para processo de aceitação
     */
    @Transactional
    public Long createMatchForAcceptance(List<QueuePlayer> team1, List<QueuePlayer> team2,
            int averageMmrTeam1, int averageMmrTeam2) {
        try {
            log.info("🎮 Criando partida para processo de aceitação...");

            // Criar partida no banco
            CustomMatch match = new CustomMatch();
            match.setTitle("Partida Customizada");
            match.setDescription("Partida criada pelo sistema de matchmaking");
            match.setTeam1PlayersJson(team1.stream()
                    .map(QueuePlayer::getSummonerName)
                    .collect(Collectors.joining(",")));
            match.setTeam2PlayersJson(team2.stream()
                    .map(QueuePlayer::getSummonerName)
                    .collect(Collectors.joining(",")));
            match.setStatus("pending_acceptance");
            match.setCreatedBy("system");
            match.setCreatedAt(Instant.now());
            match.setGameMode("5v5");
            match.setAverageMmrTeam1(averageMmrTeam1);
            match.setAverageMmrTeam2(averageMmrTeam2);
            match.setOwnerBackendId("spring-backend");
            match.setOwnerHeartbeat(System.currentTimeMillis());

            match = customMatchRepository.save(match);

            // Criar status de aceitação
            List<String> allPlayers = new ArrayList<>();
            allPlayers.addAll(team1.stream().map(QueuePlayer::getSummonerName).toList());
            allPlayers.addAll(team2.stream().map(QueuePlayer::getSummonerName).toList());

            AcceptanceStatus status = new AcceptanceStatus(match.getId(), allPlayers);
            pendingMatches.put(match.getId(), status);

            // Configurar timeout
            setupAcceptanceTimeout(match.getId());

            // Notificar jogadores via WebSocket
            notifyMatchFound(match, team1, team2);

            // Notificar Discord se disponível
            notifyDiscordMatchFound(match, team1, team2);

            log.info("✅ Partida criada para aceitação: ID {}", match.getId());
            return match.getId();

        } catch (Exception e) {
            log.error("❌ Erro ao criar partida para aceitação", e);
            return null;
        }
    }

    /**
     * Processa aceitação de partida
     */
    @Transactional
    public boolean acceptMatch(Long matchId, String playerName) {
        try {
            AcceptanceStatus status = pendingMatches.get(matchId);
            if (status == null) {
                log.warn("⚠️ Status de aceitação não encontrado para partida: {}", matchId);
                return false;
            }

            if (status.getDeclinedPlayers().contains(playerName)) {
                log.warn("⚠️ Jogador {} já recusou a partida {}", playerName, matchId);
                return false;
            }

            status.getAcceptedPlayers().add(playerName);
            log.info("✅ Jogador {} aceitou a partida {}", playerName, matchId);

            // Verificar se todos aceitaram
            if (status.getAcceptedPlayers().size() == status.getPlayers().size()) {
                // Use proxy bean to ensure transactional proxy is applied
                getSelf().completeMatchAcceptance(matchId);
            }

            // Notificar atualização
            broadcastAcceptanceUpdate(matchId);

            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao processar aceitação", e);
            return false;
        }
    }

    /**
     * Processa recusa de partida
     */
    @Transactional
    public boolean declineMatch(Long matchId, String playerName) {
        try {
            AcceptanceStatus status = pendingMatches.get(matchId);
            if (status == null) {
                log.warn("⚠️ Status de aceitação não encontrado para partida: {}", matchId);
                return false;
            }

            status.getDeclinedPlayers().add(playerName);
            log.info("❌ Jogador {} recusou a partida {}", playerName, matchId);

            // Cancelar partida se alguém recusou (usar proxy para transação)
            getSelf().cancelMatch(matchId, "Jogador recusou a partida");

            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao processar recusa", e);
            return false;
        }
    }

    /**
     * Obtém status de aceitação
     */
    public Map<String, Object> getAcceptanceStatus(Long matchId) {
        AcceptanceStatus status = pendingMatches.get(matchId);
        if (status == null) {
            return Map.of("error", "Partida não encontrada");
        }

        return Map.of(
                "matchId", matchId,
                "players", status.getPlayers(),
                "acceptedPlayers", new ArrayList<>(status.getAcceptedPlayers()),
                "declinedPlayers", new ArrayList<>(status.getDeclinedPlayers()),
                "allAccepted", status.getAcceptedPlayers().size() == status.getPlayers().size(),
                "createdAt", status.getCreatedAt());
    }

    /**
     * Completa o processo de aceitação
     */
    @Transactional
    public void completeMatchAcceptance(Long matchId) {
        try {
            AcceptanceStatus status = pendingMatches.get(matchId);
            if (status == null) {
                return;
            }

            // Atualizar status da partida
            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match != null) {
                match.setStatus("accepted");
                match.setUpdatedAt(Instant.now());
                customMatchRepository.save(match);

                // Iniciar draft
                startDraftProcess(match);
            }

            // Remover do cache de pendentes
            pendingMatches.remove(matchId);

            // Notificar via WebSocket
            webSocketService.broadcastMatchAccepted(matchId.toString());

            log.info("✅ Partida {} aceita por todos os jogadores", matchId);

        } catch (Exception e) {
            log.error("❌ Erro ao completar aceitação", e);
        }
    }

    /**
     * Cancela uma partida
     */
    @Transactional
    public void cancelMatch(Long matchId, String reason) {
        try {
            AcceptanceStatus status = pendingMatches.get(matchId);
            if (status != null) {
                // Cancelar timeout se existir
                if (status.getTimeoutFuture() != null) {
                    status.getTimeoutFuture().cancel(true);
                }
                pendingMatches.remove(matchId);
            }

            // Atualizar status da partida
            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match != null) {
                match.setStatus("cancelled");
                match.setUpdatedAt(Instant.now());
                customMatchRepository.save(match);
            }

            // Notificar via WebSocket
            webSocketService.broadcastMatchCancelled(matchId.toString(), reason);

            log.info("❌ Partida {} cancelada: {}", matchId, reason);

        } catch (Exception e) {
            log.error("❌ Erro ao cancelar partida", e);
        }
    }

    /**
     * Configura timeout para aceitação
     */
    private void setupAcceptanceTimeout(Long matchId) {
        AcceptanceStatus status = pendingMatches.get(matchId);
        if (status != null) {
            status.setTimeoutFuture(CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(ACCEPTANCE_TIMEOUT_MS);

                    // Verificar se ainda está pendente
                    if (pendingMatches.containsKey(matchId)) {
                        log.warn("⏰ Timeout de aceitação para partida {}", matchId);
                        getSelf().cancelMatch(matchId, "Timeout de aceitação");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
    }

    /**
     * Inicia processo de draft
     */
    private void startDraftProcess(CustomMatch match) {
        try {
            log.info("🏆 Iniciando processo de draft para partida {}", match.getId());

            // Atualizar status para draft
            match.setStatus("draft");
            match.setUpdatedAt(Instant.now());
            customMatchRepository.save(match);

            // Notificar Discord
            notifyDiscordDraftStarted(match);

            // Criar listas de jogadores para o draft
            List<DraftPlayer> team1 = Arrays.stream(match.getTeam1PlayersJson().split(","))
                    .map(name -> new DraftPlayer(name.trim(), "team1", 1, 0, "mid", "top", false, ""))
                    .toList();
            List<DraftPlayer> team2 = Arrays.stream(match.getTeam2PlayersJson().split(","))
                    .map(name -> new DraftPlayer(name.trim(), "team2", 2, 0, "mid", "top", false, ""))
                    .toList();

            // Iniciar draft
            draftService.startDraft(match.getId(), team1, team2, match.getAverageMmrTeam1(),
                    match.getAverageMmrTeam2());

        } catch (Exception e) {
            log.error("❌ Erro ao iniciar draft", e);
        }
    }

    /**
     * Notifica jogadores sobre partida encontrada
     */
    private void notifyMatchFound(CustomMatch match, List<QueuePlayer> team1, List<QueuePlayer> team2) {
        try {
            MatchInfoDTO matchInfo = new MatchInfoDTO(
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
                    match.getParticipantsDataJson(),
                    match.getRiotGameId(),
                    match.getDetectedByLcu(),
                    match.getNotes(),
                    match.getCustomLp(),
                    match.getUpdatedAt(),
                    match.getPickBanDataJson(),
                    match.getLinkedResultsJson(),
                    match.getActualWinner(),
                    match.getActualDuration(),
                    match.getRiotId(),
                    match.getMmrChangesJson(),
                    match.getMatchLeader(),
                    match.getOwnerBackendId(),
                    match.getOwnerHeartbeat());
            webSocketService.broadcastMatchFound(matchInfo);
        } catch (Exception e) {
            log.error("❌ Erro ao notificar partida encontrada", e);
        }
    }

    /**
     * Notifica Discord sobre partida encontrada
     */
    private void notifyDiscordMatchFound(CustomMatch match, List<QueuePlayer> team1, List<QueuePlayer> team2) {
        try {
            if (discordService != null && discordService.isConnected()) {
                String[] team1Names = team1.stream()
                        .map(QueuePlayer::getSummonerName)
                        .toArray(String[]::new);
                String[] team2Names = team2.stream()
                        .map(QueuePlayer::getSummonerName)
                        .toArray(String[]::new);

                discordService.notifyMatchFound("matchmaking", match.getId().toString(), team1Names, team2Names);
            }
        } catch (Exception e) {
            log.error("❌ Erro ao notificar Discord sobre partida", e);
        }
    }

    /**
     * Notifica Discord sobre início do draft
     */
    private void notifyDiscordDraftStarted(CustomMatch match) {
        try {
            if (discordService != null && discordService.isConnected()) {
                // Atualizado para apontar para a porta 8080 (novo backend/frontend)
                String draftUrl = String.format("http://localhost:8080/draft/%d", match.getId());
                discordService.notifyDraftStarted("matchmaking", match.getId().toString(), draftUrl);
            }
        } catch (Exception e) {
            log.error("❌ Erro ao notificar Discord sobre draft", e);
        }
    }

    /**
     * Faz broadcast da atualização de aceitação
     */
    private void broadcastAcceptanceUpdate(Long matchId) {
        try {
            Map<String, Object> status = getAcceptanceStatus(matchId);
            webSocketService.broadcastToAll("acceptance_update", status);
        } catch (Exception e) {
            log.error("❌ Erro ao fazer broadcast de aceitação", e);
        }
    }

    /**
     * Cria uma partida encontrada
     */
    public CustomMatch createFoundMatch(List<PlayerDTO> team1, List<PlayerDTO> team2) {
        try {
            log.info("🎮 Criando partida encontrada com {} vs {} jogadores", team1.size(), team2.size());

            CustomMatch match = new CustomMatch();
            match.setTitle("Partida Encontrada");
            match.setDescription("Partida criada automaticamente pelo sistema de matchmaking");
            match.setStatus("found");
            match.setGameMode("ranked");
            match.setCreatedAt(Instant.now());
            match.setUpdatedAt(Instant.now());

            // Converter listas de jogadores para JSON
            String team1Json = team1.stream()
                    .map(PlayerDTO::getSummonerName)
                    .collect(Collectors.joining(","));
            String team2Json = team2.stream()
                    .map(PlayerDTO::getSummonerName)
                    .collect(Collectors.joining(","));

            match.setTeam1PlayersJson(team1Json);
            match.setTeam2PlayersJson(team2Json);

            // Calcular MMR médio
            double avgMmr1 = team1.stream().mapToInt(p -> p.getLeaguePoints() != null ? p.getLeaguePoints() : 0)
                    .average().orElse(0);
            double avgMmr2 = team2.stream().mapToInt(p -> p.getLeaguePoints() != null ? p.getLeaguePoints() : 0)
                    .average().orElse(0);

            match.setAverageMmrTeam1((int) avgMmr1);
            match.setAverageMmrTeam2((int) avgMmr2);

            CustomMatch savedMatch = customMatchRepository.save(match);

            return savedMatch;
        } catch (Exception e) {
            log.error("❌ Erro ao criar partida encontrada", e);
            return null;
        }
    }

    /**
     * Converte CustomMatch para MatchDTO
     */
    private MatchDTO convertToMatchDTO(CustomMatch match) {
        return MatchDTO.builder()
                .matchId(match.getId().toString())
                .status(match.getStatus())
                .createdAt(match.getCreatedAt() != null
                        ? match.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                        : null)
                .updatedAt(match.getUpdatedAt() != null
                        ? match.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                        : null)
                .matchStartedAt(match.getCreatedAt() != null
                        ? match.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                        : null)
                .matchEndedAt(match.getCompletedAt() != null
                        ? match.getCompletedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                        : null)
                .gameMode(match.getGameMode())
                .duration(match.getDuration() != null ? match.getDuration().longValue() : null)
                .winnerTeam(match.getWinnerTeam() != null ? match.getWinnerTeam().toString() : null)
                .isCustomGame(true)
                .riotMatchId(match.getRiotGameId())
                .build();
    }

    /**
     * Monitoramento de aceitação executado periodicamente via @Scheduled
     */
    @Scheduled(fixedDelay = MONITORING_INTERVAL_MS)
    public void monitorAcceptance() {
        try {
            // Limpar partidas expiradas
            cleanupExpiredMatches();
        } catch (Exception e) {
            log.error("❌ Erro no monitoramento de aceitação", e);
        }
    }

    /**
     * Limpa partidas expiradas
     */
    private void cleanupExpiredMatches() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(ACCEPTANCE_TIMEOUT_MS / 1000);

            pendingMatches.entrySet().removeIf(entry -> {
                AcceptanceStatus status = entry.getValue();
                if (status.getCreatedAt().isBefore(cutoff)) {
                    log.warn("🧹 Removendo partida expirada: {}", entry.getKey());
                    getSelf().cancelMatch(entry.getKey(), "Partida expirada");
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            log.error("❌ Erro ao limpar partidas expiradas", e);
        }
    }

    /**
     * Obtém número de partidas pendentes
     */
    public int getPendingMatchesCount() {
        return pendingMatches.size();
    }

    /**
     * Limpa todas as partidas pendentes (para debug/admin)
     */
    public void clearPendingMatches() {
        try {
            for (Long matchId : new ArrayList<>(pendingMatches.keySet())) {
                getSelf().cancelMatch(matchId, "Sistema limpo");
            }
            log.info("🧹 Partidas pendentes limpas");
        } catch (Exception e) {
            log.error("❌ Erro ao limpar partidas pendentes", e);
        }
    }

    // Retorna o bean proxy desta classe para chamadas que precisam acionar proxy de transação
    private MatchFoundService getSelf() {
        return applicationContext.getBean(MatchFoundService.class);
    }
}
