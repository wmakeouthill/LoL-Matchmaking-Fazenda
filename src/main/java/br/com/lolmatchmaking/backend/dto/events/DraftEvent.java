package br.com.lolmatchmaking.backend.dto.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * ✅ Evento de draft (pick/ban de campeões)
 * 
 * Publicado durante o draft para sincronizar picks e bans entre jogadores.
 * 
 * CANAIS REDIS:
 * - draft:started (draft iniciado)
 * - draft:pick (campeão escolhido)
 * - draft:ban (campeão banido)
 * - draft:completed (draft completo)
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md#sistema-de-broadcast-em-tempo-real
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DraftEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Tipo do evento ("draft_started", "draft_pick", "draft_ban",
     * "draft_completed")
     */
    private String eventType;

    /**
     * Timestamp do evento
     */
    private Instant timestamp;

    /**
     * ID da partida
     */
    private Long matchId;

    /**
     * Nome do jogador (se aplicável)
     */
    private String summonerName;

    /**
     * ID do campeão (para pick/ban)
     */
    private Integer championId;

    /**
     * Nome do campeão (para pick/ban)
     */
    private String championName;

    /**
     * Time (1 ou 2)
     */
    private Integer team;

    /**
     * Turno atual do draft
     */
    private Integer currentTurn;

    /**
     * Tempo restante (segundos)
     */
    private Integer timeRemaining;

    /**
     * Jogadores na partida
     */
    private List<String> playerNames;

    public DraftEvent(String eventType, Long matchId, List<String> playerNames) {
        this.eventType = eventType;
        this.timestamp = Instant.now();
        this.matchId = matchId;
        this.playerNames = playerNames;
    }
}
