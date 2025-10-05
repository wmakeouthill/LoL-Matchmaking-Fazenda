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

    @Mapping(target = "matchId", source = "id")
    @Mapping(target = "createdAt", expression = "java(instantToLocalDateTime(match.getCreatedAt()))")
    @Mapping(target = "updatedAt", expression = "java(instantToLocalDateTime(match.getUpdatedAt()))")
    @Mapping(target = "matchStartedAt", ignore = true)
    @Mapping(target = "matchEndedAt", expression = "java(instantToLocalDateTime(match.getCompletedAt()))")
    @Mapping(target = "players", ignore = true) // Será preenchido externamente
    @Mapping(target = "teams", ignore = true) // Será preenchido externamente
    @Mapping(target = "gameMode", source = "gameMode")
    @Mapping(target = "mapName", ignore = true)
    @Mapping(target = "duration", source = "duration")
    @Mapping(target = "winnerTeam", source = "winnerTeam")
    @Mapping(target = "lobbyPassword", ignore = true)
    @Mapping(target = "isCustomGame", ignore = true) // Assumir true
    @Mapping(target = "riotMatchId", source = "riotGameId")
    @Mapping(target = "participantsData", source = "participantsData") // ✅ Incluir dados dos participantes
    MatchDTO toDTO(Match match);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "title", ignore = true)
    @Mapping(target = "description", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", expression = "java(localDateTimeToInstant(matchDTO.getCreatedAt()))")
    @Mapping(target = "completedAt", expression = "java(localDateTimeToInstant(matchDTO.getMatchEndedAt()))")
    @Mapping(target = "updatedAt", expression = "java(localDateTimeToInstant(matchDTO.getUpdatedAt()))")
    @Mapping(target = "team1PlayersJson", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "team2PlayersJson", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "averageMmrTeam1", ignore = true) // Será calculado
    @Mapping(target = "averageMmrTeam2", ignore = true) // Será calculado
    @Mapping(target = "mmrChanges", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "gameMode", source = "gameMode")
    @Mapping(target = "duration", source = "duration")
    @Mapping(target = "actualDuration", source = "duration")
    @Mapping(target = "actualWinner", source = "winnerTeam")
    @Mapping(target = "riotGameId", source = "riotMatchId")
    @Mapping(target = "riotId", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "pickBanData", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "detectedByLcu", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "linkedResults", ignore = true) // Será preenchido pelo serviço
    @Mapping(target = "lpChanges", ignore = true)
    @Mapping(target = "participantsData", ignore = true)
    @Mapping(target = "notes", ignore = true)
    @Mapping(target = "customLp", ignore = true)
    @Mapping(target = "matchLeader", ignore = true)
    @Mapping(target = "ownerBackendId", ignore = true)
    @Mapping(target = "ownerHeartbeat", ignore = true)
    @Mapping(target = "lcuMatchData", ignore = true)
    @Mapping(target = "status", ignore = true)
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
