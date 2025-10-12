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
    private final DataDragonService dataDragonService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GameInProgressService gameInProgressService;
    private final DiscordService discordService;

    // ✅ NOVO: Redis para performance e resiliência
    private final RedisDraftFlowService redisDraftFlow;

    // ✅ NOVO: PlayerStateService para cleanup inteligente
    private final br.com.lolmatchmaking.backend.service.lock.PlayerStateService playerStateService;

    // ✅ NOVO: RedisPlayerMatchService para cleanup de ownership
    private final br.com.lolmatchmaking.backend.service.redis.RedisPlayerMatchService redisPlayerMatch;

    @Value("${app.draft.action-timeout-ms:30000}")
    private long configuredActionTimeoutMs;

    // ✅ REMOVIDO: finalConfirmations e matchTimers - Redis é fonte única da verdade
    // Use redisDraftFlow.confirmFinalDraft() e redisDraftFlow.getTimer() ao invés

    private Thread timerThread;
    private volatile boolean timerRunning = false;

    public record DraftAction(
            int index,
            String type,
            int team,
            String championId,
            String championName, // ✅ CORREÇÃO: Adicionar nome do campeão para o frontend
            String byPlayer) {
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
            // ✅ CORREÇÃO CRÍTICA: Usar LinkedHashSet para MANTER A ORDEM dos jogadores
            // HashSet não garante ordem, então a posição dos jogadores ficava aleatória
            // LinkedHashSet mantém a ordem de inserção (Top, Jungle, Mid, ADC, Support)
            this.team1Players = team1 == null ? Set.of() : new LinkedHashSet<>(team1);
            this.team2Players = team2 == null ? Set.of() : new LinkedHashSet<>(team2);
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

    // ❌ REMOVIDO: HashMap local - migrado para 100% Redis
    // ✅ NOVO FLUXO:
    // - getDraftStateFromRedis(matchId) → busca do Redis
    // - Se Redis vazio → busca do MySQL (pick_ban_data_json) e cacheia no Redis
    // - Salva no Redis após cada ação via redisDraftFlow.saveDraftState()
    // - NUNCA mais usa HashMap local states
    // private final Map<Long, DraftState> states = new ConcurrentHashMap<>();

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

        // ✅ TIMER: Iniciar thread simples
        startSimpleTimer();

        log.info("✅ DraftFlowService inicializado com sucesso");
    }

    /**
     * ✅ TIMER SIMPLES: Thread que apenas decrementa 30→0 e envia o número
     */
    private void startSimpleTimer() {
        timerRunning = true;
        timerThread = new Thread(() -> {
            log.info("⏰ Thread de timer INICIADA");

            while (timerRunning) {
                try {
                    Thread.sleep(1000); // 1 segundo

                    // ✅ REFATORADO: Buscar drafts ativos do MySQL (não de HashMap)
                    List<br.com.lolmatchmaking.backend.domain.entity.CustomMatch> activeDrafts = customMatchRepository
                            .findByStatus("draft");

                    for (br.com.lolmatchmaking.backend.domain.entity.CustomMatch match : activeDrafts) {
                        Long matchId = match.getId();

                        // ✅ Buscar estado do Redis (com fallback MySQL)
                        DraftState st = getDraftStateFromRedis(matchId);

                        if (st == null) {
                            log.debug("⚠️ [Timer] DraftState não encontrado: matchId={}", matchId);
                            continue;
                        }

                        // Só se não estiver completo
                        if (st.getCurrentIndex() >= st.getActions().size()) {
                            continue;
                        }

                        // ⚡ REDIS: Decrementar timer atomicamente
                        int currentTimer = redisDraftFlow.decrementTimer(matchId);

                        // Enviar atualização
                        sendTimerOnly(matchId, st, currentTimer);
                    }

                } catch (InterruptedException e) {
                    timerRunning = false;
                    break;
                } catch (Exception e) {
                    log.error("❌ Erro no timer", e);
                }
            }

            log.info("⏰ Thread de timer FINALIZADA");
        }, "Draft-Simple-Timer");

        timerThread.setDaemon(true);
        timerThread.start();
    }

    /**
     * ✅ TIMER: Envia APENAS o número via draft_update
     */
    private void sendTimerOnly(Long matchId, DraftState st, int seconds) {
        try {
            // Combinar jogadores
            Collection<String> allPlayers = new ArrayList<>();
            allPlayers.addAll(st.getTeam1Players());
            allPlayers.addAll(st.getTeam2Players());

            // Buscar sessões
            Collection<org.springframework.web.socket.WebSocketSession> sessions = sessionRegistry
                    .getByPlayers(allPlayers);

            if (sessions.isEmpty()) {
                return;
            }

            // Criar payload SIMPLES
            Map<String, Object> data = Map.of(
                    "matchId", matchId,
                    "timeRemaining", seconds);

            String payload = mapper.writeValueAsString(Map.of(
                    "type", "draft_update",
                    "data", data));

            // Enviar
            for (org.springframework.web.socket.WebSocketSession ws : sessions) {
                try {
                    ws.sendMessage(new TextMessage(payload));
                } catch (Exception e) {
                    log.warn("⚠️ Erro ao enviar timer");
                }
            }

        } catch (Exception e) {
            log.error("❌ Erro sendTimerOnly", e);
        }
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

                            // ✅ CORREÇÃO: Buscar championName do Data Dragon
                            String championName = null;
                            if (championId != null && !championId.isBlank() && !SKIPPED.equalsIgnoreCase(championId)) {
                                championName = dataDragonService.getChampionName(championId);
                            }

                            actions.add(new DraftAction(idx, type, team, championId, championName, byPlayer));
                        }
                        List<String> team1 = parseCSV(cm.getTeam1PlayersJson());
                        List<String> team2 = parseCSV(cm.getTeam2PlayersJson());
                        DraftState st = new DraftState(cm.getId(), actions, team1, team2);
                        int cur = currentIdxObj instanceof Number n ? n.intValue() : 0;
                        while (st.getCurrentIndex() < cur && st.getCurrentIndex() < actions.size()) {
                            st.advance();
                        }
                        // ✅ Salvar no Redis (não mais em HashMap)
                        saveDraftStateToRedis(cm.getId(), st);
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

    // ========================================
    // ✅ NOVO: MÉTODOS PARA REDIS 100%
    // ========================================

    /**
     * ✅ NOVO: Busca DraftState do Redis ou MySQL (NUNCA de HashMap)
     * 
     * FLUXO:
     * 1. Tentar Redis (redisDraftFlow.getDraftState)
     * 2. Se vazio → Buscar MySQL (custom_matches.pick_ban_data_json)
     * 3. Cachear no Redis
     * 4. Retornar DraftState
     */
    private DraftState getDraftStateFromRedis(Long matchId) {
        try {
            // ✅ 1. Tentar buscar do Redis
            Map<String, Object> redisState = redisDraftFlow.getDraftState(matchId);

            if (redisState != null) {
                // Converter Map para DraftState
                return deserializeDraftState(matchId, redisState);
            }

            // ✅ 2. Redis vazio → Buscar do MySQL
            log.info("🔄 [DraftFlow] Redis vazio, buscando do MySQL: matchId={}", matchId);

            DraftState stateFromMySQL = loadDraftStateFromMySQL(matchId);

            if (stateFromMySQL != null) {
                // ✅ 3. Cachear no Redis
                saveDraftStateToRedis(matchId, stateFromMySQL);
                log.info("✅ [DraftFlow] Estado carregado do MySQL e cacheado no Redis");
                return stateFromMySQL;
            }

            log.warn("⚠️ [DraftFlow] Draft {} não encontrado nem no Redis nem no MySQL", matchId);
            return null;

        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao buscar DraftState: matchId={}", matchId, e);
            return null;
        }
    }

    /**
     * ✅ NOVO: Carrega DraftState do MySQL (custom_matches.pick_ban_data_json)
     */
    private DraftState loadDraftStateFromMySQL(Long matchId) {
        try {
            return customMatchRepository.findById(matchId)
                    .filter(cm -> "draft".equalsIgnoreCase(cm.getStatus()))
                    .filter(cm -> cm.getPickBanDataJson() != null && !cm.getPickBanDataJson().isBlank())
                    .map(cm -> {
                        try {
                            return parseDraftStateFromJSON(matchId, cm);
                        } catch (Exception e) {
                            log.error("❌ Erro ao parsear DraftState do MySQL", e);
                            return null;
                        }
                    })
                    .orElse(null);

        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao carregar do MySQL: matchId={}", matchId, e);
            return null;
        }
    }

    /**
     * ✅ NOVO: Parseia DraftState do JSON do MySQL
     */
    private DraftState parseDraftStateFromJSON(Long matchId, br.com.lolmatchmaking.backend.domain.entity.CustomMatch cm)
            throws Exception {
        Map<?, ?> snap = mapper.readValue(cm.getPickBanDataJson(), Map.class);
        Object actionsObj = snap.get("actions");
        Object currentIdxObj = snap.get("currentIndex");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actionMaps = actionsObj instanceof List<?> l
                ? (List<Map<String, Object>>) (List<?>) l
                : List.of();

        List<DraftAction> actions = new ArrayList<>();
        for (Map<String, Object> am : actionMaps) {
            int idx = ((Number) am.getOrDefault("index", 0)).intValue();
            String type = (String) am.getOrDefault("type", "pick");
            int team = ((Number) am.getOrDefault("team", 1)).intValue();
            String champId = (String) am.get("championId");
            String byPlayer = (String) am.get("byPlayer");

            String championName = null;
            if (champId != null && !champId.isBlank() && !"SKIPPED".equalsIgnoreCase(champId)) {
                championName = dataDragonService.getChampionName(champId);
            }

            actions.add(new DraftAction(idx, type, team, champId, championName, byPlayer));
        }

        List<String> team1 = parseCSV(cm.getTeam1PlayersJson());
        List<String> team2 = parseCSV(cm.getTeam2PlayersJson());
        DraftState st = new DraftState(matchId, actions, team1, team2);

        int cur = currentIdxObj instanceof Number n ? n.intValue() : 0;
        while (st.getCurrentIndex() < cur && st.getCurrentIndex() < actions.size()) {
            st.advance();
        }

        return st;
    }

    /**
     * ✅ NOVO: Salva DraftState no Redis
     */
    private void saveDraftStateToRedis(Long matchId, DraftState st) {
        try {
            Map<String, Object> stateMap = serializeDraftState(st);
            redisDraftFlow.saveDraftState(matchId, stateMap);
            log.debug("💾 [DraftFlow] Estado salvo no Redis: matchId={}", matchId);
        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao salvar no Redis: matchId={}", matchId, e);
        }
    }

    /**
     * ✅ NOVO: Serializa DraftState para Map
     */
    private Map<String, Object> serializeDraftState(DraftState st) {
        Map<String, Object> map = new HashMap<>();
        map.put("matchId", st.getMatchId());
        map.put("actions", st.getActions());
        map.put("currentIndex", st.getCurrentIndex());
        map.put("team1Players", new ArrayList<>(st.getTeam1Players()));
        map.put("team2Players", new ArrayList<>(st.getTeam2Players()));
        map.put("confirmations", new ArrayList<>(st.getConfirmations()));
        map.put("lastActionStartMs", st.getLastActionStartMs());
        return map;
    }

    /**
     * ✅ NOVO: Desserializa Map para DraftState
     */
    @SuppressWarnings("unchecked")
    private DraftState deserializeDraftState(Long matchId, Map<String, Object> map) {
        try {
            List<Map<String, Object>> actionsData = (List<Map<String, Object>>) map.get("actions");
            List<DraftAction> actions = new ArrayList<>();

            for (Map<String, Object> actionData : actionsData) {
                actions.add(new DraftAction(
                        ((Number) actionData.get("index")).intValue(),
                        (String) actionData.get("type"),
                        ((Number) actionData.get("team")).intValue(),
                        (String) actionData.get("championId"),
                        (String) actionData.get("championName"),
                        (String) actionData.get("byPlayer")));
            }

            List<String> team1 = (List<String>) map.get("team1Players");
            List<String> team2 = (List<String>) map.get("team2Players");

            DraftState st = new DraftState(matchId, actions, team1, team2);

            int currentIdx = ((Number) map.getOrDefault("currentIndex", 0)).intValue();
            while (st.getCurrentIndex() < currentIdx && st.getCurrentIndex() < actions.size()) {
                st.advance();
            }

            // ✅ CRÍTICO: Restaurar lastActionStartMs do Redis (senão perde o ajuste de
            // bots!)
            Object lastActionMs = map.get("lastActionStartMs");
            if (lastActionMs instanceof Number) {
                st.lastActionStartMs = ((Number) lastActionMs).longValue();
                log.debug("✅ [DraftFlow] lastActionStartMs restaurado do Redis: {}", st.lastActionStartMs);
            }

            return st;

        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao desserializar DraftState", e);
            return null;
        }
    }

    /**
     * ✅ REFATORADO: Inicia draft usando 100% Redis
     */
    public DraftState startDraft(long matchId, List<String> team1Players, List<String> team2Players) {
        List<DraftAction> actions = buildDefaultActionSequence();
        DraftState st = new DraftState(matchId, actions, team1Players, team2Players);

        // ✅ CORREÇÃO BOTS: Se primeiro jogador é bot, iniciar com tempo no passado
        if (!actions.isEmpty()) {
            DraftAction firstAction = actions.get(0);
            String firstPlayer = getPlayerForTeamAndIndex(st, firstAction.team(), 0);
            if (isBot(firstPlayer)) {
                log.info("🤖 [DraftFlow] Primeira ação é de bot {}, ajustando timer para auto-pick", firstPlayer);
                st.lastActionStartMs = System.currentTimeMillis() - 3000; // 3s no passado
            }
        }

        // ✅ Salvar no Redis (fonte ÚNICA)
        saveDraftStateToRedis(matchId, st);

        log.info("🎬 [DraftFlow] startDraft - matchId={}, actions={}, currentIndex={}, team1={}, team2={}",
                matchId, actions.size(), st.getCurrentIndex(), team1Players, team2Players);

        // ⚡ REDIS: Inicializar timer (30 segundos)
        redisDraftFlow.initTimer(matchId);

        // ✅ Persistir o estado inicial no MySQL
        persist(matchId, st);

        log.info("📡 [DraftFlow] startDraft - Estado salvo no Redis e MySQL: matchId={}", matchId);

        // ✅ CRÍTICO: Fazer broadcast inicial para frontend
        broadcastUpdate(st, false);
        log.info("✅ [DraftFlow] startDraft - Broadcast inicial enviado para frontend");

        return st;
    }

    private List<DraftAction> buildDefaultActionSequence() {
        List<DraftAction> list = new ArrayList<>();
        int i = 0;

        // ✅ CORREÇÃO: Seguir sequência EXATA do draft ranqueado do LoL

        // Ações 0-5: Primeira fase de bans (6 bans - 3 por time)
        // Ordem: Blue Ban 1, Red Ban 1, Blue Ban 2, Red Ban 2, Blue Ban 3, Red Ban 3
        int[] firstBanTeams = { 1, 2, 1, 2, 1, 2 };
        for (int t : firstBanTeams)
            list.add(new DraftAction(i++, "ban", t, null, null, null)); // ⭐ +1 null para championName

        // Ações 6-11: Primeira fase de picks (6 picks - 3 por time)
        // Ordem: Blue Pick 1, Red Pick 1, Red Pick 2, Blue Pick 2, Blue Pick 3, Red
        // Pick 3
        int[] firstPickTeams = { 1, 2, 2, 1, 1, 2 };
        for (int t : firstPickTeams)
            list.add(new DraftAction(i++, "pick", t, null, null, null)); // ⭐ +1 null para championName

        // Ações 12-15: Segunda fase de bans (4 bans - 2 por time)
        // Ordem: Red Ban 4, Blue Ban 4, Red Ban 5, Blue Ban 5
        int[] secondBanTeams = { 2, 1, 2, 1 };
        for (int t : secondBanTeams)
            list.add(new DraftAction(i++, "ban", t, null, null, null)); // ⭐ +1 null para championName

        // Ações 16-19: Segunda fase de picks (4 picks - 2 por time)
        // Ordem: Red Pick 4, Blue Pick 4, Blue Pick 5, Red Pick 5
        int[] secondPickTeams = { 2, 1, 1, 2 };
        for (int t : secondPickTeams)
            list.add(new DraftAction(i++, "pick", t, null, null, null)); // ⭐ +1 null para championName

        return list;
    }

    /**
     * ✅ CORREÇÃO #6: Normaliza championId para formato numérico (key)
     * Aceita tanto o nome do campeão (ex: "Ahri") quanto o ID numérico (ex: "103")
     * Retorna sempre o ID numérico (key) ou null se inválido
     */
    private String normalizeChampionId(String championId) {
        if (championId == null || championId.isBlank() || SKIPPED.equalsIgnoreCase(championId)) {
            return championId;
        }

        // Se já é numérico, validar se existe
        if (championId.matches("\\d+")) {
            String championName = dataDragonService.getChampionName(championId);
            if (championName != null) {
                log.debug("✅ [normalizeChampionId] championId numérico válido: {} -> {}", championId, championName);
                return championId;
            }
            log.warn("⚠️ [normalizeChampionId] championId numérico inválido: {}", championId);
            return null;
        }

        // Se é string (nome), converter para key
        String championKey = dataDragonService.getChampionKeyByName(championId);
        if (championKey != null) {
            log.debug("✅ [normalizeChampionId] Convertido nome '{}' para key '{}'", championId, championKey);
            return championKey;
        }

        log.warn("⚠️ [normalizeChampionId] Campeão não encontrado: {}", championId);
        return null;
    }

    /**
     * ✅ REFATORADO: Processa ação do draft usando 100% Redis + Distributed Lock
     * Lock previne bugs quando 2+ players tentam ações simultâneas
     */
    @Transactional
    public boolean processAction(long matchId, int actionIndex, String championId, String byPlayer) {
        // ✅ CRÍTICO: Distributed lock para prevenir ações simultâneas
        org.redisson.api.RLock lock = redisDraftFlow.getLock("draft_action:" + matchId);

        try {
            // Tentar adquirir lock por 5 segundos
            if (!lock.tryLock(5, 30, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("⚠️ [processAction] Não foi possível adquirir lock para matchId={}", matchId);
                return false;
            }

            return processActionWithLock(matchId, actionIndex, championId, byPlayer);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ [processAction] Lock interrompido", e);
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * ✅ NOVO: Lógica interna com lock garantido
     */
    private boolean processActionWithLock(long matchId, int actionIndex, String championId, String byPlayer) {
        log.info("\n========================================");
        log.info("🔵 [processAction] === INICIANDO AÇÃO ===");
        log.info("========================================");
        log.info("📋 MatchId: {}", matchId);
        log.info("📋 Action Index: {}", actionIndex);
        log.info("📋 Champion ID: {}", championId);
        log.info("📋 By Player: {}", byPlayer);

        // ✅ NOVO: VALIDAR PlayerState COM CLEANUP INTELIGENTE
        br.com.lolmatchmaking.backend.service.lock.PlayerState state = playerStateService.getPlayerState(byPlayer);
        if (state != br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_DRAFT) {
            // ✅ CLEANUP INTELIGENTE: Verificar no MySQL
            boolean reallyInDraft = customMatchRepository.findById(matchId)
                    .map(match -> {
                        // Verificar status
                        if (!"draft".equalsIgnoreCase(match.getStatus())) {
                            return false;
                        }

                        // ✅ CORREÇÃO: Verificar com CASE-INSENSITIVE
                        String team1 = match.getTeam1PlayersJson();
                        String team2 = match.getTeam2PlayersJson();
                        boolean inTeam1 = team1 != null && team1.toLowerCase().contains(byPlayer.toLowerCase());
                        boolean inTeam2 = team2 != null && team2.toLowerCase().contains(byPlayer.toLowerCase());
                        return inTeam1 || inTeam2;
                    })
                    .orElse(false);

            if (reallyInDraft) {
                log.warn("🧹 [processAction] ESTADO INCONSISTENTE: {} está no draft (MySQL) mas estado Redis é {}",
                        byPlayer, state);
                // ✅ FORCE SET pois pode estar em qualquer estado
                playerStateService.forceSetPlayerState(byPlayer,
                        br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_DRAFT);
                log.info("✅ [processAction] PlayerState corrigido para IN_DRAFT");
            } else {
                log.warn("❌ [processAction] Player {} NÃO está no draft {} (estado: {})",
                        byPlayer, matchId, state);
                return false;
            }
        }

        // ✅ Buscar do Redis (que busca do MySQL se necessário)
        DraftState st = getDraftStateFromRedis(matchId);
        if (st == null) {
            log.warn("❌ [processAction] DraftState não encontrado no Redis/MySQL para matchId={}", matchId);
            log.info("========================================\n");
            return false;
        }

        // ✅ LOGS DETALHADOS DO ESTADO ATUAL
        log.info("📊 Estado do Draft:");
        log.info("   - Current Index: {}", st.getCurrentIndex());
        log.info("   - Total Actions: {}", st.getActions().size());
        log.info("   - Team1 Players: {}", st.getTeam1Players());
        log.info("   - Team2 Players: {}", st.getTeam2Players());

        // ✅ CORREÇÃO #6: Normalizar championId antes de processar
        final String normalizedChampionId = normalizeChampionId(championId);
        if (normalizedChampionId == null) {
            log.warn("❌ [processAction] championId inválido após normalização: {}", championId);
            return false;
        }
        log.info("✅ [processAction] championId normalizado: {} -> {}", championId, normalizedChampionId);

        if (st.getCurrentIndex() >= st.getActions().size()) { // draft já completo
            log.warn("❌ [processAction] Draft já completo: currentIndex={}, totalActions={}",
                    st.getCurrentIndex(), st.getActions().size());
            return false;
        }

        if (actionIndex != st.currentIndex) {
            log.warn("❌ [processAction] actionIndex diferente: esperado={}, recebido={}",
                    st.currentIndex, actionIndex);
            return false;
        }
        log.info("✅ [processAction] actionIndex validado: {}", actionIndex);

        DraftAction prev = st.actions.get(actionIndex);
        log.info("🔍 [processAction] Ação atual: type={}, team={}", prev.type(), prev.team());

        // ✅ Calcular jogador esperado para esta ação
        String expectedPlayer = getPlayerForTeamAndIndex(st, prev.team(), actionIndex);
        log.info("🔍 [processAction] Jogador esperado: {}", expectedPlayer);
        log.info("🔍 [processAction] Jogador recebido: {}", byPlayer);

        // ✅ Validar se o jogador pertence ao time da ação
        if (!st.isPlayerInTeam(byPlayer, prev.team())) {
            log.warn("❌ [processAction] Jogador {} NÃO pertence ao time {}", byPlayer, prev.team());
            log.warn("❌ [processAction] Team1 players: {}", st.getTeam1Players());
            log.warn("❌ [processAction] Team2 players: {}", st.getTeam2Players());
            return false;
        }
        log.info("✅ [processAction] Jogador {} pertence ao team{}", byPlayer, prev.team());
        boolean alreadyUsed = st.actions.stream()
                .filter(a -> a.championId() != null && !SKIPPED.equalsIgnoreCase(a.championId()))
                .anyMatch(a -> normalizedChampionId.equalsIgnoreCase(a.championId()));
        if (alreadyUsed) {
            return false;
        }

        // ✅ CORREÇÃO: Buscar nome do campeão antes de criar DraftAction
        String championName = dataDragonService.getChampionName(normalizedChampionId);

        DraftAction updated = new DraftAction(
                prev.index(),
                prev.type(),
                prev.team(),
                normalizedChampionId,
                championName, // ⭐ ADICIONAR championName
                byPlayer);
        st.getActions().set(actionIndex, updated);
        st.advance();

        // ✅ CORREÇÃO BOTS: Se próximo jogador é bot, ajustar timer para auto-pick
        // rápido
        if (st.getCurrentIndex() < st.getActions().size()) {
            DraftAction nextAction = st.getActions().get(st.getCurrentIndex());
            String nextPlayer = getPlayerForTeamAndIndex(st, nextAction.team(), st.getCurrentIndex());
            if (isBot(nextPlayer)) {
                log.info("🤖 [DraftFlow] Próxima ação é de bot {}, ajustando timer para auto-pick", nextPlayer);
                st.lastActionStartMs = System.currentTimeMillis() - 3000; // Permitir pick imediato
            } else {
                st.markActionStart(); // Player real: timer normal
            }
        } else {
            st.markActionStart(); // Última ação: resetar normalmente
        }

        // ⚡ REDIS: Resetar timer para 30 quando ação acontece
        redisDraftFlow.resetTimer(matchId);

        // ✅ LOGS ANTES DE PERSISTIR
        log.info("💾 Salvando ação no MySQL e Redis...");

        // ✅ Persistir no MySQL
        persist(matchId, st);

        // ✅ CRÍTICO: Salvar no Redis para sincronizar com outros backends
        saveDraftStateToRedis(matchId, st);

        log.info("✅ Ação salva com sucesso no MySQL e Redis!");

        // ✅ LOGS DETALHADOS DA AÇÃO SALVA
        log.info("\n========================================");
        log.info("✅ [processAction] === AÇÃO SALVA COM SUCESSO ===");
        log.info("========================================");
        log.info("🎯 Ação #{}: {} {} por {}",
                actionIndex,
                updated.type().toUpperCase(),
                championName != null ? championName : normalizedChampionId,
                byPlayer);
        log.info("🎯 Team: {}", updated.team());
        log.info("🎯 Champion ID normalizado: {}", normalizedChampionId);
        log.info("🎯 Champion Name: {}", championName);
        log.info("🎯 Próxima ação: {} / {}", st.getCurrentIndex(), st.getActions().size());

        // ✅ MOSTRAR PEDAÇO DO JSON SALVO
        try {
            DraftAction savedAction = st.getActions().get(actionIndex);
            log.info("📄 JSON da ação salva:");
            log.info("   {{");
            log.info("     \"index\": {},", savedAction.index());
            log.info("     \"type\": \"{}\",", savedAction.type());
            log.info("     \"team\": {},", savedAction.team());
            log.info("     \"championId\": \"{}\",", savedAction.championId());
            log.info("     \"championName\": \"{}\",", savedAction.championName());
            log.info("     \"byPlayer\": \"{}\"", savedAction.byPlayer());
            log.info("   }}");
        } catch (Exception e) {
            log.warn("⚠️ Erro ao exibir JSON da ação: {}", e.getMessage());
        }
        log.info("========================================\n");

        // ✅ Broadcast para TODOS (não só jogadores da partida)
        broadcastUpdate(st, false);

        if (st.getCurrentIndex() >= st.getActions().size()) {
            log.info("🏁 [processAction] Draft completo! Broadcast de conclusão...");
            broadcastDraftCompleted(st);
        }
        return true;
    }

    /**
     * ✅ REFATORADO: Confirma draft usando 100% Redis
     */
    @Transactional
    public synchronized void confirmDraft(long matchId, String playerId) {
        // ✅ NOVO: VALIDAR PlayerState COM CLEANUP INTELIGENTE
        br.com.lolmatchmaking.backend.service.lock.PlayerState state = playerStateService.getPlayerState(playerId);
        if (state != br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_DRAFT) {
            // ✅ CLEANUP INTELIGENTE: Verificar no MySQL
            boolean reallyInDraft = customMatchRepository.findById(matchId)
                    .map(match -> {
                        String status = match.getStatus();
                        if (!"draft".equalsIgnoreCase(status)) {
                            return false;
                        }

                        // ✅ CORREÇÃO: Verificar com CASE-INSENSITIVE
                        String team1 = match.getTeam1PlayersJson();
                        String team2 = match.getTeam2PlayersJson();
                        boolean inTeam1 = team1 != null && team1.toLowerCase().contains(playerId.toLowerCase());
                        boolean inTeam2 = team2 != null && team2.toLowerCase().contains(playerId.toLowerCase());
                        return inTeam1 || inTeam2;
                    })
                    .orElse(false);

            if (reallyInDraft) {
                log.warn("🧹 [confirmDraft] ESTADO INCONSISTENTE: {} está no draft (MySQL) mas estado Redis é {}",
                        playerId, state);
                // ✅ FORCE SET pois pode estar em qualquer estado
                playerStateService.forceSetPlayerState(playerId,
                        br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_DRAFT);
                log.info("✅ [confirmDraft] PlayerState corrigido para IN_DRAFT");
            } else {
                log.warn("❌ [confirmDraft] Player {} NÃO está no draft {} (estado: {})",
                        playerId, matchId, state);
                return;
            }
        }

        // ✅ Buscar do Redis
        DraftState st = getDraftStateFromRedis(matchId);
        if (st == null) {
            log.warn("❌ [confirmDraft] DraftState não encontrado: matchId={}", matchId);
            return;
        }

        st.getConfirmations().add(playerId);

        // ✅ Salvar no Redis
        saveDraftStateToRedis(matchId, st);

        broadcastUpdate(st, true);

        if (st.getConfirmations().size() >= 10) {
            customMatchRepository.findById(matchId).ifPresent(cm -> {
                cm.setStatus("draft_completed");
                customMatchRepository.save(cm);
            });
            broadcastAllConfirmed(st);
        }
    }

    /**
     * ✅ NOVO: Permite alterar um pick já realizado durante a fase de confirmação
     * Usado quando o jogador clica em "Editar" no modal de confirmação
     */
    @Transactional
    public synchronized void changePick(Long matchId, String playerId, String newChampionId) {
        log.info("\n========================================");
        log.info("🔄 [changePick] === ALTERANDO PICK ===");
        log.info("========================================");
        log.info("📋 MatchId: {}", matchId);
        log.info("📋 Player ID recebido: {}", playerId);
        log.info("📋 New Champion ID: {}", newChampionId);

        // ✅ Buscar do Redis
        DraftState st = getDraftStateFromRedis(matchId);
        if (st == null) {
            log.warn("❌ [changePick] DraftState não encontrado no Redis/MySQL: matchId={}", matchId);
            return;
        }

        // ✅ DEBUG: Mostrar todos os jogadores nos times
        log.info("🔍 [changePick] Players do Time 1: {}", st.getTeam1Players());
        log.info("🔍 [changePick] Players do Time 2: {}", st.getTeam2Players());

        // ✅ DEBUG: Mostrar todas as ações de pick
        log.info("🔍 [changePick] Todas as ações de pick:");
        for (int i = 0; i < st.getActions().size(); i++) {
            DraftAction action = st.getActions().get(i);
            if ("pick".equals(action.type())) {
                log.info("   [{}] type={}, byPlayer='{}', champion={}",
                        i, action.type(), action.byPlayer(), action.championName());
            }
        }

        // ✅ Normalizar championId
        final String normalizedChampionId = normalizeChampionId(newChampionId);
        if (normalizedChampionId == null) {
            log.warn("❌ [changePick] championId inválido: {}", newChampionId);
            return;
        }
        log.info("✅ [changePick] championId normalizado: {} -> {}", newChampionId, normalizedChampionId);

        // ✅ Buscar a ação de pick deste jogador (comparação mais flexível)
        int actionIndex = -1;
        for (int i = 0; i < st.getActions().size(); i++) {
            DraftAction action = st.getActions().get(i);
            if ("pick".equals(action.type())) {
                String actionPlayer = action.byPlayer();
                log.info("🔍 [changePick] Comparando '{}' com '{}'", playerId, actionPlayer);

                // ✅ COMPARAÇÃO FLEXÍVEL: tentar match exato ou parcial
                if (playerId.equals(actionPlayer) ||
                        actionPlayer.contains(playerId) ||
                        playerId.contains(actionPlayer)) {
                    actionIndex = i;
                    log.info("✅ [changePick] MATCH encontrado no index {}", i);
                    break;
                }
            }
        }

        if (actionIndex == -1) {
            log.warn("❌ [changePick] Pick não encontrado para jogador: {}", playerId);
            log.warn("❌ [changePick] Nenhuma ação de pick corresponde a este jogador");
            return;
        }

        DraftAction oldAction = st.getActions().get(actionIndex);
        log.info("🔍 [changePick] Pick anterior: {} (index={})", oldAction.championName(), actionIndex);

        // ✅ Verificar se o novo campeão já está sendo usado (exceto pelo próprio
        // jogador)
        boolean alreadyUsed = st.getActions().stream()
                .filter(a -> a.championId() != null && !SKIPPED.equalsIgnoreCase(a.championId()))
                .filter(a -> !playerId.equals(a.byPlayer())) // Ignorar o próprio jogador
                .anyMatch(a -> normalizedChampionId.equalsIgnoreCase(a.championId()));

        if (alreadyUsed) {
            log.warn("❌ [changePick] Campeão {} já está sendo usado por outro jogador", normalizedChampionId);
            return;
        }

        // ✅ Buscar nome do novo campeão
        String championName = dataDragonService.getChampionName(normalizedChampionId);

        // ✅ Criar nova ação atualizada
        DraftAction updatedAction = new DraftAction(
                oldAction.index(),
                oldAction.type(),
                oldAction.team(),
                normalizedChampionId,
                championName,
                playerId);

        // ✅ Atualizar a ação
        st.getActions().set(actionIndex, updatedAction);

        // ✅ Remover confirmação do jogador (precisa confirmar novamente)
        st.getConfirmations().remove(playerId);

        // ✅ Salvar no banco
        log.info("💾 Salvando alteração no banco de dados...");
        persist(matchId, st);
        log.info("✅ Alteração salva com sucesso!");

        log.info("\n========================================");
        log.info("✅ [changePick] === PICK ALTERADO COM SUCESSO ===");
        log.info("========================================");
        log.info("🎯 Jogador: {}", playerId);
        log.info("🎯 Campeão antigo: {}", oldAction.championName());
        log.info("🎯 Campeão novo: {}", championName);
        log.info("🎯 Confirmação removida - jogador precisa confirmar novamente");
        log.info("========================================\n");

        // ✅ Broadcast da atualização
        broadcastUpdate(st, true);
    }

    public Optional<DraftState> getState(long matchId) {
        // ✅ Buscar do Redis
        return Optional.ofNullable(getDraftStateFromRedis(matchId));
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
                            log.warn("⚠️ [DraftFlow] team1 vazio, adicionando nomes (fallback): {}",
                                    st.getTeam1Players());
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
                            log.warn("⚠️ [DraftFlow] team2 vazio, adicionando nomes (fallback): {}",
                                    st.getTeam2Players());
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

                    // ✅ ESTRUTURA LIMPA + COMPATIBILIDADE
                    log.info("🔨 [persist] Gerando JSON LIMPO (teams + metadados + team1/team2 para compatibilidade)");
                    Map<String, Object> cleanData = buildHierarchicalDraftData(st);

                    // ✅ JSON FINAL: teams.blue/red (limpo) + team1/team2 (compatibilidade) +
                    // metadados
                    Map<String, Object> finalSnapshot = new HashMap<>();

                    // ✅ 1. Estrutura limpa (teams hierárquicos)
                    finalSnapshot.put("teams", cleanData.get("teams"));

                    // ✅ 2. Compatibilidade (team1/team2 flat) - NECESSÁRIO para
                    // buildHierarchicalDraftData funcionar
                    finalSnapshot.put(KEY_TEAM1, snapshot.get(KEY_TEAM1));
                    finalSnapshot.put(KEY_TEAM2, snapshot.get(KEY_TEAM2));

                    // ✅ 3. Metadados
                    finalSnapshot.put("currentIndex", cleanData.get("currentIndex"));
                    finalSnapshot.put("currentPhase", cleanData.get("currentPhase"));
                    finalSnapshot.put("currentPlayer", cleanData.get("currentPlayer"));
                    finalSnapshot.put("currentTeam", cleanData.get("currentTeam"));
                    finalSnapshot.put("currentActionType", cleanData.get("currentActionType"));

                    cm.setPickBanDataJson(mapper.writeValueAsString(finalSnapshot));
                    customMatchRepository.save(cm);

                    log.info(
                            "✅ [persist] JSON salvo: {} keys (teams LIMPO + team1/team2 compatibilidade)",
                            finalSnapshot.keySet().size());

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
    // ✅ Usar "team1" e "team2" (como no backend antigo), não
    // "team1Players"/"team2Players"
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
            long elapsed = System.currentTimeMillis() - st.getLastActionStartMs();

            // ✅ Calcular jogador atual
            String currentPlayer = null;
            int currentIdx = st.getCurrentIndex();
            if (currentIdx < st.getActions().size()) {
                DraftAction currentAction = st.getActions().get(currentIdx);
                currentPlayer = getPlayerForTeamAndIndex(st, currentAction.team(), currentIdx);
            }

            // ✅ CRÍTICO: Carregar dados completos dos times do banco, não apenas nomes
            Map<String, Object> updateData = new HashMap<>();
            updateData.put(KEY_TYPE, "draft_updated");
            updateData.put(KEY_MATCH_ID, st.getMatchId());
            updateData.put(KEY_CURRENT_INDEX, st.getCurrentIndex());
            updateData.put("currentAction", st.getCurrentIndex()); // ✅ CRÍTICO: Frontend espera currentAction
            updateData.put(KEY_ACTIONS, st.getActions());
            updateData.put(KEY_CONFIRMATIONS, st.getConfirmations());
            updateData.put("currentPlayer", currentPlayer); // ✅ Nome do jogador da vez

            log.info(
                    "📡 [DraftFlow] Broadcasting update - matchId={}, currentIndex={}, currentPlayer={}, elapsed={}ms, remainingMs={}, timeout={}ms",
                    st.getMatchId(), currentIdx, currentPlayer, elapsed, remainingMs, getActionTimeoutMs());

            // ✅ Buscar dados completos dos times do banco
            customMatchRepository.findById(st.getMatchId()).ifPresent(cm -> {
                if (cm.getPickBanDataJson() != null && !cm.getPickBanDataJson().isEmpty()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pickBanData = mapper.readValue(cm.getPickBanDataJson(), Map.class);

                        // ✅ ESTRUTURA HIERÁRQUICA (teams.blue/red)
                        Object teamsData = pickBanData.get("teams");
                        if (teamsData != null) {
                            updateData.put("teams", teamsData);
                            log.info("✅✅✅ [DraftFlow] Broadcast COM estrutura teams.blue/red!");
                            log.info("✅✅✅ [DraftFlow] teams Data: {}", mapper.writeValueAsString(teamsData));
                        } else {
                            log.warn("⚠️⚠️⚠️ [DraftFlow] Broadcast SEM estrutura teams! pickBanData keys: {}",
                                    pickBanData.keySet());
                        }

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
            // ✅ CORREÇÃO CRÍTICA: Frontend espera timeRemaining em SEGUNDOS
            updateData.put("timeRemaining", (int) Math.ceil(remainingMs / 1000.0));

            // ✅ ADICIONAR ESTRUTURA LIMPA (sem duplicação team1/team2/actions)
            log.info("🔨 [broadcastUpdate] Adicionando JSON LIMPO ao broadcast");
            Map<String, Object> cleanData = buildHierarchicalDraftData(st);
            if (cleanData.containsKey("teams")) {
                updateData.put("teams", cleanData.get("teams"));
                updateData.put("currentPhase", cleanData.get("currentPhase"));
                updateData.put("currentPlayer", cleanData.get("currentPlayer"));
                updateData.put("currentTeam", cleanData.get("currentTeam"));
                updateData.put("currentActionType", cleanData.get("currentActionType"));
                updateData.put(KEY_CURRENT_INDEX, cleanData.get("currentIndex"));
                log.info(
                        "✅ [broadcastUpdate] JSON LIMPO adicionado: apenas teams + metadados (sem team1/team2/actions)");

                // ✅ LOG DETALHADO: Mostrar estrutura teams enviada
                try {
                    String teamsJson = mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(cleanData.get("teams"));
                    log.info("📤 [broadcastUpdate] === ESTRUTURA TEAMS ENVIADA ===");
                    log.info("{}", teamsJson);
                    log.info("==============================================");
                } catch (Exception e) {
                    log.warn("⚠️ Erro ao serializar teams para log: {}", e.getMessage());
                }
            }

            String payload = mapper.writeValueAsString(updateData);

            // ✅ LOG DETALHADO: Mostrar payload completo do broadcast
            log.info("📤 [broadcastUpdate] === PAYLOAD COMPLETO DO BROADCAST ===");
            log.info("{}", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(updateData));
            log.info("====================================================");

            // ✅ CORREÇÃO: Enviar apenas para os 10 jogadores da partida (não para todos)
            sendToMatchPlayers(st, payload);
        } catch (Exception e) {
            log.error("Erro broadcast draft_updated", e);
        }
    }

    /**
     * ✅ NOVO: Envia mensagem específica para UM jogador
     */
    private void sendToPlayer(String summonerName, String payload) {
        if (summonerName == null)
            return;

        sessionRegistry.getByPlayer(summonerName).ifPresent(ws -> {
            try {
                ws.sendMessage(new TextMessage(payload));
                log.debug("📤 Mensagem enviada para jogador: {}", summonerName);
            } catch (Exception e) {
                log.warn("⚠️ Erro ao enviar mensagem para {}: {}", summonerName, e.getMessage());
            }
        });
    }

    /**
     * ✅ NOVO: Envia mensagem para OS 10 JOGADORES da partida (broadcast restrito)
     * Usado para: match_found, draft_updated, game_in_progress
     */
    private void sendToMatchPlayers(DraftState st, String payload) {
        // Combinar todos os jogadores da partida (time 1 + time 2)
        Collection<String> allPlayers = new java.util.ArrayList<>();
        allPlayers.addAll(st.getTeam1Players());
        allPlayers.addAll(st.getTeam2Players());

        // Buscar sessões apenas desses 10 jogadores
        Collection<org.springframework.web.socket.WebSocketSession> playerSessions = sessionRegistry
                .getByPlayers(allPlayers);

        log.info("📤 Enviando mensagem para {} jogadores da partida (de {} esperados)",
                playerSessions.size(), allPlayers.size());

        // Enviar apenas para esses jogadores
        playerSessions.forEach(ws -> {
            try {
                ws.sendMessage(new TextMessage(payload));
            } catch (Exception e) {
                log.warn("⚠️ Erro ao enviar para jogador: {}", e.getMessage());
            }
        });
    }

    private void broadcastDraftCompleted(DraftState st) {
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    KEY_TYPE, "draft_completed",
                    KEY_MATCH_ID, st.getMatchId()));

            // ✅ CORREÇÃO: Enviar apenas para os 10 jogadores da partida
            sendToMatchPlayers(st, payload);
        } catch (Exception e) {
            log.error("Erro broadcast draft_completed", e);
        }
    }

    private void broadcastAllConfirmed(DraftState st) {
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    KEY_TYPE, "draft_confirmed",
                    KEY_MATCH_ID, st.getMatchId()));

            // ✅ CORREÇÃO: Enviar apenas para os 10 jogadores da partida
            sendToMatchPlayers(st, payload);
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
    }

    /**
     * ✅ ESTRUTURA HIERÁRQUICA LIMPA (SEM DUPLICAÇÃO)
     * Constrói JSON limpo: apenas teams.blue/red com players e ações + metadados
     * 🔥 CORREÇÃO: Constrói times diretamente da memória (DraftState), não do
     * banco!
     */
    private Map<String, Object> buildHierarchicalDraftData(DraftState st) {
        log.info("🔨 [buildHierarchicalDraftData] Construindo estrutura LIMPA para match {}", st.getMatchId());

        Map<String, Object> result = new HashMap<>();

        // ✅ CORREÇÃO CRÍTICA: Buscar dados dos times da MEMÓRIA ou do BANCO
        customMatchRepository.findById(st.getMatchId()).ifPresent(cm -> {
            try {
                List<Map<String, Object>> team1Players = null;
                List<Map<String, Object>> team2Players = null;

                // ✅ 1. Tentar buscar do banco (se existir estrutura antiga)
                if (cm.getPickBanDataJson() != null && !cm.getPickBanDataJson().isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pickBanData = mapper.readValue(cm.getPickBanDataJson(), Map.class);

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> team1FromDB = (List<Map<String, Object>>) pickBanData.get(KEY_TEAM1);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> team2FromDB = (List<Map<String, Object>>) pickBanData.get(KEY_TEAM2);

                    if (team1FromDB != null && !team1FromDB.isEmpty()) {
                        team1Players = team1FromDB;
                    }
                    if (team2FromDB != null && !team2FromDB.isEmpty()) {
                        team2Players = team2FromDB;
                    }
                }

                // ✅ 2. FALLBACK: Se não existe no banco, criar da memória (DraftState)
                if (team1Players == null || team1Players.isEmpty()) {
                    log.info("📝 [buildHierarchicalDraftData] Criando team1 da MEMÓRIA (DraftState)");
                    team1Players = new ArrayList<>();
                    int idx = 0;
                    for (String playerName : st.getTeam1Players()) {
                        Map<String, Object> playerObj = new HashMap<>();
                        playerObj.put("summonerName", playerName);
                        playerObj.put("teamIndex", idx++);
                        team1Players.add(playerObj);
                    }
                }

                if (team2Players == null || team2Players.isEmpty()) {
                    log.info("📝 [buildHierarchicalDraftData] Criando team2 da MEMÓRIA (DraftState)");
                    team2Players = new ArrayList<>();
                    int idx = 5; // Team 2 começa no índice 5
                    for (String playerName : st.getTeam2Players()) {
                        Map<String, Object> playerObj = new HashMap<>();
                        playerObj.put("summonerName", playerName);
                        playerObj.put("teamIndex", idx++);
                        team2Players.add(playerObj);
                    }
                }

                // ✅ 3. Construir estrutura hierárquica teams.blue/red (SEM duplicação)
                Map<String, Object> teams = new HashMap<>();
                teams.put("blue", buildCleanTeamData("Blue Team", team1Players, st.getActions(), 1));
                teams.put("red", buildCleanTeamData("Red Team", team2Players, st.getActions(), 2));
                result.put("teams", teams);

                log.info("✅ [buildHierarchicalDraftData] Estrutura LIMPA: blue={} players, red={} players",
                        team1Players.size(), team2Players.size());

            } catch (Exception e) {
                log.error("❌ [buildHierarchicalDraftData] Erro ao construir estrutura hierárquica", e);
            }
        });

        // ✅ 4. Adicionar APENAS metadados necessários
        result.put("currentIndex", st.getCurrentIndex());
        result.put("currentPhase", getCurrentPhaseName(st.getCurrentIndex()));

        // ✅ 5. Calcular jogador e time atual
        if (st.getCurrentIndex() < st.getActions().size()) {
            DraftAction currentAction = st.getActions().get(st.getCurrentIndex());
            String currentPlayer = getPlayerForTeamAndIndex(st, currentAction.team(), st.getCurrentIndex());
            result.put("currentPlayer", currentPlayer);
            result.put("currentTeam", currentAction.team() == 1 ? "blue" : "red");
            result.put("currentActionType", currentAction.type());
        }

        log.info("✅ [buildHierarchicalDraftData] JSON LIMPO gerado: {} keys (sem duplicação)", result.keySet().size());
        return result;
    }

    /**
     * ✅ Constrói dados LIMPOS de um time (sem duplicação)
     * Apenas: name, teamNumber, averageMmr, players (com actions)
     */
    private Map<String, Object> buildCleanTeamData(
            String teamName,
            List<Map<String, Object>> players,
            List<DraftAction> actions,
            int teamNumber) {

        Map<String, Object> team = new HashMap<>();
        team.put("name", teamName);
        team.put("teamNumber", teamNumber);

        // ✅ Calcular MMR médio do time
        double avgMmr = players.stream()
                .mapToInt(p -> {
                    Object mmrObj = p.get("mmr");
                    if (mmrObj instanceof Number) {
                        return ((Number) mmrObj).intValue();
                    }
                    return 0;
                })
                .average()
                .orElse(0);
        team.put("averageMmr", (int) Math.round(avgMmr));

        // ✅ Adicionar APENAS players essenciais com suas ações
        List<Map<String, Object>> cleanPlayers = new ArrayList<>();

        for (Map<String, Object> player : players) {
            String playerName = (String) player.get("summonerName");

            if (playerName == null || playerName.isEmpty()) {
                log.warn("⚠️ [buildCleanTeamData] Player sem summonerName, pulando");
                continue;
            }

            // ✅ Criar objeto limpo do player (apenas campos essenciais)
            Map<String, Object> cleanPlayer = new HashMap<>();
            cleanPlayer.put("summonerName", playerName);
            cleanPlayer.put("playerId", player.get("playerId"));
            cleanPlayer.put("mmr", player.get("mmr"));
            cleanPlayer.put("assignedLane", player.get("assignedLane"));
            cleanPlayer.put("primaryLane", player.get("primaryLane"));
            cleanPlayer.put("secondaryLane", player.get("secondaryLane"));
            cleanPlayer.put("isAutofill", player.get("isAutofill"));
            cleanPlayer.put("teamIndex", player.get("teamIndex"));

            // ✅ NOVO: Calcular e adicionar laneBadge
            String laneBadge = calculateLaneBadge(
                    (String) player.get("assignedLane"),
                    (String) player.get("primaryLane"),
                    (String) player.get("secondaryLane"));
            cleanPlayer.put("laneBadge", laneBadge);

            // ✅ ADICIONAR gameName e tagLine se existirem no formato summonerName
            if (playerName.contains("#")) {
                String[] parts = playerName.split("#", 2);
                cleanPlayer.put("gameName", parts[0]);
                cleanPlayer.put("tagLine", parts[1]);
            } else {
                // Se não tiver "#", usar o nome completo como gameName
                cleanPlayer.put("gameName", playerName);
                cleanPlayer.put("tagLine", "");
            }

            // ✅ Buscar TODAS as ações deste jogador (bans E picks juntos)
            List<Map<String, Object>> playerActions = actions.stream()
                    .filter(a -> a.team() == teamNumber)
                    .filter(a -> playerName.equals(a.byPlayer()))
                    .map(a -> {
                        Map<String, Object> actionData = new HashMap<>();
                        actionData.put("index", a.index());
                        actionData.put("type", a.type());
                        actionData.put("championId", a.championId());
                        actionData.put("championName", a.championName());
                        actionData.put("phase", getPhaseLabel(a.index()));
                        actionData.put("status", a.championId() == null ? "pending" : "completed");
                        return actionData;
                    })
                    .collect(java.util.stream.Collectors.toList());

            cleanPlayer.put("actions", playerActions);

            cleanPlayers.add(cleanPlayer);
        }

        team.put("players", cleanPlayers);

        // ✅ Adicionar allBans e allPicks para compatibilidade com o frontend
        List<String> allBans = actions.stream()
                .filter(a -> a.team() == teamNumber && "ban".equals(a.type()))
                .filter(a -> a.championId() != null && !SKIPPED.equals(a.championId()))
                .map(DraftAction::championId)
                .toList();
        team.put("allBans", allBans);

        List<String> allPicks = actions.stream()
                .filter(a -> a.team() == teamNumber && "pick".equals(a.type()))
                .filter(a -> a.championId() != null && !SKIPPED.equals(a.championId()))
                .map(DraftAction::championId)
                .toList();
        team.put("allPicks", allPicks);

        log.debug("✅ [buildCleanTeamData] Time {} construído: {} players, {} bans, {} picks", teamName,
                cleanPlayers.size(), allBans.size(), allPicks.size());
        return team;
    }

    /**
     * Retorna o nome da fase atual baseado no índice da ação
     */
    private String getCurrentPhaseName(int actionIndex) {
        if (actionIndex < 6)
            return "ban1";
        if (actionIndex < 12)
            return "pick1";
        if (actionIndex < 16)
            return "ban2";
        if (actionIndex < 20)
            return "pick2";
        return "completed";
    }

    /**
     * Retorna o label da fase para uma ação específica
     */
    private String getPhaseLabel(int actionIndex) {
        if (actionIndex < 6)
            return "ban1";
        if (actionIndex < 12)
            return "pick1";
        if (actionIndex < 16)
            return "ban2";
        if (actionIndex < 20)
            return "pick2";
        return "completed";
    }

    // ✅ FIM DA NOVA ESTRUTURA HIERÁRQUICA

    private void broadcastTimeout(DraftState st) {
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
        DraftState st = getDraftStateFromRedis(matchId);
        if (st == null)
            return Map.of("exists", false);
        long remainingMs = calcRemainingMs(st);

        // ✅ Usar HashMap mutável para adicionar dados completos dos times
        Map<String, Object> result = new HashMap<>();
        result.put("exists", true);
        result.put(KEY_ACTIONS, st.getActions());
        result.put(KEY_CURRENT_INDEX, st.getCurrentIndex());
        result.put(KEY_CONFIRMATIONS, st.getConfirmations());
        result.put(KEY_REMAINING_MS, remainingMs);
        result.put(KEY_ACTION_TIMEOUT_MS, getActionTimeoutMs());

        // ✅ Buscar dados completos dos times do banco (igual ao broadcastUpdate)
        customMatchRepository.findById(matchId).ifPresent(cm -> {
            if (cm.getPickBanDataJson() != null && !cm.getPickBanDataJson().isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pickBanData = mapper.readValue(cm.getPickBanDataJson(), Map.class);

                    // ✅ ESTRUTURA HIERÁRQUICA (teams.blue/red)
                    Object teamsData = pickBanData.get("teams");
                    if (teamsData != null) {
                        result.put("teams", teamsData);
                    }

                    // ✅ COMPATIBILIDADE (team1/team2 flat)
                    Object team1Data = pickBanData.get(KEY_TEAM1);
                    Object team2Data = pickBanData.get(KEY_TEAM2);

                    if (team1Data != null) {
                        result.put(KEY_TEAM1, team1Data);
                    }
                    if (team2Data != null) {
                        result.put(KEY_TEAM2, team2Data);
                    }
                } catch (Exception e) {
                    log.warn("Erro ao carregar pick_ban_data no snapshot", e);
                }
            }
        });

        // ✅ Fallback se não houver dados completos
        if (!result.containsKey(KEY_TEAM1)) {
            result.put(KEY_TEAM1, st.getTeam1Players());
        }
        if (!result.containsKey(KEY_TEAM2)) {
            result.put(KEY_TEAM2, st.getTeam2Players());
        }

        return result;
    }

    /**
     * ✅ REFATORADO: Reemite draft usando MySQL como fonte
     */
    public void reemitIfPlayerInDraft(String playerName, org.springframework.web.socket.WebSocketSession session) {
        // ✅ Buscar drafts ativos do MySQL
        List<br.com.lolmatchmaking.backend.domain.entity.CustomMatch> drafts = customMatchRepository
                .findByStatus("draft");

        for (br.com.lolmatchmaking.backend.domain.entity.CustomMatch match : drafts) {
            DraftState st = getDraftStateFromRedis(match.getId());
            if (st != null &&
                    (st.getTeam1Players().contains(playerName) || st.getTeam2Players().contains(playerName))) {
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

                    // Encontrou e enviou, sair do loop
                    break;
                } catch (Exception e) {
                    log.warn("Falha reemitir draft_snapshot", e);
                }
            }
        }
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

            // ✅ CORREÇÃO: Enviar apenas para os 10 jogadores da partida
            sendToMatchPlayers(st, payload);
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

    /**
     * ✅ REFATORADO: Reemite game_ready usando MySQL como fonte
     */
    public void reemitIfPlayerGameReady(String playerName, org.springframework.web.socket.WebSocketSession session) {
        // ✅ Buscar drafts game_ready do MySQL
        List<br.com.lolmatchmaking.backend.domain.entity.CustomMatch> drafts = customMatchRepository
                .findByStatus("game_ready");

        for (br.com.lolmatchmaking.backend.domain.entity.CustomMatch match : drafts) {
            DraftState st = getDraftStateFromRedis(match.getId());
            if (st != null &&
                    (st.getTeam1Players().contains(playerName) || st.getTeam2Players().contains(playerName))) {
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
                // Encontrou e enviou, sair do loop
                break;
            }
        }
    }

    /**
     * ✅ REFATORADO: Monitora timeouts usando MySQL como fonte
     */
    @Scheduled(fixedDelay = 1000)
    public void monitorActionTimeouts() {
        long now = System.currentTimeMillis();

        // ✅ Buscar drafts ativos do MySQL
        List<br.com.lolmatchmaking.backend.domain.entity.CustomMatch> drafts = customMatchRepository
                .findByStatus("draft");

        if (drafts.isEmpty()) {
            return; // Sem drafts ativos
        }

        log.info("⏰ [DraftFlow] Monitorando {} drafts ativos", drafts.size());

        for (br.com.lolmatchmaking.backend.domain.entity.CustomMatch match : drafts) {
            DraftState st = getDraftStateFromRedis(match.getId());
            if (st == null) {
                log.warn("⚠️ [DraftFlow] Estado null para match {}", match.getId());
                continue;
            }
            if (st.getCurrentIndex() >= st.getActions().size()) {
                log.info("✅ [DraftFlow] Draft {} completo", match.getId());
                continue; // ✅ CORREÇÃO: continue ao invés de return!
            }

            long elapsed = now - st.getLastActionStartMs();

            // ✅ NOVO: Para bots, fazer ação automática após 2 segundos ao invés de esperar
            // timeout completo
            int currentIdx = st.getCurrentIndex();
            DraftAction currentAction = st.getActions().get(currentIdx);
            String currentPlayer = getPlayerForTeamAndIndex(st, currentAction.team(), currentIdx);

            log.info("🔍 [DraftFlow] Match {} - Ação {}/{}: team={}, type={}, player={}, elapsed={}ms, isBot={}",
                    st.getMatchId(), currentIdx, st.getActions().size(),
                    currentAction.team(), currentAction.type(), currentPlayer, elapsed, isBot(currentPlayer));

            if (currentPlayer == null) {
                log.error("❌ [DraftFlow] Match {} - Jogador NULL para ação {} (team {})",
                        st.getMatchId(), currentIdx, currentAction.team());
                // Pular esta ação
                DraftAction skipped = new DraftAction(
                        currentAction.index(),
                        currentAction.type(),
                        currentAction.team(),
                        SKIPPED,
                        "SKIPPED", // ⭐ championName = "SKIPPED" também
                        "NO_PLAYER");
                st.getActions().set(currentIdx, skipped);
                st.advance();
                st.markActionStart();
                persist(st.getMatchId(), st);
                broadcastUpdate(st, false);
                return;
            }

            if (isBot(currentPlayer) && elapsed >= 2000) { // 2 segundos para bots
                log.info("🤖 [DraftFlow] Match {} - Bot {} fazendo ação automática (ação {}, {}) - elapsed={}ms",
                        st.getMatchId(), currentPlayer, currentIdx, currentAction.type(), elapsed);
                handleBotAutoAction(st, currentPlayer);
                return;
            } else if (isBot(currentPlayer)) {
                log.debug("⏳ [DraftFlow] Bot {} aguardando... elapsed={}ms (precisa 2000ms)", currentPlayer, elapsed);
            }

            // ✅ Para jogadores reais, usar timeout configurado
            if (elapsed >= getActionTimeoutMs()) {
                int idx = st.getCurrentIndex();
                DraftAction prev = st.getActions().get(idx);
                log.warn("⏰ [DraftFlow] Timeout na ação {} - marcando como SKIPPED", idx);
                DraftAction skipped = new DraftAction(
                        prev.index(),
                        prev.type(),
                        prev.team(),
                        SKIPPED,
                        "SKIPPED", // ⭐ championName = "SKIPPED" também
                        TIMEOUT_PLAYER);
                st.getActions().set(idx, skipped);
                st.advance();
                st.markActionStart();
                persist(st.getMatchId(), st);

                // ✅ Salvar estado no Redis
                saveDraftStateToRedis(st.getMatchId(), st);

                broadcastUpdate(st, false);
                if (st.getCurrentIndex() >= st.getActions().size()) {
                    broadcastDraftCompleted(st);
                }
            }
        }
    }

    /**
     * Verifica se um jogador é um bot
     */
    private boolean isBot(String playerName) {
        return playerName != null && playerName.startsWith("Bot");
    }

    /**
     * Retorna o nome do jogador para uma ação específica
     * Segue a sequência EXATA do draft ranqueado do LoL:
     * 
     * Ações 0-5: Bans fase 1 → team1[0], team2[0], team1[1], team2[1], team1[2],
     * team2[2]
     * Ações 6-11: Picks fase 1 → team1[0], team2[0], team2[1], team1[1], team1[2],
     * team2[2]
     * Ações 12-15: Bans fase 2 → team2[3], team1[3], team2[4], team1[4]
     * Ações 16-19: Picks fase 2 → team2[3], team1[3], team1[4], team2[4]
     */
    private String getPlayerForTeamAndIndex(DraftState st, int team, int actionIndex) {
        Set<String> teamPlayers = team == 1 ? st.getTeam1Players() : st.getTeam2Players();

        if (teamPlayers.isEmpty()) {
            log.warn("⚠️ [DraftFlow] Time {} está vazio", team);
            return null;
        }

        // Converter Set para List para acessar por índice (mantém ordem de inserção)
        List<String> team1List = new ArrayList<>(st.getTeam1Players());
        List<String> team2List = new ArrayList<>(st.getTeam2Players());

        // ✅ ORDEM CORRETA DO DOCUMENTO "pergunas draft.md"
        // Fase 1 - Bans (6): Top Azul → Top Vermelho → Jungle Azul → Jungle Vermelho →
        // Mid Azul → Mid Vermelho
        // Fase 2 - Picks (6): Azul pick 1 → Vermelho pick 2 → Azul pick 2 → Vermelho
        // pick 1 → Azul pick 1
        // Fase 3 - Bans (4): ADC Azul → ADC Vermelho → Suporte Azul → Suporte Vermelho
        // Fase 4 - Picks (4): Vermelho pick 2 → Azul pick 2 → Vermelho Last Pick
        int playerIndex;
        switch (actionIndex) {
            // ✅ FASE 1 - BANS (ações 0-5): Alternado Top/Jungle/Mid
            case 0:
                playerIndex = 0;
                break; // team1[0] - Top Azul
            case 1:
                playerIndex = 0;
                break; // team2[0] - Top Vermelho
            case 2:
                playerIndex = 1;
                break; // team1[1] - Jungle Azul
            case 3:
                playerIndex = 1;
                break; // team2[1] - Jungle Vermelho
            case 4:
                playerIndex = 2;
                break; // team1[2] - Mid Azul
            case 5:
                playerIndex = 2;
                break; // team2[2] - Mid Vermelho

            // ✅ FASE 2 - PICKS (ações 6-11): Azul 1 → Vermelho 2 (top+jungle) → Azul 2
            // (jungle+mid) → Vermelho 1 (mid)
            case 6:
                playerIndex = 0;
                break; // team1[0] - Azul Pick 1: Top
            case 7:
                playerIndex = 0;
                break; // team2[0] - Vermelho Pick 1: Top
            case 8:
                playerIndex = 1;
                break; // team2[1] - Vermelho Pick 2: Jungle
            case 9:
                playerIndex = 1;
                break; // team1[1] - Azul Pick 2: Jungle
            case 10:
                playerIndex = 2;
                break; // team1[2] - Azul Pick 3: Mid
            case 11:
                playerIndex = 2;
                break; // team2[2] - Vermelho Pick 3: Mid

            // ✅ FASE 3 - BANS (ações 12-15): ADC Azul → ADC Vermelho → Suporte Azul →
            // Suporte Vermelho
            case 12:
                playerIndex = 3;
                break; // team1[3] - ADC Azul
            case 13:
                playerIndex = 3;
                break; // team2[3] - ADC Vermelho
            case 14:
                playerIndex = 4;
                break; // team1[4] - Suporte Azul
            case 15:
                playerIndex = 4;
                break; // team2[4] - Suporte Vermelho

            // ✅ FASE 4 - PICKS (ações 16-19): Vermelho 2 (adc+sup) → Azul 2 (adc+sup - last
            // pick)
            case 16:
                playerIndex = 3;
                break; // team2[3] - Vermelho Pick 4: ADC
            case 17:
                playerIndex = 3;
                break; // team1[3] - Azul Pick 4: ADC
            case 18:
                playerIndex = 4;
                break; // team1[4] - Azul Pick 5: Support (LAST PICK)
            case 19:
                playerIndex = 4;
                break; // team2[4] - Vermelho Pick 5: Support

            default:
                log.warn("⚠️ [DraftFlow] Ação {} fora do range esperado (0-19)", actionIndex);
                return null;
        }

        // Retornar jogador do time correto
        List<String> currentTeamPlayers = team == 1 ? team1List : team2List;
        if (playerIndex < currentTeamPlayers.size()) {
            String selectedPlayer = currentTeamPlayers.get(playerIndex);
            log.info("✅ [DraftFlow] Ação {}: Team{} → Lane[{}] → Player: {}",
                    actionIndex, team, playerIndex, selectedPlayer);
            return selectedPlayer;
        }

        log.warn("⚠️ [DraftFlow] PlayerIndex {} fora do range para time {} (size: {})",
                playerIndex, team, currentTeamPlayers.size());
        return null;
    }

    /**
     * Executa ação automática para bot
     */
    private void handleBotAutoAction(DraftState st, String botName) {
        try {
            int actionIndex = st.getCurrentIndex();
            DraftAction currentAction = st.getActions().get(actionIndex);

            // Selecionar campeão aleatório que não foi banido ou escolhido
            String championId = selectRandomAvailableChampion(st);

            if (championId == null) {
                log.warn("⚠️ [DraftFlow] Nenhum campeão disponível para bot {}, pulando", botName);
                // Marcar como SKIPPED se não houver campeões disponíveis
                DraftAction skipped = new DraftAction(
                        currentAction.index(),
                        currentAction.type(),
                        currentAction.team(),
                        SKIPPED,
                        "SKIPPED", // ⭐ championName = "SKIPPED" também
                        botName);
                st.getActions().set(actionIndex, skipped);
                st.advance();
                st.markActionStart();
                persist(st.getMatchId(), st);
                broadcastUpdate(st, false);
                return;
            }

            log.info("🤖 [DraftFlow] Bot {} fazendo {} do campeão {}", botName, currentAction.type(), championId);

            // Processar ação do bot
            boolean success = processAction(st.getMatchId(), actionIndex, championId, botName);

            if (!success) {
                log.error("❌ [DraftFlow] Falha ao processar ação do bot {}", botName);
            }

        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao executar ação automática do bot", e);
        }
    }

    /**
     * Seleciona um campeão aleatório disponível (não banido, não escolhido)
     */
    private String selectRandomAvailableChampion(DraftState st) {
        // Lista de IDs de campeões (simplificada - em produção, buscar de Data Dragon)
        List<String> allChampions = generateChampionIds();

        // Filtrar campeões já usados (banidos ou escolhidos)
        Set<String> usedChampions = st.getActions().stream()
                .filter(a -> a.championId() != null && !SKIPPED.equals(a.championId()))
                .map(DraftAction::championId)
                .collect(java.util.stream.Collectors.toSet());

        List<String> availableChampions = allChampions.stream()
                .filter(id -> !usedChampions.contains(id))
                .collect(java.util.stream.Collectors.toList());

        if (availableChampions.isEmpty()) {
            return null;
        }

        // Selecionar aleatório
        int randomIndex = new java.util.Random().nextInt(availableChampions.size());
        return availableChampions.get(randomIndex);
    }

    /**
     * Gera lista de IDs de campeões (simplificada)
     */
    private List<String> generateChampionIds() {
        // ✅ Buscar IDs reais do Data Dragon dinamicamente
        List<DataDragonService.ChampionData> allChampions = dataDragonService.getAllChampions();
        return allChampions.stream()
                .map(DataDragonService.ChampionData::getKey)
                .filter(Objects::nonNull)
                .toList();
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

    /**
     * ✅ NOVO: Confirma draft final - TODOS os 10 jogadores devem confirmar
     * Quando todos confirmarem, inicia o jogo automaticamente
     * 
     * ⚡ AGORA USA REDIS para performance e resiliência:
     * - Operações atômicas (SADD, SCARD)
     * - Dados persistentes (sobrevive a reinícios)
     * - Distributed locks (zero race conditions)
     * - TTL automático (1 hora)
     */
    @Transactional
    public Map<String, Object> confirmFinalDraft(Long matchId, String playerId) {
        log.info("╔════════════════════════════════════════════════════════════════╗");
        log.info("║  ✅ [DraftFlow] CONFIRMAÇÃO FINAL (REDIS)                     ║");
        log.info("╚════════════════════════════════════════════════════════════════╝");
        log.info("🎯 Match ID: {}", matchId);
        log.info("👤 Player ID: {}", playerId);

        // ✅ 1. Buscar draft do Redis (com fallback MySQL)
        DraftState state = getDraftStateFromRedis(matchId);
        if (state == null) {
            log.warn("⚠️ [DraftFlow] Draft não encontrado no Redis/MySQL para match {}", matchId);
            throw new RuntimeException("Draft não encontrado");
        }

        // 2. Verificar se draft está completo
        if (state.getCurrentIndex() < state.getActions().size()) {
            log.warn("⚠️ [DraftFlow] Draft ainda não foi completado: {}/{} ações",
                    state.getCurrentIndex(), state.getActions().size());
            throw new RuntimeException("Draft ainda não está completo");
        }

        // 3. ⚡ REGISTRAR CONFIRMAÇÃO NO REDIS (com distributed lock)
        boolean confirmed = redisDraftFlow.confirmFinalDraft(matchId, playerId);

        if (!confirmed) {
            log.error("❌ [DraftFlow] Falha ao registrar confirmação no Redis");
            throw new RuntimeException("Erro ao registrar confirmação");
        }

        log.info("✅ [DraftFlow] Confirmação registrada no REDIS: {}", playerId);

        // 4. Contar total de jogadores
        int totalPlayers = state.getTeam1Players().size() + state.getTeam2Players().size();

        // 5. ⚡ BUSCAR CONFIRMADOS DO REDIS (operação O(1))
        Set<String> confirmations = redisDraftFlow.getConfirmedPlayers(matchId);
        int confirmedCount = confirmations.size();

        log.info("📊 [DraftFlow] Confirmações (REDIS): {}/{} jogadores", confirmedCount, totalPlayers);
        log.info("📋 [DraftFlow] Jogadores confirmados: {}", confirmations);

        // 6. Broadcast atualização de confirmações para todos
        broadcastConfirmationUpdate(matchId, confirmations, totalPlayers);

        // 7. ⚡ VERIFICAR NO REDIS SE TODOS CONFIRMARAM
        boolean allConfirmed = redisDraftFlow.allPlayersConfirmedFinal(matchId, totalPlayers);

        if (allConfirmed) {
            log.info("╔════════════════════════════════════════════════════════════════╗");
            log.info("║  🎮 [DraftFlow] TODOS OS 10 JOGADORES CONFIRMARAM! (REDIS)   ║");
            log.info("╚════════════════════════════════════════════════════════════════╝");

            // 8. Finalizar draft e iniciar jogo
            finalizeDraftAndStartGame(matchId, state);

            // 9. ⚡ LIMPAR DADOS DO REDIS
            redisDraftFlow.clearAllDraftData(matchId);

            // ✅ REMOVIDO: states.remove() - não há mais HashMap local
            log.info("🗑️ [DraftFlow] Dados do draft limpos do Redis");
        }

        // 11. Retornar resultado
        return Map.of(
                "success", true,
                "allConfirmed", allConfirmed,
                "confirmedCount", confirmedCount,
                "totalPlayers", totalPlayers);
    }

    /**
     * ✅ NOVO: Broadcast atualização de confirmações para os 10 jogadores da partida
     */
    private void broadcastConfirmationUpdate(Long matchId, Set<String> confirmations, int totalPlayers) {
        try {
            // Buscar DraftState para pegar lista de jogadores
            DraftState st = getDraftStateFromRedis(matchId);
            if (st == null) {
                log.warn("⚠️ [DraftFlow] DraftState não encontrado para matchId={}", matchId);
                return;
            }

            Map<String, Object> payload = Map.of(
                    "type", "draft_confirmation_update",
                    "matchId", matchId,
                    "confirmations", new ArrayList<>(confirmations),
                    "confirmedCount", confirmations.size(),
                    "totalPlayers", totalPlayers,
                    "allConfirmed", confirmations.size() >= totalPlayers);

            String json = mapper.writeValueAsString(payload);

            // ✅ CORREÇÃO: Enviar apenas para os 10 jogadores da partida
            sendToMatchPlayers(st, json);

            log.info("📡 [DraftFlow] Broadcast para 10 jogadores: {}/{} confirmaram", confirmations.size(),
                    totalPlayers);

        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao broadcast confirmação", e);
        }
    }

    /**
     * ✅ NOVO: Finaliza draft e inicia o jogo
     */
    @Transactional
    private void finalizeDraftAndStartGame(Long matchId, DraftState state) {
        try {
            log.info("🏁 [DraftFlow] Finalizando draft e iniciando jogo...");

            // 1. Atualizar status da partida para "game_ready"
            customMatchRepository.findById(matchId).ifPresent(cm -> {
                cm.setStatus("game_ready");
                customMatchRepository.save(cm);
                log.info("✅ [DraftFlow] Status atualizado: draft → game_ready");
            });

            // 2. Broadcast evento match_game_ready (compatibilidade)
            broadcastGameReady(matchId);

            // 3. Chamar GameInProgressService para iniciar jogo
            gameInProgressService.startGame(matchId);

            log.info("✅ [DraftFlow] Draft finalizado, jogo iniciado com sucesso!");

        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao finalizar draft", e);
            throw new RuntimeException("Erro ao finalizar draft: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ NOVO: Broadcast evento game_ready para os 10 jogadores
     */
    private void broadcastGameReady(Long matchId) {
        try {
            // Buscar DraftState para pegar lista de jogadores
            DraftState st = getDraftStateFromRedis(matchId);
            if (st == null) {
                log.warn("⚠️ [DraftFlow] DraftState não encontrado para matchId={}", matchId);
                return;
            }

            Map<String, Object> payload = Map.of(
                    "type", "match_game_ready",
                    "matchId", matchId,
                    "status", "game_ready",
                    "message", "Todos confirmaram! Jogo iniciando...");

            String json = mapper.writeValueAsString(payload);

            // ✅ CORREÇÃO: Enviar apenas para os 10 jogadores da partida
            sendToMatchPlayers(st, json);
            log.info("📡 [DraftFlow] Broadcast game_ready para 10 jogadores");

        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao broadcast game_ready", e);
        }
    }

    /**
     * ✅ NOVO: Cancela partida e notifica todos os jogadores
     */
    @Transactional
    public void cancelMatch(Long matchId) {
        try {
            log.info("❌ [DraftFlow] Cancelando partida: {}", matchId);

            // 1. Verificar se partida existe
            var match = customMatchRepository.findById(matchId)
                    .orElseThrow(() -> new RuntimeException("Partida não encontrada: " + matchId));

            log.info("📊 [DraftFlow] Partida encontrada - Status: {}", match.getStatus());

            // ✅ 1.5. NOVO: Buscar jogadores ANTES de deletar partida
            DraftState state = getDraftStateFromRedis(matchId);
            Set<String> allPlayers = new HashSet<>();
            if (state != null) {
                allPlayers.addAll(state.getTeam1Players());
                allPlayers.addAll(state.getTeam2Players());
                log.info("🎯 [DraftFlow] {} jogadores para limpar estados", allPlayers.size());
            } else {
                log.warn("⚠️ [DraftFlow] DraftState null, tentando extrair jogadores do MySQL");
                // Fallback: extrair do MySQL (team1_players/team2_players são CSV)
                String team1 = match.getTeam1PlayersJson();
                String team2 = match.getTeam2PlayersJson();
                if (team1 != null && !team1.isEmpty()) {
                    allPlayers.addAll(Arrays.asList(team1.split(",\\s*")));
                }
                if (team2 != null && !team2.isEmpty()) {
                    allPlayers.addAll(Arrays.asList(team2.split(",\\s*")));
                }
                log.info("🎯 [DraftFlow] {} jogadores extraídos do MySQL", allPlayers.size());
            }

            // ✅ 2. CRÍTICO: LIMPAR ESTADOS DOS JOGADORES **ANTES** DE DELETAR DO MYSQL!
            // Se deletarmos primeiro, o RedisPlayerMatch.clearPlayerMatch vai consultar
            // MySQL,
            // não vai encontrar a partida, e pode falhar na limpeza!

            log.info("🧹 [DraftFlow] Limpando estados de {} jogadores ANTES de deletar partida", allPlayers.size());

            // 2.1. Limpar PlayerState de TODOS os jogadores
            for (String playerName : allPlayers) {
                try {
                    playerStateService.setPlayerState(playerName,
                            br.com.lolmatchmaking.backend.service.lock.PlayerState.AVAILABLE);
                    log.info("✅ [DraftFlow] Estado de {} limpo para AVAILABLE", playerName);
                } catch (Exception e) {
                    log.error("❌ [DraftFlow] Erro ao limpar estado de {}: {}", playerName, e.getMessage());
                }
            }

            // 2.2. Limpar RedisPlayerMatch ownership
            // IMPORTANTE: Fazer ANTES de deletar do MySQL, pois clearPlayerMatch valida
            // contra MySQL!
            for (String playerName : allPlayers) {
                try {
                    redisPlayerMatch.clearPlayerMatch(playerName);
                    log.info("✅ [DraftFlow] Ownership de {} limpo", playerName);
                } catch (Exception e) {
                    log.error("❌ [DraftFlow] Erro ao limpar ownership de {}: {}", playerName, e.getMessage());
                }
            }

            // 3. Limpar Discord (mover jogadores de volta e deletar canais)
            discordService.deleteMatchChannels(matchId, true);
            log.info("🧹 [DraftFlow] Canais do Discord limpos e jogadores movidos de volta");

            // 4. Deletar partida do banco de dados (ÚLTIMO PASSO!)
            customMatchRepository.deleteById(matchId);
            log.info("🗑️ [DraftFlow] Partida deletada do banco de dados");

            // 6. ✅ REDIS ONLY: Limpar dados do Redis
            redisDraftFlow.clearAllDraftData(matchId);
            log.info("🧹 [DraftFlow] Dados limpos do Redis");

            // 7. Broadcast evento de cancelamento
            broadcastMatchCancelled(matchId);

            // ✅ 8. Limpar do Redis
            redisDraftFlow.clearDraftState(matchId);
            log.info("🗑️ [DraftFlow] Estado do draft limpo do Redis");

            log.info("✅ [DraftFlow] Partida cancelada com sucesso!");

        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao cancelar partida", e);
            throw new RuntimeException("Erro ao cancelar partida: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ NOVO: Broadcast evento de cancelamento para os 10 jogadores
     */
    private void broadcastMatchCancelled(Long matchId) {
        try {
            // Buscar DraftState para pegar lista de jogadores
            DraftState st = getDraftStateFromRedis(matchId);
            if (st == null) {
                log.warn("⚠️ [DraftFlow] DraftState não encontrado para matchId={} - broadcast para todos", matchId);
                // Fallback: broadcast global se não temos os jogadores
                Map<String, Object> payload = Map.of(
                        "type", "match_cancelled",
                        "matchId", matchId,
                        "message", "Partida cancelada pelo líder");
                String json = mapper.writeValueAsString(payload);
                sessionRegistry.all().forEach(ws -> {
                    try {
                        ws.sendMessage(new TextMessage(json));
                    } catch (Exception e) {
                        log.warn("⚠️ Erro ao enviar match_cancelled", e);
                    }
                });
                return;
            }

            Map<String, Object> payload = Map.of(
                    "type", "match_cancelled",
                    "matchId", matchId,
                    "message", "Partida cancelada pelo líder");

            String json = mapper.writeValueAsString(payload);

            // ✅ CORREÇÃO: Enviar apenas para os 10 jogadores da partida
            sendToMatchPlayers(st, json);
            log.info("📡 [DraftFlow] Broadcast match_cancelled para 10 jogadores");

        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao broadcast match_cancelled", e);
        }
    }

    /**
     * ✅ NOVO: Calcula o tipo de badge de lane para um jogador
     * 
     * @param assignedLane  Lane atribuída ao jogador
     * @param primaryLane   Lane primária preferida
     * @param secondaryLane Lane secundária preferida
     * @return "primary", "secondary" ou "autofill"
     */
    private static String calculateLaneBadge(String assignedLane, String primaryLane, String secondaryLane) {
        if (assignedLane == null || assignedLane.isEmpty()) {
            return "autofill";
        }

        String normalizedAssigned = normalizeLaneForBadge(assignedLane);
        String normalizedPrimary = normalizeLaneForBadge(primaryLane != null ? primaryLane : "");
        String normalizedSecondary = normalizeLaneForBadge(secondaryLane != null ? secondaryLane : "");

        if (normalizedAssigned.equals(normalizedPrimary)) {
            return "primary";
        } else if (normalizedAssigned.equals(normalizedSecondary)) {
            return "secondary";
        } else {
            return "autofill";
        }
    }

    /**
     * ✅ NOVO: Normaliza nome de lane para comparação de badge
     * 
     * @param lane Nome da lane
     * @return Lane normalizada (lowercase, adc -> bot)
     */
    private static String normalizeLaneForBadge(String lane) {
        if (lane == null)
            return "";
        String normalized = lane.toLowerCase().trim();
        return normalized.equals("adc") ? "bot" : normalized;
    }
}
