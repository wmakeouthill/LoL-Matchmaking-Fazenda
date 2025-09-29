package br.com.lolmatchmaking.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketService {
    
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    
    /**
     * Envia uma mensagem para todos os clientes conectados via WebSocket
     */
    public void broadcastMessage(String type, Object data) {
        try {
            Map<String, Object> message = Map.of(
                    "type", type,
                    "data", data,
                    "timestamp", System.currentTimeMillis()
            );
            
            String jsonMessage = objectMapper.writeValueAsString(message);
            messagingTemplate.convertAndSend("/topic/updates", jsonMessage);
            
            log.debug("📡 [WebSocketService] Mensagem enviada: {} -> {}", type, data);
            
        } catch (Exception e) {
            log.error("❌ [WebSocketService] Erro ao enviar mensagem WebSocket", e);
        }
    }
    
    /**
     * Envia uma mensagem para um cliente específico
     */
    public void sendToUser(String userId, String type, Object data) {
        try {
            Map<String, Object> message = Map.of(
                    "type", type,
                    "data", data,
                    "timestamp", System.currentTimeMillis()
            );
            
            String jsonMessage = objectMapper.writeValueAsString(message);
            messagingTemplate.convertAndSendToUser(userId, "/queue/updates", jsonMessage);
            
            log.debug("📡 [WebSocketService] Mensagem enviada para usuário {}: {} -> {}", userId, type, data);
            
        } catch (Exception e) {
            log.error("❌ [WebSocketService] Erro ao enviar mensagem para usuário {}", userId, e);
        }
    }
}
