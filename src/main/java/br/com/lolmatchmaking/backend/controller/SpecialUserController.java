package br.com.lolmatchmaking.backend.controller;

import br.com.lolmatchmaking.backend.service.SpecialUserService;
import br.com.lolmatchmaking.backend.util.SummonerAuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ✅ NOVO: Controller para gerenciar special users
 * 
 * 🔒 SEGURANÇA: Usa validação de sessão via X-Summoner-Name header
 * para garantir que apenas o jogador autenticado possa acessar suas próprias
 * configurações
 */
@RestController
@RequestMapping("/api/admin/special-user")
@RequiredArgsConstructor
@Slf4j
public class SpecialUserController {

    private final SpecialUserService specialUserService;

    /**
     * Verificar se um jogador é special user
     * 🔒 SEGURANÇA: Valida via X-Summoner-Name header
     */
    @GetMapping("/{summonerName}/status")
    public ResponseEntity<Boolean> checkSpecialUserStatus(
            @PathVariable String summonerName,
            HttpServletRequest request) {
        try {
            // ✅ VALIDAÇÃO: Extrair summonerName do header X-Summoner-Name
            String authenticatedSummonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);

            // ✅ SEGURANÇA: Verificar se está tentando acessar suas próprias informações
            if (!authenticatedSummonerName.equalsIgnoreCase(summonerName)) {
                log.warn("🚨 [SpecialUserController] Tentativa de acesso não autorizado: {} tentou acessar {}",
                        authenticatedSummonerName, summonerName);
                return ResponseEntity.status(403).body(false);
            }

            boolean isSpecial = specialUserService.isSpecialUser(summonerName);
            log.info("🔍 [SpecialUserController] Status verificado para {}: {}", summonerName, isSpecial);
            return ResponseEntity.ok(isSpecial);
        } catch (Exception e) {
            log.error("❌ [SpecialUserController] Erro ao verificar status: {}", e.getMessage(), e);
            return ResponseEntity.ok(false); // Fallback para false
        }
    }

    /**
     * Obter configuração de special user
     * 🔒 SEGURANÇA: Valida via X-Summoner-Name header
     */
    @GetMapping("/{summonerName}/config")
    public ResponseEntity<SpecialUserService.SpecialUserConfig> getSpecialUserConfig(
            @PathVariable String summonerName,
            HttpServletRequest request) {
        try {
            // ✅ VALIDAÇÃO: Extrair summonerName do header X-Summoner-Name
            String authenticatedSummonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);

            // ✅ SEGURANÇA: Verificar se está tentando acessar suas próprias informações
            if (!authenticatedSummonerName.equalsIgnoreCase(summonerName)) {
                log.warn("🚨 [SpecialUserController] Tentativa de acesso não autorizado: {} tentou acessar {}",
                        authenticatedSummonerName, summonerName);
                return ResponseEntity.status(403).body(new SpecialUserService.SpecialUserConfig(
                        summonerName, 1, false, 1));
            }

            SpecialUserService.SpecialUserConfig config = specialUserService.getSpecialUserConfig(summonerName);
            log.info("📋 [SpecialUserController] Configuração obtida para {}: {}", summonerName, config);
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("❌ [SpecialUserController] Erro ao obter configuração: {}", e.getMessage(), e);
            // Retornar configuração padrão em caso de erro
            SpecialUserService.SpecialUserConfig defaultConfig = new SpecialUserService.SpecialUserConfig(
                    summonerName, 1, false, 1);
            return ResponseEntity.ok(defaultConfig);
        }
    }

    /**
     * Atualizar configuração de special user
     * 🔒 SEGURANÇA: Valida via X-Summoner-Name header
     */
    @PutMapping("/{summonerName}/config")
    public ResponseEntity<Map<String, Object>> updateSpecialUserConfig(
            @PathVariable String summonerName,
            @RequestBody Map<String, Object> configData,
            HttpServletRequest request) {
        try {
            // ✅ VALIDAÇÃO: Extrair summonerName do header X-Summoner-Name
            String authenticatedSummonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);

            // ✅ SEGURANÇA: Verificar se está tentando acessar suas próprias informações
            if (!authenticatedSummonerName.equalsIgnoreCase(summonerName)) {
                log.warn("🚨 [SpecialUserController] Tentativa de acesso não autorizado: {} tentou acessar {}",
                        authenticatedSummonerName, summonerName);
                return ResponseEntity.status(403).body(Map.of(
                        "success", false,
                        "error", "Acesso negado: você só pode modificar suas próprias configurações"));
            }

            // Extrair dados da requisição
            int voteWeight = (Integer) configData.getOrDefault("voteWeight", 1);
            boolean allowMultipleVotes = (Boolean) configData.getOrDefault("allowMultipleVotes", false);
            int maxVotes = (Integer) configData.getOrDefault("maxVotes", 1);

            // Criar configuração
            SpecialUserService.SpecialUserConfig config = new SpecialUserService.SpecialUserConfig(
                    summonerName, voteWeight, allowMultipleVotes, maxVotes);

            // Salvar configuração
            specialUserService.saveSpecialUserConfig(config);

            log.info("✅ [SpecialUserController] Configuração atualizada para {}: weight={}, multiple={}, max={}",
                    summonerName, voteWeight, allowMultipleVotes, maxVotes);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Configuração atualizada com sucesso",
                    "config", config));

        } catch (Exception e) {
            log.error("❌ [SpecialUserController] Erro ao atualizar configuração: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Erro ao atualizar configuração: " + e.getMessage()));
        }
    }

    /**
     * Adicionar special user (apenas para administradores)
     * 🔒 SEGURANÇA: Valida via X-Summoner-Name header
     */
    @PostMapping("/{summonerName}")
    public ResponseEntity<Map<String, Object>> addSpecialUser(
            @PathVariable String summonerName,
            HttpServletRequest request) {
        try {
            // ✅ VALIDAÇÃO: Extrair summonerName do header X-Summoner-Name
            String authenticatedSummonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);

            // ✅ SEGURANÇA: Verificar se está tentando acessar suas próprias informações
            if (!authenticatedSummonerName.equalsIgnoreCase(summonerName)) {
                log.warn("🚨 [SpecialUserController] Tentativa de acesso não autorizado: {} tentou acessar {}",
                        authenticatedSummonerName, summonerName);
                return ResponseEntity.status(403).body(Map.of(
                        "success", false,
                        "error", "Acesso negado: você só pode gerenciar suas próprias configurações"));
            }

            specialUserService.addSpecialUser(summonerName);
            log.info("✅ [SpecialUserController] Special user adicionado: {}", summonerName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Special user adicionado com sucesso"));
        } catch (Exception e) {
            log.error("❌ [SpecialUserController] Erro ao adicionar special user: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Erro ao adicionar special user: " + e.getMessage()));
        }
    }

    /**
     * Remover special user (apenas para administradores)
     * 🔒 SEGURANÇA: Valida via X-Summoner-Name header
     */
    @DeleteMapping("/{summonerName}")
    public ResponseEntity<Map<String, Object>> removeSpecialUser(
            @PathVariable String summonerName,
            HttpServletRequest request) {
        try {
            // ✅ VALIDAÇÃO: Extrair summonerName do header X-Summoner-Name
            String authenticatedSummonerName = SummonerAuthUtil.getSummonerNameFromRequest(request);

            // ✅ SEGURANÇA: Verificar se está tentando acessar suas próprias informações
            if (!authenticatedSummonerName.equalsIgnoreCase(summonerName)) {
                log.warn("🚨 [SpecialUserController] Tentativa de acesso não autorizado: {} tentou acessar {}",
                        authenticatedSummonerName, summonerName);
                return ResponseEntity.status(403).body(Map.of(
                        "success", false,
                        "error", "Acesso negado: você só pode gerenciar suas próprias configurações"));
            }

            specialUserService.removeSpecialUser(summonerName);
            log.info("✅ [SpecialUserController] Special user removido: {}", summonerName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Special user removido com sucesso"));
        } catch (Exception e) {
            log.error("❌ [SpecialUserController] Erro ao remover special user: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Erro ao remover special user: " + e.getMessage()));
        }
    }
}