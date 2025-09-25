package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.DiscordConfig;
import br.com.lolmatchmaking.backend.domain.repository.DiscordConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordConfigService {

    private final DiscordConfigRepository discordConfigRepository;
    private final DiscordService discordService;

    /**
     * Obtém a configuração ativa do Discord
     */
    public Optional<DiscordConfig> getActiveConfig() {
        return discordConfigRepository.findByIsActiveTrue();
    }

    /**
     * Lista todos os servidores Discord disponíveis
     */
    public List<DiscordGuildInfo> getAvailableGuilds() {
        if (!discordService.isConnected()) {
            log.warn("Discord não conectado - não é possível listar servidores");
            return List.of();
        }

        try {
            JDA jda = getJDAInstance();
            return jda.getGuilds().stream()
                    .map(guild -> new DiscordGuildInfo(
                            guild.getId(),
                            guild.getName(),
                            guild.getMemberCount()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Erro ao listar servidores Discord", e);
            return List.of();
        }
    }

    /**
     * Lista todos os canais de texto de um servidor
     */
    public List<DiscordChannelInfo> getGuildChannels(String guildId) {
        if (!discordService.isConnected()) {
            log.warn("Discord não conectado - não é possível listar canais");
            return List.of();
        }

        try {
            JDA jda = getJDAInstance();
            Guild guild = jda.getGuildById(guildId);

            if (guild == null) {
                log.warn("Servidor Discord não encontrado: {}", guildId);
                return List.of();
            }

            return guild.getTextChannels().stream()
                    .map(channel -> new DiscordChannelInfo(
                            channel.getId(),
                            channel.getName(),
                            channel.getTopic()))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Erro ao listar canais do servidor {}", guildId, e);
            return List.of();
        }
    }

    /**
     * Salva a configuração do Discord selecionada no frontend
     */
    @Transactional
    public DiscordConfig saveConfig(DiscordConfigRequest request) {
        try {
            log.info("Salvando configuração Discord - Guild: {}, QueueChannel: {}, MatchesChannel: {}",
                    request.getGuildId(), request.getQueueChannelId(), request.getMatchesChannelId());

            // Desativar configuração anterior
            discordConfigRepository.findByIsActiveTrue()
                    .ifPresent(config -> {
                        config.setIsActive(false);
                        discordConfigRepository.save(config);
                    });

            // Obter informações detalhadas do Discord
            String guildName = getGuildName(request.getGuildId());
            String queueChannelName = getChannelName(request.getGuildId(), request.getQueueChannelId());
            String matchesChannelName = getChannelName(request.getGuildId(), request.getMatchesChannelId());

            // Criar nova configuração
            DiscordConfig config = DiscordConfig.builder()
                    .guildId(request.getGuildId())
                    .guildName(guildName)
                    .queueChannelId(request.getQueueChannelId())
                    .queueChannelName(queueChannelName)
                    .matchesChannelId(request.getMatchesChannelId())
                    .matchesChannelName(matchesChannelName)
                    .isActive(true)
                    .build();

            DiscordConfig savedConfig = discordConfigRepository.save(config);
            log.info("✅ Configuração Discord salva com sucesso: {}", savedConfig.getId());

            return savedConfig;

        } catch (Exception e) {
            log.error("❌ Erro ao salvar configuração Discord", e);
            throw new RuntimeException("Erro ao salvar configuração Discord: " + e.getMessage());
        }
    }

    private String getGuildName(String guildId) {
        try {
            if (!discordService.isConnected()) return "Servidor não identificado";

            JDA jda = getJDAInstance();
            Guild guild = jda.getGuildById(guildId);
            return guild != null ? guild.getName() : "Servidor não identificado";
        } catch (Exception e) {
            log.warn("Erro ao obter nome do servidor {}", guildId, e);
            return "Servidor não identificado";
        }
    }

    private String getChannelName(String guildId, String channelId) {
        try {
            if (!discordService.isConnected()) return "Canal não identificado";

            JDA jda = getJDAInstance();
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) return "Canal não identificado";

            TextChannel channel = guild.getTextChannelById(channelId);
            return channel != null ? channel.getName() : "Canal não identificado";
        } catch (Exception e) {
            log.warn("Erro ao obter nome do canal {} no servidor {}", channelId, guildId, e);
            return "Canal não identificado";
        }
    }

    private JDA getJDAInstance() {
        // Usar reflexão para acessar o JDA do DiscordService
        try {
            java.lang.reflect.Field jdaField = DiscordService.class.getDeclaredField("jda");
            jdaField.setAccessible(true);
            return (JDA) jdaField.get(discordService);
        } catch (Exception e) {
            throw new RuntimeException("Erro ao acessar instância JDA", e);
        }
    }

    // DTOs
    public static class DiscordGuildInfo {
        private final String id;
        private final String name;
        private final int memberCount;

        public DiscordGuildInfo(String id, String name, int memberCount) {
            this.id = id;
            this.name = name;
            this.memberCount = memberCount;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public int getMemberCount() { return memberCount; }
    }

    public static class DiscordChannelInfo {
        private final String id;
        private final String name;
        private final String topic;

        public DiscordChannelInfo(String id, String name, String topic) {
            this.id = id;
            this.name = name;
            this.topic = topic;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getTopic() { return topic; }
    }

    public static class DiscordConfigRequest {
        private String guildId;
        private String queueChannelId;
        private String matchesChannelId;

        public String getGuildId() { return guildId; }
        public void setGuildId(String guildId) { this.guildId = guildId; }
        public String getQueueChannelId() { return queueChannelId; }
        public void setQueueChannelId(String queueChannelId) { this.queueChannelId = queueChannelId; }
        public String getMatchesChannelId() { return matchesChannelId; }
        public void setMatchesChannelId(String matchesChannelId) { this.matchesChannelId = matchesChannelId; }
    }
}
