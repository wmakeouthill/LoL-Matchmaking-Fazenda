package br.com.lolmatchmaking.backend.mapper;

import br.com.lolmatchmaking.backend.domain.entity.Match;
import br.com.lolmatchmaking.backend.dto.MatchDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Mapper(componentModel = "spring")
public interface MatchMapper {

    MatchMapper INSTANCE = Mappers.getMapper(MatchMapper.class);

    @Mapping(target = "matchId", source = "matchIdentifier")
    @Mapping(target = "createdAt", expression = "java(instantToLocalDateTime(match.getCreatedAt()))")
    @Mapping(target = "updatedAt", ignore = true) // Não existe no entity
    @Mapping(target = "matchStartedAt", ignore = true) // Não existe no entity
    @Mapping(target = "matchEndedAt", expression = "java(instantToLocalDateTime(match.getCompletedAt()))")
    @Mapping(target = "players", ignore = true) // Será preenchido externamente
    @Mapping(target = "teams", ignore = true) // Será preenchido externamente
    @Mapping(target = "gameMode", ignore = true) // Não existe no entity
    @Mapping(target = "mapName", ignore = true) // Não existe no entity
    @Mapping(target = "duration", source = "actualDuration")
    @Mapping(target = "winnerTeam", source = "winnerTeam")
    @Mapping(target = "lobbyPassword", ignore = true) // Não existe no entity
    @Mapping(target = "isCustomGame", ignore = true) // Assumir true
    @Mapping(target = "riotMatchId", source = "riotGameId")
    MatchDTO toDTO(Match match);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "matchIdentifier", source = "matchId")
    @Mapping(target = "createdAt", expression = "java(localDateTimeToInstant(matchDTO.getCreatedAt()))")
    @Mapping(target = "completedAt", expression = "java(localDateTimeToInstant(matchDTO.getMatchEndedAt()))")
    @Mapping(target = "team1PlayersJson", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "team2PlayersJson", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "averageMmrTeam1", ignore = true) // Será calculado
    @Mapping(target = "averageMmrTeam2", ignore = true) // Será calculado
    @Mapping(target = "mmrChangesJson", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "actualDuration", source = "duration")
    @Mapping(target = "actualWinner", source = "winnerTeam")
    @Mapping(target = "riotGameId", source = "riotMatchId")
    @Mapping(target = "riotId", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "pickBanDataJson", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "detectedByLcu", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "linkedResultsJson", ignore = true) // Será preenchido pelo serviço
    Match toEntity(MatchDTO matchDTO);

    default LocalDateTime instantToLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    default Instant localDateTimeToInstant(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
    }
}
