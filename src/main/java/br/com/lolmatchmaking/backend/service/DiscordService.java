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
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.ChannelType;
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

    // ✅ NOVO: Redis cache service
    private final br.com.lolmatchmaking.backend.service.redis.RedisDiscordCacheService redisDiscordCache;

    private JDA jda;
    private String discordToken;
    private String discordChannelName; // Mudança: usar nome do canal em vez de ID
    private VoiceChannel monitoredChannel;
    private final Map<String, DiscordUser> usersInChannel = new ConcurrentHashMap<>();
    private final Map<String, DiscordPlayer> discordQueue = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    // ✅ NOVO: Armazenamento de partidas ativas com canais Discord
    private final Map<Long, DiscordMatch> activeMatches = new ConcurrentHashMap<>();

    private boolean isConnected = false;
    private String botUsername;
    private String channelName;

    @PostConstruct
    public void initialize() {
        log.info("🤖 [DiscordService] Inicializando serviço Discord...");
        loadDiscordSettings();

        if (discordToken != null && !discordToken.trim().isEmpty()) {
            connectToDiscord();

            // ✅ OTIMIZADO: Timer para atualizações periódicas como fallback (reduzido de
            // 120s para 5 minutos)
            // Nota: Eventos em tempo real (onGuildVoiceUpdate) são a fonte primária
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (isConnected && monitoredChannel != null) {
                        updateDiscordUsers();
                        sendDiscordStatus();
                    }
                } catch (Exception e) {
                    log.warn("⚠️ [DiscordService] Erro na atualização periódica: {}", e.getMessage());
                }
            }, 300, 300, TimeUnit.SECONDS); // A cada 5 minutos - apenas fallback de segurança
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

    /**
     * ✅ OTIMIZADO: Polling reduzido para fallback apenas
     * Eventos em tempo real (onGuildVoiceUpdate) são a fonte primária
     */
    private void startPeriodicMonitoring() {
        // ✅ OTIMIZAÇÃO: Reduzido de 30s para 2 minutos (apenas fallback de segurança)
        scheduler.scheduleAtFixedRate(() -> {
            if (isConnected && monitoredChannel != null) {
                loadCurrentUsersInChannel();
            }
        }, 120, 120, TimeUnit.SECONDS); // A cada 2 minutos - apenas fallback
    }

    // ✅ Event listeners - FONTE PRIMÁRIA de atualizações em tempo real
    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        String leftChannelName = event.getChannelLeft() != null ? event.getChannelLeft().getName() : null;
        String joinedChannelName = event.getChannelJoined() != null ? event.getChannelJoined().getName() : null;

        if (leftChannelName != null && leftChannelName.equals(discordChannelName)) {
            log.info("👋 [DiscordService] Usuário saiu do canal: {}", event.getMember().getEffectiveName());
            usersInChannel.remove(event.getMember().getId());
            notifyUsersUpdate(); // ✅ Atualiza cache Redis + WebSocket broadcast
        } else if (joinedChannelName != null && joinedChannelName.equals(discordChannelName)) {
            log.info("👋 [DiscordService] Usuário entrou no canal: {}", event.getMember().getEffectiveName());
            DiscordUser user = createDiscordUser(event.getMember());
            usersInChannel.put(user.getId(), user);
            notifyUsersUpdate(); // ✅ Atualiza cache Redis + WebSocket broadcast
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

    /**
     * ✅ OTIMIZADO: Notifica status do Discord com cache Redis
     */
    private void notifyDiscordStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("isConnected", isConnected);
        status.put("botUsername", botUsername);
        status.put("channelName", channelName);
        status.put("usersCount", usersInChannel.size());

        // ✅ NOVO: Cachear status no Redis
        try {
            redisDiscordCache.cacheStatus(status);
        } catch (Exception e) {
            log.debug("⚠️ [DiscordService] Erro ao cachear status (não crítico): {}", e.getMessage());
        }

        webSocketService.broadcastMessage("discord_status", status);
        log.info("📡 [DiscordService] Status enviado via WebSocket: {}", status);
    }

    /**
     * ✅ OTIMIZADO: Notifica atualização de usuários com cache Redis
     * Atualiza cache antes do broadcast para garantir consistência
     */
    private void notifyUsersUpdate() {
        List<DiscordUser> users = new ArrayList<>(usersInChannel.values());

        // ✅ NOVO: Cachear usuários no Redis ANTES do broadcast
        // Isso garante que outros backends/instâncias vejam os dados atualizados
        try {
            redisDiscordCache.cacheUsers(users);
            log.debug("✅ [DiscordService] {} usuários cacheados no Redis", users.size());
        } catch (Exception e) {
            log.debug("⚠️ [DiscordService] Erro ao cachear usuários (não crítico): {}", e.getMessage());
        }

        // Broadcast para todos os clientes WebSocket
        webSocketService.broadcastMessage("discord_users", Map.of("users", users));
        log.info("📡 [DiscordService] {} usuários enviados via WebSocket", users.size());
    }

    /**
     * ✅ OTIMIZADO: Atualiza usuários do Discord (usado como fallback de segurança)
     * Eventos em tempo real (onGuildVoiceUpdate) são a fonte primária
     */
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

            // ✅ PROTEÇÃO: NÃO enviar lista vazia - causa reconexões falsas no frontend
            if (currentUsers.isEmpty() && !usersInChannel.isEmpty()) {
                log.debug(
                        "⚠️ [DiscordService] Lista vazia recebida mas cache tem {} usuários - ignorando atualização vazia",
                        usersInChannel.size());
                return;
            }

            // ✅ OTIMIZAÇÃO: Atualizar incrementalmente sem limpar todos os usuários
            final boolean[] hasChanges = { false };

            // Adicionar novos usuários
            for (Map.Entry<String, DiscordUser> entry : currentUsers.entrySet()) {
                if (!usersInChannel.containsKey(entry.getKey())) {
                    usersInChannel.put(entry.getKey(), entry.getValue());
                    hasChanges[0] = true;
                    log.debug("➕ [DiscordService] Usuário adicionado (fallback): {}", entry.getValue().getUsername());
                }
            }

            // Remover usuários que saíram
            usersInChannel.entrySet().removeIf(entry -> {
                if (!currentUsers.containsKey(entry.getKey())) {
                    log.debug("➖ [DiscordService] Usuário removido (fallback): {}", entry.getValue().getUsername());
                    hasChanges[0] = true;
                    return true;
                }
                return false;
            });

            if (hasChanges[0]) {
                notifyUsersUpdate();
                log.info("🔄 [DiscordService] Usuários atualizados via fallback: {} usuários", usersInChannel.size());
            }
        } catch (Exception e) {
            log.warn("⚠️ [DiscordService] Erro ao atualizar usuários do Discord: {}", e.getMessage());
        }
    }

    /**
     * ✅ OTIMIZADO: Envia status do Discord (usado como fallback de segurança)
     */
    private void sendDiscordStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("botUsername", botUsername);
        status.put("isConnected", isConnected);
        status.put("channelName", channelName);
        status.put("usersCount", usersInChannel.size());

        // ✅ NOVO: Cachear status no Redis
        try {
            redisDiscordCache.cacheStatus(status);
        } catch (Exception e) {
            log.debug("⚠️ [DiscordService] Erro ao cachear status (não crítico): {}", e.getMessage());
        }

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

    /**
     * ✅ OTIMIZADO: Retorna usuários do canal (usa cache Redis se disponível)
     */
    public List<DiscordUser> getUsersInChannel() {
        // ✅ NOVO: Tentar buscar do cache Redis primeiro (melhor performance
        // multi-instância)
        try {
            List<Object> cachedUsers = redisDiscordCache.getCachedUsers();
            if (cachedUsers != null && !cachedUsers.isEmpty()) {
                log.debug("⚡ [DiscordService] Usuários retornados do cache Redis");
                // Converter List<Object> para List<DiscordUser>
                List<DiscordUser> users = new ArrayList<>();
                for (Object obj : cachedUsers) {
                    if (obj instanceof DiscordUser) {
                        users.add((DiscordUser) obj);
                    }
                }
                if (!users.isEmpty()) {
                    return users;
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [DiscordService] Cache miss ou erro (usando memória local): {}", e.getMessage());
        }

        // Fallback: retornar da memória local
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

    // ========================================
    // ✅ AUTOMAÇÃO DE CANAIS DISCORD
    // ========================================

    /**
     * Cria canais temporários de time para uma partida
     * 
     * @param matchId               ID da partida
     * @param blueTeamSummonerNames Lista de summoner names (gameName#tagLine) do
     *                              time azul
     * @param redTeamSummonerNames  Lista de summoner names (gameName#tagLine) do
     *                              time vermelho
     * @return DiscordMatch com os canais criados ou null se falhar
     */
    public DiscordMatch createMatchChannels(Long matchId,
            List<String> blueTeamSummonerNames,
            List<String> redTeamSummonerNames) {

        if (!isConnected || jda == null) {
            log.warn("⚠️ [DiscordService] Discord não conectado, não é possível criar canais");
            return null;
        }

        try {
            log.info("🎮 [createMatchChannels] Criando canais para match {}", matchId);
            log.info("🔵 [createMatchChannels] Blue Team: {}", blueTeamSummonerNames);
            log.info("🔴 [createMatchChannels] Red Team: {}", redTeamSummonerNames);

            // 1. Encontrar guild
            Guild guild = jda.getGuilds().stream().findFirst().orElse(null);
            if (guild == null) {
                log.error("❌ [createMatchChannels] Guild não encontrada");
                return null;
            }

            // 2. Criar categoria
            String categoryName = "Match #" + matchId;
            Category category = guild.createCategory(categoryName).complete();
            log.info("✅ [createMatchChannels] Categoria criada: {} ({})", category.getName(), category.getId());

            // 3. Criar canal Blue Team
            VoiceChannel blueChannel = guild.createVoiceChannel("🔵-blue-team-" + matchId)
                    .setParent(category)
                    .complete();
            log.info("✅ [createMatchChannels] Canal Blue criado: {} ({})", blueChannel.getName(), blueChannel.getId());

            // 4. Criar canal Red Team
            VoiceChannel redChannel = guild.createVoiceChannel("🔴-red-team-" + matchId)
                    .setParent(category)
                    .complete();
            log.info("✅ [createMatchChannels] Canal Red criado: {} ({})", redChannel.getName(), redChannel.getId());

            // 5. Converter summoner names para Discord IDs
            List<String> blueTeamDiscordIds = new ArrayList<>();
            List<String> redTeamDiscordIds = new ArrayList<>();

            for (String summonerName : blueTeamSummonerNames) {
                String discordId = findDiscordIdBySummonerName(summonerName);
                if (discordId != null) {
                    blueTeamDiscordIds.add(discordId);
                    log.info("🔗 [createMatchChannels] Blue: {} -> Discord ID: {}", summonerName, discordId);
                } else {
                    log.warn("⚠️ [createMatchChannels] Blue: {} não tem vinculação Discord", summonerName);
                }
            }

            for (String summonerName : redTeamSummonerNames) {
                String discordId = findDiscordIdBySummonerName(summonerName);
                if (discordId != null) {
                    redTeamDiscordIds.add(discordId);
                    log.info("🔗 [createMatchChannels] Red: {} -> Discord ID: {}", summonerName, discordId);
                } else {
                    log.warn("⚠️ [createMatchChannels] Red: {} não tem vinculação Discord", summonerName);
                }
            }

            // 6. Criar objeto DiscordMatch
            DiscordMatch match = new DiscordMatch();
            match.setMatchId(matchId);
            match.setCategoryId(category.getId());
            match.setBlueChannelId(blueChannel.getId());
            match.setRedChannelId(redChannel.getId());
            match.setBlueTeamDiscordIds(blueTeamDiscordIds);
            match.setRedTeamDiscordIds(redTeamDiscordIds);

            // 7. Armazenar em activeMatches
            activeMatches.put(matchId, match);

            log.info("✅ [createMatchChannels] Match {} criado com sucesso! Blue: {}, Red: {}",
                    matchId, blueTeamDiscordIds.size(), redTeamDiscordIds.size());

            // 8. Agendar timeout de limpeza (3 horas)
            scheduler.schedule(() -> {
                if (activeMatches.containsKey(matchId)) {
                    log.warn("⚠️ [createMatchChannels] Match {} excedeu timeout de 3 horas, limpando...", matchId);
                    deleteMatchChannels(matchId, true);
                }
            }, 3, TimeUnit.HOURS);

            return match;

        } catch (Exception e) {
            log.error("❌ [createMatchChannels] Erro ao criar canais para match {}", matchId, e);
            return null;
        }
    }

    /**
     * Move jogadores para seus respectivos canais de time
     * 
     * @param matchId ID da partida
     */
    public void movePlayersToTeamChannels(Long matchId) {
        DiscordMatch match = activeMatches.get(matchId);
        if (match == null) {
            log.warn("⚠️ [movePlayersToTeamChannels] Match {} não encontrado", matchId);
            return;
        }

        if (!isConnected || jda == null) {
            log.warn("⚠️ [movePlayersToTeamChannels] Discord não conectado");
            return;
        }

        try {
            Guild guild = jda.getGuilds().stream().findFirst().orElse(null);
            if (guild == null) {
                log.error("❌ [movePlayersToTeamChannels] Guild não encontrada");
                return;
            }

            VoiceChannel blueChannel = guild.getVoiceChannelById(match.getBlueChannelId());
            VoiceChannel redChannel = guild.getVoiceChannelById(match.getRedChannelId());

            if (blueChannel == null || redChannel == null) {
                log.error("❌ [movePlayersToTeamChannels] Canais não encontrados");
                return;
            }

            log.info("🔄 [movePlayersToTeamChannels] Movendo jogadores para match {}", matchId);

            int movedBlue = 0;
            int movedRed = 0;

            // Mover Blue Team
            for (String discordId : match.getBlueTeamDiscordIds()) {
                Member member = guild.getMemberById(discordId);
                if (member != null && member.getVoiceState() != null && member.getVoiceState().getChannel() != null) {
                    VoiceChannel currentChannel = (VoiceChannel) member.getVoiceState().getChannel();

                    // Salvar canal original
                    match.getOriginalChannels().put(discordId, currentChannel.getId());

                    // Mover para Blue Team
                    guild.moveVoiceMember(member, blueChannel).queue(
                            success -> log.info("✅ [movePlayersToTeamChannels] 🔵 {} movido de {} para {}",
                                    member.getEffectiveName(), currentChannel.getName(), blueChannel.getName()),
                            error -> log.error("❌ [movePlayersToTeamChannels] Erro ao mover {}: {}",
                                    member.getEffectiveName(), error.getMessage()));
                    movedBlue++;
                } else {
                    log.warn("⚠️ [movePlayersToTeamChannels] Jogador Blue {} não está em canal de voz", discordId);
                }
            }

            // Mover Red Team
            for (String discordId : match.getRedTeamDiscordIds()) {
                Member member = guild.getMemberById(discordId);
                if (member != null && member.getVoiceState() != null && member.getVoiceState().getChannel() != null) {
                    VoiceChannel currentChannel = (VoiceChannel) member.getVoiceState().getChannel();

                    // Salvar canal original
                    match.getOriginalChannels().put(discordId, currentChannel.getId());

                    // Mover para Red Team
                    guild.moveVoiceMember(member, redChannel).queue(
                            success -> log.info("✅ [movePlayersToTeamChannels] 🔴 {} movido de {} para {}",
                                    member.getEffectiveName(), currentChannel.getName(), redChannel.getName()),
                            error -> log.error("❌ [movePlayersToTeamChannels] Erro ao mover {}: {}",
                                    member.getEffectiveName(), error.getMessage()));
                    movedRed++;
                } else {
                    log.warn("⚠️ [movePlayersToTeamChannels] Jogador Red {} não está em canal de voz", discordId);
                }
            }

            log.info("✅ [movePlayersToTeamChannels] Match {}: {} Blue movidos, {} Red movidos",
                    matchId, movedBlue, movedRed);

        } catch (Exception e) {
            log.error("❌ [movePlayersToTeamChannels] Erro ao mover jogadores", e);
        }
    }

    /**
     * Deleta canais de uma partida e move jogadores de volta
     * 
     * @param matchId         ID da partida
     * @param movePlayersBack Se true, move jogadores de volta ao canal original
     */
    public void deleteMatchChannels(Long matchId, boolean movePlayersBack) {
        DiscordMatch match = activeMatches.get(matchId);
        if (match == null) {
            log.warn("⚠️ [deleteMatchChannels] Match {} não encontrado", matchId);
            return;
        }

        if (!isConnected || jda == null) {
            log.warn("⚠️ [deleteMatchChannels] Discord não conectado");
            activeMatches.remove(matchId);
            return;
        }

        try {
            Guild guild = jda.getGuilds().stream().findFirst().orElse(null);
            if (guild == null) {
                log.error("❌ [deleteMatchChannels] Guild não encontrada");
                activeMatches.remove(matchId);
                return;
            }

            log.info("🧹 [deleteMatchChannels] Limpando match {}, movePlayersBack={}", matchId, movePlayersBack);

            // 1. Mover TODOS os usuários de volta (jogadores + espectadores)
            if (movePlayersBack) {
                VoiceChannel mainChannel = guild.getVoiceChannels().stream()
                        .filter(ch -> ch.getName().equals(discordChannelName))
                        .findFirst()
                        .orElse(null);

                if (mainChannel == null) {
                    log.warn("⚠️ [deleteMatchChannels] Canal principal '{}' não encontrado", discordChannelName);
                }

                // ✅ NOVO: Pegar TODOS os membros nos canais Blue e Red (jogadores +
                // espectadores)
                VoiceChannel blueChannel = guild.getVoiceChannelById(match.getBlueChannelId());
                VoiceChannel redChannel = guild.getVoiceChannelById(match.getRedChannelId());

                Set<Member> allMembers = new HashSet<>();

                if (blueChannel != null) {
                    allMembers.addAll(blueChannel.getMembers());
                    log.info("👥 [deleteMatchChannels] {} membros no canal Blue", blueChannel.getMembers().size());
                }

                if (redChannel != null) {
                    allMembers.addAll(redChannel.getMembers());
                    log.info("👥 [deleteMatchChannels] {} membros no canal Red", redChannel.getMembers().size());
                }

                log.info("👥 [deleteMatchChannels] Total de {} membros para mover de volta", allMembers.size());

                // Mover cada membro de volta
                for (Member member : allMembers) {
                    if (member.getVoiceState() != null && member.getVoiceState().getChannel() != null) {

                        // Tentar mover para canal original
                        String originalChannelId = match.getOriginalChannels().get(member.getId());
                        VoiceChannel targetChannel = null;

                        if (originalChannelId != null) {
                            targetChannel = guild.getVoiceChannelById(originalChannelId);
                        }

                        // Se canal original não existe mais, usar canal principal
                        if (targetChannel == null) {
                            targetChannel = mainChannel;
                        }

                        if (targetChannel != null) {
                            final VoiceChannel finalTarget = targetChannel;
                            guild.moveVoiceMember(member, targetChannel).queue(
                                    success -> log.info("✅ [deleteMatchChannels] {} movido de volta para {}",
                                            member.getEffectiveName(), finalTarget.getName()),
                                    error -> log.error("❌ [deleteMatchChannels] Erro ao mover {}: {}",
                                            member.getEffectiveName(), error.getMessage()));
                        }
                    }
                }

                // Aguardar 2 segundos para completar movimentações
                Thread.sleep(2000);
            }

            // 2. Deletar canais Blue e Red
            VoiceChannel blueChannel = guild.getVoiceChannelById(match.getBlueChannelId());
            if (blueChannel != null) {
                blueChannel.delete().queue(
                        success -> log.info("✅ [deleteMatchChannels] Canal Blue deletado"),
                        error -> log.error("❌ [deleteMatchChannels] Erro ao deletar Blue: {}", error.getMessage()));
            }

            VoiceChannel redChannel = guild.getVoiceChannelById(match.getRedChannelId());
            if (redChannel != null) {
                redChannel.delete().queue(
                        success -> log.info("✅ [deleteMatchChannels] Canal Red deletado"),
                        error -> log.error("❌ [deleteMatchChannels] Erro ao deletar Red: {}", error.getMessage()));
            }

            // 3. Deletar categoria
            Category category = guild.getCategoryById(match.getCategoryId());
            if (category != null) {
                // Aguardar um pouco para os canais serem deletados primeiro
                scheduler.schedule(() -> {
                    category.delete().queue(
                            success -> log.info("✅ [deleteMatchChannels] Categoria deletada"),
                            error -> log.error("❌ [deleteMatchChannels] Erro ao deletar categoria: {}",
                                    error.getMessage()));
                }, 3, TimeUnit.SECONDS);
            }

            // 4. Remover do cache
            activeMatches.remove(matchId);

            log.info("✅ [deleteMatchChannels] Match {} limpo com sucesso", matchId);

        } catch (Exception e) {
            log.error("❌ [deleteMatchChannels] Erro ao limpar match {}", matchId, e);
            activeMatches.remove(matchId);
        }
    }

    /**
     * Move apenas espectadores (quem está assistindo) de volta ao canal principal
     * 
     * @param matchId ID da partida
     */
    public void moveSpectatorsBackToLobby(Long matchId) {
        DiscordMatch match = activeMatches.get(matchId);
        if (match == null) {
            log.warn("⚠️ [moveSpectatorsBackToLobby] Match {} não encontrado", matchId);
            return;
        }

        if (!isConnected || jda == null) {
            log.warn("⚠️ [moveSpectatorsBackToLobby] Discord não conectado");
            return;
        }

        try {
            Guild guild = jda.getGuilds().stream().findFirst().orElse(null);
            if (guild == null) {
                log.error("❌ [moveSpectatorsBackToLobby] Guild não encontrada");
                return;
            }

            VoiceChannel blueChannel = guild.getVoiceChannelById(match.getBlueChannelId());
            VoiceChannel redChannel = guild.getVoiceChannelById(match.getRedChannelId());
            VoiceChannel mainChannel = guild.getVoiceChannels().stream()
                    .filter(ch -> ch.getName().equals(discordChannelName))
                    .findFirst()
                    .orElse(null);

            if (mainChannel == null) {
                log.warn("⚠️ [moveSpectatorsBackToLobby] Canal principal '{}' não encontrado", discordChannelName);
                return;
            }

            log.info("👥 [moveSpectatorsBackToLobby] Movendo espectadores do match {} de volta ao lobby", matchId);

            // Combinar IDs dos jogadores reais
            List<String> realPlayerIds = new ArrayList<>();
            realPlayerIds.addAll(match.getBlueTeamDiscordIds());
            realPlayerIds.addAll(match.getRedTeamDiscordIds());

            int movedSpectators = 0;

            // Verificar Blue Channel
            if (blueChannel != null) {
                for (Member member : blueChannel.getMembers()) {
                    if (!realPlayerIds.contains(member.getId())) {
                        guild.moveVoiceMember(member, mainChannel).queue(
                                success -> log.info(
                                        "✅ [moveSpectatorsBackToLobby] Espectador {} movido de Blue para lobby",
                                        member.getEffectiveName()),
                                error -> log.error("❌ [moveSpectatorsBackToLobby] Erro ao mover {}: {}",
                                        member.getEffectiveName(), error.getMessage()));
                        movedSpectators++;
                    }
                }
            }

            // Verificar Red Channel
            if (redChannel != null) {
                for (Member member : redChannel.getMembers()) {
                    if (!realPlayerIds.contains(member.getId())) {
                        guild.moveVoiceMember(member, mainChannel).queue(
                                success -> log.info(
                                        "✅ [moveSpectatorsBackToLobby] Espectador {} movido de Red para lobby",
                                        member.getEffectiveName()),
                                error -> log.error("❌ [moveSpectatorsBackToLobby] Erro ao mover {}: {}",
                                        member.getEffectiveName(), error.getMessage()));
                        movedSpectators++;
                    }
                }
            }

            log.info("✅ [moveSpectatorsBackToLobby] {} espectadores movidos de volta ao lobby", movedSpectators);

        } catch (Exception e) {
            log.error("❌ [moveSpectatorsBackToLobby] Erro ao mover espectadores", e);
        }
    }

    /**
     * Método auxiliar para encontrar Discord ID por summoner name
     * 
     * @param summonerName gameName#tagLine
     * @return Discord ID ou null se não encontrado
     */
    private String findDiscordIdBySummonerName(String summonerName) {
        try {
            // PRIMEIRA TENTATIVA: Buscar direto pela coluna summoner_name (mais preciso)
            Optional<DiscordLoLLink> linkBySummonerName = discordLoLLinkService.findBySummonerName(summonerName);

            if (linkBySummonerName.isPresent()) {
                log.debug(
                        "✅ [findDiscordIdBySummonerName] Vinculação encontrada por summoner_name: {} -> Discord ID: {}",
                        summonerName, linkBySummonerName.get().getDiscordId());
                return linkBySummonerName.get().getDiscordId();
            }

            // SEGUNDA TENTATIVA (fallback): Separar gameName#tagLine e buscar
            String gameName;
            String tagLine;

            if (summonerName.contains("#")) {
                String[] parts = summonerName.split("#");
                if (parts.length != 2) {
                    log.warn("⚠️ [findDiscordIdBySummonerName] Formato inválido: {}", summonerName);
                    return null;
                }
                gameName = parts[0];
                tagLine = parts[1];
            } else {
                // Bots ou jogadores sem tagLine (fallback: usar apenas gameName)
                gameName = summonerName;
                tagLine = ""; // TagLine vazio para bots
                log.debug("🤖 [findDiscordIdBySummonerName] Summoner sem tagLine (bot?): {}", summonerName);
            }

            // Buscar por gameName + tagLine
            Optional<DiscordLoLLink> link = discordLoLLinkService.findByGameNameAndTagLine(gameName, tagLine);

            if (link.isPresent()) {
                log.debug(
                        "✅ [findDiscordIdBySummonerName] Vinculação encontrada por gameName+tagLine: {} -> Discord ID: {}",
                        summonerName, link.get().getDiscordId());
                return link.get().getDiscordId();
            } else {
                log.warn(
                        "⚠️ [findDiscordIdBySummonerName] Vinculação não encontrada para {} (tentou summoner_name, gameName={}, tagLine={})",
                        summonerName, gameName, tagLine);
                return null;
            }

        } catch (Exception e) {
            log.error("❌ [findDiscordIdBySummonerName] Erro ao buscar Discord ID para {}", summonerName, e);
            return null;
        }
    }

    // ========================================
    // ✅ GERENCIAMENTO DE ESPECTADORES
    // ========================================

    /**
     * Lista espectadores de uma partida (excluindo os 10 jogadores)
     * 
     * @param matchId ID da partida
     * @return Lista de espectadores
     */
    public List<SpectatorDTO> getMatchSpectators(Long matchId) {
        DiscordMatch match = activeMatches.get(matchId);
        if (match == null) {
            log.warn("⚠️ [getMatchSpectators] Match {} não encontrado", matchId);
            return Collections.emptyList();
        }

        if (!isConnected || jda == null) {
            log.warn("⚠️ [getMatchSpectators] Discord não conectado");
            return Collections.emptyList();
        }

        try {
            Guild guild = jda.getGuilds().stream().findFirst().orElse(null);
            if (guild == null) {
                log.warn("⚠️ [getMatchSpectators] Guild não encontrada");
                return Collections.emptyList();
            }

            VoiceChannel blueChannel = guild.getVoiceChannelById(match.getBlueChannelId());
            VoiceChannel redChannel = guild.getVoiceChannelById(match.getRedChannelId());

            List<SpectatorDTO> spectators = new ArrayList<>();

            // IDs dos jogadores reais da partida (não são espectadores)
            List<String> realPlayerIds = new ArrayList<>();
            realPlayerIds.addAll(match.getBlueTeamDiscordIds());
            realPlayerIds.addAll(match.getRedTeamDiscordIds());

            // Verificar Blue Channel
            if (blueChannel != null) {
                for (Member member : blueChannel.getMembers()) {
                    if (!realPlayerIds.contains(member.getId())) {
                        boolean isMuted = member.getVoiceState() != null && member.getVoiceState().isMuted();
                        spectators.add(new SpectatorDTO(
                                member.getId(),
                                member.getEffectiveName(),
                                "Blue Team",
                                isMuted));
                        log.debug("👀 [getMatchSpectators] Espectador encontrado em Blue: {} (mutado: {})",
                                member.getEffectiveName(), isMuted);
                    }
                }
            }

            // Verificar Red Channel
            if (redChannel != null) {
                for (Member member : redChannel.getMembers()) {
                    if (!realPlayerIds.contains(member.getId())) {
                        boolean isMuted = member.getVoiceState() != null && member.getVoiceState().isMuted();
                        spectators.add(new SpectatorDTO(
                                member.getId(),
                                member.getEffectiveName(),
                                "Red Team",
                                isMuted));
                        log.debug("👀 [getMatchSpectators] Espectador encontrado em Red: {} (mutado: {})",
                                member.getEffectiveName(), isMuted);
                    }
                }
            }

            log.info("👥 [getMatchSpectators] Match {}: {} espectadores encontrados", matchId, spectators.size());
            return spectators;

        } catch (Exception e) {
            log.error("❌ [getMatchSpectators] Erro ao listar espectadores", e);
            return Collections.emptyList();
        }
    }

    /**
     * Muta um espectador no Discord (SERVER MUTE - espectador não pode se desmutar)
     * 
     * @param matchId   ID da partida
     * @param discordId Discord ID do espectador
     * @return true se mutou com sucesso
     */
    public boolean muteSpectator(Long matchId, String discordId) {
        DiscordMatch match = activeMatches.get(matchId);
        if (match == null) {
            log.warn("⚠️ [muteSpectator] Match {} não encontrado", matchId);
            return false;
        }

        if (!isConnected || jda == null) {
            log.warn("⚠️ [muteSpectator] Discord não conectado");
            return false;
        }

        try {
            Guild guild = jda.getGuilds().stream().findFirst().orElse(null);
            if (guild == null) {
                log.warn("⚠️ [muteSpectator] Guild não encontrada");
                return false;
            }

            Member member = guild.getMemberById(discordId);
            if (member == null) {
                log.warn("⚠️ [muteSpectator] Membro {} não encontrado", discordId);
                return false;
            }

            // ✅ SERVER MUTE (guild.mute) - espectador NÃO consegue se desmutar sozinho
            member.mute(true).queue(
                    success -> log.info("✅ [muteSpectator] 🔇 Espectador {} mutado (SERVER MUTE)",
                            member.getEffectiveName()),
                    error -> log.error("❌ [muteSpectator] Erro ao mutar {}: {}", member.getEffectiveName(),
                            error.getMessage()));

            return true;

        } catch (Exception e) {
            log.error("❌ [muteSpectator] Erro ao mutar espectador", e);
            return false;
        }
    }

    /**
     * Desmuta um espectador no Discord (remove SERVER MUTE)
     * 
     * @param matchId   ID da partida
     * @param discordId Discord ID do espectador
     * @return true se desmutou com sucesso
     */
    public boolean unmuteSpectator(Long matchId, String discordId) {
        DiscordMatch match = activeMatches.get(matchId);
        if (match == null) {
            log.warn("⚠️ [unmuteSpectator] Match {} não encontrado", matchId);
            return false;
        }

        if (!isConnected || jda == null) {
            log.warn("⚠️ [unmuteSpectator] Discord não conectado");
            return false;
        }

        try {
            Guild guild = jda.getGuilds().stream().findFirst().orElse(null);
            if (guild == null) {
                log.warn("⚠️ [unmuteSpectator] Guild não encontrada");
                return false;
            }

            Member member = guild.getMemberById(discordId);
            if (member == null) {
                log.warn("⚠️ [unmuteSpectator] Membro {} não encontrado", discordId);
                return false;
            }

            // Remove SERVER MUTE
            member.mute(false).queue(
                    success -> log.info("✅ [unmuteSpectator] 🔊 Espectador {} desmutado", member.getEffectiveName()),
                    error -> log.error("❌ [unmuteSpectator] Erro ao desmutar {}: {}", member.getEffectiveName(),
                            error.getMessage()));

            return true;

        } catch (Exception e) {
            log.error("❌ [unmuteSpectator] Erro", e);
            return false;
        }
    }

    /**
     * ✅ NOVO: Verifica se um espectador está mutado
     * 
     * @param matchId   ID da partida
     * @param discordId Discord ID do espectador
     * @return true se está mutado, false caso contrário
     */
    public boolean isSpectatorMuted(Long matchId, String discordId) {
        DiscordMatch match = activeMatches.get(matchId);
        if (match == null) {
            log.warn("⚠️ [isSpectatorMuted] Match {} não encontrado", matchId);
            return false;
        }

        if (!isConnected || jda == null) {
            log.warn("⚠️ [isSpectatorMuted] Discord não conectado");
            return false;
        }

        try {
            Guild guild = jda.getGuilds().stream().findFirst().orElse(null);
            if (guild == null) {
                log.warn("⚠️ [isSpectatorMuted] Guild não encontrada");
                return false;
            }

            Member member = guild.getMemberById(discordId);
            if (member == null) {
                log.warn("⚠️ [isSpectatorMuted] Membro {} não encontrado", discordId);
                return false;
            }

            // Verificar estado de mute via VoiceState
            boolean isMuted = member.getVoiceState() != null && member.getVoiceState().isMuted();

            log.debug("🔍 [isSpectatorMuted] Espectador {} está mutado: {}", member.getEffectiveName(), isMuted);
            return isMuted;

        } catch (Exception e) {
            log.error("❌ [isSpectatorMuted] Erro ao verificar estado de mute", e);
            return false;
        }
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

    // ✅ NOVO: Classe para representar uma partida ativa com canais Discord
    public static class DiscordMatch {
        private Long matchId;
        private String categoryId; // ID da categoria Discord
        private String blueChannelId; // ID do canal Blue Team
        private String redChannelId; // ID do canal Red Team
        private List<String> blueTeamDiscordIds; // IDs Discord do time azul
        private List<String> redTeamDiscordIds; // IDs Discord do time vermelho
        private Map<String, String> originalChannels; // discordId -> originalChannelId
        private java.time.Instant createdAt;

        public DiscordMatch() {
            this.originalChannels = new HashMap<>();
            this.blueTeamDiscordIds = new ArrayList<>();
            this.redTeamDiscordIds = new ArrayList<>();
            this.createdAt = java.time.Instant.now();
        }

        // Getters e Setters
        public Long getMatchId() {
            return matchId;
        }

        public void setMatchId(Long matchId) {
            this.matchId = matchId;
        }

        public String getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(String categoryId) {
            this.categoryId = categoryId;
        }

        public String getBlueChannelId() {
            return blueChannelId;
        }

        public void setBlueChannelId(String blueChannelId) {
            this.blueChannelId = blueChannelId;
        }

        public String getRedChannelId() {
            return redChannelId;
        }

        public void setRedChannelId(String redChannelId) {
            this.redChannelId = redChannelId;
        }

        public List<String> getBlueTeamDiscordIds() {
            return blueTeamDiscordIds;
        }

        public void setBlueTeamDiscordIds(List<String> blueTeamDiscordIds) {
            this.blueTeamDiscordIds = blueTeamDiscordIds;
        }

        public List<String> getRedTeamDiscordIds() {
            return redTeamDiscordIds;
        }

        public void setRedTeamDiscordIds(List<String> redTeamDiscordIds) {
            this.redTeamDiscordIds = redTeamDiscordIds;
        }

        public Map<String, String> getOriginalChannels() {
            return originalChannels;
        }

        public void setOriginalChannels(Map<String, String> originalChannels) {
            this.originalChannels = originalChannels;
        }

        public java.time.Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(java.time.Instant createdAt) {
            this.createdAt = createdAt;
        }
    }

    // ✅ NOVO: Classe para representar espectadores
    public static class SpectatorDTO {
        private String discordId;
        private String discordUsername;
        private String channelName; // "Blue Team" ou "Red Team"
        private boolean isMuted;

        public SpectatorDTO(String discordId, String discordUsername, String channelName, boolean isMuted) {
            this.discordId = discordId;
            this.discordUsername = discordUsername;
            this.channelName = channelName;
            this.isMuted = isMuted;
        }

        // Getters and Setters
        public String getDiscordId() {
            return discordId;
        }

        public void setDiscordId(String discordId) {
            this.discordId = discordId;
        }

        public String getDiscordUsername() {
            return discordUsername;
        }

        public void setDiscordUsername(String discordUsername) {
            this.discordUsername = discordUsername;
        }

        public String getChannelName() {
            return channelName;
        }

        public void setChannelName(String channelName) {
            this.channelName = channelName;
        }

        public boolean isMuted() {
            return isMuted;
        }

        public void setMuted(boolean muted) {
            isMuted = muted;
        }
    }
}
