package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.DiscordLolLink;
import br.com.lolmatchmaking.backend.domain.repository.DiscordLolLinkRepository;
import br.com.lolmatchmaking.backend.domain.entity.DiscordConfig;
import br.com.lolmatchmaking.backend.domain.repository.DiscordConfigRepository;
import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordAdvancedService {

    // Token será obtido do DiscordService principal
    private String discordToken;

    private final DiscordLolLinkRepository discordLolLinkRepository;
    private final DiscordConfigRepository discordConfigRepository;
    private final DiscordService discordService;
    private final PlayerRepository playerRepository;
    // ✅ MIGRADO: QueueManagementService (Redis-only) ao invés de MatchmakingService
    private final QueueManagementService queueManagementService;

    private boolean isConnected = false;

    // Cache de canais de voz ativos
    private final Map<String, String> activeVoiceChannels = new ConcurrentHashMap<>();
    private final Map<String, Instant> channelLastActivity = new ConcurrentHashMap<>();

    // ✅ TTL de 2 horas para canais Discord (consistente com
    // RedisDiscordMatchService)
    private static final long CHANNEL_TTL_HOURS = 2;

    @PostConstruct
    public void initialize() {
        // Obter token do DiscordService principal
        try {
            discordToken = discordService.getDiscordToken();
            if (discordToken == null || discordToken.isEmpty()) {
                log.warn("⚠️ Token do Discord não configurado no DiscordService");
                return;
            }
        } catch (Exception e) {
            log.warn("⚠️ Erro ao obter token do Discord: {}", e.getMessage());
            return;
        }

        try {
            log.info("🤖 Inicializando Discord Service...");

            // Simular inicialização do Discord Bot
            isConnected = true;

            log.info("✅ Discord Service inicializado com sucesso");
        } catch (Exception e) {
            log.error("❌ Erro ao inicializar Discord Service", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        isConnected = false;
        log.info("🔌 Discord Service desconectado");
    }

    /**
     * Obtém o ID do canal da fila a partir da configuração no banco
     */
    private String getQueueChannelId() {
        return discordConfigRepository.findByIsActiveTrue()
                .map(DiscordConfig::getQueueChannelId)
                .orElse(null);
    }

    /**
     * Obtém o ID do canal de partidas a partir da configuração no banco
     */
    private String getMatchesChannelId() {
        return discordConfigRepository.findByIsActiveTrue()
                .map(DiscordConfig::getMatchesChannelId)
                .orElse(null);
    }

    /**
     * Processa comando de vincular conta
     */
    public String handleVincularCommand(String discordId, String summonerName, String region) {
        try {
            // Verificar se já existe vinculação
            Optional<DiscordLolLink> existingLink = discordLolLinkRepository.findByDiscordId(discordId);
            if (existingLink.isPresent()) {
                return "❌ Você já possui uma conta vinculada: " + existingLink.get().getSummonerName();
            }

            // Buscar jogador
            Optional<Player> player = playerRepository.findBySummonerName(summonerName);
            if (player.isEmpty()) {
                return "❌ Jogador não encontrado: " + summonerName;
            }

            // Criar vinculação
            DiscordLolLink link = new DiscordLolLink();
            link.setDiscordId(discordId);
            link.setSummonerName(summonerName);
            link.setRegion(region);
            link.setLinkedAt(Instant.now());
            link.setActive(true);

            discordLolLinkRepository.save(link);

            return "✅ Conta vinculada com sucesso!\n" +
                    "**Invocador:** " + summonerName + "\n" +
                    "**Região:** " + region;

        } catch (Exception e) {
            log.error("❌ Erro ao vincular conta", e);
            return "❌ Erro ao vincular conta";
        }
    }

    /**
     * Processa comando de desvincular conta
     */
    public String handleDesvincularCommand(String discordId) {
        try {
            Optional<DiscordLolLink> link = discordLolLinkRepository.findByDiscordId(discordId);
            if (link.isEmpty()) {
                return "❌ Você não possui uma conta vinculada";
            }

            discordLolLinkRepository.delete(link.get());
            return "✅ Conta desvinculada com sucesso";

        } catch (Exception e) {
            log.error("❌ Erro ao desvincular conta", e);
            return "❌ Erro ao desvincular conta";
        }
    }

    /**
     * Processa comando de entrar na fila
     */
    public String handleQueueCommand(String discordId, String primaryLane, String secondaryLane) {
        try {
            // Verificar vinculação
            Optional<DiscordLolLink> link = discordLolLinkRepository.findByDiscordId(discordId);
            if (link.isEmpty()) {
                return "❌ Você precisa vincular uma conta primeiro! Use `/vincular`";
            }

            // Buscar dados do player
            Optional<Player> player = playerRepository.findBySummonerName(link.get().getSummonerName());
            if (player.isEmpty()) {
                return "❌ Jogador não encontrado no sistema";
            }

            // ✅ MIGRADO: Entrar na fila via QueueManagementService (Redis-only)
            boolean joined = queueManagementService.addToQueue(
                    player.get().getSummonerName(),
                    player.get().getRegion(),
                    player.get().getId(),
                    null, // customLp (usa LP atual)
                    primaryLane,
                    secondaryLane);

            if (joined) {
                return "✅ Entrou na fila!\n" +
                        "**Lane Principal:** " + primaryLane + "\n" +
                        "**Lane Secundária:** " + secondaryLane;
            } else {
                return "❌ Erro ao entrar na fila";
            }

        } catch (Exception e) {
            log.error("❌ Erro ao entrar na fila", e);
            return "❌ Erro ao entrar na fila";
        }
    }

    /**
     * Processa comando de sair da fila
     */
    public String handleLeaveCommand(String discordId) {
        try {
            Optional<DiscordLolLink> link = discordLolLinkRepository.findByDiscordId(discordId);
            if (link.isEmpty()) {
                return "❌ Você não possui uma conta vinculada";
            }

            // ✅ MIGRADO: Sair da fila via QueueManagementService (Redis-only)
            boolean left = queueManagementService.removeFromQueue(link.get().getSummonerName());

            if (left) {
                return "✅ Saiu da fila";
            } else {
                return "❌ Você não estava na fila";
            }

        } catch (Exception e) {
            log.error("❌ Erro ao sair da fila", e);
            return "❌ Erro ao sair da fila";
        }
    }

    /**
     * Processa comando de status
     */
    public String handleStatusCommand() {
        try {
            // Implementar lógica de status
            return "📊 **Status da Fila**\n" +
                    "Jogadores na fila: 0\n" +
                    "Tempo médio de espera: 0min";

        } catch (Exception e) {
            log.error("❌ Erro ao obter status", e);
            return "❌ Erro ao obter status";
        }
    }

    /**
     * Processa comando de estatísticas
     */
    public String handleStatsCommand(String discordId) {
        try {
            Optional<DiscordLolLink> link = discordLolLinkRepository.findByDiscordId(discordId);
            if (link.isEmpty()) {
                return "❌ Você não possui uma conta vinculada";
            }

            // Implementar lógica de stats
            return "📈 **Suas Estatísticas**\n" +
                    "Partidas jogadas: 0\n" +
                    "Vitórias: 0\n" +
                    "Derrotas: 0\n" +
                    "Taxa de vitória: 0%";

        } catch (Exception e) {
            log.error("❌ Erro ao obter estatísticas", e);
            return "❌ Erro ao obter estatísticas";
        }
    }

    /**
     * Processa comando de leaderboard
     */
    public String handleLeaderboardCommand(int limit) {
        try {
            // Implementar lógica de leaderboard
            return "🏆 **Ranking de Jogadores**\n" +
                    "1. Jogador1 - 2000 LP\n" +
                    "2. Jogador2 - 1950 LP\n" +
                    "3. Jogador3 - 1900 LP";

        } catch (Exception e) {
            log.error("❌ Erro ao obter leaderboard", e);
            return "❌ Erro ao obter leaderboard";
        }
    }

    /**
     * Processa comando de aceitar partida
     */
    public String handleAcceptCommand(String discordId) {
        try {
            // Implementar lógica de accept
            return "✅ Partida aceita!";

        } catch (Exception e) {
            log.error("❌ Erro ao aceitar partida", e);
            return "❌ Erro ao aceitar partida";
        }
    }

    /**
     * Processa comando de recusar partida
     */
    public String handleDeclineCommand(String discordId) {
        try {
            // Implementar lógica de decline
            return "❌ Partida recusada";

        } catch (Exception e) {
            log.error("❌ Erro ao recusar partida", e);
            return "❌ Erro ao recusar partida";
        }
    }

    /**
     * Processa comando de draft
     */
    public String handleDraftCommand(String discordId, String action, String champion) {
        try {
            // Implementar lógica de draft
            return "🎯 Ação de draft processada: " + action;

        } catch (Exception e) {
            log.error("❌ Erro ao processar ação de draft", e);
            return "❌ Erro ao processar ação de draft";
        }
    }

    /**
     * Processa comando de ajuda
     */
    public String handleHelpCommand() {
        return "**🤖 Comandos Disponíveis**\n\n" +
                "**Conta:**\n" +
                "`/vincular` - Vincular conta Discord com LoL\n" +
                "`/desvincular` - Desvincular conta\n\n" +
                "**Fila:**\n" +
                "`/queue` - Entrar na fila\n" +
                "`/leave` - Sair da fila\n" +
                "`/status` - Ver status da fila\n\n" +
                "`/stats` - Ver suas estatísticas\n" +
                "`/leaderboard` - Ver ranking\n\n" +
                "`/help` - Mostrar esta ajuda";
    }

    /**
     * Cria canal de voz para partida
     */
    public void createMatchVoiceChannel(String matchId, List<String> playerNames) {
        try {
            if (!isConnected) {
                log.warn("⚠️ Discord não conectado, não é possível criar canal de voz");
                return;
            }

            String channelName = "Partida-" + matchId;

            // Em uma implementação real, aqui seria feita a chamada para a API do Discord
            // Para criar o canal de voz no servidor configurado
            log.info("🎤 Criando canal de voz: {} para partida: {}", channelName, matchId);

            // Simular criação do canal (em produção, usar JDA ou API do Discord)
            String channelId = "channel_" + System.currentTimeMillis();
            activeVoiceChannels.put(matchId, channelId);
            channelLastActivity.put(channelId, Instant.now());

            log.info("✅ Canal de voz criado com sucesso: {} (ID: {})", channelName, channelId);

        } catch (Exception e) {
            log.error("❌ Erro ao criar canal de voz", e);
        }
    }

    /**
     * Remove canal de voz da partida
     */
    public void removeMatchVoiceChannel(String matchId) {
        try {
            if (!isConnected) {
                log.warn("⚠️ Discord não conectado, não é possível remover canal de voz");
                return;
            }

            String channelId = activeVoiceChannels.remove(matchId);
            if (channelId != null) {
                // Em uma implementação real, aqui seria feita a chamada para a API do Discord
                // Para deletar o canal de voz
                log.info("🗑️ Removendo canal de voz: {} da partida: {}", channelId, matchId);

                channelLastActivity.remove(channelId);
                log.info("✅ Canal de voz removido com sucesso: {}", channelId);
            } else {
                log.warn("⚠️ Canal de voz não encontrado para partida: {}", matchId);
            }
        } catch (Exception e) {
            log.error("❌ Erro ao remover canal de voz", e);
        }
    }

    /**
     * Move jogadores para canal de voz da partida
     */
    public void movePlayersToMatchChannel(String matchId, List<String> discordUserIds) {
        try {
            if (!isConnected) {
                log.warn("⚠️ Discord não conectado, não é possível mover jogadores");
                return;
            }

            String channelId = activeVoiceChannels.get(matchId);
            if (channelId == null) {
                log.warn("⚠️ Canal de voz não encontrado para partida: {}", matchId);
                return;
            }

            log.info("👥 Movendo {} jogadores para canal da partida: {}", discordUserIds.size(), matchId);

            // Em uma implementação real, aqui seria feita a chamada para a API do Discord
            // Para mover os usuários para o canal de voz
            for (String userId : discordUserIds) {
                log.debug("🔄 Movendo usuário {} para canal {}", userId, channelId);
            }

            log.info("✅ Jogadores movidos com sucesso para canal da partida");

        } catch (Exception e) {
            log.error("❌ Erro ao mover jogadores para canal", e);
        }
    }

    /**
     * Move jogadores de volta para canal original
     */
    public void movePlayersBackToOrigin(String matchId, List<String> discordUserIds) {
        try {
            if (!isConnected) {
                log.warn("⚠️ Discord não conectado, não é possível mover jogadores");
                return;
            }

            log.info("👥 Movendo {} jogadores de volta para canal original da partida: {}",
                    discordUserIds.size(), matchId);

            // Em uma implementação real, aqui seria feita a chamada para a API do Discord
            // Para mover os usuários de volta para o canal de matchmaking
            for (String userId : discordUserIds) {
                log.debug("🔄 Movendo usuário {} de volta para canal original", userId);
            }

            log.info("✅ Jogadores movidos de volta com sucesso");

        } catch (Exception e) {
            log.error("❌ Erro ao mover jogadores de volta", e);
        }
    }

    /**
     * Envia notificação de partida encontrada (simulado)
     */
    public void notifyMatchFound(String matchId, List<String> playerNames) {
        try {
            StringBuilder message = new StringBuilder();
            message.append("🎮 **Partida Encontrada!**\n");
            message.append("**ID:** ").append(matchId).append("\n");
            message.append("**Jogadores:**\n");

            for (String playerName : playerNames) {
                message.append("• ").append(playerName).append("\n");
            }

            message.append("\nUse `/accept` para aceitar ou `/decline` para recusar");

            log.info("📢 Notificação de partida enviada: {}", message.toString());

        } catch (Exception e) {
            log.error("❌ Erro ao enviar notificação de partida", e);
        }
    }

    /**
     * Obtém estatísticas do Discord
     */
    public Map<String, Object> getDiscordStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("connected", isConnected);

        // Obter guildId da configuração ativa
        String guildId = discordConfigRepository.findByIsActiveTrue()
                .map(DiscordConfig::getGuildId)
                .orElse("não configurado");
        stats.put("guild", guildId);

        stats.put("activeVoiceChannels", activeVoiceChannels.size());
        stats.put("totalLinks", discordLolLinkRepository.count());
        return stats;
    }

    /**
     * Notifica quando um jogador entra na fila
     */
    public boolean notifyPlayerJoinedQueue(String summonerName, int queuePosition, int totalInQueue) {
        String channelId = getQueueChannelId();
        if (channelId == null) {
            log.warn("⚠️ Canal da fila não configurado");
            return false;
        }

        String message = String.format("🎮 **%s** entrou na fila! (Posição: %d/%d)",
                summonerName, queuePosition, totalInQueue);

        return discordService.sendMessage(channelId, message);
    }

    /**
     * Notifica quando um jogador sai da fila
     */
    public boolean notifyPlayerLeftQueue(String summonerName, int totalInQueue) {
        String channelId = getQueueChannelId();
        if (channelId == null) {
            log.warn("⚠️ Canal da fila não configurado");
            return false;
        }

        String message = String.format("👋 **%s** saiu da fila! (Restam: %d)",
                summonerName, totalInQueue);

        return discordService.sendMessage(channelId, message);
    }

    /**
     * Notifica quando uma partida é encontrada
     */
    public boolean notifyMatchFound(String matchId, String[] team1, String[] team2) {
        String channelId = getMatchesChannelId();
        if (channelId == null) {
            log.warn("⚠️ Canal de partidas não configurado");
            return false;
        }

        return discordService.notifyMatchFound(channelId, matchId, team1, team2);
    }

    /**
     * Notifica quando um draft é iniciado
     */
    public boolean notifyDraftStarted(String matchId, String draftUrl) {
        String channelId = getMatchesChannelId();
        if (channelId == null) {
            log.warn("⚠️ Canal de partidas não configurado");
            return false;
        }

        return discordService.notifyDraftStarted(channelId, matchId, draftUrl);
    }

    /**
     * ✅ NOVO: Limpeza automática de canais Discord expirados
     * Executa a cada 30 minutos para remover canais inativos há mais de 2 horas
     */
    @Scheduled(fixedRate = 1800000) // 30 minutos
    public void cleanupExpiredChannels() {
        try {
            if (!isConnected) {
                return;
            }

            Instant cutoffTime = Instant.now().minus(CHANNEL_TTL_HOURS, java.time.temporal.ChronoUnit.HOURS);
            List<String> expiredChannels = new ArrayList<>();

            // Verificar canais expirados
            for (Map.Entry<String, Instant> entry : channelLastActivity.entrySet()) {
                if (entry.getValue().isBefore(cutoffTime)) {
                    expiredChannels.add(entry.getKey());
                }
            }

            if (!expiredChannels.isEmpty()) {
                log.info("🧹 [DiscordCleanup] Removendo {} canais expirados (inativos há >{}h)",
                        expiredChannels.size(), CHANNEL_TTL_HOURS);

                for (String channelId : expiredChannels) {
                    // Encontrar matchId correspondente
                    String matchId = null;
                    for (Map.Entry<String, String> entry : activeVoiceChannels.entrySet()) {
                        if (entry.getValue().equals(channelId)) {
                            matchId = entry.getKey();
                            break;
                        }
                    }

                    // Remover do cache
                    channelLastActivity.remove(channelId);
                    if (matchId != null) {
                        activeVoiceChannels.remove(matchId);
                        log.info("🗑️ [DiscordCleanup] Canal expirado removido: matchId={}, channelId={}",
                                matchId, channelId);
                    } else {
                        log.warn("⚠️ [DiscordCleanup] Canal {} encontrado sem matchId correspondente", channelId);
                    }
                }
            }

        } catch (Exception e) {
            log.error("❌ [DiscordCleanup] Erro ao limpar canais expirados", e);
        }
    }
}
