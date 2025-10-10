package br.com.lolmatchmaking.backend.service.redis;

import br.com.lolmatchmaking.backend.dto.QueuePlayerInfoDTO;
import br.com.lolmatchmaking.backend.dto.QueueStatusDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * ⚡ Redis cache para dados da fila (Queue)
 * 
 * PROBLEMA RESOLVIDO:
 * - Query SQL a cada requisição de status da fila (lento)
 * - Lista de fila recalculada a cada 5 segundos (scheduled)
 * - Múltiplas requisições simultâneas sobrecarregam o SQL
 * 
 * SOLUÇÃO COM REDIS:
 * - Cache de 5 segundos (sincronizado com @Scheduled)
 * - Compartilhado entre todas instâncias do backend
 * - Performance: O(1) ao invés de SELECT * FROM queue_players
 * 
 * ARQUITETURA:
 * - @Scheduled atualiza cache a cada 5s
 * - Requisições retornam do cache (sem SQL)
 * - TTL garante que cache não fica desatualizado
 * 
 * CHAVES REDIS:
 * - queue:status:cache → QueueStatusDTO (JSON)
 * - queue:players:cache → List<QueuePlayerInfoDTO> (JSON)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisQueueCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String QUEUE_STATUS_KEY = "queue:status:cache";
    private static final String QUEUE_PLAYERS_KEY = "queue:players:cache";
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    /**
     * ✅ Armazena status completo da fila no cache
     */
    public void cacheQueueStatus(QueueStatusDTO status) {
        try {
            redisTemplate.opsForValue().set(QUEUE_STATUS_KEY, status, CACHE_TTL);
            log.debug("✅ [RedisQueueCache] Status cacheado: {} jogadores", status.getPlayersInQueue());
        } catch (Exception e) {
            log.error("❌ [RedisQueueCache] Erro ao cachear status", e);
        }
    }

    /**
     * ✅ Busca status da fila do cache
     * 
     * @return QueueStatusDTO ou null se cache expirou
     */
    public QueueStatusDTO getCachedQueueStatus() {
        try {
            Object cached = redisTemplate.opsForValue().get(QUEUE_STATUS_KEY);
            if (cached instanceof QueueStatusDTO) {
                log.debug("✅ [RedisQueueCache] Status retornado do cache");
                return (QueueStatusDTO) cached;
            }
            return null;
        } catch (Exception e) {
            log.error("❌ [RedisQueueCache] Erro ao buscar status do cache", e);
            return null;
        }
    }

    /**
     * ✅ Armazena lista de jogadores no cache
     */
    public void cacheQueuePlayers(List<QueuePlayerInfoDTO> players) {
        try {
            redisTemplate.opsForValue().set(QUEUE_PLAYERS_KEY, players, CACHE_TTL);
            log.debug("✅ [RedisQueueCache] {} jogadores cacheados", players.size());
        } catch (Exception e) {
            log.error("❌ [RedisQueueCache] Erro ao cachear jogadores", e);
        }
    }

    /**
     * ✅ Busca lista de jogadores do cache
     */
    @SuppressWarnings("unchecked")
    public List<QueuePlayerInfoDTO> getCachedQueuePlayers() {
        try {
            Object cached = redisTemplate.opsForValue().get(QUEUE_PLAYERS_KEY);
            if (cached instanceof List) {
                log.debug("✅ [RedisQueueCache] Jogadores retornados do cache");
                return (List<QueuePlayerInfoDTO>) cached;
            }
            return null;
        } catch (Exception e) {
            log.error("❌ [RedisQueueCache] Erro ao buscar jogadores do cache", e);
            return null;
        }
    }

    /**
     * ✅ Limpa cache da fila
     */
    public void clearCache() {
        try {
            redisTemplate.delete(QUEUE_STATUS_KEY);
            redisTemplate.delete(QUEUE_PLAYERS_KEY);
            log.info("🗑️ [RedisQueueCache] Cache da fila limpo");
        } catch (Exception e) {
            log.error("❌ [RedisQueueCache] Erro ao limpar cache", e);
        }
    }
}
