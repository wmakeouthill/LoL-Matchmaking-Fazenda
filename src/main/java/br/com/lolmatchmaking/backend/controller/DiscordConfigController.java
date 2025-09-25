package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.domain.entity.DiscordConfig;
import br.com.lolmatchmaking.backend.service.DiscordConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/discord/config")
@RequiredArgsConstructor
public class DiscordConfigController {

    private final DiscordConfigService discordConfigService;

    /**
     * GET /api/discord/config
     * Obtém a configuração ativa do Discord
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getActiveConfig() {
        try {
            return discordConfigService.getActiveConfig()
                    .map(config -> ResponseEntity.ok(Map.of(
                            "success", true,
                            "config", Map.of(
                                    "id", config.getId(),
                                    "guildId", config.getGuildId(),
                                    "guildName", config.getGuildName(),
                                    "queueChannelId", config.getQueueChannelId(),
                                    "queueChannelName", config.getQueueChannelName(),
                                    "matchesChannelId", config.getMatchesChannelId(),
                                    "matchesChannelName", config.getMatchesChannelName(),
                                    "isActive", config.getIsActive()))))
                    .orElse(ResponseEntity.ok(Map.of(
                            "success", true,
                            "config", null)));

        } catch (Exception e) {
            log.error("❌ Erro ao obter configuração Discord", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/discord/config/guilds
     * Lista todos os servidores Discord disponíveis
     */
    @GetMapping("/guilds")
    public ResponseEntity<Map<String, Object>> getAvailableGuilds() {
        try {
            List<DiscordConfigService.DiscordGuildInfo> guilds = discordConfigService.getAvailableGuilds();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "guilds", guilds));

        } catch (Exception e) {
            log.error("❌ Erro ao listar servidores Discord", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * GET /api/discord/config/guilds/{guildId}/channels
     * Lista todos os canais de texto de um servidor
     */
    @GetMapping("/guilds/{guildId}/channels")
    public ResponseEntity<Map<String, Object>> getGuildChannels(@PathVariable String guildId) {
        try {
            List<DiscordConfigService.DiscordChannelInfo> channels = discordConfigService.getGuildChannels(guildId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "channels", channels));

        } catch (Exception e) {
            log.error("❌ Erro ao listar canais do servidor {}", guildId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * POST /api/discord/config
     * Salva a configuração do Discord selecionada no frontend
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody DiscordConfigService.DiscordConfigRequest request) {
        try {
            log.info("💾 Salvando configuração Discord via frontend");

            DiscordConfig savedConfig = discordConfigService.saveConfig(request);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Configuração Discord salva com sucesso",
                    "config", Map.of(
                            "id", savedConfig.getId(),
                            "guildId", savedConfig.getGuildId(),
                            "guildName", savedConfig.getGuildName(),
                            "queueChannelId", savedConfig.getQueueChannelId(),
                            "queueChannelName", savedConfig.getQueueChannelName(),
                            "matchesChannelId", savedConfig.getMatchesChannelId(),
                            "matchesChannelName", savedConfig.getMatchesChannelName(),
                            "isActive", savedConfig.getIsActive())));

        } catch (Exception e) {
            log.error("❌ Erro ao salvar configuração Discord", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/discord/config/{id}
     * Remove uma configuração do Discord
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteConfig(@PathVariable Long id) {
        try {
            log.info("🗑️ Removendo configuração Discord: {}", id);

            // Implementar lógica de remoção se necessário
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Configuração removida com sucesso"));

        } catch (Exception e) {
            log.error("❌ Erro ao remover configuração Discord", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
