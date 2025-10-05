package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.Match;
import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.MatchRepository;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchVoteService {

    private final MatchRepository matchRepository;
    private final PlayerRepository playerRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final ObjectMapper objectMapper;
    private final SpecialUserService specialUserService;

    private static final int VOTES_REQUIRED_FOR_AUTO_LINK = 5;
    private final Map<Long, Map<Long, Long>> temporaryVotes = new ConcurrentHashMap<>();

    @Transactional
    public Map<String, Object> processVote(Long matchId, Long playerId, Long lcuGameId) {
        log.info("Voto recebido: matchId={}, playerId={}, lcuGameId={}", matchId, playerId, lcuGameId);

        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Partida nao encontrada"));

        String status = match.getStatus();
        boolean validStatus = "in_progress".equals(status) || "game_in_progress".equals(status)
                || "ended".equals(status);
        if (validStatus == false) {
            throw new IllegalStateException("Partida nao disponivel para votacao");
        }

        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Jogador nao encontrado"));

        temporaryVotes.putIfAbsent(matchId, new ConcurrentHashMap<>());
        temporaryVotes.get(matchId).put(playerId, lcuGameId);

        boolean isSpecialUser = specialUserService.isSpecialUser(player.getSummonerName());

        if (isSpecialUser) {
            log.info("🌟 Special user detectado! Voto finaliza a partida imediatamente");
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("shouldLink", true);
            result.put("lcuGameId", lcuGameId);
            result.put("specialUserVote", true);
            result.put("voteCount", 1);
            result.put("playerVote", lcuGameId);
            return result;
        }

        Map<Long, Long> voteCountMap = countVotesByLcuGameId(matchId);
        log.info("📊 Contagem atual de votos: {}", voteCountMap);

        Optional<Long> winningLcuGameId = voteCountMap.entrySet().stream()
                .filter(entry -> entry.getValue() >= VOTES_REQUIRED_FOR_AUTO_LINK)
                .map(Map.Entry::getKey)
                .findFirst();

        int currentVoteCount = voteCountMap.getOrDefault(lcuGameId, 0L).intValue();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("shouldLink", winningLcuGameId.isPresent());
        result.put("lcuGameId", lcuGameId);
        result.put("specialUserVote", false);
        result.put("voteCount", currentVoteCount);
        result.put("playerVote", lcuGameId);

        if (winningLcuGameId.isPresent()) {
            log.info("🎯 5 votos atingidos para lcuGameId={}", winningLcuGameId.get());
        }

        return result;
    }

    public Map<Long, Long> countVotesByLcuGameId(Long matchId) {
        Map<Long, Long> playerVotes = temporaryVotes.get(matchId);
        if (playerVotes == null || playerVotes.isEmpty()) {
            return new HashMap<>();
        }
        return playerVotes.values().stream()
                .collect(Collectors.groupingBy(lcuGameId -> lcuGameId, Collectors.counting()));
    }

    public void removeVote(Long matchId, Long playerId) {
        Map<Long, Long> matchVotes = temporaryVotes.get(matchId);
        if (matchVotes != null) {
            matchVotes.remove(playerId);
            log.info("Voto removido: matchId={}, playerId={}", matchId, playerId);
        }
    }

    @Transactional
    public void linkMatch(Long matchId, Long lcuGameId, JsonNode lcuMatchData) {
        try {
            Match match = matchRepository.findById(matchId)
                    .orElseThrow(() -> new IllegalArgumentException("Partida nao encontrada"));

            match.setLcuMatchData(objectMapper.writeValueAsString(lcuMatchData));
            match.setRiotGameId(String.valueOf(lcuGameId));
            match.setStatus("completed");
            match.setCompletedAt(Instant.now());

            matchRepository.save(match);
            temporaryVotes.remove(matchId);
        } catch (Exception e) {
            throw new IllegalStateException("Erro ao vincular partida", e);
        }
    }
}
