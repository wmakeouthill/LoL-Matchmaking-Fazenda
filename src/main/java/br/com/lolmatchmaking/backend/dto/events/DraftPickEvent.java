package br.com.lolmatchmaking.backend.dto.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * ✅ Evento de pick/ban no draft
 * 
 * Publicado quando um jogador faz pick ou ban de campeão.
 * Sincroniza ações do draft em tempo real entre todos os jogadores.
 * 
 * CANAIS REDIS:
 * - draft:pick - Campeão escolhido
 * - draft:ban - Campeão banido
 * - draft:edit - Pick editado durante confirmação
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DraftPickEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Tipo do evento ("pick", "ban", "edit")
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
     * Nome do jogador
     */
    private String summonerName;

    /**
     * ID do campeão
     */
    private Integer championId;

    /**
     * Nome do campeão
     */
    private String championName;

    /**
     * Time (1 ou 2)
     */
    private Integer team;

    /**
     * Turno do draft
     */
    private Integer turn;

    /**
     * Se é edição durante modal de confirmação
     */
    private Boolean isEdit;

    public DraftPickEvent(String eventType, Long matchId, String summonerName, Integer championId) {
        this.eventType = eventType;
        this.timestamp = Instant.now();
        this.matchId = matchId;
        this.summonerName = summonerName;
        this.championId = championId;
        this.isEdit = false;
    }
}
