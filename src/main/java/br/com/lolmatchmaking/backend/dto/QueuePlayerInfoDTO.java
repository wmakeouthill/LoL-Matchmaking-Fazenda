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
    private String summonerName;
    private String tagLine;
    private String region;
    private Integer customLp;
    private Integer mmr; // Alias para customLp para compatibilidade
    private String primaryLane;
    private String secondaryLane;
    private Instant joinTime;
    private Integer queuePosition;
    private Boolean isActive;
    private Integer acceptanceStatus;
    private Boolean isCurrentPlayer;
}
