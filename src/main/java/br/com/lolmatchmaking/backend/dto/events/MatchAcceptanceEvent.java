package br.com.lolmatchmaking.backend.dto.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * ✅ Evento de aceitação de partida
 * 
 * Publicado quando um jogador aceita ou recusa uma partida.
 * Permite mostrar progresso em tempo real (ex: "3/10 aceitaram").
 * 
 * CANAL REDIS: match:acceptance
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md#sistema-de-broadcast-em-tempo-real
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MatchAcceptanceEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Tipo do evento (fixo: "match_acceptance")
     */
    private String eventType = "match_acceptance";

    /**
     * Timestamp do evento
     */
    private Instant timestamp;

    /**
     * ID da partida
     */
    private Long matchId;

    /**
     * Nome do jogador que aceitou/recusou
     */
    private String summonerName;

    /**
     * Número de jogadores que aceitaram
     */
    private int accepted;

    /**
     * Total de jogadores que precisam aceitar
     */
    private int total;

    /**
     * Se foi aceitação (true) ou recusa (false)
     */
    private boolean isAcceptance;

    public MatchAcceptanceEvent(Long matchId, String summonerName, int accepted, int total) {
        this.eventType = "match_acceptance";
        this.timestamp = Instant.now();
        this.matchId = matchId;
        this.summonerName = summonerName;
        this.accepted = accepted;
        this.total = total;
        this.isAcceptance = true;
    }
}
