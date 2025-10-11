package br.com.lolmatchmaking.backend.dto.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * ✅ Evento de votação de vencedor
 * 
 * Publicado quando jogador vota no time vencedor após o jogo.
 * Mostra progresso em tempo real da votação.
 * 
 * CANAL REDIS: game:winner_vote
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WinnerVoteEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Tipo do evento (fixo: "winner_vote")
     */
    private String eventType = "winner_vote";

    /**
     * Timestamp do evento
     */
    private Instant timestamp;

    /**
     * ID da partida
     */
    private Long matchId;

    /**
     * Nome do jogador que votou
     */
    private String summonerName;

    /**
     * Time votado como vencedor (1 ou 2)
     */
    private Integer votedTeam;

    /**
     * Votos atuais para time 1
     */
    private Integer votesTeam1;

    /**
     * Votos atuais para time 2
     */
    private Integer votesTeam2;

    /**
     * Total de votos necessários
     */
    private Integer totalVotesNeeded;

    /**
     * Se votação está completa
     */
    private Boolean votingComplete;

    public WinnerVoteEvent(Long matchId, String summonerName, Integer votedTeam,
            Integer votesTeam1, Integer votesTeam2, Integer totalVotesNeeded) {
        this.eventType = "winner_vote";
        this.timestamp = Instant.now();
        this.matchId = matchId;
        this.summonerName = summonerName;
        this.votedTeam = votedTeam;
        this.votesTeam1 = votesTeam1;
        this.votesTeam2 = votesTeam2;
        this.totalVotesNeeded = totalVotesNeeded;
        this.votingComplete = (votesTeam1 + votesTeam2) >= totalVotesNeeded;
    }
}
