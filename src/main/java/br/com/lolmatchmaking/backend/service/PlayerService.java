package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import br.com.lolmatchmaking.backend.domain.dto.PlayerChampionStatsDTO;
import br.com.lolmatchmaking.backend.dto.PlayerDTO;
import br.com.lolmatchmaking.backend.mapper.PlayerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final PlayerMapper playerMapper;
    private final RiotAPIService riotAPIService;
    private final RiotChampionStatsService riotChampionStatsService;

    @Cacheable("players")
    public List<PlayerDTO> getAllPlayers() {
        return playerRepository.findAll()
                .stream()
                .map(this::enrichPlayerData)
                .toList();
    }

    @Cacheable("player-by-summoner-name")
    public Optional<PlayerDTO> getPlayerBySummonerName(String summonerName) {
        return playerRepository.findBySummonerNameIgnoreCase(summonerName)
                .map(this::enrichPlayerData);
    }

    @Cacheable("player-by-puuid")
    public Optional<PlayerDTO> getPlayerByPuuid(String puuid) {
        return playerRepository.findByPuuid(puuid)
                .map(this::enrichPlayerData);
    }

    @Transactional
    @CacheEvict(value = { "players", "player-by-summoner-name", "player-by-puuid" }, allEntries = true)
    public PlayerDTO createOrUpdatePlayer(PlayerDTO playerDTO) {
        Optional<Player> existingPlayer = playerRepository.findBySummonerNameIgnoreCase(playerDTO.getSummonerName());

        Player player;
        if (existingPlayer.isPresent()) {
            player = existingPlayer.get();
            updatePlayerFromDTO(player, playerDTO);
            log.info("Atualizando jogador existente: {}", playerDTO.getSummonerName());
        } else {
            player = playerMapper.toEntity(playerDTO);
            log.info("Criando novo jogador: {}", playerDTO.getSummonerName());
        }

        // Buscar dados atualizados da Riot API se disponível
        if (riotAPIService.isConfigured()) {
            enrichPlayerFromRiotAPI(player);
        }

        Player savedPlayer = playerRepository.save(player);
        return enrichPlayerData(savedPlayer);
    }

    @Transactional
    @CacheEvict(value = { "players", "player-by-summoner-name", "player-by-puuid" }, allEntries = true)
    public void deletePlayer(Long playerId) {
        playerRepository.deleteById(playerId);
        log.info("Jogador deletado: {}", playerId);
    }

    @Transactional
    @CacheEvict(value = { "players", "player-by-summoner-name", "player-by-puuid" }, allEntries = true)
    public PlayerDTO updatePlayerStats(String summonerName, int wins, int losses) {
        Optional<Player> playerOpt = playerRepository.findBySummonerNameIgnoreCase(summonerName);
        if (playerOpt.isEmpty()) {
            throw new IllegalArgumentException("Jogador não encontrado: " + summonerName);
        }

        Player player = playerOpt.get();
        player.setWins(wins);
        player.setLosses(losses);

        Player savedPlayer = playerRepository.save(player);
        return enrichPlayerData(savedPlayer);
    }

    @Transactional
    @CacheEvict(value = { "players", "player-by-summoner-name", "player-by-puuid" }, allEntries = true)
    public PlayerDTO updateCustomStats(String summonerName, int customWins, int customLosses, int customMmr) {
        Optional<Player> playerOpt = playerRepository.findBySummonerNameIgnoreCase(summonerName);
        if (playerOpt.isEmpty()) {
            throw new IllegalArgumentException("Jogador não encontrado: " + summonerName);
        }

        Player player = playerOpt.get();
        player.setCustomWins(customWins);
        player.setCustomLosses(customLosses);
        player.setCustomMmr(customMmr);

        if (customMmr > player.getCustomPeakMmr()) {
            player.setCustomPeakMmr(customMmr);
        }

        Player savedPlayer = playerRepository.save(player);
        return enrichPlayerData(savedPlayer);
    }

    /**
     * Cria ou atualiza um player quando ele loga, atualizando com dados completos
     * do LoL
     */
    @Transactional
    public Player createOrUpdatePlayerOnLogin(String summonerName, String region, Integer currentMmrFromLoL,
            String summonerId, String puuid, Integer profileIconId) {
        log.info("🎯🎯🎯 [PlayerService.createOrUpdatePlayerOnLogin] MÉTODO CHAMADO!");
        log.info("🔄 Criando/atualizando player no login: {} (MMR: {}, ID: {}, PUUID: {}, ProfileIcon: {})",
                summonerName, currentMmrFromLoL, summonerId, puuid, profileIconId);

        Optional<Player> existingPlayer = playerRepository.findBySummonerNameIgnoreCase(summonerName);

        Player player;
        if (existingPlayer.isPresent()) {
            player = existingPlayer.get();
            player.setCurrentMmr(currentMmrFromLoL);
            player.setRegion(region);

            // ✅ Atualizar summonerId e puuid se fornecidos
            if (summonerId != null && !summonerId.isEmpty()) {
                player.setSummonerId(summonerId);
            }
            if (puuid != null && !puuid.isEmpty()) {
                player.setPuuid(puuid);
            }

            // ✅ NOVO: Atualizar profileIconUrl se profileIconId fornecido e URL ainda não existe
            if (profileIconId != null && (player.getProfileIconUrl() == null || player.getProfileIconUrl().isEmpty())) {
                String profileIconUrl = String.format(
                    "https://ddragon.leagueoflegends.com/cdn/15.19.1/img/profileicon/%d.png",
                    profileIconId
                );
                player.setProfileIconUrl(profileIconUrl);
                log.info("✅ Profile icon URL salva no login: {}", profileIconUrl);
            }

            // ✅ Atualizar custom_mmr (current_mmr + custom_lp)
            int customLp = player.getCustomLp() != null ? player.getCustomLp() : 0;
            player.setCustomMmr(currentMmrFromLoL + customLp);

            log.info("✅ Player existente atualizado: {} (current_mmr: {}, custom_lp: {}, custom_mmr: {})",
                    summonerName,
                    player.getCurrentMmr(),
                    customLp,
                    player.getCustomMmr());
        } else {
            // ✅ NOVO: Construir profileIconUrl se profileIconId fornecido
            String profileIconUrl = null;
            if (profileIconId != null) {
                profileIconUrl = String.format(
                    "https://ddragon.leagueoflegends.com/cdn/15.19.1/img/profileicon/%d.png",
                    profileIconId
                );
                log.info("✅ Profile icon URL definida para novo jogador: {}", profileIconUrl);
            }

            player = Player.builder()
                    .summonerName(summonerName)
                    .region(region)
                    .currentMmr(currentMmrFromLoL)
                    .summonerId(summonerId)
                    .puuid(puuid)
                    .profileIconUrl(profileIconUrl) // ✅ NOVO: Adicionar URL do ícone
                    .customLp(0) // Inicia com 0 LP customizado
                    .customMmr(currentMmrFromLoL) // custom_mmr = current_mmr + 0
                    .customWins(0)
                    .customLosses(0)
                    .customGamesPlayed(0)
                    .gamesPlayed(0)
                    .wins(0)
                    .losses(0)
                    .build();
            log.info("✅ Novo player criado: {} (current_mmr: {}, custom_mmr: {})",
                    summonerName, currentMmrFromLoL, currentMmrFromLoL);
        }

        return playerRepository.save(player);
    }

    /**
     * Calcula o MMR total do jogador (current_mmr + custom_lp)
     */
    public int calculateTotalMMR(Player player) {
        int currentMmr = player.getCurrentMmr() != null ? player.getCurrentMmr() : 0;
        int customLp = player.getCustomLp() != null ? player.getCustomLp() : 0;
        return currentMmr + customLp;
    }

    public List<PlayerDTO> getTopPlayersByCustomMmr(int limit) {
        return playerRepository.findTopByCustomMmr(limit)
                .stream()
                .map(this::enrichPlayerData)
                .toList();
    }

    @Transactional
    public PlayerDTO registerPlayer(String summonerName, String region, String gameName, String tagLine) {
        try {
            log.info("Registrando jogador: {} ({})", summonerName, region);

            // Verificar se já existe
            Optional<Player> existingPlayer = playerRepository.findBySummonerName(summonerName);
            if (existingPlayer.isPresent()) {
                log.warn("Jogador já existe: {}", summonerName);
                return playerMapper.toDTO(existingPlayer.get());
            }

            // Criar novo jogador
            Player player = new Player();
            player.setSummonerName(summonerName);
            player.setRegion(region);
            player.setCurrentMmr(1000);
            player.setPeakMmr(1000);
            player.setCustomMmr(1000);
            player.setCustomPeakMmr(1000);

            // Se gameName e tagLine foram fornecidos, usar como displayName
            if (gameName != null && tagLine != null) {
                player.setSummonerName(gameName + "#" + tagLine);
            }

            player = playerRepository.save(player);
            log.info("Jogador registrado com sucesso: {}", player.getId());

            return playerMapper.toDTO(player);

        } catch (Exception e) {
            log.error("Erro ao registrar jogador", e);
            return null;
        }
    }

    public PlayerDTO getPlayerById(Long id) {
        try {
            Optional<Player> player = playerRepository.findById(id);
            return player.map(playerMapper::toDTO).orElse(null);
        } catch (Exception e) {
            log.error("Erro ao buscar jogador por ID", e);
            return null;
        }
    }

    public PlayerDTO getPlayerByDisplayName(String displayName) {
        try {
            Optional<Player> player = playerRepository.findBySummonerName(displayName);
            return player.map(playerMapper::toDTO).orElse(null);
        } catch (Exception e) {
            log.error("Erro ao buscar jogador por displayName", e);
            return null;
        }
    }

    public PlayerStats getPlayerStats(Long playerId) {
        try {
            Optional<Player> playerOpt = playerRepository.findById(playerId);
            if (playerOpt.isEmpty()) {
                return null;
            }

            Player player = playerOpt.get();

            PlayerStats stats = new PlayerStats();
            stats.setGamesPlayed(player.getGamesPlayed());
            stats.setWins(player.getWins());
            stats.setLosses(player.getLosses());
            stats.setWinRate(
                    player.getGamesPlayed() > 0 ? (double) player.getWins() / player.getGamesPlayed() * 100 : 0.0);
            stats.setCurrentMmr(player.getCurrentMmr());
            stats.setPeakMmr(player.getPeakMmr());
            stats.setWinStreak(player.getWinStreak());
            stats.setCustomGamesPlayed(player.getCustomGamesPlayed());
            stats.setCustomWins(player.getCustomWins());
            stats.setCustomLosses(player.getCustomLosses());
            stats.setCustomWinRate(player.getCustomGamesPlayed() > 0
                    ? (double) player.getCustomWins() / player.getCustomGamesPlayed() * 100
                    : 0.0);
            stats.setCustomLp(player.getCustomLp());

            return stats;

        } catch (Exception e) {
            log.error("Erro ao obter estatísticas do jogador", e);
            return null;
        }
    }

    public List<PlayerDTO> getLeaderboard(int page, int limit) {
        try {
            // Ordenar por custom_lp para mostrar o ranking de partidas customizadas
            return playerRepository.findByOrderByCustomLpDesc()
                    .stream()
                    .skip((long) page * limit)
                    .limit(limit)
                    .map(playerMapper::toDTO)
                    .toList();
        } catch (Exception e) {
            log.error("Erro ao buscar leaderboard", e);
            return List.of();
        }
    }

    /**
     * Atualiza as estatísticas de custom matches de todos os jogadores
     * baseando-se nos dados da tabela custom_matches
     */
    @Transactional
    @CacheEvict(value = { "players", "player-by-summoner-name", "player-by-puuid" }, allEntries = true)
    public int updateAllPlayersCustomStats() {
        log.info("🔄 Iniciando atualização de estatísticas de custom matches...");

        List<Player> allPlayers = playerRepository.findAll();
        int updatedCount = 0;

        for (Player player : allPlayers) {
            try {
                updateSinglePlayerStats(player);
                updatedCount++;
            } catch (Exception e) {
                log.error("Erro ao atualizar stats do jogador {}: {}", player.getSummonerName(), e.getMessage());
            }
        }

        log.info("✅ Atualização concluída: {} jogadores atualizados", updatedCount);
        return updatedCount;
    }

    /**
     * Atualiza as estatísticas de custom matches de um jogador específico
     * Se não houver partidas, ZERA todas as estatísticas
     */
    private void updateSinglePlayerStats(Player player) {
        String summonerName = player.getSummonerName();
        List<Object[]> matches = playerRepository.findCustomMatchesForPlayer(summonerName);

        CustomStatsAccumulator stats;

        if (matches.isEmpty()) {
            log.info("⚠️ Nenhuma partida customizada encontrada para {} - ZERANDO estatísticas", summonerName);
            // ✅ CORREÇÃO: Se não há partidas, criar stats zeradas
            stats = new CustomStatsAccumulator();
        } else {
            // Calcular stats normalmente
            stats = calculateCustomStats(summonerName, matches);
        }

        // Aplicar stats (zeradas ou calculadas) ao jogador
        applyStatsToPlayer(player, stats);

        // ✅ Atualizar player_stats_draft com top 5 campeões das custom matches
        try {
            List<Map<String, Object>> draftChampions = extractTop5CustomChampions(summonerName);
            player.setPlayerStatsDraft(convertToJson(draftChampions));
            log.debug("✅ Top 5 draft champions para {}: {}", summonerName, draftChampions.size());
        } catch (Exception e) {
            log.warn("⚠️ Erro ao atualizar player_stats_draft para {}: {}", summonerName, e.getMessage());
        }

        // ✅ SEMPRE salva, seja com stats zeradas ou calculadas
        playerRepository.save(player);

        log.info("✅ Stats atualizadas para {}: LP={}, Games={}, W/L={}/{}, Streak={}",
                summonerName, stats.totalLp, stats.gamesPlayed, stats.wins, stats.losses, stats.maxStreak);
    }

    /**
     * Calcula as estatísticas a partir das partidas
     */
    private CustomStatsAccumulator calculateCustomStats(String summonerName, List<Object[]> matches) {
        CustomStatsAccumulator stats = new CustomStatsAccumulator();

        for (Object[] match : matches) {
            processMatch(summonerName, match, stats);
        }

        return stats;
    }

    /**
     * Processa uma partida individual
     */
    private void processMatch(String summonerName, Object[] match, CustomStatsAccumulator stats) {
        Long matchId = (Long) match[0];
        String team1Json = (String) match[1];
        String team2Json = (String) match[2];
        Integer winnerTeam = (Integer) match[3];
        String lpChangesJson = (String) match[4];
        String participantsDataJson = (String) match[5]; // participants_data field

        int playerTeam = determinePlayerTeam(summonerName, team1Json, team2Json);

        if (playerTeam == 0 || winnerTeam == null) {
            return;
        }

        stats.gamesPlayed++;
        boolean won = (playerTeam == winnerTeam);

        if (won) {
            stats.wins++;
            stats.currentStreak++;
            stats.maxStreak = Math.max(stats.maxStreak, stats.currentStreak);
        } else {
            stats.losses++;
            stats.currentStreak = 0;
        }

        int lpChange = extractLpChange(summonerName, lpChangesJson, matchId);
        stats.totalLp += lpChange;

        // Extrair KDA e campeão do participants_data JSON
        extractPlayerStats(summonerName, participantsDataJson, stats, matchId);
    }

    /**
     * Determina em qual time o jogador estava
     */
    private int determinePlayerTeam(String summonerName, String team1Json, String team2Json) {
        if (team1Json != null && team1Json.contains(summonerName)) {
            return 1;
        } else if (team2Json != null && team2Json.contains(summonerName)) {
            return 2;
        }
        return 0;
    }

    /**
     * Extrai a mudança de LP do JSON
     */
    private int extractLpChange(String summonerName, String lpChangesJson, Long matchId) {
        if (lpChangesJson == null || lpChangesJson.isEmpty()) {
            return 0;
        }

        try {
            String playerKey = "\"" + summonerName + "\"";
            int keyIndex = lpChangesJson.indexOf(playerKey);
            if (keyIndex == -1) {
                return 0;
            }

            int colonIndex = lpChangesJson.indexOf(":", keyIndex);
            if (colonIndex == -1) {
                return 0;
            }

            int commaIndex = lpChangesJson.indexOf(",", colonIndex);
            int braceIndex = lpChangesJson.indexOf("}", colonIndex);
            int endIndex = (commaIndex != -1 && commaIndex < braceIndex) ? commaIndex : braceIndex;

            if (endIndex == -1) {
                return 0;
            }

            String lpValue = lpChangesJson.substring(colonIndex + 1, endIndex).trim();
            return Integer.parseInt(lpValue);
        } catch (Exception e) {
            log.warn("Erro ao parsear LP para jogador {} na partida {}: {}", summonerName, matchId, e.getMessage());
            return 0;
        }
    }

    /**
     * Extrai estatísticas do jogador (KDA e campeão) do JSON participants_data
     */
    private void extractPlayerStats(String summonerName, String participantsDataJson, CustomStatsAccumulator stats,
            Long matchId) {
        if (participantsDataJson == null || participantsDataJson.isEmpty()) {
            return;
        }

        try {
            // Procurar pelo jogador no JSON
            String playerKey = "\"summonerName\":\"" + summonerName + "\"";
            int playerIndex = participantsDataJson.indexOf(playerKey);
            if (playerIndex == -1) {
                return;
            }

            // Encontrar o início do objeto do jogador (buscar o '{' anterior)
            int objectStart = participantsDataJson.lastIndexOf("{", playerIndex);
            if (objectStart == -1) {
                return;
            }

            // Encontrar o fim do objeto (próximo '}' no mesmo nível)
            int objectEnd = participantsDataJson.indexOf("}", playerIndex);
            if (objectEnd == -1) {
                return;
            }

            String playerData = participantsDataJson.substring(objectStart, objectEnd + 1);

            // Extrair kills
            int kills = extractJsonInt(playerData, "kills");
            stats.totalKills += kills;

            // Extrair deaths
            int deaths = extractJsonInt(playerData, "deaths");
            stats.totalDeaths += deaths;

            // Extrair assists
            int assists = extractJsonInt(playerData, "assists");
            stats.totalAssists += assists;

            // Extrair championName
            String championName = extractJsonString(playerData, "championName");
            if (championName != null && !championName.isEmpty()) {
                log.debug("Campeão extraído para {}: {}", summonerName, championName);
                stats.championPlayCount.merge(championName, 1, Integer::sum);
            } else {
                // Tentar extrair championId se championName não estiver disponível
                int championId = extractJsonInt(playerData, "championId");
                if (championId > 0) {
                    log.debug("ChampionId extraído para {}: {} (será convertido no frontend)", summonerName,
                            championId);
                    stats.championPlayCount.merge(String.valueOf(championId), 1, Integer::sum);
                }
            }

        } catch (Exception e) {
            log.warn("Erro ao extrair estatísticas do jogador {} na partida {}: {}", summonerName, matchId,
                    e.getMessage());
        }
    }

    /**
     * Extrai um valor inteiro de uma chave no JSON
     */
    private int extractJsonInt(String json, String key) {
        try {
            String keyPattern = "\"" + key + "\":";
            int keyIndex = json.indexOf(keyPattern);
            if (keyIndex == -1) {
                return 0;
            }

            int valueStart = keyIndex + keyPattern.length();
            int commaIndex = json.indexOf(",", valueStart);
            int braceIndex = json.indexOf("}", valueStart);
            int endIndex = (commaIndex != -1 && commaIndex < braceIndex) ? commaIndex : braceIndex;

            if (endIndex == -1) {
                return 0;
            }

            String value = json.substring(valueStart, endIndex).trim();
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Extrai um valor string de uma chave no JSON
     */
    private String extractJsonString(String json, String key) {
        try {
            String keyPattern = "\"" + key + "\":\"";
            int keyIndex = json.indexOf(keyPattern);
            if (keyIndex == -1) {
                return null;
            }

            int valueStart = keyIndex + keyPattern.length();
            int quoteIndex = json.indexOf("\"", valueStart);

            if (quoteIndex == -1) {
                return null;
            }

            return json.substring(valueStart, quoteIndex);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Aplica as estatísticas calculadas ao jogador
     */
    private void applyStatsToPlayer(Player player, CustomStatsAccumulator stats) {
        player.setCustomLp(stats.totalLp);
        player.setCustomGamesPlayed(stats.gamesPlayed);
        player.setCustomWins(stats.wins);
        player.setCustomLosses(stats.losses);
        player.setCustomWinStreak(stats.maxStreak);

        int currentMmr = player.getCurrentMmr() != null ? player.getCurrentMmr() : 1000;
        player.setCustomMmr(currentMmr + stats.totalLp);

        // Calcular médias de KDA
        if (stats.gamesPlayed > 0) {
            player.setAvgKills((double) stats.totalKills / stats.gamesPlayed);
            player.setAvgDeaths((double) stats.totalDeaths / stats.gamesPlayed);
            player.setAvgAssists((double) stats.totalAssists / stats.gamesPlayed);

            // Calcular KDA ratio: (K + A) / D (se deaths = 0, usar 1)
            double avgDeaths = player.getAvgDeaths() != null ? player.getAvgDeaths() : 0.0;
            double divisor = avgDeaths > 0 ? avgDeaths : 1.0;
            double kdaRatio = (player.getAvgKills() + player.getAvgAssists()) / divisor;
            player.setKdaRatio(kdaRatio);
        } else {
            player.setAvgKills(0.0);
            player.setAvgDeaths(0.0);
            player.setAvgAssists(0.0);
            player.setKdaRatio(0.0);
        }

        // Encontrar campeão favorito (mais jogado)
        if (!stats.championPlayCount.isEmpty()) {
            String favoriteChamp = null;
            int maxGames = 0;

            for (Map.Entry<String, Integer> entry : stats.championPlayCount.entrySet()) {
                if (entry.getValue() > maxGames) {
                    maxGames = entry.getValue();
                    favoriteChamp = entry.getKey();
                }
            }

            player.setFavoriteChampion(favoriteChamp);
            player.setFavoriteChampionGames(maxGames);
        } else {
            player.setFavoriteChampion(null);
            player.setFavoriteChampionGames(0);
        }
    }

    /**
     * Classe auxiliar para acumular estatísticas
     */
    private static class CustomStatsAccumulator {
        int totalLp = 0;
        int gamesPlayed = 0;
        int wins = 0;
        int losses = 0;
        int currentStreak = 0;
        int maxStreak = 0;
        int totalKills = 0;
        int totalDeaths = 0;
        int totalAssists = 0;
        Map<String, Integer> championPlayCount = new HashMap<>();
    }

    @Transactional
    public PlayerDTO refreshPlayerByDisplayName(String displayName, String region) {
        try {
            log.info("Atualizando jogador: {} ({})", displayName, region);

            // Buscar jogador existente
            Optional<Player> playerOpt = playerRepository.findBySummonerName(displayName);
            if (playerOpt.isEmpty()) {
                log.warn("Jogador não encontrado: {}", displayName);
                return null;
            }

            Player player = playerOpt.get();

            // Atualizar dados via Riot API
            try {
                // TODO: Implementar busca de dados atualizados via Riot API
                // Map<String, Object> riotData =
                // riotAPIService.getSummonerByDisplayName(displayName, region);
                // Atualizar campos do jogador com dados da Riot API

                player = playerRepository.save(player);
                log.info("Jogador atualizado com sucesso: {}", player.getId());

            } catch (Exception e) {
                log.warn("Erro ao atualizar via Riot API, mantendo dados existentes", e);
            }

            return playerMapper.toDTO(player);

        } catch (Exception e) {
            log.error("Erro ao atualizar jogador", e);
            return null;
        }
    }

    // DTO interno para estatísticas
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

        // Getters e setters
        public int getGamesPlayed() {
            return gamesPlayed;
        }

        public void setGamesPlayed(int gamesPlayed) {
            this.gamesPlayed = gamesPlayed;
        }

        public int getWins() {
            return wins;
        }

        public void setWins(int wins) {
            this.wins = wins;
        }

        public int getLosses() {
            return losses;
        }

        public void setLosses(int losses) {
            this.losses = losses;
        }

        public double getWinRate() {
            return winRate;
        }

        public void setWinRate(double winRate) {
            this.winRate = winRate;
        }

        public int getCurrentMmr() {
            return currentMmr;
        }

        public void setCurrentMmr(int currentMmr) {
            this.currentMmr = currentMmr;
        }

        public int getPeakMmr() {
            return peakMmr;
        }

        public void setPeakMmr(int peakMmr) {
            this.peakMmr = peakMmr;
        }

        public int getWinStreak() {
            return winStreak;
        }

        public void setWinStreak(int winStreak) {
            this.winStreak = winStreak;
        }

        public int getCustomGamesPlayed() {
            return customGamesPlayed;
        }

        public void setCustomGamesPlayed(int customGamesPlayed) {
            this.customGamesPlayed = customGamesPlayed;
        }

        public int getCustomWins() {
            return customWins;
        }

        public void setCustomWins(int customWins) {
            this.customWins = customWins;
        }

        public int getCustomLosses() {
            return customLosses;
        }

        public void setCustomLosses(int customLosses) {
            this.customLosses = customLosses;
        }

        public double getCustomWinRate() {
            return customWinRate;
        }

        public void setCustomWinRate(double customWinRate) {
            this.customWinRate = customWinRate;
        }

        public int getCustomLp() {
            return customLp;
        }

        public void setCustomLp(int customLp) {
            this.customLp = customLp;
        }
    }

    private PlayerDTO enrichPlayerData(Player player) {
        PlayerDTO dto = playerMapper.toDTO(player);

        // Enriquecer com dados da Riot API se disponível
        if (riotAPIService.isConfigured() && player.getPuuid() != null) {
            enrichDTOFromRiotAPI(dto, player);
        }

        return dto;
    }

    private void updatePlayerFromDTO(Player player, PlayerDTO dto) {
        if (dto.getSummonerId() != null) {
            player.setSummonerId(dto.getSummonerId());
        }
        if (dto.getPuuid() != null) {
            player.setPuuid(dto.getPuuid());
        }
        if (dto.getRegion() != null) {
            player.setRegion(dto.getRegion());
        }
        if (dto.getWins() != null) {
            player.setWins(dto.getWins());
        }
        if (dto.getLosses() != null) {
            player.setLosses(dto.getLosses());
        }
    }

    private void enrichPlayerFromRiotAPI(Player player) {
        if (player.getSummonerName() == null || player.getRegion() == null) {
            return;
        }

        try {
            // Buscar dados do summoner
            Optional<RiotAPIService.SummonerData> summonerData = Optional.empty();

            String name = player.getSummonerName();
            if (name != null && name.contains("#")) {
                // If stored as gameName#tagLine, resolve via account API to obtain PUUID
                try {
                    String[] parts = name.split("#", 2);
                    String gameName = parts[0];
                    String tagLine = parts[1];
                    Optional<RiotAPIService.AccountData> acc = riotAPIService.getAccountByRiotId(gameName, tagLine,
                            player.getRegion());
                    if (acc.isPresent()) {
                        summonerData = riotAPIService.getSummonerByPUUID(acc.get().getPuuid(), player.getRegion());
                    }
                } catch (Exception e) {
                    log.debug("Erro resolvendo account by Riot ID: {}", e.getMessage());
                }
            } else {
                // Try by summoner name directly (legacy behaviour)
                summonerData = riotAPIService.getSummonerByName(name, player.getRegion());
            }

            if (summonerData.isPresent()) {
                RiotAPIService.SummonerData data = summonerData.get();
                player.setSummonerId(data.getId());
                player.setPuuid(data.getPuuid());

                // ✅ NOVO: Construir e salvar URL do ícone de perfil
                if (data.getProfileIconId() != null) {
                    String profileIconUrl = String.format(
                            "https://ddragon.leagueoflegends.com/cdn/15.19.1/img/profileicon/%d.png",
                            data.getProfileIconId());
                    player.setProfileIconUrl(profileIconUrl);
                    log.debug("✅ Profile icon URL salva para {}: {}", player.getSummonerName(), profileIconUrl);
                }

                // Buscar dados ranked
                List<RiotAPIService.RankedData> rankedData = riotAPIService.getRankedData(data.getId(),
                        player.getRegion());

                rankedData.stream()
                        .filter(rd -> "RANKED_SOLO_5x5".equals(rd.getQueueType()))
                        .findFirst()
                        .ifPresent(rd -> {
                            player.setWins(rd.getWins());
                            player.setLosses(rd.getLosses());
                        });
            }
        } catch (Exception e) {
            log.warn("Erro ao enriquecer dados do jogador {} da Riot API: {}",
                    player.getSummonerName(), e.getMessage());
        }
    }

    private void enrichDTOFromRiotAPI(PlayerDTO dto, Player player) {
        try {
            // Buscar dados do summoner
            Optional<RiotAPIService.SummonerData> summonerData = riotAPIService.getSummonerByPUUID(player.getPuuid(),
                    player.getRegion());

            if (summonerData.isPresent()) {
                RiotAPIService.SummonerData data = summonerData.get();
                dto.setProfileIconId(data.getProfileIconId());
                dto.setSummonerLevel(data.getSummonerLevel());

                // ✅ NOVO: Adicionar URL do ícone de perfil ao DTO
                if (data.getProfileIconId() != null) {
                    String profileIconUrl = String.format(
                            "https://ddragon.leagueoflegends.com/cdn/15.19.1/img/profileicon/%d.png",
                            data.getProfileIconId());
                    dto.setProfileIconUrl(profileIconUrl);
                }

                // Buscar dados ranked
                List<RiotAPIService.RankedData> rankedData = riotAPIService.getRankedData(data.getId(),
                        player.getRegion());

                rankedData.stream()
                        .filter(rd -> "RANKED_SOLO_5x5".equals(rd.getQueueType()))
                        .findFirst()
                        .ifPresent(rd -> {
                            dto.setTier(rd.getTier());
                            dto.setRank(rd.getRank());
                            dto.setLeaguePoints(rd.getLeaguePoints());
                        });
            }

            // Buscar dados da conta (gameName#tagLine) se estivermos armazenando o nome
            // nesse formato
            if (player.getSummonerName() != null && player.getSummonerName().contains("#")) {
                try {
                    String[] parts = player.getSummonerName().split("#", 2);
                    String gameName = parts[0];
                    String tagLine = parts[1];
                    Optional<RiotAPIService.AccountData> accountData = riotAPIService.getAccountByRiotId(gameName,
                            tagLine, player.getRegion());

                    if (accountData.isPresent()) {
                        RiotAPIService.AccountData data = accountData.get();
                        dto.setGameName(data.getGameName());
                        dto.setTagLine(data.getTagLine());
                    }
                } catch (Exception e) {
                    log.debug("Erro ao buscar account by Riot ID para {}: {}", player.getSummonerName(),
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Erro ao enriquecer DTO do jogador {} da Riot API: {}",
                    dto.getSummonerName(), e.getMessage());
        }
    }

    /**
     * Atualiza as estatísticas completas do jogador:
     * - Top 5 campeões das custom matches
     * - Top 3 campeões de maestria (Riot API)
     * - Top 5 campeões ranked (Riot API)
     * 
     * @param summonerName Nome do jogador
     * @param forceUpdate  Se true, ignora o cache de 2 dias e força atualização da
     *                     Riot API
     */
    @Transactional
    public void updatePlayerChampionStats(String summonerName, boolean forceUpdate) {
        log.info("🔄 Atualizando estatísticas de campeões para: {} (forçar: {})", summonerName, forceUpdate);

        Optional<Player> playerOpt = playerRepository.findBySummonerNameIgnoreCase(summonerName);
        if (playerOpt.isEmpty()) {
            log.warn("⚠️ Jogador não encontrado: {}", summonerName);
            return;
        }

        Player player = playerOpt.get();

        try {
            // 1. Top 5 campeões das custom matches (sempre atualiza - dados locais)
            List<Map<String, Object>> draftChampions = extractTop5CustomChampions(summonerName);
            player.setPlayerStatsDraft(convertToJson(draftChampions));
            log.info("✅ Top 5 custom champions para {}: {}", summonerName, draftChampions.size());

            // 2. Verificar se precisa atualizar dados da Riot API (cache de 5 dias)
            boolean needsRiotApiUpdate = forceUpdate; // Se forçado, sempre atualiza

            if (!forceUpdate && player.getStatsLastUpdated() != null) {
                java.time.Instant lastUpdate = player.getStatsLastUpdated();
                java.time.Instant fiveDaysAgo = java.time.Instant.now().minus(5, java.time.temporal.ChronoUnit.DAYS);

                if (lastUpdate.isAfter(fiveDaysAgo)) {
                    needsRiotApiUpdate = false;
                    log.info(
                            "⏭️ Dados da Riot API ainda válidos para {} (última atualização: {}). Pulando atualização.",
                            summonerName, lastUpdate);
                }
            }

            // Se não precisa atualizar Riot API, apenas salva os dados das custom matches e
            // retorna
            if (!needsRiotApiUpdate) {
                playerRepository.save(player);
                log.info("✅ Estatísticas de custom matches atualizadas para: {} (Riot API em cache)", summonerName);
                return;
            }

            if (forceUpdate) {
                log.info("🔄 Atualização forçada - ignorando cache.");
            } else {
                log.info("🔄 Dados da Riot API expirados. Atualizando...");
            }

            log.info("🔄 Dados da Riot API expirados ou inexistentes. Atualizando...");

            // 3. Verificar/Buscar PUUID correto da Riot API
            String puuid = player.getPuuid();

            // Se PUUID está vazio, inválido (formato UUID) ou não funciona, buscar o
            // correto
            if (puuid == null || puuid.isEmpty() || puuid.contains("-")) {
                log.info("🔄 PUUID inválido ou vazio, buscando da Riot API...");
                String riotPuuid = riotChampionStatsService.getRiotPuuid(summonerName);

                if (riotPuuid != null) {
                    player.setPuuid(riotPuuid);
                    puuid = riotPuuid;
                    playerRepository.save(player);
                    log.info("✅ PUUID atualizado para {}: {}...", summonerName,
                            puuid.substring(0, Math.min(20, puuid.length())));
                } else {
                    log.warn("⚠️ Não foi possível buscar PUUID da Riot API para {}", summonerName);
                }
            } else {
                log.info("🔍 PUUID encontrado para {}: {}...", summonerName,
                        puuid.substring(0, Math.min(20, puuid.length())));
            }

            // 4. Top 3 campeões de maestria (Riot API) - se tiver PUUID válido
            if (puuid != null && !puuid.isEmpty()) {
                try {
                    List<PlayerChampionStatsDTO.ChampionMasteryStats> masteryChampions = riotChampionStatsService
                            .getTopMasteryChampions(puuid);
                    player.setMasteryChampions(convertToJson(masteryChampions));
                    log.info("✅ Top 3 maestria para {}: {}", summonerName, masteryChampions.size());
                } catch (Exception e) {
                    log.error("⚠️ Erro ao buscar maestria para {}: {}", summonerName, e.getMessage(), e);
                }
            } else {
                log.warn("⚠️ Jogador {} não tem PUUID válido - pulando maestria", summonerName);
            }

            // 5. Top 5 campeões ranked (Riot API) - se tiver PUUID válido
            if (puuid != null && !puuid.isEmpty()) {
                try {
                    List<PlayerChampionStatsDTO.ChampionRankedStats> rankedChampions = riotChampionStatsService
                            .getTopRankedChampions(puuid);
                    player.setRankedChampions(convertToJson(rankedChampions));
                    log.info("✅ Top 5 ranked para {}: {}", summonerName, rankedChampions.size());
                } catch (Exception e) {
                    log.error("⚠️ Erro ao buscar stats ranked para {}: {}", summonerName, e.getMessage(), e);
                }
            } else {
                log.warn("⚠️ Jogador {} não tem PUUID válido - pulando ranked", summonerName);
            }

            player.setStatsLastUpdated(java.time.Instant.now());
            playerRepository.save(player);
            log.info("✅ Estatísticas atualizadas para: {}", summonerName);

        } catch (Exception e) {
            log.error("❌ Erro ao atualizar estatísticas de campeões para {}: {}", summonerName, e.getMessage(), e);
        }
    }

    /**
     * Atualiza estatísticas de campeões (versão sem forceUpdate para
     * compatibilidade)
     */
    @Transactional
    public void updatePlayerChampionStats(String summonerName) {
        updatePlayerChampionStats(summonerName, false);
    }

    /**
     * Atualiza estatísticas de campeões no login do jogador
     * - Sempre atualiza player_stats_draft (dados locais, rápido)
     * - Só atualiza ranked_champions e mastery_champions se:
     * 1. Não tiver dados (null ou vazio)
     * 2. Dados tiverem mais de 10 dias
     * - Prioriza preencher dados que o jogador não tem
     * 
     * @param summonerName Nome do jogador
     */
    @Transactional
    public void updatePlayerChampionStatsOnLogin(String summonerName) {
        log.info("🔄 [Login] Verificando estatísticas de campeões para: {}", summonerName);

        Optional<Player> playerOpt = playerRepository.findBySummonerNameIgnoreCase(summonerName);
        if (playerOpt.isEmpty()) {
            log.warn("⚠️ [Login] Jogador não encontrado: {}", summonerName);
            return;
        }

        Player player = playerOpt.get();

        try {
            // 1. SEMPRE atualiza player_stats_draft (dados locais das custom matches)
            List<Map<String, Object>> draftChampions = extractTop5CustomChampions(summonerName);
            player.setPlayerStatsDraft(convertToJson(draftChampions));
            log.info("✅ [Login] Top 5 custom champions para {}: {}", summonerName, draftChampions.size());

            // 2. Verificar se precisa atualizar dados da Riot API (cache de 10 dias)
            boolean needsRiotApiUpdate = false;
            boolean hasMissingData = false;

            // Verificar se tem dados faltando (priorizar)
            if (player.getRankedChampions() == null || player.getRankedChampions().isEmpty() ||
                    player.getMasteryChampions() == null || player.getMasteryChampions().isEmpty()) {
                hasMissingData = true;
                needsRiotApiUpdate = true;
                log.info("🔍 [Login] Jogador {} tem dados faltando - priorizando atualização", summonerName);
            }

            // Se tem todos os dados, verificar idade (10 dias)
            if (!hasMissingData && player.getStatsLastUpdated() != null) {
                java.time.Instant lastUpdate = player.getStatsLastUpdated();
                java.time.Instant tenDaysAgo = java.time.Instant.now().minus(10, java.time.temporal.ChronoUnit.DAYS);

                if (lastUpdate.isBefore(tenDaysAgo)) {
                    needsRiotApiUpdate = true;
                    log.info("🔍 [Login] Dados da Riot API expirados para {} (última atualização: {})",
                            summonerName, lastUpdate);
                } else {
                    log.info("⏭️ [Login] Dados da Riot API ainda válidos para {} (última atualização: {}). Pulando.",
                            summonerName, lastUpdate);
                }
            }

            // Se não precisa atualizar Riot API, apenas salva os dados das custom matches
            if (!needsRiotApiUpdate) {
                playerRepository.save(player);
                log.info("✅ [Login] Estatísticas de custom matches atualizadas para: {} (Riot API em cache)",
                        summonerName);
                return;
            }

            log.info("🔄 [Login] Atualizando dados da Riot API para {}...", summonerName);

            // 3. Verificar/Buscar PUUID correto da Riot API
            String puuid = player.getPuuid();

            if (puuid == null || puuid.isEmpty() || puuid.contains("-")) {
                log.info("🔄 [Login] PUUID inválido ou vazio, buscando da Riot API...");
                String riotPuuid = riotChampionStatsService.getRiotPuuid(summonerName);

                if (riotPuuid != null) {
                    player.setPuuid(riotPuuid);
                    puuid = riotPuuid;
                    playerRepository.save(player);
                    log.info("✅ [Login] PUUID atualizado para {}: {}...", summonerName,
                            puuid.substring(0, Math.min(20, puuid.length())));
                } else {
                    log.warn("⚠️ [Login] Não foi possível buscar PUUID da Riot API para {}", summonerName);
                }
            }

            // 4. Top 3 campeões de maestria (Riot API) - se tiver PUUID válido
            if (puuid != null && !puuid.isEmpty()) {
                // Priorizar se não tem dados
                if (player.getMasteryChampions() == null || player.getMasteryChampions().isEmpty()) {
                    log.info("🎯 [Login] PRIORIZANDO maestria (sem dados) para {}", summonerName);
                }

                try {
                    List<PlayerChampionStatsDTO.ChampionMasteryStats> masteryChampions = riotChampionStatsService
                            .getTopMasteryChampions(puuid);
                    player.setMasteryChampions(convertToJson(masteryChampions));
                    log.info("✅ [Login] Top 3 maestria para {}: {}", summonerName, masteryChampions.size());
                } catch (Exception e) {
                    log.error("⚠️ [Login] Erro ao buscar maestria para {}: {}", summonerName, e.getMessage());
                }
            }

            // 5. Top 5 campeões ranked (Riot API) - se tiver PUUID válido
            if (puuid != null && !puuid.isEmpty()) {
                // Priorizar se não tem dados
                if (player.getRankedChampions() == null || player.getRankedChampions().isEmpty()) {
                    log.info("🎯 [Login] PRIORIZANDO ranked (sem dados) para {}", summonerName);
                }

                try {
                    List<PlayerChampionStatsDTO.ChampionRankedStats> rankedChampions = riotChampionStatsService
                            .getTopRankedChampions(puuid);
                    player.setRankedChampions(convertToJson(rankedChampions));
                    log.info("✅ [Login] Top 5 ranked para {}: {}", summonerName, rankedChampions.size());
                } catch (Exception e) {
                    log.error("⚠️ [Login] Erro ao buscar stats ranked para {}: {}", summonerName, e.getMessage());
                }
            }

            player.setStatsLastUpdated(java.time.Instant.now());
            playerRepository.save(player);
            log.info("✅ [Login] Estatísticas atualizadas para: {}", summonerName);

        } catch (Exception e) {
            log.error("❌ [Login] Erro ao atualizar estatísticas de campeões para {}: {}", summonerName,
                    e.getMessage(), e);
        }
    }

    /**
     * Extrai os top 5 campeões mais pickados nas custom matches do jogador
     */
    private List<Map<String, Object>> extractTop5CustomChampions(String summonerName) {
        // Buscar todas as custom matches do jogador
        List<Object[]> matches = playerRepository.findCustomMatchesForPlayer(summonerName);

        // Acumular estatísticas por campeão
        Map<String, ChampionCustomStats> championStats = new HashMap<>();

        for (Object[] match : matches) {
            String participantsDataJson = (String) match[5]; // participants_data
            Long matchId = (Long) match[0];

            if (participantsDataJson == null || participantsDataJson.isEmpty()) {
                continue;
            }

            try {
                // Procurar pelo jogador no JSON
                String playerKey = "\"summonerName\":\"" + summonerName + "\"";
                int playerIndex = participantsDataJson.indexOf(playerKey);
                if (playerIndex == -1) {
                    continue;
                }

                int objectStart = participantsDataJson.lastIndexOf("{", playerIndex);
                int objectEnd = participantsDataJson.indexOf("}", playerIndex);
                if (objectStart == -1 || objectEnd == -1) {
                    continue;
                }

                String playerData = participantsDataJson.substring(objectStart, objectEnd + 1);

                // Extrair dados do campeão
                String championName = extractJsonString(playerData, "championName");
                int championId = extractJsonInt(playerData, "championId");
                boolean win = extractJsonBoolean(playerData, "win");

                String championKey = championName != null && !championName.isEmpty()
                        ? championName
                        : "Champion " + championId;

                ChampionCustomStats stats = championStats.computeIfAbsent(
                        championKey,
                        k -> new ChampionCustomStats(championKey, championId));
                stats.addGame(win);

            } catch (Exception e) {
                log.warn("Erro ao processar match {} para {}: {}", matchId, summonerName, e.getMessage());
            }
        }

        // Converter para lista e ordenar por games played
        return championStats.values().stream()
                .sorted((a, b) -> Integer.compare(b.gamesPlayed, a.gamesPlayed))
                .limit(5)
                .map(stats -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("championId", stats.championId);
                    map.put("championName", stats.championName);
                    map.put("gamesPlayed", stats.gamesPlayed);
                    map.put("wins", stats.wins);
                    map.put("losses", stats.losses);
                    map.put("winRate", stats.getWinRate());
                    return map;
                })
                .toList();
    }

    /**
     * Extrai um valor boolean de uma chave no JSON
     */
    private boolean extractJsonBoolean(String json, String key) {
        try {
            String keyPattern = "\"" + key + "\":";
            int keyIndex = json.indexOf(keyPattern);
            if (keyIndex == -1) {
                return false;
            }

            int valueStart = keyIndex + keyPattern.length();
            int commaIndex = json.indexOf(",", valueStart);
            int braceIndex = json.indexOf("}", valueStart);
            int endIndex = (commaIndex != -1 && commaIndex < braceIndex) ? commaIndex : braceIndex;

            if (endIndex == -1) {
                return false;
            }

            String value = json.substring(valueStart, endIndex).trim();
            return Boolean.parseBoolean(value);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Converte objeto para JSON string
     */
    private String convertToJson(Object data) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            log.error("Erro ao converter para JSON: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * Classe auxiliar para acumular estatísticas de campeões custom
     */
    private static class ChampionCustomStats {
        private final String championName;
        private final int championId;
        private int gamesPlayed = 0;
        private int wins = 0;
        private int losses = 0;

        public ChampionCustomStats(String championName, int championId) {
            this.championName = championName;
            this.championId = championId;
        }

        public void addGame(boolean win) {
            gamesPlayed++;
            if (win) {
                wins++;
            } else {
                losses++;
            }
        }

        public double getWinRate() {
            return gamesPlayed > 0 ? (wins * 100.0 / gamesPlayed) : 0.0;
        }
    }
}
