package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.QueuePlayer;
import br.com.lolmatchmaking.backend.websocket.SessionRegistry;
import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.TextMessage;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ⚠️ ORQUESTRADOR LEGADO - SUBSTITUÍDO
 * 
 * Este orquestrador foi substituído pelo fluxo:
 * QueueManagementService → MatchFoundService → DraftFlowService →
 * GameInProgressService
 * 
 * PROBLEMA:
 * - Usa AcceptanceService (legado)
 * - Usa MatchmakingService (legado)
 * - Usa QueueService (legado)
 * 
 * FLUXO NOVO (ATIVO):
 * 1. QueueManagementService.processQueue() - processa fila do SQL
 * 2. MatchFoundService.createMatchForAcceptance() - cria no Redis
 * 3. DraftFlowService.startDraft() - inicia draft
 * 4. GameInProgressService.startGame() - inicia jogo
 * 
 * STATUS: Mantido por compatibilidade, mas NÃO é usado no fluxo principal
 * 
 * @deprecated Use QueueManagementService + MatchFoundService ao invés
 */
@Deprecated(forRemoval = true)
@Component
@RequiredArgsConstructor
@Slf4j
public class MatchmakingOrchestrator {
    private final QueueService queueService;
    private final MatchmakingService matchmakingService;
    private final AcceptanceService acceptanceService;
    private final SessionRegistry sessionRegistry;
    private final CustomMatchRepository customMatchRepository;
    private final ObjectMapper mapper = new ObjectMapper();
    private final DraftFlowService draftFlowService;
    private static final String FIELD_MATCH_TEMP_ID = "matchTempId";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_PLAYERS = "players";

    // tenta formar partida (usa jogadores ativos que nao estejam locked)
    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void tick() {
        List<QueuePlayer> actives = queueService.activePlayers();
        Optional<List<QueuePlayer>> maybe = matchmakingService.tryFormMatch();
        if (maybe.isEmpty())
            return;
        List<QueuePlayer> selected = maybe.get();
        selected.forEach(p -> {
            p.setAcceptanceStatus(0);
            // lock: remover da fila marcando active=false para evitar nova match
            p.setActive(false);
        });
        var session = acceptanceService.createSession(selected, 20_000);
        broadcastMatchFound(session.matchTempId, selected);
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1500)
    @Transactional
    public void monitorAcceptance() {
        acceptanceService.allSessions().forEach(s -> processAcceptanceSession(s.matchTempId));
    }

    private void processAcceptanceSession(long matchTempId) {
        var status = acceptanceService.status(matchTempId);
        broadcastAcceptanceProgress(matchTempId, status);
        if (!Boolean.TRUE.equals(status.get("allResolved")))
            return;
        int accepted = (int) status.get("accepted");
        int declined = (int) status.get("declined");
        if (declined > 0) {
            handleDeclinedSession(matchTempId);
        } else if (accepted > 0) {
            handleFullyAcceptedSession(matchTempId);
        }
    }

    private void handleDeclinedSession(long matchTempId) {
        var playersSession = acceptanceService.playersForSession(matchTempId);
        Set<Long> acceptedIds = playersSession.stream()
                .filter(p -> p.getAcceptanceStatus() != null && p.getAcceptanceStatus() == 1)
                .map(QueuePlayer::getId)
                .collect(Collectors.toSet());
        boolean anyManual = playersSession.stream()
                .filter(p -> p.getAcceptanceStatus() != null && p.getAcceptanceStatus() == 2)
                .anyMatch(p -> acceptanceService.isManualDecline(p.getId()));
        playersSession.forEach(p -> {
            if (acceptedIds.contains(p.getId())) {
                p.setActive(true);
            }
        });
        String reason = anyManual ? "declined" : "timeout";
        broadcastMatchCancelled(matchTempId, reason);
        acceptanceService.removeSession(matchTempId);
        acceptanceService.clearManualDeclines(playersSession.stream().map(QueuePlayer::getId).toList());
    }

    private void handleFullyAcceptedSession(long matchTempId) {
        List<QueuePlayer> players = acceptanceService.playersForSession(matchTempId);
        createDraftMatchAndBroadcast(matchTempId, players);
        acceptanceService.removeSession(matchTempId);
    }

