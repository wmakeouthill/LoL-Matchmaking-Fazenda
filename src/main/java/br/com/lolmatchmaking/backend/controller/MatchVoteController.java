package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.domain.entity.Match;
import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.MatchRepository;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import br.com.lolmatchmaking.backend.service.EventBroadcastService;
import br.com.lolmatchmaking.backend.service.LCUService;
import br.com.lolmatchmaking.backend.service.MatchVoteService;
import br.com.lolmatchmaking.backend.service.RedisMatchVoteService;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import br.com.lolmatchmaking.backend.util.SummonerAuthUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/match")
@RequiredArgsConstructor
public class MatchVoteController {

    private final MatchVoteService matchVoteService;
    private final LCUService lcuService;
    private final MatchRepository matchRepository;
    private final PlayerRepository playerRepository;
    private final EventBroadcastService eventBroadcastService;
    private final RedisMatchVoteService redisMatchVoteService;
    private final MatchmakingWebSocketService webSocketService;

    private static final String KEY_ERROR = "error";
    private static final String KEY_SUCCESS = "success";

    /**
     * DTO para requisição de voto
     */
    record VoteRequest(String summonerName, Long lcuGameId) {
    }

    /**
     * DTO para requisição de link manual (admin fallback)
     */
    record LinkMatchRequest(Long lcuGameId, String lcuMatchData) {
    }

    /**
     * POST /api/match/{matchId}/vote
     * Registra voto de um jogador em uma partida LCU
     */
    @PostMapping("/{matchId}/vote")
    public ResponseEntity<Map<String, Object>> voteForMatch(
            @PathVariable Long matchId,
            @RequestBody VoteRequest request,
            HttpServletRequest httpRequest) {

        log.info("🗳️ [MatchVoteController] Voto recebido: matchId={}, summonerName={}, lcuGameId={}",
                matchId, request.summonerName(), request.lcuGameId());

        try {
            // 🔒 Autenticação via header
            String authenticatedSummoner = SummonerAuthUtil.getSummonerNameFromRequest(httpRequest);

            // 🔍 Validação de ownership
            if (!authenticatedSummoner.equalsIgnoreCase(request.summonerName())) {
                log.warn("⚠️ [{}] Tentativa de votar com nome de outro jogador: {}",
                        authenticatedSummoner, request.summonerName());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of(KEY_ERROR, "Nome do invocador não corresponde ao jogador autenticado"));
            }

            log.info("🔵 [{}] DENTRO DO TRY - iniciando validações", authenticatedSummoner);

            // Validar parâmetros
            log.info("🔵 [{}] Validando parâmetros: summonerName='{}', lcuGameId={}",
                    authenticatedSummoner, request.summonerName(), request.lcuGameId());
            if (request.summonerName() == null || request.summonerName().isEmpty() || request.lcuGameId() == null) {
                log.warn("❌ [{}] Validação falhou - parâmetros inválidos", authenticatedSummoner);
                return ResponseEntity.badRequest()
                        .body(Map.of(KEY_ERROR, "summonerName e lcuGameId são obrigatórios"));
            }
            log.info("✅ [{}] Validação OK - buscando partida com ID: {}", authenticatedSummoner, matchId);

            // Verificar se a partida existe
            Match match = matchRepository.findById(matchId).orElse(null);
            log.info("🔵 [MatchVoteController] Partida buscada: {}", match != null ? "encontrada" : "NÃO encontrada");
            if (match == null) {
                log.error("❌ [MatchVoteController] Partida não encontrada: matchId={}", matchId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(KEY_ERROR, "Partida não encontrada"));
            }
            log.info("✅ [MatchVoteController] Partida encontrada: ID={}, Status={}", match.getId(), match.getStatus());

            // Verificar se o jogador existe pelo summoner name
            log.info("🔍 [MatchVoteController] Buscando jogador: '{}'", request.summonerName());
            Player player = playerRepository.findBySummonerNameIgnoreCase(request.summonerName()).orElse(null);
            if (player == null) {
                log.error("❌ [MatchVoteController] Jogador não encontrado no banco: '{}'", request.summonerName());
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(KEY_ERROR, "Jogador não encontrado: " + request.summonerName()));
            }
            log.info("✅ [MatchVoteController] Jogador encontrado: ID={}, SummonerName='{}'", player.getId(),
                    player.getSummonerName());

