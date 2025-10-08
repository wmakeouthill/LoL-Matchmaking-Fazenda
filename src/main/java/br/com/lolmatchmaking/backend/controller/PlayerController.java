package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.dto.PlayerDTO;
import br.com.lolmatchmaking.backend.service.PlayerService;
import br.com.lolmatchmaking.backend.service.RiotAPIService;
import br.com.lolmatchmaking.backend.service.LCUService;
import br.com.lolmatchmaking.backend.util.SummonerAuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/player")
@RequiredArgsConstructor
public class PlayerController {

    private final PlayerService playerService;
    private final RiotAPIService riotAPIService;
    private final LCUService lcuService;

    /**
     * GET /api/player/current-details
     * Obtém detalhes do jogador atual via LCU
     * 
     * @param summonerNameHeader Optional header para especificar qual sessão LCU
     *                           usar
     */
    @GetMapping("/current-details")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getCurrentPlayerDetails(
            @RequestHeader(value = "X-Summoner-Name", required = false) String summonerNameHeader) {
        try {
            if (summonerNameHeader != null && !summonerNameHeader.isEmpty()) {
                log.info("🔍 Obtendo detalhes do jogador atual via LCU para: {}", summonerNameHeader);
            } else {
                log.info("🔍 Obtendo detalhes do jogador atual via LCU (sem header X-Summoner-Name)");
            }

            return lcuService.getCurrentSummoner(summonerNameHeader)
                    .thenApply(summonerData -> {
                        Map<String, Object> response = new HashMap<>();

                        if (summonerData != null) {
                            // Extrair dados do LCU
                            String summonerName = summonerData.has("displayName")
                                    ? summonerData.get("displayName").asText()
                                    : "Unknown";
                            String gameName = summonerData.has("gameName") ? summonerData.get("gameName").asText()
                                    : summonerName;
                            String tagLine = summonerData.has("tagLine") ? summonerData.get("tagLine").asText() : "";

                            Map<String, Object> playerData = new HashMap<>();
                            playerData.put("summonerName", summonerName);
                            playerData.put("gameName", gameName);
                            playerData.put("tagLine", tagLine);
                            playerData.put("region", "br1"); // Assumir BR1 por padrão
                            playerData.put("lcuConnected", true);

                            // Adicionar dados do LCU se disponíveis
                            if (summonerData.has("summonerId")) {
                                String sid = summonerData.get("summonerId").asText();
                                playerData.put("summonerId", sid);
                                try {
                                    playerData.put("id", Integer.parseInt(sid));
                                } catch (Exception ignore) {
                                    playerData.put("id", 0);
                                }
                            } else {
                                playerData.put("id", 0);
                            }

                            if (summonerData.has("puuid")) {
                                playerData.put("puuid", summonerData.get("puuid").asText());
                            } else {
                                playerData.put("puuid", "");
                            }

                            if (summonerData.has("summonerLevel")) {
                                playerData.put("summonerLevel", summonerData.get("summonerLevel").asInt());
                            } else {
                                playerData.put("summonerLevel", 1);
                            }

                            // Build displayName and include common player fields expected by the frontend
                            String constructedDisplayName = (gameName != null && !gameName.isEmpty() && tagLine != null
                                    && !tagLine.isEmpty())
                                            ? gameName + "#" + tagLine
                                            : (summonerData.has("displayName")
                                                    ? summonerData.get("displayName").asText()
                                                    : summonerName);
                            playerData.put("displayName", constructedDisplayName);

                            playerData.put("profileIconId",
                                    summonerData.has("profileIconId") ? summonerData.get("profileIconId").asInt() : 29);

                            playerData.put("currentMMR",
                                    summonerData.has("currentMMR") ? summonerData.get("currentMMR").asInt() : 1200);
                            // Try to get ranked info from the local LCU first (local client often
                            // has current-ranked-stats). If available, use it. Otherwise fall back
                            // to Riot API enrichment (if configured).
                            Map<String, Object> fallbackRank = new HashMap<>();
                            fallbackRank.put("tier", "UNRANKED");
                            fallbackRank.put("rank", "");
                            fallbackRank.put("lp", 0);
                            fallbackRank.put("wins", 0);
                            fallbackRank.put("losses", 0);

                            // Default values
                            playerData.put("rank", fallbackRank);
                            playerData.put("wins", 0);
                            playerData.put("losses", 0);

                            try {
                                // Try LCU local ranked stats
                                JsonNode rankedNode = lcuService.getCurrentSummonerRanked().get();
                                log.info("🔍 LCU ranked data received: {}", rankedNode);
                                if (rankedNode != null) {
                                    // The LCU /lol-ranked/v1/current-ranked-stats endpoint varies by
                                    // client. We try to coerce commonly used fields.
                                    Map<String, Object> rankObj = new HashMap<>();

                                    // ✅ NOVO: Checar se tem campo "queues" (formato novo do LCU)
                                    if (rankedNode.has("queues") && rankedNode.get("queues").isArray()) {
                                        log.info("🔍 LCU ranked data has 'queues' array, processing...");
                                        for (JsonNode queue : rankedNode.get("queues")) {
                                            if (queue.has("queueType")
                                                    && queue.get("queueType").asText().equals("RANKED_SOLO_5x5")) {
                                                log.info("🔍 Found solo queue data in queues: {}", queue);
                                                if (queue.has("tier"))
                                                    rankObj.put("tier", queue.get("tier").asText(null));
                                                if (queue.has("division"))
                                                    rankObj.put("rank", queue.get("division").asText(null));
                                                if (queue.has("leaguePoints"))
                                                    rankObj.put("lp", queue.get("leaguePoints").asInt(0));
                                                if (queue.has("wins"))
                                                    rankObj.put("wins", queue.get("wins").asInt(0));
                                                if (queue.has("losses"))
                                                    rankObj.put("losses", queue.get("losses").asInt(0));
                                                break;
                                            }
                                        }
                                    }
                                    // Check if it's an array of queues (common format)
                                    else if (rankedNode.isArray()) {
                                        log.info("🔍 LCU ranked data is array, processing queues...");
                                        for (JsonNode queue : rankedNode) {
                                            if (queue.has("queueType")
                                                    && queue.get("queueType").asText().equals("RANKED_SOLO_5x5")) {
                                                log.info("🔍 Found solo queue data: {}", queue);
                                                if (queue.has("tier"))
                                                    rankObj.put("tier", queue.get("tier").asText(null));
                                                if (queue.has("division")) // ✅ CORREÇÃO: Campo correto é "division"
                                                    rankObj.put("rank", queue.get("division").asText(null));
                                                if (queue.has("leaguePoints"))
                                                    rankObj.put("lp", queue.get("leaguePoints").asInt(0));
                                                if (queue.has("wins"))
                                                    rankObj.put("wins", queue.get("wins").asInt(0));
                                                if (queue.has("losses"))
                                                    rankObj.put("losses", queue.get("losses").asInt(0));
                                                break;
                                            }
                                        }
                                    } else {
                                        // Single object format
                                        if (rankedNode.has("tier"))
                                            rankObj.put("tier", rankedNode.get("tier").asText(null));
                                        if (rankedNode.has("division"))
                                            rankObj.put("rank", rankedNode.get("division").asText(null));
                                        if (rankedNode.has("leaguePoints"))
                                            rankObj.put("lp", rankedNode.get("leaguePoints").asInt(0));
                                        if (rankedNode.has("wins"))
                                            rankObj.put("wins", rankedNode.get("wins").asInt(0));
                                        if (rankedNode.has("losses"))
                                            rankObj.put("losses", rankedNode.get("losses").asInt(0));
                                    }

                                    log.info("🔍 Processed rank object: {}", rankObj);

                                    // If we found any tier info, consider this a valid ranked payload
                                    if (rankObj.get("tier") != null || rankObj.get("lp") != null) {
                                        playerData.put("rank", rankObj);
                                        playerData.put("wins", (Integer) rankObj.getOrDefault("wins", 0));
                                        playerData.put("losses", (Integer) rankObj.getOrDefault("losses", 0));
                                    }

                                    // Store raw LCU ranked stats for frontend processing (like old backend)
                                    playerData.put("lcuRankedStats", rankedNode);
                                }
                            } catch (Exception e) {
                                log.debug("LCU ranked-stats unavailable or failed: {}", e.getMessage());
                            }

                            // If still not found and Riot API is configured, enrich via Riot API
                            try {
                                if ((playerData.get("rank") == null
                                        || ((Map) playerData.get("rank")).get("tier").equals("UNRANKED"))
                                        && riotAPIService != null && riotAPIService.isConfigured()
                                        && playerData.get("summonerId") != null) {
                                    String sid = String.valueOf(playerData.get("summonerId"));
                                    List<RiotAPIService.RankedData> ranked = riotAPIService.getRankedData(sid,
                                            (String) playerData.getOrDefault("region", "br1"));
                                    if (ranked != null && !ranked.isEmpty()) {
                                        // Prefer solo queue
                                        RiotAPIService.RankedData solo = ranked.stream()
                                                .filter(r -> "RANKED_SOLO_5x5".equals(r.getQueueType()))
                                                .findFirst()
                                                .orElse(ranked.get(0));

                                        Map<String, Object> rankObj = new HashMap<>();
                                        rankObj.put("tier", solo.getTier());
                                        rankObj.put("rank", solo.getRank());
                                        rankObj.put("lp", solo.getLeaguePoints());
                                        rankObj.put("wins", solo.getWins());
                                        rankObj.put("losses", solo.getLosses());

                                        playerData.put("rank", rankObj);
                                        // Also set flat wins/losses for backward-compat
                                        playerData.put("wins", solo.getWins() != null ? solo.getWins() : 0);
                                        playerData.put("losses", solo.getLosses() != null ? solo.getLosses() : 0);
                                    }
                                }
                            } catch (Exception e) {
                                log.debug("⚠️ Não foi possível enriquecer com Riot ranked data: {}", e.getMessage());
                            }
                            playerData.put("region", playerData.getOrDefault("region", "br1"));
                            playerData.put("registeredAt", java.time.Instant.now().toString());
                            playerData.put("updatedAt", java.time.Instant.now().toString());

                            response.put("success", true);
                            response.put("player", playerData);
                            response.put("source", "LCU");

                            // Also provide a `data` shape expected by the Electron frontend
                            // so callers that expect `{ success:true, data: { lcu: ..., riotAccount: ...
                            // }}`
                            // can consume it directly.
                            Map<String, Object> riotAccount = new HashMap<>();
                            riotAccount.put("gameName", gameName);
                            riotAccount.put("tagLine", tagLine);
                            riotAccount.put("puuid",
                                    summonerData.has("puuid") ? summonerData.get("puuid").asText() : "");

                            Map<String, Object> dataWrapper = new HashMap<>();
                            dataWrapper.put("lcu", summonerData);
                            dataWrapper.put("riotAccount", riotAccount);
                            dataWrapper.put("partialData", true);

                            response.put("data", dataWrapper);

                            // ✅ NOVO: Criar/atualizar player na tabela players com dados completos
                            try {
                                Map<String, Object> rankData = (Map<String, Object>) playerData.get("rank");

                                // ✅ LOG DETALHADO: Ver rankData recebido
                                log.info("🔍 [PlayerController] rankData recebido: {}", rankData);

                                int currentMmrFromLoL = calculateMMRFromRank(rankData);
                                String fullSummonerName = constructedDisplayName != null ? constructedDisplayName
                                        : summonerName;
                                String region = (String) playerData.getOrDefault("region", "br1");

                                // ✅ Extrair summonerId e puuid
                                String summonerId = summonerData.has("summonerId")
                                        ? summonerData.get("summonerId").asText()
                                        : null;
                                String puuid = summonerData.has("puuid")
                                        ? summonerData.get("puuid").asText()
                                        : null;

                                // ✅ LOG DETALHADO: Ver valores calculados
                                log.info("🔍 [PlayerController] Valores calculados:");
                                log.info("  - summonerName: {}", fullSummonerName);
                                log.info("  - region: {}", region);
                                log.info("  - currentMmrFromLoL: {}", currentMmrFromLoL);
                                log.info("  - summonerId: {}", summonerId);
                                log.info("  - puuid: {}", puuid);

                                Player savedPlayer = playerService.createOrUpdatePlayerOnLogin(
                                        fullSummonerName,
                                        region,
                                        currentMmrFromLoL,
                                        summonerId,
                                        puuid);

                                // ✅ LOG DETALHADO: Ver player salvo
                                log.info("✅ [PlayerController] Player salvo no banco:");
                                log.info("  - ID: {}", savedPlayer.getId());
                                log.info("  - summonerName: {}", savedPlayer.getSummonerName());
                                log.info("  - current_mmr: {}", savedPlayer.getCurrentMmr());
                                log.info("  - custom_lp: {}", savedPlayer.getCustomLp());
                                log.info("  - custom_mmr: {}", savedPlayer.getCustomMmr());
                                log.info("  - summonerId: {}", savedPlayer.getSummonerId());
                                log.info("  - puuid: {}", savedPlayer.getPuuid());
                            } catch (Exception e) {
                                log.error("❌ [PlayerController] Erro ao criar/atualizar player: {}", e.getMessage(), e);
                                log.error("❌ [PlayerController] Stack trace:", e);
                            }

                            return ResponseEntity.ok(response);
                        } else {
                            response.put("success", false);
                            response.put("error", "LCU não conectado ou League of Legends não está aberto");
                            response.put("lcuConnected", false);

                            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
                        }
                    })
                    .exceptionally(ex -> {
                        log.error("❌ Erro ao obter detalhes do jogador atual", ex);
                        Map<String, Object> errorResponse = new HashMap<>();
                        errorResponse.put("success", false);
                        errorResponse.put("error", "Erro interno: " + ex.getMessage());
                        errorResponse.put("lcuConnected", false);

                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
                    });

        } catch (Exception e) {
            log.error("❌ Erro ao obter detalhes do jogador atual", e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            errorResponse.put("lcuConnected", false);

            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
        }
    }

    /**
     * POST /api/player/register
     * Registra um novo jogador
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerPlayer(@RequestBody PlayerRegistrationRequest request) {
        try {
            log.info("➕ Registrando jogador: {}", request.getSummonerName());

            // Validar dados
            if (request.getSummonerName() == null || request.getSummonerName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Nome do invocador é obrigatório"));
            }

            if (request.getRegion() == null || request.getRegion().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Região é obrigatória"));
            }

            // Registrar jogador
            PlayerDTO player = playerService.registerPlayer(
                    request.getSummonerName(),
                    request.getRegion(),
                    request.getGameName(),
                    request.getTagLine());

            if (player != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "player", player));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Erro ao registrar jogador"));
            }

        } catch (Exception e) {
            log.error("❌ Erro ao registrar jogador", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/player/{playerId}
     * Obtém detalhes de um jogador específico
     */
    @GetMapping("/{playerId}")
    public ResponseEntity<Map<String, Object>> getPlayer(@PathVariable Long playerId) {
        try {
            log.info("🔍 Obtendo jogador: {}", playerId);

            PlayerDTO player = playerService.getPlayerById(playerId);
            if (player != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "player", player));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter jogador", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/player/{playerId}/stats
     * Obtém estatísticas de um jogador
     * ✅ MODIFICADO: Valida header X-Summoner-Name
     */
    @GetMapping("/{playerId}/stats")
    public ResponseEntity<Map<String, Object>> getPlayerStats(
            @PathVariable Long playerId,
            HttpServletRequest request) {
        try {
            // ✅ Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("📊 [{}] Obtendo estatísticas do jogador: {}", summonerName, playerId);

            // TODO: Validar se playerId pertence ao summonerName autenticado
            // Por enquanto, apenas logando para visibilidade

            PlayerService.PlayerStats serviceStats = playerService.getPlayerStats(playerId);
            if (serviceStats != null) {
                PlayerStats stats = new PlayerStats();
                stats.setGamesPlayed(serviceStats.getGamesPlayed());
                stats.setWins(serviceStats.getWins());
                stats.setLosses(serviceStats.getLosses());
                stats.setWinRate(serviceStats.getWinRate());
                stats.setCurrentMmr(serviceStats.getCurrentMmr());
                stats.setPeakMmr(serviceStats.getPeakMmr());
                stats.setWinStreak(serviceStats.getWinStreak());
                stats.setCustomGamesPlayed(serviceStats.getCustomGamesPlayed());
                stats.setCustomWins(serviceStats.getCustomWins());
                stats.setCustomLosses(serviceStats.getCustomLosses());

                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "stats", stats));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("❌ Erro ao obter estatísticas do jogador", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/stats/leaderboard
     * Obtém leaderboard de jogadores
     * ✅ MODIFICADO: Valida header X-Summoner-Name
     */
    @GetMapping("/leaderboard")
    public ResponseEntity<Map<String, Object>> getLeaderboard(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        try {
            // ✅ Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("🏆 [{}] Obtendo leaderboard (page: {}, limit: {})", summonerName, page, limit);

            List<PlayerDTO> leaderboard = playerService.getLeaderboard(page, limit);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "leaderboard", leaderboard,
                    "page", page,
                    "limit", limit,
                    "total", leaderboard.size()));

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Header X-Summoner-Name ausente em requisição de leaderboard");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "error", "Header X-Summoner-Name obrigatório"));
        } catch (Exception e) {
            log.error("❌ Erro ao obter leaderboard", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/summoner/{displayName}
     * Busca invocador por nome de exibição
     * ✅ MODIFICADO: Valida header X-Summoner-Name
     */
    @GetMapping("/summoner/{displayName}")
    public ResponseEntity<Map<String, Object>> getSummonerByDisplayName(
            @PathVariable String displayName,
            HttpServletRequest request) {
        try {
            // ✅ Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("🔍 [{}] Buscando invocador: {}", summonerName, displayName);

            Map<String, Object> summonerData = riotAPIService.getSummonerByDisplayName(displayName, "br1");
            if (summonerData != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "summoner", summonerData));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Header X-Summoner-Name ausente em requisição de busca de summoner");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "error", "Header X-Summoner-Name obrigatório"));
        } catch (Exception e) {
            log.error("❌ Erro ao buscar invocador", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/player/details/{displayName}
     * Obtém detalhes de jogador por nome de exibição
     * ✅ MODIFICADO: Valida header X-Summoner-Name
     */
    @GetMapping("/details/{displayName}")
    public ResponseEntity<Map<String, Object>> getPlayerDetails(
            @PathVariable String displayName,
            HttpServletRequest request) {
        try {
            // ✅ Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("🔍 [{}] Obtendo detalhes do jogador: {}", summonerName, displayName);

            PlayerDTO player = playerService.getPlayerByDisplayName(displayName);
            if (player != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "player", player));
            } else {
                return ResponseEntity.notFound().build();
            }

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Header X-Summoner-Name ausente em requisição de detalhes de jogador");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "error", "Header X-Summoner-Name obrigatório"));
        } catch (Exception e) {
            log.error("❌ Erro ao obter detalhes do jogador", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/player/refresh-by-display-name
     * Atualiza dados do jogador via Riot API
     * ✅ MODIFICADO: Valida header X-Summoner-Name
     */
    @PostMapping("/refresh-by-display-name")
    public ResponseEntity<Map<String, Object>> refreshPlayerByDisplayName(
            @RequestBody RefreshPlayerRequest request,
            HttpServletRequest httpRequest) {
        try {
            // ✅ Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(httpRequest);
            log.info("🔄 [{}] Atualizando jogador: {}", summonerName, request.getDisplayName());

            PlayerDTO player = playerService.refreshPlayerByDisplayName(
                    request.getDisplayName(),
                    request.getRegion());

            if (player != null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "player", player));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Jogador não encontrado"));
            }

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Header X-Summoner-Name ausente em requisição de refresh de jogador");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "error", "Header X-Summoner-Name obrigatório"));
        } catch (Exception e) {
            log.error("❌ Erro ao atualizar jogador", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Calcula o MMR usando a MESMA fórmula do frontend
     * Baseado em: tier base + rank bonus + (LP * 0.8)
     */
    private int calculateMMRFromRank(Map<String, Object> rankData) {
        if (rankData == null) {
            log.warn("⚠️ [calculateMMRFromRank] rankData é null, retornando MMR padrão 1200");
            return 1200; // MMR padrão para unranked
        }

        String tier = (String) rankData.getOrDefault("tier", "UNRANKED");
        String rank = (String) rankData.getOrDefault("rank", "");

        // ✅ Tentar converter LP de diferentes tipos
        int lp = 0;
        Object lpObj = rankData.get("lp");
        if (lpObj instanceof Integer) {
            lp = (Integer) lpObj;
        } else if (lpObj instanceof String) {
            try {
                lp = Integer.parseInt((String) lpObj);
            } catch (NumberFormatException e) {
                log.warn("⚠️ [calculateMMRFromRank] LP não é um número válido: {}", lpObj);
            }
        }

        // ✅ MMR base por tier (MESMA tabela do frontend)
        int baseMmr = switch (tier.toUpperCase()) {
            case "IRON" -> 800;
            case "BRONZE" -> 1000;
            case "SILVER" -> 1200;
            case "GOLD" -> 1400;
            case "PLATINUM" -> 1700;
            case "EMERALD" -> 2000;
            case "DIAMOND" -> 2300;
            case "MASTER" -> 2600;
            case "GRANDMASTER" -> 2800;
            case "CHALLENGER" -> 3000;
            default -> 1200; // UNRANKED
        };

        // ✅ Ajuste por divisão (MESMA tabela do frontend)
        int rankBonus = switch (rank.toUpperCase()) {
            case "IV" -> 0;
            case "III" -> 50;
            case "II" -> 100;
            case "I" -> 150;
            default -> 0;
        };

        // ✅ LP contribui com multiplicador 0.8 (MESMA fórmula do frontend)
        int lpBonus = (int) Math.round(lp * 0.8);

        int finalMmr = baseMmr + rankBonus + lpBonus;

        // ✅ LOG DETALHADO: Ver cálculo passo a passo
        log.info("🔢 [calculateMMRFromRank] Cálculo detalhado:");
        log.info("  - Tier: {} → Base MMR: {}", tier, baseMmr);
        log.info("  - Rank: {} → Rank Bonus: {}", rank, rankBonus);
        log.info("  - LP: {} → LP Bonus (LP * 0.8): {}", lp, lpBonus);
        log.info("  - Final MMR: {} + {} + {} = {}", baseMmr, rankBonus, lpBonus, finalMmr);

        return finalMmr;
    }

    // DTOs
    @Data
    public static class PlayerRegistrationRequest {
        private String summonerName;
        private String region;
        private String gameName;
        private String tagLine;
    }

    @Data
    public static class RefreshPlayerRequest {
        private String displayName;
        private String region;
    }

    @Data
    public static class PlayerStats {
        private int gamesPlayed;
        private int wins;
        private int losses;
        private double winRate;
        private int currentMmr;
        private int peakMmr;
        private int winStreak;
        private int customGamesPlayed;
        private int customWins;
        private int customLosses;
        private double customWinRate;
        private int customLp;
    }
}
