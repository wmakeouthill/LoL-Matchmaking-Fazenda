package br.com.lolmatchmaking.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerDTO {
    private String summonerId;

    @NotBlank(message = "Nome do invocador é obrigatório")
    private String summonerName;

    private String puuid;
    private String gameName;
    private String tagLine;

    @PositiveOrZero(message = "Nível deve ser positivo")
    private Integer summonerLevel;

    @PositiveOrZero(message = "Ícone de perfil deve ser positivo")
    private Integer profileIconId;

    private String tier;
    private String rank;

    @PositiveOrZero(message = "Pontos da liga devem ser positivos")
    private Integer leaguePoints;

    @PositiveOrZero(message = "Vitórias devem ser positivas")
    private Integer wins;

    @PositiveOrZero(message = "Derrotas devem ser positivas")
    private Integer losses;

    private String discordId;
    private String discordUsername;
    private String region;
    private Boolean isOnline;
    private String status;

    // Custom match statistics
    @PositiveOrZero(message = "Custom LP deve ser positivo ou zero")
    private Integer customLp;

    @PositiveOrZero(message = "Custom MMR deve ser positivo")
    private Integer customMmr;

    @PositiveOrZero(message = "Jogos customizados devem ser positivos")
    private Integer customGamesPlayed;

    @PositiveOrZero(message = "Vitórias customizadas devem ser positivas")
    private Integer customWins;

    @PositiveOrZero(message = "Derrotas customizadas devem ser positivas")
    private Integer customLosses;

    @PositiveOrZero(message = "Sequência de vitórias customizada deve ser positiva")
    private Integer customWinStreak;

    // Estatísticas detalhadas de partidas customizadas
    private Double avgKills;
    private Double avgDeaths;
    private Double avgAssists;
    private Double kdaRatio;
    private String favoriteChampion;
    private Integer favoriteChampionGames;

    // Estatísticas detalhadas de campeões (JSON strings do banco)
    private String playerStatsDraft; // JSON com top 5 campeões das custom matches
    private String masteryChampions; // JSON com top 3 campeões de maestria (Riot API)
    private String rankedChampions; // JSON com top 5 campeões ranked (Riot API)
    private String statsLastUpdated; // Última atualização das estatísticas

    // Títulos de campeonato (JSON string)
    private String championshipTitles; // JSON array: [{"title": "...", "date": "...", "lpBonus": 100}]

    public Double getWinRate() {
        if (wins == null || losses == null || (wins + losses) == 0) {
            return null;
        }
        return (double) wins / (wins + losses) * 100;
    }

    public Double getCustomWinRate() {
        if (customWins == null || customLosses == null || (customWins + customLosses) == 0) {
            return null;
        }
        return (double) customWins / (customWins + customLosses) * 100;
    }
}
