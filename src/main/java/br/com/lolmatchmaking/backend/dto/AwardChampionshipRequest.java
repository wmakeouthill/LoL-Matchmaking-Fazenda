package br.com.lolmatchmaking.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AwardChampionshipRequest {
    private String summonerName; // Jogador alvo
    private String championshipTitle; // Título do campeonato
    private Integer lpBonus; // Bônus de LP
}
