package br.com.lolmatchmaking.backend.domain.repository;

import br.com.lolmatchmaking.backend.domain.entity.Match;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MatchRepository extends JpaRepository<Match, Long> {

    List<Match> findTop20ByOrderByCreatedAtDesc();

    Optional<Match> findByMatchIdentifier(String matchIdentifier);

    Page<Match> findByStatus(String status, Pageable pageable);

    List<Match> findByStatusIn(List<String> statuses);

    Page<Match> findByCreatedAtAfter(Instant since, Pageable pageable);

    @Query("SELECT m FROM Match m WHERE m.team1PlayersJson LIKE %:summonerName% OR m.team2PlayersJson LIKE %:summonerName%")
    Page<Match> findByPlayerInvolved(@Param("summonerName") String summonerName, Pageable pageable);

    @Query("SELECT m FROM Match m WHERE m.team1PlayersJson LIKE %:summonerName% OR m.team2PlayersJson LIKE %:summonerName%")
    List<Match> findPlayerMatches(@Param("summonerName") String summonerName);
}
