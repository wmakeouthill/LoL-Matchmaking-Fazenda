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
import java.util.Optional;

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

    /**
     * ✅ NOVO: Configuração de votos para special users
     */
    public static class SpecialUserConfig {
        private String summonerName;
        private int voteWeight; // Quantos votos vale (padrão: 5)
        private boolean allowMultipleVotes; // Se pode votar múltiplas vezes
        private int maxVotes; // Máximo de votos (se allowMultipleVotes = true)

        public SpecialUserConfig(String summonerName, int voteWeight, boolean allowMultipleVotes, int maxVotes) {
            this.summonerName = summonerName;
            this.voteWeight = voteWeight;
            this.allowMultipleVotes = allowMultipleVotes;
            this.maxVotes = maxVotes;
        }

        // Getters e Setters
        public String getSummonerName() {
            return summonerName;
        }

        public void setSummonerName(String summonerName) {
            this.summonerName = summonerName;
        }

        public int getVoteWeight() {
            return voteWeight;
        }

        public void setVoteWeight(int voteWeight) {
            this.voteWeight = voteWeight;
        }

        public boolean isAllowMultipleVotes() {
            return allowMultipleVotes;
        }

        public void setAllowMultipleVotes(boolean allowMultipleVotes) {
            this.allowMultipleVotes = allowMultipleVotes;
        }

        public int getMaxVotes() {
            return maxVotes;
        }

        public void setMaxVotes(int maxVotes) {
            this.maxVotes = maxVotes;
        }
    }

    private static final String SPECIAL_USERS_KEY = "special_users";
    private static final String SPECIAL_USER_CONFIGS_KEY = "special_user_configs";

    /**
     * ✅ NOVO: Obter configuração de special user
     */
    public SpecialUserConfig getSpecialUserConfig(String summonerName) {
        try {
            List<SpecialUserConfig> configs = getSpecialUserConfigs();
            return configs.stream()
                    .filter(config -> config.getSummonerName().equalsIgnoreCase(summonerName))
                    .findFirst()
                    .orElse(new SpecialUserConfig(summonerName, 1, false, 1)); // ✅ ALTERADO: Padrão de 5 para 1 voto
        } catch (Exception e) {
            log.error("❌ [SpecialUser] Erro ao obter configuração: {}", e.getMessage(), e);
            return new SpecialUserConfig(summonerName, 1, false, 1); // ✅ ALTERADO: Fallback de 5 para 1 voto
        }
    }

    /**
     * ✅ NOVO: Salvar configuração de special user
     */
    public void saveSpecialUserConfig(SpecialUserConfig config) {
        try {
            List<SpecialUserConfig> configs = new ArrayList<>(getSpecialUserConfigs());

            // Remover configuração existente se houver
            configs.removeIf(c -> c.getSummonerName().equalsIgnoreCase(config.getSummonerName()));

            // Adicionar nova configuração
            configs.add(config);

            saveSpecialUserConfigs(configs);
            log.info("✅ [SpecialUser] Configuração salva para {}: weight={}, multiple={}, max={}",
                    config.getSummonerName(), config.getVoteWeight(), config.isAllowMultipleVotes(),
                    config.getMaxVotes());
        } catch (Exception e) {
            log.error("❌ [SpecialUser] Erro ao salvar configuração", e);
            throw new RuntimeException("Erro ao salvar configuração", e);
        }
    }

    /**
     * ✅ NOVO: Obter todas as configurações de special users
     */
    private List<SpecialUserConfig> getSpecialUserConfigs() {
        try {
            Optional<br.com.lolmatchmaking.backend.domain.entity.Setting> settingOpt = settingRepository
                    .findByKey(SPECIAL_USER_CONFIGS_KEY);

            if (settingOpt.isEmpty()) {
                log.debug("📋 [SpecialUser] Nenhuma configuração de special user cadastrada");
                return new ArrayList<>();
            }

            String jsonValue = settingOpt.get().getValue();
            if (jsonValue == null || jsonValue.trim().isEmpty()) {
                return new ArrayList<>();
            }

            List<SpecialUserConfig> configs = objectMapper.readValue(jsonValue,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, SpecialUserConfig.class));

            log.debug("📋 [SpecialUser] {} configurações encontradas: {}", configs.size(), configs);
            return configs;

        } catch (Exception e) {
            log.error("❌ [SpecialUser] Erro ao ler configurações do banco", e);
            return new ArrayList<>();
        }
    }

    /**
     * ✅ NOVO: Salvar todas as configurações de special users
     */
    private void saveSpecialUserConfigs(List<SpecialUserConfig> configs) {
        try {
            String jsonValue = objectMapper.writeValueAsString(configs);

            br.com.lolmatchmaking.backend.domain.entity.Setting setting = settingRepository
                    .findByKey(SPECIAL_USER_CONFIGS_KEY)
                    .orElse(new br.com.lolmatchmaking.backend.domain.entity.Setting());

            setting.setKey(SPECIAL_USER_CONFIGS_KEY);
            setting.setValue(jsonValue);
            setting.setUpdatedAt(java.time.Instant.now());

            settingRepository.save(setting);

            log.debug("💾 [SpecialUser] Configurações atualizadas no banco: {}", configs);

        } catch (Exception e) {
            log.error("❌ [SpecialUser] Erro ao salvar configurações", e);
            throw new RuntimeException("Erro ao salvar configurações", e);
        }
    }

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
