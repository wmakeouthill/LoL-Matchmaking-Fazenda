package br.com.lolmatchmaking.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MatchInfoDTO {
    private Long id;
    private String title;
    private String description;
    private List<String> team1Players;
    private List<String> team2Players;
    private Integer winnerTeam;
    private String status;
    private String createdBy;
    private Instant createdAt;
    private Instant completedAt;
    private String gameMode;
    private Integer duration;
    private String lpChanges;
    private Integer averageMmrTeam1;
    private Integer averageMmrTeam2;
    private String participantsData;
    private String riotGameId;
    private Boolean detectedByLcu;
    private String notes;
    private Integer customLp;
    private Instant updatedAt;
    private String pickBanData;
    private String linkedResults;
    private Integer actualWinner;
    private Integer actualDuration;
    private String riotId;
    private String mmrChanges;
    private String matchLeader;
    private String ownerBackendId;
    private Long ownerHeartbeat;
}
