package br.com.lolmatchmaking.backend.mapper;

import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.dto.PlayerDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface PlayerMapper {

    PlayerMapper INSTANCE = Mappers.getMapper(PlayerMapper.class);

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
    PlayerDTO toDTO(Player player);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "currentMmr", ignore = true)
    @Mapping(target = "peakMmr", ignore = true)
    @Mapping(target = "gamesPlayed", ignore = true)
    @Mapping(target = "winStreak", ignore = true)
    @Mapping(target = "customMmr", ignore = true)
    @Mapping(target = "customPeakMmr", ignore = true)
    @Mapping(target = "customGamesPlayed", ignore = true)
    @Mapping(target = "customWins", ignore = true)
    @Mapping(target = "customLosses", ignore = true)
    @Mapping(target = "customWinStreak", ignore = true)
    @Mapping(target = "customLp", ignore = true)
    Player toEntity(PlayerDTO playerDTO);
}
