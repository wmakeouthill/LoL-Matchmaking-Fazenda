package br.com.lolmatchmaking.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchDTO {
    private String matchId;

    @NotBlank(message = "Status da partida é obrigatório")
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime matchStartedAt;
    private LocalDateTime matchEndedAt;

    @Size(min = 2, max = 10, message = "Uma partida deve ter entre 2 e 10 jogadores")
    private List<PlayerDTO> players;

    private List<TeamDTO> teams;
    private String gameMode;
    private String mapName;
    private Long duration;
    private String winnerTeam;
    private String lobbyPassword;
    private Boolean isCustomGame;
    private String riotMatchId;
    private String participantsData; // ✅ Dados dos participantes processados do LCU
    private String lpChanges; // ✅ JSON com LP changes de cada jogador

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeamDTO {
        private String teamId;
        private String teamName;
        private List<PlayerDTO> players;
        private Boolean isWinner;
    }
}
