package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.Match;
import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.MatchRepository;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchVoteService {

    private final MatchRepository matchRepository;
    private final PlayerRepository playerRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final ObjectMapper objectMapper;
    private final SpecialUserService specialUserService;
    private final LCUService lcuService;
    private final LPCalculationService lpCalculationService;

    private static final int VOTES_REQUIRED_FOR_AUTO_LINK = 5;
    private final Map<Long, Map<Long, Long>> temporaryVotes = new ConcurrentHashMap<>();

    @Transactional
    public Map<String, Object> processVote(Long matchId, Long playerId, Long lcuGameId) {
        log.info("Voto recebido: matchId={}, playerId={}, lcuGameId={}", matchId, playerId, lcuGameId);

        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Partida nao encontrada"));

        String status = match.getStatus();
        boolean validStatus = "in_progress".equals(status) || "game_in_progress".equals(status)
                || "ended".equals(status);
        if (validStatus == false) {
            throw new IllegalStateException("Partida nao disponivel para votacao");
        }

        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Jogador nao encontrado"));

        temporaryVotes.putIfAbsent(matchId, new ConcurrentHashMap<>());
        temporaryVotes.get(matchId).put(playerId, lcuGameId);

        boolean isSpecialUser = specialUserService.isSpecialUser(player.getSummonerName());

        if (isSpecialUser) {
            log.info("🌟 Special user detectado! Voto finaliza a partida imediatamente");
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("shouldLink", true);
            result.put("lcuGameId", lcuGameId);
            result.put("specialUserVote", true);
            result.put("voteCount", 1);
            result.put("playerVote", lcuGameId);
            return result;
        }

        Map<Long, Long> voteCountMap = countVotesByLcuGameId(matchId);
        log.info("📊 Contagem atual de votos: {}", voteCountMap);

        Optional<Long> winningLcuGameId = voteCountMap.entrySet().stream()
                .filter(entry -> entry.getValue() >= VOTES_REQUIRED_FOR_AUTO_LINK)
                .map(Map.Entry::getKey)
                .findFirst();

        int currentVoteCount = voteCountMap.getOrDefault(lcuGameId, 0L).intValue();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("shouldLink", winningLcuGameId.isPresent());
        result.put("lcuGameId", lcuGameId);
        result.put("specialUserVote", false);
        result.put("voteCount", currentVoteCount);
        result.put("playerVote", lcuGameId);

        if (winningLcuGameId.isPresent()) {
            log.info("🎯 5 votos atingidos para lcuGameId={}", winningLcuGameId.get());
        }

        return result;
    }

    public Map<Long, Long> countVotesByLcuGameId(Long matchId) {
        Map<Long, Long> playerVotes = temporaryVotes.get(matchId);
        if (playerVotes == null || playerVotes.isEmpty()) {
            return new HashMap<>();
        }
        return playerVotes.values().stream()
                .collect(Collectors.groupingBy(lcuGameId -> lcuGameId, Collectors.counting()));
    }

    public void removeVote(Long matchId, Long playerId) {
        Map<Long, Long> matchVotes = temporaryVotes.get(matchId);
        if (matchVotes != null) {
            matchVotes.remove(playerId);
            log.info("Voto removido: matchId={}, playerId={}", matchId, playerId);
        }
    }

    @Transactional
    public void linkMatch(Long matchId, Long lcuGameId, JsonNode lcuMatchData) {
        try {
            Match match = matchRepository.findById(matchId)
                    .orElseThrow(() -> new IllegalArgumentException("Partida nao encontrada"));

            // Salvar JSON completo do LCU
            match.setLcuMatchData(objectMapper.writeValueAsString(lcuMatchData));
            match.setRiotGameId(String.valueOf(lcuGameId));

            // Extrair e salvar informações da partida do LCU
            String gameMode = lcuMatchData.path("gameMode").asText("CLASSIC");
            String gameType = lcuMatchData.path("gameType").asText("CUSTOM_GAME");
            Integer gameDuration = lcuMatchData.path("gameDuration").asInt(0);

            log.info("🎮 Informações da partida LCU: gameMode={}, gameType={}, duration={}s",
                    gameMode, gameType, gameDuration);

            // Atualizar campos da partida
            match.setGameMode(gameMode);
            if (gameDuration > 0) {
                match.setDuration(gameDuration); // Já está em segundos
            }

            // Mesclar pick_ban_data (estrutura dos times) com lcu_match_data (stats da
            // partida)
            List<Map<String, Object>> participantsList = new ArrayList<>();

            // 1. Carregar pick_ban_data que tem a estrutura completa dos 10 jogadores
            JsonNode pickBanData = null;
            if (match.getPickBanData() != null && !match.getPickBanData().isEmpty()) {
                try {
                    pickBanData = objectMapper.readTree(match.getPickBanData());
                    log.info("📊 Pick/Ban data encontrado na partida");
                } catch (Exception e) {
                    log.warn("⚠️ Erro ao parsear pick_ban_data: {}", e.getMessage());
                }
            }

            // 2. Tentar buscar dados completos da partida via LCU (com todos os 10
            // jogadores)
            JsonNode fullMatchData = null;
            try {
                log.info("🔍 Tentando buscar dados completos da partida via LCU: gameId={}", lcuGameId);
                fullMatchData = lcuService.getMatchDetails(lcuGameId).join();
                if (fullMatchData != null) {
                    log.info("✅ Dados completos da partida encontrados via LCU!");
                    // Usar dados completos em vez dos dados parciais
                    lcuMatchData = fullMatchData;
                }
            } catch (Exception e) {
                log.warn("⚠️ Não foi possível buscar dados completos via LCU: {}", e.getMessage());
            }

            // 3. Processar LCU data para ter stats disponíveis
            Map<String, Map<String, Object>> lcuStatsMap = new HashMap<>();
            JsonNode participantIdentities = lcuMatchData.path("participantIdentities");

            if (lcuMatchData.has("participants") && lcuMatchData.get("participants").isArray()) {
                JsonNode participants = lcuMatchData.get("participants");
                log.info("📊 Processando {} participantes do LCU", participants.size());

                for (JsonNode participant : participants) {
                    int participantId = participant.path("participantId").asInt(0);
                    int championId = participant.path("championId").asInt(0);

                    // Buscar nome do jogador
                    String playerKey = "";
                    if (participantIdentities.isArray()) {
                        for (JsonNode identity : participantIdentities) {
                            if (identity.path("participantId").asInt() == participantId) {
                                JsonNode player = identity.path("player");
                                String gameName = player.path("gameName").asText("");
                                String tagLine = player.path("tagLine").asText("");
                                playerKey = gameName + "#" + tagLine;
                                break;
                            }
                        }
                    }

                    Map<String, Object> stats = new HashMap<>();
                    JsonNode statsNode = participant.path("stats");
                    if (statsNode != null && !statsNode.isMissingNode()) {
                        stats.put("win", statsNode.path("win").asBoolean(false));
                        stats.put("kills", statsNode.path("kills").asInt(0));
                        stats.put("deaths", statsNode.path("deaths").asInt(0));
                        stats.put("assists", statsNode.path("assists").asInt(0));
                        stats.put("champLevel", statsNode.path("champLevel").asInt(0));
                        stats.put("totalDamageDealtToChampions",
                                statsNode.path("totalDamageDealtToChampions").asInt(0));
                        stats.put("totalMinionsKilled", statsNode.path("totalMinionsKilled").asInt(0));
                        stats.put("goldEarned", statsNode.path("goldEarned").asInt(0));
                        stats.put("visionScore", statsNode.path("visionScore").asInt(0));
                        stats.put("item0", statsNode.path("item0").asInt(0));
                        stats.put("item1", statsNode.path("item1").asInt(0));
                        stats.put("item2", statsNode.path("item2").asInt(0));
                        stats.put("item3", statsNode.path("item3").asInt(0));
                        stats.put("item4", statsNode.path("item4").asInt(0));
                        stats.put("item5", statsNode.path("item5").asInt(0));
                        stats.put("item6", statsNode.path("item6").asInt(0));
                        stats.put("spell1Id", participant.path("spell1Id").asInt(0));
                        stats.put("spell2Id", participant.path("spell2Id").asInt(0));
                        stats.put("championId", championId);
                        stats.put("teamId", participant.path("teamId").asInt(0));
                    }

                    log.info("🔑 [LCU] Adicionando stats com chave: '{}', championId={}, kills={}",
                            playerKey, championId, stats.get("kills"));
                    lcuStatsMap.put(playerKey, stats);
                }

                log.info("🗺️ [LCU Stats Map] Total de {} jogadores no mapa:", lcuStatsMap.size());
                for (String key : lcuStatsMap.keySet()) {
                    Map<String, Object> statsDebug = lcuStatsMap.get(key);
                    log.info("  - Chave: '{}' | ChampionId: {} | Kills: {}",
                            key, statsDebug.get("championId"), statsDebug.get("kills"));
                }
            }

            // 3. Mesclar: usar pick_ban_data como base e complementar com LCU stats
            if (pickBanData != null) {
                log.info("🔄 Mesclando pick/ban data com LCU stats");

                // Estrutura: { "blue": { "players": [...] }, "red": { "players": [...] } }
                // OU estrutura antiga: { "team1": [...], "team2": [...] }

                JsonNode blueTeam = null;
                JsonNode redTeam = null;

                // Tentar nova estrutura primeiro
                if (pickBanData.has("blue") && pickBanData.get("blue").has("players")) {
                    blueTeam = pickBanData.get("blue").get("players");
                    log.info("✅ Usando estrutura nova: blue.players");
                } else if (pickBanData.has("team1")) {
                    blueTeam = pickBanData.get("team1");
                    log.info("✅ Usando estrutura antiga: team1");
                }

                if (pickBanData.has("red") && pickBanData.get("red").has("players")) {
                    redTeam = pickBanData.get("red").get("players");
                    log.info("✅ Usando estrutura nova: red.players");
                } else if (pickBanData.has("team2")) {
                    redTeam = pickBanData.get("team2");
                    log.info("✅ Usando estrutura antiga: team2");
                }

                // Processar Team Blue (100)
                if (blueTeam != null && blueTeam.isArray()) {
                    for (JsonNode player : blueTeam) {
                        Map<String, Object> participantData = mergePlayerData(player, lcuStatsMap, 100);
                        participantsList.add(participantData);
                    }
                }

                // Processar Team Red (200)
                if (redTeam != null && redTeam.isArray()) {
                    for (JsonNode player : redTeam) {
                        Map<String, Object> participantData = mergePlayerData(player, lcuStatsMap, 200);
                        participantsList.add(participantData);
                    }
                }

                log.info("✅ Mesclados {} participantes com sucesso", participantsList.size());
            } else if (!lcuStatsMap.isEmpty()) {
                // Fallback: usar só LCU data se não tiver pick_ban_data
                log.warn("⚠️ Pick/ban data não disponível, usando apenas LCU data");
                participantsList.addAll(lcuStatsMap.values().stream()
                        .map(stats -> new HashMap<>(stats))
                        .collect(Collectors.toList()));
            }

            if (!participantsList.isEmpty()) {
                match.setParticipantsData(objectMapper.writeValueAsString(participantsList));
                log.info("✅ Dados de {} participantes salvos com sucesso", participantsList.size());
            } else {
                log.warn("⚠️ Nenhum dado de participante processado!");
            }

            // Determinar time vencedor baseado nos participantes
            if (!participantsList.isEmpty()) {
                for (Map<String, Object> p : participantsList) {
                    if ((Boolean) p.get("win")) {
                        match.setWinnerTeam((Integer) p.get("teamId"));
                        break;
                    }
                }
            }

            // ✅ NOVO: Calcular LP changes para todos os jogadores
            if (match.getWinnerTeam() != null && match.getWinnerTeam() > 0) {
                try {
                    log.info("🔄 Calculando LP changes para a partida {}", matchId);

                    // Extrair listas de jogadores dos times
                    List<String> team1Players = parsePlayerList(match.getTeam1PlayersJson());
                    List<String> team2Players = parsePlayerList(match.getTeam2PlayersJson());

                    // Normalizar winnerTeam: LCU usa 100/200, sistema espera 1/2
                    int normalizedWinnerTeam = match.getWinnerTeam() == 100 ? 1
                            : match.getWinnerTeam() == 200 ? 2 : match.getWinnerTeam();

                    log.info("🏆 Winner Team: {} → Normalizado: {}", match.getWinnerTeam(), normalizedWinnerTeam);

                    // Calcular mudanças de LP
                    Map<String, Integer> lpChanges = lpCalculationService.calculateMatchLPChanges(
                            team1Players,
                            team2Players,
                            normalizedWinnerTeam);

                    // Salvar LP changes na partida
                    if (!lpChanges.isEmpty()) {
                        match.setLpChanges(objectMapper.writeValueAsString(lpChanges));

                        log.info("✅ LP changes calculados: {} jogadores afetados",
                                lpChanges.size());
                    }
                } catch (Exception lpError) {
                    log.error("❌ Erro ao calcular LP changes: {}", lpError.getMessage(), lpError);
                    // Não falhar a vinculação por erro no cálculo de LP
                }
            } else {
                log.warn("⚠️ Time vencedor não determinado, LP não será calculado");
            }

            match.setStatus("completed");
            match.setCompletedAt(Instant.now());

            matchRepository.save(match);
            temporaryVotes.remove(matchId);

            log.info("🎉 Partida {} vinculada com sucesso! LCU Game ID: {}", matchId, lcuGameId);
        } catch (Exception e) {
            log.error("❌ Erro ao vincular partida: {}", e.getMessage(), e);
            throw new IllegalStateException("Erro ao vincular partida", e);
        }
    }

    /**
     * Mescla dados do jogador do pick/ban com stats do LCU
     */
    private Map<String, Object> mergePlayerData(JsonNode player, Map<String, Map<String, Object>> lcuStatsMap,
            int teamId) {
        Map<String, Object> participantData = new HashMap<>();

        // Dados do pick/ban (estrutura, lane, champion)
        String gameName = player.path("gameName").asText("");
        String tagLine = player.path("tagLine").asText("");
        String summonerName = player.path("summonerName").asText("");

        log.info("🔍 [MERGE] Tentando mesclar jogador: gameName='{}', tagLine='{}', summonerName='{}'",
                gameName, tagLine, summonerName);

        participantData.put("summonerName", summonerName);
        participantData.put("riotIdGameName", gameName);
        participantData.put("riotIdTagline", tagLine);
        participantData.put("assignedLane", player.path("assignedLane").asText(""));
        participantData.put("primaryLane", player.path("primaryLane").asText(""));
        participantData.put("secondaryLane", player.path("secondaryLane").asText(""));
        participantData.put("laneBadge", player.path("laneBadge").asText(null));
        participantData.put("mmr", player.path("mmr").asInt(1500));
        participantData.put("teamId", teamId);

        log.info("🏷️ [MERGE] laneBadge para {}: {}", summonerName, player.path("laneBadge").asText("N/A"));

        // Champion do pick/ban
        int pickBanChampionId = 0;
        if (player.has("actions") && player.get("actions").isArray()) {
            for (JsonNode action : player.get("actions")) {
                if ("pick".equals(action.path("type").asText(""))) {
                    pickBanChampionId = action.path("championId").asInt(0);
                    participantData.put("championId", pickBanChampionId);
                    participantData.put("championName", action.path("championName").asText(""));
                    break;
                }
            }
        }

        // Estratégia de matching inteligente com LCU
        Map<String, Object> lcuStats = findBestMatchInLcuStats(summonerName, pickBanChampionId, lcuStatsMap);

        if (!lcuStats.isEmpty()) {
            log.info("✅ Stats do LCU encontrados para {}", summonerName);

            // Preservar championName e championId do pick/ban se já existirem
            Object existingChampionId = participantData.get("championId");
            Object existingChampionName = participantData.get("championName");
            String existingAssignedLane = (String) participantData.get("assignedLane");
            String existingLaneBadge = (String) participantData.get("laneBadge");

            participantData.putAll(lcuStats);

            // Restaurar championName e championId do pick/ban se foram sobrescritos
            if (existingChampionId != null) {
                participantData.put("championId", existingChampionId);
            }
            if (existingChampionName != null && !existingChampionName.toString().isEmpty()) {
                participantData.put("championName", existingChampionName);
            }

            // Garantir que lane existe - usar assignedLane se lane não foi definido
            if (!participantData.containsKey("lane") || participantData.get("lane") == null
                    || participantData.get("lane").toString().isEmpty()) {
                if (existingAssignedLane != null && !existingAssignedLane.isEmpty()) {
                    participantData.put("lane", existingAssignedLane);
                    log.info("📍 [MERGE] lane não encontrado no LCU, usando assignedLane: {}", existingAssignedLane);
                }
            }

            // Restaurar laneBadge se foi sobrescrito
            if (existingLaneBadge != null && !existingLaneBadge.isEmpty()) {
                participantData.put("laneBadge", existingLaneBadge);
            }
        } else {
            log.warn("⚠️ Stats do LCU não encontrados para {}, usando valores padrão", summonerName);
            // Valores padrão quando LCU data não está disponível
            participantData.put("kills", 0);
            participantData.put("deaths", 0);
            participantData.put("assists", 0);
            participantData.put("champLevel", 1);
            participantData.put("totalDamageDealtToChampions", 0);
            participantData.put("totalMinionsKilled", 0);
            participantData.put("goldEarned", 0);
            participantData.put("visionScore", 0);
            participantData.put("item0", 0);
            participantData.put("item1", 0);
            participantData.put("item2", 0);
            participantData.put("item3", 0);
            participantData.put("item4", 0);
            participantData.put("item5", 0);
            participantData.put("item6", 0);
            participantData.put("spell1Id", 0);
            participantData.put("spell2Id", 0);
            participantData.put("win", false);
        }

        return participantData;
    }

    /**
     * Tenta encontrar o melhor match entre o jogador do pick/ban e os stats do LCU
     * usando múltiplas estratégias: exact match, normalized match, champion match
     */
    private Map<String, Object> findBestMatchInLcuStats(String summonerName, int championId,
            Map<String, Map<String, Object>> lcuStatsMap) {

        // Estratégia 1: Match exato
        if (lcuStatsMap.containsKey(summonerName)) {
            log.debug("📍 Match exato encontrado: {}", summonerName);
            return lcuStatsMap.get(summonerName);
        }

        // Estratégia 2: Match normalizado (remove espaços duplicados, case-insensitive)
        String normalizedSummoner = normalizePlayerName(summonerName);
        for (Map.Entry<String, Map<String, Object>> entry : lcuStatsMap.entrySet()) {
            String normalizedKey = normalizePlayerName(entry.getKey());
            if (normalizedKey.equals(normalizedSummoner)) {
                log.debug("📍 Match normalizado encontrado: {} -> {}", summonerName, entry.getKey());
                return entry.getValue();
            }
        }

        // Estratégia 3: Match fuzzy (Levenshtein distance < 3)
        for (Map.Entry<String, Map<String, Object>> entry : lcuStatsMap.entrySet()) {
            int distance = levenshteinDistance(normalizedSummoner, normalizePlayerName(entry.getKey()));
            if (distance <= 2) {
                log.debug("📍 Match fuzzy encontrado (distância={}): {} -> {}", distance, summonerName, entry.getKey());
                return entry.getValue();
            }
        }

        // Estratégia 4: Match por championId (último recurso)
        if (championId > 0) {
            for (Map.Entry<String, Map<String, Object>> entry : lcuStatsMap.entrySet()) {
                Integer lcuChampionId = (Integer) entry.getValue().get("championId");
                if (lcuChampionId != null && lcuChampionId == championId) {
                    log.debug("📍 Match por championId encontrado: {} (champ {})", entry.getKey(), championId);
                    return entry.getValue();
                }
            }
        }

        return Collections.emptyMap();
    }

    /**
     * Normaliza nome do jogador para comparação
     */
    private String normalizePlayerName(String name) {
        return name.toLowerCase()
                .replaceAll("\\s+", " ")
                .replaceAll("[^a-z0-9#]", "")
                .trim();
    }

    /**
     * Calcula distância de Levenshtein entre duas strings
     */
    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) {
            for (int j = 0; j <= b.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] = Math.min(Math.min(
                            dp[i - 1][j] + 1,
                            dp[i][j - 1] + 1),
                            dp[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                }
            }
        }

        return dp[a.length()][b.length()];
    }

    /**
     * Parseia a string JSON de jogadores e retorna uma lista de nomes
     * 
     * @param playersJson String contendo nomes separados por vírgula ou JSON array
     * @return Lista de nomes dos jogadores
     */
    private List<String> parsePlayerList(String playersJson) {
        try {
            if (playersJson == null || playersJson.trim().isEmpty()) {
                return Collections.emptyList();
            }

            String trimmed = playersJson.trim();

            // Tentar parsear como JSON array primeiro
            if (trimmed.startsWith("[")) {
                JsonNode playersNode = objectMapper.readTree(trimmed);
                List<String> playerNames = new ArrayList<>();

                if (playersNode.isArray()) {
                    for (JsonNode playerNode : playersNode) {
                        if (playerNode.isTextual()) {
                            playerNames.add(playerNode.asText());
                        }
                    }
                }

                return playerNames;
            }

            // Se não for JSON, tratar como comma-separated string
            return Arrays.stream(trimmed.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("❌ Erro ao parsear lista de jogadores: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
