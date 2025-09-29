package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.Match;
import br.com.lolmatchmaking.backend.domain.repository.MatchRepository;
import br.com.lolmatchmaking.backend.dto.MatchDTO;
import br.com.lolmatchmaking.backend.mapper.MatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchHistoryService {

    private static final String CREATED_AT_FIELD = "createdAt";
    private static final String COMPLETED_STATUS = "COMPLETED";

    private final MatchRepository matchRepository;
    private final MatchMapper matchMapper;
    private final PlayerService playerService;

    @Cacheable("match-history")
    public Page<MatchDTO> getRecentMatches(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, CREATED_AT_FIELD));
        return matchRepository.findAll(pageable)
                .map(matchMapper::toDTO);
    }

    @Cacheable("match-history-by-player")
    public Page<MatchDTO> getMatchesByPlayer(String summonerName, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, CREATED_AT_FIELD));
        return matchRepository.findByPlayerInvolved(summonerName, pageable)
                .map(matchMapper::toDTO);
    }

    /**
     * Fallback method that does the same as getMatchesByPlayer but without cache
     * annotations.
     * Used when the cache manager does not have the named cache available (e.g.
     * minimal
     * runtime environments or tests). Calling this method avoids cache-related
     * exceptions.
     */
    public Page<MatchDTO> getMatchesByPlayerNoCache(String summonerName, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, CREATED_AT_FIELD));
        return matchRepository.findByPlayerInvolved(summonerName, pageable)
                .map(matchMapper::toDTO);
    }

    @Cacheable("match-history-by-status")
    public List<MatchDTO> getMatchesByStatus(String status, int limit) {
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, CREATED_AT_FIELD));
        return matchRepository.findByStatus(status, pageable)
                .stream()
                .map(matchMapper::toDTO)
                .toList();
    }

    public Optional<MatchDTO> getMatchById(String matchId) {
        return matchRepository.findByMatchIdentifier(matchId)
                .map(matchMapper::toDTO);
    }

    @Cacheable("match-stats-summary")
    public MatchStatsSummary getPlayerMatchStats(String summonerName) {
        List<Match> playerMatches = matchRepository.findPlayerMatches(summonerName);

        int totalMatches = playerMatches.size();
        int wins = 0;
        int losses = 0;

        for (Match match : playerMatches) {
            if (COMPLETED_STATUS.equals(match.getStatus()) && match.getWinnerTeam() != null) {
                // Por enquanto, assumir que o jogador perdeu (implementar lógica
                // posteriormente)
                losses++;
            }
        }

        double winRate = totalMatches > 0 ? (double) wins / totalMatches * 100 : 0.0;

        return MatchStatsSummary.builder()
                .totalMatches(totalMatches)
                .wins(wins)
                .losses(losses)
                .winRate(winRate)
                .build();
    }

    @Cacheable("recent-activity")
    public List<MatchDTO> getRecentActivity(int hours, int limit) {
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, CREATED_AT_FIELD));

        return matchRepository.findByCreatedAtAfter(since, pageable)
                .stream()
                .map(matchMapper::toDTO)
                .toList();
    }

    public List<MatchDTO> getActiveMatches() {
        return matchRepository.findByStatusIn(List.of("DRAFT", "IN_PROGRESS", "FOUND"))
                .stream()
                .map(matchMapper::toDTO)
                .toList();
    }

    @Transactional
    public MatchDTO createMatch(MatchDTO matchDTO) {
        Match match = matchMapper.toEntity(matchDTO);
        match.setCreatedAt(Instant.now());
        match.setStatus("CREATED");

        Match savedMatch = matchRepository.save(match);
        log.info("Nova partida criada: {}", savedMatch.getMatchIdentifier());

        return matchMapper.toDTO(savedMatch);
    }

    @Transactional
    public Optional<MatchDTO> updateMatchStatus(String matchId, String newStatus) {
        Optional<Match> matchOpt = matchRepository.findByMatchIdentifier(matchId);
        if (matchOpt.isEmpty()) {
            log.warn("Tentativa de atualizar status de partida inexistente: {}", matchId);
            return Optional.empty();
        }

        Match match = matchOpt.get();
        String oldStatus = match.getStatus();
        match.setStatus(newStatus);

        if (COMPLETED_STATUS.equals(newStatus)) {
            match.setCompletedAt(Instant.now());
        }

        Match savedMatch = matchRepository.save(match);
        log.info("Status da partida {} alterado de {} para {}", matchId, oldStatus, newStatus);

        return Optional.of(matchMapper.toDTO(savedMatch));
    }

    @Transactional
    public Optional<MatchDTO> setMatchWinner(String matchId, Integer winnerTeam) {
        Optional<Match> matchOpt = matchRepository.findByMatchIdentifier(matchId);
        if (matchOpt.isEmpty()) {
            return Optional.empty();
        }

        Match match = matchOpt.get();
        match.setWinnerTeam(winnerTeam);
        match.setStatus(COMPLETED_STATUS);
        match.setCompletedAt(Instant.now());

        Match savedMatch = matchRepository.save(match);
        log.info("Vencedor definido para partida {}: Team {}", matchId, winnerTeam);

        // Atualizar MMR dos jogadores
        updatePlayerMmrAfterMatch(savedMatch);

        return Optional.of(matchMapper.toDTO(savedMatch));
    }

    private void updatePlayerMmrAfterMatch(Match match) {
        try {
            // Implementar lógica de atualização de MMR baseada no resultado
            // Por enquanto apenas loggar
            log.info("Atualizando MMR dos jogadores da partida: {}", match.getMatchIdentifier());
        } catch (Exception e) {
            log.error("Erro ao atualizar MMR dos jogadores", e);
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class MatchStatsSummary {
        private int totalMatches;
        private int wins;
        private int losses;
        private double winRate;
    }
}
