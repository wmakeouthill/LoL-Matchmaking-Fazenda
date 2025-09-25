package br.com.lolmatchmaking.backend.scheduled;

import br.com.lolmatchmaking.backend.service.MatchQueueService;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchmakingScheduledTasks {

    private final MatchQueueService matchQueueService;
    private final MatchmakingWebSocketService webSocketService;

    /**
     * Processa a fila de matchmaking a cada 30 segundos
     */
    @Scheduled(fixedRate = 30000)
    public void processMatchmakingQueue() {
        try {
            log.debug("Executando processamento da fila de matchmaking");
            matchQueueService.processQueue();
        } catch (Exception e) {
            log.error("Erro ao processar fila de matchmaking", e);
        }
    }

    /**
     * Limpa entradas expiradas da fila a cada 5 minutos
     */
    @Scheduled(fixedRate = 300000)
    public void cleanupExpiredQueueEntries() {
        try {
            log.debug("Executando limpeza de entradas expiradas da fila");
            matchQueueService.cleanupExpiredQueueEntries();
        } catch (Exception e) {
            log.error("Erro ao limpar entradas expiradas da fila", e);
        }
    }

    /**
     * Envia estatísticas da fila via WebSocket a cada minuto
     */
    @Scheduled(fixedRate = 60000)
    public void broadcastQueueStats() {
        try {
            log.debug("Enviando estatísticas da fila via WebSocket");
            var queueStatus = matchQueueService.getQueueStatus();
            webSocketService.broadcastQueueUpdate(queueStatus);
        } catch (Exception e) {
            log.error("Erro ao enviar estatísticas da fila", e);
        }
    }

    /**
     * Log de estatísticas do sistema a cada 10 minutos
     */
    @Scheduled(fixedRate = 600000)
    public void logSystemStats() {
        try {
            var queueStatus = matchQueueService.getQueueStatus();
            int activeSessions = webSocketService.getActiveSessionsCount();

            log.info("Estatísticas do Sistema - Fila: {} jogadores, WebSocket: {} sessões ativas",
                    queueStatus.size(), activeSessions);
        } catch (Exception e) {
            log.error("Erro ao coletar estatísticas do sistema", e);
        }
    }
}
