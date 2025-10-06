package br.com.lolmatchmaking.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "players")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Player {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "summoner_name", unique = true, nullable = false)
    private String summonerName;

    @Column(name = "summoner_id", unique = true)
    private String summonerId;

    @Column(name = "puuid", unique = true)
    private String puuid;

    @Column(name = "region", nullable = false, length = 10)
    private String region;

    @Column(name = "current_mmr")
    private Integer currentMmr;

    @Column(name = "peak_mmr")
    private Integer peakMmr;

    @Column(name = "games_played")
    private Integer gamesPlayed;

    @Column(name = "wins")
    private Integer wins;

    @Column(name = "losses")
    private Integer losses;

    @Column(name = "win_streak")
    private Integer winStreak;

    // Custom stats
    @Column(name = "custom_mmr")
    private Integer customMmr;

    @Column(name = "custom_peak_mmr")
    private Integer customPeakMmr;

    @Column(name = "custom_games_played")
    private Integer customGamesPlayed;

    @Column(name = "custom_wins")
    private Integer customWins;

    @Column(name = "custom_losses")
    private Integer customLosses;

    @Column(name = "custom_win_streak")
    private Integer customWinStreak;

    @Column(name = "custom_lp")
    private Integer customLp;

    // Estatísticas detalhadas de custom matches
    @Column(name = "avg_kills")
    private Double avgKills;

    @Column(name = "avg_deaths")
    private Double avgDeaths;

    @Column(name = "avg_assists")
    private Double avgAssists;

    @Column(name = "kda_ratio")
    private Double kdaRatio;

    @Column(name = "favorite_champion")
    private String favoriteChampion;

    @Column(name = "favorite_champion_games")
    private Integer favoriteChampionGames;

    // Estatísticas detalhadas de campeões (armazenadas como TEXT/LONGTEXT para
    // compatibilidade MySQL 5.6)
    @Column(name = "player_stats_draft", columnDefinition = "LONGTEXT")
    private String playerStatsDraft; // JSON com top 5 campeões das custom matches

    @Column(name = "mastery_champions", columnDefinition = "LONGTEXT")
    private String masteryChampions; // JSON com top 3 campeões de maestria (Riot API)

    @Column(name = "ranked_champions", columnDefinition = "LONGTEXT")
    private String rankedChampions; // JSON com top 5 campeões ranked (Riot API)

    @Column(name = "stats_last_updated")
    private Instant statsLastUpdated; // Última atualização das estatísticas

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        createdAt = (createdAt == null) ? now : createdAt;
        updatedAt = now;
        if (currentMmr == null)
            currentMmr = 1000;
        if (peakMmr == null)
            peakMmr = currentMmr;
        if (customMmr == null)
            customMmr = 1000;
        if (customPeakMmr == null)
            customPeakMmr = customMmr;
        if (customLp == null)
            customLp = 0;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
