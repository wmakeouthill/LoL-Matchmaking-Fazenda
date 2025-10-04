package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.Match;
import br.com.lolmatchmaking.backend.domain.entity.MatchVote;
import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.MatchRepository;
import br.com.lolmatchmaking.backend.domain.repository.MatchVoteRepository;
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
import java.util.stream.Collectors;

/**
 * Service responsável pelo gerenciamento de votos em partidas do LCU
 * e vinculação automática quando 5 votos são atingidos.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchVoteService {

    private final MatchVoteRepository matchVoteRepository;
    private final MatchRepository matchRepository;
    private final PlayerRepository playerRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final ObjectMapper objectMapper;

    /**
     * Número mínimo de votos para vincular automaticamente uma partida
     */
    private static final int VOTES_REQUIRED_FOR_AUTO_LINK = 5;

    /**
     * Processa um voto de um jogador em uma partida do LCU
     * 
     * @param matchId   ID da partida customizada
     * @param playerId  ID do jogador votando
     * @param lcuGameId ID da partida do LCU escolhida
     * @return Map com contagem de votos atualizada
     */
    @Transactional
    public Map<String, Object> processVote(Long matchId, Long playerId, Long lcuGameId) {
        log.info("🗳️ Processando voto: matchId={}, playerId={}, lcuGameId={}",
                matchId, playerId, lcuGameId);

        // 1. Validar se a partida existe e está no status correto
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Partida não encontrada: " + matchId));

        if (!"game_in_progress".equals(match.getStatus()) && !"ended".equals(match.getStatus())) {
            throw new IllegalStateException("Partida não está disponível para votação. Status: " + match.getStatus());
        }

        // 2. Validar se o jogador existe
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Jogador não encontrado: " + playerId));

        // 3. Verificar se o jogador pertence à partida
        if (!isPlayerInMatch(match, player)) {
            throw new IllegalArgumentException("Jogador " + player.getSummonerName() + " não está nesta partida");
        }

        // 4. Salvar ou atualizar voto
        MatchVote vote = matchVoteRepository.findByMatchIdAndPlayerId(matchId, playerId)
                .orElse(MatchVote.builder()
                        .matchId(matchId)
                        .playerId(playerId)
                        .build());

        vote.setLcuGameId(lcuGameId);
        vote.setVotedAt(Instant.now());
        matchVoteRepository.save(vote);

        log.info("✅ Voto registrado: {} votou em lcuGameId={}", player.getSummonerName(), lcuGameId);

        // 5. Contar votos
        Map<Long, Long> voteCount = countVotesByLcuGameId(matchId);
        log.info("📊 Contagem de votos atual: {}", voteCount);

        // 6. Broadcast atualização via WebSocket
        broadcastVoteUpdate(matchId, voteCount);

        // 7. Verificar se alguma partida atingiu o número mínimo de votos
        Optional<Long> winningLcuGameId = voteCount.entrySet().stream()
                .filter(entry -> entry.getValue() >= VOTES_REQUIRED_FOR_AUTO_LINK)
                .map(Map.Entry::getKey)
                .findFirst();

        if (winningLcuGameId.isPresent()) {
            log.info("🎯 5 votos atingidos! Vinculando automaticamente lcuGameId={}", winningLcuGameId.get());
            // TODO: Implementar vinculação em controller separado para evitar problemas
            // transacionais
            // linkMatchAutomatically(matchId, winningLcuGameId.get());
        }

        // 8. Retornar resultado
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("votes", voteCount);
        result.put("playerVote", lcuGameId);
        result.put("shouldLink", winningLcuGameId.isPresent());
        if (winningLcuGameId.isPresent()) {
            result.put("linkedLcuGameId", winningLcuGameId.get());
        }
        return result;
    }

    /**
     * Conta quantos votos cada partida do LCU recebeu
     */
    public Map<Long, Long> countVotesByLcuGameId(Long matchId) {
        List<MatchVoteRepository.VoteCount> voteCounts = matchVoteRepository.countVotesByLcuGameId(matchId);

        return voteCounts.stream()
                .collect(Collectors.toMap(
                        MatchVoteRepository.VoteCount::getLcuGameId,
                        MatchVoteRepository.VoteCount::getVoteCount));
    }

    /**
     * Vincula uma partida do LCU manualmente (será chamado pelo controller)
     */
    @Transactional
    public void linkMatch(Long matchId, Long lcuGameId, JsonNode lcuMatchData) {
        log.info("🔗 Vinculando partida: matchId={}, lcuGameId={}", matchId, lcuGameId);

        try {
            // 1. Detectar vencedor da partida
            String winner = detectWinnerFromLcuData(lcuMatchData);
            log.info("🏆 Vencedor detectado: {}", winner);

            // 2. Atualizar Match no banco
            Match match = matchRepository.findById(matchId)
                    .orElseThrow(() -> new IllegalArgumentException("Partida não encontrada: " + matchId));

            match.setLinkedLcuGameId(lcuGameId);
            match.setLcuMatchData(objectMapper.writeValueAsString(lcuMatchData));
            match.setStatus("completed");
            match.setCompletedAt(Instant.now());

            // Converter winner para winnerTeam (1 = blue, 2 = red)
            if ("blue".equalsIgnoreCase(winner)) {
                match.setWinnerTeam(1);
            } else if ("red".equalsIgnoreCase(winner)) {
                match.setWinnerTeam(2);
            }

            matchRepository.save(match);
            log.info("✅ Partida atualizada no banco com sucesso");

            // 3. Broadcast conclusão via WebSocket
            broadcastMatchLinked(matchId, lcuGameId, winner);

        } catch (Exception e) {
            log.error("❌ Erro ao vincular partida", e);
            throw new IllegalStateException("Erro ao vincular partida: " + e.getMessage(), e);
        }
    }

    /**
     * Envia atualização de votos via WebSocket para todos jogadores da partida
     */
    private void broadcastVoteUpdate(Long matchId, Map<Long, Long> voteCount) {
        try {
            Map<String, Object> voteUpdate = new HashMap<>();
            voteUpdate.put("matchId", matchId);
            voteUpdate.put("votes", voteCount);
            voteUpdate.put("total", voteCount.values().stream().mapToLong(Long::longValue).sum());

            // Broadcast para todos os clientes conectados
            // Nota: Em uma implementação futura, pode-se filtrar apenas os jogadores da
            // partida
            webSocketService.broadcastToAll("match_vote_update", voteUpdate);
            log.info("📢 Atualização de votos enviada via WebSocket para partida {}", matchId);
        } catch (Exception e) {
            log.error("❌ Erro ao enviar atualização de votos", e);
        }
    }

    /**
     * Envia evento de partida vinculada via WebSocket
     */
    private void broadcastMatchLinked(Long matchId, Long lcuGameId, String winner) {
        try {
            Map<String, Object> linkedEvent = new HashMap<>();
            linkedEvent.put("matchId", matchId);
            linkedEvent.put("lcuGameId", lcuGameId);
            linkedEvent.put("winner", winner);
            linkedEvent.put("message", "Partida vinculada com sucesso!");

            webSocketService.broadcastToAll("match_linked", linkedEvent);
            log.info("📢 Evento 'match_linked' enviado via WebSocket");
        } catch (Exception e) {
            log.error("❌ Erro ao enviar evento de partida vinculada", e);
        }
    }

    /**
     * Detecta o vencedor a partir dos dados da partida do LCU
     * 
     * @param lcuMatchData Dados completos da partida do LCU
     * @return "blue" se Team 100 venceu, "red" se Team 200 venceu, null se não
     *         detectado
     */
    private String detectWinnerFromLcuData(JsonNode lcuMatchData) {
        if (lcuMatchData == null || !lcuMatchData.has("teams")) {
            return null;
        }

        JsonNode teams = lcuMatchData.get("teams");

        for (JsonNode team : teams) {
            int teamId = team.has("teamId") ? team.get("teamId").asInt() : 0;
            String win = team.has("win") ? team.get("win").asText() : "";

            boolean isWinner = "Win".equalsIgnoreCase(win) ||
                    "true".equalsIgnoreCase(win) ||
                    team.get("win").asBoolean(false);

            if (isWinner) {
                if (teamId == 100) {
                    return "blue";
                } else if (teamId == 200) {
                    return "red";
                }
            }
        }

        return null;
    }

    /**
     * Verifica se um jogador pertence a uma partida
     */
    private boolean isPlayerInMatch(Match match, Player player) {
        try {
            String summonerName = player.getSummonerName().toLowerCase();

            // Verificar em team1
            if (match.getTeam1PlayersJson() != null) {
                JsonNode team1 = objectMapper.readTree(match.getTeam1PlayersJson());
                if (team1.isArray()) {
                    for (JsonNode p : team1) {
                        if (p.has("summonerName") &&
                                summonerName.equals(p.get("summonerName").asText().toLowerCase())) {
                            return true;
                        }
                    }
                }
            }

            // Verificar em team2
            if (match.getTeam2PlayersJson() != null) {
                JsonNode team2 = objectMapper.readTree(match.getTeam2PlayersJson());
                if (team2.isArray()) {
                    for (JsonNode p : team2) {
                        if (p.has("summonerName") &&
                                summonerName.equals(p.get("summonerName").asText().toLowerCase())) {
                            return true;
                        }
                    }
                }
            }

            return false;
        } catch (Exception e) {
            log.error("Erro ao verificar se jogador está na partida", e);
            return false;
        }
    }

    /**
     * Remove o voto de um jogador
     */
    @Transactional
    public void removeVote(Long matchId, Long playerId) {
        log.info("🗑️ Removendo voto: matchId={}, playerId={}", matchId, playerId);
        matchVoteRepository.deleteByMatchIdAndPlayerId(matchId, playerId);

        // Broadcast atualização
        Map<Long, Long> voteCount = countVotesByLcuGameId(matchId);
        broadcastVoteUpdate(matchId, voteCount);
    }

    /**
     * Retorna os votos atuais de uma partida
     */
    public Map<String, Object> getMatchVotes(Long matchId) {
        Map<Long, Long> voteCount = countVotesByLcuGameId(matchId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("votes", voteCount);
        result.put("total", voteCount.values().stream().mapToLong(Long::longValue).sum());

        return result;
    }
}
