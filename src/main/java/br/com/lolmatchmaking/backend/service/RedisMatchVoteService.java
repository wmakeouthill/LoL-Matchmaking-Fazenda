package br.com.lolmatchmaking.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Serviço de gerenciamento de votos para vinculação de partidas usando Redis
 * distribuído.
 * 
 * Garante que votos de jogadores sejam sincronizados entre múltiplas instâncias
 * do backend, evitando contagens duplicadas ou inconsistentes.
 * 
 * Usa:
 * - Redis Hash para mapear playerId → lcuGameId
 * - Redis Hash para contagem de votos por lcuGameId
 * - Redisson Distributed Lock para sincronização
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisMatchVoteService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    private static final Duration VOTE_TTL = Duration.ofHours(2);
    private static final String VOTE_PREFIX = "match_vote:";

    /**
     * Inicializa votação para uma partida
     */
    public void initializeVoting(Long matchId) {
        String key = getVoteKey(matchId);

        log.info("🗳️ Inicializando votação para partida {} no Redis", matchId);

        // Criar estrutura inicial
        Map<String, Object> voteData = new HashMap<>();
        voteData.put("matchId", matchId);
        voteData.put("startTime", System.currentTimeMillis());

        redisTemplate.opsForHash().putAll(key + ":metadata", voteData);
        redisTemplate.expire(key + ":metadata", VOTE_TTL);

        log.info("✅ Votação inicializada para partida {}", matchId);
    }

    /**
     * Registra voto de um jogador (com distributed lock)
     */
    public boolean registerVote(Long matchId, Long playerId, Long lcuGameId) {
        RLock lock = redissonClient.getLock("match_vote_lock:" + matchId);

        try {
            // Tentar adquirir lock por até 5 segundos
            if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                try {
                    String key = getVoteKey(matchId);

                    log.info("🗳️ Registrando voto: matchId={}, playerId={}, lcuGameId={}",
                            matchId, playerId, lcuGameId);

                    // Verificar se jogador já votou
                    Object existingVote = redisTemplate.opsForHash().get(key + ":player_votes", playerId.toString());
                    if (existingVote != null) {
                        Long oldLcuGameId = ((Number) existingVote).longValue();
                        if (oldLcuGameId.equals(lcuGameId)) {
                            log.info("ℹ️ Jogador {} já votou em lcuGameId={}", playerId, lcuGameId);
                            return true; // Já votou no mesmo
                        }

                        // Remover voto anterior da contagem
                        String oldCountKey = key + ":vote_counts";
                        Long oldCount = (Long) redisTemplate.opsForHash().get(oldCountKey, oldLcuGameId.toString());
                        if (oldCount != null && oldCount > 0) {
                            redisTemplate.opsForHash().put(oldCountKey, oldLcuGameId.toString(), oldCount - 1);
                        }

                        log.info("🔄 Jogador {} mudou voto de {} para {}", playerId, oldLcuGameId, lcuGameId);
                    }

                    // Registrar novo voto
                    redisTemplate.opsForHash().put(key + ":player_votes", playerId.toString(), lcuGameId);

                    // Incrementar contagem para o lcuGameId
                    redisTemplate.opsForHash().increment(key + ":vote_counts", lcuGameId.toString(), 1);

                    // Atualizar TTLs
                    redisTemplate.expire(key + ":player_votes", VOTE_TTL);
                    redisTemplate.expire(key + ":vote_counts", VOTE_TTL);

                    // Buscar contagem atualizada
                    Object voteCountObj = redisTemplate.opsForHash().get(key + ":vote_counts", lcuGameId.toString());
                    Long voteCount = voteCountObj instanceof Long ? (Long) voteCountObj
                            : voteCountObj instanceof Integer ? ((Integer) voteCountObj).longValue() : 0L;

                    log.info("✅ Voto registrado! lcuGameId={} agora tem {} voto(s)", lcuGameId, voteCount);

                    return true;

                } finally {
                    lock.unlock();
                }
            } else {
                log.warn("⚠️ Não foi possível adquirir lock para registrar voto na partida {}", matchId);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Thread interrompida ao registrar voto", e);
            return false;
        } catch (Exception e) {
            log.error("❌ Erro ao registrar voto no Redis", e);
            return false;
        }
    }

    /**
     * Remove voto de um jogador
     */
    public boolean removeVote(Long matchId, Long playerId) {
        RLock lock = redissonClient.getLock("match_vote_lock:" + matchId);

        try {
            if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                try {
                    String key = getVoteKey(matchId);

                    // Buscar voto anterior
                    Object existingVote = redisTemplate.opsForHash().get(key + ":player_votes", playerId.toString());
                    if (existingVote == null) {
                        log.info("ℹ️ Jogador {} não tinha voto para remover", playerId);
                        return true;
                    }

                    Long lcuGameId = ((Number) existingVote).longValue();

                    // Remover voto do jogador
                    redisTemplate.opsForHash().delete(key + ":player_votes", playerId.toString());

                    // Decrementar contagem
                    Long count = (Long) redisTemplate.opsForHash().get(key + ":vote_counts", lcuGameId.toString());
                    if (count != null && count > 0) {
                        redisTemplate.opsForHash().put(key + ":vote_counts", lcuGameId.toString(), count - 1);
                    }

                    log.info("✅ Voto removido: playerId={}, lcuGameId={}", playerId, lcuGameId);
                    return true;

                } finally {
                    lock.unlock();
                }
            } else {
                log.warn("⚠️ Não foi possível adquirir lock para remover voto");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Thread interrompida ao remover voto", e);
            return false;
        } catch (Exception e) {
            log.error("❌ Erro ao remover voto do Redis", e);
            return false;
        }
    }

    /**
     * Obtém contagem de votos por lcuGameId
     */
    public Map<Long, Long> getVoteCounts(Long matchId) {
        String key = getVoteKey(matchId);
        Map<Object, Object> counts = redisTemplate.opsForHash().entries(key + ":vote_counts");

        if (counts == null || counts.isEmpty()) {
            return new HashMap<>();
        }

        return counts.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> Long.parseLong(e.getKey().toString()),
                        e -> ((Number) e.getValue()).longValue()));
    }

    /**
     * Obtém voto de um jogador específico
     */
    public Long getPlayerVote(Long matchId, Long playerId) {
        String key = getVoteKey(matchId);
        Object vote = redisTemplate.opsForHash().get(key + ":player_votes", playerId.toString());

        return vote != null ? ((Number) vote).longValue() : null;
    }

    /**
     * Verifica se um lcuGameId atingiu o número mínimo de votos
     */
    public boolean hasRequiredVotes(Long matchId, Long lcuGameId, int requiredVotes) {
        String key = getVoteKey(matchId);
        Object count = redisTemplate.opsForHash().get(key + ":vote_counts", lcuGameId.toString());

        if (count == null) {
            return false;
        }

        long voteCount = ((Number) count).longValue();
        return voteCount >= requiredVotes;
    }

    /**
     * Obtém lcuGameId com mais votos
     */
    public Optional<Long> getWinningLcuGameId(Long matchId, int requiredVotes) {
        Map<Long, Long> voteCounts = getVoteCounts(matchId);

        return voteCounts.entrySet().stream()
                .filter(e -> e.getValue() >= requiredVotes)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    /**
     * Obtém todos os votos dos jogadores
     */
    public Map<Long, Long> getAllPlayerVotes(Long matchId) {
        String key = getVoteKey(matchId);
        Map<Object, Object> votes = redisTemplate.opsForHash().entries(key + ":player_votes");

        if (votes == null || votes.isEmpty()) {
            return new HashMap<>();
        }

        return votes.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> Long.parseLong(e.getKey().toString()),
                        e -> ((Number) e.getValue()).longValue()));
    }

    /**
     * Obtém total de jogadores que votaram
     */
    public int getTotalVoters(Long matchId) {
        String key = getVoteKey(matchId);
        Long size = redisTemplate.opsForHash().size(key + ":player_votes");
        return size != null ? size.intValue() : 0;
    }

    /**
     * Limpa todos os votos de uma partida
     */
    public void clearVotes(Long matchId) {
        String key = getVoteKey(matchId);

        log.info("🧹 Limpando votos da partida {}", matchId);

        redisTemplate.delete(key + ":metadata");
        redisTemplate.delete(key + ":player_votes");
        redisTemplate.delete(key + ":vote_counts");

        log.info("✅ Votos limpos para partida {}", matchId);
    }

    /**
     * Obtém status completo da votação
     */
    public Map<String, Object> getVotingStatus(Long matchId) {
        String key = getVoteKey(matchId);

        Map<String, Object> status = new HashMap<>();
        status.put("matchId", matchId);
        status.put("totalVoters", getTotalVoters(matchId));
        status.put("voteCounts", getVoteCounts(matchId));
        status.put("playerVotes", getAllPlayerVotes(matchId));
        status.put("metadata", redisTemplate.opsForHash().entries(key + ":metadata"));

        return status;
    }

    /**
     * ✅ NOVO: Obter lista de nomes dos jogadores que votaram
     */
    public List<String> getVotedPlayerNames(Long matchId) {
        String key = getVoteKey(matchId);
        Map<Object, Object> votes = redisTemplate.opsForHash().entries(key + ":player_votes");

        if (votes == null || votes.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> votedPlayerNames = new ArrayList<>();

        // Converter playerId para playerName
        for (Map.Entry<Object, Object> entry : votes.entrySet()) {
            Long playerId = Long.parseLong(entry.getKey().toString());
            Object voteInfo = entry.getValue();

            if (voteInfo != null) {
                // Buscar nome do jogador pelo ID
                try {
                    // TODO: Implementar busca de playerName por playerId via PlayerRepository
                    // Por enquanto, usar playerId como string
                    votedPlayerNames.add("Player_" + playerId);
                } catch (Exception e) {
                    log.warn("⚠️ [RedisMatchVote] Erro ao buscar nome do jogador {}: {}", playerId, e.getMessage());
                }
            }
        }

        return votedPlayerNames;
    }

    /**
     * ✅ NOVO: Obter contagem de votos de um special user
     */
    public int getSpecialUserVoteCount(Long matchId, String summonerName) {
        String key = getVoteKey(matchId);
        Object count = redisTemplate.opsForHash().get(key + ":special_votes", summonerName);

        if (count == null) {
            return 0;
        }

        return ((Number) count).intValue();
    }

    /**
     * ✅ NOVO: Adicionar voto de special user
     */
    public void addSpecialUserVote(Long matchId, String summonerName, Long lcuGameId) {
        String key = getVoteKey(matchId);

        // Incrementar contagem de votos do special user
        redisTemplate.opsForHash().increment(key + ":special_votes", summonerName, 1);

        // Adicionar à lista de votos do special user
        redisTemplate.opsForSet().add(key + ":special_vote_details:" + summonerName, lcuGameId.toString());

        // Incrementar contagem geral do lcuGameId
        redisTemplate.opsForHash().increment(key + ":vote_counts", lcuGameId.toString(), 1);

        log.info("✅ [RedisMatchVote] Special user {} votou em lcuGameId {} para match {}",
                summonerName, lcuGameId, matchId);
    }

    /**
     * ✅ NOVO: Obter lista de votos de um special user
     */
    public Set<String> getSpecialUserVotes(Long matchId, String summonerName) {
        String key = getVoteKey(matchId);
        Set<Object> rawMembers = redisTemplate.opsForSet().members(key + ":special_vote_details:" + summonerName);

        if (rawMembers == null) {
            return new HashSet<>();
        }

        return rawMembers.stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private String getVoteKey(Long matchId) {
        return VOTE_PREFIX + matchId;
    }
}
