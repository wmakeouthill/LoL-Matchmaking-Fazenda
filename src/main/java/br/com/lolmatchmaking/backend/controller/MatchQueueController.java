package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.dto.QueuePlayerDTO;
import br.com.lolmatchmaking.backend.dto.QueuePlayerInfoDTO;
import br.com.lolmatchmaking.backend.service.MatchQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

@Slf4j
// @RestController // ✅ DESATIVADO: Substituído por QueueController
@RequestMapping("/api/match-queue")
@RequiredArgsConstructor
@Validated
public class MatchQueueController {

    private final MatchQueueService matchQueueService;

    @PostMapping("/join")
    public ResponseEntity<QueuePlayerDTO> joinQueue(@RequestParam @NotBlank String summonerName) {
        log.debug("Jogador {} entrando na fila", summonerName);

        try {
            QueuePlayerDTO queuePlayer = matchQueueService.joinQueue(summonerName);
            return ResponseEntity.ok(queuePlayer);
        } catch (IllegalStateException e) {
            log.warn("Tentativa de entrar na fila: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException e) {
            log.warn("Jogador não encontrado: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Erro ao entrar na fila", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/leave")
    public ResponseEntity<Void> leaveQueue(@RequestParam @NotBlank String summonerName) {
        log.debug("Jogador {} saindo da fila", summonerName);

        boolean left = matchQueueService.leaveQueue(summonerName);
        return left ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/status")
    public ResponseEntity<List<QueuePlayerInfoDTO>> getQueueStatus() {
        log.debug("Consultando status da fila");

        List<QueuePlayerInfoDTO> queueStatus = matchQueueService.getQueueStatus();
        return ResponseEntity.ok(queueStatus);
    }

    @GetMapping("/size")
    public ResponseEntity<Integer> getQueueSize() {
        log.debug("Consultando tamanho da fila");

        List<QueuePlayerInfoDTO> queueStatus = matchQueueService.getQueueStatus();
        return ResponseEntity.ok(queueStatus.size());
    }
}
