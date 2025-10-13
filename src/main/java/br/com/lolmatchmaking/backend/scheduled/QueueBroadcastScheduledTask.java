package br.com.lolmatchmaking.backend.scheduled;

import br.com.lolmatchmaking.backend.dto.QueueStatusDTO;
import br.com.lolmatchmaking.backend.service.EventBroadcastService;
import br.com.lolmatchmaking.backend.service.QueueManagementService;
import br.com.lolmatchmaking.backend.service.redis.RedisQueueCacheService;
import br.com.lolmatchmaking.backend.websocket.SessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ✅ Scheduled Task de Broadcast Automático da Fila
 * 
 * PROBLEMA RESOLVIDO:
 * - Fila não atualizava em tempo real (precisava clicar em "Atualizar")
 * - Discord funcionava em tempo real, mas fila usava polling
 * - Clientes só viam mudanças após 15 segundos (polling interval)
 * 
 * SOLUÇÃO:
 * - Broadcast automático da fila a cada 3 segundos
 * - Publicar via Redis Pub/Sub (todas instâncias fazem broadcast)
 * - TODOS os clientes recebem atualização via WebSocket push
 * - Fila funciona EM TEMPO REAL (igual Discord)
 * 
 * FLUXO:
 * 1. A cada 3 segundos:
 * - Buscar status da fila do banco
 * - Cachear no Redis
 * - Publicar via Pub/Sub
 * 2. Todas as instâncias recebem evento
 * 3. Todas fazem broadcast via WebSocket
 * 4. TODOS os clientes recebem atualização
 * 
 * RESULTADO:
 * - Fila atualiza automaticamente (sem clicar)
 * - Sincronizado entre todos os jogadores
 * - Igual Discord (tempo real)
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md#scheduled-task-de-broadcast-automático
 * - TODO-CORRECOES-PRODUCAO.md#41
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueBroadcastScheduledTask {

    private final QueueManagementService queueManagementService;
    private final EventBroadcastService eventBroadcastService;
    private final RedisQueueCacheService redisQueueCache;
    private final SessionRegistry sessionRegistry;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * ✅ BROADCAST AUTOMÁTICO DA FILA A CADA 3 SEGUNDOS
     * 
     * Garante que todos os clientes tenham a fila atualizada em tempo real,
     * mesmo que não haja eventos de entrar/sair.
     * 
     * ⚠️ IMPORTANTE: Apenas jogadores que estão REALMENTE na fila (não em
     * draft/game)
     * 
     * Executado a cada 3 segundos para manter fila sincronizada.
     */
    @Scheduled(fixedRate = 3000) // A cada 3 segundos
    public void broadcastQueueStatusAutomatic() {
        try {
            // ✅ NOVO: VERIFICAR SE HÁ SESSÕES ATIVAS ANTES DE FAZER BROADCAST
            if (!hasActiveSessions()) {
                log.debug("⏭️ [QueueBroadcast] Nenhuma sessão ativa, pulando broadcast...");
                return;
            }

            // 1. Buscar status da fila (APENAS jogadores que estão aguardando)
            // getQueueStatus já filtra jogadores que estão em match/draft
            QueueStatusDTO status = queueManagementService.getQueueStatus(null);

            if (status == null) {
                log.warn("⚠️ [QueueBroadcast] Status nulo, pulando broadcast");
                return;
            }

            // ✅ FILTRO: Não fazer broadcast se não houver jogadores na fila
            // Isso evita enviar eventos vazios para jogadores em draft/game
            if (status.getPlayersInQueue() == 0) {
                log.debug("⏭️ [QueueBroadcast] Fila vazia, pulando broadcast (jogadores podem estar em partida)");
                return;
            }

            // 2. Cachear no Redis (para performance)
            redisQueueCache.cacheQueueStatus(status);

            // 3. Publicar via Pub/Sub (todas instâncias fazem broadcast)
            // ✅ NOTA: Apenas jogadores na tela de fila receberão este evento
            // Jogadores em draft/game ignoram este evento
            eventBroadcastService.publishQueueUpdate(status);

            log.info("✅ [QueueBroadcast] Broadcast automático executado: {} jogadores em tempo real",
                    status.getPlayersInQueue());

        } catch (Exception e) {
            log.error("❌ [QueueBroadcast] Erro no broadcast automático da fila", e);
        }
    }

    /**
     * ✅ Limpeza periódica de estados expirados (a cada 5 minutos)
     * 
     * Remove estados de jogadores que ficaram órfãos no Redis.
     */
    @Scheduled(fixedRate = 300000) // A cada 5 minutos
    public void cleanupExpiredStates() {
        try {
            log.debug("🧹 [QueueBroadcast] Limpeza de estados expirados...");
            // A limpeza é feita automaticamente pelo TTL do Redis
            // Este método serve apenas para log/monitoramento

        } catch (Exception e) {
            log.error("❌ [QueueBroadcast] Erro na limpeza de estados", e);
        }
    }

    /**
     * ✅ Log de estatísticas do sistema (a cada 1 minuto)
     * 
     * Útil para monitoramento e debugging.
     */
    @Scheduled(fixedRate = 60000) // A cada 1 minuto
    public void logSystemStats() {
        try {
            // ✅ NOVO: VERIFICAR SE HÁ SESSÕES ATIVAS ANTES DE FAZER LOG
            if (!hasActiveSessions()) {
                log.debug("⏭️ [QueueBroadcast] Nenhuma sessão ativa, pulando log de estatísticas...");
                return;
            }

            QueueStatusDTO status = queueManagementService.getQueueStatus(null);

            if (status != null) {
                log.info("📊 [Estatísticas] Fila: {} jogadores | Tempo médio: {}s",
                        status.getPlayersInQueue(), status.getAverageWaitTime());
            }

        } catch (Exception e) {
            log.error("❌ [QueueBroadcast] Erro ao coletar estatísticas", e);
        }
    }

    // ✅ NOVO: Cache infinito - só invalida quando alguém conecta
    private volatile boolean hasActiveSessionsCache = false;
    private volatile boolean cacheInitialized = false;
    private volatile long lastCacheInvalidationCheck = 0;

    /**
     * ✅ NOVO: Verifica se há sessões WebSocket ativas com cache infinito
     * 
     * Cache infinito: se não tem sessões, não verifica mais até alguém conectar
     * Só verifica novamente quando cache é invalidado via Redis
     */
    private boolean hasActiveSessions() {
        try {
            // ✅ VERIFICAR REDIS: Se cache foi invalidado por nova conexão
            boolean cacheInvalidated = checkRedisCacheInvalidation();

            // ✅ CACHE INFINITO: Se já verificou e não havia sessões, não verificar mais
            if (cacheInitialized && !hasActiveSessionsCache && !cacheInvalidated) {
                return false; // Retorna cache (sem log, sem verificação)
            }

            // Verificar se há sessões WebSocket ativas (primeira vez ou após invalidação)
            int activeSessions = sessionRegistry.getActiveSessionCount();

            // Atualizar cache
            cacheInitialized = true;
            hasActiveSessionsCache = (activeSessions > 0);

            if (activeSessions > 0) {
                log.info("✅ [QueueBroadcast] {} sessões ativas encontradas - sistema acordou", activeSessions);
                return true;
            }

            // ✅ Log apenas uma vez quando sistema "dorme"
            log.info("💤 [Cloud Run] Sistema dormindo - 0 sessões ativas (instância pode hibernar)");
            return false;

        } catch (Exception e) {
            log.error("❌ [QueueBroadcast] Erro ao verificar sessões ativas", e);
            // Em caso de erro, assumir que há sessões (comportamento seguro)
            return true;
        }
    }

    /**
     * ✅ NOVO: Verifica se cache foi invalidado via Redis
     * 
     * @return true se cache foi invalidado por nova conexão
     */
    private boolean checkRedisCacheInvalidation() {
        try {
            String cacheInvalidationKey = "cache:session_invalidation";
            Object invalidationTimestamp = redisTemplate.opsForValue().get(cacheInvalidationKey);

            if (invalidationTimestamp != null) {
                long timestamp = Long.parseLong(invalidationTimestamp.toString());
                if (timestamp > lastCacheInvalidationCheck) {
                    lastCacheInvalidationCheck = timestamp;
                    log.debug("🔄 [QueueBroadcast] Cache invalidado via Redis (timestamp: {})", timestamp);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("Erro ao verificar invalidação de cache via Redis: {}", e.getMessage());
            return false;
        }
    }
}
