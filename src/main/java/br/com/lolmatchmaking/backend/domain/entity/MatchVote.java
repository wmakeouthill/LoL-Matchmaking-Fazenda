package br.com.lolmatchmaking.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * Entidade que representa um voto de um jogador em uma partida do LCU
 * durante o processo de vinculação de partidas customizadas.
 * 
 * Quando uma partida customizada termina, os jogadores votam em qual
 * partida do histórico do LCU corresponde à partida jogada.
 * Ao atingir 6 votos na mesma partida, ela é vinculada automaticamente.
 */
@Entity
@Table(name = "match_votes", uniqueConstraints = {
        @UniqueConstraint(name = "unique_player_vote_per_match", columnNames = { "match_id", "player_id" })
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * ID da partida customizada no banco de dados
     */
    @Column(name = "match_id", nullable = false)
    private Long matchId;

    /**
     * ID do jogador que está votando
     */
    @Column(name = "player_id", nullable = false)
    private Long playerId;

    /**
     * ID da partida do LCU escolhida pelo jogador (gameId do histórico)
     */
    @Column(name = "lcu_game_id", nullable = false)
    private Long lcuGameId;

    /**
     * Timestamp de quando o voto foi registrado
     */
    @Column(name = "voted_at", nullable = false)
    private Instant votedAt;

    @PrePersist
    public void prePersist() {
        if (votedAt == null) {
            votedAt = Instant.now();
        }
    }
}
