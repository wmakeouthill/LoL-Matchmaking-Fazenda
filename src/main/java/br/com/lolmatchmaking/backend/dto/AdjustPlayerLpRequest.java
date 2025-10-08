package br.com.lolmatchmaking.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdjustPlayerLpRequest {
    private String summonerName; // Jogador alvo
    private Integer lpAdjustment; // Valor a adicionar/remover (positivo ou negativo)
    private String reason; // Motivo do ajuste (opcional)
}
