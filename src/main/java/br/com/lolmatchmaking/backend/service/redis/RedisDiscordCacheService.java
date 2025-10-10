package br.com.lolmatchmaking.backend.service.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ⚡ Redis cache para dados do Discord
 * 
 * PROBLEMA RESOLVIDO:
 * - Cache local não é compartilhado entre instâncias do backend
 * - Cada requisição busca do Discord (lento)
 * 
 * SOLUÇÃO COM REDIS:
 * - Cache compartilhado entre todas instâncias
 * - TTL de 2 segundos (atualização rápida)
 * - Performance: O(1) para buscar lista cacheada
 * 
 * CHAVES REDIS:
 * - discord:users:cache → List<DiscordUser> (JSON)
 * - discord:status:cache → Map<String, Object> (JSON)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisDiscordCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String USERS_CACHE_KEY = "discord:users:cache";
    private static final String STATUS_CACHE_KEY = "discord:status:cache";
    private static final Duration CACHE_TTL = Duration.ofSeconds(2);

    /**
     * ✅ Armazena lista de usuários no cache
     */
    public void cacheUsers(List<?> users) {
        try {
            redisTemplate.opsForValue().set(USERS_CACHE_KEY, users, CACHE_TTL);
            log.debug("✅ [RedisDiscordCache] {} usuários cacheados", users.size());
        } catch (Exception e) {
            log.error("❌ [RedisDiscordCache] Erro ao cachear usuários", e);
        }
    }

    /**
     * ✅ Busca lista de usuários do cache
     * 
     * @return Lista de usuários ou null se cache expirou
     */
    @SuppressWarnings("unchecked")
    public List<Object> getCachedUsers() {
        try {
            Object cached = redisTemplate.opsForValue().get(USERS_CACHE_KEY);
            if (cached instanceof List) {
                log.debug("✅ [RedisDiscordCache] Usuários retornados do cache");
                return (List<Object>) cached;
            }
            return null;
        } catch (Exception e) {
            log.error("❌ [RedisDiscordCache] Erro ao buscar usuários do cache", e);
            return null;
        }
    }

    /**
     * ✅ Armazena status do Discord no cache
     */
    public void cacheStatus(Map<String, Object> status) {
        try {
            redisTemplate.opsForValue().set(STATUS_CACHE_KEY, status, CACHE_TTL);
            log.debug("✅ [RedisDiscordCache] Status cacheado");
        } catch (Exception e) {
            log.error("❌ [RedisDiscordCache] Erro ao cachear status", e);
        }
    }

    /**
     * ✅ Busca status do cache
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCachedStatus() {
        try {
            Object cached = redisTemplate.opsForValue().get(STATUS_CACHE_KEY);
            if (cached instanceof Map) {
                log.debug("✅ [RedisDiscordCache] Status retornado do cache");
                return (Map<String, Object>) cached;
            }
            return null;
        } catch (Exception e) {
            log.error("❌ [RedisDiscordCache] Erro ao buscar status do cache", e);
            return null;
        }
    }

    /**
     * ✅ Limpa todo o cache do Discord
     */
    public void clearCache() {
        try {
            redisTemplate.delete(USERS_CACHE_KEY);
            redisTemplate.delete(STATUS_CACHE_KEY);
            log.info("🗑️ [RedisDiscordCache] Cache limpo");
        } catch (Exception e) {
            log.error("❌ [RedisDiscordCache] Erro ao limpar cache", e);
        }
    }
}
