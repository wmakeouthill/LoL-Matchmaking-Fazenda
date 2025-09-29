package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.domain.entity.Setting;
import br.com.lolmatchmaking.backend.domain.repository.SettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final SettingRepository settingRepository;

    /**
     * Configura token do Discord
     */
    @PostMapping("/discord-token")
    public ResponseEntity<Map<String, Object>> setDiscordToken(@RequestBody Map<String, String> request) {
        try {
            String token = request.get("token");
            if (token == null || token.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Token do Discord é obrigatório"));
            }

            Setting setting = settingRepository.findByKey("discord_token")
                    .orElse(new Setting());

            setting.setKey("discord_token");
            setting.setValue(token);
            setting.setUpdatedAt(Instant.now());

            settingRepository.save(setting);

            log.info("🔑 Token do Discord configurado com sucesso");
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Token do Discord configurado com sucesso"));

        } catch (Exception e) {
            log.error("❌ Erro ao configurar token do Discord", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor"));
        }
    }

    /**
     * Configura chave da API da Riot
     */
    @PostMapping("/riot-api-key")
    public ResponseEntity<Map<String, Object>> setRiotApiKey(@RequestBody Map<String, String> request) {
        try {
            String apiKey = request.get("apiKey");
            if (apiKey == null || apiKey.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Chave da API da Riot é obrigatória"));
            }

            Setting setting = settingRepository.findByKey("riot_api_key")
                    .orElse(new Setting());

            setting.setKey("riot_api_key");
            setting.setValue(apiKey);
            setting.setUpdatedAt(Instant.now());

            settingRepository.save(setting);

            log.info("🔑 Chave da API da Riot configurada com sucesso");
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Chave da API da Riot configurada com sucesso"));

        } catch (Exception e) {
            log.error("❌ Erro ao configurar chave da API da Riot", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor"));
        }
    }

    /**
     * Obtém status das configurações
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getConfigStatus() {
        try {
            Optional<Setting> discordToken = settingRepository.findByKey("discord_token");
            Optional<Setting> riotApiKey = settingRepository.findByKey("riot_api_key");
            Optional<Setting> discordChannel = settingRepository.findByKey("discord_channel_id");

            Map<String, Object> status = new HashMap<>();
            status.put("discordToken", discordToken.isPresent() ? "configurado" : "não configurado");
            status.put("riotApiKey", riotApiKey.isPresent() ? "configurado" : "não configurado");
            status.put("discordChannel", discordChannel.isPresent() ? "configurado" : "não configurado");
            status.put("timestamp", Instant.now().toString());

            return ResponseEntity.ok(status);

        } catch (Exception e) {
            log.error("❌ Erro ao obter status das configurações", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor"));
        }
    }

    /**
     * Obtém todas as configurações
     */
    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getAllSettings() {
        try {
            Map<String, String> settings = new HashMap<>();

            settingRepository.findAll().forEach(setting -> {
                settings.put(setting.getKey(), setting.getValue());
            });

            return ResponseEntity.ok(Map.of(
                    "settings", settings,
                    "timestamp", Instant.now().toString()));

        } catch (Exception e) {
            log.error("❌ Erro ao obter configurações", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor"));
        }
    }

    /**
     * Configura canal do Discord
     */
    @PostMapping("/discord-channel")
    public ResponseEntity<Map<String, Object>> setDiscordChannel(@RequestBody Map<String, String> request) {
        try {
            log.info("🎯 [DISCORD-CHANNEL] Recebida requisição: {}", request);

            String channelId = request.get("channelId");
            String guildId = request.get("guildId");

            log.info("🎯 [DISCORD-CHANNEL] ChannelId: {}, GuildId: {}", channelId, guildId);

            if (channelId == null || channelId.trim().isEmpty()) {
                log.warn("🎯 [DISCORD-CHANNEL] ChannelId vazio ou nulo");
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "ID do canal é obrigatório"));
            }

            log.info("🎯 [DISCORD-CHANNEL] Buscando configuração existente...");
            // Salvar canal
            Setting channelSetting = settingRepository.findByKey("discord_channel_id")
                    .orElse(new Setting());

            log.info("🎯 [DISCORD-CHANNEL] Configuração encontrada: {}", channelSetting);

            channelSetting.setKey("discord_channel_id");
            channelSetting.setValue(channelId);
            channelSetting.setUpdatedAt(Instant.now());

            log.info("🎯 [DISCORD-CHANNEL] Salvando configuração...");
            Setting savedChannel = settingRepository.save(channelSetting);
            log.info("🎯 [DISCORD-CHANNEL] Configuração salva: {}", savedChannel);

            // Salvar guild se fornecido
            if (guildId != null && !guildId.trim().isEmpty()) {
                log.info("🎯 [DISCORD-CHANNEL] Salvando guild ID...");
                Setting guildSetting = settingRepository.findByKey("discord_guild_id")
                        .orElse(new Setting());
                guildSetting.setKey("discord_guild_id");
                guildSetting.setValue(guildId);
                guildSetting.setUpdatedAt(Instant.now());
                Setting savedGuild = settingRepository.save(guildSetting);
                log.info("🎯 [DISCORD-CHANNEL] Guild salvo: {}", savedGuild);
            }

            log.info("🎯 Canal do Discord configurado com sucesso: {}", channelId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Canal do Discord configurado com sucesso",
                    "channelId", channelId,
                    "guildId", guildId));

        } catch (Exception e) {
            log.error("❌ Erro ao configurar canal do Discord", e);
            log.error("❌ [DISCORD-CHANNEL] Stack trace completo:", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor: " + e.getMessage()));
        }
    }

    /**
     * Atualiza uma configuração específica
     */
    @PutMapping("/setting/{key}")
    public ResponseEntity<Map<String, Object>> updateSetting(
            @PathVariable String key,
            @RequestBody Map<String, String> request) {
        try {
            String value = request.get("value");
            if (value == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Valor é obrigatório"));
            }

            Setting setting = settingRepository.findByKey(key)
                    .orElse(new Setting());

            setting.setKey(key);
            setting.setValue(value);
            setting.setUpdatedAt(Instant.now());

            settingRepository.save(setting);

            log.info("⚙️ Configuração atualizada: {} = {}", key, value);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Configuração atualizada com sucesso",
                    "key", key,
                    "value", value));

        } catch (Exception e) {
            log.error("❌ Erro ao atualizar configuração", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor"));
        }
    }

    /**
     * Remove uma configuração
     */
    @DeleteMapping("/setting/{key}")
    public ResponseEntity<Map<String, Object>> deleteSetting(@PathVariable String key) {
        try {
            Optional<Setting> setting = settingRepository.findByKey(key);
            if (setting.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            settingRepository.delete(setting.get());

            log.info("🗑️ Configuração removida: {}", key);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Configuração removida com sucesso",
                    "key", key));

        } catch (Exception e) {
            log.error("❌ Erro ao remover configuração", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Erro interno do servidor"));
        }
    }
}
