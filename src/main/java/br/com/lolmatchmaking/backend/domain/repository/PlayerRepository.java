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

    List<Player> findByOrderByCustomLpDesc();

    // ✅ NOVO: Ordenação com critério de desempate (customLp DESC, customMmr DESC)
    List<Player> findByOrderByCustomLpDescCustomMmrDesc();

    List<Player> findTop10ByOrderByCurrentMmrDesc();

    List<Player> findTop10ByOrderByCreatedAtDesc();

    List<Player> findBySummonerNameContaining(String summonerName);

    @Query(value = "SELECT cm.id, cm.team1_players, cm.team2_players, cm.winner_team, cm.lp_changes, cm.participants_data "
            +
            "FROM custom_matches cm " +
            "WHERE (cm.team1_players LIKE CONCAT('%', :summonerName, '%') " +
            "   OR cm.team2_players LIKE CONCAT('%', :summonerName, '%')) " +
            "  AND cm.winner_team IS NOT NULL " +
            "ORDER BY cm.created_at DESC", nativeQuery = true)
    List<Object[]> findCustomMatchesForPlayer(@Param("summonerName") String summonerName);
}
