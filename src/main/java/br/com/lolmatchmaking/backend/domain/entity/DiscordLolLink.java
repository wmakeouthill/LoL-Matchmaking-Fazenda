package br.com.lolmatchmaking.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "discord_lol_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscordLolLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discord_id", unique = true, nullable = false)
    private String discordId;

    @Column(name = "discord_username", nullable = false)
    private String discordUsername;

    @Column(name = "game_name", nullable = false)
    private String gameName;

    @Column(name = "tag_line", nullable = false, length = 10)
    private String tagLine;

    @Column(name = "summoner_name", nullable = false)
    private String summonerName;

    @Column(name = "region")
    private String region;

    @Column(name = "linked_at")
    private Instant linkedAt;

    @Column(name = "active")
    private Boolean active;

    @Column(name = "verified")
    private Boolean verified;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "last_used")
    private Instant lastUsed;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (verified == null) verified = false;
        if (active == null) active = true;
        if (linkedAt == null) linkedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
