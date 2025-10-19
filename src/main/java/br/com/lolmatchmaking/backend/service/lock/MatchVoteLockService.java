package br.com.lolmatchmaking.backend.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Serviço para gerenciar locks distribuídos de votação e vinculação de
 * partidas.
 * 
 * Previne race conditions em:
 * - Votação de vencedor (múltiplos votos simultâneos)
 * - Vinculação de match com LCU (múltiplas vinculações)
 * - Detecção de "6 votos atingidos" por múltiplas instâncias
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchVoteLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String VOTE_LOCK_PREFIX = "match:vote:lock";
    private static final String LINK_LOCK_PREFIX = "match:link:lock";
    private static final long VOTE_LOCK_TTL = 15; // segundos
    private static final long LINK_LOCK_TTL = 30; // segundos

    /**
     * Adquire lock para registrar voto de um jogador.
     * 
     * Previne:
     * - Duplo voto do mesmo jogador (duplo-clique)
     * - Múltiplas instâncias processando mesmo voto
     * - Race condition ao detectar "6 votos atingidos"
     * 
     * @param matchId  ID da partida
     * @param playerId ID do jogador
     * @return true se lock foi adquirido, false se já existe
     */
    public boolean acquireVoteLock(Long matchId, Long playerId) {
        try {
            String lockKey = VOTE_LOCK_PREFIX + ":" + matchId + ":" + playerId;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "locked", Duration.ofSeconds(VOTE_LOCK_TTL));

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("🔒 [MatchVoteLock] Lock de voto adquirido: match={}, player={}",
                        matchId, playerId);
                return true;
            }

            log.debug("⏳ [MatchVoteLock] Lock de voto já existe: match={}, player={}",
                    matchId, playerId);
            return false;
        } catch (Exception e) {
            log.error("❌ [MatchVoteLock] Erro ao adquirir lock de voto: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Libera lock de voto.
     * 
     * DEVE ser chamado sempre em finally block para garantir liberação.
     * 
     * @param matchId  ID da partida
     * @param playerId ID do jogador
     */
    public void releaseVoteLock(Long matchId, Long playerId) {
        try {
            String lockKey = VOTE_LOCK_PREFIX + ":" + matchId + ":" + playerId;
            redisTemplate.delete(lockKey);
            log.debug("🔓 [MatchVoteLock] Lock de voto liberado: match={}, player={}",
                    matchId, playerId);
        } catch (Exception e) {
            log.error("❌ [MatchVoteLock] Erro ao liberar lock de voto: {}", e.getMessage());
        }
    }

    /**
     * Adquire lock para vincular partida com dados do LCU.
     * 
     * Previne:
     * - Múltiplas vinculações simultâneas
     * - Duplo cálculo de LP
     * - Dupla atualização do banco
     * - Dupla limpeza de canais Discord
     * 
     * @param matchId ID da partida
     * @return true se lock foi adquirido, false se já existe
     */
    public boolean acquireLinkLock(Long matchId) {
        try {
            String lockKey = LINK_LOCK_PREFIX + ":" + matchId;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "locked", Duration.ofSeconds(LINK_LOCK_TTL));

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("🔒 [MatchVoteLock] Lock de vinculação adquirido: matchId={}", matchId);
                return true;
            }

            log.debug("⏳ [MatchVoteLock] Lock de vinculação já existe: matchId={}", matchId);
            return false;
        } catch (Exception e) {
            log.error("❌ [MatchVoteLock] Erro ao adquirir lock de vinculação: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Libera lock de vinculação.
     * 
     * DEVE ser chamado sempre em finally block para garantir liberação.
     * 
     * @param matchId ID da partida
     */
    public void releaseLinkLock(Long matchId) {
        try {
            String lockKey = LINK_LOCK_PREFIX + ":" + matchId;
            redisTemplate.delete(lockKey);
            log.debug("🔓 [MatchVoteLock] Lock de vinculação liberado: matchId={}", matchId);
        } catch (Exception e) {
            log.error("❌ [MatchVoteLock] Erro ao liberar lock de vinculação: {}", e.getMessage());
        }
    }

    /**
     * Verifica se existe lock de voto ativo.
     * 
     * @param matchId  ID da partida
     * @param playerId ID do jogador
     * @return true se lock existe
     */
    public boolean isVoteLocked(Long matchId, Long playerId) {
        try {
            String lockKey = VOTE_LOCK_PREFIX + ":" + matchId + ":" + playerId;
            Boolean exists = redisTemplate.hasKey(lockKey);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("❌ [MatchVoteLock] Erro ao verificar lock de voto: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verifica se existe lock de vinculação ativo.
     * 
     * @param matchId ID da partida
     * @return true se lock existe
     */
    public boolean isLinkLocked(Long matchId) {
        try {
            String lockKey = LINK_LOCK_PREFIX + ":" + matchId;
            Boolean exists = redisTemplate.hasKey(lockKey);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("❌ [MatchVoteLock] Erro ao verificar lock de vinculação: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Força liberação do lock de voto (usar apenas em emergência).
     * 
     * @param matchId  ID da partida
     * @param playerId ID do jogador
     */
    public void forceReleaseVoteLock(Long matchId, Long playerId) {
        try {
            String lockKey = VOTE_LOCK_PREFIX + ":" + matchId + ":" + playerId;
            redisTemplate.delete(lockKey);
            log.warn("⚠️ [MatchVoteLock] Lock de voto FORÇADAMENTE liberado: match={}, player={}",
                    matchId, playerId);
        } catch (Exception e) {
            log.error("❌ [MatchVoteLock] Erro ao forçar liberação de lock de voto: {}",
                    e.getMessage());
        }
    }

    /**
     * Força liberação do lock de vinculação (usar apenas em emergência).
     * 
     * @param matchId ID da partida
     */
    public void forceReleaseLinkLock(Long matchId) {
        try {
            String lockKey = LINK_LOCK_PREFIX + ":" + matchId;
            redisTemplate.delete(lockKey);
            log.warn("⚠️ [MatchVoteLock] Lock de vinculação FORÇADAMENTE liberado: matchId={}",
                    matchId);
        } catch (Exception e) {
            log.error("❌ [MatchVoteLock] Erro ao forçar liberação de lock de vinculação: {}",
                    e.getMessage());
        }
    }
}
