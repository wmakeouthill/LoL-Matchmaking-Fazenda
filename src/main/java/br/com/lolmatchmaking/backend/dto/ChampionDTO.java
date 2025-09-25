package br.com.lolmatchmaking.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChampionDTO {
    @NotBlank(message = "ID do campeão é obrigatório")
    private String id;

    private String key;

    @NotBlank(message = "Nome do campeão é obrigatório")
    private String name;

    private String title;
    private String blurb;
    private List<String> tags;
    private String partype;

    private ChampionInfoDTO info;
    private ChampionImageDTO image;
    private ChampionStatsDTO stats;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChampionInfoDTO {
        private Integer attack;
        private Integer defense;
        private Integer magic;
        private Integer difficulty;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChampionImageDTO {
        private String full;
        private String sprite;
        private String group;
        private Integer x;
        private Integer y;
        private Integer w;
        private Integer h;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChampionStatsDTO {
        private Double hp;
        private Double hpperlevel;
        private Double mp;
        private Double mpperlevel;
        private Double movespeed;
        private Double armor;
        private Double armorperlevel;
        private Double spellblock;
        private Double spellblockperlevel;
        private Double attackrange;
        private Double hpregen;
        private Double hpregenperlevel;
        private Double mpregen;
        private Double mpregenperlevel;
        private Double crit;
        private Double critperlevel;
        private Double attackdamage;
        private Double attackdamageperlevel;
        private Double attackspeedperlevel;
        private Double attackspeed;
    }
}