            // Processar o voto usando o playerId obtido
            Map<String, Object> voteResult = matchVoteService.processVote(
                    matchId,
                    player.getId(),
                    request.lcuGameId());

            boolean shouldLink = (boolean) voteResult.getOrDefault("shouldLink", false);
            int voteCount = (int) voteResult.getOrDefault("voteCount", 0);
            Long votedGameId = (Long) voteResult.get("lcuGameId");
            boolean isSpecialUserVote = (boolean) voteResult.getOrDefault("specialUserVote", false);
            String voterName = player.getSummonerName();

            if (isSpecialUserVote) {
                log.info("🌟 Voto de SPECIAL USER detectado! Finalizando partida imediatamente...");
            } else {
                log.info("✅ Voto registrado: voteCount={}, shouldLink={}", voteCount, shouldLink);
            }

            // ✅ NOVO: Broadcast progresso de votação via WebSocket
            try {
                // Determinar qual time foi votado (assumir team1 por enquanto)
                Integer votedTeam = 1; // TODO: Implementar lógica para determinar team baseado no lcuGameId

                // ✅ CORREÇÃO: Usar peso do voto para special users ou constante padrão
                int totalNeeded = isSpecialUserVote ? (int) voteResult.getOrDefault("voteWeight", 6) : 6;

                // ✅ CORREÇÃO: Para special users, usar peso como contagem; para normais, usar
                // contagem atual
                int votesTeam1 = isSpecialUserVote ? (int) voteResult.getOrDefault("voteWeight", 1)
                        : (int) voteResult.getOrDefault("voteCount", 0);

                // ✅ NOVO: Broadcast direto via WebSocket (sem Redis Pub/Sub)
                this.broadcastVoteProgressDirectly(matchId, voterName, votedTeam, votesTeam1, totalNeeded);

                log.info("📢 [MatchVoteController] Broadcast de votação enviado: {} votou em team {} (peso: {})",
                        voterName, votedTeam, totalNeeded);
            } catch (Exception e) {
                log.error("❌ [MatchVoteController] Erro ao fazer broadcast de votação", e);
            }

