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

            // Salvar JSON completo do LCU
            match.setLcuMatchData(objectMapper.writeValueAsString(lcuMatchData));
            match.setRiotGameId(String.valueOf(lcuGameId));

            // Processar e extrair dados dos participantes do LCU
            List<Map<String, Object>> participantsList = new ArrayList<>();

            if (lcuMatchData.has("participants") && lcuMatchData.get("participants").isArray()) {
                JsonNode participants = lcuMatchData.get("participants");
                JsonNode participantIdentities = lcuMatchData.path("participantIdentities");

                log.info("📊 Processando {} participantes da partida LCU", participants.size());

                for (JsonNode participant : participants) {
                    Map<String, Object> participantData = new HashMap<>();

                    int participantId = participant.path("participantId").asInt(0);

                    // Buscar informações do jogador em participantIdentities
                    String summonerName = "";
                    String riotIdGameName = "";
                    String riotIdTagline = "";

                    if (participantIdentities.isArray()) {
                        for (JsonNode identity : participantIdentities) {
                            if (identity.path("participantId").asInt() == participantId) {
                                JsonNode player = identity.path("player");
                                summonerName = player.path("summonerName").asText("");
                                riotIdGameName = player.path("gameName").asText("");
                                riotIdTagline = player.path("tagLine").asText("");
                                break;
                            }
                        }
                    }

                    // Dados básicos do jogador
                    participantData.put("participantId", participantId);
                    participantData.put("summonerName", summonerName);
                    participantData.put("riotIdGameName", riotIdGameName);
                    participantData.put("riotIdTagline", riotIdTagline);
                    participantData.put("championId", participant.path("championId").asInt(0));

                    // Time
                    participantData.put("teamId", participant.path("teamId").asInt(0));

                    // Stats da partida
                    JsonNode stats = participant.path("stats");
                    if (stats != null && !stats.isMissingNode()) {
                        participantData.put("win", stats.path("win").asBoolean(false));
                        participantData.put("kills", stats.path("kills").asInt(0));
                        participantData.put("deaths", stats.path("deaths").asInt(0));
                        participantData.put("assists", stats.path("assists").asInt(0));
                        participantData.put("champLevel", stats.path("champLevel").asInt(0));
                        participantData.put("totalDamageDealtToChampions",
                                stats.path("totalDamageDealtToChampions").asInt(0));
                        participantData.put("totalMinionsKilled", stats.path("totalMinionsKilled").asInt(0));
                        participantData.put("goldEarned", stats.path("goldEarned").asInt(0));
                        participantData.put("visionScore", stats.path("visionScore").asInt(0));

                        // Itens
                        participantData.put("item0", stats.path("item0").asInt(0));
                        participantData.put("item1", stats.path("item1").asInt(0));
                        participantData.put("item2", stats.path("item2").asInt(0));
                        participantData.put("item3", stats.path("item3").asInt(0));
                        participantData.put("item4", stats.path("item4").asInt(0));
                        participantData.put("item5", stats.path("item5").asInt(0));
                        participantData.put("item6", stats.path("item6").asInt(0));
                    }

                    // Spells
                    participantData.put("spell1Id", participant.path("spell1Id").asInt(0));
                    participantData.put("spell2Id", participant.path("spell2Id").asInt(0));

                    participantsList.add(participantData);
                }

                // Salvar como JSON no campo participants_data
                match.setParticipantsData(objectMapper.writeValueAsString(participantsList));
                log.info("✅ Dados de {} participantes salvos com sucesso", participantsList.size());
            } else {
                log.warn("⚠️ Partida LCU não contém dados de participantes!");
            }

            // Determinar time vencedor baseado nos participantes
            if (!participantsList.isEmpty()) {
                for (Map<String, Object> p : participantsList) {
                    if ((Boolean) p.get("win")) {
                        match.setWinnerTeam((Integer) p.get("teamId"));
                        break;
                    }
                }
            }

            match.setStatus("completed");
            match.setCompletedAt(Instant.now());

            matchRepository.save(match);
            temporaryVotes.remove(matchId);

            log.info("🎉 Partida {} vinculada com sucesso! LCU Game ID: {}", matchId, lcuGameId);
        } catch (Exception e) {
            log.error("❌ Erro ao vincular partida: {}", e.getMessage(), e);
            throw new IllegalStateException("Erro ao vincular partida", e);
        }
    }
}
