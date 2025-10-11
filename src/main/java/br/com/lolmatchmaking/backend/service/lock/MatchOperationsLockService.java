package br.com.lolmatchmaking.backend.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * ✅ Service de Lock para Operações de Partida
 * 
 * PROBLEMA RESOLVIDO:
 * - Múltiplas aceitações/recusas do mesmo jogador
 * - Timeout e aceitação manual simultâneos
 * - Múltiplos cancelamentos de partida
 * - Múltiplos inícios de draft/game
 * - Race conditions em salvamento de resultado
 * 
 * SOLUÇÃO:
 * - Lock individual de aceitação/recusa (por player+match)
 * - Lock de cancelamento de partida
 * - Lock de início de draft
 * - Lock de início de game
 * - Lock de salvamento de resultado
 * - Lock de processamento de timeout
 * 
 * CHAVES REDIS:
 * - lock:acceptance:{matchId}:{summonerName} → Lock de aceitação individual
 * - lock:match:cancel:{matchId} → Lock de cancelamento
 * - lock:draft:start:{matchId} → Lock de início de draft
 * - lock:game:start:{matchId} → Lock de início de game
 * - lock:result:save:{matchId} → Lock de salvamento
 * - lock:timeout:process:{matchId} → Lock de timeout
 * 
 * REFERÊNCIA:
 * - REVISAO-LOCKS-COMPLETA.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchOperationsLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    // Prefixos de chave
    private static final String ACCEPTANCE_LOCK_PREFIX = "lock:acceptance:";
    private static final String CANCEL_LOCK_PREFIX = "lock:match:cancel:";
    private static final String DRAFT_START_LOCK_PREFIX = "lock:draft:start:";
    private static final String GAME_START_LOCK_PREFIX = "lock:game:start:";
    private static final String RESULT_SAVE_LOCK_PREFIX = "lock:result:save:";
    private static final String TIMEOUT_LOCK_PREFIX = "lock:timeout:process:";
    private static final String ALL_ACCEPTED_LOCK_PREFIX = "lock:match:all_accepted:"; // ✅ NOVO

    // TTLs
    private static final Duration ACCEPTANCE_LOCK_TTL = Duration.ofSeconds(5);
    private static final Duration CANCEL_LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration START_LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration SAVE_LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration TIMEOUT_LOCK_TTL = Duration.ofSeconds(5);
    // ✅ CORRIGIDO: 30s → 10s (alinhado com outros start locks)
    private static final Duration ALL_ACCEPTED_LOCK_TTL = Duration.ofSeconds(10);

    // ═══════════════════════════════════════════════════════════
    // LOCKS DE ACEITAÇÃO/RECUSA
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ Adquire lock de aceitação individual
     * 
     * Previne:
     * - Player clicar "Aceitar" múltiplas vezes
     * - Timeout processar aceitação que já foi feita manualmente
     * - Múltiplas instâncias processarem mesma aceitação
     * 
     * @param matchId      ID da partida
     * @param summonerName Nome do jogador
     * @return true se conseguiu adquirir lock
     */
    public boolean acquireAcceptanceLock(Long matchId, String summonerName) {
        String key = ACCEPTANCE_LOCK_PREFIX + matchId + ":" + summonerName;

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "accepting", ACCEPTANCE_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [MatchOps] Acceptance lock adquirido: match {} player {}", matchId, summonerName);
                return true;
            }

            log.warn("⚠️ [MatchOps] Acceptance lock ocupado: match {} player {}", matchId, summonerName);
            return false;

        } catch (Exception e) {
            log.error("❌ [MatchOps] Erro ao adquirir acceptance lock", e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de aceitação
     */
    public void releaseAcceptanceLock(Long matchId, String summonerName) {
        String key = ACCEPTANCE_LOCK_PREFIX + matchId + ":" + summonerName;
        redisTemplate.delete(key);
    }

    // ═══════════════════════════════════════════════════════════
    // LOCKS DE CANCELAMENTO
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ Adquire lock de cancelamento de partida
     * 
     * Previne:
     * - Múltiplos jogadores recusando ao mesmo tempo
     * - Timeout e recusa manual simultâneos
     * - Múltiplas instâncias cancelando mesma partida
     * 
     * @param matchId ID da partida
     * @return true se conseguiu adquirir lock
     */
    public boolean acquireMatchCancelLock(Long matchId) {
        String key = CANCEL_LOCK_PREFIX + matchId;

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "cancelling", CANCEL_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [MatchOps] Cancel lock adquirido: match {}", matchId);
                return true;
            }

            log.warn("⚠️ [MatchOps] Cancel lock ocupado: match {}", matchId);
            return false;

        } catch (Exception e) {
            log.error("❌ [MatchOps] Erro ao adquirir cancel lock", e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de cancelamento
     */
    public void releaseMatchCancelLock(Long matchId) {
        String key = CANCEL_LOCK_PREFIX + matchId;
        redisTemplate.delete(key);
    }

    // ═══════════════════════════════════════════════════════════
    // LOCKS DE INÍCIO DE DRAFT
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ Adquire lock de início de draft
     * 
     * Previne:
     * - Múltiplas instâncias iniciando draft simultaneamente
     * - Draft sendo iniciado múltiplas vezes
     * 
     * @param matchId ID da partida
     * @return true se conseguiu adquirir lock
     */
    public boolean acquireDraftStartLock(Long matchId) {
        String key = DRAFT_START_LOCK_PREFIX + matchId;

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "starting_draft", START_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [MatchOps] Draft start lock adquirido: match {}", matchId);
                return true;
            }

            log.warn("⚠️ [MatchOps] Draft já está sendo iniciado: match {}", matchId);
            return false;

        } catch (Exception e) {
            log.error("❌ [MatchOps] Erro ao adquirir draft start lock", e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de início de draft
     */
    public void releaseDraftStartLock(Long matchId) {
        String key = DRAFT_START_LOCK_PREFIX + matchId;
        redisTemplate.delete(key);
    }

    // ═══════════════════════════════════════════════════════════
    // LOCKS DE INÍCIO DE GAME
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ Adquire lock de início de game
     * 
     * Previne:
     * - Múltiplas instâncias iniciando game simultaneamente
     * - Game sendo iniciado múltiplas vezes
     * 
     * @param matchId ID da partida
     * @return true se conseguiu adquirir lock
     */
    public boolean acquireGameStartLock(Long matchId) {
        String key = GAME_START_LOCK_PREFIX + matchId;

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "starting_game", START_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [MatchOps] Game start lock adquirido: match {}", matchId);
                return true;
            }

            log.warn("⚠️ [MatchOps] Game já está sendo iniciado: match {}", matchId);
            return false;

        } catch (Exception e) {
            log.error("❌ [MatchOps] Erro ao adquirir game start lock", e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de início de game
     */
    public void releaseGameStartLock(Long matchId) {
        String key = GAME_START_LOCK_PREFIX + matchId;
        redisTemplate.delete(key);
    }

    // ═══════════════════════════════════════════════════════════
    // LOCKS DE SALVAMENTO DE RESULTADO
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ Adquire lock de salvamento de resultado
     * 
     * Previne:
     * - Múltiplas instâncias salvando resultado no banco
     * - Resultado sendo salvo múltiplas vezes
     * - Race condition em atualizações de estatísticas
     * 
     * @param matchId ID da partida
     * @return true se conseguiu adquirir lock
     */
    public boolean acquireResultSaveLock(Long matchId) {
        String key = RESULT_SAVE_LOCK_PREFIX + matchId;

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "saving_result", SAVE_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [MatchOps] Result save lock adquirido: match {}", matchId);
                return true;
            }

            log.warn("⚠️ [MatchOps] Resultado já está sendo salvo: match {}", matchId);
            return false;

        } catch (Exception e) {
            log.error("❌ [MatchOps] Erro ao adquirir result save lock", e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de salvamento de resultado
     */
    public void releaseResultSaveLock(Long matchId) {
        String key = RESULT_SAVE_LOCK_PREFIX + matchId;
        redisTemplate.delete(key);
    }

    // ═══════════════════════════════════════════════════════════
    // ✅ NOVO: LOCKS DE ACEITAÇÃO COMPLETA (TODOS ACEITARAM)
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ NOVO: Adquire lock de processamento de aceitação completa
     * 
     * Previne:
     * - Múltiplas instâncias processando "todos aceitaram" simultaneamente
     * - Draft sendo iniciado múltiplas vezes
     * - Race condition ao remover jogadores da fila
     * 
     * ONDE USAR:
     * - MatchFoundService.handleAllPlayersAccepted() (INÍCIO do método)
     * 
     * @param matchId ID da partida
     * @return true se conseguiu adquirir lock
     */
    public boolean acquireAllAcceptedProcessingLock(Long matchId) {
        String key = ALL_ACCEPTED_LOCK_PREFIX + matchId;

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "processing_all_accepted", ALL_ACCEPTED_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [MatchOps] AllAccepted processing lock adquirido: match {}", matchId);
                return true;
            }

            log.warn("⚠️ [MatchOps] AllAccepted já está sendo processado: match {}", matchId);
            return false;

        } catch (Exception e) {
            log.error("❌ [MatchOps] Erro ao adquirir all_accepted lock", e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de aceitação completa
     */
    public void releaseAllAcceptedProcessingLock(Long matchId) {
        String key = ALL_ACCEPTED_LOCK_PREFIX + matchId;
        redisTemplate.delete(key);
        log.info("🔓 [MatchOps] AllAccepted processing lock liberado: match {}", matchId);
    }

    // ═══════════════════════════════════════════════════════════
    // LOCKS DE PROCESSAMENTO DE TIMEOUT
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ Adquire lock de processamento de timeout
     * 
     * Previne:
     * - Múltiplas instâncias processando mesmo timeout
     * - Timeout sendo processado após ação manual
     * 
     * @param matchId ID da partida
     * @return true se conseguiu adquirir lock
     */
    public boolean acquireTimeoutProcessingLock(Long matchId) {
        String key = TIMEOUT_LOCK_PREFIX + matchId;

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "processing_timeout", TIMEOUT_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [MatchOps] Timeout processing lock adquirido: match {}", matchId);
                return true;
            }

            log.debug("⏭️ [MatchOps] Timeout já está sendo processado: match {}", matchId);
            return false;

        } catch (Exception e) {
            log.error("❌ [MatchOps] Erro ao adquirir timeout lock", e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de timeout
     */
    public void releaseTimeoutProcessingLock(Long matchId) {
        String key = TIMEOUT_LOCK_PREFIX + matchId;
        redisTemplate.delete(key);
    }

    // ═══════════════════════════════════════════════════════════
    // HELPERS E CLEANUP
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ Limpa todos os locks de operações de uma partida
     * 
     * Chamar quando partida terminar ou for cancelada completamente.
     * 
     * @param matchId ID da partida
     */
    public void clearAllMatchLocks(Long matchId) {
        try {
            releaseMatchCancelLock(matchId);
            releaseDraftStartLock(matchId);
            releaseGameStartLock(matchId);
            releaseResultSaveLock(matchId);
            releaseTimeoutProcessingLock(matchId);
            releaseAllAcceptedProcessingLock(matchId); // ✅ NOVO

            log.info("🗑️ [MatchOps] Todos os locks de operações limpos: match {}", matchId);

        } catch (Exception e) {
            log.error("❌ [MatchOps] Erro ao limpar locks de match {}", matchId, e);
        }
    }

    /**
     * ✅ Verifica se partida tem algum lock ativo
     * 
     * Útil para debugging e monitoramento.
     * 
     * @param matchId ID da partida
     * @return true se há algum lock ativo
     */
    public boolean hasAnyActiveLock(Long matchId) {
        try {
            String cancelKey = CANCEL_LOCK_PREFIX + matchId;
            String draftStartKey = DRAFT_START_LOCK_PREFIX + matchId;
            String gameStartKey = GAME_START_LOCK_PREFIX + matchId;
            String resultSaveKey = RESULT_SAVE_LOCK_PREFIX + matchId;
            String timeoutKey = TIMEOUT_LOCK_PREFIX + matchId;
            String allAcceptedKey = ALL_ACCEPTED_LOCK_PREFIX + matchId; // ✅ NOVO

            return Boolean.TRUE.equals(redisTemplate.hasKey(cancelKey))
                    || Boolean.TRUE.equals(redisTemplate.hasKey(draftStartKey))
                    || Boolean.TRUE.equals(redisTemplate.hasKey(gameStartKey))
                    || Boolean.TRUE.equals(redisTemplate.hasKey(resultSaveKey))
                    || Boolean.TRUE.equals(redisTemplate.hasKey(timeoutKey))
                    || Boolean.TRUE.equals(redisTemplate.hasKey(allAcceptedKey)); // ✅ NOVO

        } catch (Exception e) {
            log.error("❌ [MatchOps] Erro ao verificar locks ativos", e);
            return false;
        }
    }
}
