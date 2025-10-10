package br.com.lolmatchmaking.backend.service.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 🚀 SERVIÇO REDIS - EVENTOS WEBSOCKET PENDENTES
 * 
 * Gerencia eventos pendentes e requisições LCU em Redis para garantir entrega
 * após reconexão.
 * 
 * PROBLEMA RESOLVIDO:
 * - Backend envia "match_found" mas Electron está desconectado → Evento perdido
 * - Electron reconecta 5s depois → Não recebe evento
 * - Usuário fica aguardando indefinidamente
 * 
 * SOLUÇÃO:
 * - Eventos enfileirados em Redis se entrega falhar
 * - Ao reconectar, backend envia todos os eventos pendentes
 * - Zero eventos perdidos, experiência perfeita para usuário
 * 
 * CHAVES REDIS:
 * - ws:pending:{sessionId} → List de PendingEvent (FIFO queue)
 * - ws:lcu_request:{requestId} → LCURequest (Hash: sessionId, request,
 * response, status)
 * 
 * TTL:
 * - Eventos pendentes: 1 hora (eventos antigos > 1h são irrelevantes)
 * - LCU requests: 30 segundos (timeout rápido para requisições LCU)
 * 
 * OPERAÇÕES:
 * - queueEvent: Enfileira evento para envio posterior
 * - getPendingEvents: Busca todos os eventos pendentes de uma sessão
 * - clearPendingEvents: Limpa eventos após envio bem-sucedido
 * - registerLcuRequest: Registra requisição LCU pendente
 * - updateLcuResponse: Atualiza com resposta do LCU
 * - getLcuRequest: Busca requisição LCU por ID
 * 
 * @author LoL Matchmaking Backend Team
 * @since Migração Redis WebSockets
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisWebSocketEventService {

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    // TTLs
    private static final Duration PENDING_EVENT_TTL = Duration.ofHours(1); // 1 hora
    private static final Duration LCU_REQUEST_TTL = Duration.ofSeconds(30); // 30 segundos

    // Prefixos de chaves
    private static final String PENDING_KEY_PREFIX = "ws:pending:"; // sessionId → List<PendingEvent>
    private static final String LCU_REQUEST_KEY_PREFIX = "ws:lcu_request:"; // requestId → LCURequest

    /**
     * Evento pendente aguardando envio
     */
    @Data
    @Builder
    public static class PendingEvent {
        private String eventType; // Ex: "match_found", "draft_started"
        private Map<String, Object> payload; // Dados do evento
        private Instant queuedAt; // Quando foi enfileirado
        private int retryCount; // Tentativas de envio
    }

    /**
     * Requisição LCU pendente (RPC entre backend e Electron)
     */
    @Data
    @Builder
    public static class LCURequest {
        private String requestId; // UUID único
        private String sessionId; // Sessão WebSocket do Electron
        private String endpoint; // Endpoint LCU (ex: /lol-champ-select/v1/session)
        private String method; // GET, POST, PUT, DELETE
        private String requestBody; // Body da requisição (JSON)
        private String responseBody; // Resposta do LCU (JSON)
        private String status; // PENDING, SUCCESS, TIMEOUT, ERROR
        private Instant createdAt; // Quando foi criada
        private Instant respondedAt; // Quando foi respondida
    }

    /**
     * Enfileira evento para envio posterior.
     * Usado quando envio via WebSocket falha (desconectado, erro de rede).
     * 
     * Exemplo de uso:
     * - Backend tenta enviar "match_found" mas WebSocket está fechado
     * - queueEvent("sessionId123", "match_found", {matchId: 456})
     * - Electron reconecta → Backend envia todos os pendentes
     * 
     * @param sessionId ID da sessão WebSocket
     * @param eventType Tipo do evento (match_found, draft_started, etc)
     * @param payload   Dados do evento
     * @return true se enfileirou com sucesso, false em caso de erro
     */
    public boolean queueEvent(String sessionId, String eventType, Map<String, Object> payload) {
        try {
            if (sessionId == null || sessionId.isBlank() || eventType == null || eventType.isBlank()) {
                log.warn(
                        "⚠️ [RedisWSEvent] Tentativa de enfileirar evento com dados inválidos: sessionId={}, eventType={}",
                        sessionId, eventType);
                return false;
            }

            PendingEvent event = PendingEvent.builder()
                    .eventType(eventType)
                    .payload(payload != null ? payload : Collections.emptyMap())
                    .queuedAt(Instant.now())
                    .retryCount(0)
                    .build();

            // Serializar para JSON
            String eventJson = objectMapper.writeValueAsString(event);

            // Adicionar à lista Redis
            RList<String> pendingList = redisson.getList(PENDING_KEY_PREFIX + sessionId);
            pendingList.add(eventJson);
            pendingList.expire(PENDING_EVENT_TTL);

            log.info("📨 [RedisWSEvent] Evento enfileirado: sessionId={}, eventType={}, payload={}",
                    sessionId, eventType, payload);

            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWSEvent] Erro ao enfileirar evento: sessionId={}, eventType={}",
                    sessionId, eventType, e);
            return false;
        }
    }

    /**
     * Busca todos os eventos pendentes de uma sessão.
     * Chamado quando Electron reconecta para enviar eventos perdidos.
     * 
     * @param sessionId ID da sessão WebSocket
     * @return Lista de eventos pendentes (FIFO - ordem cronológica)
     */
    public List<PendingEvent> getPendingEvents(String sessionId) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return Collections.emptyList();
            }

            RList<String> pendingList = redisson.getList(PENDING_KEY_PREFIX + sessionId);

            if (pendingList.isEmpty()) {
                log.debug("📭 [RedisWSEvent] Nenhum evento pendente para sessão: {}", sessionId);
                return Collections.emptyList();
            }

            List<PendingEvent> events = new ArrayList<>();
            for (String eventJson : pendingList) {
                try {
                    PendingEvent event = objectMapper.readValue(eventJson, PendingEvent.class);
                    events.add(event);
                } catch (Exception e) {
                    log.warn("⚠️ [RedisWSEvent] Erro ao desserializar evento pendente: {}", eventJson, e);
                }
            }

            log.info("📬 [RedisWSEvent] {} eventos pendentes encontrados para sessão: {}", events.size(), sessionId);
            return events;

        } catch (Exception e) {
            log.error("❌ [RedisWSEvent] Erro ao buscar eventos pendentes: sessionId={}", sessionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * Limpa eventos pendentes após envio bem-sucedido.
     * 
     * @param sessionId ID da sessão WebSocket
     * @return true se limpou com sucesso, false em caso de erro
     */
    public boolean clearPendingEvents(String sessionId) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return false;
            }

            RList<String> pendingList = redisson.getList(PENDING_KEY_PREFIX + sessionId);
            int size = pendingList.size();

            pendingList.delete();

            log.info("🗑️ [RedisWSEvent] {} eventos pendentes limpos para sessão: {}", size, sessionId);
            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWSEvent] Erro ao limpar eventos pendentes: sessionId={}", sessionId, e);
            return false;
        }
    }

    /**
     * Incrementa contador de retry de um evento.
     * Útil para rastrear eventos problemáticos.
     * 
     * @param sessionId  ID da sessão WebSocket
     * @param eventIndex Índice do evento na lista (0-based)
     * @return true se incrementou com sucesso, false em caso de erro
     */
    public boolean incrementRetryCount(String sessionId, int eventIndex) {
        try {
            if (sessionId == null || sessionId.isBlank() || eventIndex < 0) {
                return false;
            }

            RList<String> pendingList = redisson.getList(PENDING_KEY_PREFIX + sessionId);

            if (eventIndex >= pendingList.size()) {
                log.warn("⚠️ [RedisWSEvent] Índice de evento inválido: sessionId={}, index={}, size={}",
                        sessionId, eventIndex, pendingList.size());
                return false;
            }

            String eventJson = pendingList.get(eventIndex);
            PendingEvent event = objectMapper.readValue(eventJson, PendingEvent.class);

            event.setRetryCount(event.getRetryCount() + 1);

            String updatedJson = objectMapper.writeValueAsString(event);
            pendingList.set(eventIndex, updatedJson);

            log.debug("🔄 [RedisWSEvent] Retry count incrementado: sessionId={}, eventType={}, retryCount={}",
                    sessionId, event.getEventType(), event.getRetryCount());

            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWSEvent] Erro ao incrementar retry count: sessionId={}, index={}",
                    sessionId, eventIndex, e);
            return false;
        }
    }

    /**
     * Registra requisição LCU pendente (Backend → Electron → LCU).
     * 
     * Fluxo:
     * 1. Backend precisa abrir loja para Player5
     * 2. Backend cria LCURequest e envia via WebSocket
     * 3. Electron recebe, faz requisição ao LCU local
     * 4. Electron responde via WebSocket com resultado
     * 5. Backend atualiza LCURequest com resposta
     * 
     * @param requestId   UUID único da requisição
     * @param sessionId   Sessão WebSocket do Electron
     * @param endpoint    Endpoint LCU (ex: /lol-store/v1/catalog)
     * @param method      GET, POST, PUT, DELETE
     * @param requestBody Body da requisição (JSON, pode ser null)
     * @return true se registrou com sucesso, false em caso de erro
     */
    public boolean registerLcuRequest(String requestId, String sessionId, String endpoint,
            String method, String requestBody) {
        try {
            if (requestId == null || requestId.isBlank() || sessionId == null || sessionId.isBlank()
                    || endpoint == null || endpoint.isBlank() || method == null || method.isBlank()) {
                log.warn("⚠️ [RedisWSEvent] Tentativa de registrar LCU request com dados inválidos");
                return false;
            }

            LCURequest lcuRequest = LCURequest.builder()
                    .requestId(requestId)
                    .sessionId(sessionId)
                    .endpoint(endpoint)
                    .method(method)
                    .requestBody(requestBody)
                    .status("PENDING")
                    .createdAt(Instant.now())
                    .build();

            RMap<String, String> requestMap = redisson.getMap(LCU_REQUEST_KEY_PREFIX + requestId);
            requestMap.put("requestId", requestId);
            requestMap.put("sessionId", sessionId);
            requestMap.put("endpoint", endpoint);
            requestMap.put("method", method);
            requestMap.put("requestBody", requestBody != null ? requestBody : "");
            requestMap.put("status", "PENDING");
            requestMap.put("createdAt", lcuRequest.getCreatedAt().toString());
            requestMap.expire(LCU_REQUEST_TTL);

            log.info("📤 [RedisWSEvent] LCU request registrada: id={}, endpoint={} {}, sessionId={}",
                    requestId, method, endpoint, sessionId);

            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWSEvent] Erro ao registrar LCU request: requestId={}", requestId, e);
            return false;
        }
    }

    /**
     * Atualiza requisição LCU com resposta do Electron.
     * 
     * @param requestId    UUID da requisição
     * @param responseBody Resposta do LCU (JSON)
     * @param status       SUCCESS, TIMEOUT, ERROR
     * @return true se atualizou com sucesso, false em caso de erro
     */
    public boolean updateLcuResponse(String requestId, String responseBody, String status) {
        try {
            if (requestId == null || requestId.isBlank() || status == null || status.isBlank()) {
                log.warn("⚠️ [RedisWSEvent] Tentativa de atualizar LCU response com dados inválidos");
                return false;
            }

            RMap<String, String> requestMap = redisson.getMap(LCU_REQUEST_KEY_PREFIX + requestId);

            if (requestMap.isEmpty()) {
                log.warn("⚠️ [RedisWSEvent] LCU request não encontrada: {}", requestId);
                return false;
            }

            requestMap.put("responseBody", responseBody != null ? responseBody : "");
            requestMap.put("status", status);
            requestMap.put("respondedAt", Instant.now().toString());

            log.info("📥 [RedisWSEvent] LCU response atualizada: id={}, status={}", requestId, status);

            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWSEvent] Erro ao atualizar LCU response: requestId={}", requestId, e);
            return false;
        }
    }

    /**
     * Busca requisição LCU por ID.
     * 
     * @param requestId UUID da requisição
     * @return Optional com LCURequest se encontrado, empty se não existe
     */
    public Optional<LCURequest> getLcuRequest(String requestId) {
        try {
            if (requestId == null || requestId.isBlank()) {
                return Optional.empty();
            }

            RMap<String, String> requestMap = redisson.getMap(LCU_REQUEST_KEY_PREFIX + requestId);

            if (requestMap.isEmpty()) {
                log.debug("❌ [RedisWSEvent] LCU request NÃO encontrada: {}", requestId);
                return Optional.empty();
            }

            LCURequest lcuRequest = LCURequest.builder()
                    .requestId(requestMap.get("requestId"))
                    .sessionId(requestMap.get("sessionId"))
                    .endpoint(requestMap.get("endpoint"))
                    .method(requestMap.get("method"))
                    .requestBody(requestMap.get("requestBody"))
                    .responseBody(requestMap.get("responseBody"))
                    .status(requestMap.get("status"))
                    .createdAt(Instant.parse(requestMap.get("createdAt")))
                    .respondedAt(requestMap.get("respondedAt") != null
                            ? Instant.parse(requestMap.get("respondedAt"))
                            : null)
                    .build();

            log.debug("🔍 [RedisWSEvent] LCU request encontrada: id={}, status={}", requestId, lcuRequest.getStatus());
            return Optional.of(lcuRequest);

        } catch (Exception e) {
            log.error("❌ [RedisWSEvent] Erro ao buscar LCU request: requestId={}", requestId, e);
            return Optional.empty();
        }
    }

    /**
     * Remove requisição LCU após processamento.
     * 
     * @param requestId UUID da requisição
     * @return true se removeu com sucesso, false em caso de erro
     */
    public boolean removeLcuRequest(String requestId) {
        try {
            if (requestId == null || requestId.isBlank()) {
                return false;
            }

            RMap<String, String> requestMap = redisson.getMap(LCU_REQUEST_KEY_PREFIX + requestId);
            requestMap.delete();

            log.info("🗑️ [RedisWSEvent] LCU request removida: {}", requestId);
            return true;

        } catch (Exception e) {
            log.error("❌ [RedisWSEvent] Erro ao remover LCU request: requestId={}", requestId, e);
            return false;
        }
    }

    /**
     * Lista todas as requisições LCU pendentes.
     * Útil para monitoramento e timeout.
     * 
     * @return Map de requestId → status
     */
    public Map<String, String> getAllPendingLcuRequests() {
        try {
            Map<String, String> requests = new HashMap<>();

            Iterable<String> keys = redisson.getKeys().getKeysByPattern(LCU_REQUEST_KEY_PREFIX + "*");

            for (String key : keys) {
                String requestId = key.substring(LCU_REQUEST_KEY_PREFIX.length());
                RMap<String, String> requestMap = redisson.getMap(key);
                String status = requestMap.get("status");

                if (status != null) {
                    requests.put(requestId, status);
                }
            }

            log.debug("📊 [RedisWSEvent] LCU requests pendentes: {}", requests.size());
            return requests;

        } catch (Exception e) {
            log.error("❌ [RedisWSEvent] Erro ao listar LCU requests pendentes", e);
            return Collections.emptyMap();
        }
    }

    /**
     * Limpa requisições LCU expiradas (status PENDING após 30s).
     * Executado periodicamente via @Scheduled.
     * 
     * @return Quantidade de requests limpas
     */
    public int cleanupExpiredLcuRequests() {
        try {
            int cleanedCount = 0;
            Instant now = Instant.now();

            Iterable<String> keys = redisson.getKeys().getKeysByPattern(LCU_REQUEST_KEY_PREFIX + "*");

            for (String key : keys) {
                RMap<String, String> requestMap = redisson.getMap(key);

                String status = requestMap.get("status");
                String createdAtStr = requestMap.get("createdAt");

                if ("PENDING".equals(status) && createdAtStr != null) {
                    Instant createdAt = Instant.parse(createdAtStr);
                    long secondsElapsed = Duration.between(createdAt, now).getSeconds();

                    if (secondsElapsed > 30) {
                        // Marcar como TIMEOUT
                        requestMap.put("status", "TIMEOUT");
                        requestMap.put("respondedAt", now.toString());

                        log.warn("⏰ [RedisWSEvent] LCU request timeout: id={}, elapsed={}s",
                                key.substring(LCU_REQUEST_KEY_PREFIX.length()), secondsElapsed);

                        cleanedCount++;
                    }
                }
            }

            if (cleanedCount > 0) {
                log.info("🧹 [RedisWSEvent] {} LCU requests marcadas como TIMEOUT", cleanedCount);
            }

            return cleanedCount;

        } catch (Exception e) {
            log.error("❌ [RedisWSEvent] Erro ao limpar LCU requests expiradas", e);
            return 0;
        }
    }
}
