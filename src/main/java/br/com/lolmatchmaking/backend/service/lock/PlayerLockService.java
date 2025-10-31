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
 * - Lock por customSessionId (player_{gameName}_{tagLine}) vinculando jogador a
 * uma sessão
 * - Detecta se jogador já está conectado ao abrir Electron
 * - Previne duplicação de conexões
 * - Libera automaticamente ao fechar Electron
 * 
 * CHAVES REDIS:
 * - lock:player:{customSessionId} → sessionId vinculado
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

    // ✅ CORRIGIDO: Prefixo de chave usando customSessionId (não summonerName com
    // espaços)
    private static final String PLAYER_LOCK_PREFIX = "lock:player:";

    // ✅ CORRIGIDO: TTL de 4 horas (sessão de jogo completa)
    // Com force release inteligente, pode ser maior sem riscos:
    // - Lock liberado ao fechar Electron (afterConnectionClosed)
    // - Force release se sessão antiga não existe (reconexão)
    // - Player pode ficar em draft + game por 1-2 horas
    private static final Duration LOCK_TTL = Duration.ofHours(4);

    /**
     * ✅ NOVO: Gera customSessionId consistente baseado em gameName e tagLine
     * Formato: player_{gameName}_{tagline} (lowercase, caracteres especiais
     * removidos)
     * 
     * Exemplo: FZD Ratoso#fzd -> player_fzd_ratoso_fzd
     */
    public static String generateCustomSessionId(String gameName, String tagLine) {
        if (gameName == null || gameName.isBlank() || tagLine == null || tagLine.isBlank()) {
            return null;
        }

        String fullName = gameName + "#" + tagLine;
        return "player_" + fullName
                .replace("#", "_")
                .replaceAll("[^a-zA-Z0-9_]", "_")
                .toLowerCase();
    }

    /**
     * ✅ Adquire lock para o jogador usando customSessionId como CHAVE
     * e armazena sessionId aleatória como VALOR
     * 
     * Arquitetura correta:
     * - CHAVE: lock:player:{customSessionId} (ex:
     * lock:player:player_fzd_ratoso_fzd)
     * - VALOR: sessionId aleatória (ex: c32db208-d5ec-17e1-bc36-dcd63ac8092d)
     * 
     * Vantagens:
     * - Chave imutável baseada no jogador (não muda entre conexões)
     * - Valor contém sessionId atual para identificação rápida
     * - Permite verificar se jogador já está conectado (chave existe)
     * 
     * @param customSessionId ID da sessão customizada (player_{gameName}_{tagLine})
     * @param sessionId       ID da sessão WebSocket aleatória
     * @return sessionId vinculado se sucesso, null se jogador já está conectado
     */
    public String acquirePlayerLock(String customSessionId, String sessionId) {
        if (customSessionId == null || customSessionId.isBlank()) {
            log.warn("⚠️ [PlayerLock] CustomSessionId inválido, usando sessionId como fallback");
            customSessionId = sessionId;
        }

        // ✅ CHAVE do lock: customSessionId (imutável baseado no jogador)
        String key = PLAYER_LOCK_PREFIX + customSessionId;

        try {
            // Tentar adquirir lock armazenando sessionId aleatória
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, sessionId, LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [PlayerLock] Lock adquirido: {} → session {}", customSessionId, sessionId);
                return sessionId;
            }

            // Verificar se já tem lock desta mesma sessão
            String existingSession = (String) redisTemplate.opsForValue().get(key);
            if (sessionId.equals(existingSession)) {
                log.info("✅ [PlayerLock] Lock já era desta sessão: {}", customSessionId);
                // Renovar TTL
                redisTemplate.expire(key, LOCK_TTL);
                return sessionId;
            }

            log.warn("⚠️ [PlayerLock] Jogador {} JÁ conectado em outra sessão: {}",
                    customSessionId, existingSession);
            return null;

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao adquirir lock para {}", customSessionId, e);
            return null;
        }
    }

    /**
     * ✅ Libera lock do jogador
     * 
     * Chamar ao fechar WebSocket (onClose) ou ao jogador fazer logout.
     * 
     * @param customSessionId ID da sessão customizada
     */
    public void releasePlayerLock(String customSessionId) {
        if (customSessionId == null || customSessionId.isBlank()) {
            log.warn("⚠️ [PlayerLock] CustomSessionId inválido para liberar lock");
            return;
        }

        String key = PLAYER_LOCK_PREFIX + customSessionId;

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("🔓 [PlayerLock] Lock liberado: {}", customSessionId);
            } else {
                log.debug("⚠️ [PlayerLock] Lock já estava liberado ou expirou: {}", customSessionId);
            }

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao liberar lock de {}", customSessionId, e);
        }
    }

    /**
     * ✅ Obtém sessionId vinculado ao jogador
     * 
     * @param customSessionId ID da sessão customizada
     * @return sessionId vinculado, ou null se jogador não está conectado
     */
    public String getPlayerSession(String customSessionId) {
        if (customSessionId == null || customSessionId.isBlank()) {
            return null;
        }

        String key = PLAYER_LOCK_PREFIX + customSessionId;

        try {
            Object session = redisTemplate.opsForValue().get(key);
            return session != null ? (String) session : null;

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao obter sessão de {}", customSessionId, e);
            return null;
        }
    }

    /**
     * ✅ Verifica se jogador tem sessão ativa
     * 
     * @param customSessionId ID da sessão customizada
     * @return true se jogador está conectado, false caso contrário
     * 
     *         Útil para detectar ao abrir Electron se jogador já está conectado.
     */
    public boolean hasActiveSession(String customSessionId) {
        String existingSession = getPlayerSession(customSessionId);
        return existingSession != null;
    }

    /**
     * ✅ Renova TTL do lock do jogador
     * 
     * Chamar periodicamente (ex: a cada mensagem WebSocket) para manter
     * a sessão ativa e prevenir expiração por inatividade.
     * 
     * @param customSessionId ID da sessão customizada
     * @return true se conseguiu renovar, false caso contrário
     */
    public boolean renewPlayerLock(String customSessionId) {
        if (customSessionId == null || customSessionId.isBlank()) {
            return false;
        }

        String key = PLAYER_LOCK_PREFIX + customSessionId;

        try {
            Boolean renewed = redisTemplate.expire(key, LOCK_TTL);

            if (Boolean.TRUE.equals(renewed)) {
                log.debug("♻️ [PlayerLock] TTL renovado para {}", customSessionId);
                return true;
            }

            log.debug("⚠️ [PlayerLock] Não foi possível renovar TTL de {} (lock não existe)",
                    customSessionId);
            return false;

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao renovar lock de {}", customSessionId, e);
            return false;
        }
    }

    /**
     * ✅ Obtém tempo restante do lock do jogador (em segundos)
     * 
     * @param customSessionId ID da sessão customizada
     * @return tempo restante em segundos, ou -1 se não há lock
     */
    public long getPlayerLockTtl(String customSessionId) {
        if (customSessionId == null || customSessionId.isBlank()) {
            return -1;
        }

        String key = PLAYER_LOCK_PREFIX + customSessionId;

        try {
            Long ttl = redisTemplate.getExpire(key);
            return ttl != null ? ttl : -1;

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao obter TTL de {}", customSessionId, e);
            return -1;
        }
    }

    /**
     * ✅ Força liberação do lock do jogador (usar apenas em emergências)
     * 
     * ⚠️ ATENÇÃO: Use apenas se tiver certeza que a sessão está morta
     * e o lock não foi liberado corretamente.
     * 
     * @param customSessionId ID da sessão customizada
     * @return true se conseguiu forçar liberação, false caso contrário
     */
    public boolean forceReleasePlayerLock(String customSessionId) {
        if (customSessionId == null || customSessionId.isBlank()) {
            return false;
        }

        String key = PLAYER_LOCK_PREFIX + customSessionId;

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.warn("⚠️ [PlayerLock] Lock FORÇADAMENTE liberado para: {}", customSessionId);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao forçar liberação de {}", customSessionId, e);
            return false;
        }
    }

    /**
     * ✅ Transfere lock do jogador para nova sessão
     * 
     * Útil para reconexão: se mesma sessão precisa novo ID.
     * 
     * @param customSessionId ID da sessão customizada
     * @param oldSessionId    Sessão antiga
     * @param newSessionId    Nova sessão
     * @return true se conseguiu transferir, false caso contrário
     */
    public boolean transferPlayerLock(String customSessionId, String oldSessionId, String newSessionId) {
        if (customSessionId == null || customSessionId.isBlank()) {
            return false;
        }

        String key = PLAYER_LOCK_PREFIX + customSessionId;

        try {
            // Verificar se lock existe e pertence à sessão antiga
            String currentSession = getPlayerSession(customSessionId);
            if (currentSession == null) {
                log.warn("⚠️ [PlayerLock] Não há lock para transferir: {}", customSessionId);
                return false;
            }

            if (!oldSessionId.equals(currentSession)) {
                log.warn("⚠️ [PlayerLock] Lock de {} pertence a outra sessão: {} (esperado: {})",
                        customSessionId, currentSession, oldSessionId);
                return false;
            }

            // Atualizar para nova sessão
            redisTemplate.opsForValue().set(key, newSessionId, LOCK_TTL);
            log.info("🔄 [PlayerLock] Lock transferido: {} de session {} para {}",
                    customSessionId, oldSessionId, newSessionId);
            return true;

        } catch (Exception e) {
            log.error("❌ [PlayerLock] Erro ao transferir lock de {}", customSessionId, e);
            return false;
        }
    }
}
