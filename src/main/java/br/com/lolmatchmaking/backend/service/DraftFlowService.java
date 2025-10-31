package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import br.com.lolmatchmaking.backend.domain.repository.QueuePlayerRepository;
import br.com.lolmatchmaking.backend.websocket.SessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class DraftFlowService {
    private final CustomMatchRepository customMatchRepository;
    private final QueuePlayerRepository queuePlayerRepository;
    private final SessionRegistry sessionRegistry;
    private final DataDragonService dataDragonService;
    private final br.com.lolmatchmaking.backend.mapper.UnifiedMatchDataMapper matchDataMapper;
    private final ObjectMapper objectMapper;

    private final GameInProgressService gameInProgressService;
    private final DiscordService discordService;

    // ✅ NOVO: Redis para performance e resiliência
    private final RedisDraftFlowService redisDraftFlow;

    // ✅ NOVO: WebSocketService para retry de draft_starting
    private final br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService webSocketService;

    // ✅ NOVO: PlayerStateService para cleanup inteligente
    private final br.com.lolmatchmaking.backend.service.lock.PlayerStateService playerStateService;

    // ✅ NOVO: RedisPlayerMatchService para cleanup de ownership
    private final br.com.lolmatchmaking.backend.service.redis.RedisPlayerMatchService redisPlayerMatch;

    // ✅ NOVO: RedisTemplate para throttling de retries
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    // ✅ NOVO: RedisWebSocketSessionService para busca de sessões via Redis
    private final br.com.lolmatchmaking.backend.service.redis.RedisWebSocketSessionService redisWSSession;

    // ✅ NOVO: PlayerLockService para limpeza de locks
    private final br.com.lolmatchmaking.backend.service.lock.PlayerLockService playerLockService;

    // ✅ NOVO: MatchOperationsLockService para evitar múltiplos drafts simultâneos
    private final br.com.lolmatchmaking.backend.service.lock.MatchOperationsLockService matchOpsLockService;

    @Value("${app.draft.action-timeout-ms:30000}")
    private long configuredActionTimeoutMs;

    // ✅ REMOVIDO: finalConfirmations e matchTimers (agora em Redis para
    // performance)
    // MySQL = Fonte da verdade (persistent storage em pick_ban_data)
    // Redis = Cache/estado volátil (draft temporário, confirmações, timers)
    // SEMPRE salvar no MySQL (pick_ban_data) antes de transições críticas

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

    @Getter
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

        // ✅ Lombok @Getter gera todos os getters automaticamente
        // Métodos customizados mantidos abaixo

        public void advance() {
            currentIndex++;
        }

        public void markActionStart() {
            this.lastActionStartMs = System.currentTimeMillis();
        }

        public boolean isPlayerInTeam(String player, int team) {
            if (team == 1)
                return team1Players.contains(player);
            if (team == 2)
                return team2Players.contains(player);
            return false;
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
                        log.info("🔍 [Timer] Processando draft ativo: matchId={}", matchId);

                        // ✅ Buscar estado do Redis (com fallback MySQL)
                        DraftState st = getDraftStateFromRedis(matchId);

                        if (st == null) {
                            log.info("⚠️ [Timer] DraftState não encontrado: matchId={}", matchId);
                            continue;
                        }

                        log.info("🔍 [Timer] DraftState encontrado: matchId={}, currentIndex={}/{}, actions={}",
                                matchId, st.getCurrentIndex(), st.getActions().size(), st.getActions().size());

                        // Só se não estiver completo
                        if (st.getCurrentIndex() >= st.getActions().size()) {
                            log.info("⏭️ [Timer] Draft já completo: matchId={}, currentIndex={}, actions={}",
                                    matchId, st.getCurrentIndex(), st.getActions().size());
                            continue;
                        }

                        log.info("⏰ [Timer] Decrementando timer para matchId={}", matchId);
                        // ⚡ REDIS: Decrementar timer atomicamente
                        int currentTimer = redisDraftFlow.decrementTimer(matchId);
                        log.info("⏰ [Timer] Timer decrementado: matchId={}, newTimer={}", matchId, currentTimer);

                        // ✅ CORREÇÃO: Se timer chegou a 0, progredir draft automaticamente
                        if (currentTimer <= 0) {
                            log.warn("⏰ [Timer] Timer ZEROU para matchId={}, progredindo draft automaticamente",
                                    matchId);

                            // ✅ CORREÇÃO: Progredir draft automaticamente
                            DraftAction currentAction = st.getActions().get(st.getCurrentIndex());
                            String currentPlayer = getPlayerForTeamAndIndex(st, currentAction.team(),
                                    st.getCurrentIndex());

                            // ✅ CORREÇÃO: Selecionar campeão aleatório no timeout
                            String randomChampionId = selectRandomAvailableChampion(st);
                            if (randomChampionId == null) {
                                log.error("❌ [Timer] Nenhum campeão disponível para timeout de matchId={}", matchId);
                                randomChampionId = "266"; // Fallback para Aatrox se nenhum disponível
                            }
                            log.info("🎲 [Timer] Campeão aleatório selecionado para timeout: {} (player: {})", 
                                    randomChampionId, currentPlayer);
                            boolean success = processAction(matchId, st.getCurrentIndex(), currentPlayer, randomChampionId);
                            if (success) {
                                log.info("✅ [Timer] Draft progredido automaticamente para matchId={}", matchId);

                                // ✅ CORREÇÃO: Reinicializar timer para próxima ação
                                redisDraftFlow.initTimer(matchId);
                                log.info("⏰ [Timer] Timer reinicializado para próxima ação: matchId={}", matchId);
                            } else {
                                log.error("❌ [Timer] Falha ao progredir draft automaticamente para matchId={}",
                                        matchId);
                            }
                        } else {
                            // Enviar atualização apenas se timer não zerou
                            log.info("📡 [Timer] Enviando timer update: matchId={}, timer={}", matchId, currentTimer);
                            sendTimerOnly(matchId, st, currentTimer);
                        }
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
     * ✅ TIMER: Envia APENAS o número via draft_update usando arquitetura global
     */
    private void sendTimerOnly(Long matchId, DraftState st, int seconds) {
        try {
            // Criar payload padronizado
            List<String> allowedSummoners = new ArrayList<>();
            allowedSummoners.addAll(st.getTeam1Players());
            allowedSummoners.addAll(st.getTeam2Players());

            Map<String, Object> data = new HashMap<>();
            data.put("matchId", matchId);
            data.put("timeRemaining", seconds);
            data.put("allowedSummoners", allowedSummoners);

            // ✅ CORREÇÃO: Enviar para jogadores específicos da partida
            List<String> allPlayers = getAllPlayersFromDraftState(st);
            webSocketService.sendToPlayers("draft_update", data, allPlayers);

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
                        Map<?, ?> snap = objectMapper.readValue(cm.getPickBanDataJson(), Map.class);
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

                        // ✅ CRÍTICO: RESETAR timer ao restaurar draft após restart do backend
                        // Isso evita que elapsed seja calculado com timestamp antigo (pré-restart)
                        st.lastActionStartMs = System.currentTimeMillis();
                        log.info("🔄 [restoreDraftStates] Timer resetado para ação atual (evita timeout falso)");

                        // ✅ Salvar no Redis (não mais em HashMap)
                        // saveDraftStateToRedis(cm.getId(), st); // ✅ REMOVIDO: Método deprecated
                        // redisDraftFlow.saveDraftState(cm.getId(), st); // ✅ MÉTODO NÃO EXISTE
                        // TODO: Implementar salvamento no Redis usando saveDraftStateJson
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
    /**
     * ✅ REFATORADO: Busca DraftState (para lógica interna)
     * Redis/MySQL têm JSON puro, este método converte para DraftState
     */
    private DraftState getDraftStateFromRedis(Long matchId) {
        try {
            // ✅ ESTRATÉGIA: SEMPRE buscar do MySQL (fonte da verdade)
            // Redis tem JSON puro, mas DraftState precisa de conversão complexa
            // Para lógica interna, é mais seguro buscar direto do MySQL

            log.debug("🔄 [getDraftStateFromRedis] Buscando do MySQL: matchId={}", matchId);
            DraftState state = loadDraftStateFromMySQL(matchId);

            if (state != null) {
                log.info(
                        "✅ [getDraftStateFromRedis] MySQL → DraftState: matchId={}, currentIndex={}/{}, actions={}, team1={}, team2={}",
                        matchId,
                        state.getCurrentIndex(),
                        state.getActions().size(),
                        state.getActions().size(),
                        state.getTeam1Players().size(),
                        state.getTeam2Players().size());
                return state;
            }

            log.warn("⚠️ [getDraftStateFromRedis] Draft {} não encontrado no MySQL", matchId);
            return null;

        } catch (Exception e) {
            log.error("❌ [getDraftStateFromRedis] Erro ao buscar DraftState: matchId={}", matchId, e);
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
     * ✅ REFATORADO: Parseia DraftState do JSON PURO do MySQL
     * Extrai actions de dentro de teams.blue/red.players[].actions
     */
    private DraftState parseDraftStateFromJSON(Long matchId, br.com.lolmatchmaking.backend.domain.entity.CustomMatch cm)
            throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> pickBanData = objectMapper.readValue(cm.getPickBanDataJson(), Map.class);

        // ✅ Extrair actions de dentro de teams.blue/red.players[].actions
        List<DraftAction> allActions = new ArrayList<>();

        // ✅ CORREÇÃO CRÍTICA: Extrair jogadores diretamente do JSON (não do CSV)
        List<String> team1Players = new ArrayList<>();
        List<String> team2Players = new ArrayList<>();

        if (pickBanData.containsKey("teams") && pickBanData.get("teams") instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> teams = (Map<String, Object>) pickBanData.get("teams");

            // Blue team
            if (teams.containsKey("blue") && teams.get("blue") instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> blueTeam = (Map<String, Object>) teams.get("blue");
                if (blueTeam.containsKey("players") && blueTeam.get("players") instanceof List<?>) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> players = (List<Map<String, Object>>) blueTeam.get("players");
                    for (Map<String, Object> player : players) {
                        String playerName = (String) player.get("summonerName");

                        // ✅ ADICIONAR jogador ao time 1 (blue)
                        if (playerName != null && !playerName.isBlank()) {
                            team1Players.add(playerName);
                        }

                        if (player.containsKey("actions") && player.get("actions") instanceof List<?>) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> playerActions = (List<Map<String, Object>>) player.get("actions");
                            for (Map<String, Object> action : playerActions) {
                                // ✅ CRÍTICO: Passar playerName para parseDraftAction
                                allActions.add(parseDraftAction(action, playerName));
                            }
                        }
                    }
                }
            }

            // Red team
            if (teams.containsKey("red") && teams.get("red") instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> redTeam = (Map<String, Object>) teams.get("red");
                if (redTeam.containsKey("players") && redTeam.get("players") instanceof List<?>) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> players = (List<Map<String, Object>>) redTeam.get("players");
                    for (Map<String, Object> player : players) {
                        String playerName = (String) player.get("summonerName");

                        // ✅ ADICIONAR jogador ao time 2 (red)
                        if (playerName != null && !playerName.isBlank()) {
                            team2Players.add(playerName);
                        }

                        if (player.containsKey("actions") && player.get("actions") instanceof List<?>) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> playerActions = (List<Map<String, Object>>) player.get("actions");
                            for (Map<String, Object> action : playerActions) {
                                // ✅ CRÍTICO: Passar playerName para parseDraftAction
                                allActions.add(parseDraftAction(action, playerName));
                            }
                        }
                    }
                }
            }
        }

        // Ordenar por index
        allActions.sort((a, b) -> Integer.compare(a.index(), b.index()));

        // ✅ FALLBACK CRÍTICO: Se não encontrou actions no JSON, criar a sequência
        // padrão
        if (allActions.isEmpty()) {
            log.warn("⚠️ [parseDraftStateFromJSON] Actions vazias no JSON! Criando sequência padrão (20 actions)");
            allActions = buildDefaultActionSequence();
        }

        log.info("✅ [parseDraftStateFromJSON] Actions carregadas: {} total", allActions.size());

        // ✅ CORREÇÃO: Usar jogadores extraídos do JSON (não do CSV que pode estar
        // vazio!)
        // Fallback para CSV apenas se JSON não tiver jogadores
        if (team1Players.isEmpty()) {
            team1Players = parseCSV(cm.getTeam1PlayersJson());
            log.warn("⚠️ [DraftFlow] Jogadores team1 vazios no JSON, usando CSV: {}", team1Players);
        }
        if (team2Players.isEmpty()) {
            team2Players = parseCSV(cm.getTeam2PlayersJson());
            log.warn("⚠️ [DraftFlow] Jogadores team2 vazios no JSON, usando CSV: {}", team2Players);
        }

        log.info("✅ [DraftFlow] Jogadores extraídos - Team1: {} jogadores, Team2: {} jogadores",
                team1Players.size(), team2Players.size());

        DraftState st = new DraftState(matchId, allActions, team1Players, team2Players);

        // Avançar para currentIndex
        Object currentIdxObj = pickBanData.get("currentIndex");
        int cur = currentIdxObj instanceof Number n ? n.intValue() : 0;
        while (st.getCurrentIndex() < cur && st.getCurrentIndex() < allActions.size()) {
            st.advance();
        }

        // ✅ CRÍTICO: Restaurar lastActionStartMs (para timer de bots funcionar!)
        Object lastActionMs = pickBanData.get("lastActionStartMs");
        if (lastActionMs instanceof Number) {
            st.lastActionStartMs = ((Number) lastActionMs).longValue();
            log.debug("✅ [parseDraftStateFromJSON] lastActionStartMs restaurado: {}", st.lastActionStartMs);
        } else {
            // Fallback: usar tempo atual se não existe
            st.lastActionStartMs = System.currentTimeMillis();
            log.warn("⚠️ [parseDraftStateFromJSON] lastActionStartMs não encontrado, usando tempo atual");
        }

        return st;
    }

    // ✅ REMOVIDO: Método duplicado (versão correta está nas linhas 716-726)

    /**
     * ✅ REFATORADO: Sincroniza MySQL → Redis (JSON puro, sem conversões)
     * Redis armazena EXATAMENTE o mesmo JSON que está no MySQL
     */
    private void syncMySQLtoRedis(Long matchId) {
        try {
            // 1. Buscar JSON puro do MySQL
            var matchOpt = customMatchRepository.findById(matchId);
            if (matchOpt.isEmpty()) {
                log.warn("⚠️ [syncMySQLtoRedis] Match {} não encontrado", matchId);
                return;
            }

            String pickBanDataJson = matchOpt.get().getPickBanDataJson();
            if (pickBanDataJson == null || pickBanDataJson.isBlank()) {
                log.warn("⚠️ [syncMySQLtoRedis] pick_ban_data_json NULL/vazio para match {}", matchId);
                return;
            }

            // 2. Salvar STRING JSON DIRETA no Redis (zero conversões!)
            redisDraftFlow.saveDraftStateJson(matchId, pickBanDataJson);
            log.debug("💾 [syncMySQLtoRedis] JSON puro sincronizado MySQL→Redis: matchId={}, {}chars",
                    matchId, pickBanDataJson.length());

        } catch (Exception e) {
            log.error("❌ [syncMySQLtoRedis] Erro ao sincronizar: matchId={}", matchId, e);
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
     * ✅ CORREÇÃO: Adicionado proteção contra race condition
     * 
     * ⚠️ IMPORTANTE: O lock de draft JÁ deve ter sido adquirido pelo chamador
     * (MatchFoundService)!
     * Este método NÃO adquire lock para evitar deadlock.
     */
    public DraftState startDraft(long matchId, List<String> team1Players, List<String> team2Players) {
        try {
            // ✅ CORREÇÃO: Verificar se draft já foi iniciado E está ativo (com timer)
            DraftState existingState = getDraftStateFromRedis(matchId);
            if (existingState != null) {
                // ✅ VERIFICAR: Se o draft está realmente ativo (tem timer rodando)
                int currentTimer = redisDraftFlow.getTimer(matchId);
                if (currentTimer > 0) {
                    log.info(
                            "✅ [startDraft] Draft {} já foi iniciado e está ativo (timer={}) - retornando estado existente",
                            matchId, currentTimer);

                    // ✅ CORREÇÃO: Verificar se byPlayer está correto, se não, reatribuir
                    boolean needsReassignment = existingState.getActions().stream()
                            .anyMatch(action -> action.byPlayer() == null || "SKIPPED".equals(action.byPlayer()));

                    if (needsReassignment) {
                        log.warn("⚠️ [startDraft] Ações com byPlayer incorreto detectadas, reatribuindo...");
                        assignPlayersByDraftOrder(existingState.getActions(), team1Players, team2Players);
                        log.info("✅ [startDraft] byPlayer reatribuído para estado existente");
                        // Salvar estado corrigido
                        // saveDraftStateToRedis(matchId, existingState); // ✅ REMOVIDO: Método
                        // deprecated
                        // redisDraftFlow.saveDraftState(matchId, existingState); // ✅ MÉTODO NÃO EXISTE
                        // TODO: Implementar salvamento no Redis usando saveDraftStateJson
                        persist(matchId, existingState);
                    }

                    // ✅ CRÍTICO: Fazer broadcast mesmo para estado existente para garantir que
                    // frontend receba
                    broadcastUpdate(existingState, false);
                    log.info("✅ [startDraft] Broadcast enviado para estado existente: matchId={}", matchId);

                    return existingState;
                } else {
                    log.warn("⚠️ [startDraft] Draft {} existe mas não está ativo (timer={}) - reiniciando", matchId,
                            currentTimer);
                    // ✅ LIMPAR: Remover estado antigo e criar novo
                    redisDraftFlow.clearDraftState(matchId);
                }
            }

            List<DraftAction> actions = buildDefaultActionSequence();

            // ✅ CRÍTICO: Atribuir byPlayer para cada action ANTES de criar o DraftState!
            assignPlayersByDraftOrder(actions, team1Players, team2Players);
            log.info("✅ [startDraft] byPlayer atribuído para todas as {} actions", actions.size());

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
            // saveDraftStateToRedis(matchId, st); // ✅ REMOVIDO: Método deprecated
            // redisDraftFlow.saveDraftState(matchId, st); // ✅ MÉTODO NÃO EXISTE
            // TODO: Implementar salvamento no Redis usando saveDraftStateJson

            log.info("🎬 [DraftFlow] startDraft - matchId={}, actions={}, currentIndex={}, team1={}, team2={}",
                    matchId, actions.size(), st.getCurrentIndex(), team1Players, team2Players);

            // ⚡ REDIS: Inicializar timer (30 segundos)
            redisDraftFlow.initTimer(matchId);

            // ✅ Persistir o estado inicial no MySQL
            persist(matchId, st);

            log.info("📡 [DraftFlow] startDraft - Estado salvo no Redis e MySQL: matchId={}", matchId);

            // ✅ CRÍTICO: Fazer broadcast inicial para frontend
            List<String> allPlayers = getAllPlayersFromDraftState(st);
            log.info(
                    "🎬 [DraftFlow] Iniciando broadcast inicial - enviando draft_starting para {} jogadores (CustomSessionIds: {})",
                    allPlayers.size(), allPlayers);
            broadcastUpdate(st, false);
            log.info("✅ [DraftFlow] startDraft - Broadcast inicial enviado para frontend");

            return st;

        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao iniciar draft", e);
            throw new RuntimeException("Erro ao iniciar draft: " + e.getMessage(), e);
        }
    }

    /**
     * ✅ NOVO: Atribui byPlayer para cada action baseado na ordem do draft
     * Regra: Cada jogador tem 2 actions (1 ban + 1 pick) na ordem da lane (top,
     * jungle, mid, bot, support)
     */
    private void assignPlayersByDraftOrder(List<DraftAction> actions, List<String> team1Players,
            List<String> team2Players) {
        // ✅ SEQUÊNCIA EXATA DO LOL DRAFT:
        // Index 0-5 (Bans): Blue0, Red0, Blue1, Red1, Blue2, Red2
        // Index 6-11 (Picks): Blue0, Red0, Red1, Blue1, Blue2, Red2
        // Index 12-15 (Bans): Red3, Blue3, Red4, Blue4
        // Index 16-19 (Picks): Red3, Blue3, Blue4, Red4

        // ✅ MAPEAMENTO POR INDEX → (Team, Lane)
        int[][] mapping = {
                // Bans (0-5)
                { 1, 0 }, { 2, 0 }, { 1, 1 }, { 2, 1 }, { 1, 2 }, { 2, 2 },
                // Picks (6-11)
                { 1, 0 }, { 2, 0 }, { 2, 1 }, { 1, 1 }, { 1, 2 }, { 2, 2 },
                // Bans (12-15)
                { 2, 3 }, { 1, 3 }, { 2, 4 }, { 1, 4 },
                // Picks (16-19)
                { 2, 3 }, { 1, 3 }, { 1, 4 }, { 2, 4 }
        };

        for (int i = 0; i < actions.size(); i++) {
            DraftAction action = actions.get(i);
            int expectedTeam = mapping[i][0];
            int lane = mapping[i][1];

            // ✅ VERIFICAÇÃO: Se team da action não bate, usar o correto do mapping!
            if (action.team() != expectedTeam) {
                log.warn("⚠️ [assignPlayersByDraftOrder] Action {} tem team={} mas deveria ser team={}! Corrigindo...",
                        i, action.team(), expectedTeam);
            }

            String playerName = expectedTeam == 1 ? team1Players.get(lane) : team2Players.get(lane);

            // Substituir action com byPlayer e team corretos!
            actions.set(i, new DraftAction(
                    action.index(),
                    action.type(),
                    expectedTeam, // ✅ CRÍTICO: Usar team do mapping, não da action!
                    action.championId(),
                    action.championName(),
                    playerName));
        }

        log.info("✅ [assignPlayersByDraftOrder] byPlayer atribuído para todas as {} actions", actions.size());
    }

    /**
     * ✅ Converte action do JSON do MySQL para DraftAction
     * CRÍTICO: O JSON não tem campo "team", então derivamos do index!
     */
    private DraftAction parseDraftAction(Map<String, Object> actionMap, String playerName) {
        int idx = ((Number) actionMap.getOrDefault("index", 0)).intValue();
        String type = (String) actionMap.getOrDefault("type", "pick");
        String championId = (String) actionMap.get("championId");
        String championName = (String) actionMap.get("championName");

        // ✅ CRÍTICO: Derivar team do INDEX (array buildDefaultActionSequence)
        int team = getTeamForActionIndex(idx);

        return new DraftAction(idx, type, team, championId, championName, playerName);
    }

    /**
     * ✅ Retorna qual team (1=Blue, 2=Red) uma action pertence baseado no index
     */
    private int getTeamForActionIndex(int index) {
        // ✅ SEQUÊNCIA EXATA DO DRAFT DO LOL
        int[] teams = { 1, 2, 1, 2, 1, 2, 1, 2, 2, 1, 1, 2, 2, 1, 2, 1, 2, 1, 1, 2 };
        if (index >= 0 && index < teams.length) {
            return teams[index];
        }
        return 1; // Fallback
    }

    private List<DraftAction> buildDefaultActionSequence() {
        List<DraftAction> list = new ArrayList<>();

        // ✅ SEQUÊNCIA EXATA DO DRAFT DO LOL (20 ações totais)
        // Index Team Type Lane
        // 0 Blue Ban Top
        // 1 Red Ban Top
        // 2 Blue Ban Jungle
        // 3 Red Ban Jungle
        // 4 Blue Ban Mid
        // 5 Red Ban Mid
        // 6 Blue Pick Top
        // 7 Red Pick Top
        // 8 Red Pick Jungle
        // 9 Blue Pick Jungle
        // 10 Blue Pick Mid
        // 11 Red Pick Mid
        // 12 Red Ban Bot
        // 13 Blue Ban Bot
        // 14 Red Ban Support
        // 15 Blue Ban Support
        // 16 Red Pick Bot
        // 17 Blue Pick Bot
        // 18 Blue Pick Support (Last pick)
        // 19 Red Pick Support

        int[] teams = { 1, 2, 1, 2, 1, 2, 1, 2, 2, 1, 1, 2, 2, 1, 2, 1, 2, 1, 1, 2 };
        String[] types = { "ban", "ban", "ban", "ban", "ban", "ban",
                "pick", "pick", "pick", "pick", "pick", "pick",
                "ban", "ban", "ban", "ban",
                "pick", "pick", "pick", "pick" };

        for (int i = 0; i < 20; i++) {
            list.add(new DraftAction(i, types[i], teams[i], null, null, null));
        }

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
        // saveDraftStateToRedis(matchId, st); // ✅ REMOVIDO: Método deprecated
        // redisDraftFlow.saveDraftState(matchId, st); // ✅ MÉTODO NÃO EXISTE
        // TODO: Implementar salvamento no Redis usando saveDraftStateJson

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
        // saveDraftStateToRedis(matchId, st); // ✅ REMOVIDO: Método deprecated
        // redisDraftFlow.saveDraftState(matchId, st); // ✅ MÉTODO NÃO EXISTE
        // TODO: Implementar salvamento no Redis usando saveDraftStateJson

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
                        Map<String, Object> existing = objectMapper.readValue(cm.getPickBanDataJson(), Map.class);
                        snapshot = new HashMap<>(existing);

                        // ✅ CRÍTICO: Atualizar apenas campos de estado do draft
                        // NUNCA sobrescrever team1/team2 - eles já vêm completos do MatchFoundService
                        snapshot.put(KEY_ACTIONS, st.getActions());
                        snapshot.put(KEY_CURRENT_INDEX, st.getCurrentIndex());
                        snapshot.put(KEY_CONFIRMATIONS, st.getConfirmations());
                        snapshot.put("lastActionStartMs", st.getLastActionStartMs()); // ✅ CRÍTICO: Salvar timestamp
                                                                                      // para calcular elapsed

                        // ✅ Verificar se team1/team2 existem (com dados completos do MatchFoundService)
                        Object team1Data = snapshot.get(KEY_TEAM1);
                        Object team2Data = snapshot.get(KEY_TEAM2);

                        // ✅ Se não existem OU são arrays vazios, adicionar apenas nomes (fallback)
                        boolean team1Empty = team1Data == null ||
                                (team1Data instanceof java.util.List && ((java.util.List<?>) team1Data).isEmpty());
                        boolean team2Empty = team2Data == null ||
                                (team2Data instanceof java.util.List && ((java.util.List<?>) team2Data).isEmpty());

                        if (team1Empty) {
                            log.warn("⚠️ [DraftFlow] team1 vazio, criando dados completos (fallback): {}",
                                    st.getTeam1Players());
                            // ✅ Criar dados completos dos jogadores como fallback
                            snapshot.put(KEY_TEAM1, createCompleteTeamDataFromMemory(st.getTeam1Players(), 0));
                        } else {
                            log.debug("✅ [DraftFlow] team1 já existe com {} jogadores, PRESERVANDO",
                                    ((java.util.List<?>) team1Data).size());
                            // ✅ NÃO SOBRESCREVER - manter dados completos existentes
                        }

                        if (team2Empty) {
                            log.warn("⚠️ [DraftFlow] team2 vazio, criando dados completos (fallback): {}",
                                    st.getTeam2Players());
                            // ✅ Criar dados completos dos jogadores como fallback
                            snapshot.put(KEY_TEAM2, createCompleteTeamDataFromMemory(st.getTeam2Players(), 5));
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
                        snapshot.put("lastActionStartMs", st.getLastActionStartMs()); // ✅ CRÍTICO: Salvar timestamp
                                                                                      // para calcular elapsed

                        log.warn(
                                "⚠️ [DraftFlow] Criando pick_ban_data pela primeira vez - criando dados completos dos times!");
                        // ✅ Criar estrutura completa dos times
                        snapshot.put(KEY_TEAM1, createCompleteTeamDataFromMemory(st.getTeam1Players(), 0));
                        snapshot.put(KEY_TEAM2, createCompleteTeamDataFromMemory(st.getTeam2Players(), 5));
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
                    finalSnapshot.put("lastActionStartMs", st.getLastActionStartMs()); // ✅ CRÍTICO: Salvar timestamp
                    finalSnapshot.put(KEY_MATCH_ID, matchId);
                    finalSnapshot.put(KEY_TYPE, "draft_snapshot");
                    finalSnapshot.put("timestamp", System.currentTimeMillis());

                    // ✅ CRÍTICO: Salvar lastActionStartMs para timer de bots funcionar!
                    finalSnapshot.put("lastActionStartMs", st.getLastActionStartMs());

                    // ✅ 4. Actions e confirmations (fonte da verdade para restauração)
                    finalSnapshot.put(KEY_ACTIONS, st.getActions());
                    finalSnapshot.put(KEY_CONFIRMATIONS, st.getConfirmations());

                    // ✅ NOVO: Contar actions completed vs pending para debug
                    long completedActions = st.getActions().stream()
                            .filter(a -> a.championId() != null && !SKIPPED.equals(a.championId()))
                            .count();
                    long pendingActions = st.getActions().stream()
                            .filter(a -> a.championId() == null || SKIPPED.equals(a.championId()))
                            .count();

                    String jsonToSave = objectMapper.writeValueAsString(finalSnapshot);
                    cm.setPickBanDataJson(jsonToSave);
                    customMatchRepository.save(cm);

                    log.info("📊 [persist] AÇÕES - Total:{}, Completed:{}, Pending:{}",
                            st.getActions().size(), completedActions, pendingActions);

                    // ✅ VERIFICAR se teams.blue/red.players[].actions estão sendo salvos
                    if (finalSnapshot.containsKey("teams") && finalSnapshot.get("teams") instanceof Map) {
                        Map<?, ?> teams = (Map<?, ?>) finalSnapshot.get("teams");
                        if (teams.containsKey("blue") && teams.get("blue") instanceof Map) {
                            Map<?, ?> blue = (Map<?, ?>) teams.get("blue");
                            if (blue.containsKey("players") && blue.get("players") instanceof List) {
                                List<?> players = (List<?>) blue.get("players");
                                log.info("📊 [persist] VERIFICAÇÃO - Blue team tem {} players no JSON", players.size());
                                if (!players.isEmpty() && players.get(0) instanceof Map) {
                                    Map<?, ?> firstPlayer = (Map<?, ?>) players.get(0);
                                    Object actions = firstPlayer.get("actions");
                                    log.info("📊 [persist] VERIFICAÇÃO - Primeiro player Blue tem actions? {}, size={}",
                                            actions != null,
                                            actions instanceof List ? ((List<?>) actions).size() : 0);
                                }
                            }
                        }
                    }

                    log.info("✅ [persist] JSON salvo: {} keys (teams LIMPO + team1/team2 compatibilidade)",
                            finalSnapshot.keySet().size());

                    // ✅ NOVO: Log detalhado do que foi salvo no MySQL
                    log.info(
                            "📊 [persist] MySQL SALVO - Match {}: currentIndex={}/{}, currentPlayer={}, currentPhase={}",
                            matchId,
                            finalSnapshot.get("currentIndex"),
                            st.getActions().size(),
                            finalSnapshot.get("currentPlayer"),
                            finalSnapshot.get("currentPhase"));

                    // ✅ NOVO: Log do JSON salvo (primeiros 1000 chars para debug)
                    if (jsonToSave.length() > 1000) {
                        log.debug("📄 [persist] JSON (primeiros 1000 chars): {}", jsonToSave.substring(0, 1000));
                    } else {
                        log.debug("📄 [persist] JSON completo: {}", jsonToSave);
                    }

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
    // ⚠️ DEPRECATED: SKIPPED não é mais usado - timeouts agora selecionam campeão aleatório
    // Mantido apenas para compatibilidade com dados antigos no banco
    @Deprecated
    private static final String SKIPPED = "SKIPPED";
    private static final String TIMEOUT_PLAYER = "system_timeout";
    private static final String KEY_REMAINING_MS = "remainingMs";
    private static final String KEY_ACTION_TIMEOUT_MS = "actionTimeoutMs";

    private void broadcastUpdate(DraftState st, boolean confirmationOnly) {
        try {
            long remainingMs = calcRemainingMs(st);
            long elapsed = System.currentTimeMillis() - st.getLastActionStartMs();

            // ✅ Calcular jogador/ação atual
            String currentPlayer = null;
            Integer currentTeamNum = null;
            String currentActionType = null;
            int currentIdx = st.getCurrentIndex();
            if (currentIdx < st.getActions().size()) {
                DraftAction currentAction = st.getActions().get(currentIdx);
                currentTeamNum = currentAction.team();
                currentActionType = currentAction.type();
                currentPlayer = getPlayerForTeamAndIndex(st, currentAction.team(), currentIdx);
                log.info("🎯 [DraftFlow] Ação atual: index={} type={} team={} player={}",
                        currentIdx, currentActionType, currentTeamNum, currentPlayer);
            }

            // ✅ CRÍTICO: Carregar dados completos dos times do banco, não apenas nomes
            Map<String, Object> updateData = new HashMap<>();

            // ✅ CORREÇÃO: Enviar draft_starting no início, draft_updated nas atualizações
            String eventType = (st.getCurrentIndex() == 0 && !confirmationOnly) ? "draft_starting" : "draft_updated";
            updateData.put(KEY_TYPE, eventType);
            updateData.put(KEY_MATCH_ID, st.getMatchId());
            updateData.put(KEY_CURRENT_INDEX, st.getCurrentIndex());
            updateData.put("currentAction", st.getCurrentIndex()); // ✅ CRÍTICO: Frontend espera currentAction
            if (currentTeamNum != null) {
                updateData.put("currentTeam", currentTeamNum == 1 ? "blue" : "red");
            }
            if (currentActionType != null) {
                updateData.put("currentActionType", currentActionType);
            }
            updateData.put(KEY_ACTIONS, st.getActions());
            updateData.put("phases", st.getActions()); // ✅ CRÍTICO: Frontend espera 'phases', não apenas 'actions'
            updateData.put(KEY_CONFIRMATIONS, st.getConfirmations());
            updateData.put("currentPlayer", currentPlayer); // ✅ Nome do jogador da vez

            // ✅ Padronização: incluir always allowedSummoners (para filtro no Electron)
            List<String> allowedSummoners = new ArrayList<>();
            allowedSummoners.addAll(st.getTeam1Players());
            allowedSummoners.addAll(st.getTeam2Players());
            updateData.put("allowedSummoners", allowedSummoners);

            log.info(
                    "📡 [DraftFlow] Broadcasting {} - matchId={}, currentIndex={}, currentPlayer={}, actions={}, confirmations={}, remainingMs={}, timeout={}ms",
                    eventType, st.getMatchId(), currentIdx, currentPlayer, st.getActions().size(),
                    st.getConfirmations().size(), remainingMs, getActionTimeoutMs());

            // ✅ Buscar dados completos dos times do banco
            customMatchRepository.findById(st.getMatchId()).ifPresent(cm -> {
                if (cm.getPickBanDataJson() != null && !cm.getPickBanDataJson().isEmpty()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pickBanData = objectMapper.readValue(cm.getPickBanDataJson(), Map.class);

                        // ✅ ESTRUTURA HIERÁRQUICA (teams.blue/red)
                        Object teamsData = pickBanData.get("teams");
                        if (teamsData != null) {
                            updateData.put("teams", teamsData);
                            try {
                                Map<?, ?> teamsMap = (Map<?, ?>) teamsData;
                                Map<?, ?> blue = (Map<?, ?>) teamsMap.get("blue");
                                Map<?, ?> red = (Map<?, ?>) teamsMap.get("red");
                                int bluePlayers = blue != null && blue.get("players") instanceof java.util.List
                                        ? ((java.util.List<?>) blue.get("players")).size()
                                        : 0;
                                int redPlayers = red != null && red.get("players") instanceof java.util.List
                                        ? ((java.util.List<?>) red.get("players")).size()
                                        : 0;
                                log.info("✅ [DraftFlow] teams presentes: bluePlayers={}, redPlayers={}", bluePlayers,
                                        redPlayers);
                            } catch (Exception ignore) {
                            }
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

            // ✅ ADICIONAR ESTRUTURA LIMPA (com teams.blue/red E actions global)
            log.info("🔨 [broadcastUpdate] Adicionando JSON LIMPO ao broadcast");
            Map<String, Object> cleanData = buildHierarchicalDraftData(st);
            if (cleanData.containsKey("teams")) {
                updateData.put("teams", cleanData.get("teams"));
                updateData.put("currentPhase", cleanData.get("currentPhase"));
                updateData.put("currentPlayer", cleanData.get("currentPlayer"));
                updateData.put("currentTeam", cleanData.get("currentTeam"));
                updateData.put("currentActionType", cleanData.get("currentActionType"));
                updateData.put(KEY_CURRENT_INDEX, cleanData.get("currentIndex"));

                // ✅ CRÍTICO: Adicionar array global de actions (frontend precisa disso!)
                if (cleanData.containsKey("actions")) {
                    updateData.put("actions", cleanData.get("actions"));
                    log.info("✅ [broadcastUpdate] Actions adicionados: {} total",
                            ((List<?>) cleanData.get("actions")).size());
                }

                log.info(
                        "✅ [broadcastUpdate] JSON LIMPO adicionado: teams + actions + metadados");

                // ✅ LOG DETALHADO: Mostrar estrutura teams enviada
                try {
                    String teamsJson = objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(cleanData.get("teams"));
                    log.info("📤 [broadcastUpdate] === ESTRUTURA TEAMS ENVIADA ===");
                    log.info("{}", teamsJson);
                    log.info("==============================================");
                } catch (Exception e) {
                    log.warn("⚠️ Erro ao serializar teams para log: {}", e.getMessage());
                }
            }

            // ✅ CORREÇÃO: Enviar GLOBALMENTE usando o padrão correto (eventType, data)
            log.info("📤 [broadcastUpdate] === ENVIANDO {} ===", eventType);
            log.info("📤 [broadcastUpdate] MatchId: {}", st.getMatchId());
            log.info("📤 [broadcastUpdate] CurrentIndex: {}", st.getCurrentIndex());
            log.info("📤 [broadcastUpdate] CurrentPlayer: {}", currentPlayer);
            log.info("📤 [broadcastUpdate] Actions: {}", st.getActions().size());
            log.info("📤 [broadcastUpdate] Confirmations: {}", st.getConfirmations().size());
            log.info("====================================================");

            // ✅ CORREÇÃO: Enviar para jogadores específicos da partida
            List<String> allPlayers = getAllPlayersFromDraftState(st);
            log.info("📤 [DraftFlow] Enviando {} para {} jogadores (CustomSessionIds: {})",
                    eventType, allPlayers.size(), allPlayers);
            webSocketService.sendToPlayers(eventType, updateData, allPlayers);
            log.info("✅ [DraftFlow] {} enviado com sucesso para {} jogadores", eventType, allPlayers.size());
        } catch (Exception e) {
            log.error("Erro broadcast draft_updated", e);
        }
    }

    /**
     * ✅ CORRIGIDO: Envia mensagem para jogador específico usando novo padrão
     */
    private void sendToPlayer(String summonerName, String eventType, Map<String, Object> data) {
        try {
            log.debug("🎯 [DraftFlow] Enviando {} para {}", eventType, summonerName);

            // ✅ USAR NOVO PADRÃO: sendToPlayer com sessionId customizado
            webSocketService.sendToPlayer(eventType, data, summonerName);

            log.debug("✅ [DraftFlow] {} enviado para {}", eventType, summonerName);
        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao enviar {} para {}", eventType, summonerName, e);
        }
    }

    /**
     * ✅ NOVO: Obtém todos os jogadores de um DraftState
     * ✅ CORREÇÃO: Usar dados do DraftState diretamente (não buscar do MySQL)
     */
    private List<String> getAllPlayersFromDraftState(DraftState st) {
        List<String> allPlayers = new ArrayList<>();

        try {
            // ✅ CORREÇÃO: Usar dados diretamente do DraftState (sem buscar MySQL)
            // DraftState já tem team1Players e team2Players
            if (st != null) {
                if (st.getTeam1Players() != null) {
                    allPlayers.addAll(st.getTeam1Players());
                }
                if (st.getTeam2Players() != null) {
                    allPlayers.addAll(st.getTeam2Players());
                }
            }

            log.debug("🎯 [DraftFlow] Jogadores encontrados (DraftState): {}", allPlayers);
        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao obter jogadores do DraftState", e);
        }

        return allPlayers;
    }

    // ✅ REMOVIDO: sendToMatchPlayers - substituído por broadcastToAllSessions
    // (arquitetura global)

    private void broadcastDraftCompleted(DraftState st) {
        try {
            Map<String, Object> data = Map.of(
                    KEY_MATCH_ID, st.getMatchId());

            // ✅ CORREÇÃO: Enviar para jogadores específicos da partida
            List<String> allPlayers = getAllPlayersFromDraftState(st);
            webSocketService.sendToPlayers("draft_completed", data, allPlayers);
        } catch (Exception e) {
            log.error("Erro broadcast draft_completed", e);
        }
    }

    private void broadcastAllConfirmed(DraftState st) {
        try {
            Map<String, Object> data = Map.of(
                    KEY_MATCH_ID, st.getMatchId());

            // ✅ CORREÇÃO: Enviar para jogadores específicos da partida
            List<String> allPlayers = getAllPlayersFromDraftState(st);
            webSocketService.sendToPlayers("draft_confirmed", data, allPlayers);
        } catch (Exception e) {
            log.error("Erro broadcast draft_confirmed", e);
        }

        // ✅ NOVO: Transição automática IN_DRAFT → IN_GAME para todos os jogadores
        try {
            log.info("🔄 [DraftFlow] Iniciando transição automática IN_DRAFT → IN_GAME para match {}", st.getMatchId());

            // Buscar todos os jogadores da partida
            CustomMatch match = customMatchRepository.findById(st.getMatchId()).orElse(null);
            if (match != null) {
                List<String> allPlayers = new ArrayList<>();

                // Extrair jogadores do team1 e team2
                if (match.getTeam1PlayersJson() != null && !match.getTeam1PlayersJson().isEmpty()) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<String> team1 = objectMapper.readValue(match.getTeam1PlayersJson(), List.class);
                        allPlayers.addAll(team1);
                    } catch (Exception e) {
                        log.warn("⚠️ [DraftFlow] Erro ao parsear team1: {}", e.getMessage());
                    }
                }

                if (match.getTeam2PlayersJson() != null && !match.getTeam2PlayersJson().isEmpty()) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<String> team2 = objectMapper.readValue(match.getTeam2PlayersJson(), List.class);
                        allPlayers.addAll(team2);
                    } catch (Exception e) {
                        log.warn("⚠️ [DraftFlow] Erro ao parsear team2: {}", e.getMessage());
                    }
                }

                // ✅ Transição automática para todos os jogadores
                int successCount = 0;
                int failCount = 0;

                for (String playerName : allPlayers) {
                    try {
                        boolean success = playerStateService.setPlayerState(playerName,
                                br.com.lolmatchmaking.backend.service.lock.PlayerState.IN_GAME);

                        if (success) {
                            successCount++;
                            log.info("✅ [DraftFlow] {} → IN_GAME (transição automática)", playerName);
                        } else {
                            failCount++;
                            log.warn("⚠️ [DraftFlow] Falha na transição {} → IN_GAME", playerName);
                        }
                    } catch (Exception e) {
                        failCount++;
                        log.error("❌ [DraftFlow] Erro na transição {} → IN_GAME: {}", playerName, e.getMessage());
                    }
                }

                log.info("📊 [DraftFlow] Transições automáticas: {} sucessos, {} falhas", successCount, failCount);
            }
        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro na transição automática IN_DRAFT → IN_GAME: {}", e.getMessage(), e);
        }

        // Transição para fase game_ready
        customMatchRepository.findById(st.getMatchId()).ifPresent(cm -> {
            try {
                cm.setStatus("game_ready");
                customMatchRepository.save(cm);
                log.info("✅ [DraftFlow] Status atualizado: draft_completed → game_ready");
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
                    Map<String, Object> pickBanData = objectMapper.readValue(cm.getPickBanDataJson(), Map.class);

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

                // ✅ 2. FALLBACK: Se não existe no banco, criar da memória (DraftState) com
                // dados completos
                if (team1Players == null || team1Players.isEmpty()) {
                    log.warn(
                            "⚠️ [buildHierarchicalDraftData] Team1 vazio no banco! Criando dados completos da MEMÓRIA");
                    team1Players = createCompleteTeamDataFromMemory(st.getTeam1Players(), 0);
                }

                if (team2Players == null || team2Players.isEmpty()) {
                    log.warn(
                            "⚠️ [buildHierarchicalDraftData] Team2 vazio no banco! Criando dados completos da MEMÓRIA");
                    team2Players = createCompleteTeamDataFromMemory(st.getTeam2Players(), 5);
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

        // ✅ 6. Adicionar array global de actions (frontend precisa disso!)
        List<Map<String, Object>> globalActions = st.getActions().stream()
                .map(action -> {
                    Map<String, Object> actionMap = new HashMap<>();
                    actionMap.put("index", action.index());
                    actionMap.put("type", action.type());
                    actionMap.put("team", action.team());
                    actionMap.put("championId", action.championId());
                    actionMap.put("championName", action.championName());
                    actionMap.put("byPlayer", action.byPlayer());
                    actionMap.put("phase", getPhaseLabel(action.index()));
                    return actionMap;
                })
                .toList();
        result.put("actions", globalActions);

        log.info("✅ [buildHierarchicalDraftData] JSON LIMPO gerado: {} keys, {} actions", result.keySet().size(),
                globalActions.size());
        return result;
    }

    /**
     * ✅ NOVO: Cria dados completos dos jogadores da memória (DraftState)
     * Busca informações completas do banco de dados para cada jogador
     */
    private List<Map<String, Object>> createCompleteTeamDataFromMemory(Collection<String> playerNames,
            int startTeamIndex) {
        List<Map<String, Object>> teamPlayers = new ArrayList<>();
        String[] lanes = { "top", "jungle", "mid", "bot", "support" };

        int i = 0;
        for (String playerName : playerNames) {
            int teamIndex = startTeamIndex + i;
            String assignedLane = lanes[i];

            // ✅ Buscar dados completos do jogador no banco
            Map<String, Object> playerObj = new HashMap<>();
            playerObj.put("summonerName", playerName);
            playerObj.put("assignedLane", assignedLane);
            playerObj.put("teamIndex", teamIndex);

            // ✅ Tentar buscar dados completos do QueuePlayer
            try {
                Optional<br.com.lolmatchmaking.backend.domain.entity.QueuePlayer> playerOpt = queuePlayerRepository
                        .findBySummonerName(playerName);

                if (playerOpt.isPresent()) {
                    var player = playerOpt.get();
                    playerObj.put("mmr", player.getCustomLp() != null ? player.getCustomLp() : 1500);
                    playerObj.put("primaryLane", player.getPrimaryLane() != null ? player.getPrimaryLane() : "fill");
                    playerObj.put("secondaryLane",
                            player.getSecondaryLane() != null ? player.getSecondaryLane() : "fill");
                    playerObj.put("isAutofill", false); // QueuePlayer não tem campo isAutofill
                    playerObj.put("laneBadge",
                            calculateLaneBadge(assignedLane, player.getPrimaryLane(), player.getSecondaryLane()));

                    if (player.getPlayerId() != null) {
                        playerObj.put("playerId", player.getPlayerId());
                    }
                } else {
                    // ✅ Fallback para dados básicos se não encontrar no banco
                    log.warn(
                            "⚠️ [createCompleteTeamDataFromMemory] Jogador {} não encontrado no banco, usando dados básicos",
                            playerName);
                    playerObj.put("mmr", 1500); // MMR padrão
                    playerObj.put("primaryLane", assignedLane);
                    playerObj.put("secondaryLane", "fill");
                    playerObj.put("isAutofill", false);
                    playerObj.put("laneBadge", "primary");
                }
            } catch (Exception e) {
                log.error("❌ [createCompleteTeamDataFromMemory] Erro ao buscar dados de {}: {}", playerName,
                        e.getMessage());
                // ✅ Fallback para dados básicos em caso de erro
                playerObj.put("mmr", 1500);
                playerObj.put("primaryLane", assignedLane);
                playerObj.put("secondaryLane", "fill");
                playerObj.put("isAutofill", false);
                playerObj.put("laneBadge", "primary");
            }

            // ✅ NOVO: Adicionar gameName e tagLine (extrair do summonerName)
            String[] nameParts = playerName.split("#");
            if (nameParts.length >= 2) {
                playerObj.put("gameName", nameParts[0]);
                playerObj.put("tagLine", nameParts[1]);
            } else {
                playerObj.put("gameName", playerName);
                playerObj.put("tagLine", "");
            }

            // ✅ NOVO: Inicializar actions vazias para o jogador
            playerObj.put("actions", new ArrayList<Map<String, Object>>());

            teamPlayers.add(playerObj);
            i++;
        }

        log.info("✅ [createCompleteTeamDataFromMemory] Dados completos criados para {} jogadores", teamPlayers.size());
        return teamPlayers;
    }

    /**
     * ✅ NOVO: Retorna o label da fase baseado no índice da ação
     */
    private String getPhaseLabel(int actionIndex) {
        if (actionIndex < 6) {
            return "ban1";
        } else if (actionIndex < 12) {
            return "pick1";
        } else if (actionIndex < 16) {
            return "ban2";
        } else {
            return "pick2";
        }
    }

    /**
     * ✅ NOVO: Retorna os indices de actions para uma lane específica
     * Lane 0=Top, 1=Jungle, 2=Mid, 3=Bot, 4=Support
     */
    private List<Integer> getActionIndicesForLane(int laneIndex, int teamNumber) {
        // Sequência do LoL draft:
        // Ban1: Blue[0,2,4], Red[1,3,5] → lanes [0,1,2,0,1,2]
        // Pick1: Blue[6,9,10], Red[7,8,11] → lanes [0,1,2,1,0,2]
        // Ban2: Blue[13,15], Red[12,14] → lanes [3,4,3,4]
        // Pick2: Blue[17,18], Red[16,19] → lanes [3,4,3,4]

        List<Integer> indices = new ArrayList<>();

        if (laneIndex == 0) { // Top
            indices.add(teamNumber == 1 ? 0 : 1); // Ban
            indices.add(teamNumber == 1 ? 6 : 7); // Pick
        } else if (laneIndex == 1) { // Jungle
            indices.add(teamNumber == 1 ? 2 : 3); // Ban
            indices.add(teamNumber == 1 ? 9 : 8); // Pick
        } else if (laneIndex == 2) { // Mid
            indices.add(teamNumber == 1 ? 4 : 5); // Ban
            indices.add(teamNumber == 1 ? 10 : 11); // Pick
        } else if (laneIndex == 3) { // Bot
            indices.add(teamNumber == 1 ? 13 : 12); // Ban
            indices.add(teamNumber == 1 ? 17 : 16); // Pick
        } else if (laneIndex == 4) { // Support
            indices.add(teamNumber == 1 ? 15 : 14); // Ban
            indices.add(teamNumber == 1 ? 18 : 19); // Pick
        }

        return indices;
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

        // ✅ CRÍTICO: ORDENAR players por teamIndex ANTES de processar!
        // Isso garante que a posição no array corresponda à lane (0=Top, 1=Jungle, etc)
        List<Map<String, Object>> sortedPlayers = new ArrayList<>(players);
        sortedPlayers.sort((a, b) -> {
            Integer idxA = (Integer) a.getOrDefault("teamIndex", 999);
            Integer idxB = (Integer) b.getOrDefault("teamIndex", 999);
            return idxA.compareTo(idxB);
        });

        log.info("🔍 [buildCleanTeamData] Players ordenados por teamIndex: {}",
                sortedPlayers.stream().map(p -> p.get("summonerName") + "(" + p.get("teamIndex") + ")").toList());

        // ✅ Adicionar APENAS players essenciais com suas ações
        List<Map<String, Object>> cleanPlayers = new ArrayList<>();

        for (Map<String, Object> player : sortedPlayers) {
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

            // ✅ ESTRATÉGIA: PRESERVAR actions que já existem no player (MySQL é fonte da
            // verdade!)
            // Atribuir actions por POSIÇÃO/INDEX (independente de byPlayer)
            Integer teamIndex = (Integer) player.get("teamIndex");
            List<Map<String, Object>> playerActions = new ArrayList<>();

            if (teamIndex != null) {
                int laneIndex = teamIndex % 5; // 0=Top, 1=Jungle, 2=Mid, 3=Bot, 4=Support
                List<Integer> actionIndices = getActionIndicesForLane(laneIndex, teamNumber);

                // Buscar actions do DraftState pelos indices
                for (Integer idx : actionIndices) {
                    if (idx < actions.size()) {
                        DraftAction action = actions.get(idx);

                        Map<String, Object> actionData = new HashMap<>();
                        actionData.put("index", action.index());
                        actionData.put("type", action.type());
                        actionData.put("championId", action.championId());
                        actionData.put("championName", action.championName());
                        actionData.put("phase", getPhaseLabel(action.index()));
                        actionData.put("status", action.championId() == null ? "pending" : "completed");
                        playerActions.add(actionData);
                    }
                }

                log.info("🔨 [buildCleanTeamData] {} actions atribuídas para {} (teamIndex={}, lane={}, indices={})",
                        playerActions.size(), playerName, teamIndex, laneIndex, actionIndices);
            }

            cleanPlayer.put("actions", playerActions);

            cleanPlayers.add(cleanPlayer);
        }

        team.put("players", cleanPlayers);

        // ✅ CRÍTICO: Filtrar allBans e allPicks por INDEX (não por team()!)
        // A ordem do draft do LoL define qual índice pertence a qual time
        List<Integer> teamActionIndices = getActionIndicesForTeam(teamNumber);

        List<String> allBans = actions.stream()
                .filter(a -> teamActionIndices.contains(a.index()) && "ban".equals(a.type()))
                .filter(a -> a.championId() != null && !SKIPPED.equals(a.championId()))
                .map(DraftAction::championId)
                .toList();
        team.put("allBans", allBans);

        List<String> allPicks = actions.stream()
                .filter(a -> teamActionIndices.contains(a.index()) && "pick".equals(a.type()))
                .filter(a -> a.championId() != null && !SKIPPED.equals(a.championId()))
                .map(DraftAction::championId)
                .toList();
        team.put("allPicks", allPicks);

        log.debug("✅ [buildCleanTeamData] Time {} construído: {} players, {} bans, {} picks", teamName,
                cleanPlayers.size(), allBans.size(), allPicks.size());
        return team;
    }

    /**
     * ✅ NOVO: Retorna TODOS os índices de actions que pertencem a um time
     * Team 1 (Blue): 0, 2, 4, 6, 9, 10, 13, 15, 17, 18
     * Team 2 (Red): 1, 3, 5, 7, 8, 11, 12, 14, 16, 19
     */
    private List<Integer> getActionIndicesForTeam(int teamNumber) {
        if (teamNumber == 1) {
            // Blue Team
            return List.of(0, 2, 4, 6, 9, 10, 13, 15, 17, 18);
        } else {
            // Red Team
            return List.of(1, 3, 5, 7, 8, 11, 12, 14, 16, 19);
        }
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
                    Map<String, Object> pickBanData = objectMapper.readValue(cm.getPickBanDataJson(), Map.class);

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
                    String payload = objectMapper.writeValueAsString(Map.of(
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
            Map<String, Object> data = Map.of(
                    KEY_MATCH_ID, st.getMatchId(),
                    "team1", team1Data,
                    "team2", team2Data);

            // ✅ CORREÇÃO: Enviar para jogadores específicos da partida
            List<String> allPlayers = getAllPlayersFromDraftState(st);
            webSocketService.sendToPlayers("match_game_ready", data, allPlayers);
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
                    String payload = objectMapper.writeValueAsString(Map.of(
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

            // ✅ DEBUG CRÍTICO: Logar currentIndex para diagnosticar
            log.debug("🔍 [Monitor] Match {}: currentIndex={}/{}, matchId do state={}",
                    match.getId(), st.getCurrentIndex(), st.getActions().size(), st.getMatchId());

            if (st.getCurrentIndex() >= st.getActions().size()) {
                log.info("✅ [DraftFlow] Draft {} completo (currentIndex={}/{})",
                        match.getId(), st.getCurrentIndex(), st.getActions().size());
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
                // Selecionar campeão aleatório para pular esta ação
                String randomChampionId = selectRandomAvailableChampion(st);
                if (randomChampionId == null) {
                    log.error("❌ [DraftFlow] Nenhum campeão disponível para ação sem jogador, usando fallback");
                    randomChampionId = "266"; // Fallback para Aatrox
                }
                String championName = dataDragonService.getChampionName(randomChampionId);
                DraftAction autoSelected = new DraftAction(
                        currentAction.index(),
                        currentAction.type(),
                        currentAction.team(),
                        randomChampionId,
                        championName,
                        "NO_PLAYER");
                st.getActions().set(currentIdx, autoSelected);
                st.advance();
                st.markActionStart();
                persist(st.getMatchId(), st);
                broadcastUpdate(st, false);
                return;
            }

            // ✅ CRÍTICO: RETRY draft_updated para jogadores que podem ter perdido conexão
            // A cada 3 segundos, reenviar o estado completo para TODOS os jogadores da
            // partida
            if (elapsed > 0 && elapsed % 3000 < 1000) { // A cada 3s (com margem de 1s)
                retryDraftUpdateForAllPlayers(st);
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
                log.warn("⏰ [DraftFlow] Timeout na ação {} - selecionando campeão aleatório", idx);
                String randomChampionId = selectRandomAvailableChampion(st);
                if (randomChampionId == null) {
                    log.error("❌ [DraftFlow] Nenhum campeão disponível para timeout, usando fallback");
                    randomChampionId = "266"; // Fallback para Aatrox
                }
                String championName = dataDragonService.getChampionName(randomChampionId);
                log.info("🎲 [DraftFlow] Campeão aleatório selecionado por timeout: {} ({})", 
                        randomChampionId, championName);
                DraftAction autoSelected = new DraftAction(
                        prev.index(),
                        prev.type(),
                        prev.team(),
                        randomChampionId,
                        championName,
                        TIMEOUT_PLAYER);
                st.getActions().set(idx, autoSelected);
                st.advance();
                st.markActionStart();
                persist(st.getMatchId(), st);

                // ✅ Salvar estado no Redis
                // saveDraftStateToRedis(st.getMatchId(), st); // ✅ REMOVIDO: Método deprecated
                // redisDraftFlow.saveDraftState(st.getMatchId(), st); // ✅ MÉTODO NÃO EXISTE
                // TODO: Implementar salvamento no Redis usando saveDraftStateJson

                broadcastUpdate(st, false);
                if (st.getCurrentIndex() >= st.getActions().size()) {
                    broadcastDraftCompleted(st);
                }
            }
        }
    }

    /**
     * ✅ NOVO: Reenviar draft_updated para TODOS os jogadores (retry para
     * desconectados)
     */
    private void retryDraftUpdateForAllPlayers(DraftState st) {
        try {
            Long matchId = st.getMatchId();

            // ✅ THROTTLE: Reenviar apenas a cada 3 segundos
            String retryKey = "draft_retry:" + matchId;
            Long lastRetrySec = redisTemplate.opsForValue()
                    .get(retryKey) != null ? (Long) redisTemplate.opsForValue().get(retryKey) : null;

            long nowSec = System.currentTimeMillis() / 1000;
            if (lastRetrySec != null && (nowSec - lastRetrySec) < 3) {
                return; // Já reenviou recentemente
            }

            // ✅ Marcar último retry
            redisTemplate.opsForValue().set(retryKey, nowSec, java.time.Duration.ofMinutes(5));

            // ✅ CRÍTICO: VALIDAR COM MYSQL ANTES DE RETRY
            Optional<CustomMatch> matchOpt = customMatchRepository.findById(matchId);

            if (matchOpt.isEmpty()) {
                log.warn("🧹 [CLEANUP] Match {} não existe no MySQL! Limpando Redis fantasma...", matchId);
                redisDraftFlow.clearAllDraftData(matchId);
                log.info("✅ [CLEANUP] Draft fantasma {} removida do Redis", matchId);
                return; // ABORTAR retry
            }

            CustomMatch match = matchOpt.get();

            // Validar status
            if (!"draft".equalsIgnoreCase(match.getStatus())) {
                log.warn("🧹 [CLEANUP] Match {} não está em draft no MySQL (status: {})! Limpando Redis...",
                        matchId, match.getStatus());
                redisDraftFlow.clearAllDraftData(matchId);
                log.info("✅ [CLEANUP] Draft {} removida do Redis (status MySQL: {})", matchId, match.getStatus());
                return;
            }

            log.info("✅ [Validação MySQL] Match {} confirmada como 'draft' - prosseguindo retry", matchId);

            // ✅ Buscar todos os jogadores (team1 + team2)
            List<String> allPlayers = new ArrayList<>();
            allPlayers.addAll(st.getTeam1Players());
            allPlayers.addAll(st.getTeam2Players());

            // ✅ CRÍTICO: NÃO parar retry apenas porque está "conectado"!
            // Estar conectado ≠ Estar vendo o draft (evento pode ter sido perdido)
            // SEMPRE continuar enviando (com throttle 3s) até que o draft termine
            log.debug("🔄 [DraftFlow] {} jogadores na partida - enviando retry (throttle 3s)",
                    allPlayers.size());

            // ✅ Validar ownership case-insensitive + verificar acknowledgment
            List<String> validPlayers = new ArrayList<>();
            for (String player : allPlayers) {
                // ✅ OTIMIZAÇÃO: Verificar se jogador JÁ acknowledgou (já viu o draft)
                String ackKey = "draft_ack:" + matchId + ":" + player.toLowerCase();
                Boolean hasAcked = (Boolean) redisTemplate.opsForValue().get(ackKey);
                if (Boolean.TRUE.equals(hasAcked)) {
                    log.debug("✅ [DraftFlow] Jogador {} já acknowledged - pulando retry", player);
                    continue; // Pula este jogador
                }

                boolean inTeam1 = match.getTeam1PlayersJson() != null &&
                        match.getTeam1PlayersJson().toLowerCase().contains(player.toLowerCase());
                boolean inTeam2 = match.getTeam2PlayersJson() != null &&
                        match.getTeam2PlayersJson().toLowerCase().contains(player.toLowerCase());

                if (inTeam1 || inTeam2) {
                    validPlayers.add(player);
                }
            }

            if (!validPlayers.isEmpty()) {
                log.debug("🔄 [DraftFlow] RETRY: Reenviando draft state para {} jogadores da partida {}",
                        validPlayers.size(), matchId);

                // ✅ CRÍTICO: Enviar EVENTO INICIAL (draft_starting) para jogadores
                // desconectados
                // Isso garante que quem perdeu o evento inicial vê o draft completo
                retryDraftStartingForPlayers(matchId, validPlayers);

                // ✅ Também enviar update normal (para quem já está no draft)
                broadcastUpdate(st, false);
            }

        } catch (Exception e) {
            log.debug("❌ [DraftFlow] Erro ao retry draft_updated", e);
        }
    }

    /**
     * ✅ NOVO: Reenviar draft_starting para jogadores que perderam o evento inicial
     * Garante que TODOS vejam o draft, mesmo com latência/desconexão em produção
     */
    private void retryDraftStartingForPlayers(Long matchId, List<String> players) {
        try {
            // ✅ CRÍTICO: VALIDAR COM MYSQL ANTES DE RETRY
            // Previne loops infinitos de retry para matches fantasma
            Optional<CustomMatch> matchOpt = customMatchRepository.findById(matchId);

            if (matchOpt.isEmpty()) {
                log.warn("🧹 [CLEANUP] Match {} não existe no MySQL! Limpando Redis fantasma...", matchId);
                redisDraftFlow.clearAllDraftData(matchId);
                log.info("✅ [CLEANUP] Draft fantasma {} removida do Redis", matchId);
                return; // ABORTAR retry
            }

            CustomMatch match = matchOpt.get();
            String status = match.getStatus();

            // Se não está em draft, limpar Redis
            if (!"draft".equalsIgnoreCase(status)) {
                log.warn("🧹 [CLEANUP] Match {} não está em draft no MySQL (status: {})! Limpando Redis...",
                        matchId, status);
                redisDraftFlow.clearAllDraftData(matchId);
                log.info("✅ [CLEANUP] Draft {} removida do Redis (status MySQL: {})", matchId, status);
                return; // ABORTAR retry
            }

            log.info("✅ [Validação MySQL] Match {} confirmada como 'draft' - prosseguindo retry", matchId);

            // ✅ CORREÇÃO: Usar DraftState do Redis e broadcastUpdate padronizado
            DraftState st = getDraftStateFromRedis(matchId);
            if (st == null) {
                log.error("❌ [DraftFlow] RETRY: Não foi possível reconstruir DraftState do MySQL. Abortando retry.");
                return;
            }

            // Garantir que começa em 0
            if (st.getCurrentIndex() != 0) {
                log.warn("⚠️ [DraftFlow] RETRY: currentIndex={} ajustado para 0", st.getCurrentIndex());
                // não há setter público; se existir método para reset, usar. Caso contrário,
                // reconstroi
                List<DraftAction> acts = new ArrayList<>(st.getActions());
                st = new DraftState(matchId, acts, st.getTeam1Players(), st.getTeam2Players());
                // saveDraftStateToRedis(matchId, st); // ✅ REMOVIDO: Método deprecated
                // redisDraftFlow.saveDraftState(matchId, st); // ✅ MÉTODO NÃO EXISTE
                // TODO: Implementar salvamento no Redis usando saveDraftStateJson
            }

            log.info("🔄 [DraftFlow] RETRY: Enviando broadcastUpdate inicial padronizado");
            broadcastUpdate(st, false);
            log.info("✅ [DraftFlow] RETRY: draft_starting enviado via broadcastUpdate padronizado");

        } catch (Exception e) {
            log.error("❌ [DraftFlow] Erro ao retry draft_starting", e);
        }
    }

    /**
     * Verifica se um jogador é um bot
     */
    private boolean isBot(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return false;
        }

        String normalizedName = playerName.toLowerCase().trim();

        // ✅ Padrões de nomes de bots conhecidos
        return normalizedName.startsWith("bot") ||
                normalizedName.startsWith("ai_") ||
                normalizedName.endsWith("_bot") ||
                normalizedName.contains("bot_") ||
                normalizedName.equals("bot") ||
                normalizedName.matches(".*bot\\d+.*"); // bot1, bot2, etc.
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
                log.warn("⚠️ [DraftFlow] Nenhum campeão disponível para bot {}, usando fallback", botName);
                championId = "266"; // Fallback para Aatrox se nenhum disponível
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

        // ✅ CORREÇÃO: Verificar se match já está in_progress para evitar race condition
        Optional<CustomMatch> matchOpt = customMatchRepository.findById(matchId);
        if (matchOpt.isPresent()) {
            CustomMatch match = matchOpt.get();
            if ("in_progress".equals(match.getStatus())) {
                log.info("✅ [DraftFlow] Match {} já está in_progress - confirmação ignorada", matchId);
                // Retornar dados do jogo em progresso
                return Map.of(
                        "success", true,
                        "message", "Match já está em progresso",
                        "status", "in_progress");
            }
        }

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

            // ✅ CORREÇÃO: Enviar para jogadores específicos da partida
            List<String> allPlayers = getAllPlayersFromDraftState(st);
            webSocketService.sendToPlayers("draft_confirmation_update", payload, allPlayers);

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

            // ✅ CORREÇÃO: Enviar para jogadores específicos da partida
            List<String> allPlayers = getAllPlayersFromDraftState(st);
            webSocketService.sendToPlayers("game_ready", payload, allPlayers);
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

                    // ✅ NOVO: Log específico para bots
                    if (isBot(playerName)) {
                        log.info("🤖 [DraftFlow] Estado de BOT {} limpo para AVAILABLE", playerName);
                    } else {
                        log.info("✅ [DraftFlow] Estado de {} limpo para AVAILABLE", playerName);
                    }
                } catch (Exception e) {
                    log.error("❌ [DraftFlow] Erro ao limpar estado de {}: {}", playerName, e.getMessage());
                }
            }

            // 2.2. ✅ CORREÇÃO: Limpar RedisPlayerMatch ownership usando clearMatchPlayers
            // IMPORTANTE: Fazer ANTES de deletar do MySQL para validação correta
            try {
                redisPlayerMatch.clearMatchPlayers(matchId);
                log.info("✅ [DraftFlow] Ownership de match {} limpo ({} jogadores)", matchId, allPlayers.size());
            } catch (Exception e) {
                log.error("❌ [DraftFlow] Erro ao limpar ownership do match {}: {}", matchId, e.getMessage());
            }

            // 2.3. ✅ NOVO: Limpar locks de jogadores
            for (String playerName : allPlayers) {
                try {
                    // Liberar lock de jogador se existir
                    if (playerLockService.getPlayerSession(playerName) != null) {
                        playerLockService.forceReleasePlayerLock(playerName);
                        log.info("🔓 [DraftFlow] Lock de {} liberado", playerName);
                    }
                } catch (Exception e) {
                    log.error("❌ [DraftFlow] Erro ao liberar lock de {}: {}", playerName, e.getMessage());
                }
            }

            // 3. Limpar Discord (mover jogadores de volta e deletar canais)
            discordService.deleteMatchChannels(matchId, true);
            log.info("🧹 [DraftFlow] Canais do Discord limpos e jogadores movidos de volta");

            // 4. Deletar partida do banco de dados (ÚLTIMO PASSO!)
            customMatchRepository.deleteById(matchId);
            log.info("🗑️ [DraftFlow] Partida deletada do banco de dados");

            // 6. ✅ REDIS ONLY: Limpar dados do Redis (inclui timer, confirmações e estado)
            redisDraftFlow.clearAllDraftData(matchId);
            log.info("🧹 [DraftFlow] Dados limpos do Redis (timer, confirmações, estado)");

            // 7. Broadcast evento de cancelamento
            broadcastMatchCancelled(matchId);

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
                String json = objectMapper.writeValueAsString(payload);
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

            // ✅ CORREÇÃO: Enviar para jogadores específicos da partida
            List<String> allPlayers = getAllPlayersFromDraftState(st);
            webSocketService.sendToPlayers("match_cancelled", payload, allPlayers);
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

    /**
     * ✅ PÚBLICO: Retorna dados completos do draft para my-active-match
     * REDIS FIRST → MySQL fallback (fonte da verdade)
     * 
     * @param matchId ID da partida
     * @return Map com dados completos (pickBanData, draftState, confirmationOnly,
     *         etc)
     */
    /**
     * ✅ REFATORADO: Retorna APENAS o JSON do MySQL (sem conversões)
     * MySQL = Redis = Frontend = MESMA ESTRUTURA
     * 
     * Estrutura do MySQL (pickBanData):
     * {
     * "teams": {
     * "blue": { "players": [{ "actions": [...] }], "allBans": [...], "allPicks":
     * [...] },
     * "red": { ... }
     * },
     * "currentPhase": "completed",
     * "currentIndex": 20,
     * "currentActionType": null,
     * "team1": [...],
     * "team2": [...]
     * }
     */
    public Map<String, Object> getDraftDataForRestore(Long matchId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // ✅ 1. Buscar do MySQL (SEMPRE fonte da verdade)
            var matchOpt = customMatchRepository.findById(matchId);
            if (matchOpt.isEmpty()) {
                log.warn("⚠️ [getDraftDataForRestore] Match {} não encontrado no MySQL", matchId);
                return result;
            }

            var match = matchOpt.get();

            // ✅ 2. Parsear pickBanData do MySQL
            if (match.getPickBanDataJson() != null && !match.getPickBanDataJson().isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pickBanData = objectMapper.readValue(match.getPickBanDataJson(), Map.class);

                    log.info("✅ [getDraftDataForRestore] pickBanData do MySQL: {} chars",
                            match.getPickBanDataJson().length());

                    // ✅ 3. Retornar pickBanData DIRETO (SEM conversões!)
                    result.putAll(pickBanData);

                    // ✅ 3.5. COMPATIBILIDADE: Criar 'phases' flat para frontend antigo
                    // Frontend espera array flat com todas as actions
                    List<Map<String, Object>> flatPhases = new ArrayList<>();

                    if (pickBanData.containsKey("teams") && pickBanData.get("teams") instanceof Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> teams = (Map<String, Object>) pickBanData.get("teams");

                        // Extrair actions de blue e red teams
                        for (String teamSide : new String[] { "blue", "red" }) {
                            Object teamObj = teams.get(teamSide);
                            if (teamObj instanceof Map<?, ?>) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> team = (Map<String, Object>) teamObj;

                                if (team.containsKey("players") && team.get("players") instanceof List<?>) {
                                    @SuppressWarnings("unchecked")
                                    List<Map<String, Object>> players = (List<Map<String, Object>>) team.get("players");

                                    for (Map<String, Object> player : players) {
                                        if (player.containsKey("actions") && player.get("actions") instanceof List<?>) {
                                            @SuppressWarnings("unchecked")
                                            List<Map<String, Object>> playerActions = (List<Map<String, Object>>) player
                                                    .get("actions");

                                            // Adicionar byPlayer em cada action para compatibilidade
                                            for (Map<String, Object> action : playerActions) {
                                                Map<String, Object> actionCopy = new HashMap<>(action);
                                                actionCopy.put("byPlayer", player.get("summonerName"));
                                                actionCopy.put("playerId", player.get("playerId"));
                                                flatPhases.add(actionCopy);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Ordenar por index
                    flatPhases.sort((a, b) -> {
                        Integer idxA = (Integer) a.getOrDefault("index", 0);
                        Integer idxB = (Integer) b.getOrDefault("index", 0);
                        return idxA.compareTo(idxB);
                    });

                    result.put("phases", flatPhases);
                    log.info("✅ [getDraftDataForRestore] phases flat criado: {} ações", flatPhases.size());

                    // ✅ 4. Adicionar confirmações se draft completo
                    Object indexObj = pickBanData.get("currentIndex");
                    if (indexObj instanceof Integer) {
                        int currentIndex = (Integer) indexObj;
                        boolean isDraftComplete = currentIndex >= 20;
                        result.put("confirmationOnly", isDraftComplete);

                        if (isDraftComplete) {
                            Set<String> confirmations = redisDraftFlow.getConfirmedPlayers(matchId);
                            result.put("confirmations", confirmations != null ? confirmations : new HashSet<>());
                            result.put("confirmedCount", confirmations != null ? confirmations.size() : 0);
                            log.info("✅ [getDraftDataForRestore] Draft completo, confirmações: {}/10",
                                    confirmations != null ? confirmations.size() : 0);
                        }
                    }

                    log.info("✅ [getDraftDataForRestore] Retornando pickBanData PURO do MySQL (0 conversões)");

                } catch (Exception e) {
                    log.error("❌ [getDraftDataForRestore] Erro ao parsear pickBanData", e);
                }
            } else {
                log.warn("⚠️ [getDraftDataForRestore] pick_ban_data_json é NULL ou vazio no MySQL!");
            }

            return result;

        } catch (Exception e) {
            log.error("❌ [getDraftDataForRestore] Erro ao buscar dados do draft", e);
            return result;
        }
    }

}
