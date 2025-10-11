package br.com.lolmatchmaking.backend.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * ✅ Service de Lock para Game in Progress e Winner Vote
 * 
 * PROBLEMA RESOLVIDO:
 * - Múltiplas votações de vencedor simultâneas
 * - Confirmação de resultado sem coordenação
 * - Encerramento de partida duplicado
 * 
 * SOLUÇÃO:
 * - Lock de winner vote (coordenar votação)
 * - Lock de confirmação de resultado
 * - Lock de encerramento de partida
 * 
 * CHAVES REDIS:
 * - lock:game:{matchId}:winner_vote → Lock de votação
 * - lock:game:{matchId}:confirm → Lock de confirmação
 * - lock:game:{matchId}:end → Lock de encerramento
 * - state:game:{matchId}:votes:{summonerName} → Voto individual
 * - state:game:{matchId}:result → Resultado final
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    // Prefixos de chave
    private static final String GAME_VOTE_LOCK_PREFIX = "lock:game:";
    private static final String GAME_CONFIRM_LOCK_PREFIX = "lock:game:";
    private static final String GAME_END_LOCK_PREFIX = "lock:game:";
    private static final String GAME_STATE_PREFIX = "state:game:";

    // TTLs
    // ✅ CORRIGIDO: 5min → 30s (votação dura segundos, não minutos)
    private static final Duration VOTE_LOCK_TTL = Duration.ofSeconds(30); // 30 segundos para votar
    private static final Duration CONFIRM_LOCK_TTL = Duration.ofSeconds(30); // 30 segundos para confirmar
    private static final Duration END_LOCK_TTL = Duration.ofSeconds(10); // 10 segundos para encerrar
    // ✅ CORRIGIDO: 10min → 2min (cleanup mais rápido se órfão)
    private static final Duration VOTE_STATE_TTL = Duration.ofMinutes(2); // 2 minutos de votação

    /**
     * ✅ Adquire lock de winner vote
     * 
     * Previne múltiplas sessões de votação simultâneas.
     * 
     * @param matchId ID da partida
     * @return true se conseguiu iniciar votação, false se já está votando
     */
    public boolean acquireWinnerVoteLock(Long matchId) {
        String key = GAME_VOTE_LOCK_PREFIX + matchId + ":winner_vote";

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "voting", VOTE_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [GameLock] Winner vote lock adquirido: match {}", matchId);
                return true;
            }

            log.warn("⚠️ [GameLock] Winner vote já em andamento: match {}", matchId);
            return false;

        } catch (Exception e) {
            log.error("❌ [GameLock] Erro ao adquirir winner vote lock para match {}", matchId, e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de winner vote
     * 
     * @param matchId ID da partida
     */
    public void releaseWinnerVoteLock(Long matchId) {
        String key = GAME_VOTE_LOCK_PREFIX + matchId + ":winner_vote";

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("🔓 [GameLock] Winner vote lock liberado: match {}", matchId);
            }

        } catch (Exception e) {
            log.error("❌ [GameLock] Erro ao liberar winner vote lock de match {}", matchId, e);
        }
    }

    /**
     * ✅ Registra voto de jogador
     * 
     * Thread-safe: Usa lock para garantir consistência.
     * 
     * @param matchId      ID da partida
     * @param summonerName Nome do jogador
     * @param votedWinner  Time vencedor votado (1 ou 2)
     * @return true se voto registrado, false se já votou ou erro
     */
    public boolean registerVote(Long matchId, String summonerName, int votedWinner) {
        String voteKey = GAME_STATE_PREFIX + matchId + ":votes:" + summonerName;
        String lockKey = GAME_VOTE_LOCK_PREFIX + matchId + ":vote:" + summonerName;

        try {
            // Lock individual para este jogador votar
            Boolean lockAcquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "voting", Duration.ofSeconds(5));

            if (Boolean.FALSE.equals(lockAcquired)) {
                log.warn("⚠️ [GameLock] Jogador {} já está votando em match {}", summonerName, matchId);
                return false;
            }

            try {
                // Verificar se já votou
                if (redisTemplate.hasKey(voteKey)) {
                    log.warn("⚠️ [GameLock] Jogador {} já votou em match {}", summonerName, matchId);
                    return false;
                }

                // Registrar voto
                redisTemplate.opsForValue().set(voteKey, String.valueOf(votedWinner), VOTE_STATE_TTL);

                log.info("✅ [GameLock] Voto registrado: match {} {} votou em time {}",
                        matchId, summonerName, votedWinner);
                return true;

            } finally {
                redisTemplate.delete(lockKey);
            }

        } catch (Exception e) {
            log.error("❌ [GameLock] Erro ao registrar voto de {} em match {}", summonerName, matchId, e);
            return false;
        }
    }

    /**
     * ✅ Conta votos da partida
     * 
     * @param matchId ID da partida
     * @return Array com [votosTime1, votosTime2]
     */
    public int[] countVotes(Long matchId) {
        String pattern = GAME_STATE_PREFIX + matchId + ":votes:*";

        try {
            Set<String> voteKeys = redisTemplate.keys(pattern);

            int votesTeam1 = 0;
            int votesTeam2 = 0;

            if (voteKeys != null) {
                for (String key : voteKeys) {
                    Object vote = redisTemplate.opsForValue().get(key);
                    if (vote != null) {
                        int team = Integer.parseInt(vote.toString());
                        if (team == 1)
                            votesTeam1++;
                        else if (team == 2)
                            votesTeam2++;
                    }
                }
            }

            log.debug("📊 [GameLock] Contagem de votos match {}: Team1={}, Team2={}",
                    matchId, votesTeam1, votesTeam2);

            return new int[] { votesTeam1, votesTeam2 };

        } catch (Exception e) {
            log.error("❌ [GameLock] Erro ao contar votos de match {}", matchId, e);
            return new int[] { 0, 0 };
        }
    }

    /**
     * ✅ Adquire lock de confirmação de resultado
     * 
     * @param matchId ID da partida
     * @return true se conseguiu adquirir
     */
    public boolean acquireConfirmLock(Long matchId) {
        String key = GAME_CONFIRM_LOCK_PREFIX + matchId + ":confirm";

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "confirming", CONFIRM_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [GameLock] Confirm lock adquirido: match {}", matchId);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("❌ [GameLock] Erro ao adquirir confirm lock para match {}", matchId, e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de confirmação
     * 
     * @param matchId ID da partida
     */
    public void releaseConfirmLock(Long matchId) {
        String key = GAME_CONFIRM_LOCK_PREFIX + matchId + ":confirm";

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("🔓 [GameLock] Confirm lock liberado: match {}", matchId);
            }

        } catch (Exception e) {
            log.error("❌ [GameLock] Erro ao liberar confirm lock de match {}", matchId, e);
        }
    }

    /**
     * ✅ Adquire lock de encerramento de partida
     * 
     * Previne múltiplas tentativas de encerrar a mesma partida.
     * 
     * @param matchId ID da partida
     * @return true se conseguiu adquirir
     */
    public boolean acquireEndGameLock(Long matchId) {
        String key = GAME_END_LOCK_PREFIX + matchId + ":end";

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "ending", END_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [GameLock] End game lock adquirido: match {}", matchId);
                return true;
            }

            log.warn("⚠️ [GameLock] End game já em andamento: match {}", matchId);
            return false;

        } catch (Exception e) {
            log.error("❌ [GameLock] Erro ao adquirir end game lock para match {}", matchId, e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de encerramento
     * 
     * @param matchId ID da partida
     */
    public void releaseEndGameLock(Long matchId) {
        String key = GAME_END_LOCK_PREFIX + matchId + ":end";

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("🔓 [GameLock] End game lock liberado: match {}", matchId);
            }

        } catch (Exception e) {
            log.error("❌ [GameLock] Erro ao liberar end game lock de match {}", matchId, e);
        }
    }

    /**
     * ✅ Limpa todos os locks e votos da partida
     * 
     * Chamar quando partida terminar ou for cancelada.
     * 
     * @param matchId ID da partida
     */
    public void clearAllGameLocks(Long matchId) {
        try {
            releaseWinnerVoteLock(matchId);
            releaseConfirmLock(matchId);
            releaseEndGameLock(matchId);

            // Limpar todos os votos
            String votePattern = GAME_STATE_PREFIX + matchId + ":votes:*";
            Set<String> voteKeys = redisTemplate.keys(votePattern);
            if (voteKeys != null && !voteKeys.isEmpty()) {
                redisTemplate.delete(voteKeys);
            }

            // Limpar resultado
            String resultKey = GAME_STATE_PREFIX + matchId + ":result";
            redisTemplate.delete(resultKey);

            log.info("🗑️ [GameLock] Todos os locks e votos limpos: match {}", matchId);

        } catch (Exception e) {
            log.error("❌ [GameLock] Erro ao limpar locks de match {}", matchId, e);
        }
    }

    /**
     * ✅ Define resultado final da partida
     * 
     * @param matchId    ID da partida
     * @param winnerTeam Time vencedor (1 ou 2)
     */
    public void setGameResult(Long matchId, int winnerTeam) {
        String key = GAME_STATE_PREFIX + matchId + ":result";

        try {
            redisTemplate.opsForValue().set(key, String.valueOf(winnerTeam), Duration.ofHours(24));
            log.info("✅ [GameLock] Resultado definido: match {} → Time {} venceu", matchId, winnerTeam);

        } catch (Exception e) {
            log.error("❌ [GameLock] Erro ao definir resultado de match {}", matchId, e);
        }
    }

    /**
     * ✅ Obtém resultado final da partida
     * 
     * @param matchId ID da partida
     * @return Time vencedor (1 ou 2) ou -1 se não definido
     */
    public int getGameResult(Long matchId) {
        String key = GAME_STATE_PREFIX + matchId + ":result";

        try {
            Object result = redisTemplate.opsForValue().get(key);
            return result != null ? Integer.parseInt(result.toString()) : -1;

        } catch (Exception e) {
            log.error("❌ [GameLock] Erro ao obter resultado de match {}", matchId, e);
            return -1;
        }
    }
}
