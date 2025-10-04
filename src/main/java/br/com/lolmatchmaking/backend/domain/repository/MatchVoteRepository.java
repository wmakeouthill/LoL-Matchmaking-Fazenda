package br.com.lolmatchmaking.backend.domain.repository;

import br.com.lolmatchmaking.backend.domain.entity.MatchVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository para gerenciar votos em partidas do LCU
 */
@Repository
public interface MatchVoteRepository extends JpaRepository<MatchVote, Long> {

    /**
     * Busca todos os votos de uma partida específica
     */
    List<MatchVote> findByMatchId(Long matchId);

    /**
     * Busca o voto de um jogador específico em uma partida
     * (útil para verificar se já votou ou para atualizar o voto)
     */
    Optional<MatchVote> findByMatchIdAndPlayerId(Long matchId, Long playerId);

    /**
     * Conta quantos votos cada partida do LCU recebeu
     */
    @Query("SELECT mv.lcuGameId as lcuGameId, COUNT(mv) as voteCount " +
            "FROM MatchVote mv " +
            "WHERE mv.matchId = :matchId " +
            "GROUP BY mv.lcuGameId")
    List<VoteCount> countVotesByLcuGameId(@Param("matchId") Long matchId);

    /**
     * Deleta todos os votos de uma partida (útil para resetar votação)
     */
    void deleteByMatchId(Long matchId);

    /**
     * Deleta o voto de um jogador específico em uma partida
     */
    void deleteByMatchIdAndPlayerId(Long matchId, Long playerId);

    /**
     * Interface de projeção para contagem de votos
     */
    interface VoteCount {
        Long getLcuGameId();

        Long getVoteCount();
    }
}
