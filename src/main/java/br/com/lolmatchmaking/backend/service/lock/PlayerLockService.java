package br.com.lolmatchmaking.backend.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * ✅ Service de Lock de Jogador (Player Lock)
 * 
 * PROBLEMA RESOLVIDO:
 * - Jogador abrindo múltiplas instâncias do Electron simultaneamente
 * - Perda de vinculação de sessão WebSocket com jogador
 * - Múltiplas conexões do mesmo jogador em instâncias diferentes
 * 
 * SOLUÇÃO:
 * - Lock por summonerName vinculando jogador a uma sessão
 * - Detecta se jogador já está conectado ao abrir Electron
 * - Previne duplicação de conexões
 * - Libera automaticamente ao fechar Electron
 * 
 * CHAVES REDIS:
 * - lock:player:{summonerName} → sessionId vinculado
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md#2-lock-de-jogador-player-lock
 * - TODO-CORRECOES-PRODUCAO.md#12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    // Prefixo de chave
    private static final String PLAYER_LOCK_PREFIX = "lock:player:";

    // TTL padrão: 30 minutos (tempo máximo que jogador pode ficar conectado sem
    // atividade)
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    /**
     * ✅ Adquire lock para o jogador, vinculando-o a uma sessão
     * 
     * @param summonerName Nome do jogador
     * @param sessionId    ID da sessão WebSocket
     * @return sessionId vinculado se sucesso, null se jogador já está conectado em
     *         outra sessão
     * 
     *         Exemplo de uso (no WebSocket onOpen):
     * 
     *         <pre>
     *         String lockedSession = playerLockService.acquirePlayerLock(summonerName, sessionId);
     *         if (lockedSession == null) {
     *             // Jogador já conectado em outra instância
     *             sendError(sessionId, "ALREADY_CONNECTED", "Você já está conectado em outro dispositivo");
     *             session.close();
     *             return;
     *         }
     *         // Prosseguir normalmente
     *         </pre>
     */
    public String acquirePlayerLock(String summonerName, String sessionId) {
        String key = PLAYER_LOCK_PREFIX + summonerName;

        try {
            // Tentar adquirir lock
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, sessionId, LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [PlayerLock] Lock adquirido: {} → session {}", summonerName, sessionId);
                return sessionId;
            }

            // Verificar se já tem lock desta mesma sessão
            String existingSession = (String) redisTemplate.opsForValue().get(key);
            if (sessionId.equals(existingSession)) {
                log.info("✅ [PlayerLock] Lock já era desta sessão: {}", summonerName);
                // Renovar TTL
                redisTemplate.expire(key, LOCK_TTL);
                return sessionId;
            }

            log.warn("⚠️ [PlayerLock] Jogador {} JÁ conectado em outra sessão: {}",
                    summonerName, existingSession);
            return null;

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao adquirir lock para {}", summonerName, e);
            return null;
        }
    }

    /**
     * ✅ Libera lock do jogador
     * 
     * Chamar ao fechar WebSocket (onClose) ou ao jogador fazer logout.
     * 
     * @param summonerName Nome do jogador
     */
    public void releasePlayerLock(String summonerName) {
        String key = PLAYER_LOCK_PREFIX + summonerName;

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("🔓 [PlayerLock] Lock liberado: {}", summonerName);
            } else {
                log.debug("⚠️ [PlayerLock] Lock já estava liberado ou expirou: {}", summonerName);
            }

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao liberar lock de {}", summonerName, e);
        }
    }

    /**
     * ✅ Obtém sessionId vinculado ao jogador
     * 
     * @param summonerName Nome do jogador
     * @return sessionId vinculado, ou null se jogador não está conectado
     */
    public String getPlayerSession(String summonerName) {
        String key = PLAYER_LOCK_PREFIX + summonerName;

        try {
            Object session = redisTemplate.opsForValue().get(key);
            return session != null ? (String) session : null;

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao obter sessão de {}", summonerName, e);
            return null;
        }
    }

    /**
     * ✅ Verifica se jogador tem sessão ativa
     * 
     * @param summonerName Nome do jogador
     * @return true se jogador está conectado, false caso contrário
     * 
     *         Útil para detectar ao abrir Electron se jogador já está conectado.
     */
    public boolean hasActiveSession(String summonerName) {
        String existingSession = getPlayerSession(summonerName);
        return existingSession != null;
    }

    /**
     * ✅ Renova TTL do lock do jogador
     * 
     * Chamar periodicamente (ex: a cada mensagem WebSocket) para manter
     * a sessão ativa e prevenir expiração por inatividade.
     * 
     * @param summonerName Nome do jogador
     * @return true se conseguiu renovar, false caso contrário
     */
    public boolean renewPlayerLock(String summonerName) {
        String key = PLAYER_LOCK_PREFIX + summonerName;

        try {
            Boolean renewed = redisTemplate.expire(key, LOCK_TTL);

            if (Boolean.TRUE.equals(renewed)) {
                log.debug("♻️ [PlayerLock] TTL renovado para {}", summonerName);
                return true;
            }

            log.debug("⚠️ [PlayerLock] Não foi possível renovar TTL de {} (lock não existe)",
                    summonerName);
            return false;

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao renovar lock de {}", summonerName, e);
            return false;
        }
    }

    /**
     * ✅ Obtém tempo restante do lock do jogador (em segundos)
     * 
     * @param summonerName Nome do jogador
     * @return tempo restante em segundos, ou -1 se não há lock
     */
    public long getPlayerLockTtl(String summonerName) {
        String key = PLAYER_LOCK_PREFIX + summonerName;

        try {
            Long ttl = redisTemplate.getExpire(key);
            return ttl != null ? ttl : -1;

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao obter TTL de {}", summonerName, e);
            return -1;
        }
    }

    /**
     * ✅ Força liberação do lock do jogador (usar apenas em emergências)
     * 
     * ⚠️ ATENÇÃO: Use apenas se tiver certeza que a sessão está morta
     * e o lock não foi liberado corretamente.
     * 
     * @param summonerName Nome do jogador
     * @return true se conseguiu forçar liberação, false caso contrário
     */
    public boolean forceReleasePlayerLock(String summonerName) {
        String key = PLAYER_LOCK_PREFIX + summonerName;

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.warn("⚠️ [PlayerLock] Lock FORÇADAMENTE liberado para: {}", summonerName);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao forçar liberação de {}", summonerName, e);
            return false;
        }
    }

    /**
     * ✅ Transfere lock do jogador para nova sessão
     * 
     * Útil para reconexão: se mesma sessão precisa novo ID.
     * 
     * @param summonerName Nome do jogador
     * @param oldSessionId Sessão antiga
     * @param newSessionId Nova sessão
     * @return true se conseguiu transferir, false caso contrário
     */
    public boolean transferPlayerLock(String summonerName, String oldSessionId, String newSessionId) {
        String key = PLAYER_LOCK_PREFIX + summonerName;

        try {
            // Verificar se lock existe e pertence à sessão antiga
            String currentSession = getPlayerSession(summonerName);
            if (currentSession == null) {
                log.warn("⚠️ [PlayerLock] Não há lock para transferir: {}", summonerName);
                return false;
            }

            if (!oldSessionId.equals(currentSession)) {
                log.warn("⚠️ [PlayerLock] Lock de {} pertence a outra sessão: {} (esperado: {})",
                        summonerName, currentSession, oldSessionId);
                return false;
            }

            // Atualizar para nova sessão
            redisTemplate.opsForValue().set(key, newSessionId, LOCK_TTL);
            log.info("🔄 [PlayerLock] Lock transferido: {} de session {} para {}",
                    summonerName, oldSessionId, newSessionId);
            return true;

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao transferir lock de {}", summonerName, e);
            return false;
        }
    }
}
