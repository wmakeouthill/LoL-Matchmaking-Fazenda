package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.QueuePlayer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Serviço de gerenciamento de fila usando Redis distribuído.
 * 
 * Substitui o ConcurrentHashMap local por Redis para evitar race conditions
 * entre múltiplas instâncias do backend.
 * 
 * Usa:
 * - Redis Sorted Set para ordenar jogadores por timestamp
 * - Redis String para armazenar dados completos do jogador
 * - Redisson Distributed Lock para sincronização entre instâncias
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisQueueService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    private static final String QUEUE_KEY = "queue";
    private static final String QUEUE_SORTED_KEY = QUEUE_KEY + ":sorted";
    private static final Duration PLAYER_TTL = Duration.ofHours(1);

    /**
     * Adiciona jogador à fila.
     * 
     * @param player Jogador a adicionar
     */
    public void add(QueuePlayer player) {
        try {
            String playerKey = QUEUE_KEY + ":" + player.getId();

            // Salvar dados completos do jogador
            redisTemplate.opsForValue().set(playerKey, player, PLAYER_TTL);

            // Adicionar ao sorted set (ordenado por timestamp)
            redisTemplate.opsForZSet().add(
                    QUEUE_SORTED_KEY,
                    player.getId(),
                    System.currentTimeMillis());

            log.info("✅ Jogador {} adicionado ao Redis (ID: {})",
                    player.getSummonerName(), player.getId());

        } catch (Exception e) {
            log.error("❌ Erro ao adicionar jogador {} ao Redis: {}",
                    player.getSummonerName(), e.getMessage(), e);
            throw new RuntimeException("Erro ao adicionar jogador à fila Redis", e);
        }
    }

    /**
     * Remove jogador da fila.
     * 
     * @param playerId ID do jogador
     */
    public void remove(Long playerId) {
        try {
            String playerKey = QUEUE_KEY + ":" + playerId;

            // Remover dados do jogador
            redisTemplate.delete(playerKey);

            // Remover do sorted set
            redisTemplate.opsForZSet().remove(QUEUE_SORTED_KEY, playerId);

            log.info("✅ Jogador ID {} removido do Redis", playerId);

        } catch (Exception e) {
            log.error("❌ Erro ao remover jogador {} do Redis: {}",
                    playerId, e.getMessage(), e);
            throw new RuntimeException("Erro ao remover jogador da fila Redis", e);
        }
    }

    /**
     * Busca jogadores elegíveis para matchmaking (ordenados por tempo de espera).
     * 
     * @param limit Número máximo de jogadores
     * @return Lista de jogadores elegíveis
     */
    public List<QueuePlayer> getEligible(int limit) {
        try {
            // Buscar IDs dos jogadores mais antigos na fila
            Set<Object> ids = redisTemplate.opsForZSet().range(
                    QUEUE_SORTED_KEY, 0, limit - 1);

            if (ids == null || ids.isEmpty()) {
                log.debug("Nenhum jogador na fila Redis");
                return List.of();
            }

            // Buscar dados completos dos jogadores
            List<String> keys = ids.stream()
                    .map(id -> QUEUE_KEY + ":" + id)
                    .collect(Collectors.toList());

            List<Object> players = redisTemplate.opsForValue().multiGet(keys);

            if (players == null) {
                log.warn("⚠️ multiGet retornou null para keys: {}", keys);
                return List.of();
            }

            List<QueuePlayer> result = players.stream()
                    .filter(obj -> obj instanceof QueuePlayer)
                    .map(obj -> (QueuePlayer) obj)
                    .collect(Collectors.toList());

            log.debug("📋 {} jogadores elegíveis encontrados no Redis", result.size());

            return result;

        } catch (Exception e) {
            log.error("❌ Erro ao buscar jogadores elegíveis do Redis: {}",
                    e.getMessage(), e);
            throw new RuntimeException("Erro ao buscar jogadores da fila Redis", e);
        }
    }

    /**
     * Executa ação com lock distribuído.
     * 
     * Garante que apenas UMA instância do backend execute o matchmaking
     * por vez, evitando race conditions.
     * 
     * @param action Ação a executar
     * @return true se conseguiu o lock e executou, false caso contrário
     */
    public boolean lockWithDistributedLock(Runnable action) {
        RLock lock = redissonClient.getLock("matchmaking");
        try {
            // Tentar adquirir lock por 2 segundos, mantém por 30 segundos
            boolean acquired = lock.tryLock(2, 30, TimeUnit.SECONDS);

            if (acquired) {
                log.debug("🔒 Lock distribuído adquirido");
                action.run();
                return true;
            } else {
                log.debug("⏭️ Lock distribuído não disponível, pulando tick");
                return false;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("⚠️ Thread interrompida ao aguardar lock: {}", e.getMessage());
            return false;

        } catch (Exception e) {
            log.error("❌ Erro ao executar com lock distribuído: {}",
                    e.getMessage(), e);
            throw new RuntimeException("Erro ao executar com lock distribuído", e);

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("🔓 Lock distribuído liberado");
            }
        }
    }

    /**
     * Retorna quantidade de jogadores na fila.
     * 
     * @return Número de jogadores
     */
    public long getQueueSize() {
        Long size = redisTemplate.opsForZSet().size(QUEUE_SORTED_KEY);
        return size != null ? size : 0;
    }

    /**
     * Limpa toda a fila (útil para testes).
     */
    public void clearQueue() {
        try {
            Set<Object> ids = redisTemplate.opsForZSet().range(
                    QUEUE_SORTED_KEY, 0, -1);

            if (ids != null && !ids.isEmpty()) {
                List<String> keys = ids.stream()
                        .map(id -> QUEUE_KEY + ":" + id)
                        .collect(Collectors.toList());

                redisTemplate.delete(keys);
            }

            redisTemplate.delete(QUEUE_SORTED_KEY);

            log.info("🧹 Fila Redis limpa");

        } catch (Exception e) {
            log.error("❌ Erro ao limpar fila Redis: {}", e.getMessage(), e);
        }
    }
}