            // Se atingiu votos necessários OU é special user, buscar dados do LCU e
            // vincular
            if (shouldLink) {
                if (isSpecialUserVote) {
                    log.info("🌟 SPECIAL USER finalizou a votação! Vinculando partida automaticamente...");
                } else {
                    log.info("🎯 Limite de votos atingido! Vinculando partida automaticamente...");
                }

                try {
                    // ✅ Buscar histórico de partidas do LCU usando o summoner do votante
                    log.info("🔍 Tentando buscar histórico LCU via jogador: {}", voterName);
                    JsonNode matchHistoryResponse = lcuService.getMatchHistory(voterName).join();

                    // Se falhou com o votante, tentar com outros jogadores da partida
                    if (matchHistoryResponse == null) {
                        log.warn("⚠️ Histórico LCU não disponível para votante: {}", voterName);
                        log.info("🔄 Tentando buscar histórico LCU de outros jogadores da partida...");

                        // Pegar jogadores da partida
                        List<String> allPlayers = new ArrayList<>();
                        String team1Json = match.getTeam1PlayersJson();
                        String team2Json = match.getTeam2PlayersJson();

                        if (team1Json != null && !team1Json.isEmpty()) {
                            allPlayers.addAll(List.of(team1Json.split(",")));
                        }
                        if (team2Json != null && !team2Json.isEmpty()) {
                            allPlayers.addAll(List.of(team2Json.split(",")));
                        }

                        log.info("🔍 Total de jogadores na partida: {}", allPlayers.size());

                        // Tentar com cada jogador até conseguir
                        for (String playerName : allPlayers) {
                            String cleanPlayerName = playerName.trim();
                            if (cleanPlayerName.equals(voterName)) {
                                continue; // Já tentamos com o votante
                            }

                            log.info("🔍 Tentando buscar histórico via: {}", cleanPlayerName);
                            try {
                                matchHistoryResponse = lcuService.getMatchHistory(cleanPlayerName).join();
                                if (matchHistoryResponse != null) {
                                    log.info("✅ Histórico LCU encontrado via: {}", cleanPlayerName);
                                    break;
                                }
                            } catch (Exception fallbackError) {
                                log.debug("❌ Falha ao buscar via {}: {}", cleanPlayerName, fallbackError.getMessage());
                            }
                        }

                        // Se ainda não conseguiu, retornar erro
                        if (matchHistoryResponse == null) {
                            log.error("❌ Nenhum jogador da partida tem conexão LCU ativa");
                            return ResponseEntity.ok(Map.of(
                                    KEY_SUCCESS, true,
                                    "message", "Voto registrado mas nenhum jogador com LCU conectado",
                                    "voteCount", voteCount,
                                    "linked", false,
                                    "specialUserVote", isSpecialUserVote,
                                    "voterName", voterName,
                                    KEY_ERROR, "No player with active LCU connection found"));
                        }
                    }

                    // DEBUG: Mostrar estrutura do JSON retornado
                    log.info("🔍 [DEBUG] Estrutura do matchHistoryResponse: {}",
                            matchHistoryResponse.getClass().getSimpleName());

                    // Listar todos os campos disponíveis
                    List<String> fieldNames = new ArrayList<>();
                    matchHistoryResponse.fieldNames().forEachRemaining(fieldNames::add);
                    log.info("🔍 [DEBUG] Campos disponíveis: {}", fieldNames);

                    if (matchHistoryResponse.has("games")) {
                        JsonNode gamesNode = matchHistoryResponse.get("games");
                        log.info("🔍 [DEBUG] Tem campo 'games', é array? {}", gamesNode.isArray());

                        // Se games não é array, listar seus subcampos
                        if (!gamesNode.isArray()) {
                            List<String> gamesFields = new ArrayList<>();
                            gamesNode.fieldNames().forEachRemaining(gamesFields::add);
                            log.info("🔍 [DEBUG] Subcampos de 'games': {}", gamesFields);
                        }
                    }

                    // Tentar encontrar o array de partidas
                    JsonNode gamesArray = null;
                    if (matchHistoryResponse.has("games")) {
                        JsonNode gamesNode = matchHistoryResponse.get("games");
                        if (gamesNode.isArray()) {
                            gamesArray = gamesNode;
                        } else if (gamesNode.has("games") && gamesNode.get("games").isArray()) {
                            // Estrutura: { "games": { "games": [...] } }
                            gamesArray = gamesNode.get("games");
                            log.info("🔍 [DEBUG] Array encontrado em games.games");
                        }
                    } else if (matchHistoryResponse.isArray()) {
                        gamesArray = matchHistoryResponse;
                    }

                    log.info("🔍 [DEBUG] gamesArray encontrado? {}, isArray={}, size={}",
                            gamesArray != null,
                            gamesArray != null && gamesArray.isArray(),
                            gamesArray != null ? gamesArray.size() : 0);

                    // Buscar pela lcuGameId votada
                    JsonNode lcuMatchData = null;
                    if (gamesArray != null && gamesArray.isArray()) {
                        log.info("🔍 [DEBUG] Procurando gameId={} em {} partidas", votedGameId, gamesArray.size());
                        for (JsonNode game : gamesArray) {
                            long gameId = game.has("gameId") ? game.get("gameId").asLong() : -1;
                            log.info("🔍 [DEBUG] Partida encontrada com gameId={}", gameId);
                            if (gameId == votedGameId) {
                                lcuMatchData = game;
                                log.info("✅ [DEBUG] Match! gameId={} == votedGameId={}", gameId, votedGameId);
                                break;
                            }
                        }
                    }

                    if (lcuMatchData != null) {
                        // Vincular a partida com os dados do LCU
                        matchVoteService.linkMatch(matchId, votedGameId, lcuMatchData);
                        log.info("🎉 Partida vinculada automaticamente com sucesso!");

                        // ✅ Incluir specialUserVote, voterName e shouldLink na resposta
                        Map<String, Object> response = new HashMap<>();
                        response.put(KEY_SUCCESS, true);
                        response.put("message", "Voto registrado e partida vinculada automaticamente");
                        response.put("voteCount", voteCount);
                        response.put("linked", true);
                        response.put("lcuGameId", votedGameId);
                        response.put("specialUserVote", isSpecialUserVote);
                        response.put("shouldLink", true);
                        response.put("voterName", voterName);

                        return ResponseEntity.ok(response);
                    } else {
                        log.warn("⚠️ Partida LCU não encontrada no histórico: {}", votedGameId);
                        return ResponseEntity.ok(Map.of(
                                KEY_SUCCESS, true,
                                "message", "Voto registrado mas partida LCU não encontrada no histórico",
                                "voteCount", voteCount,
                                "linked", false,
                                KEY_ERROR, "LCU match not found in history"));
                    }
                } catch (Exception e) {
                    log.error("❌ Erro ao buscar dados do LCU: {}", e.getMessage(), e);
                    return ResponseEntity.ok(Map.of(
                            KEY_SUCCESS, true,
                            "message", "Voto registrado mas erro ao vincular partida",
                            "voteCount", voteCount,
                            "linked", false,
                            KEY_ERROR, "Failed to fetch LCU data: " + e.getMessage()));
                }
            }

