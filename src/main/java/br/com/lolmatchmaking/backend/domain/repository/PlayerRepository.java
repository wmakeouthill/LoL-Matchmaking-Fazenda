package br.com.lolmatchmaking.backend.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.lolmatchmaking.backend.domain.entity.Player;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findBySummonerName(String summonerName);

    Optional<Player> findBySummonerNameIgnoreCase(String summonerName);

    Optional<Player> findByPuuid(String puuid);

    @Query("SELECT p FROM Player p ORDER BY p.customMmr DESC LIMIT :limit")
    List<Player> findTopByCustomMmr(@Param("limit") int limit);

    List<Player> findByOrderByCurrentMmrDesc();

    List<Player> findTop10ByOrderByCurrentMmrDesc();

    List<Player> findTop10ByOrderByCreatedAtDesc();

    List<Player> findBySummonerNameContaining(String summonerName);
}
