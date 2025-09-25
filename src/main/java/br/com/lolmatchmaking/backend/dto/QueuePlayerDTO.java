package br.com.lolmatchmaking.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueuePlayerDTO {
    private String queuePlayerId;

    @NotNull(message = "Jogador é obrigatório")
    private PlayerDTO player;

    @NotBlank(message = "Região é obrigatória")
    private String region;

    private Long estimatedWaitTime;
    private Long queueTime;
    private Boolean isPriority;
    private String preferredRole;
    private String secondaryRole;

    public Long getCurrentWaitTime() {
        if (queueTime == null) {
            return 0L;
        }
        return System.currentTimeMillis() - queueTime;
    }
}
