package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class DraftService {

    private final CustomMatchRepository customMatchRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final DiscordService discordService;
    private final GameInProgressService gameInProgressService;
    private final ObjectMapper objectMapper;
    private final DraftFlowService draftFlowService; // ✅ NOVO: Para delegar changePick

    // Configurações do draft
    private static final int DRAFT_TIMEOUT_SECONDS = 30;
    private static final int MONITORING_INTERVAL_MS = 1000;

    // Cache de drafts ativos
    private final Map<Long, DraftData> activeDrafts = new ConcurrentHashMap<>();
    private final Map<Long, DraftTimer> draftTimers = new ConcurrentHashMap<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DraftData {
        private Long matchId;
        private List<DraftPlayer> team1;
        private List<DraftPlayer> team2;
        private Integer averageMmrTeam1;
        private Integer averageMmrTeam2;
        private LocalDateTime createdAt;
        private Integer currentAction;
        private List<DraftPhase> phases;
        private List<DraftAction> actions;
        private String phase;
        private Map<String, Object> picks = new ConcurrentHashMap<>();
        private Map<String, Boolean> draftConfirmations = new ConcurrentHashMap<>();
    }

    @Data
    @AllArgsConstructor
    public static class DraftPlayer {
        private String summonerName;
        private String assignedLane;
        private Integer teamIndex;
        private Integer mmr;
        private String primaryLane;
        private String secondaryLane;
        private Boolean isAutofill;
        private String puuid;
    }

    @Data
    @AllArgsConstructor
    public static class DraftPhase {
        private String phase; // bans, picks
        private Integer team; // 1 ou 2
        private String action; // ban, pick
        private Integer playerIndex;
        private Integer actionIndex;
        private String playerId;
        private String playerName;
    }

    @Data
    @AllArgsConstructor
    public static class DraftAction {
        private String actionType;
        private String championId;
        private String playerId;
        private String playerName;
        private Integer teamIndex;
        private Integer actionIndex;
        private LocalDateTime timestamp;
    }

    @Data
    @AllArgsConstructor
    public static class DraftTimer {
        private Integer timeRemaining;
        private CompletableFuture<Void> timeoutFuture;
        private Runnable onTimeout;
    }

    /**
     * Inicializa o serviço de draft
     */
    public void initialize() {
        log.info("🎯 Inicializando DraftService...");
        loadActiveDraftsFromDatabase();
        // Iniciar monitoramento em thread separada para não bloquear a inicialização
        CompletableFuture.runAsync(this::startDraftMonitoring);
        log.info("✅ DraftService inicializado com sucesso");
    }

    /**
     * Carrega drafts ativos do banco de dados
     */
    @Transactional
    public void loadActiveDraftsFromDatabase() {
        try {
            List<CustomMatch> activeMatches = customMatchRepository.findByStatus("draft");

            for (CustomMatch match : activeMatches) {
                // Recriar DraftData a partir dos dados do banco
                DraftData draftData = new DraftData();
                draftData.setMatchId(match.getId());
                draftData.setPhase("bans"); // Fase padrão
                draftData.setCurrentAction(0);
                draftData.setCreatedAt(Instant.now().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());

                // Carregar dados do draft do JSON
                if (match.getPickBanDataJson() != null && !match.getPickBanDataJson().isEmpty()) {
                    // Implementar carregamento de dados do draft
                }

                activeDrafts.put(match.getId(), draftData);
                log.info("📥 Draft carregado do banco: {}", match.getId());
            }

        } catch (Exception e) {
            log.error("❌ Erro ao carregar drafts do banco", e);
        }
    }

    /**
     * Confirma sincronização de draft
     */
    public boolean confirmSync(Long matchId, String playerId, Integer actionIndex) {
        DraftData draft = activeDrafts.get(matchId);
        if (draft == null) {
            return false;
        }

        // Marcar confirmação do jogador
        draft.getDraftConfirmations().put(playerId, true);

        // Verificar se todos confirmaram
        boolean allConfirmed = draft.getDraftConfirmations().values().stream()
                .allMatch(Boolean::booleanValue);

        if (allConfirmed) {
            // Avançar para próxima ação
            draft.setCurrentAction(draft.getCurrentAction() + 1);
            // Reiniciar confirmações para próxima ação
            draft.getDraftConfirmations().clear();
        }

        return true;
    }

    /**
     * Obtém status de confirmação do draft
     */
    public Map<String, Boolean> confirmationStatus(Long matchId) {
        DraftData draft = activeDrafts.get(matchId);
        if (draft == null) {
            return new HashMap<>();
        }
        return new HashMap<>(draft.getDraftConfirmations());
    }

    /**
     * ✅ ATUALIZADO: Muda pick de um jogador usando DraftFlowService
     * Delega para o DraftFlowService que gerencia o estado real do draft
     */
    public boolean changePick(Long matchId, String playerId, String championId) {
        log.info("🔄 [DraftService.changePick] Delegando para DraftFlowService");
        log.info("   - matchId: {}", matchId);
        log.info("   - playerId: {}", playerId);
        log.info("   - championId: {}", championId);

        try {
            // ✅ Delegar para DraftFlowService (gerencia o estado real)
            draftFlowService.changePick(matchId, playerId, championId);

            // ✅ Também atualizar cache local (legacy - compatibilidade)
            DraftData draft = activeDrafts.get(matchId);
            if (draft != null) {
                draft.getPicks().put(playerId, championId);
            }

            log.info("✅ [DraftService.changePick] Pick alterado com sucesso");
            return true;
        } catch (Exception e) {
            log.error("❌ [DraftService.changePick] Erro ao alterar pick", e);
            return false;
        }
    }

    /**
     * Inicia um draft para uma partida
     */
    @Transactional
    public boolean startDraft(Long matchId, List<DraftPlayer> team1, List<DraftPlayer> team2,
            Integer averageMmrTeam1, Integer averageMmrTeam2) {
        try {
            log.info("🎯 Iniciando draft para partida: {}", matchId);

            DraftData draftData = new DraftData();
            draftData.setMatchId(matchId);
            draftData.setTeam1(team1);
            draftData.setTeam2(team2);
            draftData.setAverageMmrTeam1(averageMmrTeam1);
            draftData.setAverageMmrTeam2(averageMmrTeam2);
            draftData.setCreatedAt(LocalDateTime.now());
            draftData.setCurrentAction(0);
            draftData.setPhases(generateDraftPhases());
            draftData.setActions(new ArrayList<>());
            draftData.setPhase("bans");

            activeDrafts.put(matchId, draftData);

            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match != null) {
                match.setStatus("draft");
                match.setUpdatedAt(Instant.now());
                customMatchRepository.save(match);
            }

            startDraftTimer(matchId);
            broadcastDraftStarted(matchId, draftData);

            log.info("✅ Draft iniciado para partida: {}", matchId);
            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao iniciar draft", e);
            return false;
        }
    }

    /**
     * Processa uma ação do draft
     */
    @Transactional
    public boolean processDraftAction(Long matchId, String actionType, String championId,
            String playerId, Integer actionIndex) {
        try {
            log.info("🎯 Processando ação do draft: {} (partida: {}, jogador: {})",
                    actionType, matchId, playerId);

            DraftData draftData = activeDrafts.get(matchId);
            if (draftData == null) {
                log.warn("⚠️ Draft não encontrado: {}", matchId);
                return false;
            }

            if (!isPlayerTurn(matchId, playerId, actionIndex)) {
                log.warn("⚠️ Não é a vez do jogador: {} (ação: {})", playerId, actionIndex);
                return false;
            }

            if (!isValidChampion(championId)) {
                log.warn("⚠️ Campeão inválido: {}", championId);
                return false;
            }

            DraftAction action = new DraftAction(
                    actionType, championId, playerId, getPlayerName(playerId, draftData),
                    getPlayerTeam(playerId, draftData), actionIndex, LocalDateTime.now());

            draftData.getActions().add(action);
            draftData.setCurrentAction(draftData.getCurrentAction() + 1);

            if ("pick".equals(actionType)) {
                draftData.getPicks().put(playerId, championId);
            }

            if (isDraftComplete(draftData)) {
                completeDraft(matchId);
            } else {
                nextDraftAction(matchId);
            }

            broadcastDraftUpdate(matchId, draftData);

            log.info("✅ Ação do draft processada: {} (partida: {})", actionType, matchId);
            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao processar ação do draft", e);
            return false;
        }
    }

    /**
     * Confirma draft por um jogador
     */
    public boolean confirmDraft(Long matchId, String playerId) {
        try {
            DraftData draftData = activeDrafts.get(matchId);
            if (draftData == null) {
                return false;
            }

            draftData.getDraftConfirmations().put(playerId, true);

            if (draftData.getDraftConfirmations().size() == 10) {
                completeDraft(matchId);
            }

            broadcastDraftUpdate(matchId, draftData);
            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao confirmar draft", e);
            return false;
        }
    }

    /**
     * Obtém sessão de draft
     */
    public Map<String, Object> getDraftSession(Long matchId) {
        try {
            DraftData draftData = activeDrafts.get(matchId);
            if (draftData == null) {
                return null;
            }

            return Map.of(
                    "matchId", matchId,
                    "team1", draftData.getTeam1(),
                    "team2", draftData.getTeam2(),
                    "currentAction", draftData.getCurrentAction(),
                    "phase", draftData.getPhase(),
                    "picks", draftData.getPicks(),
                    "actions", draftData.getActions(),
                    "confirmations", draftData.getDraftConfirmations(),
                    "timeRemaining", getTimeRemaining(matchId));

        } catch (Exception e) {
            log.error("❌ Erro ao obter sessão de draft", e);
            return null;
        }
    }

    /**
     * Gera fases do draft
     */
    private List<DraftPhase> generateDraftPhases() {
        List<DraftPhase> phases = new ArrayList<>();

        // Fase de bans (3 bans por time)
        for (int i = 0; i < 3; i++) {
            phases.add(new DraftPhase("bans", 1, "ban", i, i, null, null));
            phases.add(new DraftPhase("bans", 2, "ban", i, i + 3, null, null));
        }

        // Fase de picks (5 picks por time)
        for (int i = 0; i < 5; i++) {
            phases.add(new DraftPhase("picks", 1, "pick", i, i + 6, null, null));
            phases.add(new DraftPhase("picks", 2, "pick", i, i + 11, null, null));
        }

        return phases;
    }

    private boolean isPlayerTurn(Long matchId, String playerId, Integer actionIndex) {
        DraftData draftData = activeDrafts.get(matchId);
        return draftData != null && draftData.getCurrentAction().equals(actionIndex);
    }

    private boolean isValidChampion(String championId) {
        return championId != null && !championId.trim().isEmpty();
    }

    private String getPlayerName(String playerId, DraftData draftData) {
        return playerId; // TODO: Implementar busca do nome do jogador
    }

    private Integer getPlayerTeam(String playerId, DraftData draftData) {
        return 1; // TODO: Implementar busca do time do jogador
    }

    private boolean isDraftComplete(DraftData draftData) {
        return draftData.getCurrentAction() >= draftData.getPhases().size();
    }

    @Transactional
    private void completeDraft(Long matchId) {
        try {
            log.info("🏆 Completando draft para partida: {}", matchId);

            DraftData draftData = activeDrafts.get(matchId);
            if (draftData == null) {
                return;
            }

            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match != null) {
                match.setStatus("draft_completed");
                match.setUpdatedAt(Instant.now());

                try {
                    String draftDataJson = objectMapper.writeValueAsString(draftData);
                    match.setPickBanDataJson(draftDataJson);
                } catch (Exception e) {
                    log.warn("⚠️ Erro ao salvar dados do draft", e);
                }

                customMatchRepository.save(match);
            }

            activeDrafts.remove(matchId);
            draftTimers.remove(matchId);

            gameInProgressService.startGame(matchId, Map.of(
                    "team1", draftData.getTeam1(),
                    "team2", draftData.getTeam2(),
                    "draftData", draftData));

            broadcastDraftCompleted(matchId);

            log.info("✅ Draft completado para partida: {}", matchId);

        } catch (Exception e) {
            log.error("❌ Erro ao completar draft", e);
        }
    }

    private void nextDraftAction(Long matchId) {
        DraftData draftData = activeDrafts.get(matchId);
        if (draftData == null) {
            return;
        }

        if (draftData.getCurrentAction() < draftData.getPhases().size()) {
            DraftPhase currentPhase = draftData.getPhases().get(draftData.getCurrentAction());
            draftData.setPhase(currentPhase.getPhase());
        }

        startDraftTimer(matchId);
    }

    private void startDraftTimer(Long matchId) {
        try {
            DraftTimer existingTimer = draftTimers.get(matchId);
            if (existingTimer != null && existingTimer.getTimeoutFuture() != null) {
                existingTimer.getTimeoutFuture().cancel(true);
            }

            DraftTimer timer = new DraftTimer(
                    DRAFT_TIMEOUT_SECONDS,
                    CompletableFuture.runAsync(() -> {
                        try {
                            Thread.sleep(DRAFT_TIMEOUT_SECONDS * 1000);

                            if (activeDrafts.containsKey(matchId)) {
                                log.warn("⏰ Timeout do draft para partida: {}", matchId);
                                timeoutDraft(matchId);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }),
                    () -> timeoutDraft(matchId));

            draftTimers.put(matchId, timer);

        } catch (Exception e) {
            log.error("❌ Erro ao iniciar timer do draft", e);
        }
    }

    private void timeoutDraft(Long matchId) {
        try {
            log.warn("⏰ Timeout do draft para partida: {}", matchId);

            activeDrafts.remove(matchId);
            draftTimers.remove(matchId);

            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match != null) {
                match.setStatus("cancelled");
                match.setUpdatedAt(Instant.now());
                customMatchRepository.save(match);
            }

            webSocketService.broadcastMatchCancelled(matchId.toString(), "Draft timeout");

        } catch (Exception e) {
            log.error("❌ Erro ao processar timeout do draft", e);
        }
    }

    private Integer getTimeRemaining(Long matchId) {
        DraftTimer timer = draftTimers.get(matchId);
        return timer != null ? timer.getTimeRemaining() : 0;
    }

    @Async
    public void startDraftMonitoring() {
        while (true) {
            try {
                Thread.sleep(MONITORING_INTERVAL_MS);
                updateTimers();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("❌ Erro no monitoramento de drafts", e);
            }
        }
    }

    /**
     * Persiste dados do draft no banco
     */
    @Transactional
    public void persistDraftData(Long matchId) {
        try {
            DraftData draftData = activeDrafts.get(matchId);
            if (draftData == null)
                return;

            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match == null)
                return;

            // Converter dados do draft para JSON
            Map<String, Object> draftJson = new HashMap<>();
            draftJson.put("phase", draftData.getPhase());
            draftJson.put("currentAction", draftData.getCurrentAction());
            draftJson.put("picks", draftData.getPicks());
            draftJson.put("confirmations", draftData.getDraftConfirmations());
            draftJson.put("phases", draftData.getPhases());
            draftJson.put("actions", draftData.getActions());

            String jsonData = objectMapper.writeValueAsString(draftJson);
            match.setPickBanDataJson(jsonData);
            match.setUpdatedAt(Instant.now());

            customMatchRepository.save(match);

            log.debug("💾 Draft persistido: {}", matchId);

        } catch (Exception e) {
            log.error("❌ Erro ao persistir draft", e);
        }
    }

    /**
     * Avança para próxima fase do draft
     */
    @Transactional
    public void advanceToNextPhase(Long matchId) {
        try {
            DraftData draftData = activeDrafts.get(matchId);
            if (draftData == null)
                return;

            List<DraftPhase> phases = draftData.getPhases();
            int currentAction = draftData.getCurrentAction();

            if (currentAction < phases.size() - 1) {
                draftData.setCurrentAction(currentAction + 1);
                DraftPhase nextPhase = phases.get(currentAction + 1);
                draftData.setPhase(nextPhase.getPhase());

                // Reiniciar confirmações
                draftData.getDraftConfirmations().clear();

                // Persistir mudanças
                persistDraftData(matchId);

                // Broadcast da mudança
                Map<String, Object> data = new HashMap<>();
                data.put("matchId", matchId);
                data.put("phase", nextPhase.getPhase());
                data.put("action", currentAction + 1);
                webSocketService.broadcastToAll("draft_phase_changed", data);

                log.info("🎯 Draft {} avançou para fase: {}", matchId, nextPhase.getPhase());
            }

        } catch (Exception e) {
            log.error("❌ Erro ao avançar fase do draft", e);
        }
    }

    /**
     * Finaliza o draft
     */
    @Transactional
    public void finishDraft(Long matchId) {
        try {
            DraftData draftData = activeDrafts.remove(matchId);
            if (draftData == null)
                return;

            // Remover timer
            DraftTimer timer = draftTimers.remove(matchId);
            if (timer != null && timer.getTimeoutFuture() != null) {
                timer.getTimeoutFuture().cancel(true);
            }

            // Atualizar status da partida
            CustomMatch match = customMatchRepository.findById(matchId).orElse(null);
            if (match != null) {
                match.setStatus("ready_to_start");
                match.setUpdatedAt(Instant.now());
                customMatchRepository.save(match);
            }

            // Persistir dados finais
            persistDraftData(matchId);

            // Broadcast da finalização
            Map<String, Object> data = new HashMap<>();
            data.put("matchId", matchId);
            data.put("picks", draftData.getPicks());
            webSocketService.broadcastToAll("draft_finished", data);

            log.info("🏁 Draft finalizado: {}", matchId);

        } catch (Exception e) {
            log.error("❌ Erro ao finalizar draft", e);
        }
    }

    /**
     * Obtém dados do draft
     */
    public DraftData getDraftData(Long matchId) {
        return activeDrafts.get(matchId);
    }

    /**
     * Obtém todos os drafts ativos
     */
    public Map<Long, DraftData> getActiveDrafts() {
        return new HashMap<>(activeDrafts);
    }

    private void updateTimers() {
        try {
            draftTimers.entrySet().removeIf(entry -> {
                DraftTimer timer = entry.getValue();
                return timer.getTimeoutFuture().isDone();
            });
        } catch (Exception e) {
            log.error("❌ Erro ao atualizar timers", e);
        }
    }

    private void broadcastDraftStarted(Long matchId, DraftData draftData) {
        try {
            Map<String, Object> data = Map.of(
                    "matchId", matchId,
                    "team1", draftData.getTeam1(),
                    "team2", draftData.getTeam2(),
                    "phase", draftData.getPhase(),
                    "currentAction", draftData.getCurrentAction());

            webSocketService.broadcastToAll("draft_started", data);
        } catch (Exception e) {
            log.error("❌ Erro ao notificar início do draft", e);
        }
    }

    private void broadcastDraftUpdate(Long matchId, DraftData draftData) {
        try {
            Map<String, Object> data = Map.of(
                    "matchId", matchId,
                    "currentAction", draftData.getCurrentAction(),
                    "phase", draftData.getPhase(),
                    "picks", draftData.getPicks(),
                    "actions", draftData.getActions(),
                    "timeRemaining", getTimeRemaining(matchId));

            webSocketService.broadcastToAll("draft_update", data);
        } catch (Exception e) {
            log.error("❌ Erro ao notificar atualização do draft", e);
        }
    }

    private void broadcastDraftCompleted(Long matchId) {
        try {
            Map<String, Object> data = Map.of(
                    "matchId", matchId,
                    "status", "completed");

            webSocketService.broadcastToAll("draft_completed", data);
        } catch (Exception e) {
            log.error("❌ Erro ao notificar conclusão do draft", e);
        }
    }

    public int getActiveDraftsCount() {
        return activeDrafts.size();
    }

    public void clearActiveDrafts() {
        try {
            for (Long matchId : new ArrayList<>(activeDrafts.keySet())) {
                timeoutDraft(matchId);
            }
            log.info("🧹 Drafts ativos limpos");
        } catch (Exception e) {
            log.error("❌ Erro ao limpar drafts ativos", e);
        }
    }
}