package br.com.lolmatchmaking.backend.service.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * ⚡ Redis service para gerenciar espectadores em tempo real
 * 
 * PROBLEMA RESOLVIDO:
 * - Mute/unmute de espectadores não era notificado em tempo real
 * - Modal de espectadores não atualizava automaticamente
 * - Estado de mute não era compartilhado entre backends
 * 
 * SOLUÇÃO COM REDIS:
 * - Armazena estado de mute por espectador
 * - TTL de 3 horas (duração máxima de uma partida)
 * - Broadcasts via Pub/Sub para atualização em tempo real
 * 
 * CHAVES REDIS:
 * - spectator:mute:{matchId}:{discordId} → Boolean (true = mutado)
 * - spectator:list:{matchId} → Set<String> discordIds
 * 
 * FLUXO:
 * 1. Mute/unmute → Atualiza Redis
 * 2. Publica evento: spectator_update:{matchId}
 * 3. Outros backends/frontends recebem via WebSocket
 * 4. Modal atualiza automaticamente
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSpectatorService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String MUTE_PREFIX = "spectator:mute:";
    private static final String LIST_PREFIX = "spectator:list:";
    private static final Duration TTL = Duration.ofHours(3);

    /**
     * ✅ Registra que um espectador foi mutado
     */
    public void markAsMuted(Long matchId, String discordId) {
        try {
            String key = MUTE_PREFIX + matchId + ":" + discordId;
            redisTemplate.opsForValue().set(key, true, TTL);
            log.info("🔇 [RedisSpectator] Espectador {} mutado em match {}", discordId, matchId);
        } catch (Exception e) {
            log.error("❌ [RedisSpectator] Erro ao marcar mute", e);
        }
    }

    /**
     * ✅ Registra que um espectador foi desmutado
     */
    public void markAsUnmuted(Long matchId, String discordId) {
        try {
            String key = MUTE_PREFIX + matchId + ":" + discordId;
            redisTemplate.delete(key);
            log.info("🔊 [RedisSpectator] Espectador {} desmutado em match {}", discordId, matchId);
        } catch (Exception e) {
            log.error("❌ [RedisSpectator] Erro ao marcar unmute", e);
        }
    }

    /**
     * ✅ Verifica se espectador está mutado no Redis
     */
    public boolean isMuted(Long matchId, String discordId) {
        try {
            String key = MUTE_PREFIX + matchId + ":" + discordId;
            Boolean muted = (Boolean) redisTemplate.opsForValue().get(key);
            return Boolean.TRUE.equals(muted);
        } catch (Exception e) {
            log.error("❌ [RedisSpectator] Erro ao verificar mute", e);
            return false;
        }
    }

    /**
     * ✅ Adiciona espectador à lista
     */
    public void addSpectator(Long matchId, String discordId) {
        try {
            String key = LIST_PREFIX + matchId;
            redisTemplate.opsForSet().add(key, discordId);
            redisTemplate.expire(key, TTL);
            log.debug("➕ [RedisSpectator] Espectador {} adicionado a match {}", discordId, matchId);
        } catch (Exception e) {
            log.error("❌ [RedisSpectator] Erro ao adicionar espectador", e);
        }
    }

    /**
     * ✅ Remove espectador da lista
     */
    public void removeSpectator(Long matchId, String discordId) {
        try {
            String key = LIST_PREFIX + matchId;
            redisTemplate.opsForSet().remove(key, discordId);

            // Remover estado de mute também
            markAsUnmuted(matchId, discordId);

            log.debug("➖ [RedisSpectator] Espectador {} removido de match {}", discordId, matchId);
        } catch (Exception e) {
            log.error("❌ [RedisSpectator] Erro ao remover espectador", e);
        }
    }

    /**
     * ✅ Busca todos os espectadores de uma partida
     */
    public Set<String> getSpectators(Long matchId) {
        try {
            String key = LIST_PREFIX + matchId;
            Set<Object> spectatorsObj = redisTemplate.opsForSet().members(key);

            if (spectatorsObj == null) {
                return Collections.emptySet();
            }

            Set<String> spectators = new HashSet<>();
            for (Object obj : spectatorsObj) {
                spectators.add(obj.toString());
            }

            return spectators;

        } catch (Exception e) {
            log.error("❌ [RedisSpectator] Erro ao buscar espectadores de match {}", matchId, e);
            return Collections.emptySet();
        }
    }

    /**
     * ✅ Limpa todos os espectadores de uma partida
     */
    public void clearMatch(Long matchId) {
        try {
            // Buscar todos os espectadores primeiro
            Set<String> spectators = getSpectators(matchId);

            // Limpar estados de mute
            for (String discordId : spectators) {
                String muteKey = MUTE_PREFIX + matchId + ":" + discordId;
                redisTemplate.delete(muteKey);
            }

            // Limpar lista
            String listKey = LIST_PREFIX + matchId;
            redisTemplate.delete(listKey);

            log.info("🗑️ [RedisSpectator] Match {} limpo ({} espectadores)", matchId, spectators.size());

        } catch (Exception e) {
            log.error("❌ [RedisSpectator] Erro ao limpar match {}", matchId, e);
        }
    }
}
