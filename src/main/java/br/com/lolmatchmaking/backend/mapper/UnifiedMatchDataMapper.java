package br.com.lolmatchmaking.backend.mapper;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.entity.QueuePlayer;
import br.com.lolmatchmaking.backend.dto.UnifiedMatchDataDTO;
import br.com.lolmatchmaking.backend.dto.UnifiedMatchDataDTO.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedMatchDataMapper {

    private final ObjectMapper objectMapper;

    private static final String[] LANES = { "top", "jungle", "mid", "bot", "support" };

    public UnifiedMatchDataDTO toUnifiedDTO(
            CustomMatch match,
            List<QueuePlayer> team1,
            List<QueuePlayer> team2,
            String phase) {

        try {
            UnifiedMatchDataDTO dto = UnifiedMatchDataDTO.builder()
                    .matchId(match.getId())
                    .phase(phase)
                    .status(match.getStatus())
                    .createdAt(match.getCreatedAt() != null ? match.getCreatedAt().toEpochMilli() : null)
                    .averageMmrTeam1(match.getAverageMmrTeam1())
                    .averageMmrTeam2(match.getAverageMmrTeam2())
                    .build();

            Teams teams = buildTeamsStructure(team1, team2, phase);
            dto.setTeams(teams);

            if (match.getPickBanDataJson() != null && !match.getPickBanDataJson().isBlank()) {
                mergeDraftData(dto, match.getPickBanDataJson());
            }
            switch (phase) {
                case "match_found":
                    populateMatchFoundData(dto, team1, team2);
                    break;
                case "draft":
                    populateDraftData(dto, match);
                    break;
                case "in_game":
                    populateInGameData(dto, match);
                    break;
                case "post_game":
                    populatePostGameData(dto, match);
                    break;
            }

            return dto;

        } catch (Exception e) {
            log.error("❌ [UnifiedMapper] Erro ao converter CustomMatch → DTO: matchId={}",
                    match.getId(), e);
            throw new RuntimeException("Erro ao converter match data", e);
        }
    }

    public String toJson(UnifiedMatchDataDTO dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            log.error("❌ [UnifiedMapper] Erro ao serializar DTO → JSON", e);
            throw new RuntimeException("Erro ao serializar match data", e);
        }
    }

    public UnifiedMatchDataDTO fromJson(String json) {
        try {
            return objectMapper.readValue(json, UnifiedMatchDataDTO.class);
        } catch (Exception e) {
            log.error("❌ [UnifiedMapper] Erro ao desserializar JSON → DTO", e);
            throw new RuntimeException("Erro ao desserializar match data", e);
        }
    }

    public Map<String, Object> jsonToMap(String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });

            // ✅ COMPATIBILIDADE RETROATIVA: Adicionar team1/team2 para o frontend legado
            addLegacyTeamArrays(map);

            return map;
        } catch (Exception e) {
            log.error("❌ [UnifiedMapper] Erro ao desserializar JSON → Map", e);
            throw new RuntimeException("Erro ao desserializar JSON", e);
        }
    }

    /**
     * ✅ COMPATIBILIDADE RETROATIVA: Adiciona arrays team1/team2 a partir de
     * teams.blue/red
     * Permite que o frontend legado continue funcionando sem mudanças
     */
    @SuppressWarnings("unchecked")
    private void addLegacyTeamArrays(Map<String, Object> map) {
        try {
            Object teamsObj = map.get("teams");
            if (teamsObj instanceof Map) {
                Map<String, Object> teams = (Map<String, Object>) teamsObj;

                Object blueObj = teams.get("blue");
                Object redObj = teams.get("red");

                if (blueObj instanceof Map && redObj instanceof Map) {
                    Map<String, Object> blue = (Map<String, Object>) blueObj;
                    Map<String, Object> red = (Map<String, Object>) redObj;

                    Object bluePlayers = blue.get("players");
                    Object redPlayers = red.get("players");

                    if (bluePlayers != null) {
                        map.put("team1", bluePlayers);
                    }
                    if (redPlayers != null) {
                        map.put("team2", redPlayers);
                    }

                    log.debug("✅ [UnifiedMapper] Adicionados arrays legados team1/team2 para compatibilidade");
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [UnifiedMapper] Erro ao adicionar arrays legados: {}", e.getMessage());
        }
    }

    public String mapToJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.error("❌ [UnifiedMapper] Erro ao serializar Map → JSON", e);
            throw new RuntimeException("Erro ao serializar Map", e);
        }
    }

    private Teams buildTeamsStructure(List<QueuePlayer> team1, List<QueuePlayer> team2, String phase) {
        Team blueTeam = Team.builder()
                .name("Blue Team")
                .side("blue")
                .players(buildPlayersList(team1, 0, phase))
                .build();

        Team redTeam = Team.builder()
                .name("Red Team")
                .side("red")
                .players(buildPlayersList(team2, 5, phase))
                .build();

        return Teams.builder()
                .blue(blueTeam)
                .red(redTeam)
                .build();
    }

    private List<TeamPlayer> buildPlayersList(List<QueuePlayer> players, int startIndex, String phase) {
        if (players == null || players.isEmpty()) {
            return new ArrayList<>();
        }

        List<TeamPlayer> result = new ArrayList<>();
        for (int i = 0; i < players.size() && i < 5; i++) {
            QueuePlayer player = players.get(i);
            String assignedLane = LANES[i];
            boolean isAutofill = determineIfAutofill(player, assignedLane);

            String summonerName = player.getSummonerName();
            String[] nameParts = summonerName != null && summonerName.contains("#")
                    ? summonerName.split("#", 2)
                    : new String[] { summonerName, "" };

            TeamPlayer teamPlayer = TeamPlayer.builder()
                    .playerId(player.getId())
                    .summonerName(summonerName)
                    .gameName(nameParts[0])
                    .tagLine(nameParts.length > 1 ? nameParts[1] : "")
                    .mmr(player.getCustomLp())
                    .customLp(player.getCustomLp())
                    .profileIconId(29)
                    .teamIndex(startIndex + i)
                    .assignedLane(assignedLane)
                    .primaryLane(player.getPrimaryLane())
                    .secondaryLane(player.getSecondaryLane())
                    .isAutofill(isAutofill)
                    .laneBadge(determineLaneBadge(player, assignedLane))
                    .acceptanceStatus(player.getAcceptanceStatus())
                    .hasAccepted(player.getAcceptanceStatus() != null && player.getAcceptanceStatus() == 1)
                    .build();

            result.add(teamPlayer);
        }

        return result;
    }

    private boolean determineIfAutofill(QueuePlayer player, String assignedLane) {
        if (assignedLane == null || player.getPrimaryLane() == null) {
            return false;
        }
        return !assignedLane.equalsIgnoreCase(player.getPrimaryLane())
                && !assignedLane.equalsIgnoreCase(player.getSecondaryLane());
    }

    private String determineLaneBadge(QueuePlayer player, String assignedLane) {
        if (assignedLane == null || player.getPrimaryLane() == null) {
            return "autofill";
        }
        if (assignedLane.equalsIgnoreCase(player.getPrimaryLane())) {
            return "primary";
        }
        if (assignedLane.equalsIgnoreCase(player.getSecondaryLane())) {
            return "secondary";
        }
        return "autofill";
    }

    private void mergeDraftData(UnifiedMatchDataDTO dto, String pickBanDataJson) {
        try {
            Map<String, Object> draftData = jsonToMap(pickBanDataJson);
            if (draftData.containsKey("currentIndex")) {
                dto.setCurrentAction(((Number) draftData.get("currentIndex")).intValue());
                dto.setCurrentIndex(dto.getCurrentAction());
            }

            if (draftData.containsKey("currentPlayer")) {
                dto.setCurrentPlayer((String) draftData.get("currentPlayer"));
            }

            if (draftData.containsKey("currentActionType")) {
                dto.setCurrentActionType((String) draftData.get("currentActionType"));
            }

            if (draftData.containsKey("timeRemaining")) {
                dto.setTimeRemaining(((Number) draftData.get("timeRemaining")).intValue());
            }

        } catch (Exception e) {
            log.error("❌ [UnifiedMapper] Erro ao mesclar draft data", e);
        }
    }

    private void populateMatchFoundData(UnifiedMatchDataDTO dto, List<QueuePlayer> team1, List<QueuePlayer> team2) {
        dto.setTimeoutSeconds(30);
        dto.setTotalPlayers(10);

        List<String> accepted = new ArrayList<>();
        List<String> pending = new ArrayList<>();

        for (QueuePlayer player : team1) {
            if (player.getAcceptanceStatus() != null && player.getAcceptanceStatus() == 1) {
                accepted.add(player.getSummonerName());
            } else {
                pending.add(player.getSummonerName());
            }
        }

        for (QueuePlayer player : team2) {
            if (player.getAcceptanceStatus() != null && player.getAcceptanceStatus() == 1) {
                accepted.add(player.getSummonerName());
            } else {
                pending.add(player.getSummonerName());
            }
        }

        dto.setAcceptedCount(accepted.size());
        dto.setAcceptedPlayers(accepted);
        dto.setPendingPlayers(pending);

        // ✅ CRÍTICO: Criar actions iniciais vazias (20 actions padrão para 5v5)
        // Isso garante que o pick_ban_data já tem a estrutura completa desde
        // match_found
        dto.setActions(createInitialDraftActions(team1, team2));
        log.info("✅ [UnifiedMapper] Actions criadas no match_found: {} actions", dto.getActions().size());
    }

    /**
     * ✅ NOVO: Cria as 20 actions do draft (6 bans + 14 picks)
     * Pattern: ban1 (6) → pick1 (6) → ban2 (4) → pick2 (4)
     */
    private List<UnifiedMatchDataDTO.DraftAction> createInitialDraftActions(List<QueuePlayer> team1,
            List<QueuePlayer> team2) {
        List<UnifiedMatchDataDTO.DraftAction> actions = new ArrayList<>();

        // Criar lista de players na ordem correta (intercalado)
        List<String> allPlayers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            allPlayers.add(team1.get(i).getSummonerName()); // Team 1 (Blue)
            allPlayers.add(team2.get(i).getSummonerName()); // Team 2 (Red)
        }

        // Ordem padrão do draft profissional (mesma do DraftFlowService)
        // Blue (1): 0, 2, 4, 6, 9, 10, 13, 15, 17, 18
        // Red (2): 1, 3, 5, 7, 8, 11, 12, 14, 16, 19
        int[] draftOrder = {
                0, 1, // ban1: Blue, Red
                2, 3, // ban1: Blue, Red
                4, 5, // ban1: Blue, Red
                0, 1, // pick1: Blue, Red (Top)
                3, 2, // pick1: Red, Blue (Jungle)
                4, 5, // pick1: Blue, Red (Mid)
                7, 6, // ban2: Red, Blue
                9, 8, // ban2: Red, Blue
                6, 7, // pick2: Blue, Red (Bot)
                8, 9 // pick2: Blue, Red (Support)
        };

        String[] phases = {
                "ban1", "ban1", "ban1", "ban1", "ban1", "ban1", // 6 bans
                "pick1", "pick1", "pick1", "pick1", "pick1", "pick1", // 6 picks
                "ban2", "ban2", "ban2", "ban2", // 4 bans
                "pick2", "pick2", "pick2", "pick2" // 4 picks
        };

        for (int i = 0; i < draftOrder.length; i++) {
            int playerIndex = draftOrder[i];
            String byPlayer = allPlayers.get(playerIndex);
            int team = (playerIndex % 2 == 0) ? 1 : 2; // Par=Blue(1), Ímpar=Red(2)
            String type = i < 6 ? "ban" : "pick";

            UnifiedMatchDataDTO.DraftAction action = new UnifiedMatchDataDTO.DraftAction();
            action.setIndex(i);
            action.setType(type);
            action.setTeam(team);
            action.setPhase(phases[i]);
            action.setByPlayer(byPlayer);
            action.setChampionId(null);
            action.setChampionName(null);
            action.setStatus("pending");
            action.setCompletedAt(null);

            actions.add(action);
        }

        log.info("✅ [UnifiedMapper] {} actions criadas (6 bans + 14 picks)", actions.size());
        return actions;
    }

    private void populateDraftData(UnifiedMatchDataDTO dto, CustomMatch match) {
        if (dto.getCurrentAction() != null) {
            dto.setCurrentIndex(dto.getCurrentAction());
        }
        if (dto.getActions() != null) {
            dto.setPhases(dto.getActions());
        }
    }

    private void populateInGameData(UnifiedMatchDataDTO dto, CustomMatch match) {
        dto.setGameState(new HashMap<>());
    }

    private void populatePostGameData(UnifiedMatchDataDTO dto, CustomMatch match) {
        dto.setFinalStats(new HashMap<>());
    }
}
