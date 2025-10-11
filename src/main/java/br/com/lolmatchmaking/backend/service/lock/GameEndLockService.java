package br.com.lolmatchmaking.backend.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Serviço para gerenciar locks distribuídos de finalização e cancelamento de
 * partidas.
 * 
 * Previne race conditions em:
 * - Finalização de partida (múltiplas instâncias finalizando simultaneamente)
 * - Cancelamento de partida (múltiplas instâncias cancelando simultaneamente)
 * - Salvamento duplo no banco de dados
 * - Exclusão duplicada de registros
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameEndLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String FINISH_LOCK_PREFIX = "game:finish:lock";
    private static final String CANCEL_LOCK_PREFIX = "game:cancel:lock";
    private static final long LOCK_TTL = 30; // segundos

    /**
     * Adquire lock para finalizar partida.
     * 
     * Previne múltiplas instâncias do backend finalizando a mesma partida
     * simultaneamente, o que poderia causar:
     * - Duplo cálculo de LP
     * - Dupla atualização do banco
     * - Dupla exclusão do registro
     * - Logs inconsistentes
     * 
     * @param matchId ID da partida
     * @return true se lock foi adquirido, false se já existe
     */
    public boolean acquireFinishLock(Long matchId) {
        try {
            String lockKey = FINISH_LOCK_PREFIX + ":" + matchId;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "locked", Duration.ofSeconds(LOCK_TTL));

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("🔒 [GameEndLock] Lock de finalização adquirido: matchId={}", matchId);
                return true;
            }

            log.debug("⏳ [GameEndLock] Lock de finalização já existe: matchId={}", matchId);
            return false;
        } catch (Exception e) {
            log.error("❌ [GameEndLock] Erro ao adquirir lock de finalização: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Libera lock de finalização de partida.
     * 
     * DEVE ser chamado sempre em finally block para garantir liberação.
     * 
     * @param matchId ID da partida
     */
    public void releaseFinishLock(Long matchId) {
        try {
            String lockKey = FINISH_LOCK_PREFIX + ":" + matchId;
            redisTemplate.delete(lockKey);
            log.debug("🔓 [GameEndLock] Lock de finalização liberado: matchId={}", matchId);
        } catch (Exception e) {
            log.error("❌ [GameEndLock] Erro ao liberar lock de finalização: {}", e.getMessage());
        }
    }

    /**
     * Adquire lock para cancelar partida.
     * 
     * Previne múltiplas instâncias cancelando a mesma partida simultaneamente.
     * 
     * @param matchId ID da partida
     * @return true se lock foi adquirido, false se já existe
     */
    public boolean acquireCancelLock(Long matchId) {
        try {
            String lockKey = CANCEL_LOCK_PREFIX + ":" + matchId;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "locked", Duration.ofSeconds(LOCK_TTL));

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("🔒 [GameEndLock] Lock de cancelamento adquirido: matchId={}", matchId);
                return true;
            }

            log.debug("⏳ [GameEndLock] Lock de cancelamento já existe: matchId={}", matchId);
            return false;
        } catch (Exception e) {
            log.error("❌ [GameEndLock] Erro ao adquirir lock de cancelamento: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Libera lock de cancelamento de partida.
     * 
     * DEVE ser chamado sempre em finally block para garantir liberação.
     * 
     * @param matchId ID da partida
     */
    public void releaseCancelLock(Long matchId) {
        try {
            String lockKey = CANCEL_LOCK_PREFIX + ":" + matchId;
            redisTemplate.delete(lockKey);
            log.debug("🔓 [GameEndLock] Lock de cancelamento liberado: matchId={}", matchId);
        } catch (Exception e) {
            log.error("❌ [GameEndLock] Erro ao liberar lock de cancelamento: {}", e.getMessage());
        }
    }

    /**
     * Verifica se existe lock de finalização ativo.
     * 
     * @param matchId ID da partida
     * @return true se lock existe
     */
    public boolean isFinishLocked(Long matchId) {
        try {
            String lockKey = FINISH_LOCK_PREFIX + ":" + matchId;
            Boolean exists = redisTemplate.hasKey(lockKey);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("❌ [GameEndLock] Erro ao verificar lock de finalização: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verifica se existe lock de cancelamento ativo.
     * 
     * @param matchId ID da partida
     * @return true se lock existe
     */
    public boolean isCancelLocked(Long matchId) {
        try {
            String lockKey = CANCEL_LOCK_PREFIX + ":" + matchId;
            Boolean exists = redisTemplate.hasKey(lockKey);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("❌ [GameEndLock] Erro ao verificar lock de cancelamento: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Força liberação do lock de finalização (usar apenas em emergência).
     * 
     * Útil se uma instância crashar sem liberar o lock.
     * 
     * @param matchId ID da partida
     */
    public void forceReleaseFinishLock(Long matchId) {
        try {
            String lockKey = FINISH_LOCK_PREFIX + ":" + matchId;
            redisTemplate.delete(lockKey);
            log.warn("⚠️ [GameEndLock] Lock de finalização FORÇADAMENTE liberado: matchId={}", matchId);
        } catch (Exception e) {
            log.error("❌ [GameEndLock] Erro ao forçar liberação de lock de finalização: {}", e.getMessage());
        }
    }

    /**
     * Força liberação do lock de cancelamento (usar apenas em emergência).
     * 
     * @param matchId ID da partida
     */
    public void forceReleaseCancelLock(Long matchId) {
        try {
            String lockKey = CANCEL_LOCK_PREFIX + ":" + matchId;
            redisTemplate.delete(lockKey);
            log.warn("⚠️ [GameEndLock] Lock de cancelamento FORÇADAMENTE liberado: matchId={}", matchId);
        } catch (Exception e) {
            log.error("❌ [GameEndLock] Erro ao forçar liberação de lock de cancelamento: {}", e.getMessage());
        }
    }
}
