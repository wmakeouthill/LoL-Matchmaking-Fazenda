package br.com.lolmatchmaking.backend.dto.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * ✅ Evento de confirmação de draft
 * 
 * Publicado quando jogadores confirmam seus picks no modal de confirmação.
 * Mostra progresso em tempo real (ex: "7/10 confirmaram").
 * 
 * CANAL REDIS: draft:confirm
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DraftConfirmEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Tipo do evento (fixo: "draft_confirm")
     */
    private String eventType = "draft_confirm";

    /**
     * Timestamp do evento
     */
    private Instant timestamp;

    /**
     * ID da partida
     */
    private Long matchId;

    /**
     * Nome do jogador que confirmou
     */
    private String summonerName;

    /**
     * Número de jogadores que confirmaram
     */
    private Integer confirmed;

    /**
     * Total de jogadores (10)
     */
    private Integer total;

    /**
     * Se todos confirmaram (draft completo)
     */
    private Boolean allConfirmed;

    public DraftConfirmEvent(Long matchId, String summonerName, Integer confirmed, Integer total) {
        this.eventType = "draft_confirm";
        this.timestamp = Instant.now();
        this.matchId = matchId;
        this.summonerName = summonerName;
        this.confirmed = confirmed;
        this.total = total;
        this.allConfirmed = confirmed.equals(total);
    }
}
