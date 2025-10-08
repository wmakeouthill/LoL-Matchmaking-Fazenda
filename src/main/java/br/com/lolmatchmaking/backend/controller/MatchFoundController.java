package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.service.MatchFoundService;
import br.com.lolmatchmaking.backend.util.SummonerAuthUtil;
import jakarta.servlet.http.HttpServletRequest;
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
     * ✅ MODIFICADO: Valida header X-Summoner-Name
     */
    @PostMapping("/accept")
    public ResponseEntity<Map<String, Object>> acceptMatch(
            @RequestBody AcceptMatchRequest request,
            HttpServletRequest httpRequest) {
        try {
            // ✅ Validar header X-Summoner-Name
            String authenticatedSummoner = SummonerAuthUtil.getSummonerNameFromRequest(httpRequest);
            log.info("✅ [{}] Jogador {} aceitando partida {}",
                    authenticatedSummoner, request.getSummonerName(), request.getMatchId());

            // ✅ Validar se summonerName do body corresponde ao header
            if (!authenticatedSummoner.equalsIgnoreCase(request.getSummonerName())) {
                log.warn("⚠️ Tentativa de aceitar partida com summonerName diferente do autenticado: {} != {}",
                        authenticatedSummoner, request.getSummonerName());
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error",
                                "Nome do invocador não corresponde ao jogador autenticado"));
            }

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
     * ✅ MODIFICADO: Valida header X-Summoner-Name
     */
    @PostMapping("/decline")
    public ResponseEntity<Map<String, Object>> declineMatch(
            @RequestBody DeclineMatchRequest request,
            HttpServletRequest httpRequest) {
        try {
            // ✅ Validar header X-Summoner-Name
            String authenticatedSummoner = SummonerAuthUtil.getSummonerNameFromRequest(httpRequest);
            log.info("❌ [{}] Jogador {} recusando partida {}",
                    authenticatedSummoner, request.getSummonerName(), request.getMatchId());

            // ✅ Validar se summonerName do body corresponde ao header
            if (!authenticatedSummoner.equalsIgnoreCase(request.getSummonerName())) {
                log.warn("⚠️ Tentativa de recusar partida com summonerName diferente do autenticado: {} != {}",
                        authenticatedSummoner, request.getSummonerName());
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error",
                                "Nome do invocador não corresponde ao jogador autenticado"));
            }

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
