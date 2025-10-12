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

    // ✅ CORRIGIDO: Buscar partida ativa (TODOS os status ativos) do jogador por
    // summonerName
    // Status ativos: match_found, accepting, accepted, pending, draft, in_progress
    // Os campos team1_players e team2_players contêm comma-separated summonerNames,
    // não PUUIDs
    // ✅ CASE-INSENSITIVE: usa LOWER() para evitar problemas de case
    @Query("SELECT cm FROM CustomMatch cm " +
            "WHERE cm.status IN ('match_found', 'accepting', 'accepted', 'pending', 'draft', 'in_progress') " +
            "AND (LOWER(cm.team1PlayersJson) LIKE LOWER(CONCAT('%', :summonerName, '%')) " +
            "     OR LOWER(cm.team2PlayersJson) LIKE LOWER(CONCAT('%', :summonerName, '%'))) " +
            "ORDER BY cm.createdAt DESC")
    Optional<CustomMatch> findActiveMatchByPlayerPuuid(String summonerName);

    // Métodos adicionais necessários para compatibilidade
    List<CustomMatch> findByStatus(String status);

    List<CustomMatch> findTop10ByOrderByCreatedAtDesc();

    List<CustomMatch> findByTitleContaining(String title);
}