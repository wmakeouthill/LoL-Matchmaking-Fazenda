package br.com.lolmatchmaking.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueuePlayerInfoDTO {
    private Long id;
    private String summonerName;
    private String tagLine;
    private String region;
    private Integer customLp;
    private String primaryLane;
    private String secondaryLane;
    private LocalDateTime joinTime;
    private Integer queuePosition;
    private Boolean isActive;
    private Integer acceptanceStatus;
    private Boolean isCurrentPlayer;
}
