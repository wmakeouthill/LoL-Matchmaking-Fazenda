package br.com.lolmatchmaking.backend.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO para estatísticas detalhadas de campeões do jogador
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerChampionStatsDTO {

    /**
     * Top 5 campeões mais pickados nas custom matches
     */
    private List<ChampionPickStats> draftChampions;

    /**
     * Top 3 campeões de maior maestria (Riot API)
     */
    private List<ChampionMasteryStats> masteryChampions;

    /**
     * Top 5 campeões ranked (mais jogados + maior winrate)
     */
    private List<ChampionRankedStats> rankedChampions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChampionPickStats {
        private Integer championId;
        private String championName;
        private Integer gamesPlayed;
        private Integer wins;
        private Integer losses;
        private Double winRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChampionMasteryStats {
        private Integer championId;
        private String championName;
        private Integer championLevel;
        private Long championPoints;
        private Long championPointsSinceLastLevel;
        private Long championPointsUntilNextLevel;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChampionRankedStats {
        private Integer championId;
        private String championName;
        private Integer gamesPlayed;
        private Integer wins;
        private Integer losses;
        private Double winRate;
        private String tier; // Para filtrar apenas ranked
    }
}
