package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.service.MatchFoundService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/match")
@RequiredArgsConstructor
public class MatchFoundController {

    private final MatchFoundService matchFoundService;

    /**
     * POST /api/match/accept
     * Aceitar partida encontrada
     */
    @PostMapping("/accept")
    public ResponseEntity<Map<String, Object>> acceptMatch(@RequestBody AcceptMatchRequest request) {
        try {
            log.info("✅ Jogador {} aceitando partida {}", request.getSummonerName(), request.getMatchId());

            matchFoundService.acceptMatch(request.getMatchId(), request.getSummonerName());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Partida aceita com sucesso"));

        } catch (Exception e) {
            log.error("❌ Erro ao aceitar partida", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/match/decline
     * Recusar partida encontrada
     */
    @PostMapping("/decline")
    public ResponseEntity<Map<String, Object>> declineMatch(@RequestBody DeclineMatchRequest request) {
        try {
            log.info("❌ Jogador {} recusando partida {}", request.getSummonerName(), request.getMatchId());

            matchFoundService.declineMatch(request.getMatchId(), request.getSummonerName());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Partida recusada"));

        } catch (Exception e) {
            log.error("❌ Erro ao recusar partida", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // DTOs
    @Data
    public static class AcceptMatchRequest {
        private Long matchId;
        private String summonerName;
    }

    @Data
    public static class DeclineMatchRequest {
        private Long matchId;
        private String summonerName;
    }
}
