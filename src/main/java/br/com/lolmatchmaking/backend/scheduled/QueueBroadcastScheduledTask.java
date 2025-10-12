package br.com.lolmatchmaking.backend.scheduled;

import br.com.lolmatchmaking.backend.dto.QueueStatusDTO;
import br.com.lolmatchmaking.backend.service.EventBroadcastService;
import br.com.lolmatchmaking.backend.service.QueueManagementService;
import br.com.lolmatchmaking.backend.service.redis.RedisQueueCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            QueueStatusDTO status = queueManagementService.getQueueStatus(null);

            if (status != null) {
                log.info("📊 [Estatísticas] Fila: {} jogadores | Tempo médio: {}s",
                        status.getPlayersInQueue(), status.getAverageWaitTime());
            }

        } catch (Exception e) {
            log.error("❌ [QueueBroadcast] Erro ao coletar estatísticas", e);
        }
    }
}
