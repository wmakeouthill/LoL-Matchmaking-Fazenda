package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import br.com.lolmatchmaking.backend.websocket.SessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.TextMessage;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class DraftFlowService {
    private final CustomMatchRepository customMatchRepository;
    private final SessionRegistry sessionRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.draft.action-timeout-ms:30000}")
    private long configuredActionTimeoutMs;

    public record DraftAction(int index, String type, int team, String championId, String byPlayer) {
    }

    public static class DraftState {
        private final long matchId;
        private final List<DraftAction> actions;
        private int currentIndex;
        private final Set<String> confirmations = new HashSet<>();
        private final Set<String> team1Players;
        private final Set<String> team2Players;
        private long lastActionStartMs;

        public DraftState(long matchId, List<DraftAction> actions, Collection<String> team1, Collection<String> team2) {
            this.matchId = matchId;
            this.actions = actions;
            this.currentIndex = 0;
            this.team1Players = team1 == null ? Set.of() : new HashSet<>(team1);
            this.team2Players = team2 == null ? Set.of() : new HashSet<>(team2);
            this.lastActionStartMs = System.currentTimeMillis();
        }

        public long getMatchId() {
            return matchId;
        }

        public List<DraftAction> getActions() {
            return actions;
        }

        public int getCurrentIndex() {
            return currentIndex;
        }

        public Set<String> getConfirmations() {
            return confirmations;
        }

        public void advance() {
            currentIndex++;
        }

        public void markActionStart() {
            this.lastActionStartMs = System.currentTimeMillis();
        }

        public long getLastActionStartMs() {
            return lastActionStartMs;
        }

        public boolean isPlayerInTeam(String player, int team) {
            if (team == 1)
                return team1Players.contains(player);
            if (team == 2)
                return team2Players.contains(player);
            return false;
        }

        public Set<String> getTeam1Players() {
            return team1Players;
        }

        public Set<String> getTeam2Players() {
            return team2Players;
        }
    }

    private final Map<Long, DraftState> states = new ConcurrentHashMap<>();

    @PostConstruct
    public void restoreDraftStates() {
        log.info("🎯 Inicializando DraftFlowService...");

        // Fazer a restauração de forma assíncrona para não bloquear a inicialização
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(3000); // Aguarda 3s para garantir que tudo está carregado
                restoreDraftStatesAsync();
                log.info("✅ DraftFlowService - Estados de draft restaurados");
            } catch (Exception e) {
                log.error("❌ Erro ao restaurar estados de draft", e);
            }
        });

        log.info("✅ DraftFlowService inicializado com sucesso");
    }

    private void restoreDraftStatesAsync() {
        // Carrega drafts com status 'draft' e que possuem pickBanDataJson
        customMatchRepository.findAll().stream()
                .filter(cm -> "draft".equalsIgnoreCase(cm.getStatus()) && cm.getPickBanDataJson() != null
                        && !cm.getPickBanDataJson().isBlank())
                .forEach(cm -> {
                    try {
                        Map<?, ?> snap = mapper.readValue(cm.getPickBanDataJson(), Map.class);
                        Object actionsObj = snap.get(KEY_ACTIONS);
                        Object currentIdxObj = snap.get(KEY_CURRENT_INDEX);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> actionMaps = actionsObj instanceof List<?> l
                                ? (List<Map<String, Object>>) (List<?>) l
                                : List.of();
                        List<DraftAction> actions = new ArrayList<>();
                        for (Map<String, Object> am : actionMaps) {
                            int idx = ((Number) am.getOrDefault("index", 0)).intValue();
                            String type = (String) am.getOrDefault("type", "pick");
                            int team = ((Number) am.getOrDefault("team", 1)).intValue();
                            String championId = (String) am.get("championId");
                            String byPlayer = (String) am.get("byPlayer");
                            actions.add(new DraftAction(idx, type, team, championId, byPlayer));
                        }
                        List<String> team1 = parseCSV(cm.getTeam1PlayersJson());
                        List<String> team2 = parseCSV(cm.getTeam2PlayersJson());
                        DraftState st = new DraftState(cm.getId(), actions, team1, team2);
                        int cur = currentIdxObj instanceof Number n ? n.intValue() : 0;
                        while (st.getCurrentIndex() < cur && st.getCurrentIndex() < actions.size()) {
                            st.advance();
                        }
                        states.put(cm.getId(), st);
                        log.info("Draft restaurado matchId={} actions={} currentIndex={}", cm.getId(), actions.size(),
                                st.getCurrentIndex());
                    } catch (Exception e) {
                        log.warn("Falha restaurando draft matchId={}", cm.getId(), e);
                    }
                });
    }

    private List<String> parseCSV(String csv) {
        if (csv == null || csv.isBlank())
            return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public DraftState startDraft(long matchId, List<String> team1Players, List<String> team2Players) {
        List<DraftAction> actions = buildDefaultActionSequence();
        DraftState st = new DraftState(matchId, actions, team1Players, team2Players);
        states.put(matchId, st);
        persist(matchId, st);
        broadcastUpdate(st, false);
        return st;
    }

    private List<DraftAction> buildDefaultActionSequence() {
        List<DraftAction> list = new ArrayList<>();
        int i = 0;
        int[] banTeams = { 1, 2, 1, 2, 1, 2, 1, 2, 1, 2 };
        for (int t : banTeams)
            list.add(new DraftAction(i++, "ban", t, null, null));
        int[] pickTeams = { 1, 2, 2, 1, 1, 2, 2, 1, 1, 2 };
        for (int t : pickTeams)
            list.add(new DraftAction(i++, "pick", t, null, null));
        return list;
    }

    @Transactional
    public synchronized boolean processAction(long matchId, int actionIndex, String championId, String byPlayer) {
        DraftState st = states.get(matchId);
        if (st == null) {
            return false;
        }
        if (st.getCurrentIndex() >= st.getActions().size()) { // draft já completo
            return false;
        }
        if (actionIndex != st.currentIndex) {
            return false;
        }
        DraftAction prev = st.actions.get(actionIndex);
        // valida se jogador pertence ao time da ação
        if (!st.isPlayerInTeam(byPlayer, prev.team())) {
            return false;
        }
        boolean alreadyUsed = st.actions.stream()
                .filter(a -> a.championId() != null && !SKIPPED.equalsIgnoreCase(a.championId()))
                .anyMatch(a -> championId.equalsIgnoreCase(a.championId()));
        if (alreadyUsed) {
            return false;
        }
        DraftAction updated = new DraftAction(prev.index(), prev.type(), prev.team(), championId, byPlayer);
        st.getActions().set(actionIndex, updated);
        st.advance();
        st.markActionStart();
        persist(matchId, st);
        broadcastUpdate(st, false);
        if (st.getCurrentIndex() >= st.getActions().size()) {
            broadcastDraftCompleted(st);
        }
        return true;
    }

    @Transactional
    public synchronized void confirmDraft(long matchId, String playerId) {
        DraftState st = states.get(matchId);
        if (st == null)
            return;
        st.getConfirmations().add(playerId);
        broadcastUpdate(st, true);
        if (st.getConfirmations().size() >= 10) {
            customMatchRepository.findById(matchId).ifPresent(cm -> {
                cm.setStatus("draft_completed");
                customMatchRepository.save(cm);
            });
            broadcastAllConfirmed(st);
        }
    }

    public Optional<DraftState> getState(long matchId) {
        return Optional.ofNullable(states.get(matchId));
    }

    private void persist(long matchId, DraftState st) {
        try {
            customMatchRepository.findById(matchId).ifPresent(cm -> {
                try {
                    Map<String, Object> snapshot;

                    if (cm.getPickBanDataJson() != null && !cm.getPickBanDataJson().isEmpty()) {
                        // ✅ Carregar dados existentes
                        @SuppressWarnings("unchecked")
                        Map<String, Object> existing = mapper.readValue(cm.getPickBanDataJson(), Map.class);
                        snapshot = new HashMap<>(existing);

                        // ✅ CRÍTICO: Atualizar apenas campos de estado do draft
                        // NUNCA sobrescrever team1/team2 - eles já vêm completos do MatchFoundService
                        snapshot.put(KEY_ACTIONS, st.getActions());
                        snapshot.put(KEY_CURRENT_INDEX, st.getCurrentIndex());
                        snapshot.put(KEY_CONFIRMATIONS, st.getConfirmations());

                        // ✅ Verificar se team1/team2 existem (com dados completos do MatchFoundService)
                        Object team1Data = snapshot.get(KEY_TEAM1);
                        Object team2Data = snapshot.get(KEY_TEAM2);

                        // ✅ Se não existem OU são arrays vazios, adicionar apenas nomes (fallback)
                        boolean team1Empty = team1Data == null ||
                                (team1Data instanceof java.util.List && ((java.util.List<?>) team1Data).isEmpty());
                        boolean team2Empty = team2Data == null ||
                                (team2Data instanceof java.util.List && ((java.util.List<?>) team2Data).isEmpty());

                        if (team1Empty) {
                            log.warn("⚠️ [DraftFlow] team1 vazio, adicionando nomes (fallback): {}", st.getTeam1Players());
                            // ✅ Criar objetos básicos com apenas nomes como fallback
                            List<Map<String, Object>> team1Fallback = new ArrayList<>();
                            int idx = 0;
                            for (String playerName : st.getTeam1Players()) {
                                Map<String, Object> playerObj = new HashMap<>();
                                playerObj.put("summonerName", playerName);
                                playerObj.put("teamIndex", idx++);
                                team1Fallback.add(playerObj);
                            }
                            snapshot.put(KEY_TEAM1, team1Fallback);
                        } else {
                            log.debug("✅ [DraftFlow] team1 já existe com {} jogadores, PRESERVANDO",
                                    ((java.util.List<?>) team1Data).size());
                            // ✅ NÃO SOBRESCREVER - manter dados completos existentes
                        }

                        if (team2Empty) {
                            log.warn("⚠️ [DraftFlow] team2 vazio, adicionando nomes (fallback): {}", st.getTeam2Players());
                            // ✅ Criar objetos básicos com apenas nomes como fallback
                            List<Map<String, Object>> team2Fallback = new ArrayList<>();
                            int idx = 5; // Team 2 começa no índice 5
                            for (String playerName : st.getTeam2Players()) {
                                Map<String, Object> playerObj = new HashMap<>();
                                playerObj.put("summonerName", playerName);
                                playerObj.put("teamIndex", idx++);
                                team2Fallback.add(playerObj);
                            }
                            snapshot.put(KEY_TEAM2, team2Fallback);
                        } else {
                            log.debug("✅ [DraftFlow] team2 já existe com {} jogadores, PRESERVANDO",
                                    ((java.util.List<?>) team2Data).size());
                            // ✅ NÃO SOBRESCREVER - manter dados completos existentes
                        }
                    } else {
                        // ✅ Primeira persistência - criar estrutura básica
                        // (Normalmente não deveria acontecer pois MatchFoundService salva primeiro)
                        snapshot = new HashMap<>();
                        snapshot.put(KEY_ACTIONS, st.getActions());
                        snapshot.put(KEY_CURRENT_INDEX, st.getCurrentIndex());
                        snapshot.put(KEY_CONFIRMATIONS, st.getConfirmations());

                        log.warn("⚠️ [DraftFlow] Criando pick_ban_data pela primeira vez (SEM dados de times!)");
                        // ✅ Criar estrutura mínima
                        List<Map<String, Object>> team1Fallback = new ArrayList<>();
                        int idx = 0;
                        for (String playerName : st.getTeam1Players()) {
                            Map<String, Object> playerObj = new HashMap<>();
                            playerObj.put("summonerName", playerName);
                            playerObj.put("teamIndex", idx++);
                            team1Fallback.add(playerObj);
                        }
                        
                        List<Map<String, Object>> team2Fallback = new ArrayList<>();
                        idx = 5;
                        for (String playerName : st.getTeam2Players()) {
                            Map<String, Object> playerObj = new HashMap<>();
                            playerObj.put("summonerName", playerName);
                            playerObj.put("teamIndex", idx++);
                            team2Fallback.add(playerObj);
                        }
                        
                        snapshot.put(KEY_TEAM1, team1Fallback);
                        snapshot.put(KEY_TEAM2, team2Fallback);
                    }

                    cm.setPickBanDataJson(mapper.writeValueAsString(snapshot));
                    customMatchRepository.save(cm);

                    log.debug("✅ [DraftFlow] Draft state persistido para match {}", matchId);
                } catch (Exception e) {
                    log.error("❌ [DraftFlow] Falha ao serializar pickBanData", e);
                }
            });
        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao persistir snapshot draft", e);
        }
    }

    private static final String KEY_TYPE = "type";
    private static final String KEY_MATCH_ID = "matchId";
    private static final String KEY_CURRENT_INDEX = "currentIndex";
    private static final String KEY_ACTIONS = "actions";
    private static final String KEY_CONFIRMATIONS = "confirmations";
    // ✅ Usar "team1" e "team2" (como no backend antigo), não "team1Players"/"team2Players"
    private static final String KEY_TEAM1 = "team1";
    private static final String KEY_TEAM2 = "team2";
    // configurable via property above
    private static final String SKIPPED = "SKIPPED";
    private static final String TIMEOUT_PLAYER = "system_timeout";
    private static final String KEY_REMAINING_MS = "remainingMs";
    private static final String KEY_ACTION_TIMEOUT_MS = "actionTimeoutMs";

    private void broadcastUpdate(DraftState st, boolean confirmationOnly) {
        try {
            long remainingMs = calcRemainingMs(st);

            // ✅ CRÍTICO: Carregar dados completos dos times do banco, não apenas nomes
            Map<String, Object> updateData = new HashMap<>();
            updateData.put(KEY_TYPE, "draft_updated");
            updateData.put(KEY_MATCH_ID, st.getMatchId());
            updateData.put(KEY_CURRENT_INDEX, st.getCurrentIndex());
            updateData.put(KEY_ACTIONS, st.getActions());
            updateData.put(KEY_CONFIRMATIONS, st.getConfirmations());

            // ✅ Buscar dados completos dos times do banco
            customMatchRepository.findById(st.getMatchId()).ifPresent(cm -> {
                if (cm.getPickBanDataJson() != null && !cm.getPickBanDataJson().isEmpty()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pickBanData = mapper.readValue(cm.getPickBanDataJson(), Map.class);

                        // ✅ Buscar dados completos usando KEY_TEAM1 e KEY_TEAM2 ("team1" e "team2")
                        Object team1Data = pickBanData.get(KEY_TEAM1);
                        Object team2Data = pickBanData.get(KEY_TEAM2);

                        if (team1Data != null && team1Data instanceof java.util.List
                                && !((java.util.List<?>) team1Data).isEmpty()) {
                            updateData.put(KEY_TEAM1, team1Data);
                            log.debug("✅ [DraftFlow] Broadcast com team1 completo ({} jogadores)",
                                    ((java.util.List<?>) team1Data).size());
                        } else {
                            log.warn("⚠️ [DraftFlow] Broadcast com team1 apenas nomes (fallback)");
                            // Fallback: criar objetos básicos
                            List<Map<String, Object>> team1Fallback = new ArrayList<>();
                            int idx = 0;
                            for (String playerName : st.getTeam1Players()) {
                                Map<String, Object> playerObj = new HashMap<>();
                                playerObj.put("summonerName", playerName);
                                playerObj.put("teamIndex", idx++);
                                team1Fallback.add(playerObj);
                            }
                            updateData.put(KEY_TEAM1, team1Fallback);
                        }

                        if (team2Data != null && team2Data instanceof java.util.List
                                && !((java.util.List<?>) team2Data).isEmpty()) {
                            updateData.put(KEY_TEAM2, team2Data);
                            log.debug("✅ [DraftFlow] Broadcast com team2 completo ({} jogadores)",
                                    ((java.util.List<?>) team2Data).size());
                        } else {
                            log.warn("⚠️ [DraftFlow] Broadcast com team2 apenas nomes (fallback)");
                            // Fallback: criar objetos básicos
                            List<Map<String, Object>> team2Fallback = new ArrayList<>();
                            int idx = 5;
                            for (String playerName : st.getTeam2Players()) {
                                Map<String, Object> playerObj = new HashMap<>();
                                playerObj.put("summonerName", playerName);
                                playerObj.put("teamIndex", idx++);
                                team2Fallback.add(playerObj);
                            }
                            updateData.put(KEY_TEAM2, team2Fallback);
                        }
                    } catch (Exception e) {
                        log.error("❌ [DraftFlow] Erro ao ler pick_ban_data para broadcast", e);
                        // Fallback: criar objetos básicos
                        List<Map<String, Object>> team1Fallback = new ArrayList<>();
                        int idx = 0;
                        for (String playerName : st.getTeam1Players()) {
                            Map<String, Object> playerObj = new HashMap<>();
                            playerObj.put("summonerName", playerName);
                            playerObj.put("teamIndex", idx++);
                            team1Fallback.add(playerObj);
                        }
                        updateData.put(KEY_TEAM1, team1Fallback);
                        
                        List<Map<String, Object>> team2Fallback = new ArrayList<>();
                        idx = 5;
                        for (String playerName : st.getTeam2Players()) {
                            Map<String, Object> playerObj = new HashMap<>();
                            playerObj.put("summonerName", playerName);
                            playerObj.put("teamIndex", idx++);
                            team2Fallback.add(playerObj);
                        }
                        updateData.put(KEY_TEAM2, team2Fallback);
                    }
                } else {
                    log.warn("⚠️ [DraftFlow] Sem pick_ban_data no banco, usando fallback");
                    // Sem dados no banco, criar objetos básicos
                    List<Map<String, Object>> team1Fallback = new ArrayList<>();
                    int idx = 0;
                    for (String playerName : st.getTeam1Players()) {
                        Map<String, Object> playerObj = new HashMap<>();
                        playerObj.put("summonerName", playerName);
                        playerObj.put("teamIndex", idx++);
                        team1Fallback.add(playerObj);
                    }
                    updateData.put(KEY_TEAM1, team1Fallback);
                    
                    List<Map<String, Object>> team2Fallback = new ArrayList<>();
                    idx = 5;
                    for (String playerName : st.getTeam2Players()) {
                        Map<String, Object> playerObj = new HashMap<>();
                        playerObj.put("summonerName", playerName);
                        playerObj.put("teamIndex", idx++);
                        team2Fallback.add(playerObj);
                    }
                    updateData.put(KEY_TEAM2, team2Fallback);
                }
            });

            updateData.put("timeRemainingMs", remainingMs);
            updateData.put(KEY_REMAINING_MS, remainingMs);
            updateData.put(KEY_ACTION_TIMEOUT_MS, getActionTimeoutMs());
            updateData.put("confirmationOnly", confirmationOnly);

            String payload = mapper.writeValueAsString(updateData);

            sessionRegistry.all().forEach(ws -> {
                try {
                    ws.sendMessage(new TextMessage(payload));
                } catch (Exception ignored) {
                    /* ignore individual send errors */ }
            });
        } catch (Exception e) {
            log.error("Erro broadcast draft_updated", e);
        }
    }

    private void broadcastDraftCompleted(DraftState st) {
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    KEY_TYPE, "draft_completed",
                    KEY_MATCH_ID, st.getMatchId()));
            sessionRegistry.all().forEach(ws -> {
                try {
                    ws.sendMessage(new TextMessage(payload));
                } catch (Exception ignored) {
                    /* ignore */ }
            });
        } catch (Exception e) {
            log.error("Erro broadcast draft_completed", e);
        }
    }

    private void broadcastAllConfirmed(DraftState st) {
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    KEY_TYPE, "draft_confirmed",
                    KEY_MATCH_ID, st.getMatchId()));
            sessionRegistry.all().forEach(ws -> {
                try {
                    ws.sendMessage(new TextMessage(payload));
                } catch (Exception ignored) {
                    /* ignore */ }
            });
        } catch (Exception e) {
            log.error("Erro broadcast draft_confirmed", e);
        }
        // Transição para fase game_ready
        customMatchRepository.findById(st.getMatchId()).ifPresent(cm -> {
            try {
                cm.setStatus("game_ready");
                customMatchRepository.save(cm);
            } catch (Exception ex) {
                log.warn("Falha atualizar status game_ready matchId={} ", st.getMatchId(), ex);
            }
        });
        broadcastGameReady(st);
    }

    public Map<String, Object> snapshot(long matchId) {
        DraftState st = states.get(matchId);
        if (st == null)
            return Map.of("exists", false);
        long remainingMs = calcRemainingMs(st);
        return Map.of(
                "exists", true,
                KEY_ACTIONS, st.getActions(),
                KEY_CURRENT_INDEX, st.getCurrentIndex(),
                KEY_CONFIRMATIONS, st.getConfirmations(),
                KEY_TEAM1, st.getTeam1Players(),
                KEY_TEAM2, st.getTeam2Players(),
                KEY_REMAINING_MS, remainingMs,
                KEY_ACTION_TIMEOUT_MS, getActionTimeoutMs());
    }

    public void reemitIfPlayerInDraft(String playerName, org.springframework.web.socket.WebSocketSession session) {
        states.values().stream()
                .filter(st -> st.getTeam1Players().contains(playerName) || st.getTeam2Players().contains(playerName))
                .findFirst()
                .ifPresent(st -> {
                    try {
                        long remainingMs = calcRemainingMs(st);
                        String payload = mapper.writeValueAsString(Map.of(
                                KEY_TYPE, "draft_snapshot",
                                KEY_MATCH_ID, st.getMatchId(),
                                KEY_CURRENT_INDEX, st.getCurrentIndex(),
                                KEY_ACTIONS, st.getActions(),
                                KEY_CONFIRMATIONS, st.getConfirmations(),
                                KEY_TEAM1, st.getTeam1Players(),
                                KEY_TEAM2, st.getTeam2Players(),
                                KEY_REMAINING_MS, remainingMs,
                                KEY_ACTION_TIMEOUT_MS, getActionTimeoutMs(),
                                "success", true));
                        session.sendMessage(new TextMessage(payload));
                    } catch (Exception e) {
                        log.warn("Falha reemitir draft_snapshot", e);
                    }
                });
    }

    private void broadcastGameReady(DraftState st) {
        try {
            Map<String, Object> team1Data = buildTeamData(st, 1);
            Map<String, Object> team2Data = buildTeamData(st, 2);
            String payload = mapper.writeValueAsString(Map.of(
                    KEY_TYPE, "match_game_ready",
                    KEY_MATCH_ID, st.getMatchId(),
                    "team1", team1Data,
                    "team2", team2Data));
            sessionRegistry.all().forEach(ws -> {
                try {
                    ws.sendMessage(new TextMessage(payload));
                } catch (Exception ignored) {
                    // ignorar falha individual de envio
                }
            });
        } catch (Exception e) {
            log.error("Erro broadcast match_game_ready", e);
        }
    }

    private Map<String, Object> buildTeamData(DraftState st, int team) {
        List<String> players = team == 1 ? new ArrayList<>(st.getTeam1Players())
                : new ArrayList<>(st.getTeam2Players());
        List<Map<String, String>> picks = st.getActions().stream()
                .filter(a -> "pick".equals(a.type()) && a.team() == team && a.championId() != null
                        && !SKIPPED.equals(a.championId()))
                .map(a -> Map.of(
                        "player", a.byPlayer() == null ? "" : a.byPlayer(),
                        "championId", a.championId()))
                .toList();
        return Map.of(
                "players", players,
                "picks", picks);
    }

    public void reemitIfPlayerGameReady(String playerName, org.springframework.web.socket.WebSocketSession session) {
        // verifica drafts confirmados em memória com status game_ready no banco
        states.values().stream()
                .filter(st -> customMatchRepository.findById(st.getMatchId())
                        .map(cm -> "game_ready".equalsIgnoreCase(cm.getStatus()))
                        .orElse(false))
                .filter(st -> st.getTeam1Players().contains(playerName) || st.getTeam2Players().contains(playerName))
                .findFirst()
                .ifPresent(st -> {
                    // envia apenas para a sessão solicitante
                    try {
                        Map<String, Object> team1Data = buildTeamData(st, 1);
                        Map<String, Object> team2Data = buildTeamData(st, 2);
                        String payload = mapper.writeValueAsString(Map.of(
                                KEY_TYPE, "match_game_ready",
                                KEY_MATCH_ID, st.getMatchId(),
                                "team1", team1Data,
                                "team2", team2Data));
                        session.sendMessage(new TextMessage(payload));
                    } catch (Exception e) {
                        log.warn("Falha reemitir match_game_ready", e);
                    }
                });
    }

    @Scheduled(fixedDelay = 1000)
    public void monitorActionTimeouts() {
        long now = System.currentTimeMillis();
        states.values().forEach(st -> {
            if (st.getCurrentIndex() >= st.getActions().size())
                return; // completo
            if (now - st.getLastActionStartMs() >= getActionTimeoutMs()) {
                int idx = st.getCurrentIndex();
                DraftAction prev = st.getActions().get(idx);
                DraftAction skipped = new DraftAction(prev.index(), prev.type(), prev.team(), SKIPPED, TIMEOUT_PLAYER);
                st.getActions().set(idx, skipped);
                st.advance();
                st.markActionStart();
                persist(st.getMatchId(), st);
                broadcastUpdate(st, false);
                if (st.getCurrentIndex() >= st.getActions().size()) {
                    broadcastDraftCompleted(st);
                }
            }
        });
    }

    private long getActionTimeoutMs() {
        return configuredActionTimeoutMs <= 0 ? 30000 : configuredActionTimeoutMs;
    }

    private long calcRemainingMs(DraftState st) {
        if (st.getCurrentIndex() >= st.getActions().size())
            return 0;
        long elapsed = System.currentTimeMillis() - st.getLastActionStartMs();
        long remaining = getActionTimeoutMs() - elapsed;
        return Math.max(0, remaining);
    }
}
