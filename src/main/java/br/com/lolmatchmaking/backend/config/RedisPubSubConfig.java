package br.com.lolmatchmaking.backend.config;

import br.com.lolmatchmaking.backend.service.EventBroadcastService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * ✅ Configuração Redis Pub/Sub
 * 
 * PROBLEMA RESOLVIDO:
 * - Múltiplas instâncias do backend no Cloud Run sem sincronização
 * - Eventos (match_found, queue_update) não chegando para todos os jogadores
 * - Broadcasting limitado a clientes da mesma instância
 * 
 * SOLUÇÃO:
 * - Redis Pub/Sub para sincronizar eventos entre TODAS as instâncias
 * - Cada instância escuta eventos e faz broadcast via WebSocket para seus
 * clientes
 * - Garante que TODOS os jogadores recebem notificações, independente da
 * instância
 * 
 * CANAIS CONFIGURADOS:
 * - queue:* (eventos de fila: queue:update, queue:player_joined,
 * queue:player_left)
 * - match:* (eventos de partida: match:found, match:acceptance)
 * - draft:* (eventos de draft: draft:started, draft:pick, draft:ban)
 * 
 * ARQUITETURA:
 * Evento → Redis Pub/Sub → TODAS as instâncias → WebSocket → TODOS os clientes
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md#implementação---redis-pubsub
 * - TODO-CORRECOES-PRODUCAO.md#21
 */
@Slf4j
@Configuration
public class RedisPubSubConfig {

    /**
     * ✅ Container para escutar mensagens do Redis Pub/Sub
     * 
     * Configurado para escutar múltiplos canais usando pattern topics.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter queueListenerAdapter,
            MessageListenerAdapter matchListenerAdapter,
            MessageListenerAdapter draftListenerAdapter,
            MessageListenerAdapter spectatorListenerAdapter,
            MessageListenerAdapter gameListenerAdapter) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // ✅ CANAIS DE FILA (queue:*)
        container.addMessageListener(queueListenerAdapter, new PatternTopic("queue:*"));

        // ✅ CANAIS DE PARTIDA (match:*)
        container.addMessageListener(matchListenerAdapter, new PatternTopic("match:*"));

        // ✅ CANAIS DE DRAFT (draft:*)
        container.addMessageListener(draftListenerAdapter, new PatternTopic("draft:*"));

        // ✅ CANAIS DE ESPECTADORES (spectator:*)
        container.addMessageListener(spectatorListenerAdapter, new PatternTopic("spectator:*"));

        // ✅ CANAIS DE GAME (game:*)
        container.addMessageListener(gameListenerAdapter, new PatternTopic("game:*"));

        log.info("✅ [RedisPubSub] Configurado para escutar canais: queue:*, match:*, draft:*, spectator:*, game:*");

        return container;
    }

    /**
     * ✅ Adapter para escutar eventos de fila
     */
    @Bean
    public MessageListenerAdapter queueListenerAdapter(EventBroadcastService eventBroadcastService) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(
                eventBroadcastService,
                "handleQueueEvent");

        // ✅ CRÍTICO: Configurar adapter para aceitar nome do canal como segundo
        // parâmetro
        adapter.setSerializer(null); // Usar String diretamente

        log.info("✅ [RedisPubSub] Queue listener adapter criado");
        return adapter;
    }

    /**
     * ✅ Adapter para escutar eventos de partida
     */
    @Bean
    public MessageListenerAdapter matchListenerAdapter(EventBroadcastService eventBroadcastService) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(
                eventBroadcastService,
                "handleMatchEvent");

        // ✅ CRÍTICO: Configurar adapter para aceitar nome do canal como segundo
        // parâmetro
        adapter.setSerializer(null); // Usar String diretamente

        log.info("✅ [RedisPubSub] Match listener adapter criado");
        return adapter;
    }

    /**
     * ✅ Adapter para escutar eventos de draft
     */
    @Bean
    public MessageListenerAdapter draftListenerAdapter(EventBroadcastService eventBroadcastService) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(
                eventBroadcastService,
                "handleDraftEvent");

        // ✅ CRÍTICO: Configurar adapter para aceitar nome do canal como segundo
        // parâmetro
        adapter.setSerializer(null); // Usar String diretamente

        log.info("✅ [RedisPubSub] Draft listener adapter criado");
        return adapter;
    }

    /**
     * ✅ Adapter para escutar eventos de espectadores
     */
    @Bean
    public MessageListenerAdapter spectatorListenerAdapter(EventBroadcastService eventBroadcastService) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(
                eventBroadcastService,
                "handleSpectatorEvent");

        // ✅ CRÍTICO: Configurar adapter para aceitar nome do canal como segundo
        // parâmetro
        adapter.setSerializer(null); // Usar String diretamente

        log.info("✅ [RedisPubSub] Spectator listener adapter criado");
        return adapter;
    }

    /**
     * ✅ Adapter para escutar eventos de game
     */
    @Bean
    public MessageListenerAdapter gameListenerAdapter(EventBroadcastService eventBroadcastService) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(
                eventBroadcastService,
                "handleGameEvent");

        // ✅ CRÍTICO: Configurar adapter para aceitar nome do canal como segundo
        // parâmetro
        adapter.setSerializer(null); // Usar String diretamente

        log.info("✅ [RedisPubSub] Game listener adapter criado");
        return adapter;
    }
}
