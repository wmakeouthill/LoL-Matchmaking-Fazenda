package br.com.lolmatchmaking.backend.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * ✅ Service de Lock Distribuído para Matchmaking
 * 
 * PROBLEMA RESOLVIDO:
 * - Múltiplas instâncias do Cloud Run processando fila simultaneamente
 * - Criação de múltiplas partidas com os mesmos jogadores
 * - Race conditions no processamento de fila
 * 
 * SOLUÇÃO:
 * - Lock distribuído via Redis com TTL
 * - Apenas UMA instância pode processar fila por vez
 * - Auto-liberação do lock após 10 segundos (timeout)
 * 
 * CHAVES REDIS:
 * - lock:matchmaking:process → Lock de processamento de fila
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md#1-lock-de-processamento-de-fila
 * - TODO-CORRECOES-PRODUCAO.md#11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchmakingLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    // Chaves Redis
    private static final String PROCESS_LOCK_KEY = "lock:matchmaking:process";

    // Configurações de timeout
    private static final Duration LOCK_TTL = Duration.ofSeconds(10); // 10 segundos de timeout

    /**
     * ✅ Adquire lock para processar fila de matchmaking
     * 
     * @return true se conseguiu adquirir o lock, false se outra instância já tem o
     *         lock
     * 
     *         Exemplo de uso:
     * 
     *         <pre>
     *         if (matchmakingLockService.acquireProcessLock()) {
     *             try {
     *                 // Processar fila com segurança
     *                 processQueue();
     *             } finally {
     *                 matchmakingLockService.releaseProcessLock();
     *             }
     *         } else {
     *             log.debug("Outra instância está processando fila");
     *         }
     *         </pre>
     */
    public boolean acquireProcessLock() {
        try {
            // Tenta adquirir lock com setIfAbsent (SETNX do Redis)
            // Se retornar true, significa que conseguiu adquirir o lock
            // Se retornar false, outra instância já tem o lock
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(PROCESS_LOCK_KEY, "locked", LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [MatchmakingLock] Lock adquirido para processamento de fila");
                return true;
            } else {
                log.debug("⏭️ [MatchmakingLock] Lock já ocupado por outra instância");
                return false;
            }

        } catch (Exception e) {
            log.error("❌ [MatchmakingLock] Erro ao adquirir lock", e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de processamento de fila
     * 
     * IMPORTANTE: Sempre chamar este método em um bloco finally para garantir
     * que o lock seja liberado mesmo em caso de exceção.
     */
    public void releaseProcessLock() {
        try {
            Boolean deleted = redisTemplate.delete(PROCESS_LOCK_KEY);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("🔓 [MatchmakingLock] Lock liberado");
            } else {
                log.debug("⚠️ [MatchmakingLock] Lock já estava liberado ou expirou");
            }

        } catch (Exception e) {
            log.error("❌ [MatchmakingLock] Erro ao liberar lock", e);
        }
    }

    /**
     * ✅ Verifica se há lock ativo
     * 
     * @return true se há lock ativo, false caso contrário
     * 
     *         Útil para monitoring e debugging.
     */
    public boolean isLocked() {
        try {
            Boolean hasKey = redisTemplate.hasKey(PROCESS_LOCK_KEY);
            return Boolean.TRUE.equals(hasKey);

        } catch (Exception e) {
            log.error("❌ [MatchmakingLock] Erro ao verificar lock", e);
            return false;
        }
    }

    /**
     * ✅ Obtém tempo restante do lock (em segundos)
     * 
     * @return tempo restante em segundos, ou -1 se não há lock
     */
    public long getLockTtl() {
        try {
            Long ttl = redisTemplate.getExpire(PROCESS_LOCK_KEY);
            return ttl != null ? ttl : -1;

        } catch (Exception e) {
            log.error("❌ [MatchmakingLock] Erro ao obter TTL do lock", e);
            return -1;
        }
    }

    /**
     * ✅ Força liberação do lock (usar apenas em emergências)
     * 
     * ⚠️ ATENÇÃO: Este método deve ser usado apenas em casos excepcionais,
     * como quando uma instância travou e o lock não foi liberado.
     * 
     * @return true se conseguiu forçar liberação, false caso contrário
     */
    public boolean forceRelease() {
        try {
            Boolean deleted = redisTemplate.delete(PROCESS_LOCK_KEY);

            if (Boolean.TRUE.equals(deleted)) {
                log.warn("⚠️ [MatchmakingLock] Lock FORÇADAMENTE liberado!");
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("❌ [MatchmakingLock] Erro ao forçar liberação do lock", e);
            return false;
        }
    }
}
