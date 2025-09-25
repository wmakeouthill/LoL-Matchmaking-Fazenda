package br.com.lolmatchmaking.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "matches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Match {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", unique = true, nullable = false)
    private String matchIdentifier;

    @Column(name = "team1_players", columnDefinition = "TEXT", nullable = false)
    private String team1PlayersJson;

    @Column(name = "team2_players", columnDefinition = "TEXT", nullable = false)
    private String team2PlayersJson;

    @Column(name = "winner_team")
    private Integer winnerTeam;

    @Column(name = "average_mmr_team1")
    private Integer averageMmrTeam1;

    @Column(name = "average_mmr_team2")
    private Integer averageMmrTeam2;

    @Column(name = "mmr_changes", columnDefinition = "TEXT")
    private String mmrChangesJson;

    @Column(length = 50)
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "riot_game_id")
    private String riotGameId;

    @Column(name = "actual_winner")
    private Integer actualWinner;

    @Column(name = "actual_duration")
    private Integer actualDuration;

    @Column(name = "riot_id")
    private String riotId;

    // Campos adicionais alinhados ao backend Node
    @Column(name = "pick_ban_data", columnDefinition = "TEXT")
    private String pickBanDataJson;

    @Column(name = "detected_by_lcu")
    private Boolean detectedByLcu; // Representa TINYINT (0/1)

    @Column(name = "linked_results", columnDefinition = "TEXT")
    private String linkedResultsJson;

    @PrePersist
    public void prePersist() {
        createdAt = Instant.now();
        if (status == null)
            status = "pending";
    }
}
