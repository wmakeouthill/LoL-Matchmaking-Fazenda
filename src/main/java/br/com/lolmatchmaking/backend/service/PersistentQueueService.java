package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.QueuePlayer;
import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.QueuePlayerRepository;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import br.com.lolmatchmaking.backend.dto.QueuePlayerInfoDTO;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersistentQueueService {

    private final QueuePlayerRepository queuePlayerRepository;
    private final PlayerRepository playerRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final MultiBackendSyncService syncService;

    @Value("${app.queue.max-wait-time:1800}") // 30 minutos
    private long maxWaitTimeSeconds;

    @Value("${app.queue.cleanup-interval:300}") // 5 minutos
    private long cleanupIntervalSeconds;

    @Value("${app.queue.sync-interval:10}") // 10 segundos
    private long syncIntervalSeconds;

    // Cache local da fila
    private final Map<String, QueuePlayer> queueCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSync = new ConcurrentHashMap<>();

    /**
     * Inicializa o serviço de fila persistente
     */
    public void initialize() {
        log.info("🔄 Inicializando PersistentQueueService...");
        loadQueueFromDatabase();
        startQueueMonitoring();
        log.info("✅ PersistentQueueService inicializado");
    }

    /**
     * Carrega fila do banco de dados
     */
    @Transactional
    public void loadQueueFromDatabase() {
        try {
            List<QueuePlayer> activePlayers = queuePlayerRepository.findByActiveTrueOrderByJoinTimeAsc();

            queueCache.clear();
            for (QueuePlayer player : activePlayers) {
                queueCache.put(player.getSummonerName(), player);
            }

            log.info("📥 Fila carregada do banco: {} jogadores", queueCache.size());
        } catch (Exception e) {
            log.error("❌ Erro ao carregar fila do banco", e);
        }
    }

    /**
     * Adiciona jogador à fila
     */
    @Transactional
    public boolean addToQueue(String summonerName, String primaryLane, String secondaryLane) {
        try {
            // Verificar se já está na fila
            if (queueCache.containsKey(summonerName)) {
                log.warn("⚠️ Jogador {} já está na fila", summonerName);
                return false;
            }

            // Buscar dados do jogador
            Optional<Player> playerOpt = playerRepository.findBySummonerName(summonerName);
            if (playerOpt.isEmpty()) {
                log.warn("⚠️ Jogador {} não encontrado", summonerName);
                return false;
            }

            Player player = playerOpt.get();

            // Criar entrada na fila
            QueuePlayer queuePlayer = new QueuePlayer();
            queuePlayer.setPlayerId(player.getId());
            queuePlayer.setSummonerName(summonerName);
            queuePlayer.setRegion(player.getRegion());
            queuePlayer.setCustomLp(player.getCustomLp());
            queuePlayer.setPrimaryLane(primaryLane);
            queuePlayer.setSecondaryLane(secondaryLane);
            queuePlayer.setJoinTime(Instant.now());
            queuePlayer.setQueuePosition(calculateQueuePosition());
            queuePlayer.setActive(true);
            queuePlayer.setAcceptanceStatus(0);

            // Salvar no banco
            QueuePlayer savedPlayer = queuePlayerRepository.save(queuePlayer);

            // Adicionar ao cache
            queueCache.put(summonerName, savedPlayer);

            // Registrar evento de sincronização
            syncService.registerEvent("player_joined_queue", Map.of(
                    "summonerName", summonerName,
                    "primaryLane", primaryLane,
                    "secondaryLane", secondaryLane), null);

            // Broadcast da atualização
            broadcastQueueUpdate();

            log.info("✅ Jogador {} adicionado à fila (Posição: {})", summonerName, savedPlayer.getQueuePosition());
            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao adicionar jogador à fila", e);
            return false;
        }
    }

    /**
     * Remove jogador da fila
     */
    @Transactional
    public boolean removeFromQueue(String summonerName) {
        try {
            QueuePlayer queuePlayer = queueCache.remove(summonerName);
            if (queuePlayer == null) {
                log.warn("⚠️ Jogador {} não está na fila", summonerName);
                return false;
            }

            // Marcar como inativo no banco
            queuePlayer.setActive(false);
            queuePlayer.setAcceptanceStatus(0);
            queuePlayerRepository.save(queuePlayer);

            // Registrar evento de sincronização
            syncService.registerEvent("player_left_queue", Map.of(
                    "summonerName", summonerName), null);

            // Recalcular posições
            recalculateQueuePositions();

            // Broadcast da atualização
            broadcastQueueUpdate();

            log.info("✅ Jogador {} removido da fila", summonerName);
            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao remover jogador da fila", e);
            return false;
        }
    }

    /**
     * Obtém status da fila
     */
    public List<QueuePlayerInfoDTO> getQueueStatus() {
        return queueCache.values().stream()
                .map(this::convertToQueuePlayerInfoDTO)
                .sorted(Comparator.comparing(QueuePlayerInfoDTO::getQueuePosition))
                .collect(Collectors.toList());
    }

    /**
     * Obtém jogadores elegíveis para matchmaking
     */
    public List<QueuePlayer> getEligiblePlayers() {
        return queueCache.values().stream()
                .filter(QueuePlayer::getActive)
                .filter(player -> "waiting".equals(player.getAcceptanceStatus()))
                .filter(player -> !isPlayerTimedOut(player))
                .collect(Collectors.toList());
    }

    /**
     * Verifica se jogador está na fila
     */
    public boolean isPlayerInQueue(String summonerName) {
        return queueCache.containsKey(summonerName);
    }

    /**
     * Obtém tamanho da fila
     */
    public int getQueueSize() {
        return queueCache.size();
    }

    /**
     * Monitora a fila
     */
    @Scheduled(fixedRate = 10000) // A cada 10 segundos
    @Async
    public void startQueueMonitoring() {
        try {
            // Limpar jogadores expirados
            cleanupExpiredPlayers();

            // Sincronizar com outros backends
            syncWithOtherBackends();

            // Atualizar estatísticas
            updateQueueStatistics();

        } catch (Exception e) {
            log.error("❌ Erro no monitoramento da fila", e);
        }
    }

    /**
     * Limpa jogadores expirados
     */
    @Transactional
    public void cleanupExpiredPlayers() {
        try {
            Instant cutoffTime = Instant.now().minus(maxWaitTimeSeconds, ChronoUnit.SECONDS);

            List<QueuePlayer> expiredPlayers = queueCache.values().stream()
                    .filter(player -> player.getJoinTime().isBefore(cutoffTime))
                    .collect(Collectors.toList());

            for (QueuePlayer player : expiredPlayers) {
                log.info("⏰ Removendo jogador expirado: {} (Esperou {} minutos)",
                        player.getSummonerName(),
                        ChronoUnit.MINUTES.between(player.getJoinTime(), Instant.now()));

                removeFromQueue(player.getSummonerName());
            }

        } catch (Exception e) {
            log.error("❌ Erro ao limpar jogadores expirados", e);
        }
    }

    /**
     * Sincroniza com outros backends
     */
    @Async
    public void syncWithOtherBackends() {
        try {
            // Implementar lógica de sincronização
            // Por enquanto, apenas log
            log.debug("🔄 Sincronizando fila com outros backends");

        } catch (Exception e) {
            log.error("❌ Erro na sincronização", e);
        }
    }

    /**
     * Atualiza estatísticas da fila
     */
    private void updateQueueStatistics() {
        try {
            int activePlayers = (int) queueCache.values().stream()
                    .filter(QueuePlayer::getActive)
                    .count();

            double avgWaitTime = queueCache.values().stream()
                    .filter(QueuePlayer::getActive)
                    .mapToLong(player -> ChronoUnit.SECONDS.between(player.getJoinTime(), Instant.now()))
                    .average()
                    .orElse(0);

            log.debug("📊 Estatísticas da fila - Ativos: {}, Tempo médio: {:.1f}s",
                    activePlayers, avgWaitTime);

        } catch (Exception e) {
            log.error("❌ Erro ao atualizar estatísticas", e);
        }
    }

    /**
     * Calcula posição na fila
     */
    private Integer calculateQueuePosition() {
        return queueCache.size() + 1;
    }

    /**
     * Recalcula posições na fila
     */
    @Transactional
    public void recalculateQueuePositions() {
        try {
            List<QueuePlayer> activePlayers = queueCache.values().stream()
                    .filter(QueuePlayer::getActive)
                    .sorted(Comparator.comparing(QueuePlayer::getJoinTime))
                    .collect(Collectors.toList());

            for (int i = 0; i < activePlayers.size(); i++) {
                QueuePlayer player = activePlayers.get(i);
                player.setQueuePosition(i + 1);
                queuePlayerRepository.save(player);
            }

        } catch (Exception e) {
            log.error("❌ Erro ao recalcular posições", e);
        }
    }

    /**
     * Verifica se jogador está com timeout
     */
    private boolean isPlayerTimedOut(QueuePlayer player) {
        return ChronoUnit.SECONDS.between(player.getJoinTime(), Instant.now()) > maxWaitTimeSeconds;
    }

    /**
     * Converte QueuePlayer para DTO
     */
    private QueuePlayerInfoDTO convertToQueuePlayerInfoDTO(QueuePlayer queuePlayer) {
        QueuePlayerInfoDTO dto = new QueuePlayerInfoDTO();
        dto.setId(queuePlayer.getId());
        dto.setSummonerName(queuePlayer.getSummonerName());
        dto.setTagLine(""); // QueuePlayer não tem tagLine
        dto.setRegion(queuePlayer.getRegion());
        dto.setCustomLp(queuePlayer.getCustomLp());
        dto.setPrimaryLane(queuePlayer.getPrimaryLane());
        dto.setSecondaryLane(queuePlayer.getSecondaryLane());
        dto.setJoinTime(queuePlayer.getJoinTime() != null
                ? queuePlayer.getJoinTime().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                : null);
        dto.setQueuePosition(queuePlayer.getQueuePosition());
        dto.setIsActive(queuePlayer.getActive());
        dto.setAcceptanceStatus(queuePlayer.getAcceptanceStatus());
        dto.setIsCurrentPlayer(false);
        return dto;
    }

    /**
     * Broadcast da atualização da fila
     */
    private void broadcastQueueUpdate() {
        try {
            List<QueuePlayerInfoDTO> queueStatus = getQueueStatus();
            webSocketService.broadcastQueueUpdate(queueStatus);
        } catch (Exception e) {
            log.error("❌ Erro ao fazer broadcast da fila", e);
        }
    }

    /**
     * Obtém estatísticas da fila
     */
    public Map<String, Object> getQueueStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPlayers", queueCache.size());
        stats.put("activePlayers", queueCache.values().stream().mapToInt(p -> p.getActive() ? 1 : 0).sum());
        stats.put("averageWaitTime", queueCache.values().stream()
                .filter(QueuePlayer::getActive)
                .mapToLong(p -> ChronoUnit.SECONDS.between(p.getJoinTime(), Instant.now()))
                .average().orElse(0));
        stats.put("lastSync", lastSync);
        return stats;
    }

    /**
     * Força sincronização com banco
     */
    @Transactional
    public void forceSyncWithDatabase() {
        log.info("🔄 Forçando sincronização com banco de dados");
        loadQueueFromDatabase();
        broadcastQueueUpdate();
    }
}
