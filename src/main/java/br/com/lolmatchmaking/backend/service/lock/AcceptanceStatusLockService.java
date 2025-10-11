package br.com.lolmatchmaking.backend.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * ✅ Service de Lock para Atualização de AcceptanceStatus
 * 
 * PROBLEMA RESOLVIDO:
 * - Múltiplas atualizações do campo acceptanceStatus na tabela queue_players
 * - Race condition entre aceitação e timeout
 * - Valor de acceptanceStatus inconsistente
 * 
 * SOLUÇÃO:
 * - Lock individual por player ao atualizar acceptanceStatus
 * - Garante que apenas UMA atualização por vez
 * - Thread-safe com TTL automático
 * 
 * CHAVES REDIS:
 * - lock:acceptance_status:{summonerName} → Lock de atualização
 * 
 * REFERÊNCIA:
 * - REVISAO-LOCKS-COMPLETA.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcceptanceStatusLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String STATUS_LOCK_PREFIX = "lock:acceptance_status:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    /**
     * ✅ Adquire lock para atualizar acceptanceStatus
     * 
     * @param summonerName Nome do jogador
     * @return true se conseguiu adquirir lock
     */
    public boolean acquireStatusLock(String summonerName) {
        String key = STATUS_LOCK_PREFIX + summonerName;

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "updating", LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("🔒 [AcceptanceStatus] Lock adquirido: {}", summonerName);
                return true;
            }

            log.warn("⚠️ [AcceptanceStatus] Lock ocupado: {}", summonerName);
            return false;

        } catch (Exception e) {
            log.error("❌ [AcceptanceStatus] Erro ao adquirir lock", e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de acceptanceStatus
     */
    public void releaseStatusLock(String summonerName) {
        String key = STATUS_LOCK_PREFIX + summonerName;
        redisTemplate.delete(key);
    }

    /**
     * ✅ Executa atualização de acceptanceStatus com lock
     * 
     * Garante thread-safety ao atualizar acceptanceStatus.
     * 
     * @param summonerName Nome do jogador
     * @param updateAction Action a executar (ex: salvar no banco)
     * @return true se conseguiu executar
     */
    public boolean updateWithLock(String summonerName, Runnable updateAction) {
        if (!acquireStatusLock(summonerName)) {
            return false;
        }

        try {
            updateAction.run();
            return true;
        } finally {
            releaseStatusLock(summonerName);
        }
    }
}
