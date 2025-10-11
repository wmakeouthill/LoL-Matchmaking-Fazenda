package br.com.lolmatchmaking.backend.dto.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * ✅ Evento de ação em espectadores
 * 
 * Publicado quando jogador muta/desmuta, adiciona ou remove espectador.
 * Sincroniza estado de espectadores em tempo real entre todos os jogadores.
 * 
 * CANAIS REDIS:
 * - spectator:mute - Espectador mutado
 * - spectator:unmute - Espectador desmutado
 * - spectator:add - Espectador adicionado
 * - spectator:remove - Espectador removido
 * 
 * CONTEXTO:
 * Durante draft e game in progress, jogadores podem gerenciar espectadores.
 * Todos os jogadores devem ver as mesmas mudanças em tempo real.
 * 
 * REFERÊNCIA:
 * - Solicitado pelo usuário durante implementação
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpectatorEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Tipo do evento ("mute", "unmute", "add", "remove")
     */
    private String eventType;

    /**
     * Timestamp do evento
     */
    private Instant timestamp;

    /**
     * ID da partida
     */
    private Long matchId;

    /**
     * Nome do espectador afetado
     */
    private String spectatorName;

    /**
     * Jogador que executou a ação
     */
    private String performedBy;

    /**
     * Estado atual de mute (após ação)
     */
    private Boolean isMuted;

    /**
     * Total de espectadores após ação
     */
    private Integer totalSpectators;

    /**
     * Total de espectadores mutados
     */
    private Integer totalMuted;

    public SpectatorEvent(String eventType, Long matchId, String spectatorName, String performedBy) {
        this.eventType = eventType;
        this.timestamp = Instant.now();
        this.matchId = matchId;
        this.spectatorName = spectatorName;
        this.performedBy = performedBy;
        this.isMuted = eventType.equals("mute");
    }
}
