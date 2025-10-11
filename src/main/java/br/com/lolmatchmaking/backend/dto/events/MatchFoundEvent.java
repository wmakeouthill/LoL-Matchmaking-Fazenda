package br.com.lolmatchmaking.backend.dto.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * ✅ Evento de partida encontrada
 * 
 * Publicado quando uma partida é formada com 10 jogadores.
 * TODAS as instâncias recebem este evento e enviam notificação
 * match_found para os jogadores conectados nelas.
 * 
 * CANAL REDIS: match:found
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md#sistema-de-broadcast-em-tempo-real
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MatchFoundEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Tipo do evento (fixo: "match_found")
     */
    private String eventType = "match_found";

    /**
     * Timestamp do evento
     */
    private Instant timestamp;

    /**
     * ID da partida criada
     */
    private Long matchId;

    /**
     * Lista de nomes dos 10 jogadores na partida
     */
    private List<String> playerNames;

    /**
     * Time 1 (5 jogadores)
     */
    private List<String> team1;

    /**
     * Time 2 (5 jogadores)
     */
    private List<String> team2;

    public MatchFoundEvent(Long matchId, List<String> playerNames) {
        this.eventType = "match_found";
        this.timestamp = Instant.now();
        this.matchId = matchId;
        this.playerNames = playerNames;

        // Dividir em times
        if (playerNames != null && playerNames.size() >= 10) {
            this.team1 = playerNames.subList(0, 5);
            this.team2 = playerNames.subList(5, 10);
        }
    }
}
