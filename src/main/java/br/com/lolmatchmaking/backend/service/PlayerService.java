package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import br.com.lolmatchmaking.backend.dto.PlayerDTO;
import br.com.lolmatchmaking.backend.mapper.PlayerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final PlayerMapper playerMapper;
    private final RiotAPIService riotAPIService;

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
     * Cria ou atualiza um player quando ele loga, atualizando com dados completos do LoL
     */
    @Transactional
    public Player createOrUpdatePlayerOnLogin(String summonerName, String region, Integer currentMmrFromLoL, 
                                              String summonerId, String puuid) {
        log.info("🎯🎯🎯 [PlayerService.createOrUpdatePlayerOnLogin] MÉTODO CHAMADO!");
        log.info("🔄 Criando/atualizando player no login: {} (MMR: {}, ID: {}, PUUID: {})", 
            summonerName, currentMmrFromLoL, summonerId, puuid);
        
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
            
            // ✅ Atualizar custom_mmr (current_mmr + custom_lp)
            int customLp = player.getCustomLp() != null ? player.getCustomLp() : 0;
            player.setCustomMmr(currentMmrFromLoL + customLp);
            
            log.info("✅ Player existente atualizado: {} (current_mmr: {}, custom_lp: {}, custom_mmr: {})", 
                summonerName, 
                player.getCurrentMmr(), 
                customLp,
                player.getCustomMmr());
        } else {
            player = Player.builder()
                    .summonerName(summonerName)
                    .region(region)
                    .currentMmr(currentMmrFromLoL)
                    .summonerId(summonerId)
                    .puuid(puuid)
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
            return playerRepository.findByOrderByCurrentMmrDesc()
                    .stream()
                    .skip(page * limit)
                    .limit(limit)
                    .map(playerMapper::toDTO)
                    .toList();
        } catch (Exception e) {
            log.error("Erro ao buscar leaderboard", e);
            return List.of();
        }
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
}
