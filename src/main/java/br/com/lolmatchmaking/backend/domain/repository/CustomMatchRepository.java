package br.com.lolmatchmaking.backend.domain.repository;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomMatchRepository extends JpaRepository<CustomMatch, Long> {

    List<CustomMatch> findByStatusOrderByCreatedAtDesc(String status);

    List<CustomMatch> findByStatusInOrderByCreatedAtDesc(List<String> statuses);

    @Query("SELECT cm FROM CustomMatch cm WHERE cm.status = 'match_found' AND cm.createdAt > :since")
    List<CustomMatch> findActiveMatches(java.time.Instant since);

    Optional<CustomMatch> findByRiotGameId(String riotGameId);

    @Query("SELECT cm FROM CustomMatch cm WHERE cm.team1PlayersJson LIKE %:playerName% OR cm.team2PlayersJson LIKE %:playerName%")
    List<CustomMatch> findByPlayerName(String playerName);

    // ✅ NOVO: Buscar partida ativa (draft ou in_progress) do jogador por PUUID
    @Query("SELECT cm FROM CustomMatch cm " +
            "WHERE cm.status IN ('draft', 'in_progress') " +
            "AND (cm.team1PlayersJson LIKE CONCAT('%', :puuid, '%') " +
            "     OR cm.team2PlayersJson LIKE CONCAT('%', :puuid, '%')) " +
            "ORDER BY cm.createdAt DESC")
    Optional<CustomMatch> findActiveMatchByPlayerPuuid(String puuid);

    // Métodos adicionais necessários para compatibilidade
    List<CustomMatch> findByStatus(String status);

    List<CustomMatch> findTop10ByOrderByCreatedAtDesc();

    List<CustomMatch> findByTitleContaining(String title);
}