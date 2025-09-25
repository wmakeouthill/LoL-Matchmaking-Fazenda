package br.com.lolmatchmaking.backend.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

import br.com.lolmatchmaking.backend.domain.entity.QueuePlayer;

public interface QueuePlayerRepository extends JpaRepository<QueuePlayer, Long> {
    Optional<QueuePlayer> findBySummonerName(String summonerName);

    List<QueuePlayer> findByActiveTrueOrderByJoinTimeAsc();

    List<QueuePlayer> findByActiveTrue();
}
