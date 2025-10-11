package br.com.lolmatchmaking.backend.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * ✅ Service de Lock para Espectadores (Mute/Unmute)
 * 
 * PROBLEMA RESOLVIDO:
 * - Múltiplos jogadores tentando mute/unmute espectadores simultaneamente
 * - Estado de mute inconsistente entre jogadores
 * - Race conditions em ações de espectadores
 * 
 * SOLUÇÃO:
 * - Lock de ação de espectador (mute/unmute)
 * - Estado centralizado no Redis
 * - Sincronização entre todos os jogadores
 * 
 * CONTEXTO:
 * Durante draft e game in progress, jogadores podem:
 * - Abrir modal de espectadores
 * - Mutar/desmutar espectadores
 * - Adicionar/remover espectadores
 * 
 * Essas ações precisam de lock para garantir consistência.
 * 
 * CHAVES REDIS:
 * - lock:spectator:{matchId}:action → Lock de ação em espectadores
 * - state:spectator:{matchId}:muted → Set de espectadores mutados
 * - state:spectator:{matchId}:list → Lista de espectadores
 * 
 * REFERÊNCIA:
 * - Solicitado pelo usuário durante implementação
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpectatorLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    // Prefixos de chave
    private static final String SPECTATOR_LOCK_PREFIX = "lock:spectator:";
    private static final String SPECTATOR_STATE_PREFIX = "state:spectator:";

    // TTLs
    private static final Duration ACTION_LOCK_TTL = Duration.ofSeconds(5); // 5 segundos por ação
    private static final Duration STATE_TTL = Duration.ofHours(2); // 2 horas de estado

    /**
     * ✅ Adquire lock de ação em espectadores
     * 
     * Previne múltiplos jogadores modificando espectadores simultaneamente.
     * 
     * @param matchId      ID da partida
     * @param summonerName Jogador que está fazendo a ação
     * @param action       Tipo de ação ("mute", "unmute", "add", "remove")
     * @return true se conseguiu adquirir lock
     */
    public boolean acquireSpectatorActionLock(Long matchId, String summonerName, String action) {
        String key = SPECTATOR_LOCK_PREFIX + matchId + ":action";
        String value = summonerName + ":" + action;

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, value, ACTION_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [SpectatorLock] Action lock adquirido: match {} {} por {}",
                        matchId, action, summonerName);
                return true;
            }

            log.warn("⚠️ [SpectatorLock] Action lock ocupado: match {}", matchId);
            return false;

        } catch (Exception e) {
            log.error("❌ [SpectatorLock] Erro ao adquirir action lock para match {}", matchId, e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de ação em espectadores
     * 
     * @param matchId ID da partida
     */
    public void releaseSpectatorActionLock(Long matchId) {
        String key = SPECTATOR_LOCK_PREFIX + matchId + ":action";

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("🔓 [SpectatorLock] Action lock liberado: match {}", matchId);
            }

        } catch (Exception e) {
            log.error("❌ [SpectatorLock] Erro ao liberar action lock de match {}", matchId, e);
        }
    }

    /**
     * ✅ Muta espectador
     * 
     * Thread-safe: Adiciona espectador ao set de mutados.
     * 
     * @param matchId       ID da partida
     * @param spectatorName Nome do espectador
     * @param performedBy   Jogador que executou a ação
     * @return true se mutado com sucesso
     */
    public boolean muteSpectator(Long matchId, String spectatorName, String performedBy) {
        String key = SPECTATOR_STATE_PREFIX + matchId + ":muted";

        try {
            // Adicionar ao set de mutados
            redisTemplate.opsForSet().add(key, spectatorName);
            redisTemplate.expire(key, STATE_TTL);

            log.info("🔇 [SpectatorLock] Espectador mutado: match {} {} por {}",
                    matchId, spectatorName, performedBy);
            return true;

        } catch (Exception e) {
            log.error("❌ [SpectatorLock] Erro ao mutar espectador {} em match {}",
                    spectatorName, matchId, e);
            return false;
        }
    }

    /**
     * ✅ Desmuta espectador
     * 
     * Thread-safe: Remove espectador do set de mutados.
     * 
     * @param matchId       ID da partida
     * @param spectatorName Nome do espectador
     * @param performedBy   Jogador que executou a ação
     * @return true se desmutado com sucesso
     */
    public boolean unmuteSpectator(Long matchId, String spectatorName, String performedBy) {
        String key = SPECTATOR_STATE_PREFIX + matchId + ":muted";

        try {
            // Remover do set de mutados
            Long removed = redisTemplate.opsForSet().remove(key, spectatorName);

            if (removed != null && removed > 0) {
                log.info("🔊 [SpectatorLock] Espectador desmutado: match {} {} por {}",
                        matchId, spectatorName, performedBy);
                return true;
            }

            log.debug("⚠️ [SpectatorLock] Espectador {} já estava desmutado em match {}",
                    spectatorName, matchId);
            return true; // Idempotente

        } catch (Exception e) {
            log.error("❌ [SpectatorLock] Erro ao desmutar espectador {} em match {}",
                    spectatorName, matchId, e);
            return false;
        }
    }

    /**
     * ✅ Verifica se espectador está mutado
     * 
     * @param matchId       ID da partida
     * @param spectatorName Nome do espectador
     * @return true se está mutado
     */
    public boolean isSpectatorMuted(Long matchId, String spectatorName) {
        String key = SPECTATOR_STATE_PREFIX + matchId + ":muted";

        try {
            Boolean isMuted = redisTemplate.opsForSet().isMember(key, spectatorName);
            return Boolean.TRUE.equals(isMuted);

        } catch (Exception e) {
            log.error("❌ [SpectatorLock] Erro ao verificar mute de {} em match {}",
                    spectatorName, matchId, e);
            return false;
        }
    }

    /**
     * ✅ Obtém lista de espectadores mutados
     * 
     * @param matchId ID da partida
     * @return Set de nomes de espectadores mutados
     */
    public Set<Object> getMutedSpectators(Long matchId) {
        String key = SPECTATOR_STATE_PREFIX + matchId + ":muted";

        try {
            Set<Object> muted = redisTemplate.opsForSet().members(key);
            return muted != null ? muted : Set.of();

        } catch (Exception e) {
            log.error("❌ [SpectatorLock] Erro ao obter espectadores mutados de match {}", matchId, e);
            return Set.of();
        }
    }

    /**
     * ✅ Adiciona espectador à partida
     * 
     * @param matchId       ID da partida
     * @param spectatorName Nome do espectador
     * @param performedBy   Jogador que adicionou
     * @return true se adicionado com sucesso
     */
    public boolean addSpectator(Long matchId, String spectatorName, String performedBy) {
        String key = SPECTATOR_STATE_PREFIX + matchId + ":list";

        try {
            redisTemplate.opsForSet().add(key, spectatorName);
            redisTemplate.expire(key, STATE_TTL);

            log.info("➕ [SpectatorLock] Espectador adicionado: match {} {} por {}",
                    matchId, spectatorName, performedBy);
            return true;

        } catch (Exception e) {
            log.error("❌ [SpectatorLock] Erro ao adicionar espectador {} em match {}",
                    spectatorName, matchId, e);
            return false;
        }
    }

    /**
     * ✅ Remove espectador da partida
     * 
     * @param matchId       ID da partida
     * @param spectatorName Nome do espectador
     * @param performedBy   Jogador que removeu
     * @return true se removido com sucesso
     */
    public boolean removeSpectator(Long matchId, String spectatorName, String performedBy) {
        String key = SPECTATOR_STATE_PREFIX + matchId + ":list";

        try {
            Long removed = redisTemplate.opsForSet().remove(key, spectatorName);

            // Se estava mutado, remover do set de mutados também
            String mutedKey = SPECTATOR_STATE_PREFIX + matchId + ":muted";
            redisTemplate.opsForSet().remove(mutedKey, spectatorName);

            if (removed != null && removed > 0) {
                log.info("➖ [SpectatorLock] Espectador removido: match {} {} por {}",
                        matchId, spectatorName, performedBy);
                return true;
            }

            return true; // Idempotente

        } catch (Exception e) {
            log.error("❌ [SpectatorLock] Erro ao remover espectador {} de match {}",
                    spectatorName, matchId, e);
            return false;
        }
    }

    /**
     * ✅ Obtém lista de espectadores
     * 
     * @param matchId ID da partida
     * @return Set de nomes de espectadores
     */
    public Set<Object> getSpectators(Long matchId) {
        String key = SPECTATOR_STATE_PREFIX + matchId + ":list";

        try {
            Set<Object> spectators = redisTemplate.opsForSet().members(key);
            return spectators != null ? spectators : Set.of();

        } catch (Exception e) {
            log.error("❌ [SpectatorLock] Erro ao obter espectadores de match {}", matchId, e);
            return Set.of();
        }
    }

    /**
     * ✅ Limpa todos os dados de espectadores
     * 
     * Chamar quando partida terminar.
     * 
     * @param matchId ID da partida
     */
    public void clearAllSpectatorData(Long matchId) {
        try {
            releaseSpectatorActionLock(matchId);

            String listKey = SPECTATOR_STATE_PREFIX + matchId + ":list";
            String mutedKey = SPECTATOR_STATE_PREFIX + matchId + ":muted";

            redisTemplate.delete(listKey);
            redisTemplate.delete(mutedKey);

            log.info("🗑️ [SpectatorLock] Dados de espectadores limpos: match {}", matchId);

        } catch (Exception e) {
            log.error("❌ [SpectatorLock] Erro ao limpar dados de espectadores de match {}", matchId, e);
        }
    }

    /**
     * ✅ Obtém contagem de espectadores
     * 
     * @param matchId ID da partida
     * @return Número de espectadores
     */
    public long getSpectatorCount(Long matchId) {
        String key = SPECTATOR_STATE_PREFIX + matchId + ":list";

        try {
            Long count = redisTemplate.opsForSet().size(key);
            return count != null ? count : 0;

        } catch (Exception e) {
            log.error("❌ [SpectatorLock] Erro ao contar espectadores de match {}", matchId, e);
            return 0;
        }
    }
}
