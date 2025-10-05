package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.Setting;
import br.com.lolmatchmaking.backend.domain.repository.SettingRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Service responsável por gerenciar special users
 * Special users têm privilégios especiais como finalizar votação com 1 voto
 * apenas
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpecialUserService {

    private final SettingRepository settingRepository;
    private final ObjectMapper objectMapper;

    private static final String SPECIAL_USERS_KEY = "special_users";

    /**
     * Verifica se um jogador (pelo summonerName) é um special user
     * 
     * @param summonerName Nome do invocador (ex: "FZD Ratoso#fzd")
     * @return true se é special user, false caso contrário
     */
    public boolean isSpecialUser(String summonerName) {
        if (summonerName == null || summonerName.trim().isEmpty()) {
            return false;
        }

        try {
            List<String> specialUsers = getSpecialUsers();

            // Normalizar comparação (case-insensitive)
            String normalizedInput = summonerName.trim().toLowerCase();

            boolean isSpecial = specialUsers.stream()
                    .anyMatch(user -> user.trim().toLowerCase().equals(normalizedInput));

            if (isSpecial) {
                log.info("🌟 [SpecialUser] {} identificado como SPECIAL USER", summonerName);
            }

            return isSpecial;

        } catch (Exception e) {
            log.error("❌ [SpecialUser] Erro ao verificar special user: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Retorna lista de special users cadastrados
     * 
     * @return Lista de summonerNames (pode estar vazia)
     */
    public List<String> getSpecialUsers() {
        try {
            Setting setting = settingRepository.findByKey(SPECIAL_USERS_KEY).orElse(null);

            if (setting == null || setting.getValue() == null || setting.getValue().trim().isEmpty()) {
                log.debug("📋 [SpecialUser] Nenhum special user cadastrado");
                return new ArrayList<>();
            }

            // Parse JSON array: ["FZD Ratoso#fzd", "Player2#tag"]
            List<String> users = objectMapper.readValue(
                    setting.getValue(),
                    new TypeReference<List<String>>() {
                    });

            log.debug("📋 [SpecialUser] {} special users encontrados: {}", users.size(), users);
            return users;

        } catch (Exception e) {
            log.error("❌ [SpecialUser] Erro ao ler special users do banco", e);
            return new ArrayList<>();
        }
    }

    /**
     * Adiciona um special user
     * 
     * @param summonerName Nome do invocador para adicionar
     */
    public void addSpecialUser(String summonerName) {
        if (summonerName == null || summonerName.trim().isEmpty()) {
            throw new IllegalArgumentException("SummonerName não pode ser vazio");
        }

        try {
            List<String> specialUsers = new ArrayList<>(getSpecialUsers());

            if (!specialUsers.contains(summonerName.trim())) {
                specialUsers.add(summonerName.trim());
                saveSpecialUsers(specialUsers);
                log.info("✅ [SpecialUser] {} adicionado aos special users", summonerName);
            } else {
                log.warn("⚠️ [SpecialUser] {} já é um special user", summonerName);
            }

        } catch (Exception e) {
            log.error("❌ [SpecialUser] Erro ao adicionar special user", e);
            throw new RuntimeException("Erro ao adicionar special user", e);
        }
    }

    /**
     * Remove um special user
     * 
     * @param summonerName Nome do invocador para remover
     */
    public void removeSpecialUser(String summonerName) {
        if (summonerName == null || summonerName.trim().isEmpty()) {
            throw new IllegalArgumentException("SummonerName não pode ser vazio");
        }

        try {
            List<String> specialUsers = new ArrayList<>(getSpecialUsers());

            if (specialUsers.removeIf(user -> user.trim().equalsIgnoreCase(summonerName.trim()))) {
                saveSpecialUsers(specialUsers);
                log.info("✅ [SpecialUser] {} removido dos special users", summonerName);
            } else {
                log.warn("⚠️ [SpecialUser] {} não encontrado nos special users", summonerName);
            }

        } catch (Exception e) {
            log.error("❌ [SpecialUser] Erro ao remover special user", e);
            throw new RuntimeException("Erro ao remover special user", e);
        }
    }

    /**
     * Salva lista de special users no banco
     */
    private void saveSpecialUsers(List<String> specialUsers) {
        try {
            String jsonValue = objectMapper.writeValueAsString(specialUsers);

            Setting setting = settingRepository.findByKey(SPECIAL_USERS_KEY)
                    .orElse(Setting.builder()
                            .key(SPECIAL_USERS_KEY)
                            .build());

            setting.setValue(jsonValue);
            settingRepository.save(setting);

            log.debug("💾 [SpecialUser] Lista atualizada no banco: {}", specialUsers);

        } catch (Exception e) {
            log.error("❌ [SpecialUser] Erro ao salvar special users", e);
            throw new RuntimeException("Erro ao salvar special users", e);
        }
    }
}
