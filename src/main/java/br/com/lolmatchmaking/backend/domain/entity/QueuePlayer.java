package br.com.lolmatchmaking.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "queue_players")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueuePlayer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId; // pode ser 0 para bots

    @Column(name = "summoner_name", nullable = false, unique = true)
    private String summonerName;

    @Column(length = 10, nullable = false)
    private String region;

    @Column(name = "custom_lp")
    private Integer customLp;

    @Column(name = "primary_lane")
    private String primaryLane;

    @Column(name = "secondary_lane")
    private String secondaryLane;

    @Column(name = "join_time")
    private Instant joinTime;

    @Column(name = "queue_position")
    private Integer queuePosition;

    @Column(name = "is_active")
    private Boolean active;

    @Column(name = "acceptance_status")
    private Integer acceptanceStatus; // 0=pendente,1=aceito,2=recusado

    @PrePersist
    public void prePersist() {
        if (joinTime == null)
            joinTime = Instant.now();
        if (active == null)
            active = true;
        if (acceptanceStatus == null)
            acceptanceStatus = 0;
    }
}
