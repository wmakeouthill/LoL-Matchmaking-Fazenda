package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.EventInbox;
import br.com.lolmatchmaking.backend.domain.repository.EventInboxRepository;
import br.com.lolmatchmaking.backend.dto.MatchInfoDTO;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiBackendSyncService {

    private final EventInboxRepository eventInboxRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${app.backend.id:backend-1}")
    private String backendId;

    @Value("${app.backend.heartbeat-interval:30000}")
    private long heartbeatInterval;

    @Value("${app.backend.other-backends:}")
    private List<String> otherBackends;

    // Cache de ownership de partidas
    private final Map<Long, String> matchOwnership = new ConcurrentHashMap<>();
    private final Map<String, Instant> backendHeartbeats = new ConcurrentHashMap<>();
    private final Map<String, String> backendStatuses = new ConcurrentHashMap<>();

    /**
     * Inicializa o serviço de sincronização
     */
    public void initialize() {
        log.info("🔄 Inicializando MultiBackendSyncService...");
        startHeartbeat();
        startEventProcessing();
        log.info("✅ MultiBackendSyncService inicializado");
    }

    /**
     * Registra um evento para sincronização
     */
    @Transactional
    public void registerEvent(String eventType, Object data, Long matchId) {
        try {
            EventInbox event = new EventInbox();
            event.setEventType(eventType);
            event.setData(objectMapper.writeValueAsString(data));
            event.setMatchId(matchId);
            event.setBackendId(backendId);
            event.setCreatedAt(Instant.now());
            event.setProcessed(false);

            eventInboxRepository.save(event);

            log.debug("📝 Evento registrado: {} para partida {}", eventType, matchId);
        } catch (Exception e) {
            log.error("❌ Erro ao registrar evento", e);
        }
    }

    /**
     * Processa eventos pendentes
     */
    @Scheduled(fixedRate = 5000) // A cada 5 segundos
    @Transactional
    public void processPendingEvents() {
        try {
            List<EventInbox> pendingEvents = eventInboxRepository.findByProcessedFalseOrderByCreatedAtAsc();

            for (EventInbox event : pendingEvents) {
                processEvent(event);
                event.setProcessed(true);
                eventInboxRepository.save(event);
            }
        } catch (Exception e) {
            log.error("❌ Erro ao processar eventos pendentes", e);
        }
    }

    /**
     * Processa um evento específico
     */
    private void processEvent(EventInbox event) {
        try {
            switch (event.getEventType()) {
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
                    log.warn("⚠️ Tipo de evento desconhecido: {}", event.getEventType());
            }
        } catch (Exception e) {
            log.error("❌ Erro ao processar evento {}", event.getEventType(), e);
        }
    }

    /**
     * Verifica ownership de uma partida
     */
    public boolean isMatchOwner(Long matchId) {
        String owner = matchOwnership.get(matchId);
        return backendId.equals(owner);
    }

    /**
     * Reivindica ownership de uma partida
     */
    public boolean claimMatchOwnership(Long matchId) {
        String currentOwner = matchOwnership.get(matchId);

        if (currentOwner == null || !isBackendAlive(currentOwner)) {
            matchOwnership.put(matchId, backendId);
            log.info("🎯 Reivindicando ownership da partida {}", matchId);
            return true;
        }

        return false;
    }

    /**
     * Libera ownership de uma partida
     */
    public void releaseMatchOwnership(Long matchId) {
        matchOwnership.remove(matchId);
        log.info("🔓 Liberando ownership da partida {}", matchId);
    }

    /**
     * Sincroniza com outros backends
     */
    @Scheduled(fixedRate = 30000) // A cada 30 segundos
    @Async
    public void syncWithOtherBackends() {
        for (String backendUrl : otherBackends) {
            try {
                syncWithBackend(backendUrl);
            } catch (Exception e) {
                log.warn("⚠️ Erro ao sincronizar com backend {}", backendUrl, e);
            }
        }
    }

    /**
     * Sincroniza com um backend específico
     */
    private void syncWithBackend(String backendUrl) {
        try {
            // Verificar heartbeat do backend
            String heartbeatUrl = backendUrl + "/api/health/heartbeat";
            String response = restTemplate.getForObject(heartbeatUrl, String.class);

            if (response != null) {
                backendHeartbeats.put(backendUrl, Instant.now());
                backendStatuses.put(backendUrl, "online");
            }
        } catch (Exception e) {
            backendStatuses.put(backendUrl, "offline");
            log.debug("Backend {} offline", backendUrl);
        }
    }

    /**
     * Verifica se um backend está vivo
     */
    private boolean isBackendAlive(String backendId) {
        Instant lastHeartbeat = backendHeartbeats.get(backendId);
        if (lastHeartbeat == null) {
            return false;
        }

        return Instant.now().minusMillis(heartbeatInterval * 2).isBefore(lastHeartbeat);
    }

    /**
     * Inicia heartbeat
     */
    @Scheduled(fixedRate = 30000) // A cada 30 segundos
    public void startHeartbeat() {
        backendHeartbeats.put(backendId, Instant.now());
        log.debug("💓 Heartbeat enviado: {}", backendId);
    }

    /**
     * Inicia processamento de eventos
     */
    @Async
    public void startEventProcessing() {
        log.info("🔄 Iniciando processamento de eventos...");
    }

    // Handlers para diferentes tipos de eventos
    private void handleMatchCreated(EventInbox event) {
        log.info("📢 Processando match_created: {}", event.getMatchId());
        // Implementar lógica específica
    }

    private void handleMatchUpdated(EventInbox event) {
        log.info("📢 Processando match_updated: {}", event.getMatchId());
        // Implementar lógica específica
    }

    private void handleMatchCancelled(EventInbox event) {
        log.info("📢 Processando match_cancelled: {}", event.getMatchId());
        // Implementar lógica específica
    }

    private void handleDraftStarted(EventInbox event) {
        log.info("📢 Processando draft_started: {}", event.getMatchId());
        // Implementar lógica específica
    }

    private void handleDraftUpdated(EventInbox event) {
        log.info("📢 Processando draft_updated: {}", event.getMatchId());
        // Implementar lógica específica
    }

    private void handleGameStarted(EventInbox event) {
        log.info("📢 Processando game_started: {}", event.getMatchId());
        // Implementar lógica específica
    }

    private void handleGameEnded(EventInbox event) {
        log.info("📢 Processando game_ended: {}", event.getMatchId());
        // Implementar lógica específica
    }

    /**
     * Obtém status dos backends
     */
    public Map<String, Object> getBackendStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("currentBackend", backendId);
        status.put("backends", backendStatuses);
        status.put("heartbeats", backendHeartbeats);
        status.put("matchOwnership", matchOwnership);
        return status;
    }
}
