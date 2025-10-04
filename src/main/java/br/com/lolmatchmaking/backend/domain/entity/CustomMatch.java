package br.com.lolmatchmaking.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "custom_matches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomMatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "team1_players", columnDefinition = "TEXT", nullable = false)
    private String team1PlayersJson;

    @Column(name = "team2_players", columnDefinition = "TEXT", nullable = false)
    private String team2PlayersJson;

    @Column(name = "winner_team")
    private Integer winnerTeam;

    @Column(length = 50)
    private String status;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "game_mode", length = 20)
    private String gameMode;

    @Column(name = "duration")
    private Integer duration;

    @Column(name = "lp_changes", columnDefinition = "TEXT")
    private String lpChangesJson;

    @Column(name = "average_mmr_team1")
    private Integer averageMmrTeam1;

    @Column(name = "average_mmr_team2")
    private Integer averageMmrTeam2;

    @Column(name = "participants_data", columnDefinition = "TEXT")
    private String participantsDataJson;

    @Column(name = "riot_game_id")
    private String riotGameId;

    @Column(name = "detected_by_lcu")
    private Boolean detectedByLcu;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "custom_lp")
    private Integer customLp;

    @Column(name = "pick_ban_data", columnDefinition = "TEXT")
    private String pickBanDataJson;

    @Column(name = "linked_results", columnDefinition = "TEXT")
    private String linkedResultsJson;

    @Column(name = "actual_winner")
    private Integer actualWinner;

    @Column(name = "actual_duration")
    private Integer actualDuration;

    @Column(name = "riot_id")
    private String riotId;

    @Column(name = "mmr_changes", columnDefinition = "TEXT")
    private String mmrChangesJson;

    @Column(name = "match_leader")
    private String matchLeader;

    @Column(name = "owner_backend_id")
    private String ownerBackendId;

    @Column(name = "owner_heartbeat")
    private Long ownerHeartbeat;

    @Column(name = "lcu_match_data", columnDefinition = "TEXT")
    private String lcuMatchData; // ✅ NOVO: JSON completo da partida do LCU

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null)
            status = "pending";
        if (gameMode == null)
            gameMode = "5v5";
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
