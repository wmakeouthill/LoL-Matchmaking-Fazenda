package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.Setting;
import br.com.lolmatchmaking.backend.entity.DiscordLoLLink;
import br.com.lolmatchmaking.backend.domain.repository.SettingRepository;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordService extends ListenerAdapter {

    private final SettingRepository settingRepository;
    private final MatchmakingWebSocketService webSocketService;
    private final DiscordLoLLinkService discordLoLLinkService;

    private JDA jda;
    private String discordToken;
    private String discordChannelName; // Mudança: usar nome do canal em vez de ID
    private VoiceChannel monitoredChannel;
    private final Map<String, DiscordUser> usersInChannel = new ConcurrentHashMap<>();
    private final Map<String, DiscordPlayer> discordQueue = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private boolean isConnected = false;
    private String botUsername;
    private String channelName;

    @PostConstruct
    public void initialize() {
        log.info("🤖 [DiscordService] Inicializando serviço Discord...");
        loadDiscordSettings();

        if (discordToken != null && !discordToken.trim().isEmpty()) {
            connectToDiscord();

            // ✅ NOVO: Timer para atualizações periódicas do estado do Discord
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (isConnected && monitoredChannel != null) {
                        updateDiscordUsers();
                        sendDiscordStatus();
                    }
                } catch (Exception e) {
                    log.warn("⚠️ [DiscordService] Erro na atualização periódica: {}", e.getMessage());
                }
            }, 120, 120, TimeUnit.SECONDS); // A cada 2 minutos - menos agressivo
        } else {
            log.warn("⚠️ [DiscordService] Token do Discord não configurado");
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("🛑 [DiscordService] Encerrando serviço Discord...");
        if (jda != null) {
            jda.shutdown();
        }
        scheduler.shutdown();
    }

    private void loadDiscordSettings() {
        try {
            Optional<Setting> tokenSetting = settingRepository.findByKey("discord_token");
            Optional<Setting> channelSetting = settingRepository.findByKey("discord_channel_id");

            if (tokenSetting.isPresent()) {
                discordToken = tokenSetting.get().getValue();
                log.info("🔑 [DiscordService] Token do Discord carregado");
            }

            if (channelSetting.isPresent()) {
                discordChannelName = channelSetting.get().getValue();
                log.info("🎯 [DiscordService] Nome do canal Discord carregado: {}", discordChannelName);
            }
        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao carregar configurações do Discord", e);
        }
    }

    public void connectToDiscord() {
        if (discordToken == null || discordToken.trim().isEmpty()) {
            log.warn("⚠️ [DiscordService] Token do Discord não disponível");
            return;
        }

        try {
            log.info("🔌 [DiscordService] Conectando ao Discord...");

            jda = JDABuilder.createDefault(discordToken)
                    .enableIntents(GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MEMBERS)
                    .addEventListeners(this)
                    .build();

            jda.awaitReady();

            isConnected = true;
            botUsername = jda.getSelfUser().getName();

            log.info("✅ [DiscordService] Conectado ao Discord como: {}", botUsername);

            // Registrar comandos slash
            registerSlashCommands();

            // Encontrar e monitorar o canal
            findAndMonitorChannel();

            // Iniciar monitoramento periódico
            startPeriodicMonitoring();

            // Notificar frontend
            notifyDiscordStatus();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ [DiscordService] Conexão interrompida", e);
            isConnected = false;
        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao conectar ao Discord", e);
            isConnected = false;
        }
    }

    private void registerSlashCommands() {
        try {
            log.info("🔧 [DiscordService] Registrando comandos slash...");
            log.info("🔧 [DiscordService] JDA Status: {}", jda.getStatus());
            log.info("🔧 [DiscordService] Guilds disponíveis: {}", jda.getGuilds().size());

            if (jda.getGuilds().isEmpty()) {
                log.warn("⚠️ [DiscordService] Bot não está em nenhuma guild! Comandos serão registrados globalmente.");
            } else {
                log.info("🔧 [DiscordService] Registrando comandos para guild: {}", jda.getGuilds().get(0).getName());
            }

            // ✅ NOVO: Registrar comandos globais (funcionam em qualquer chat)
            CommandListUpdateAction globalCommands = jda.updateCommands();

            globalCommands.addCommands(
                    Commands.slash("vincular", "Vincular seu Discord ao seu nickname do LoL")
                            .addOption(OptionType.STRING, "nickname", "Seu nickname no LoL (sem a tag)", true)
                            .addOption(OptionType.STRING, "tag", "Sua tag no LoL (ex: BR1, sem o #)", true),

                    Commands.slash("desvincular", "Remover vinculação do seu Discord com LoL"),

                    Commands.slash("queue", "Ver status da fila atual"),

                    Commands.slash("clear_queue", "Limpar fila (apenas moderadores)"),

                    Commands.slash("lobby", "Ver usuários no lobby #lol-matchmaking"));

            globalCommands.queue((success) -> {
                log.info("✅ [DiscordService] Comandos slash registrados com sucesso: {}", success);
                log.info(
                        "🎯 [DiscordService] Comandos disponíveis: /vincular, /desvincular, /queue, /clear_queue, /lobby");
                log.info("⏰ [DiscordService] Pode levar até 1 hora para aparecer no Discord (comandos globais)");
            }, (error) -> {
                log.error("❌ [DiscordService] Erro ao registrar comandos: {}", error.getMessage());
            });

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao registrar comandos slash", e);
        }
    }

    private void findAndMonitorChannel() {
        if (discordChannelName == null || discordChannelName.trim().isEmpty()) {
            log.warn("⚠️ [DiscordService] Nome do canal não configurado");
            return;
        }

        try {
            // Buscar canal por nome em todas as guilds
            for (net.dv8tion.jda.api.entities.Guild guild : jda.getGuilds()) {
                monitoredChannel = guild.getVoiceChannels().stream()
                        .filter(channel -> channel.getName().equals(discordChannelName))
                        .findFirst()
                        .orElse(null);

                if (monitoredChannel != null) {
                    channelName = monitoredChannel.getName();
                    log.info("🎯 [DiscordService] Canal encontrado: {} (ID: {})", channelName,
                            monitoredChannel.getId());

                    // Carregar usuários já no canal
                    loadCurrentUsersInChannel();
                    return;
                }
            }

            log.warn("⚠️ [DiscordService] Canal não encontrado: {}", discordChannelName);
            log.info("🔍 [DiscordService] Canais disponíveis:");
            for (net.dv8tion.jda.api.entities.Guild guild : jda.getGuilds()) {
                for (VoiceChannel channel : guild.getVoiceChannels()) {
                    log.info("  - {} (ID: {})", channel.getName(), channel.getId());
                }
            }

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao encontrar canal", e);
        }
    }

    private void loadCurrentUsersInChannel() {
        if (monitoredChannel == null)
            return;

        try {
            List<Member> members = monitoredChannel.getMembers();
            usersInChannel.clear();

            for (Member member : members) {
                if (!member.getUser().isBot()) {
                    DiscordUser user = createDiscordUser(member);
                    usersInChannel.put(user.getId(), user);
                }
            }

            log.info("👥 [DiscordService] {} usuários carregados do canal", usersInChannel.size());
            notifyUsersUpdate();

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao carregar usuários do canal", e);
        }
    }

    private DiscordUser createDiscordUser(Member member) {
        DiscordUser user = new DiscordUser();
        user.setId(member.getId());
        user.setUsername(member.getUser().getName());
        user.setDisplayName(member.getEffectiveName());
        user.setInChannel(true);
        user.setJoinedAt(new Date());

        // ✅ CORREÇÃO: Carregar dados vinculados do banco de dados
        try {
            Optional<DiscordLoLLink> link = discordLoLLinkService.findByDiscordId(member.getId());
            if (link.isPresent()) {
                DiscordLoLLink linkData = link.get();
                LinkedNickname linkedNick = new LinkedNickname(linkData.getGameName(), linkData.getTagLine());
                user.setLinkedNickname(linkedNick);
                user.setHasAppOpen(true);
                log.debug("🔗 [DiscordService] Dados vinculados carregados do banco para {}: {}",
                        member.getEffectiveName(), linkData.getSummonerName());
            } else {
                // Fallback: Tentar extrair gameName#tagLine do nickname do Discord
                String nickname = member.getNickname();
                if (nickname != null && nickname.contains("#")) {
                    String[] parts = nickname.split("#", 2);
                    if (parts.length == 2) {
                        user.setLinkedNickname(new LinkedNickname(parts[0].trim(), parts[1].trim()));
                        user.setHasAppOpen(true);
                        log.debug("🔗 [DiscordService] Dados vinculados extraídos do nickname para {}: {}",
                                member.getEffectiveName(), nickname);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [DiscordService] Erro ao carregar dados vinculados para {}: {}",
                    member.getEffectiveName(), e.getMessage());
        }

        return user;
    }

    private void startPeriodicMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            if (isConnected && monitoredChannel != null) {
                loadCurrentUsersInChannel();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    // Event listeners
    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        String leftChannelName = event.getChannelLeft() != null ? event.getChannelLeft().getName() : null;
        String joinedChannelName = event.getChannelJoined() != null ? event.getChannelJoined().getName() : null;

        if (leftChannelName != null && leftChannelName.equals(discordChannelName)) {
            log.info("👋 [DiscordService] Usuário saiu do canal: {}", event.getMember().getEffectiveName());
            usersInChannel.remove(event.getMember().getId());
            notifyUsersUpdate();
        } else if (joinedChannelName != null && joinedChannelName.equals(discordChannelName)) {
            log.info("👋 [DiscordService] Usuário entrou no canal: {}", event.getMember().getEffectiveName());
            DiscordUser user = createDiscordUser(event.getMember());
            usersInChannel.put(user.getId(), user);
            notifyUsersUpdate();
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        try {
            switch (event.getName()) {
                case "vincular":
                    handleVincularCommand(event);
                    break;
                case "desvincular":
                    handleDesvincularCommand(event);
                    break;
                case "queue":
                    handleQueueCommand(event);
                    break;
                case "clear_queue":
                    handleClearQueueCommand(event);
                    break;
                case "lobby":
                    handleLobbyCommand(event);
                    break;
                default:
                    event.reply("❌ Comando não reconhecido").setEphemeral(true).queue();
            }
        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao processar comando slash: {}", event.getName(), e);
            event.reply("❌ Erro interno ao processar comando").setEphemeral(true).queue();
        }
    }

    private void notifyDiscordStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("isConnected", isConnected);
        status.put("botUsername", botUsername);
        status.put("channelName", channelName);
        status.put("usersCount", usersInChannel.size());

        webSocketService.broadcastMessage("discord_status", status);
        log.info("📡 [DiscordService] Status enviado via WebSocket: {}", status);
    }

    private void notifyUsersUpdate() {
        List<DiscordUser> users = new ArrayList<>(usersInChannel.values());
        webSocketService.broadcastMessage("discord_users", Map.of("users", users));
        log.info("📡 [DiscordService] {} usuários enviados via WebSocket", users.size());
    }

    // ✅ NOVO: Método para atualizar usuários do Discord
    private void updateDiscordUsers() {
        if (monitoredChannel == null) {
            log.debug("🔍 [DiscordService] Nenhum canal monitorado para atualizar usuários");
            return;
        }

        try {
            List<Member> members = monitoredChannel.getMembers();
            Map<String, DiscordUser> currentUsers = new ConcurrentHashMap<>();

            for (Member member : members) {
                if (!member.getUser().isBot()) {
                    DiscordUser user = createDiscordUser(member);
                    currentUsers.put(member.getId(), user);
                }
            }

            // ✅ CORREÇÃO: Atualizar incrementalmente sem limpar todos os usuários
            final boolean[] hasChanges = { false };

            // Adicionar novos usuários
            for (Map.Entry<String, DiscordUser> entry : currentUsers.entrySet()) {
                if (!usersInChannel.containsKey(entry.getKey())) {
                    usersInChannel.put(entry.getKey(), entry.getValue());
                    hasChanges[0] = true;
                    log.debug("➕ [DiscordService] Usuário adicionado: {}", entry.getValue().getUsername());
                }
            }

            // Remover usuários que saíram
            usersInChannel.entrySet().removeIf(entry -> {
                if (!currentUsers.containsKey(entry.getKey())) {
                    log.debug("➖ [DiscordService] Usuário removido: {}", entry.getValue().getUsername());
                    hasChanges[0] = true;
                    return true;
                }
                return false;
            });

            if (hasChanges[0]) {
                notifyUsersUpdate();
                log.debug("🔄 [DiscordService] Usuários do Discord atualizados: {} usuários", usersInChannel.size());
            }
        } catch (Exception e) {
            log.warn("⚠️ [DiscordService] Erro ao atualizar usuários do Discord: {}", e.getMessage());
        }
    }

    // ✅ NOVO: Método para enviar status do Discord
    private void sendDiscordStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("botUsername", botUsername);
        status.put("isConnected", isConnected);
        status.put("channelName", channelName);
        status.put("usersCount", usersInChannel.size());

        webSocketService.broadcastMessage("discord_status", status);
        log.debug("📡 [DiscordService] Status periódico enviado via WebSocket: {}", status);
    }

    // Public methods
    public boolean isConnected() {
        return isConnected && jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    public String getBotUsername() {
        return botUsername;
    }

    public String getChannelName() {
        return channelName;
    }

    public List<DiscordUser> getUsersInChannel() {
        return new ArrayList<>(usersInChannel.values());
    }

    public int getUsersCount() {
        return usersInChannel.size();
    }

    public void refreshSettings() {
        log.info("🔄 [DiscordService] Atualizando configurações...");
        loadDiscordSettings();

        if (isConnected) {
            jda.shutdown();
            isConnected = false;
        }

        if (discordToken != null && !discordToken.trim().isEmpty()) {
            connectToDiscord();
        }
    }

    public boolean registerSlashCommandsNow() {
        try {
            log.info("🔧 [DiscordService] Registrando comandos slash via API...");

            if (!isConnected) {
                log.warn("⚠️ [DiscordService] Bot não está conectado");
                return false;
            }

            registerSlashCommands();
            return true;

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao registrar comandos via API", e);
            return false;
        }
    }

    // Handlers dos comandos slash
    private void handleVincularCommand(SlashCommandInteractionEvent event) {
        String gameName = event.getOption("nickname") != null ? event.getOption("nickname").getAsString() : null;
        String tagLine = event.getOption("tag") != null ? event.getOption("tag").getAsString() : null;

        if (gameName == null || tagLine == null) {
            event.reply("❌ Uso: `/vincular <nickname> <tag>`\nExemplo: `/vincular PlayerName BR1`").setEphemeral(true)
                    .queue();
            return;
        }

        try {
            // ✅ NOVO: Adicionar # automaticamente se não tiver
            if (!tagLine.startsWith("#")) {
                tagLine = "#" + tagLine;
            }

            // ✅ NOVO: Salvar vinculação no MySQL
            String userId = event.getUser().getId();
            String discordUsername = event.getUser().getName();

            // Salvar no banco de dados
            discordLoLLinkService.createOrUpdateLink(userId, discordUsername, gameName, tagLine, "BR1");

            // Atualizar usuário na memória se ele estiver no canal
            DiscordUser user = usersInChannel.get(userId);
            if (user == null) {
                // Se o usuário não está no canal, criar um usuário temporário para exibição
                log.info("🔗 [DiscordService] Usuário {} não está no canal, criando vinculação temporária",
                        event.getUser().getAsTag());
                user = new DiscordUser();
                user.setId(userId);
                user.setUsername(discordUsername);
                user.setDisplayName(discordUsername);
                user.setInChannel(false); // Não está no canal
                user.setJoinedAt(new Date());
                usersInChannel.put(userId, user);
            }

            // Criar LinkedNickname e aplicar ao usuário
            LinkedNickname linkedNick = new LinkedNickname(gameName, tagLine);
            user.setLinkedNickname(linkedNick);
            user.setHasAppOpen(true);

            log.info("🔗 [DiscordService] Vinculação salva no MySQL e aplicada ao usuário: {} -> {}",
                    user.getDisplayName(), gameName + tagLine);

            // Notificar atualização via WebSocket
            notifyUsersUpdate();

            event.reply("✅ Vinculação criada com sucesso!\n" +
                    "**LoL:** " + gameName + tagLine + "\n" +
                    "**Discord:** " + event.getUser().getAsTag()).queue();

            log.info("🔗 [DiscordService] Vinculação criada: {} -> {}",
                    event.getUser().getAsTag(), gameName + tagLine);

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao vincular conta", e);
            event.reply("❌ Erro ao vincular conta").setEphemeral(true).queue();
        }
    }

    private void handleDesvincularCommand(SlashCommandInteractionEvent event) {
        try {
            // ✅ NOVO: Remover vinculação do MySQL e da memória
            String userId = event.getUser().getId();

            // Buscar vinculação existente para mostrar o que foi removido
            String previousLinked = "nenhum";
            var existingLink = discordLoLLinkService.findByDiscordId(userId);
            if (existingLink.isPresent()) {
                previousLinked = existingLink.get().getFullGameName();
            }

            // Remover do banco de dados
            boolean removed = discordLoLLinkService.removeLink(userId);

            // Remover da memória
            DiscordUser user = usersInChannel.get(userId);
            if (user != null) {
                user.setLinkedNickname(null);
                user.setHasAppOpen(false);
            }

            if (removed) {
                log.info("🔓 [DiscordService] Vinculação removida do MySQL e da memória: {} (era: {})",
                        event.getUser().getAsTag(), previousLinked);

                // Notificar atualização via WebSocket
                notifyUsersUpdate();

                event.reply("✅ Vinculação removida com sucesso!\n" +
                        "**Vinculação anterior:** " + previousLinked).queue();
            } else {
                log.warn("⚠️ [DiscordService] Nenhuma vinculação encontrada para: {}", event.getUser().getAsTag());
                event.reply("❌ Nenhuma vinculação encontrada").setEphemeral(true).queue();
            }

            log.info("🔓 [DiscordService] Comando desvincular executado: {}", event.getUser().getAsTag());

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao desvincular conta", e);
            event.reply("❌ Erro ao desvincular conta").setEphemeral(true).queue();
        }
    }

    private void handleQueueCommand(SlashCommandInteractionEvent event) {
        try {
            int queueSize = discordQueue.size();

            StringBuilder queueList = new StringBuilder();
            if (queueSize == 0) {
                queueList.append("Nenhum jogador na fila");
            } else {
                discordQueue.values().forEach(player -> {
                    String nickname = player.getLinkedNickname() != null
                            ? player.getLinkedNickname().getGameName() + "#" + player.getLinkedNickname().getTagLine()
                            : player.getUsername();
                    queueList.append("• ").append(nickname).append("\n");
                });
            }

            event.reply("🎯 **Fila de Matchmaking**\n\n" +
                    "👥 **Jogadores na Fila:** " + queueSize + "/10\n\n" +
                    "**Lista:**\n" + queueList.toString()).queue();

            log.info("📊 [DiscordService] Status da fila consultado: {} jogadores", queueSize);

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao consultar fila", e);
            event.reply("❌ Erro ao consultar fila").setEphemeral(true).queue();
        }
    }

    private void handleClearQueueCommand(SlashCommandInteractionEvent event) {
        try {
            // TODO: Verificar se o usuário tem permissão de moderador

            discordQueue.clear();
            event.reply("✅ Fila limpa com sucesso!").queue();

            log.info("🧹 [DiscordService] Fila limpa por: {}", event.getUser().getAsTag());

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao limpar fila", e);
            event.reply("❌ Erro ao limpar fila").setEphemeral(true).queue();
        }
    }

    private void handleLobbyCommand(SlashCommandInteractionEvent event) {
        try {
            int usersInLobby = usersInChannel.size();

            StringBuilder lobbyList = new StringBuilder();
            if (usersInLobby == 0) {
                lobbyList.append("Nenhum usuário no lobby");
            } else {
                usersInChannel.values().forEach(user -> {
                    String nickname = user.getLinkedNickname() != null
                            ? user.getLinkedNickname().getGameName() + "#" + user.getLinkedNickname().getTagLine()
                            : user.getDisplayName();
                    lobbyList.append("• ").append(nickname).append("\n");
                });
            }

            event.reply("🎮 **Lobby Discord**\n\n" +
                    "👥 **Usuários no Canal:** " + usersInLobby + "\n\n" +
                    "**Lista:**\n" + lobbyList.toString()).queue();

            log.info("👥 [DiscordService] Lobby consultado: {} usuários", usersInLobby);

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao consultar lobby", e);
            event.reply("❌ Erro ao consultar lobby").setEphemeral(true).queue();
        }
    }

    // Métodos para compatibilidade com outros serviços
    public boolean sendMessage(String channelId, String message) {
        log.info("📤 [DiscordService] Enviando mensagem para canal {}: {}", channelId, message);
        // TODO: Implementar envio de mensagem quando necessário
        return true;
    }

    public boolean notifyMatchFound(String channelId, String message, String[] team1, String[] team2) {
        log.info("🎮 [DiscordService] Notificando partida encontrada: {}", message);
        // TODO: Implementar notificação de partida quando necessário
        return true;
    }

    public boolean notifyDraftStarted(String channelId, String message, String gameId) {
        log.info("📋 [DiscordService] Notificando draft iniciado: {}", message);
        // TODO: Implementar notificação de draft quando necessário
        return true;
    }

    // Inner classes
    public static class DiscordUser {
        private String id;
        private String username;
        private String displayName;
        private boolean inChannel;
        private Date joinedAt;
        private LinkedNickname linkedNickname;
        private boolean hasAppOpen = false; // ✅ NOVO: Indica se o usuário tem o app aberto

        // Getters and setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public boolean isInChannel() {
            return inChannel;
        }

        public void setInChannel(boolean inChannel) {
            this.inChannel = inChannel;
        }

        public Date getJoinedAt() {
            return joinedAt;
        }

        public void setJoinedAt(Date joinedAt) {
            this.joinedAt = joinedAt;
        }

        public LinkedNickname getLinkedNickname() {
            return linkedNickname;
        }

        public void setLinkedNickname(LinkedNickname linkedNickname) {
            this.linkedNickname = linkedNickname;
        }

        public boolean isHasAppOpen() {
            return hasAppOpen;
        }

        public void setHasAppOpen(boolean hasAppOpen) {
            this.hasAppOpen = hasAppOpen;
        }
    }

    public static class LinkedNickname {
        private String gameName;
        private String tagLine;

        public LinkedNickname() {
        }

        public LinkedNickname(String gameName, String tagLine) {
            this.gameName = gameName;
            this.tagLine = tagLine;
        }

        public String getGameName() {
            return gameName;
        }

        public void setGameName(String gameName) {
            this.gameName = gameName;
        }

        public String getTagLine() {
            return tagLine;
        }

        public void setTagLine(String tagLine) {
            this.tagLine = tagLine;
        }
    }

    public static class DiscordPlayer {
        private String userId;
        private String username;
        private String role;
        private long timestamp;
        private LinkedNickname linkedNickname;

        public DiscordPlayer() {
            this.timestamp = System.currentTimeMillis();
        }

        // Getters and setters
        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        public LinkedNickname getLinkedNickname() {
            return linkedNickname;
        }

        public void setLinkedNickname(LinkedNickname linkedNickname) {
            this.linkedNickname = linkedNickname;
        }
    }

    // ✅ NOVO: Método getter para o token do Discord
    public String getDiscordToken() {
        return discordToken;
    }
}