package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.service.DiscordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/discord")
@RequiredArgsConstructor
public class DiscordController {

    private final DiscordService discordService;

    private static final String IS_CONNECTED = "isConnected";
    private static final String TIMESTAMP = "timestamp";
    private static final String SUCCESS = "success";
    private static final String MESSAGE = "message";
    private static final String ERROR = "error";

    // Cache para reduzir chamadas desnecessárias
    private Map<String, Object> cachedStatus = null;
    private long lastStatusUpdate = 0;
    private Map<String, Object> cachedUsers = null;
    private long lastUsersUpdate = 0;
    private static final long CACHE_DURATION_MS = 2000; // 2 segundos de cache

    /**
     * Obtém o status atual do Discord Bot
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getDiscordStatus() {
        try {
            long currentTime = System.currentTimeMillis();

            // Verificar se o cache ainda é válido
            if (cachedStatus != null && (currentTime - lastStatusUpdate) < CACHE_DURATION_MS) {
                log.debug("📊 [DiscordController] Retornando status do cache");
                return ResponseEntity.ok(cachedStatus);
            }

            // Atualizar cache
            Map<String, Object> status = new HashMap<>();
            status.put(IS_CONNECTED, discordService.isConnected());
            status.put("botUsername", discordService.getBotUsername());
            status.put("channelName", discordService.getChannelName());
            status.put("usersCount", discordService.getUsersCount());
            status.put(TIMESTAMP, currentTime);

            // Armazenar no cache
            cachedStatus = status;
            lastStatusUpdate = currentTime;

            log.info("📊 [DiscordController] Status solicitado: {}", status);
            return ResponseEntity.ok(status);

        } catch (Exception e) {
            log.error("❌ [DiscordController] Erro ao obter status do Discord", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    SUCCESS, false,
                    MESSAGE, "Erro interno do servidor",
                    ERROR, e.getMessage()));
        }
    }

    /**
     * Obtém a lista de usuários no canal do Discord
     */
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> getDiscordUsers() {
        try {
            long currentTime = System.currentTimeMillis();

            // Verificar se o cache ainda é válido
            if (cachedUsers != null && (currentTime - lastUsersUpdate) < CACHE_DURATION_MS) {
                log.debug("👥 [DiscordController] Retornando usuários do cache");
                return ResponseEntity.ok(cachedUsers);
            }

            // Atualizar cache
            List<DiscordService.DiscordUser> users = discordService.getUsersInChannel();

            Map<String, Object> response = new HashMap<>();
            response.put(SUCCESS, true);
            response.put("users", users);
            response.put("count", users.size());
            response.put(TIMESTAMP, currentTime);

            // Armazenar no cache
            cachedUsers = response;
            lastUsersUpdate = currentTime;

            log.info("👥 [DiscordController] {} usuários solicitados", users.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ [DiscordController] Erro ao obter usuários do Discord", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    SUCCESS, false,
                    MESSAGE, "Erro interno do servidor",
                    ERROR, e.getMessage()));
        }
    }

    /**
     * Força a atualização das configurações do Discord
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshDiscordSettings() {
        try {
            log.info("🔄 [DiscordController] Atualizando configurações do Discord...");
            discordService.refreshSettings();

            Map<String, Object> response = new HashMap<>();
            response.put(SUCCESS, true);
            response.put(MESSAGE, "Configurações do Discord atualizadas com sucesso");
            response.put(IS_CONNECTED, discordService.isConnected());
            response.put(TIMESTAMP, System.currentTimeMillis());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ [DiscordController] Erro ao atualizar configurações do Discord", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    SUCCESS, false,
                    MESSAGE, "Erro ao atualizar configurações",
                    ERROR, e.getMessage()));
        }
    }

    /**
     * Testa a conexão com o Discord
     */
    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection() {
        try {
            boolean connected = discordService.isConnected();
            String message = connected ? "Discord Bot conectado com sucesso" : "Discord Bot não está conectado";

            Map<String, Object> response = new HashMap<>();
            response.put(SUCCESS, connected);
            response.put(MESSAGE, message);
            response.put(IS_CONNECTED, connected);
            response.put("botUsername", discordService.getBotUsername());
            response.put("channelName", discordService.getChannelName());
            response.put("usersCount", discordService.getUsersCount());
            response.put(TIMESTAMP, System.currentTimeMillis());

            log.info("🧪 [DiscordController] Teste de conexão: {}", message);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ [DiscordController] Erro no teste de conexão", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    SUCCESS, false,
                    MESSAGE, "Erro no teste de conexão",
                    ERROR, e.getMessage()));
        }
    }

    /**
     * Força o registro de comandos slash no Discord
     */
    @PostMapping("/register-commands")
    public ResponseEntity<Map<String, Object>> registerSlashCommands() {
        log.info("🔧 [DiscordController] Forçando registro de comandos slash");

        try {
            boolean success = discordService.registerSlashCommandsNow();

            Map<String, Object> response = new HashMap<>();
            response.put(SUCCESS, success);
            response.put(MESSAGE, success ? "Comandos slash registrados com sucesso" : "Erro ao registrar comandos");
            response.put(TIMESTAMP, System.currentTimeMillis());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ [DiscordController] Erro ao registrar comandos", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    SUCCESS, false,
                    MESSAGE, "Erro ao registrar comandos slash",
                    ERROR, e.getMessage()));
        }
    }

    // ========================================
    // ✅ NOVOS ENDPOINTS: GERENCIAMENTO DE ESPECTADORES
    // ========================================

    /**
     * Lista espectadores de uma partida (excluindo os 10 jogadores)
     * Requer header X-Summoner-Name para validação
     */
    @GetMapping("/match/{matchId}/spectators")
    public ResponseEntity<Map<String, Object>> getMatchSpectators(
            @PathVariable Long matchId,
            @RequestHeader(value = "X-Summoner-Name", required = false) String summonerName) {
        try {
            // Validar header (isolamento por jogador)
            if (summonerName == null || summonerName.isBlank()) {
                log.warn("⚠️ [DiscordController] Header X-Summoner-Name ausente");
                return ResponseEntity.badRequest().body(Map.of(
                        SUCCESS, false,
                        MESSAGE, "Header X-Summoner-Name é obrigatório"));
            }

            // Buscar espectadores
            List<DiscordService.SpectatorDTO> spectators = discordService.getMatchSpectators(matchId);

            Map<String, Object> response = new HashMap<>();
            response.put(SUCCESS, true);
            response.put("spectators", spectators);
            response.put("count", spectators.size());
            response.put(TIMESTAMP, System.currentTimeMillis());

            log.info("👀 [DiscordController] Espectadores da partida {} solicitados por {}: {} espectadores",
                    matchId, summonerName, spectators.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ [DiscordController] Erro ao listar espectadores", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    SUCCESS, false,
                    MESSAGE, "Erro ao listar espectadores",
                    ERROR, e.getMessage()));
        }
    }

    /**
     * Muta um espectador no Discord (SERVER MUTE - espectador não pode se desmutar)
     * Requer header X-Summoner-Name para validação
     */
    @PostMapping("/match/{matchId}/spectator/{discordId}/mute")
    public ResponseEntity<Map<String, Object>> muteSpectator(
            @PathVariable Long matchId,
            @PathVariable String discordId,
            @RequestHeader(value = "X-Summoner-Name", required = false) String summonerName) {
        try {
            // Validar header (isolamento por jogador)
            if (summonerName == null || summonerName.isBlank()) {
                log.warn("⚠️ [DiscordController] Header X-Summoner-Name ausente");
                return ResponseEntity.badRequest().body(Map.of(
                        SUCCESS, false,
                        MESSAGE, "Header X-Summoner-Name é obrigatório"));
            }

            // Mutar espectador
            boolean success = discordService.muteSpectator(matchId, discordId);

            Map<String, Object> response = new HashMap<>();
            response.put(SUCCESS, success);
            response.put(MESSAGE, success ? "Espectador mutado com sucesso" : "Erro ao mutar espectador");
            response.put(TIMESTAMP, System.currentTimeMillis());

            log.info("🔇 [DiscordController] Espectador {} mutado na partida {} por {}",
                    discordId, matchId, summonerName);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ [DiscordController] Erro ao mutar espectador", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    SUCCESS, false,
                    MESSAGE, "Erro ao mutar espectador",
                    ERROR, e.getMessage()));
        }
    }

    /**
     * Desmuta um espectador no Discord (remove SERVER MUTE)
     * Requer header X-Summoner-Name para validação
     */
    @PostMapping("/match/{matchId}/spectator/{discordId}/unmute")
    public ResponseEntity<Map<String, Object>> unmuteSpectator(
            @PathVariable Long matchId,
            @PathVariable String discordId,
            @RequestHeader(value = "X-Summoner-Name", required = false) String summonerName) {
        try {
            // Validar header (isolamento por jogador)
            if (summonerName == null || summonerName.isBlank()) {
                log.warn("⚠️ [DiscordController] Header X-Summoner-Name ausente");
                return ResponseEntity.badRequest().body(Map.of(
                        SUCCESS, false,
                        MESSAGE, "Header X-Summoner-Name é obrigatório"));
            }

            // Desmutar espectador
            boolean success = discordService.unmuteSpectator(matchId, discordId);

            Map<String, Object> response = new HashMap<>();
            response.put(SUCCESS, success);
            response.put(MESSAGE, success ? "Espectador desmutado com sucesso" : "Erro ao desmutar espectador");
            response.put(TIMESTAMP, System.currentTimeMillis());

            log.info("🔊 [DiscordController] Espectador {} desmutado na partida {} por {}",
                    discordId, matchId, summonerName);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ [DiscordController] Erro ao desmutar espectador", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    SUCCESS, false,
                    MESSAGE, "Erro ao desmutar espectador",
                    ERROR, e.getMessage()));
        }
    }
}