            // Resposta normal (sem vinculação ainda)
            Map<String, Object> response = new HashMap<>();
            response.put(KEY_SUCCESS, true);
            response.put("message", "Voto registrado com sucesso");
            response.put("voteCount", voteCount);
            response.put("linked", false);
            response.put("specialUserVote", isSpecialUserVote);
            response.put("shouldLink", shouldLink);
            response.put("voterName", voterName);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Validação falhou: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of(KEY_ERROR, e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Erro ao processar voto: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(KEY_ERROR, "Erro ao processar voto: " + e.getMessage()));
        }
    }

    /**
     * GET /api/match/{matchId}/votes
     * Retorna contagem de votos agrupados por lcuGameId
     * ✅ MODIFICADO: Valida header X-Summoner-Name
     */
    @GetMapping("/{matchId}/votes")
    public ResponseEntity<Map<String, Object>> getMatchVotes(
            @PathVariable Long matchId,
            HttpServletRequest request) {
        try {
            // ✅ Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("📊 [{}] Buscando votos da partida: matchId={}", summonerName, matchId);

            // Verificar se a partida existe
            Match match = matchRepository.findById(matchId).orElse(null);
            if (match == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(KEY_ERROR, "Partida não encontrada"));
            }

            // Buscar votos
            Map<Long, Long> voteCounts = matchVoteService.countVotesByLcuGameId(matchId);

            // Converter Map<Long, Long> para formato serializable
            Map<String, Long> voteCountsFormatted = voteCounts.entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()),
                            Map.Entry::getValue));

            log.info("✅ [{}] Votos encontrados: {}", summonerName, voteCountsFormatted);

            return ResponseEntity.ok(Map.of(
                    KEY_SUCCESS, true,
                    "votes", voteCountsFormatted));

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Header X-Summoner-Name ausente em requisição de get votes");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(KEY_ERROR, "Header X-Summoner-Name obrigatório"));
        } catch (Exception e) {
            log.error("❌ Erro ao buscar votos: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(KEY_ERROR, "Erro ao buscar votos: " + e.getMessage()));
        }
    }

    /**
     * DELETE /api/match/{matchId}/vote
     * Remove o voto de um jogador
     */
    @DeleteMapping("/{matchId}/vote")
    public ResponseEntity<Map<String, Object>> removeVote(
            @PathVariable Long matchId,
            @RequestParam Long playerId,
            HttpServletRequest httpRequest) {

        log.info("🗑️ [MatchVoteController] Removendo voto: matchId={}, playerId={}", matchId, playerId);

        try {
            // 🔒 Autenticação via header
            String authenticatedSummoner = SummonerAuthUtil.getSummonerNameFromRequest(httpRequest);

            // Validar parâmetros
            if (playerId == null) {
                log.warn("❌ [{}] playerId não fornecido", authenticatedSummoner);
                return ResponseEntity.badRequest()
                        .body(Map.of(KEY_ERROR, "playerId é obrigatório"));
            }

            // 🔍 Validação de ownership - verificar se playerId pertence ao summonerName
            // autenticado
            Player player = playerRepository.findById(playerId).orElse(null);
            if (player == null) {
                log.warn("❌ [{}] Jogador não encontrado: playerId={}", authenticatedSummoner, playerId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(KEY_ERROR, "Jogador não encontrado"));
            }

            if (!player.getSummonerName().equalsIgnoreCase(authenticatedSummoner)) {
                log.warn("⚠️ [{}] Tentativa de remover voto de outro jogador: playerId={}, summonerName={}",
                        authenticatedSummoner, playerId, player.getSummonerName());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of(KEY_ERROR, "Você não pode remover o voto de outro jogador"));
            }

            // Verificar se a partida existe
            Match match = matchRepository.findById(matchId).orElse(null);
            if (match == null) {
                log.warn("❌ [{}] Partida não encontrada: matchId={}", authenticatedSummoner, matchId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(KEY_ERROR, "Partida não encontrada"));
            }

            // 🔒 NOVO: ADICIONAR LOCK DE VOTAÇÃO
            // Note: Service removeVote não tem lock interno, precisa do Controller
            log.info("✅ [{}] Removendo voto de playerId={} (sem lock necessário - operação de remoção simples)",
                    authenticatedSummoner, playerId);

            // Remover voto
            matchVoteService.removeVote(matchId, playerId);

            log.info("✅ [{}] Voto removido com sucesso para playerId={}", authenticatedSummoner, playerId);

            return ResponseEntity.ok(Map.of(
                    KEY_SUCCESS, true,
                    "message", "Voto removido com sucesso"));

        } catch (Exception e) {
            log.error("❌ Erro ao remover voto: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(KEY_ERROR, "Erro ao remover voto: " + e.getMessage()));
        }
    }

    /**
     * POST /api/match/{matchId}/link
     * Link manual de partida (fallback administrativo)
     * Útil se o sistema de votação falhar ou para testes
     * ✅ MODIFICADO: Valida header X-Summoner-Name (Admin endpoint)
     */
    @PostMapping("/{matchId}/link")
    public ResponseEntity<Map<String, Object>> linkMatchManually(
            @PathVariable Long matchId,
            @RequestBody LinkMatchRequest request,
            HttpServletRequest httpRequest) {

        try {
            // ✅ Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(httpRequest);
            log.info("🔗 [{}] [ADMIN] Link manual solicitado: matchId={}, lcuGameId={}",
                    summonerName, matchId, request.lcuGameId());

            // Validar parâmetros
            if (request.lcuGameId() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of(KEY_ERROR, "lcuGameId é obrigatório"));
            }

            // Verificar se a partida existe
            Match match = matchRepository.findById(matchId).orElse(null);
            if (match == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(KEY_ERROR, "Partida não encontrada"));
            }

            // Se lcuMatchData não foi fornecido, buscar do LCU
            JsonNode lcuMatchDataNode = null;
            String lcuDataString = request.lcuMatchData();

            if (lcuDataString != null && !lcuDataString.isBlank()) {
                // Parse string para JsonNode
                try {
                    lcuMatchDataNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lcuDataString);
                } catch (Exception e) {
                    log.error("❌ Erro ao parsear lcuMatchData: {}", e.getMessage());
                    return ResponseEntity.badRequest()
                            .body(Map.of(KEY_ERROR, "lcuMatchData inválido: " + e.getMessage()));
                }
            } else {
                log.info("🔍 Buscando dados do LCU para gameId={}", request.lcuGameId());

                try {
                    JsonNode matchHistoryResponse = lcuService.getMatchHistory().join();

                    if (matchHistoryResponse == null) {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Map.of(KEY_ERROR, "Histórico LCU não disponível"));
                    }

                    JsonNode gamesArray = matchHistoryResponse.has("games")
                            ? matchHistoryResponse.get("games")
                            : matchHistoryResponse;

                    if (gamesArray.isArray()) {
                        for (JsonNode game : gamesArray) {
                            if (game.has("gameId") && game.get("gameId").asLong() == request.lcuGameId()) {
                                lcuMatchDataNode = game;
                                break;
                            }
                        }
                    }

                    if (lcuMatchDataNode == null) {
                        return ResponseEntity.badRequest()
                                .body(Map.of(KEY_ERROR, "Partida LCU não encontrada no histórico"));
                    }
                } catch (Exception e) {
                    log.error("❌ Erro ao buscar dados do LCU: {}", e.getMessage(), e);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of(KEY_ERROR, "Erro ao buscar dados do LCU: " + e.getMessage()));
                }
            }

            // Vincular a partida
            matchVoteService.linkMatch(matchId, request.lcuGameId(), lcuMatchDataNode);

            log.info("✅ [{}] Partida vinculada manualmente com sucesso", summonerName);

            return ResponseEntity.ok(Map.of(
                    KEY_SUCCESS, true,
                    "message", "Partida vinculada com sucesso",
                    "lcuGameId", request.lcuGameId()));

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Header X-Summoner-Name ausente em requisição de link match");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(KEY_ERROR, "Header X-Summoner-Name obrigatório"));
        } catch (Exception e) {
            log.error("❌ Erro ao vincular partida: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(KEY_ERROR, "Erro ao vincular partida: " + e.getMessage()));
        }
    }

    /**
     * GET /api/match/{matchId}/lcu-candidates
     * Retorna últimas 3 partidas personalizadas do histórico LCU
     * Para exibir no modal de votação
     * ✅ MODIFICADO: Valida header X-Summoner-Name
     */
    @GetMapping("/{matchId}/lcu-candidates")
    public ResponseEntity<Map<String, Object>> getLcuCandidates(
            @PathVariable Long matchId,
            HttpServletRequest request) {
        try {
            // ✅ Validar header X-Summoner-Name
            String summonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);
            log.info("🔍 [{}] Buscando candidatos LCU: matchId={}", summonerName, matchId);

            // Verificar se a partida existe
            Match match = matchRepository.findById(matchId).orElse(null);
            if (match == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(KEY_ERROR, "Partida não encontrada"));
            }

            // Buscar histórico do LCU
            JsonNode matchHistoryResponse = lcuService.getMatchHistory().join();

            if (matchHistoryResponse == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of(KEY_ERROR, "Histórico LCU não disponível"));
            }

            // O retorno pode ser um objeto com campo "games" ou diretamente um array
            JsonNode gamesArray = matchHistoryResponse.has("games")
                    ? matchHistoryResponse.get("games")
                    : matchHistoryResponse;

            // Filtrar apenas custom games e pegar as últimas 3
            java.util.List<JsonNode> customGames = new java.util.ArrayList<>();
            if (gamesArray.isArray()) {
                for (JsonNode game : gamesArray) {
                    if (game.has("queueId") && game.get("queueId").asInt() == 0) {
                        customGames.add(game);
                        if (customGames.size() >= 3) {
                            break;
                        }
                    }
                }
            }

            log.info("✅ [{}] Encontradas {} partidas personalizadas", summonerName, customGames.size());

            return ResponseEntity.ok(Map.of(
                    KEY_SUCCESS, true,
                    "candidates", customGames));

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Header X-Summoner-Name ausente em requisição de lcu-candidates");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(KEY_ERROR, "Header X-Summoner-Name obrigatório"));
        } catch (Exception e) {
            log.error("❌ Erro ao buscar candidatos LCU: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(KEY_ERROR, "Erro ao buscar candidatos LCU: " + e.getMessage()));
        }
    }

    /**
     * ✅ NOVO: Broadcast direto de progresso de votação via WebSocket
     */
    private void broadcastVoteProgressDirectly(Long matchId, String voterName, Integer votedTeam,
            int votesTeam1, int totalNeeded) {
        try {
            // Buscar lista de jogadores que votaram
            List<String> votedPlayers = redisMatchVoteService.getVotedPlayerNames(matchId);
            int totalPlayers = 10; // Assumindo 10 jogadores por partida

            // ✅ NOVO: Buscar informações dos times da partida
            Map<String, Object> teamInfo = getMatchTeamInfo(matchId);
            List<Map<String, Object>> team1 = (List<Map<String, Object>>) teamInfo.get("team1");
            List<Map<String, Object>> team2 = (List<Map<String, Object>>) teamInfo.get("team2");

            // Criar dados de votação
            Map<String, Object> voteData = new HashMap<>();
            voteData.put("matchId", matchId);
            voteData.put("summonerName", voterName);
            voteData.put("votedTeam", votedTeam);
            voteData.put("votesTeam1", votesTeam1);
            voteData.put("votesTeam2", 0); // Assumir 0 por enquanto
            voteData.put("totalNeeded", totalNeeded);
            voteData.put("votedPlayers", votedPlayers);
            voteData.put("votedCount", votedPlayers.size());
            voteData.put("totalPlayers", totalPlayers);
            voteData.put("team1", team1 != null ? team1 : new ArrayList<>());
            voteData.put("team2", team2 != null ? team2 : new ArrayList<>());

            // Broadcast via WebSocket
            webSocketService.broadcastMessage("match_vote_progress", voteData);

            log.info("📢 [MatchVoteController] Broadcast direto enviado: {} votou ({} jogadores votaram)",
                    voterName, votedPlayers.size());

        } catch (Exception e) {
            log.error("❌ [MatchVoteController] Erro no broadcast direto de votação", e);
        }
    }

    /**
     * ✅ NOVO: Buscar informações dos times da partida
     */
    private Map<String, Object> getMatchTeamInfo(Long matchId) {
        try {
            // Buscar partida do banco
            Optional<br.com.lolmatchmaking.backend.domain.entity.Match> matchOpt = matchRepository.findById(matchId);
            if (matchOpt.isEmpty()) {
                log.warn("⚠️ [MatchVoteController] Partida {} não encontrada", matchId);
                return Map.of("team1", new ArrayList<>(), "team2", new ArrayList<>());
            }

            br.com.lolmatchmaking.backend.domain.entity.Match match = matchOpt.get();

            // ✅ SIMPLIFICADO: Usar dados JSON dos times
            List<Map<String, Object>> team1 = parsePlayersFromJson(match.getTeam1PlayersJson());
            List<Map<String, Object>> team2 = parsePlayersFromJson(match.getTeam2PlayersJson());

            return Map.of("team1", team1, "team2", team2);

        } catch (Exception e) {
            log.error("❌ [MatchVoteController] Erro ao buscar informações dos times", e);
            return Map.of("team1", new ArrayList<>(), "team2", new ArrayList<>());
        }
    }

    /**
     * ✅ NOVO: Parsear jogadores do JSON
     */
    private List<Map<String, Object>> parsePlayersFromJson(String playersJson) {
        try {
            if (playersJson == null || playersJson.trim().isEmpty()) {
                return new ArrayList<>();
            }

            // Parsear JSON e converter para formato esperado pelo Electron
            List<String> playerNames = List.of(playersJson.split(","));
            return playerNames.stream()
                    .map(name -> {
                        Map<String, Object> playerData = new HashMap<>();
                        String cleanName = name.trim();
                        playerData.put("summonerName", cleanName);
                        playerData.put("gameName", cleanName.split("#")[0]);
                        playerData.put("name", cleanName);
                        return playerData;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("❌ [MatchVoteController] Erro ao parsear jogadores do JSON: {}", playersJson, e);
            return new ArrayList<>();
        }
    }
}