    private void createDraftMatchAndBroadcast(long matchTempId, List<QueuePlayer> players) {
        // Dividir times simples: primeiros 5 vs últimos 5 (igual Node inicial)
        String team1 = players.subList(0, 5).stream().map(QueuePlayer::getSummonerName).reduce((a, b) -> a + "," + b)
                .orElse("");
        String team2 = players.subList(5, 10).stream().map(QueuePlayer::getSummonerName).reduce((a, b) -> a + "," + b)
                .orElse("");
        CustomMatch cm = CustomMatch.builder()
                .title("AutoMatch " + System.currentTimeMillis())
                .description("Match formed from queue")
                .team1PlayersJson(team1)
                .team2PlayersJson(team2)
                .createdBy("system")
                .status("draft")
                .gameMode("5v5")
                .build();
        customMatchRepository.save(cm);
        // iniciar fluxo de draft em memória com listas de jogadores por time
        List<String> team1List = players.subList(0, 5).stream().map(QueuePlayer::getSummonerName).toList();
        List<String> team2List = players.subList(5, 10).stream().map(QueuePlayer::getSummonerName).toList();
        draftFlowService.startDraft(cm.getId(), team1List, team2List);
        broadcastDraftStarted(cm.getId(), matchTempId, players);
    }

    private void broadcastAcceptanceProgress(long matchTempId, java.util.Map<String, Object> status) {
        try {
            String payload = mapper.writeValueAsString(java.util.Map.of(
                    FIELD_TYPE, "match_acceptance_progress",
                    FIELD_MATCH_TEMP_ID, matchTempId,
                    "progress", status));
            sessionRegistry.all().forEach(ws -> {
                try {
                    ws.sendMessage(new TextMessage(payload));
                } catch (Exception e) {
                    log.warn("Falha enviar acceptance_progress", e);
                }
            });
        } catch (Exception e) {
            log.error("Erro serializando acceptance_progress", e);
        }
    }

    private void broadcastMatchCancelled(long matchTempId, String reason) {
        try {
            String payload = mapper.writeValueAsString(java.util.Map.of(
                    FIELD_TYPE, "match_cancelled",
                    FIELD_MATCH_TEMP_ID, matchTempId,
                    "reason", reason));
            sessionRegistry.all().forEach(ws -> {
                try {
                    ws.sendMessage(new TextMessage(payload));
                } catch (Exception e) {
                    log.warn("Falha enviar match_cancelled", e);
                }
            });
        } catch (Exception e) {
            log.error("Erro serializando match_cancelled", e);
        }
    }

    private void broadcastDraftStarted(long customMatchId, long matchTempId, List<QueuePlayer> players) {
        try {
            String payload = mapper.writeValueAsString(java.util.Map.of(
                    FIELD_TYPE, "draft_started",
                    "matchId", customMatchId,
                    FIELD_MATCH_TEMP_ID, matchTempId,
                    FIELD_PLAYERS, players));
            sessionRegistry.all().forEach(ws -> {
                try {
                    ws.sendMessage(new TextMessage(payload));
                } catch (Exception e) {
                    log.warn("Falha enviar draft_started", e);
                }
            });
        } catch (Exception e) {
            log.error("Erro serializando draft_started", e);
        }
    }

    private void broadcastMatchFound(long matchTempId, List<QueuePlayer> players) {
        try {
            // ✅ Extrair lista de summonerNames dos jogadores da partida
            List<String> playerNames = players.stream()
                    .map(QueuePlayer::getSummonerName)
                    .collect(java.util.stream.Collectors.toList());

            String payload = mapper.writeValueAsString(java.util.Map.of(
                    FIELD_TYPE, "match_found",
                    FIELD_MATCH_TEMP_ID, matchTempId,
                    FIELD_PLAYERS, players));

            // ✅ CORREÇÃO: Enviar apenas para os 10 jogadores da partida
            java.util.Collection<org.springframework.web.socket.WebSocketSession> playerSessions = sessionRegistry
                    .getByPlayers(playerNames);

            log.info("📤 Enviando match_found para {} jogadores (de {} esperados)",
                    playerSessions.size(), playerNames.size());

            playerSessions.forEach(ws -> {
                try {
                    ws.sendMessage(new TextMessage(payload));
                } catch (Exception e) {
                    log.warn("Falha enviar match_found", e);
                }
            });
        } catch (Exception e) {
            log.error("Erro serializando match_found", e);
        }
    }

    public void reemitIfInAcceptance(long queuePlayerId, org.springframework.web.socket.WebSocketSession session) {
        // localizar sessão que contenha o jogador
        acceptanceService.allSessions().stream()
                .filter(s -> s.queuePlayerIds.contains(queuePlayerId))
                .findFirst()
                .ifPresent(s -> {
                    var players = acceptanceService.playersForSession(s.matchTempId);
                    try {
                        String payload = mapper.writeValueAsString(java.util.Map.of(
                                FIELD_TYPE, "match_found",
                                FIELD_MATCH_TEMP_ID, s.matchTempId,
                                FIELD_PLAYERS, players));
                        session.sendMessage(new TextMessage(payload));
                    } catch (Exception e) {
                        log.warn("Falha reemitir match_found para {}", session.getId(), e);
                    }
                });
    }
}
