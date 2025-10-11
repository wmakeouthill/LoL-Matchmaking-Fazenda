package br.com.lolmatchmaking.backend.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Serviço para gerenciar locks distribuídos de operações do Discord.
 * 
 * Previne race conditions em:
 * - Atualização de usuários do Discord (múltiplas instâncias)
 * - Vinculação de contas (duplo-clique, múltiplas instâncias)
 * - Desvinculação de contas
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String UPDATE_LOCK_PREFIX = "discord:update:lock";
    private static final String LINK_LOCK_PREFIX = "discord:link:lock";
    private static final long UPDATE_LOCK_TTL = 10; // segundos
    private static final long LINK_LOCK_TTL = 15; // segundos

    /**
     * Adquire lock para atualizar lista de usuários do Discord.
     * 
     * Previne múltiplas instâncias do backend processando o mesmo evento JDA
     * simultaneamente (ex: onGuildVoiceUpdate).
     * 
     * @return true se lock foi adquirido, false se já existe
     */
    public boolean acquireDiscordUpdateLock() {
        try {
            String lockKey = UPDATE_LOCK_PREFIX;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "locked", Duration.ofSeconds(UPDATE_LOCK_TTL));

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("🔒 [DiscordLock] Lock de atualização adquirido");
                return true;
            }

            log.debug("⏳ [DiscordLock] Lock de atualização já existe, pulando");
            return false;
        } catch (Exception e) {
            log.error("❌ [DiscordLock] Erro ao adquirir lock de atualização: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Libera lock de atualização de usuários.
     * 
     * DEVE ser chamado sempre em finally block para garantir liberação.
     */
    public void releaseDiscordUpdateLock() {
        try {
            String lockKey = UPDATE_LOCK_PREFIX;
            redisTemplate.delete(lockKey);
            log.debug("🔓 [DiscordLock] Lock de atualização liberado");
        } catch (Exception e) {
            log.error("❌ [DiscordLock] Erro ao liberar lock de atualização: {}", e.getMessage());
        }
    }

    /**
     * Adquire lock para vinculação de conta Discord ↔ LoL.
     * 
     * Previne:
     * - Duplo-clique no comando /vincular
     * - Múltiplas instâncias processando mesma vinculação
     * - Vinculação + Desvinculação simultâneas
     * 
     * @param discordUserId ID do usuário Discord
     * @return true se lock foi adquirido, false se já existe
     */
    public boolean acquireUserLinkLock(String discordUserId) {
        try {
            String lockKey = LINK_LOCK_PREFIX + ":" + discordUserId;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "locked", Duration.ofSeconds(LINK_LOCK_TTL));

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("🔒 [DiscordLock] Lock de vinculação adquirido: {}", discordUserId);
                return true;
            }

            log.debug("⏳ [DiscordLock] Lock de vinculação já existe: {}", discordUserId);
            return false;
        } catch (Exception e) {
            log.error("❌ [DiscordLock] Erro ao adquirir lock de vinculação: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Libera lock de vinculação de usuário.
     * 
     * DEVE ser chamado sempre em finally block para garantir liberação.
     * 
     * @param discordUserId ID do usuário Discord
     */
    public void releaseUserLinkLock(String discordUserId) {
        try {
            String lockKey = LINK_LOCK_PREFIX + ":" + discordUserId;
            redisTemplate.delete(lockKey);
            log.debug("🔓 [DiscordLock] Lock de vinculação liberado: {}", discordUserId);
        } catch (Exception e) {
            log.error("❌ [DiscordLock] Erro ao liberar lock de vinculação: {}", e.getMessage());
        }
    }

    /**
     * Verifica se existe lock de atualização ativo.
     * 
     * @return true se lock existe
     */
    public boolean isUpdateLocked() {
        try {
            String lockKey = UPDATE_LOCK_PREFIX;
            Boolean exists = redisTemplate.hasKey(lockKey);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("❌ [DiscordLock] Erro ao verificar lock: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verifica se usuário específico tem lock de vinculação ativo.
     * 
     * @param discordUserId ID do usuário Discord
     * @return true se lock existe
     */
    public boolean isUserLinkLocked(String discordUserId) {
        try {
            String lockKey = LINK_LOCK_PREFIX + ":" + discordUserId;
            Boolean exists = redisTemplate.hasKey(lockKey);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("❌ [DiscordLock] Erro ao verificar lock de vinculação: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Força liberação do lock de atualização (usar apenas em emergência).
     * 
     * Útil se uma instância crashar sem liberar o lock.
     */
    public void forceReleaseDiscordUpdateLock() {
        try {
            String lockKey = UPDATE_LOCK_PREFIX;
            redisTemplate.delete(lockKey);
            log.warn("⚠️ [DiscordLock] Lock de atualização FORÇADAMENTE liberado");
        } catch (Exception e) {
            log.error("❌ [DiscordLock] Erro ao forçar liberação de lock: {}", e.getMessage());
        }
    }

    /**
     * Força liberação do lock de vinculação de um usuário (usar apenas em
     * emergência).
     * 
     * @param discordUserId ID do usuário Discord
     */
    public void forceReleaseUserLinkLock(String discordUserId) {
        try {
            String lockKey = LINK_LOCK_PREFIX + ":" + discordUserId;
            redisTemplate.delete(lockKey);
            log.warn("⚠️ [DiscordLock] Lock de vinculação FORÇADAMENTE liberado: {}", discordUserId);
        } catch (Exception e) {
            log.error("❌ [DiscordLock] Erro ao forçar liberação de lock de vinculação: {}", e.getMessage());
        }
    }
}
