package br.com.lolmatchmaking.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueuePlayerInfoDTO {
    private Long id;
    private Long playerId; // ID do jogador (para bots é negativo)
    private String summonerName;
    private String tagLine;
    private String region;
    private Integer customLp;
    private Integer mmr; // Alias para customLp para compatibilidade
    private String primaryLane;
    private String secondaryLane;
    private String assignedLane; // Lane atribuída pelo balanceamento
    private Boolean isAutofill; // Se foi forçado para outra lane
    private Integer teamIndex; // 0-4 (time 1) ou 5-9 (time 2)
    private Instant joinTime;
    private Integer queuePosition;
    private Boolean isActive;
    private Integer acceptanceStatus;
    private Boolean isCurrentPlayer;
    private Integer profileIconId; // Ícone do perfil
}
