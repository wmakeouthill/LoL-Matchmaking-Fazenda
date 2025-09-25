package br.com.lolmatchmaking.backend.service;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class DiscordService extends ListenerAdapter {

    @Value("${app.discord.token:}")
    private String botToken;

    private JDA jda;
    private boolean isConnected = false;

    @PostConstruct
    public void initialize() {
        if (botToken == null || botToken.trim().isEmpty()) {
            log.warn("Discord bot token não configurado. Serviço Discord não será iniciado.");
            return;
        }

        log.info("🤖 Inicializando Discord Service...");

        // Conectar ao Discord de forma assíncrona para não bloquear a inicialização do Spring
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1000); // Pequena pausa para garantir que o Spring está estável
                connectToDiscord();
                log.info("✅ Discord Service inicializado com sucesso");
            } catch (Exception e) {
                log.error("❌ Erro ao conectar ao Discord", e);
            }
        });

        log.info("✅ Discord Service configurado - conectando em background");
    }

    @PreDestroy
    public void shutdown() {
        if (jda != null) {
            jda.shutdown();
            log.info("Discord bot desconectado");
        }
    }

    private void connectToDiscord() {
        try {
            jda = JDABuilder.createDefault(botToken)
                    .enableIntents(
                            GatewayIntent.GUILD_MEMBERS,
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(this)
                    .build();

            // REMOVER o awaitReady() completamente - deixar o Discord conectar naturalmente
            // jda.awaitReady(); // REMOVIDO - isso estava bloqueando mesmo em background

            // Definir como conectado imediatamente e verificar status depois
            isConnected = true;
            log.info("Discord bot iniciado - conectando em background...");

            // Verificar conexão de forma assíncrona após alguns segundos
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(5000); // Aguarda 5s para o Discord conectar
                    if (jda != null && jda.getStatus().isInit()) {
                        log.info("✅ Discord bot conectado com sucesso! Status: {}", jda.getStatus());
                        if (!jda.getGuilds().isEmpty()) {
                            log.info("🎮 Discord conectado a {} servidor(es)", jda.getGuilds().size());
                        }
                    }
                } catch (Exception e) {
                    log.warn("⚠️ Erro ao verificar status do Discord: {}", e.getMessage());
                }
            });

        } catch (Exception e) {
            log.error("Erro ao conectar com Discord", e);
            isConnected = false;
        }
    }

    public boolean isConnected() {
        return isConnected && jda != null;
    }

    public CompletableFuture<Boolean> sendMessage(String channelId, String message) {
        if (!isConnected()) {
            log.warn("Tentativa de enviar mensagem mas Discord não está conectado");
            return CompletableFuture.completedFuture(false);
        }

        try {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                log.warn("Canal Discord não encontrado: {}", channelId);
                return CompletableFuture.completedFuture(false);
            }

            return channel.sendMessage(message)
                    .submit()
                    .thenApply(msg -> {
                        log.debug("Mensagem enviada para Discord: {}", channelId);
                        return true;
                    })
                    .exceptionally(throwable -> {
                        log.error("Erro ao enviar mensagem para Discord", throwable);
                        return false;
                    });

        } catch (Exception e) {
            log.error("Erro ao enviar mensagem para Discord", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    public CompletableFuture<Boolean> sendPrivateMessage(String userId, String message) {
        if (!isConnected()) {
            return CompletableFuture.completedFuture(false);
        }

        try {
            User user = jda.getUserById(userId);
            if (user == null) {
                log.warn("Usuário Discord não encontrado: {}", userId);
                return CompletableFuture.completedFuture(false);
            }

            return user.openPrivateChannel()
                    .flatMap(channel -> channel.sendMessage(message))
                    .submit()
                    .thenApply(msg -> {
                        log.debug("Mensagem privada enviada para Discord: {}", userId);
                        return true;
                    })
                    .exceptionally(throwable -> {
                        log.error("Erro ao enviar mensagem privada para Discord", throwable);
                        return false;
                    });

        } catch (Exception e) {
            log.error("Erro ao enviar mensagem privada para Discord", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    public Optional<String> getUserDisplayName(String guildId, String userId) {
        if (!isConnected()) {
            return Optional.empty();
        }

        try {
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                return Optional.empty();
            }

            Member member = guild.getMemberById(userId);
            if (member == null) {
                return Optional.empty();
            }

            return Optional.of(member.getEffectiveName());

        } catch (Exception e) {
            log.debug("Erro ao buscar nome do usuário Discord: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> getUserAvatarUrl(String userId) {
        if (!isConnected()) {
            return Optional.empty();
        }

        try {
            User user = jda.getUserById(userId);
            if (user == null) {
                return Optional.empty();
            }

            return Optional.ofNullable(user.getEffectiveAvatarUrl());

        } catch (Exception e) {
            log.debug("Erro ao buscar avatar do usuário Discord: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public CompletableFuture<Boolean> notifyMatchFound(String channelId, String matchId, String[] team1,
            String[] team2) {
        if (!isConnected()) {
            return CompletableFuture.completedFuture(false);
        }

        StringBuilder message = new StringBuilder();
        message.append("🎮 **PARTIDA ENCONTRADA!** 🎮\n\n");
        message.append("**Match ID:** `").append(matchId).append("`\n\n");
        message.append("**Team 1:**\n");
        for (String player : team1) {
            message.append("• ").append(player).append("\n");
        }
        message.append("\n**Team 2:**\n");
        for (String player : team2) {
            message.append("• ").append(player).append("\n");
        }
        message.append("\n🔥 **Boa sorte na Summoner's Rift!** 🔥");

        return sendMessage(channelId, message.toString());
    }

    public CompletableFuture<Boolean> notifyDraftStarted(String channelId, String matchId, String draftUrl) {
        if (!isConnected()) {
            return CompletableFuture.completedFuture(false);
        }

        String message = String.format(
                "🏆 **DRAFT INICIADO!** 🏆%n%n" +
                        "**Match ID:** `%s`%n" +
                        "**Draft URL:** %s%n%n" +
                        "⏰ **Entrem no draft agora!** ⏰",
                matchId, draftUrl);

        return sendMessage(channelId, message);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }

        String content = event.getMessage().getContentRaw();
        String channelId = event.getChannel().getId();
        String userId = event.getAuthor().getId();

        log.debug("Mensagem recebida do Discord - Canal: {}, Usuário: {}, Conteúdo: {}",
                channelId, userId, content);

        // Aqui você pode adicionar lógica para comandos do bot
        if (content.startsWith("!")) {
            handleBotCommand(event, content);
        }
    }

    private void handleBotCommand(MessageReceivedEvent event, String command) {
        // Implementar comandos do bot aqui
        String cmd = command.toLowerCase();

        switch (cmd) {
            case "!queue" -> event.getChannel().sendMessage("Verificando fila de matchmaking...").queue();
            case "!status" -> event.getChannel().sendMessage("Bot online! ✅").queue();
            case "!help" -> {
                String helpMessage = """
                        🤖 **Comandos disponíveis:**

                        `!queue` - Verificar fila de matchmaking
                        `!status` - Status do bot
                        `!help` - Esta mensagem
                        """;
                event.getChannel().sendMessage(helpMessage).queue();
            }
            default -> {
                // Comando não reconhecido
            }
        }
    }
}
