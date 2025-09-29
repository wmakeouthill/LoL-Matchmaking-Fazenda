package br.com.lolmatchmaking.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "discord_lol_links")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiscordLoLLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discord_id", nullable = false, unique = true)
    private String discordId;

    @Column(name = "discord_username")
    private String discordUsername;

    @Column(name = "game_name", nullable = false)
    private String gameName;

    @Column(name = "tag_line", nullable = false)
    private String tagLine;

    @Column(name = "summoner_name", nullable = true)
    private String summonerName;

    @Column(name = "verified", nullable = false)
    private Boolean verified = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_used")
    private LocalDateTime lastUsed;

    @Column(name = "region")
    private String region;

    @Column(name = "linked_at")
    private LocalDateTime linkedAt;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        linkedAt = LocalDateTime.now();
        lastUsed = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        lastUsed = LocalDateTime.now();
    }

    // Helper method to get full game name with tag
    public String getFullGameName() {
        return gameName + "#" + tagLine;
    }
}
