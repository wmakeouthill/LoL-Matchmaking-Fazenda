package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.domain.entity.Match;
import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.MatchRepository;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import br.com.lolmatchmaking.backend.service.LCUService;
import br.com.lolmatchmaking.backend.service.MatchVoteService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
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

    private static final String KEY_ERROR = "error";
    private static final String KEY_SUCCESS = "success";

    /**
     * DTO para requisição de voto
     */
    record VoteRequest(Long playerId, Long lcuGameId) {
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
            @RequestBody VoteRequest request) {

        log.info("🗳️ [MatchVoteController] Voto recebido: matchId={}, playerId={}, lcuGameId={}",
                matchId, request.playerId(), request.lcuGameId());

        try {
            // Validar parâmetros
            if (request.playerId() == null || request.lcuGameId() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of(KEY_ERROR, "playerId e lcuGameId são obrigatórios"));
            }

            // Verificar se a partida existe
            Match match = matchRepository.findById(matchId).orElse(null);
            if (match == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(KEY_ERROR, "Partida não encontrada"));
            }

            // Verificar se o jogador existe
            Player player = playerRepository.findById(request.playerId()).orElse(null);
            if (player == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(KEY_ERROR, "Jogador não encontrado"));
            }

            // Processar o voto
            Map<String, Object> voteResult = matchVoteService.processVote(
                    matchId,
                    request.playerId(),
                    request.lcuGameId());

            boolean shouldLink = (boolean) voteResult.getOrDefault("shouldLink", false);
            int voteCount = (int) voteResult.getOrDefault("voteCount", 0);
            Long votedGameId = (Long) voteResult.get("lcuGameId");

            log.info("✅ Voto registrado: voteCount={}, shouldLink={}", voteCount, shouldLink);

            // Se atingiu 5 votos, buscar dados do LCU e vincular
            if (shouldLink) {
                log.info("🎯 Limite de 5 votos atingido! Vinculando partida automaticamente...");

                try {
                    // Buscar histórico de partidas do LCU
                    JsonNode matchHistoryResponse = lcuService.getMatchHistory().join();

                    if (matchHistoryResponse == null) {
                        log.warn("⚠️ Histórico LCU não disponível");
                        return ResponseEntity.ok(Map.of(
                                KEY_SUCCESS, true,
                                "message", "Voto registrado mas histórico LCU não disponível",
                                "voteCount", voteCount,
                                "linked", false,
                                KEY_ERROR, "LCU history not available"));
                    }

                    // O retorno pode ser um objeto com campo "games" ou diretamente um array
                    JsonNode gamesArray = matchHistoryResponse.has("games")
                            ? matchHistoryResponse.get("games")
                            : matchHistoryResponse;

                    // Buscar pela lcuGameId votada
                    JsonNode lcuMatchData = null;
                    if (gamesArray.isArray()) {
                        for (JsonNode game : gamesArray) {
                            if (game.has("gameId") && game.get("gameId").asLong() == votedGameId) {
                                lcuMatchData = game;
                                break;
                            }
                        }
                    }

                    if (lcuMatchData != null) {
                        // Vincular a partida com os dados do LCU
                        matchVoteService.linkMatch(matchId, votedGameId, lcuMatchData);
                        log.info("🎉 Partida vinculada automaticamente com sucesso!");

                        return ResponseEntity.ok(Map.of(
                                KEY_SUCCESS, true,
                                "message", "Voto registrado e partida vinculada automaticamente",
                                "voteCount", voteCount,
                                "linked", true,
                                "lcuGameId", votedGameId));
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
            return ResponseEntity.ok(Map.of(
                    KEY_SUCCESS, true,
                    "message", "Voto registrado com sucesso",
                    "voteCount", voteCount,
                    "linked", false));

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
     */
    @GetMapping("/{matchId}/votes")
    public ResponseEntity<Map<String, Object>> getMatchVotes(@PathVariable Long matchId) {
        log.info("📊 [MatchVoteController] Buscando votos da partida: matchId={}", matchId);

        try {
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

            log.info("✅ Votos encontrados: {}", voteCountsFormatted);

            return ResponseEntity.ok(Map.of(
                    KEY_SUCCESS, true,
                    "votes", voteCountsFormatted));

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
            @RequestParam Long playerId) {

        log.info("🗑️ [MatchVoteController] Removendo voto: matchId={}, playerId={}", matchId, playerId);

        try {
            // Validar parâmetros
            if (playerId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of(KEY_ERROR, "playerId é obrigatório"));
            }

            // Verificar se a partida existe
            Match match = matchRepository.findById(matchId).orElse(null);
            if (match == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(KEY_ERROR, "Partida não encontrada"));
            }

            // Remover voto
            matchVoteService.removeVote(matchId, playerId);

            log.info("✅ Voto removido com sucesso");

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
     */
    @PostMapping("/{matchId}/link")
    public ResponseEntity<Map<String, Object>> linkMatchManually(
            @PathVariable Long matchId,
            @RequestBody LinkMatchRequest request) {

        log.info("🔗 [MatchVoteController] Link manual solicitado: matchId={}, lcuGameId={}",
                matchId, request.lcuGameId());

        try {
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

            log.info("✅ Partida vinculada manualmente com sucesso");

            return ResponseEntity.ok(Map.of(
                    KEY_SUCCESS, true,
                    "message", "Partida vinculada com sucesso",
                    "lcuGameId", request.lcuGameId()));

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
     */
    @GetMapping("/{matchId}/lcu-candidates")
    public ResponseEntity<Map<String, Object>> getLcuCandidates(@PathVariable Long matchId) {
        log.info("🔍 [MatchVoteController] Buscando candidatos LCU: matchId={}", matchId);

        try {
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

            log.info("✅ Encontradas {} partidas personalizadas", customGames.size());

            return ResponseEntity.ok(Map.of(
                    KEY_SUCCESS, true,
                    "candidates", customGames));

        } catch (Exception e) {
            log.error("❌ Erro ao buscar candidatos LCU: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(KEY_ERROR, "Erro ao buscar candidatos LCU: " + e.getMessage()));
        }
    }
}
