package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.service.redis.RedisPlayerMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ⚡ Controller para gerenciar associação player → match ativo
 * 
 * ENDPOINTS:
 * - GET /api/player/current-match/{summonerName} → Busca matchId ativo do
 * jogador
 * - GET /api/player/current-match → Busca matchId usando header Authorization
 * (futuro)
 * 
 * USO NO FRONTEND:
 * 1. Ao conectar WebSocket, frontend chama GET
 * /api/player/current-match/{summonerName}
 * 2. Se retornar matchId, frontend sincroniza e entra no draft correto
 * 3. Se retornar null, frontend aguarda match_found notification
 */
@Slf4j
@RestController
@RequestMapping("/api/player")
@RequiredArgsConstructor
public class PlayerMatchController {

    private final RedisPlayerMatchService redisPlayerMatch;

    /**
     * ✅ Busca o matchId ativo de um jogador
     * 
     * @param summonerName Nome do jogador
     * @return matchId ativo ou null
     */
    @GetMapping("/current-match/{summonerName}")
    public ResponseEntity<Map<String, Object>> getCurrentMatch(@PathVariable String summonerName) {
        try {
            log.debug("🔍 [PlayerMatch] Buscando matchId ativo para: {}", summonerName);

            Long matchId = redisPlayerMatch.getCurrentMatch(summonerName);

            if (matchId == null) {
                log.debug("📭 [PlayerMatch] Jogador {} não tem partida ativa", summonerName);
                return ResponseEntity.ok(Map.of(
                        "hasMatch", false,
                        "matchId", (Object) null,
                        "message", "Jogador não está em partida ativa"));
            }

            log.info("✅ [PlayerMatch] Jogador {} está na partida {}", summonerName, matchId);
            return ResponseEntity.ok(Map.of(
                    "hasMatch", true,
                    "matchId", matchId,
                    "summonerName", summonerName));

        } catch (Exception e) {
            log.error("❌ [PlayerMatch] Erro ao buscar matchId para {}", summonerName, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Erro ao buscar partida ativa",
                    "message", e.getMessage()));
        }
    }

    /**
     * ✅ Busca jogadores de uma partida (para debug)
     * 
     * @param matchId ID da partida
     * @return Set de summonerNames
     */
    @GetMapping("/match/{matchId}/players")
    public ResponseEntity<Map<String, Object>> getMatchPlayers(@PathVariable Long matchId) {
        try {
            log.debug("🔍 [PlayerMatch] Buscando jogadores da partida {}", matchId);

            var players = redisPlayerMatch.getMatchPlayers(matchId);

            log.info("✅ [PlayerMatch] Partida {} tem {} jogadores", matchId, players.size());
            return ResponseEntity.ok(Map.of(
                    "matchId", matchId,
                    "players", players,
                    "playerCount", players.size()));

        } catch (Exception e) {
            log.error("❌ [PlayerMatch] Erro ao buscar jogadores de match {}", matchId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Erro ao buscar jogadores",
                    "message", e.getMessage()));
        }
    }

    /**
     * ✅ ADMIN: Limpa ownership de um jogador (para testes/debug)
     * 
     * @param summonerName Nome do jogador
     */
    @DeleteMapping("/current-match/{summonerName}")
    public ResponseEntity<Map<String, Object>> clearPlayerMatch(@PathVariable String summonerName) {
        try {
            log.warn("⚠️ [PlayerMatch] ADMIN: Limpando ownership de {}", summonerName);

            redisPlayerMatch.clearPlayerMatch(summonerName);

            log.info("✅ [PlayerMatch] Ownership removido: {}", summonerName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Ownership removido com sucesso",
                    "summonerName", summonerName));

        } catch (Exception e) {
            log.error("❌ [PlayerMatch] Erro ao limpar ownership de {}", summonerName, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Erro ao limpar ownership",
                    "message", e.getMessage()));
        }
    }

    /**
     * ✅ ADMIN: Limpa ownership de todos jogadores de uma partida (para
     * testes/debug)
     * 
     * @param matchId ID da partida
     */
    @DeleteMapping("/match/{matchId}/players")
    public ResponseEntity<Map<String, Object>> clearMatchPlayers(@PathVariable Long matchId) {
        try {
            log.warn("⚠️ [PlayerMatch] ADMIN: Limpando ownership de match {}", matchId);

            redisPlayerMatch.clearMatchPlayers(matchId);

            log.info("✅ [PlayerMatch] Ownership removido de match {}", matchId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Ownership de todos jogadores removido",
                    "matchId", matchId));

        } catch (Exception e) {
            log.error("❌ [PlayerMatch] Erro ao limpar ownership de match {}", matchId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Erro ao limpar ownership",
                    "message", e.getMessage()));
        }
    }
}
