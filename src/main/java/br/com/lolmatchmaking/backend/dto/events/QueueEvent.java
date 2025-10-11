package br.com.lolmatchmaking.backend.dto.events;

import br.com.lolmatchmaking.backend.dto.QueueStatusDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * ✅ Evento de atualização da fila
 * 
 * Publicado quando a fila é atualizada (jogador entra/sai ou broadcast
 * automático).
 * Todas as instâncias recebem este evento via Redis Pub/Sub e fazem broadcast
 * via WebSocket para seus clientes conectados.
 * 
 * CANAL REDIS: queue:update
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md#sistema-de-broadcast-em-tempo-real
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueueEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Tipo do evento (ex: "queue_update")
     */
    private String eventType;

    /**
     * Timestamp do evento
     */
    private Instant timestamp;

    /**
     * Status atual da fila
     */
    private QueueStatusDTO queueStatus;

    public QueueEvent(String eventType, QueueStatusDTO queueStatus) {
        this.eventType = eventType;
        this.timestamp = Instant.now();
        this.queueStatus = queueStatus;
    }
}
