package br.com.lolmatchmaking.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftDTO {
    private String draftId;

    @NotNull(message = "Partida é obrigatória")
    private MatchDTO match;

    @NotBlank(message = "Fase do draft é obrigatória")
    private String currentPhase;

    private String currentAction;
    private PlayerDTO currentPlayer;
    private Long timeRemaining;
    private Integer currentRound;
    private Boolean isCompleted;

    private List<BanPickDTO> bansAndPicks;
    private List<PlayerDTO> teamA;
    private List<PlayerDTO> teamB;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BanPickDTO {
        private String type; // BAN ou PICK
        private String championId;
        private String championName;
        private PlayerDTO player;
        private String team;
        private Integer order;
        private LocalDateTime timestamp;
    }
}
