package br.com.lolmatchmaking.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChampionshipTitle {
    private String title;
    private LocalDateTime date;
    private Integer lpBonus;
}
