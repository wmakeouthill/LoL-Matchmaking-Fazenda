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
    
    /**
     * Obtém o status atual do Discord Bot
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getDiscordStatus() {
        try {
            Map<String, Object> status = new HashMap<>();
            status.put(IS_CONNECTED, discordService.isConnected());
            status.put("botUsername", discordService.getBotUsername());
            status.put("channelName", discordService.getChannelName());
            status.put("usersCount", discordService.getUsersCount());
            status.put(TIMESTAMP, System.currentTimeMillis());
            
            log.info("📊 [DiscordController] Status solicitado: {}", status);
            return ResponseEntity.ok(status);
            
        } catch (Exception e) {
            log.error("❌ [DiscordController] Erro ao obter status do Discord", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    SUCCESS, false,
                    MESSAGE, "Erro interno do servidor",
                    ERROR, e.getMessage()
            ));
        }
    }
    
    /**
     * Obtém a lista de usuários no canal do Discord
     */
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> getDiscordUsers() {
        try {
            List<DiscordService.DiscordUser> users = discordService.getUsersInChannel();
            
            Map<String, Object> response = new HashMap<>();
            response.put(SUCCESS, true);
            response.put("users", users);
            response.put("count", users.size());
            response.put(TIMESTAMP, System.currentTimeMillis());
            
            log.info("👥 [DiscordController] {} usuários solicitados", users.size());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("❌ [DiscordController] Erro ao obter usuários do Discord", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    SUCCESS, false,
                    MESSAGE, "Erro interno do servidor",
                    ERROR, e.getMessage()
            ));
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
                    ERROR, e.getMessage()
            ));
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
                    ERROR, e.getMessage()
            ));
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
                    ERROR, e.getMessage()
            ));
        }
    }
}
