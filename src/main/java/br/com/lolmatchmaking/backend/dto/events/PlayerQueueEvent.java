package br.com.lolmatchmaking.backend.dto.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * ✅ Evento de jogador entrando/saindo da fila
 * 
 * Publicado quando um jogador entra ou sai da fila.
 * Permite notificação em tempo real para todos os clientes.
 * 
 * CANAIS REDIS:
 * - queue:player_joined (jogador entrou)
 * - queue:player_left (jogador saiu)
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md#sistema-de-broadcast-em-tempo-real
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayerQueueEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Tipo do evento ("player_joined" ou "player_left")
     */
    private String eventType;

    /**
     * Timestamp do evento
     */
    private Instant timestamp;

    /**
     * Nome do jogador
     */
    private String summonerName;

    /**
     * Região do jogador
     */
    private String region;

    public PlayerQueueEvent(String eventType, String summonerName) {
        this.eventType = eventType;
        this.timestamp = Instant.now();
        this.summonerName = summonerName;
    }
}
