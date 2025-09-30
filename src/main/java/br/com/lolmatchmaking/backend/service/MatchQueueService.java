package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.QueuePlayer;
import br.com.lolmatchmaking.backend.domain.repository.QueuePlayerRepository;
import br.com.lolmatchmaking.backend.dto.PlayerDTO;
import br.com.lolmatchmaking.backend.dto.QueuePlayerDTO;
import br.com.lolmatchmaking.backend.dto.QueuePlayerInfoDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchQueueService {

    private static final int TEAM_SIZE = 5;
    private static final long QUEUE_TIMEOUT_MINUTES = 10;

    private final QueuePlayerRepository queuePlayerRepository;
    private final PlayerService playerService;
    private final MatchFoundService matchFoundService;

    @Transactional
    public QueuePlayerDTO joinQueue(String summonerName) {
        // Verificar se jogador já está na fila
        if (queuePlayerRepository.findBySummonerName(summonerName).isPresent()) {
            log.warn("Jogador {} já está na fila", summonerName);
            throw new IllegalStateException("Jogador já está na fila");
        }

        // Buscar dados do jogador
        Optional<PlayerDTO> playerOpt = playerService.getPlayerBySummonerName(summonerName);
        if (playerOpt.isEmpty()) {
            log.warn("Tentativa de entrar na fila com jogador inexistente: {}", summonerName);
            throw new IllegalArgumentException("Jogador não encontrado");
        }

        PlayerDTO player = playerOpt.get();

        // Criar entrada na fila
        QueuePlayer queuePlayer = QueuePlayer.builder()
                .summonerName(summonerName)
                .region("BR1")
                .customLp(player.getLeaguePoints())
                .joinTime(Instant.now())
                .active(true)
                .build();

        QueuePlayer savedQueuePlayer = queuePlayerRepository.save(queuePlayer);
        log.info("Jogador {} entrou na fila - Rank: {} {} {}LP",
                summonerName, player.getTier(), player.getRank(), player.getLeaguePoints());

        return QueuePlayerDTO.builder()
                .queuePlayerId(savedQueuePlayer.getId().toString())
                .player(player)
                .region(savedQueuePlayer.getRegion())
                .estimatedWaitTime(calculateEstimatedWaitTime())
                .queueTime(calculateQueueTimeFor(savedQueuePlayer))
                .isPriority(false)
                .build();
    }

    @Transactional
    public boolean leaveQueue(String summonerName) {
        Optional<QueuePlayer> queuePlayerOpt = queuePlayerRepository.findBySummonerName(summonerName);
        if (queuePlayerOpt.isPresent()) {
            queuePlayerRepository.delete(queuePlayerOpt.get());
            log.info("Jogador {} saiu da fila", summonerName);
            return true;
        }
        return false;
    }

    public List<QueuePlayerInfoDTO> getQueueStatus() {
        List<QueuePlayer> queuePlayers = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc();

        return queuePlayers.stream()
                .map(this::convertToQueuePlayerInfoDTO)
                .toList();
    }

    @Scheduled(fixedRate = 30000) // Executa a cada 30 segundos
    public void processQueue() {
        try {
            List<QueuePlayer> queuePlayers = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc();

            if (queuePlayers.size() < TEAM_SIZE * 2) {
                log.debug("Não há jogadores suficientes na fila: {}/{}", queuePlayers.size(), TEAM_SIZE * 2);
                return;
            }

            // Tentar formar partida
            Optional<MatchTeams> teamsOpt = findBalancedTeams(queuePlayers);
            if (teamsOpt.isPresent()) {
                MatchTeams teams = teamsOpt.get();

                // Converter para PlayerDTO
                List<PlayerDTO> team1 = convertToPlayerDTOs(teams.team1());
                List<PlayerDTO> team2 = convertToPlayerDTOs(teams.team2());

                // Criar partida
                matchFoundService.createFoundMatch(team1, team2);

                log.info("Partida criada com sucesso - Team1: {} jogadores, Team2: {} jogadores",
                        team1.size(), team2.size());
            } else {
                log.debug("Não foi possível formar uma partida balanceada com os jogadores na fila");
            }

        } catch (Exception e) {
            log.error("Erro ao processar fila", e);
        }
    }

    @Scheduled(fixedRate = 300000) // Executa a cada 5 minutos
    @Transactional
    public void cleanupExpiredQueueEntries() {
        try {
            Instant cutoff = Instant.now().minus(QUEUE_TIMEOUT_MINUTES, ChronoUnit.MINUTES);
            List<QueuePlayer> expiredPlayers = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc()
                    .stream()
                    .filter(qp -> qp.getJoinTime().isBefore(cutoff))
                    .toList();

            if (!expiredPlayers.isEmpty()) {
                queuePlayerRepository.deleteAll(expiredPlayers);
                log.info("Removidos {} jogadores da fila por timeout", expiredPlayers.size());
            }
        } catch (Exception e) {
            log.error("Erro ao limpar entradas expiradas da fila", e);
        }
    }

    private Optional<MatchTeams> findBalancedTeams(List<QueuePlayer> queuePlayers) {
        if (queuePlayers.size() < TEAM_SIZE * 2) {
            return Optional.empty();
        }

        // Implementação simples: pegar os primeiros 10 jogadores e dividir
        List<QueuePlayer> selectedPlayers = queuePlayers.subList(0, TEAM_SIZE * 2);

        List<QueuePlayer> team1 = new ArrayList<>();
        List<QueuePlayer> team2 = new ArrayList<>();

        // Distribuição alternada para balanceamento
        for (int i = 0; i < selectedPlayers.size(); i++) {
            if (i % 2 == 0) {
                team1.add(selectedPlayers.get(i));
            } else {
                team2.add(selectedPlayers.get(i));
            }
        }

        return Optional.of(new MatchTeams(team1, team2));
    }

    private List<PlayerDTO> convertToPlayerDTOs(List<QueuePlayer> queuePlayers) {
        return queuePlayers.stream()
                .map(qp -> playerService.getPlayerBySummonerName(qp.getSummonerName()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private QueuePlayerDTO convertToQueuePlayerDTO(QueuePlayer queuePlayer) {
        Optional<PlayerDTO> playerOpt = playerService.getPlayerBySummonerName(queuePlayer.getSummonerName());

        return QueuePlayerDTO.builder()
                .queuePlayerId(queuePlayer.getId().toString())
                .player(playerOpt.orElse(null))
                .region(queuePlayer.getRegion())
                .estimatedWaitTime(calculateEstimatedWaitTime())
                .queueTime(calculateQueueTimeFor(queuePlayer))
                .isPriority(false)
                .build();
    }

    private QueuePlayerInfoDTO convertToQueuePlayerInfoDTO(QueuePlayer queuePlayer) {
        QueuePlayerInfoDTO dto = new QueuePlayerInfoDTO();
        dto.setId(queuePlayer.getId());
        dto.setSummonerName(queuePlayer.getSummonerName());
        dto.setTagLine(""); // QueuePlayer não tem tagLine
        dto.setRegion(queuePlayer.getRegion());
        dto.setCustomLp(queuePlayer.getCustomLp());
        dto.setPrimaryLane(queuePlayer.getPrimaryLane());
        dto.setSecondaryLane(queuePlayer.getSecondaryLane());
        dto.setJoinTime(queuePlayer.getJoinTime());
        dto.setQueuePosition(queuePlayer.getQueuePosition());
        dto.setIsActive(queuePlayer.getActive());
        dto.setAcceptanceStatus(queuePlayer.getAcceptanceStatus());
        dto.setIsCurrentPlayer(false); // Implementar lógica se necessário
        return dto;
    }

    private long calculateEstimatedWaitTime() {
        List<QueuePlayer> queuePlayers = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc();
        int queueSize = queuePlayers.size();

        if (queueSize < TEAM_SIZE * 2) {
            // Estimar baseado na necessidade de mais jogadores
            int playersNeeded = (TEAM_SIZE * 2) - queueSize;
            return playersNeeded * 30L; // 30 segundos por jogador estimado
        }

        return 60L; // 1 minuto se há jogadores suficientes
    }

    private long calculateQueueTimeFor(QueuePlayer queuePlayer) {
        return Duration.between(queuePlayer.getJoinTime(), Instant.now()).toSeconds();
    }

    private record MatchTeams(List<QueuePlayer> team1, List<QueuePlayer> team2) {
    }
}
