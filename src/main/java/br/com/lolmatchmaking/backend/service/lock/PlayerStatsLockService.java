package br.com.lolmatchmaking.backend.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Serviço para gerenciar locks distribuídos de atualização de estatísticas de
 * jogadores.
 * 
 * Previne race conditions em:
 * - Atualização de stats quando múltiplas partidas terminam simultaneamente
 * - Lost update problem em LP, wins, losses, etc
 * - Cálculo incorreto de estatísticas
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerStatsLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String STATS_LOCK_PREFIX = "player:stats:lock";
    private static final long LOCK_TTL = 15; // segundos

    /**
     * Adquire lock para atualizar estatísticas de um jogador.
     * 
     * Previne:
     * - Lost update problem quando múltiplas partidas terminam ao mesmo tempo
     * - Stats incorretos (LP, wins, losses, streak, etc)
     * - Race condition em cálculos de MMR
     * 
     * @param playerId ID do jogador
     * @return true se lock foi adquirido, false se já existe
     */
    public boolean acquireStatsLock(Long playerId) {
        try {
            String lockKey = STATS_LOCK_PREFIX + ":" + playerId;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "locked", Duration.ofSeconds(LOCK_TTL));

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("🔒 [PlayerStatsLock] Lock de stats adquirido: playerId={}", playerId);
                return true;
            }

            log.debug("⏳ [PlayerStatsLock] Lock de stats já existe: playerId={}", playerId);
            return false;
        } catch (Exception e) {
            log.error("❌ [PlayerStatsLock] Erro ao adquirir lock de stats: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Adquire lock para atualizar estatísticas usando summonerName.
     * 
     * @param summonerName Nome do jogador
     * @return true se lock foi adquirido, false se já existe
     */
    public boolean acquireStatsLockBySummoner(String summonerName) {
        try {
            String lockKey = STATS_LOCK_PREFIX + ":summoner:" + summonerName;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "locked", Duration.ofSeconds(LOCK_TTL));

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("🔒 [PlayerStatsLock] Lock de stats adquirido: summoner={}", summonerName);
                return true;
            }

            log.debug("⏳ [PlayerStatsLock] Lock de stats já existe: summoner={}", summonerName);
            return false;
        } catch (Exception e) {
            log.error("❌ [PlayerStatsLock] Erro ao adquirir lock de stats: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Libera lock de atualização de stats.
     * 
     * DEVE ser chamado sempre em finally block para garantir liberação.
     * 
     * @param playerId ID do jogador
     */
    public void releaseStatsLock(Long playerId) {
        try {
            String lockKey = STATS_LOCK_PREFIX + ":" + playerId;
            redisTemplate.delete(lockKey);
            log.debug("🔓 [PlayerStatsLock] Lock de stats liberado: playerId={}", playerId);
        } catch (Exception e) {
            log.error("❌ [PlayerStatsLock] Erro ao liberar lock de stats: {}", e.getMessage());
        }
    }

    /**
     * Libera lock de atualização de stats usando summonerName.
     * 
     * @param summonerName Nome do jogador
     */
    public void releaseStatsLockBySummoner(String summonerName) {
        try {
            String lockKey = STATS_LOCK_PREFIX + ":summoner:" + summonerName;
            redisTemplate.delete(lockKey);
            log.debug("🔓 [PlayerStatsLock] Lock de stats liberado: summoner={}", summonerName);
        } catch (Exception e) {
            log.error("❌ [PlayerStatsLock] Erro ao liberar lock de stats: {}", e.getMessage());
        }
    }

    /**
     * Executa atualização de stats com lock automático.
     * 
     * Helper method que adquire lock, executa a operação e libera o lock.
     * 
     * @param playerId        ID do jogador
     * @param updateOperation Operação de atualização a ser executada
     * @return true se operação foi executada, false se lock não pôde ser adquirido
     */
    public boolean updateStatsWithLock(Long playerId, Runnable updateOperation) {
        if (!acquireStatsLock(playerId)) {
            log.warn("⏳ [PlayerStatsLock] Aguardando lock de stats: playerId={}", playerId);
            return false;
        }

        try {
            updateOperation.run();
            return true;
        } finally {
            releaseStatsLock(playerId);
        }
    }

    /**
     * Executa atualização de stats com lock automático usando summonerName.
     * 
     * @param summonerName    Nome do jogador
     * @param updateOperation Operação de atualização a ser executada
     * @return true se operação foi executada, false se lock não pôde ser adquirido
     */
    public boolean updateStatsWithLockBySummoner(String summonerName, Runnable updateOperation) {
        if (!acquireStatsLockBySummoner(summonerName)) {
            log.warn("⏳ [PlayerStatsLock] Aguardando lock de stats: summoner={}", summonerName);
            return false;
        }

        try {
            updateOperation.run();
            return true;
        } finally {
            releaseStatsLockBySummoner(summonerName);
        }
    }

    /**
     * Verifica se existe lock de stats ativo.
     * 
     * @param playerId ID do jogador
     * @return true se lock existe
     */
    public boolean isStatsLocked(Long playerId) {
        try {
            String lockKey = STATS_LOCK_PREFIX + ":" + playerId;
            Boolean exists = redisTemplate.hasKey(lockKey);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("❌ [PlayerStatsLock] Erro ao verificar lock de stats: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Força liberação do lock de stats (usar apenas em emergência).
     * 
     * @param playerId ID do jogador
     */
    public void forceReleaseStatsLock(Long playerId) {
        try {
            String lockKey = STATS_LOCK_PREFIX + ":" + playerId;
            redisTemplate.delete(lockKey);
            log.warn("⚠️ [PlayerStatsLock] Lock de stats FORÇADAMENTE liberado: playerId={}",
                    playerId);
        } catch (Exception e) {
            log.error("❌ [PlayerStatsLock] Erro ao forçar liberação de lock de stats: {}",
                    e.getMessage());
        }
    }
}
