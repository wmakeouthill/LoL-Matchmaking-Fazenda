package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.EventInbox;
import br.com.lolmatchmaking.backend.domain.repository.EventInboxRepository;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncObserver {

    private final EventInboxRepository eventInboxRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final MultiBackendSyncService multiBackendSyncService;

    @Value("${app.backend.id:backend-1}")
    private String backendId;

    @Value("${app.backend.sync.enabled:true}")
    private boolean syncEnabled;

    @Value("${app.sync.observer-interval:5000}")
    private long observerIntervalMs;

    // Cache de eventos processados para evitar duplicação
    private final Map<String, Instant> processedEvents = new ConcurrentHashMap<>();
    private final Map<String, Integer> eventCounts = new ConcurrentHashMap<>();

    /**
     * Inicializa o observador de sincronização
     */
    public void initialize() {
        log.info("👁️ Inicializando SyncObserver...");
        startEventObservation();
        log.info("✅ SyncObserver inicializado");
    }

    /**
     * Observa eventos de sincronização
     */
    @Scheduled(fixedRate = 5000) // A cada 5 segundos
    @Async
    public void startEventObservation() {
        if (!syncEnabled) {
            log.info("🔕 SyncObserver disabled by configuration (app.backend.sync.enabled=false)");
            return;
        }

        try {
            observeEvents();
        } catch (Exception e) {
            log.error("❌ Erro no observador de eventos", e);
        }
    }

    /**
     * Observa e processa eventos pendentes
     */
    @Transactional
    public void observeEvents() {
        try {
            List<EventInbox> pendingEvents = eventInboxRepository.findByProcessedFalseOrderByCreatedAtAsc();

            for (EventInbox event : pendingEvents) {
                if (shouldProcessEvent(event)) {
                    processEvent(event);
                    markEventAsProcessed(event);
                }
            }

            // Limpar cache de eventos antigos
            cleanupProcessedEventsCache();

        } catch (Exception e) {
            log.error("❌ Erro ao observar eventos", e);
        }
    }

    /**
     * Verifica se deve processar um evento
     */
    private boolean shouldProcessEvent(EventInbox event) {
        String eventKey = event.getEventId() + "_" + event.getBackendId();

        // Verificar se já foi processado recentemente
        Instant lastProcessed = processedEvents.get(eventKey);
        if (lastProcessed != null &&
                lastProcessed.isAfter(Instant.now().minusSeconds(30))) {
            return false;
        }

        // Verificar se não é do próprio backend
        if (backendId.equals(event.getBackendId())) {
            return false;
        }

        return true;
    }

    /**
     * Processa um evento específico
     */
    private void processEvent(EventInbox event) {
        try {
            String eventType = event.getEventType();
            String eventKey = event.getEventId() + "_" + event.getBackendId();

            log.debug("📢 Processando evento: {} de {}", eventType, event.getBackendId());

            switch (eventType) {
                case "player_joined_queue":
                    handlePlayerJoinedQueue(event);
                    break;
                case "player_left_queue":
                    handlePlayerLeftQueue(event);
                    break;
                case "match_created":
                    handleMatchCreated(event);
                    break;
                case "match_updated":
                    handleMatchUpdated(event);
                    break;
                case "match_cancelled":
                    handleMatchCancelled(event);
                    break;
                case "draft_started":
                    handleDraftStarted(event);
                    break;
                case "draft_updated":
                    handleDraftUpdated(event);
                    break;
                case "game_started":
                    handleGameStarted(event);
                    break;
                case "game_ended":
                    handleGameEnded(event);
                    break;
                default:
                    log.warn("⚠️ Tipo de evento desconhecido: {}", eventType);
            }

            // Atualizar contadores
            eventCounts.merge(eventType, 1, Integer::sum);
            processedEvents.put(eventKey, Instant.now());

        } catch (Exception e) {
            log.error("❌ Erro ao processar evento {}", event.getEventType(), e);
        }
    }

    /**
     * Marca evento como processado
     */
    private void markEventAsProcessed(EventInbox event) {
        event.setProcessed(true);
        eventInboxRepository.save(event);
    }

    /**
     * Limpa cache de eventos processados
     */
    private void cleanupProcessedEventsCache() {
        Instant cutoff = Instant.now().minusSeconds(300); // 5 minutos

        processedEvents.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }

    // Handlers para diferentes tipos de eventos
    private void handlePlayerJoinedQueue(EventInbox event) {
        log.info("👤 Jogador entrou na fila em outro backend");
        // Implementar lógica específica se necessário
    }

    private void handlePlayerLeftQueue(EventInbox event) {
        log.info("👤 Jogador saiu da fila em outro backend");
        // Implementar lógica específica se necessário
    }

    private void handleMatchCreated(EventInbox event) {
        log.info("🎮 Partida criada em outro backend");
        // Implementar lógica específica se necessário
    }

    private void handleMatchUpdated(EventInbox event) {
        log.info("🔄 Partida atualizada em outro backend");
        // Implementar lógica específica se necessário
    }

    private void handleMatchCancelled(EventInbox event) {
        log.info("❌ Partida cancelada em outro backend");
        // Implementar lógica específica se necessário
    }

    private void handleDraftStarted(EventInbox event) {
        log.info("🎯 Draft iniciado em outro backend");
        // Implementar lógica específica se necessário
    }

    private void handleDraftUpdated(EventInbox event) {
        log.info("🎯 Draft atualizado em outro backend");
        // Implementar lógica específica se necessário
    }

    private void handleGameStarted(EventInbox event) {
        log.info("🎮 Jogo iniciado em outro backend");
        // Implementar lógica específica se necessário
    }

    private void handleGameEnded(EventInbox event) {
        log.info("🏁 Jogo finalizado em outro backend");
        // Implementar lógica específica se necessário
    }

    /**
     * Registra um evento para observação
     */
    public void registerEvent(String eventType, Object data, Long matchId) {
        try {
            EventInbox event = new EventInbox();
            event.setEventId(generateEventId());
            event.setEventType(eventType);
            event.setData(data != null ? data.toString() : "");
            event.setMatchId(matchId);
            event.setBackendId(backendId);
            event.setCreatedAt(Instant.now());
            event.setProcessed(false);

            eventInboxRepository.save(event);

            log.debug("📝 Evento registrado para observação: {}", eventType);

        } catch (Exception e) {
            log.error("❌ Erro ao registrar evento para observação", e);
        }
    }

    /**
     * Gera ID único para evento
     */
    private String generateEventId() {
        return "event_" + System.currentTimeMillis() + "_" +
                Thread.currentThread().getId();
    }

    /**
     * Obtém estatísticas do observador
     */
    public Map<String, Object> getObserverStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("backendId", backendId);
        stats.put("processedEvents", processedEvents.size());
        stats.put("eventCounts", eventCounts);
        stats.put("observerInterval", observerIntervalMs);
        stats.put("lastObservation", Instant.now().toString());
        return stats;
    }

    /**
     * Força observação de eventos
     */
    @Transactional
    public void forceObservation() {
        log.info("🔄 Forçando observação de eventos...");
        observeEvents();
    }

    /**
     * Limpa todos os eventos processados
     */
    @Transactional
    public void clearProcessedEvents() {
        try {
            List<EventInbox> processedEvents = eventInboxRepository.findAll().stream()
                    .filter(EventInbox::getProcessed)
                    .toList();

            eventInboxRepository.deleteAll(processedEvents);
            this.processedEvents.clear();
            this.eventCounts.clear();

            log.info("🧹 {} eventos processados removidos", processedEvents.size());

        } catch (Exception e) {
            log.error("❌ Erro ao limpar eventos processados", e);
        }
    }
}
