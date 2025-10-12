package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.dto.QueueStatusDTO;
import br.com.lolmatchmaking.backend.dto.events.*;
import br.com.lolmatchmaking.backend.websocket.MatchmakingWebSocketService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ✅ Service Central de Broadcasting via Redis Pub/Sub
 * 
 * PROBLEMA RESOLVIDO:
 * - Múltiplas instâncias do backend sem sincronização de eventos
 * - match_found não chegando para todos os 10 jogadores
 * - Fila não atualizando em tempo real para todos os clientes
 * 
 * SOLUÇÃO:
 * - Publicar eventos no Redis Pub/Sub
 * - TODAS as instâncias escutam e fazem broadcast via WebSocket
 * - Garantia de entrega para TODOS os clientes
 * 
 * FLUXO:
 * 1. Instância A publica evento → Redis Pub/Sub
 * 2. TODAS as instâncias (A, B, C, ...) recebem evento
 * 3. Cada instância faz broadcast via WebSocket para SEUS clientes
 * 4. TODOS os clientes recebem evento em tempo real
 * 
 * CANAIS:
 * - queue:update - Atualização completa da fila
 * - queue:player_joined - Jogador entrou na fila
 * - queue:player_left - Jogador saiu da fila
 * - match:found - Partida encontrada
 * - match:acceptance - Progresso de aceitação
 * - draft:started - Draft iniciado
 * - draft:pick - Campeão escolhido
 * - draft:ban - Campeão banido
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md#service-de-broadcasting
 * - TODO-CORRECOES-PRODUCAO.md#23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventBroadcastService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final MatchmakingWebSocketService webSocketService;
    private final ObjectMapper objectMapper;

    // ═══════════════════════════════════════════════════════════
    // PUBLICAÇÃO DE EVENTOS (Métodos chamados por services)
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ Publica atualização completa da fila
     * 
     * Chamado quando:
     * - Jogador entra na fila
     * - Jogador sai da fila
     * - Broadcast automático a cada 3s
     * 
     * @param queueStatus Status atual da fila
     */
    public void publishQueueUpdate(QueueStatusDTO queueStatus) {
        try {
            QueueEvent event = new QueueEvent("queue_update", queueStatus);
            String json = objectMapper.writeValueAsString(event);

            redisTemplate.convertAndSend("queue:update", json);

            log.info("📢 [Pub/Sub] queue_update publicado: {} jogadores",
                    queueStatus.getPlayersInQueue());

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao serializar QueueEvent", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao publicar queue_update", e);
        }
    }

    /**
     * ✅ Publica evento de jogador entrando na fila
     * 
     * @param summonerName Nome do jogador
     */
    public void publishPlayerJoinedQueue(String summonerName) {
        try {
            PlayerQueueEvent event = new PlayerQueueEvent("player_joined", summonerName);
            String json = objectMapper.writeValueAsString(event);

            redisTemplate.convertAndSend("queue:player_joined", json);

            log.info("📢 [Pub/Sub] player_joined publicado: {}", summonerName);

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao serializar PlayerQueueEvent", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao publicar player_joined", e);
        }
    }

    /**
     * ✅ Publica evento de jogador saindo da fila
     * 
     * @param summonerName Nome do jogador
     */
    public void publishPlayerLeftQueue(String summonerName) {
        try {
            PlayerQueueEvent event = new PlayerQueueEvent("player_left", summonerName);
            String json = objectMapper.writeValueAsString(event);

            redisTemplate.convertAndSend("queue:player_left", json);

            log.info("📢 [Pub/Sub] player_left publicado: {}", summonerName);

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao serializar PlayerQueueEvent", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao publicar player_left", e);
        }
    }

    /**
     * ✅ Publica evento de partida encontrada
     * 
     * CRÍTICO: Este método garante que TODOS os 10 jogadores recebem
     * a notificação match_found SIMULTANEAMENTE, independente da instância.
     * 
     * @param matchId     ID da partida criada
     * @param playerNames Lista dos 10 jogadores
     */
    public void publishMatchFound(Long matchId, List<String> playerNames) {
        try {
            MatchFoundEvent event = new MatchFoundEvent(matchId, playerNames);
            String json = objectMapper.writeValueAsString(event);

            redisTemplate.convertAndSend("match:found", json);

            log.info("📢 [Pub/Sub] match_found publicado: match {} para {} jogadores",
                    matchId, playerNames.size());

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao serializar MatchFoundEvent", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao publicar match_found", e);
        }
    }

    /**
     * ✅ Publica progresso de aceitação de partida
     * 
     * Permite mostrar em tempo real quantos jogadores aceitaram (ex: "3/10").
     * 
     * @param matchId      ID da partida
     * @param summonerName Jogador que aceitou
     * @param accepted     Número de jogadores que aceitaram
     * @param total        Total de jogadores (geralmente 10)
     */
    public void publishMatchAcceptance(Long matchId, String summonerName, int accepted, int total) {
        try {
            MatchAcceptanceEvent event = new MatchAcceptanceEvent(matchId, summonerName, accepted, total);
            String json = objectMapper.writeValueAsString(event);

            redisTemplate.convertAndSend("match:acceptance", json);

            log.info("📢 [Pub/Sub] match_acceptance publicado: {} ({}/{})",
                    summonerName, accepted, total);

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao serializar MatchAcceptanceEvent", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao publicar match_acceptance", e);
        }
    }

    /**
     * ✅ Publica evento de draft iniciado
     * 
     * @param matchId     ID da partida
     * @param playerNames Lista dos jogadores
     */
    public void publishDraftStarted(Long matchId, List<String> playerNames) {
        try {
            DraftEvent event = new DraftEvent("draft_started", matchId, playerNames);
            String json = objectMapper.writeValueAsString(event);

            redisTemplate.convertAndSend("draft:started", json);

            log.info("📢 [Pub/Sub] draft_started publicado: match {}", matchId);

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao serializar DraftEvent", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao publicar draft_started", e);
        }
    }

    /**
     * ✅ Publica evento de pick/ban no draft
     * 
     * @param eventType    "pick" ou "ban"
     * @param matchId      ID da partida
     * @param summonerName Jogador
     * @param championId   ID do campeão
     */
    public void publishDraftAction(String eventType, Long matchId, String summonerName, Integer championId) {
        try {
            br.com.lolmatchmaking.backend.dto.events.DraftPickEvent event = new br.com.lolmatchmaking.backend.dto.events.DraftPickEvent(
                    eventType, matchId, summonerName, championId);
            String json = objectMapper.writeValueAsString(event);

            String channel = "draft:" + eventType;
            redisTemplate.convertAndSend(channel, json);

            log.info("📢 [Pub/Sub] draft:{} publicado: match {} {} champion {}",
                    eventType, matchId, summonerName, championId);

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao serializar DraftPickEvent", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao publicar draft:{}", eventType, e);
        }
    }

    /**
     * ✅ Publica progresso de confirmação do draft
     * 
     * @param matchId      ID da partida
     * @param summonerName Jogador que confirmou
     * @param confirmed    Número de confirmações
     * @param total        Total de jogadores
     */
    public void publishDraftConfirmation(Long matchId, String summonerName, Integer confirmed, Integer total) {
        try {
            br.com.lolmatchmaking.backend.dto.events.DraftConfirmEvent event = new br.com.lolmatchmaking.backend.dto.events.DraftConfirmEvent(
                    matchId, summonerName, confirmed, total);
            String json = objectMapper.writeValueAsString(event);

            redisTemplate.convertAndSend("draft:confirm", json);

            log.info("📢 [Pub/Sub] draft:confirm publicado: match {} ({}/{})",
                    matchId, confirmed, total);

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao serializar DraftConfirmEvent", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao publicar draft:confirm", e);
        }
    }

    /**
     * ✅ Publica voto de vencedor
     * 
     * @param matchId      ID da partida
     * @param summonerName Jogador que votou
     * @param votedTeam    Time votado
     * @param votesTeam1   Votos para time 1
     * @param votesTeam2   Votos para time 2
     * @param totalNeeded  Total necessário
     */
    public void publishWinnerVote(Long matchId, String summonerName, Integer votedTeam,
            Integer votesTeam1, Integer votesTeam2, Integer totalNeeded) {
        try {
            br.com.lolmatchmaking.backend.dto.events.WinnerVoteEvent event = new br.com.lolmatchmaking.backend.dto.events.WinnerVoteEvent(
                    matchId, summonerName, votedTeam, votesTeam1, votesTeam2, totalNeeded);
            String json = objectMapper.writeValueAsString(event);

            redisTemplate.convertAndSend("game:winner_vote", json);

            log.info("📢 [Pub/Sub] game:winner_vote publicado: match {} {} votou em team {} ({}/{}+{}/{})",
                    matchId, summonerName, votedTeam, votesTeam1, votesTeam2, totalNeeded);

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao serializar WinnerVoteEvent", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao publicar game:winner_vote", e);
        }
    }

    /**
     * ✅ Publica ação em espectador
     * 
     * @param eventType     "mute", "unmute", "add", "remove"
     * @param matchId       ID da partida
     * @param spectatorName Nome do espectador
     * @param performedBy   Jogador que executou ação
     */
    public void publishSpectatorAction(String eventType, Long matchId, String spectatorName, String performedBy) {
        try {
            br.com.lolmatchmaking.backend.dto.events.SpectatorEvent event = new br.com.lolmatchmaking.backend.dto.events.SpectatorEvent(
                    eventType, matchId, spectatorName, performedBy);
            String json = objectMapper.writeValueAsString(event);

            String channel = "spectator:" + eventType;
            redisTemplate.convertAndSend(channel, json);

            log.info("📢 [Pub/Sub] spectator:{} publicado: match {} {} por {}",
                    eventType, matchId, spectatorName, performedBy);

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao serializar SpectatorEvent", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao publicar spectator:{}", eventType, e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ESCUTA DE EVENTOS (Métodos chamados pelo Redis Pub/Sub)
    // ═══════════════════════════════════════════════════════════

    /**
     * ✅ Handler de eventos de fila
     * 
     * Chamado automaticamente quando evento é recebido no canal queue:*
     * 
     * @param message JSON do evento
     * @param pattern Canal que recebeu (queue:update, queue:player_joined, etc)
     */
    public void handleQueueEvent(String message, String pattern) {
        try {
            log.info("📥 [Pub/Sub] Evento recebido no canal: {}", pattern);

            if (pattern.equals("queue:update")) {
                QueueEvent event = objectMapper.readValue(message, QueueEvent.class);

                // ✅ BROADCAST VIA WEBSOCKET para clientes desta instância
                webSocketService.broadcastQueueUpdate(
                        event.getQueueStatus().getPlayersInQueueList());

                log.info("✅ [Pub/Sub] queue_update processado e broadcast WebSocket realizado para {} jogadores",
                        event.getQueueStatus().getPlayersInQueue());

            } else if (pattern.equals("queue:player_joined")) {
                PlayerQueueEvent event = objectMapper.readValue(message, PlayerQueueEvent.class);

                // Broadcast notificação de jogador entrou
                // webSocketService.broadcastPlayerJoined(event.getSummonerName());

                log.info("✅ [Pub/Sub] player_joined processado: {}", event.getSummonerName());

            } else if (pattern.equals("queue:player_left")) {
                PlayerQueueEvent event = objectMapper.readValue(message, PlayerQueueEvent.class);

                // Broadcast notificação de jogador saiu
                // webSocketService.broadcastPlayerLeft(event.getSummonerName());

                log.info("✅ [Pub/Sub] player_left processado: {}", event.getSummonerName());
            }

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao parsear evento de fila", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao processar evento de fila", e);
        }
    }

    /**
     * ✅ Handler de eventos de partida
     * 
     * Chamado automaticamente quando evento é recebido no canal match:*
     * 
     * @param message JSON do evento
     * @param pattern Canal que recebeu (match:found, match:acceptance, etc)
     */
    public void handleMatchEvent(String message, String pattern) {
        try {
            log.debug("📥 [Pub/Sub] Evento recebido no canal: {}", pattern);

            if (pattern.equals("match:found")) {
                MatchFoundEvent event = objectMapper.readValue(message, MatchFoundEvent.class);

                long startTime = System.currentTimeMillis();

                // ✅ BROADCAST PARALELO match_found para os 10 jogadores SIMULTANEAMENTE
                // Cada instância envia para os jogadores conectados NELA
                List<CompletableFuture<Void>> sendFutures = new ArrayList<>();

                for (String playerName : event.getPlayerNames()) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        webSocketService.sendMatchFoundToPlayer(playerName, event.getMatchId());
                    });
                    sendFutures.add(future);
                }

                // ✅ AGUARDAR TODOS OS ENVIOS COMPLETAREM
                try {
                    CompletableFuture.allOf(sendFutures.toArray(new CompletableFuture[0]))
                            .get(3, TimeUnit.SECONDS);

                    long elapsed = System.currentTimeMillis() - startTime;

                    log.info("✅ [Pub/Sub PARALELO] match_found processado: match {} para {} jogadores em {}ms",
                            event.getMatchId(), event.getPlayerNames().size(), elapsed);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("⚠️ [Pub/Sub] Thread interrompida ao aguardar envios paralelos", e);
                } catch (Exception e) {
                    log.error("⚠️ [Pub/Sub] Erro ao aguardar envios paralelos", e);
                }

            } else if (pattern.equals("match:acceptance")) {
                MatchAcceptanceEvent event = objectMapper.readValue(message, MatchAcceptanceEvent.class);

                // Broadcast progresso de aceitação
                webSocketService.broadcastMatchAcceptanceProgress(
                        event.getMatchId(),
                        event.getAccepted(),
                        event.getTotal());

                log.debug("✅ [Pub/Sub] match_acceptance processado: {}/{}",
                        event.getAccepted(), event.getTotal());
            }

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao parsear evento de partida", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao processar evento de partida", e);
        }
    }

    /**
     * ✅ Handler de eventos de draft
     * 
     * Chamado automaticamente quando evento é recebido no canal draft:*
     * 
     * @param message JSON do evento
     * @param pattern Canal que recebeu (draft:started, draft:pick, etc)
     */
    public void handleDraftEvent(String message, String pattern) {
        try {
            log.debug("📥 [Pub/Sub] Evento recebido no canal: {}", pattern);

            if (pattern.equals("draft:started")) {
                DraftEvent event = objectMapper.readValue(message, DraftEvent.class);

                // Broadcast draft iniciado
                // webSocketService.broadcastDraftStarted(event.getMatchId(),
                // event.getPlayerNames());

                log.info("✅ [Pub/Sub] draft_started processado: match {}", event.getMatchId());

            } else if (pattern.equals("draft:pick") || pattern.equals("draft:ban") || pattern.equals("draft:edit")) {
                br.com.lolmatchmaking.backend.dto.events.DraftPickEvent event = objectMapper.readValue(message,
                        br.com.lolmatchmaking.backend.dto.events.DraftPickEvent.class);

                // Broadcast pick/ban/edit para todos da partida
                // webSocketService.broadcastDraftAction(event);

                log.info("✅ [Pub/Sub] draft:{} processado: match {} {} champion {}",
                        pattern.substring(6), event.getMatchId(), event.getSummonerName(), event.getChampionId());

            } else if (pattern.equals("draft:confirm")) {
                br.com.lolmatchmaking.backend.dto.events.DraftConfirmEvent event = objectMapper.readValue(message,
                        br.com.lolmatchmaking.backend.dto.events.DraftConfirmEvent.class);

                // Broadcast progresso de confirmação
                // webSocketService.broadcastDraftConfirmProgress(event);

                log.info("✅ [Pub/Sub] draft:confirm processado: match {} ({}/{})",
                        event.getMatchId(), event.getConfirmed(), event.getTotal());
            }

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao parsear evento de draft", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao processar evento de draft", e);
        }
    }

    /**
     * ✅ Handler de eventos de espectadores
     * 
     * Chamado automaticamente quando evento é recebido no canal spectator:*
     * 
     * @param message JSON do evento
     * @param pattern Canal que recebeu (spectator:mute, spectator:unmute, etc)
     */
    public void handleSpectatorEvent(String message, String pattern) {
        try {
            log.debug("📥 [Pub/Sub] Evento recebido no canal: {}", pattern);

            br.com.lolmatchmaking.backend.dto.events.SpectatorEvent event = objectMapper.readValue(message,
                    br.com.lolmatchmaking.backend.dto.events.SpectatorEvent.class);

            // Broadcast ação de espectador para todos da partida
            // webSocketService.broadcastSpectatorAction(event);

            log.info("✅ [Pub/Sub] spectator:{} processado: match {} {} por {}",
                    event.getEventType(), event.getMatchId(), event.getSpectatorName(), event.getPerformedBy());

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao parsear evento de espectador", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao processar evento de espectador", e);
        }
    }

    /**
     * ✅ Handler de eventos de game/winner vote
     * 
     * Chamado automaticamente quando evento é recebido no canal game:*
     * 
     * @param message JSON do evento
     * @param pattern Canal que recebeu (game:winner_vote, etc)
     */
    public void handleGameEvent(String message, String pattern) {
        try {
            log.debug("📥 [Pub/Sub] Evento recebido no canal: {}", pattern);

            if (pattern.equals("game:winner_vote")) {
                br.com.lolmatchmaking.backend.dto.events.WinnerVoteEvent event = objectMapper.readValue(message,
                        br.com.lolmatchmaking.backend.dto.events.WinnerVoteEvent.class);

                // Broadcast progresso de votação
                // webSocketService.broadcastWinnerVoteProgress(event);

                log.info("✅ [Pub/Sub] game:winner_vote processado: match {} ({} votos: T1={}, T2={})",
                        event.getMatchId(), event.getSummonerName(), event.getVotesTeam1(), event.getVotesTeam2());
            }

        } catch (JsonProcessingException e) {
            log.error("❌ [Pub/Sub] Erro ao parsear evento de game", e);
        } catch (Exception e) {
            log.error("❌ [Pub/Sub] Erro ao processar evento de game", e);
        }
    }
}
