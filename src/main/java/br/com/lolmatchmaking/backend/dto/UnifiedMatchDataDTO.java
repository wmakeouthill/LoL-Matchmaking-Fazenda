package br.com.lolmatchmaking.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnifiedMatchDataDTO {

    private Long matchId;
    private String phase;
    private String status;
    private Long createdAt;
    private Integer averageMmrTeam1;
    private Integer averageMmrTeam2;

    // ========================================
    // TIMES (estrutura hierárquica unificada)
    // ========================================

    /**
     * ✅ Estrutura ÚNICA para times em TODAS as fases
     * 
     * Formato:
     * {
     * "blue": {
     * "name": "Blue Team",
     * "side": "blue",
     * "players": [ ... ]
     * },
     * "red": {
     * "name": "Red Team",
     * "side": "red",
     * "players": [ ... ]
     * }
     * }
     */
    private Teams teams;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Teams {
        private Team blue;
        private Team red;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Team {
        private String name; // "Blue Team" / "Red Team"
        private String side; // "blue" / "red"
        private Integer averageMmr;
        private List<TeamPlayer> players;
        private Map<String, Object> metadata; // Campos extras específicos da fase
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TeamPlayer {
        // Identificação
        private Long playerId;
        private String summonerName;
        private String gameName;
        private String tagLine;
        private String puuid;

        // Stats e ranking
        private Integer mmr;
        private Integer customLp;
        private Integer profileIconId;
        private Integer summonerLevel;

        // Posicionamento na partida
        private Integer teamIndex; // 0-4 (blue) ou 5-9 (red)
        private String assignedLane; // "top", "jungle", "mid", "bot", "support"
        private String primaryLane; // Lane preferida
        private String secondaryLane; // Lane secundária
        private Boolean isAutofill; // Se está fora da lane preferida
        private String laneBadge; // "primary", "secondary", "autofill"

        // Estado de aceitação (phase: match_found)
        private Integer acceptanceStatus; // 0=pending, 1=accepted, -1=declined
        private Boolean hasAccepted;

        // Dados do draft (phase: draft)
        private List<DraftAction> actions; // Ações deste jogador
        private Integer championId; // Campeão selecionado (após pick)
        private String championName;
        private Boolean hasConfirmed; // Se confirmou a seleção

        // Dados in-game (phase: in_game)
        private Map<String, Object> gameStats; // KDA, gold, etc

        // Dados post-game (phase: post_game)
        private Map<String, Object> endGameStats; // Stats finais
        private Integer mvpScore; // Pontuação MVP

        // Metadados flexíveis
        private Map<String, Object> metadata;
    }

    // ========================================
    // DADOS DO DRAFT (phase: draft)
    // ========================================

    private List<DraftAction> actions; // TODAS as 20 ações (bans + picks)
    private List<DraftAction> phases; // Alias para actions (compatibilidade)
    private Integer currentAction; // Índice da ação atual (0-19)
    private Integer currentIndex; // Alias para currentAction
    private String currentPlayer; // Summoner da vez
    private String currentActionType; // "ban" ou "pick"
    private String currentPhase; // "ban1", "pick1", "ban2", "pick2"
    private String currentTeam; // "blue" ou "red"
    private Integer timeRemaining; // Tempo restante em segundos
    private Long lastActionStartMs; // Timestamp da última ação
    private Map<String, Object> confirmations; // Confirmações de picks

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DraftAction {
        private Integer index; // 0-19
        private String type; // "ban" ou "pick"
        private String phase; // "ban1", "pick1", "ban2", "pick2"
        private Integer team; // 1 (blue) ou 2 (red)
        private String byPlayer; // Summoner que fez a ação
        private Integer championId; // ID do campeão (null se pending)
        private String championName; // Nome do campeão (null se pending)
        private String status; // "pending", "completed", "skipped"
        private Long completedAt; // Timestamp de conclusão
    }

    // ========================================
    // DADOS DE ACEITAÇÃO (phase: match_found)
    // ========================================

    private Integer timeoutSeconds; // Tempo para aceitar
    private Integer acceptedCount; // Quantos aceitaram
    private Integer totalPlayers; // Total de jogadores (10)
    private List<String> acceptedPlayers; // Jogadores que aceitaram
    private List<String> pendingPlayers; // Jogadores pendentes

    // ========================================
    // DADOS IN-GAME (phase: in_game)
    // ========================================

    private Map<String, Object> gameState; // Estado do jogo em tempo real
    private Integer gameTime; // Tempo de jogo em segundos
    private String winner; // "blue", "red", ou null

    // ========================================
    // DADOS POST-GAME (phase: post_game)
    // ========================================

    private String winningTeam; // "blue" ou "red"
    private Map<String, Object> finalStats; // Estatísticas finais
    private String mvpPlayer; // Jogador MVP

    // ========================================
    // METADADOS FLEXÍVEIS
    // ========================================

    private Map<String, Object> metadata; // Campos extras específicos da fase
}
