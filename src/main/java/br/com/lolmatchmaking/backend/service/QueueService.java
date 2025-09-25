package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.QueuePlayer;
import br.com.lolmatchmaking.backend.domain.repository.QueuePlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class QueueService {
    private final QueuePlayerRepository queueRepo;

    @Transactional
    public QueuePlayer joinQueue(String summonerName, String region, Long playerId, Integer customLp, String primary,
            String secondary) {
        Optional<QueuePlayer> existing = queueRepo.findBySummonerName(summonerName);
        if (existing.isPresent()) {
            QueuePlayer qp = existing.get();
            qp.setActive(true);
            qp.setAcceptanceStatus(0);
            // mantém posição atual (não recalcula) – comportamento Node mantém posição
            return queueRepo.save(qp);
        }
        QueuePlayer qp = QueuePlayer.builder()
                .summonerName(summonerName)
                .region(region)
                .playerId(playerId == null ? 0L : playerId)
                .customLp(customLp)
                .primaryLane(primary)
                .secondaryLane(secondary)
                .joinTime(Instant.now())
                .acceptanceStatus(0)
                .active(true)
                .build();
        queueRepo.save(qp);
        recalcPositions();
        return qp;
    }

    @Transactional
    public void leaveQueue(String summonerName) {
        queueRepo.findBySummonerName(summonerName).ifPresent(qp -> {
            qp.setActive(false);
            queueRepo.save(qp);
            recalcPositions();
        });
    }

    public List<QueuePlayer> activePlayers() {
        return queueRepo.findByActiveTrueOrderByJoinTimeAsc();
    }

    protected void recalcPositions() {
        List<QueuePlayer> actives = activePlayers();
        int pos = 1;
        for (QueuePlayer qp : actives) {
            qp.setQueuePosition(pos++);
            queueRepo.save(qp); // pequenas quantidades, ok; otimizar batch depois
        }
    }

    public Map<String, Object> queueStatus(String currentSummonerName) {
        List<QueuePlayer> actives = activePlayers();
        boolean isInQueue = currentSummonerName != null && actives.stream()
                .anyMatch(q -> q.getSummonerName().equalsIgnoreCase(currentSummonerName));
        return Map.of(
                "playersInQueue", actives.size(),
                "isCurrentPlayerInQueue", isInQueue,
                "players", actives);
    }
}
