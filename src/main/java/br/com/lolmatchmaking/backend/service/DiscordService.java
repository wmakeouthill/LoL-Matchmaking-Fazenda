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

    // ✅ NOVO: Redis services
    private final br.com.lolmatchmaking.backend.service.redis.RedisDiscordCacheService redisDiscordCache;
    private final br.com.lolmatchmaking.backend.service.redis.RedisDiscordMatchService redisDiscordMatch;
    private final br.com.lolmatchmaking.backend.service.redis.RedisSpectatorService redisSpectator;

    // ✅ NOVO: Lock service para prevenir race conditions
    private final br.com.lolmatchmaking.backend.service.lock.DiscordLockService discordLockService;

    // ✅ NOVO: Repository para limpar fila
    private final br.com.lolmatchmaking.backend.domain.repository.QueuePlayerRepository queuePlayerRepository;

    // ✅ NOVO: PlayerStateService para limpar estados
    private final br.com.lolmatchmaking.backend.service.lock.PlayerStateService playerStateService;

    private JDA jda;
    private String discordToken;
    private String discordChannelName; // Mudança: usar nome do canal em vez de ID
    private VoiceChannel monitoredChannel;

    // ❌ REMOVIDO: HashMaps locais - Redis é fonte ÚNICA da verdade
    // NUNCA usar cache local, mesmo como fallback
    // private final Map<String, DiscordUser> usersInChannel = new
    // ConcurrentHashMap<>();
    // private final Map<String, DiscordPlayer> discordQueue = new
    // ConcurrentHashMap<>();
    // private final Map<Long, DiscordMatch> activeMatches = new
    // ConcurrentHashMap<>();

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
            // ✅ PRIORIDADE 1: Tentar buscar do Secret Manager/Variável de Ambiente
            discordToken = getDiscordTokenFromEnvironment();

            // ✅ FALLBACK: Se não encontrar, buscar do banco (para compatibilidade)
            if (discordToken == null || discordToken.trim().isEmpty()) {
                Optional<Setting> tokenSetting = settingRepository.findByKey("discord_token");
                if (tokenSetting.isPresent()) {
                    discordToken = tokenSetting.get().getValue();
                    log.warn(
                            "⚠️ [DiscordService] Token carregado do banco de dados (FALLBACK - configure DISCORD_TOKEN no Secret Manager)");
                } else {
                    log.warn(
                            "⚠️ [DiscordService] Token do Discord não encontrado nem no Secret Manager nem no banco de dados");
                }
            } else {
                log.info("🔑 [DiscordService] Token do Discord carregado do Secret Manager/Variável de Ambiente");
            }

            // Configurações de canal (mantém no banco por enquanto)
            Optional<Setting> channelSetting = settingRepository.findByKey("discord_channel_id");
            if (channelSetting.isPresent()) {
                discordChannelName = channelSetting.get().getValue();
                log.info("🎯 [DiscordService] Nome do canal Discord carregado: {}", discordChannelName);
            }
        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao carregar configurações do Discord", e);
        }
    }

    /**
     * ✅ NOVO: Busca token do Discord do Secret Manager/Variável de Ambiente
     */
    private String getDiscordTokenFromEnvironment() {
        // Tentar buscar da variável de ambiente (Secret Manager do GCP)
        String envToken = System.getenv("DISCORD_TOKEN");
        if (envToken != null && !envToken.trim().isEmpty()) {
            return envToken;
        }

        // Nota: Para usar propriedades Spring, seria melhor injetar
        // @Value("${discord.token:}")
        // mas mantemos compatibilidade com a estrutura atual usando apenas variáveis de
        // ambiente
        return null;
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

    /**
     * ✅ REFATORADO: Busca usuários direto do JDA e cacheia no Redis
     * ✅ NOVO: Com lock distribuído para prevenir race conditions
     * NUNCA usa HashMap local
     */
    private void loadCurrentUsersInChannel() {
        if (monitoredChannel == null)
            return;

        // 🔒 NOVO: ADQUIRIR LOCK ANTES DE ATUALIZAR
        if (!discordLockService.acquireDiscordUpdateLock()) {
            log.debug("⏭️ [DiscordService] Outra instância já está atualizando usuários, pulando");
            return; // Outra instância está processando
        }

        try {
            List<Member> members = monitoredChannel.getMembers();
            List<DiscordUser> users = new ArrayList<>();

            for (Member member : members) {
                if (!member.getUser().isBot()) {
                    DiscordUser user = createDiscordUser(member);
                    users.add(user);
                }
            }

            // ✅ Cachear no Redis IMEDIATAMENTE
            redisDiscordCache.cacheUsers(users);

            log.info("👥 [DiscordService] {} usuários carregados do JDA e cacheados no Redis", users.size());
            notifyUsersUpdate();

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao carregar usuários do canal", e);
        } finally {
            // 🔓 SEMPRE LIBERAR LOCK
            discordLockService.releaseDiscordUpdateLock();
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

    /**
     * ✅ REFATORADO: Event listeners - FONTE PRIMÁRIA de atualizações em tempo real
     * Busca do JDA e atualiza Redis IMEDIATAMENTE, sem passar por HashMap
     */
    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        String leftChannelName = event.getChannelLeft() != null ? event.getChannelLeft().getName() : null;
        String joinedChannelName = event.getChannelJoined() != null ? event.getChannelJoined().getName() : null;

        if (leftChannelName != null && leftChannelName.equals(discordChannelName)) {
            log.info("👋 [DiscordService] Usuário saiu do canal: {}", event.getMember().getEffectiveName());

            // ✅ Recarregar TODOS os usuários do JDA e atualizar Redis
            loadCurrentUsersInChannel();

        } else if (joinedChannelName != null && joinedChannelName.equals(discordChannelName)) {
            log.info("👋 [DiscordService] Usuário entrou no canal: {}", event.getMember().getEffectiveName());

            // ✅ Recarregar TODOS os usuários do JDA e atualizar Redis
            loadCurrentUsersInChannel();
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
     * ✅ REFATORADO: Notifica status do Discord - busca contagem do Redis
     */
    private void notifyDiscordStatus() {
        // ✅ Buscar usuários do Redis para ter contagem correta
        int usersCount = getUsersCount();

        Map<String, Object> status = new HashMap<>();
        status.put("isConnected", isConnected);
        status.put("botUsername", botUsername);
        status.put("channelName", channelName);
        status.put("usersCount", usersCount);

        // Cachear status no Redis
        try {
            redisDiscordCache.cacheStatus(status);
        } catch (Exception e) {
            log.debug("⚠️ [DiscordService] Erro ao cachear status (não crítico): {}", e.getMessage());
        }

        webSocketService.broadcastMessage("discord_status", status);
        log.info("📡 [DiscordService] Status enviado via WebSocket: {} usuários", usersCount);
    }

    /**
     * ✅ REFATORADO: Notifica atualização de usuários - busca do Redis, NUNCA de
     * HashMap
     */
    private void notifyUsersUpdate() {
        // ✅ Buscar SEMPRE do Redis (fonte única da verdade)
        List<DiscordUser> users = getUsersInChannel();

        if (users.isEmpty()) {
            log.warn("⚠️ [DiscordService] Nenhum usuário no Redis para notificar");
            return;
        }

        // Broadcast para TODOS os clientes WebSocket
        webSocketService.broadcastMessage("discord_users", Map.of("users", users));
        log.info("📡 [DiscordService] {} usuários (Redis) enviados via WebSocket", users.size());
    }

    /**
     * ✅ REFATORADO: Atualiza usuários do Discord (fallback de segurança)
     * Busca do JDA e compara com Redis para detectar mudanças
     */
    private void updateDiscordUsers() {
        if (monitoredChannel == null) {
            log.debug("🔍 [DiscordService] Nenhum canal monitorado para atualizar usuários");
            return;
        }

        try {
            // ✅ 1. Buscar usuários atuais do JDA (fonte da verdade)
            List<Member> members = monitoredChannel.getMembers();
            List<DiscordUser> currentUsers = new ArrayList<>();

            for (Member member : members) {
                if (!member.getUser().isBot()) {
                    currentUsers.add(createDiscordUser(member));
                }
            }

            // ✅ 2. Buscar usuários do Redis para comparação
            List<DiscordUser> cachedUsers = getUsersInChannel();

            // ✅ 3. Verificar se houve mudanças
            boolean hasChanges = currentUsers.size() != cachedUsers.size();

            if (!hasChanges) {
                // Verificar mudanças individuais (IDs)
                Set<String> currentIds = currentUsers.stream()
                        .map(DiscordUser::getId)
                        .collect(java.util.stream.Collectors.toSet());
                Set<String> cachedIds = cachedUsers.stream()
                        .map(DiscordUser::getId)
                        .collect(java.util.stream.Collectors.toSet());
                hasChanges = !currentIds.equals(cachedIds);
            }

            if (hasChanges) {
                // ✅ 4. Cachear nova lista no Redis
                redisDiscordCache.cacheUsers(currentUsers);

                // ✅ 5. Notificar via WebSocket
                notifyUsersUpdate();

                log.info("🔄 [DiscordService] Usuários atualizados via fallback: {} usuários (Redis)",
                        currentUsers.size());
            } else {
                log.debug("✅ [DiscordService] Nenhuma mudança detectada no fallback");
            }

        } catch (Exception e) {
            log.warn("⚠️ [DiscordService] Erro ao atualizar usuários do Discord: {}", e.getMessage());
        }
    }

    /**
     * ✅ REFATORADO: Envia status do Discord - busca contagem do Redis
     */
    private void sendDiscordStatus() {
        // ✅ Buscar usuários do Redis para ter contagem correta
        int usersCount = getUsersCount();

        Map<String, Object> status = new HashMap<>();
        status.put("botUsername", botUsername);
        status.put("isConnected", isConnected);
        status.put("channelName", channelName);
        status.put("usersCount", usersCount);

        // Cachear status no Redis
        try {
            redisDiscordCache.cacheStatus(status);
        } catch (Exception e) {
            log.debug("⚠️ [DiscordService] Erro ao cachear status (não crítico): {}", e.getMessage());
        }

        webSocketService.broadcastMessage("discord_status", status);
        log.debug("📡 [DiscordService] Status periódico enviado via WebSocket: {} usuários", usersCount);
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
     * ✅ REFATORADO 100% REDIS: Retorna usuários APENAS do Redis
     * Se Redis vazio, busca do JDA e cacheia
     * NUNCA usa HashMap local
     */
    public List<DiscordUser> getUsersInChannel() {
        try {
            // ✅ 1. Tentar buscar do Redis
            List<Object> cachedUsers = redisDiscordCache.getCachedUsers();
            if (cachedUsers != null && !cachedUsers.isEmpty()) {
                log.debug("⚡ [DiscordService] {} usuários retornados do Redis", cachedUsers.size());

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

            // ✅ 2. Redis vazio → Buscar do JDA (fonte da verdade)
            log.debug("⚠️ [DiscordService] Redis vazio, buscando direto do JDA");

            if (monitoredChannel != null) {
                List<Member> members = monitoredChannel.getMembers();
                List<DiscordUser> users = new ArrayList<>();

                for (Member member : members) {
                    if (!member.getUser().isBot()) {
                        users.add(createDiscordUser(member));
                    }
                }

                // ✅ 3. Cachear no Redis para próximas requisições
                if (!users.isEmpty()) {
                    redisDiscordCache.cacheUsers(users);
                    log.info("✅ [DiscordService] {} usuários buscados do JDA e cacheados no Redis", users.size());
                }

                return users;
            }

            // ✅ 4. Se tudo falhar, retornar vazio (NUNCA usar HashMap!)
            log.error("❌ [DiscordService] Canal não disponível e Redis vazio");
            return Collections.emptyList();

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao buscar usuários: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * ✅ REFATORADO: Retorna contagem de usuários do Redis
     */
    public int getUsersCount() {
        return getUsersInChannel().size();
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

        String userId = event.getUser().getId();

        // 🔒 NOVO: ADQUIRIR LOCK DE VINCULAÇÃO
        if (!discordLockService.acquireUserLinkLock(userId)) {
            event.reply("⏳ Você já está processando uma vinculação! Aguarde alguns segundos.")
                    .setEphemeral(true).queue();
            log.warn("⚠️ [DiscordService] Tentativa de vinculação duplicada: {}", event.getUser().getAsTag());
            return;
        }

        try {
            // ✅ NOVO: Adicionar # automaticamente se não tiver
            if (!tagLine.startsWith("#")) {
                tagLine = "#" + tagLine;
            }

            // ✅ NOVO: Salvar vinculação no MySQL
            String discordUsername = event.getUser().getName();

            // Salvar no banco de dados
            discordLoLLinkService.createOrUpdateLink(userId, discordUsername, gameName, tagLine, "BR1");

            // ✅ REFATORADO: Recarregar TODOS os usuários do JDA após vinculação
            // Isso garante que a vinculação apareça para todos imediatamente
            log.info("🔗 [DiscordService] Vinculação salva no MySQL: {} -> {}", discordUsername, gameName + tagLine);

            // Recarregar usuários do JDA e cachear no Redis
            loadCurrentUsersInChannel();

            log.info("✅ [DiscordService] Usuários recarregados e cacheados no Redis com nova vinculação");

            event.reply("✅ Vinculação criada com sucesso!\n" +
                    "**LoL:** " + gameName + tagLine + "\n" +
                    "**Discord:** " + event.getUser().getAsTag()).queue();

            log.info("🔗 [DiscordService] Vinculação criada: {} -> {}",
                    event.getUser().getAsTag(), gameName + tagLine);

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao vincular conta", e);
            event.reply("❌ Erro ao vincular conta").setEphemeral(true).queue();
        } finally {
            // 🔓 SEMPRE LIBERAR LOCK
            discordLockService.releaseUserLinkLock(userId);
        }
    }

    private void handleDesvincularCommand(SlashCommandInteractionEvent event) {
        String userId = event.getUser().getId();

        // 🔒 NOVO: ADQUIRIR LOCK DE DESVINCULAÇÃO
        if (!discordLockService.acquireUserLinkLock(userId)) {
            event.reply("⏳ Você já está processando uma operação! Aguarde alguns segundos.")
                    .setEphemeral(true).queue();
            log.warn("⚠️ [DiscordService] Tentativa de desvinculação duplicada: {}", event.getUser().getAsTag());
            return;
        }

        try {
            // ✅ NOVO: Remover vinculação do MySQL e da memória
            // Buscar vinculação existente para mostrar o que foi removido
            String previousLinked = "nenhum";
            var existingLink = discordLoLLinkService.findByDiscordId(userId);
            if (existingLink.isPresent()) {
                previousLinked = existingLink.get().getFullGameName();
            }

            // Remover do banco de dados
            boolean removed = discordLoLLinkService.removeLink(userId);

            if (removed) {
                log.info("🔓 [DiscordService] Vinculação removida do MySQL: {} (era: {})",
                        event.getUser().getAsTag(), previousLinked);

                // ✅ REFATORADO: Recarregar usuários do JDA e cachear no Redis
                loadCurrentUsersInChannel();

                log.info("✅ [DiscordService] Usuários recarregados e cacheados no Redis sem a vinculação");

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
        } finally {
            // 🔓 SEMPRE LIBERAR LOCK
            discordLockService.releaseUserLinkLock(userId);
        }
    }

    /**
     * ✅ REFATORADO: Busca fila do MySQL (não usa discordQueue HashMap)
     */
    private void handleQueueCommand(SlashCommandInteractionEvent event) {
        try {
            // ❌ REMOVIDO: discordQueue (Discord não gerencia fila, MySQL sim)
            // O sistema usa queue_players no MySQL como fonte da verdade

            event.reply("ℹ️ Use o aplicativo para ver a fila em tempo real.\n" +
                    "A fila é gerenciada via MySQL e exibida no app.").setEphemeral(true).queue();

            log.info("📊 [DiscordService] Comando /queue executado (redirecionado para app)");

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao processar comando /queue", e);
            event.reply("❌ Erro ao processar comando").setEphemeral(true).queue();
        }
    }

    /**
     * ✅ NOVO: Limpa TODA a fila do MySQL + Estados do Redis
     * Comando de emergência para resolver bugs
     */
    private void handleClearQueueCommand(SlashCommandInteractionEvent event) {
        try {
            log.warn("🧹 [DiscordService] COMANDO /clear_queue EXECUTADO por {}",
                    event.getUser().getName());

            // ✅ CRÍTICO: Buscar TODOS os jogadores na fila ANTES de deletar
            List<br.com.lolmatchmaking.backend.domain.entity.QueuePlayer> allPlayers = queuePlayerRepository.findAll();

            if (allPlayers.isEmpty()) {
                event.reply("✅ A fila já está vazia!").setEphemeral(true).queue();
                log.info("✅ [DiscordService] Fila já estava vazia");
                return;
            }

            log.warn("🧹 [clear_queue] Limpando {} jogadores da fila:", allPlayers.size());
            for (br.com.lolmatchmaking.backend.domain.entity.QueuePlayer player : allPlayers) {
                log.warn("  🗑️ {}", player.getSummonerName());
            }

            // ✅ PASSO 1: Limpar estados do Redis (PlayerState) para TODOS
            for (br.com.lolmatchmaking.backend.domain.entity.QueuePlayer player : allPlayers) {
                try {
                    playerStateService.forceSetPlayerState(
                            player.getSummonerName(),
                            br.com.lolmatchmaking.backend.service.lock.PlayerState.AVAILABLE);
                    log.info("  ✅ Estado resetado: {}", player.getSummonerName());
                } catch (Exception e) {
                    log.warn("  ⚠️ Erro ao resetar estado de {}: {}",
                            player.getSummonerName(), e.getMessage());
                }
            }

            // ✅ PASSO 2: Deletar TODOS os jogadores do MySQL
            queuePlayerRepository.deleteAll();
            queuePlayerRepository.flush();

            log.warn("✅ [clear_queue] {} jogadores removidos do MySQL", allPlayers.size());
            log.warn("✅ [clear_queue] Estados Redis resetados para AVAILABLE");

            event.reply("✅ **Fila Limpa com Sucesso!**\n\n" +
                    "🗑️ **" + allPlayers.size() + " jogadores** removidos do MySQL\n" +
                    "🔄 Estados Redis resetados para **AVAILABLE**\n\n" +
                    "Todos podem entrar na fila novamente!").setEphemeral(true).queue();

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao processar comando /clear_queue", e);
            event.reply("❌ Erro ao limpar fila: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    /**
     * ✅ REFATORADO: Busca usuários do Redis/JDA, NUNCA de HashMap
     */
    private void handleLobbyCommand(SlashCommandInteractionEvent event) {
        try {
            // ✅ Buscar usuários do Redis (que busca do JDA se necessário)
            List<DiscordUser> users = getUsersInChannel();
            int usersInLobby = users.size();

            StringBuilder lobbyList = new StringBuilder();
            if (usersInLobby == 0) {
                lobbyList.append("Nenhum usuário no lobby");
            } else {
                users.forEach(user -> {
                    String nickname = user.getLinkedNickname() != null
                            ? user.getLinkedNickname().getGameName() + "#" + user.getLinkedNickname().getTagLine()
                            : user.getDisplayName();
                    lobbyList.append("• ").append(nickname).append("\n");
                });
            }

            event.reply("🎮 **Lobby Discord**\n\n" +
                    "👥 **Usuários no Canal:** " + usersInLobby + "\n\n" +
                    "**Lista:**\n" + lobbyList.toString()).queue();

            log.info("👥 [DiscordService] Lobby consultado: {} usuários (Redis)", usersInLobby);

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

            // ✅ 7. Armazenar no Redis (fonte ÚNICA)
            redisDiscordMatch.registerMatch(
                    matchId,
                    category.getId(),
                    blueChannel.getId(),
                    redChannel.getId(),
                    blueTeamDiscordIds,
                    redTeamDiscordIds);

            log.info("✅ [createMatchChannels] Match {} criado e salvo no Redis! Blue: {}, Red: {}",
                    matchId, blueTeamDiscordIds.size(), redTeamDiscordIds.size());

            // ✅ 8. Agendar timeout de limpeza (3 horas) - verificando no Redis
            scheduler.schedule(() -> {
                if (redisDiscordMatch.matchExists(matchId)) {
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
    /**
     * ✅ REFATORADO: Busca match do Redis
     */
    public void movePlayersToTeamChannels(Long matchId) {
        // ✅ Verificar se match existe no Redis
        if (!redisDiscordMatch.matchExists(matchId)) {
            log.warn("⚠️ [movePlayersToTeamChannels] Match {} não encontrado no Redis", matchId);
            return;
        }

        // ✅ Buscar dados do Redis
        Map<String, String> channels = redisDiscordMatch.getMatchChannels(matchId);
        Set<String> blueTeamDiscordIds = redisDiscordMatch.getTeamPlayers(matchId, "blue");
        Set<String> redTeamDiscordIds = redisDiscordMatch.getTeamPlayers(matchId, "red");

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

            // ✅ Buscar canais do Redis
            String blueChannelId = channels.get("blue");
            String redChannelId = channels.get("red");

            if (blueChannelId == null || redChannelId == null) {
                log.error("❌ [movePlayersToTeamChannels] IDs de canais não encontrados no Redis");
                return;
            }

            VoiceChannel blueChannel = guild.getVoiceChannelById(blueChannelId);
            VoiceChannel redChannel = guild.getVoiceChannelById(redChannelId);

            if (blueChannel == null || redChannel == null) {
                log.error("❌ [movePlayersToTeamChannels] Canais não encontrados no Discord");
                return;
            }

            log.info("🔄 [movePlayersToTeamChannels] Movendo jogadores para match {}", matchId);

            int movedBlue = 0;
            int movedRed = 0;

            // Mover Blue Team
            for (String discordId : blueTeamDiscordIds) {
                Member member = guild.getMemberById(discordId);
                if (member != null && member.getVoiceState() != null && member.getVoiceState().getChannel() != null) {
                    VoiceChannel currentChannel = (VoiceChannel) member.getVoiceState().getChannel();

                    // ✅ Salvar canal original no Redis
                    redisDiscordMatch.storeOriginalChannel(matchId, discordId, currentChannel.getId());

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
            for (String discordId : redTeamDiscordIds) {
                Member member = guild.getMemberById(discordId);
                if (member != null && member.getVoiceState() != null && member.getVoiceState().getChannel() != null) {
                    VoiceChannel currentChannel = (VoiceChannel) member.getVoiceState().getChannel();

                    // ✅ Salvar canal original no Redis
                    redisDiscordMatch.storeOriginalChannel(matchId, discordId, currentChannel.getId());

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
     * ✅ REFATORADO: Deleta canais de uma partida e move jogadores de volta
     * Busca dados do Redis, NUNCA de activeMatches HashMap
     * 
     * @param matchId         ID da partida
     * @param movePlayersBack Se true, move jogadores de volta ao canal original
     */
    public void deleteMatchChannels(Long matchId, boolean movePlayersBack) {
        // ✅ Verificar se match existe no Redis
        if (!redisDiscordMatch.matchExists(matchId)) {
            log.warn("⚠️ [deleteMatchChannels] Match {} não encontrado no Redis", matchId);
            return;
        }

        // ✅ Buscar dados do Redis
        Map<String, String> channels = redisDiscordMatch.getMatchChannels(matchId);
        Map<String, String> originalChannels = redisDiscordMatch.getOriginalChannels(matchId);

        if (!isConnected || jda == null) {
            log.warn("⚠️ [deleteMatchChannels] Discord não conectado");
            redisDiscordMatch.removeMatch(matchId);
            return;
        }

        try {
            Guild guild = jda.getGuilds().stream().findFirst().orElse(null);
            if (guild == null) {
                log.error("❌ [deleteMatchChannels] Guild não encontrada");
                redisDiscordMatch.removeMatch(matchId);
                return;
            }

            log.info("🧹 [deleteMatchChannels] Limpando match {}, movePlayersBack={}", matchId, movePlayersBack);

            // ✅ Buscar IDs dos canais do Redis
            String blueChannelId = channels.get("blue");
            String redChannelId = channels.get("red");
            String categoryId = channels.get("category");

            // 1. Mover TODOS os usuários de volta (jogadores + espectadores)
            if (movePlayersBack) {
                VoiceChannel mainChannel = guild.getVoiceChannels().stream()
                        .filter(ch -> ch.getName().equals(discordChannelName))
                        .findFirst()
                        .orElse(null);

                if (mainChannel == null) {
                    log.warn("⚠️ [deleteMatchChannels] Canal principal '{}' não encontrado", discordChannelName);
                }

                // ✅ Pegar TODOS os membros nos canais Blue e Red
                VoiceChannel blueChannel = blueChannelId != null ? guild.getVoiceChannelById(blueChannelId) : null;
                VoiceChannel redChannel = redChannelId != null ? guild.getVoiceChannelById(redChannelId) : null;

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

                        // ✅ Tentar mover para canal original do Redis
                        String originalChannelId = originalChannels.get(member.getId());
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
            VoiceChannel blueChannel = blueChannelId != null ? guild.getVoiceChannelById(blueChannelId) : null;
            if (blueChannel != null) {
                blueChannel.delete().queue(
                        success -> log.info("✅ [deleteMatchChannels] Canal Blue deletado"),
                        error -> log.error("❌ [deleteMatchChannels] Erro ao deletar Blue: {}", error.getMessage()));
            }

            VoiceChannel redChannel = redChannelId != null ? guild.getVoiceChannelById(redChannelId) : null;
            if (redChannel != null) {
                redChannel.delete().queue(
                        success -> log.info("✅ [deleteMatchChannels] Canal Red deletado"),
                        error -> log.error("❌ [deleteMatchChannels] Erro ao deletar Red: {}", error.getMessage()));
            }

            // 3. Deletar categoria
            Category category = categoryId != null ? guild.getCategoryById(categoryId) : null;
            if (category != null) {
                // Aguardar um pouco para os canais serem deletados primeiro
                scheduler.schedule(() -> {
                    category.delete().queue(
                            success -> log.info("✅ [deleteMatchChannels] Categoria deletada"),
                            error -> log.error("❌ [deleteMatchChannels] Erro ao deletar categoria: {}",
                                    error.getMessage()));
                }, 3, TimeUnit.SECONDS);
            }

            // ✅ 4. Remover do Redis
            redisDiscordMatch.removeMatch(matchId);

            log.info("✅ [deleteMatchChannels] Match {} limpo do Redis e Discord", matchId);

        } catch (Exception e) {
            log.error("❌ [deleteMatchChannels] Erro ao limpar match {}", matchId, e);
            redisDiscordMatch.removeMatch(matchId);
        }
    }

    /**
     * ✅ REFATORADO: Move apenas espectadores de volta ao lobby - busca do Redis
     * 
     * @param matchId ID da partida
     */
    public void moveSpectatorsBackToLobby(Long matchId) {
        // ✅ Verificar se match existe no Redis
        if (!redisDiscordMatch.matchExists(matchId)) {
            log.warn("⚠️ [moveSpectatorsBackToLobby] Match {} não encontrado no Redis", matchId);
            return;
        }

        // ✅ Buscar dados do Redis
        Map<String, String> channels = redisDiscordMatch.getMatchChannels(matchId);
        Set<String> blueTeamDiscordIds = redisDiscordMatch.getTeamPlayers(matchId, "blue");
        Set<String> redTeamDiscordIds = redisDiscordMatch.getTeamPlayers(matchId, "red");

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

            // ✅ Buscar canais do Redis
            String blueChannelId = channels.get("blue");
            String redChannelId = channels.get("red");

            VoiceChannel blueChannel = blueChannelId != null ? guild.getVoiceChannelById(blueChannelId) : null;
            VoiceChannel redChannel = redChannelId != null ? guild.getVoiceChannelById(redChannelId) : null;
            VoiceChannel mainChannel = guild.getVoiceChannels().stream()
                    .filter(ch -> ch.getName().equals(discordChannelName))
                    .findFirst()
                    .orElse(null);

            if (mainChannel == null) {
                log.warn("⚠️ [moveSpectatorsBackToLobby] Canal principal '{}' não encontrado", discordChannelName);
                return;
            }

            log.info("👥 [moveSpectatorsBackToLobby] Movendo espectadores do match {} de volta ao lobby", matchId);

            // ✅ Combinar IDs dos jogadores reais do Redis
            List<String> realPlayerIds = new ArrayList<>();
            realPlayerIds.addAll(blueTeamDiscordIds);
            realPlayerIds.addAll(redTeamDiscordIds);

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
     * ✅ REFATORADO: Lista espectadores de uma partida - busca do Redis
     * 
     * @param matchId ID da partida
     * @return Lista de espectadores
     */
    public List<SpectatorDTO> getMatchSpectators(Long matchId) {
        // ✅ Verificar se match existe no Redis
        if (!redisDiscordMatch.matchExists(matchId)) {
            log.warn("⚠️ [getMatchSpectators] Match {} não encontrado no Redis", matchId);
            return Collections.emptyList();
        }

        // ✅ Buscar dados do Redis
        Map<String, String> channels = redisDiscordMatch.getMatchChannels(matchId);
        Set<String> blueTeamDiscordIds = redisDiscordMatch.getTeamPlayers(matchId, "blue");
        Set<String> redTeamDiscordIds = redisDiscordMatch.getTeamPlayers(matchId, "red");

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

            // ✅ Buscar canais do Redis
            String blueChannelId = channels.get("blue");
            String redChannelId = channels.get("red");

            VoiceChannel blueChannel = blueChannelId != null ? guild.getVoiceChannelById(blueChannelId) : null;
            VoiceChannel redChannel = redChannelId != null ? guild.getVoiceChannelById(redChannelId) : null;

            List<SpectatorDTO> spectators = new ArrayList<>();

            // ✅ IDs dos jogadores reais da partida do Redis (não são espectadores)
            List<String> realPlayerIds = new ArrayList<>();
            realPlayerIds.addAll(blueTeamDiscordIds);
            realPlayerIds.addAll(redTeamDiscordIds);

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
     * ✅ REFATORADO: Muta um espectador + Atualiza Redis + Broadcast em tempo real
     * 
     * @param matchId   ID da partida
     * @param discordId Discord ID do espectador
     * @return true se mutou com sucesso
     */
    public boolean muteSpectator(Long matchId, String discordId) {
        // ✅ Verificar se match existe no Redis
        if (!redisDiscordMatch.matchExists(matchId)) {
            log.warn("⚠️ [muteSpectator] Match {} não encontrado no Redis", matchId);
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

            // ✅ SERVER MUTE no Discord
            member.mute(true).queue(
                    success -> {
                        log.info("✅ [muteSpectator] 🔇 Espectador {} mutado (SERVER MUTE)", member.getEffectiveName());

                        // ✅ CRÍTICO: Atualizar Redis
                        redisSpectator.markAsMuted(matchId, discordId);

                        // ✅ CRÍTICO: Broadcast para TODOS os jogadores da partida
                        broadcastSpectatorUpdate(matchId);
                    },
                    error -> log.error("❌ [muteSpectator] Erro ao mutar {}: {}", member.getEffectiveName(),
                            error.getMessage()));

            return true;

        } catch (Exception e) {
            log.error("❌ [muteSpectator] Erro ao mutar espectador", e);
            return false;
        }
    }

    /**
     * ✅ REFATORADO: Desmuta um espectador + Atualiza Redis + Broadcast em tempo
     * real
     * 
     * @param matchId   ID da partida
     * @param discordId Discord ID do espectador
     * @return true se desmutou com sucesso
     */
    public boolean unmuteSpectator(Long matchId, String discordId) {
        // ✅ Verificar se match existe no Redis
        if (!redisDiscordMatch.matchExists(matchId)) {
            log.warn("⚠️ [unmuteSpectator] Match {} não encontrado no Redis", matchId);
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

            // Remove SERVER MUTE no Discord
            member.mute(false).queue(
                    success -> {
                        log.info("✅ [unmuteSpectator] 🔊 Espectador {} desmutado", member.getEffectiveName());

                        // ✅ CRÍTICO: Atualizar Redis
                        redisSpectator.markAsUnmuted(matchId, discordId);

                        // ✅ CRÍTICO: Broadcast para TODOS os jogadores da partida
                        broadcastSpectatorUpdate(matchId);
                    },
                    error -> log.error("❌ [unmuteSpectator] Erro ao desmutar {}: {}", member.getEffectiveName(),
                            error.getMessage()));

            return true;

        } catch (Exception e) {
            log.error("❌ [unmuteSpectator] Erro", e);
            return false;
        }
    }

    /**
     * ✅ REFATORADO: Verifica se um espectador está mutado - verifica no Redis
     * 
     * @param matchId   ID da partida
     * @param discordId Discord ID do espectador
     * @return true se está mutado, false caso contrário
     */
    public boolean isSpectatorMuted(Long matchId, String discordId) {
        // ✅ Verificar se match existe no Redis
        if (!redisDiscordMatch.matchExists(matchId)) {
            log.warn("⚠️ [isSpectatorMuted] Match {} não encontrado no Redis", matchId);
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

    /**
     * ✅ NOVO: Broadcast de atualização de espectadores em tempo real
     * Envia para TODOS os jogadores da partida quando espectador é mutado/desmutado
     */
    private void broadcastSpectatorUpdate(Long matchId) {
        try {
            // ✅ Buscar espectadores atualizados (com estados de mute do Redis)
            List<SpectatorDTO> spectators = getMatchSpectators(matchId);

            // ✅ Buscar jogadores da partida do Redis
            Set<String> blueTeam = redisDiscordMatch.getTeamPlayers(matchId, "blue");
            Set<String> redTeam = redisDiscordMatch.getTeamPlayers(matchId, "red");

            // Combinar jogadores
            List<String> allPlayerIds = new ArrayList<>();
            allPlayerIds.addAll(blueTeam);
            allPlayerIds.addAll(redTeam);

            // Converter Discord IDs para Summoner Names (buscar vinculações)
            List<String> summonerNames = new ArrayList<>();
            for (String discordId : allPlayerIds) {
                // Buscar vinculação Discord → LoL
                Optional<br.com.lolmatchmaking.backend.entity.DiscordLoLLink> link = discordLoLLinkService
                        .findByDiscordId(discordId);

                if (link.isPresent()) {
                    summonerNames.add(link.get().getSummonerName());
                }
            }

            // ✅ Broadcast para os jogadores
            if (!summonerNames.isEmpty()) {
                Map<String, Object> data = new HashMap<>();
                data.put("matchId", matchId);
                data.put("spectators", spectators);
                data.put("count", spectators.size());

                // ✅ CORREÇÃO: Enviar apenas para jogadores da partida
                webSocketService.sendToPlayers("spectators_update", data, summonerNames);
                log.info("📡 [DiscordService] Broadcast de espectadores enviado para {} jogadores (match {})",
                        summonerNames.size(), matchId);
            }

        } catch (Exception e) {
            log.error("❌ [DiscordService] Erro ao fazer broadcast de espectadores", e);
        }
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
