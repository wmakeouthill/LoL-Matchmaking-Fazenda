package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.service.DiscordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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

    // ✅ NOVO: Redis para validação de ownership
    private final br.com.lolmatchmaking.backend.service.redis.RedisPlayerMatchService redisPlayerMatch;

    // ✅ NOVO: Redis cache para performance
    private final br.com.lolmatchmaking.backend.service.redis.RedisDiscordCacheService redisDiscordCache;

    private static final String IS_CONNECTED = "isConnected";
    private static final String TIMESTAMP = "timestamp";
    private static final String SUCCESS = "success";
    private static final String MESSAGE = "message";
    private static final String ERROR = "error";

    // ✅ REMOVIDO: Cache local substituído por Redis (compartilhado entre
    // instâncias)
    // Use redisDiscordCache para cache distribuído

    /**
     * Obtém o status atual do Discord Bot
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getDiscordStatus() {
        try {
            long currentTime = System.currentTimeMillis();

            // ✅ NOVO: Tentar buscar do cache Redis primeiro
            Map<String, Object> cachedFromRedis = redisDiscordCache.getCachedStatus();

            if (cachedFromRedis != null) {
                log.debug("⚡ [DiscordController] Status retornado do cache Redis (rápido)");
                return ResponseEntity.ok(cachedFromRedis);
            }

            // Cache miss: Buscar do Discord e cachear no Redis
            log.info("🔄 [DiscordController] Cache miss - buscando status do Discord");
            Map<String, Object> status = new HashMap<>();
            status.put(IS_CONNECTED, discordService.isConnected());
            status.put("botUsername", discordService.getBotUsername());
            status.put("channelName", discordService.getChannelName());
            status.put("usersCount", discordService.getUsersCount());
            status.put(TIMESTAMP, currentTime);

            // ✅ NOVO: Armazenar no Redis (compartilhado)
            redisDiscordCache.cacheStatus(status);
            log.info("✅ [DiscordController] Status cacheado no Redis");

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

            // ✅ NOVO: Tentar buscar do cache Redis primeiro (compartilhado entre
            // instâncias)
            List<Object> cachedFromRedis = redisDiscordCache.getCachedUsers();

            if (cachedFromRedis != null) {
                log.debug("⚡ [DiscordController] Usuários retornados do cache Redis (rápido)");
                Map<String, Object> response = new HashMap<>();
                response.put(SUCCESS, true);
                response.put("users", cachedFromRedis);
                response.put("count", cachedFromRedis.size());
                response.put(TIMESTAMP, currentTime);
                return ResponseEntity.ok(response);
            }

            // Cache miss: Buscar do Discord e cachear no Redis
            log.info("🔄 [DiscordController] Cache miss - buscando do Discord");
            List<DiscordService.DiscordUser> users = discordService.getUsersInChannel();

            Map<String, Object> response = new HashMap<>();
            response.put(SUCCESS, true);
            response.put("users", users);
            response.put("count", users.size());
            response.put(TIMESTAMP, currentTime);

            // ✅ NOVO: Armazenar no Redis (compartilhado)
            redisDiscordCache.cacheUsers(users);
            log.info("✅ [DiscordController] {} usuários cacheados no Redis", users.size());

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

            // ✅ CRÍTICO: Validar ownership ANTES de mostrar espectadores
            if (!redisPlayerMatch.validateOwnership(summonerName, matchId)) {
                log.warn("🚫 [SEGURANÇA] Jogador {} tentou ver espectadores de match {} sem ownership!", summonerName,
                        matchId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        SUCCESS, false,
                        MESSAGE, "Jogador não pertence a esta partida"));
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

            // ✅ CRÍTICO: Validar ownership ANTES de mutar espectador
            if (!redisPlayerMatch.validateOwnership(summonerName, matchId)) {
                log.warn("🚫 [SEGURANÇA] Jogador {} tentou mutar espectador de match {} sem ownership!", summonerName,
                        matchId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        SUCCESS, false,
                        MESSAGE, "Jogador não pertence a esta partida"));
            }

            // Mutar espectador
            boolean success = discordService.muteSpectator(matchId, discordId);

            // ✅ CORREÇÃO: Buscar estado atualizado após mutar
            boolean currentMuteState = discordService.isSpectatorMuted(matchId, discordId);

            Map<String, Object> response = new HashMap<>();
            response.put(SUCCESS, success);
            response.put("isMuted", currentMuteState); // ✅ Estado atualizado
            response.put("discordId", discordId);
            response.put(MESSAGE, success ? "Espectador mutado com sucesso" : "Erro ao mutar espectador");
            response.put(TIMESTAMP, System.currentTimeMillis());

            log.info("🔇 [DiscordController] Espectador {} mutado na partida {} por {} (estado: {})",
                    discordId, matchId, summonerName, currentMuteState);

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

            // ✅ CRÍTICO: Validar ownership ANTES de desmutar espectador
            if (!redisPlayerMatch.validateOwnership(summonerName, matchId)) {
                log.warn("🚫 [SEGURANÇA] Jogador {} tentou desmutar espectador de match {} sem ownership!",
                        summonerName, matchId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        SUCCESS, false,
                        MESSAGE, "Jogador não pertence a esta partida"));
            }

            // Desmutar espectador
            boolean success = discordService.unmuteSpectator(matchId, discordId);

            // ✅ CORREÇÃO: Buscar estado atualizado após desmutar
            boolean currentMuteState = discordService.isSpectatorMuted(matchId, discordId);

            Map<String, Object> response = new HashMap<>();
            response.put(SUCCESS, success);
            response.put("isMuted", currentMuteState); // ✅ Estado atualizado
            response.put("discordId", discordId);
            response.put(MESSAGE, success ? "Espectador desmutado com sucesso" : "Erro ao desmutar espectador");
            response.put(TIMESTAMP, System.currentTimeMillis());

            log.info("🔊 [DiscordController] Espectador {} desmutado na partida {} por {} (estado: {})",
                    discordId, matchId, summonerName, currentMuteState);

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
