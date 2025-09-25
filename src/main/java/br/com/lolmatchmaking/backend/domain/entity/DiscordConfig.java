package br.com.lolmatchmaking.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "discord_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscordConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guild_id")
    private String guildId;

    @Column(name = "guild_name")
    private String guildName;

    @Column(name = "queue_channel_id")
    private String queueChannelId;

    @Column(name = "queue_channel_name")
    private String queueChannelName;

    @Column(name = "matches_channel_id")
    private String matchesChannelId;

    @Column(name = "matches_channel_name")
    private String matchesChannelName;

    @Column(name = "bot_token")
    private String botToken;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
