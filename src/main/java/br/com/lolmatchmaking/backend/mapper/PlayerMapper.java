package br.com.lolmatchmaking.backend.mapper;

import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.dto.PlayerDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PlayerMapper {

    @Mapping(target = "tier", ignore = true) // Será preenchido externamente
    @Mapping(target = "rank", ignore = true) // Será preenchido externamente
    @Mapping(target = "leaguePoints", ignore = true) // Será preenchido externamente
    @Mapping(target = "discordId", ignore = true) // Será preenchido externamente
    @Mapping(target = "discordUsername", ignore = true) // Será preenchido externamente
    @Mapping(target = "profileIconId", ignore = true) // Será preenchido externamente
    @Mapping(target = "summonerLevel", ignore = true) // Será preenchido externamente
    @Mapping(target = "isOnline", ignore = true) // Será preenchido externamente
    @Mapping(target = "status", ignore = true) // Será preenchido externamente
    @Mapping(target = "gameName", ignore = true) // Será preenchido externamente
    @Mapping(target = "tagLine", ignore = true) // Será preenchido externamente
    @Mapping(source = "profileIconUrl", target = "profileIconUrl") // ✅ NOVO: Mapear URL do ícone de perfil
    @Mapping(target = "statsLastUpdated", expression = "java(player.getStatsLastUpdated() != null ? player.getStatsLastUpdated().toString() : null)")
    PlayerDTO toDTO(Player player);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "currentMmr", ignore = true)
    @Mapping(target = "peakMmr", ignore = true)
    @Mapping(target = "gamesPlayed", ignore = true)
    @Mapping(target = "winStreak", ignore = true)
    @Mapping(target = "wins", ignore = true)
    @Mapping(target = "losses", ignore = true)
    @Mapping(target = "customMmr", ignore = true)
    @Mapping(target = "customPeakMmr", ignore = true)
    @Mapping(target = "customGamesPlayed", ignore = true)
    @Mapping(target = "customWins", ignore = true)
    @Mapping(target = "customLosses", ignore = true)
    @Mapping(target = "customWinStreak", ignore = true)
    @Mapping(target = "customLp", ignore = true)
    @Mapping(target = "avgKills", ignore = true)
    @Mapping(target = "avgDeaths", ignore = true)
    @Mapping(target = "avgAssists", ignore = true)
    @Mapping(target = "kdaRatio", ignore = true)
    @Mapping(target = "favoriteChampion", ignore = true)
    @Mapping(target = "favoriteChampionGames", ignore = true)
    @Mapping(target = "lcuPort", ignore = true)
    @Mapping(target = "lcuProtocol", ignore = true)
    @Mapping(target = "lcuPassword", ignore = true)
    @Mapping(target = "lcuLastUpdated", ignore = true)
    @Mapping(target = "playerStatsDraft", ignore = true)
    @Mapping(target = "profileIconUrl", ignore = true) // ✅ NOVO: Não mapear do DTO para a entidade
    @Mapping(target = "masteryChampions", ignore = true)
    @Mapping(target = "rankedChampions", ignore = true)
    @Mapping(target = "statsLastUpdated", ignore = true)
    @Mapping(target = "championshipTitles", ignore = true)
    Player toEntity(PlayerDTO playerDTO);
}
