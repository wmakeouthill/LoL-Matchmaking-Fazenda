package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.QueuePlayer;
import br.com.lolmatchmaking.backend.domain.repository.QueuePlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ⚠️ SERVIÇO LEGADO - SUBSTITUÍDO POR MatchFoundService
 * 
 * Este serviço foi substituído pelo MatchFoundService que usa
 * RedisMatchAcceptanceService.
 * O MatchFoundService oferece:
 * - Persistência em Redis (sobrevive a reinícios)
 * - Performance superior (operações O(1))
 * - TTL automático (auto-limpeza)
 * - Thread-safety garantido
 * 
 * MOTIVO: Este serviço usa ConcurrentHashMaps que perdem dados em reinícios do
 * backend.
 * STATUS: Mantido temporariamente por compatibilidade com
 * MatchmakingOrchestrator.
 * TODO: Migrar MatchmakingOrchestrator para usar MatchFoundService diretamente.
 * 
 * @deprecated Desde migração Redis. Use {@link MatchFoundService} e
 *             {@link RedisMatchAcceptanceService}.
 */
@Deprecated(forRemoval = true)
@Service
@RequiredArgsConstructor
@Slf4j
public class AcceptanceService {
    private final QueuePlayerRepository queueRepo;

    public static class AcceptanceSession {
        public final long matchTempId; // id temporário antes de criar custom_match
        public final List<Long> queuePlayerIds;
        public final Instant createdAt = Instant.now();
        public final long timeoutMillis;

        public AcceptanceSession(long matchTempId, List<Long> queuePlayerIds, long timeoutMillis) {
            this.matchTempId = matchTempId;
            this.queuePlayerIds = queuePlayerIds;
            this.timeoutMillis = timeoutMillis;
        }
    }

    // matchTempId -> session
    /**
     * @deprecated Substituído por RedisMatchAcceptanceService (chave:
     *             match:{matchId}:acceptances)
     */
    @Deprecated(forRemoval = true)
    private final Map<Long, AcceptanceSession> sessions = new ConcurrentHashMap<>();

    // queuePlayerIds que recusaram manualmente (para distinguir timeout)
    /**
     * @deprecated Substituído por RedisMatchAcceptanceService (chave:
     *             match:{matchId}:metadata)
     */
    @Deprecated(forRemoval = true)
    private final Set<Long> manualDeclines = ConcurrentHashMap.newKeySet();

    public AcceptanceSession createSession(List<QueuePlayer> players, long timeoutMillis) {
        long id = System.nanoTime();
        AcceptanceSession s = new AcceptanceSession(id, players.stream().map(QueuePlayer::getId).toList(),
                timeoutMillis);
        sessions.put(id, s);
        log.info("Sessão de aceitação criada {} para {} jogadores", id, players.size());
        return s;
    }

    @Transactional
    public void accept(long queuePlayerId) {
        queueRepo.findById(queuePlayerId).ifPresent(qp -> {
            qp.setAcceptanceStatus(1);
            queueRepo.save(qp);
        });
    }

    @Transactional
    public void decline(long queuePlayerId) {
        queueRepo.findById(queuePlayerId).ifPresent(qp -> {
            qp.setAcceptanceStatus(2);
            queueRepo.save(qp);
            manualDeclines.add(queuePlayerId);
        });
    }

    public Map<String, Object> status(long matchTempId) {
        AcceptanceSession s = sessions.get(matchTempId);
        if (s == null)
            return Map.of("exists", false);
        List<QueuePlayer> players = queueRepo.findAllById(s.queuePlayerIds);
        int accepted = (int) players.stream()
                .filter(p -> p.getAcceptanceStatus() != null && p.getAcceptanceStatus() == 1).count();
        int declined = (int) players.stream()
                .filter(p -> p.getAcceptanceStatus() != null && p.getAcceptanceStatus() == 2).count();
        boolean allResolved = accepted + declined == players.size();
        long remainingMs = s.timeoutMillis - (System.currentTimeMillis() - s.createdAt.toEpochMilli());
        return Map.of(
                "exists", true,
                "players", players,
                "accepted", accepted,
                "declined", declined,
                "allResolved", allResolved,
                "remainingMs", Math.max(0, remainingMs));
    }

    public List<AcceptanceSession> allSessions() {
        return List.copyOf(sessions.values());
    }

    public Optional<AcceptanceSession> findSessionByQueuePlayerId(long queuePlayerId) {
        return sessions.values().stream()
                .filter(s -> s.queuePlayerIds.contains(queuePlayerId))
                .findFirst();
    }

    public Optional<AcceptanceSession> getSession(long matchTempId) {
        return Optional.ofNullable(sessions.get(matchTempId));
    }

    public void removeSession(long matchTempId) {
        sessions.remove(matchTempId);
    }

    public List<QueuePlayer> playersForSession(long matchTempId) {
        AcceptanceSession s = sessions.get(matchTempId);
        if (s == null)
            return List.of();
        return queueRepo.findAllById(s.queuePlayerIds);
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void monitorTimeouts() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Long, AcceptanceSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            AcceptanceSession s = e.getValue();
            if (now - s.createdAt.toEpochMilli() > s.timeoutMillis) {
                // Marcar pendentes como recusados
                queueRepo.findAllById(s.queuePlayerIds).forEach(qp -> {
                    if (qp.getAcceptanceStatus() == null || qp.getAcceptanceStatus() == 0) {
                        qp.setAcceptanceStatus(2);
                        queueRepo.save(qp);
                    }
                });
                log.info("Sessão {} expirada", s.matchTempId);
                it.remove();
            }
        }
    }

    public boolean isManualDecline(long queuePlayerId) {
        return manualDeclines.contains(queuePlayerId);
    }

    public void clearManualDeclines(Collection<Long> queuePlayerIds) {
        manualDeclines.removeAll(queuePlayerIds);
    }
}
