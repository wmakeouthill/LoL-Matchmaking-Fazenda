package br.com.lolmatchmaking.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(
    name = "event_inbox",
    uniqueConstraints = {
        @UniqueConstraint(name = "uniq_event_id", columnNames = {"event_id"})
    },
    indexes = {
        @Index(name = "idx_event_type", columnList = "event_type"),
        @Index(name = "idx_match_id", columnList = "match_id"),
        @Index(name = "idx_backend", columnList = "backend_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventInbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", length = 191, nullable = false)
    private String eventId;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @Column(name = "match_id")
    private Long matchId;

    @Column(name = "backend_id", length = 100, nullable = false)
    private String backendId;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(columnDefinition = "TEXT")
    private String data; // campo adicional usado pelos serviços

    @Column(nullable = false)
    private Long timestamp;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "processed")
    private Boolean processed;

    @PrePersist
    public void prePersist() {
        if (timestamp == null) timestamp = System.currentTimeMillis();
        if (receivedAt == null) receivedAt = Instant.now();
        if (createdAt == null) createdAt = receivedAt;
        if (processed == null) processed = false;
    }
}
