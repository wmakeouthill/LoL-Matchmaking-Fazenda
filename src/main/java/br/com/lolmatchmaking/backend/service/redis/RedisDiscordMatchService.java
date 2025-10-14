package br.com.lolmatchmaking.backend.service.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * ⚡ Redis service para gerenciar partidas Discord ativas
 * 
 * PROBLEMA RESOLVIDO:
 * - HashMap local activeMatches perdia dados em restart
 * - Canais Discord não eram limpos corretamente
 * - Múltiplos backends não compartilhavam estado
 * 
 * SOLUÇÃO COM REDIS:
 * - TTL automático de 3 horas (duração máxima de uma partida)
 * - Compartilhado entre todas instâncias
 * - Limpeza automática
 * 
 * CHAVES REDIS:
 * - discord:match:{matchId}:category → String categoryId
 * - discord:match:{matchId}:channels:blue → String channelId
 * - discord:match:{matchId}:channels:red → String channelId
 * - discord:match:{matchId}:players:blue → Set<String> discordIds
 * - discord:match:{matchId}:players:red → Set<String> discordIds
 * - discord:match:{matchId}:original_channels → Hash<discordId, channelId>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisDiscordMatchService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String MATCH_PREFIX = "discord:match:";
    // ✅ CORRIGIDO: TTL de 2 horas (máximo recomendado para matches Discord)
    // CRÍTICO: Matches e canais Discord devem expirar rapidamente
    // Cleanup automático após 2h mesmo se não houver limpeza explícita
    private static final Duration TTL = Duration.ofHours(2);

    /**
     * ✅ Registra uma partida Discord ativa
     */
    public void registerMatch(Long matchId, String categoryId, String blueChannelId, String redChannelId,
            List<String> blueTeamDiscordIds, List<String> redTeamDiscordIds) {
        try {
            String baseKey = MATCH_PREFIX + matchId;

            // Armazenar IDs dos canais
            redisTemplate.opsForValue().set(baseKey + ":category", categoryId, TTL);
            redisTemplate.opsForValue().set(baseKey + ":channels:blue", blueChannelId, TTL);
            redisTemplate.opsForValue().set(baseKey + ":channels:red", redChannelId, TTL);

            // Armazenar jogadores dos times
            if (blueTeamDiscordIds != null && !blueTeamDiscordIds.isEmpty()) {
                redisTemplate.opsForSet().add(baseKey + ":players:blue", blueTeamDiscordIds.toArray());
                redisTemplate.expire(baseKey + ":players:blue", TTL);
            }

            if (redTeamDiscordIds != null && !redTeamDiscordIds.isEmpty()) {
                redisTemplate.opsForSet().add(baseKey + ":players:red", redTeamDiscordIds.toArray());
                redisTemplate.expire(baseKey + ":players:red", TTL);
            }

            log.info("✅ [RedisDiscordMatch] Partida {} registrada no Redis", matchId);

        } catch (Exception e) {
            log.error("❌ [RedisDiscordMatch] Erro ao registrar partida {}", matchId, e);
        }
    }

    /**
     * ✅ Busca IDs dos canais de uma partida
     */
    public Map<String, String> getMatchChannels(Long matchId) {
        try {
            String baseKey = MATCH_PREFIX + matchId;
            Map<String, String> channels = new HashMap<>();

            Object categoryId = redisTemplate.opsForValue().get(baseKey + ":category");
            Object blueChannelId = redisTemplate.opsForValue().get(baseKey + ":channels:blue");
            Object redChannelId = redisTemplate.opsForValue().get(baseKey + ":channels:red");

            if (categoryId != null)
                channels.put("category", categoryId.toString());
            if (blueChannelId != null)
                channels.put("blue", blueChannelId.toString());
            if (redChannelId != null)
                channels.put("red", redChannelId.toString());

            return channels;

        } catch (Exception e) {
            log.error("❌ [RedisDiscordMatch] Erro ao buscar canais de match {}", matchId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * ✅ Busca jogadores de um time
     */
    public Set<String> getTeamPlayers(Long matchId, String team) {
        try {
            String baseKey = MATCH_PREFIX + matchId + ":players:" + team;
            Set<Object> playersObj = redisTemplate.opsForSet().members(baseKey);

            if (playersObj == null) {
                return Collections.emptySet();
            }

            Set<String> players = new HashSet<>();
            for (Object obj : playersObj) {
                players.add(obj.toString());
            }

            return players;

        } catch (Exception e) {
            log.error("❌ [RedisDiscordMatch] Erro ao buscar players do time {} de match {}", team, matchId, e);
            return Collections.emptySet();
        }
    }

    /**
     * ✅ Armazena canal original de um jogador
     */
    public void storeOriginalChannel(Long matchId, String discordId, String originalChannelId) {
        try {
            String baseKey = MATCH_PREFIX + matchId + ":original_channels";
            redisTemplate.opsForHash().put(baseKey, discordId, originalChannelId);
            redisTemplate.expire(baseKey, TTL);

        } catch (Exception e) {
            log.error("❌ [RedisDiscordMatch] Erro ao armazenar canal original", e);
        }
    }

    /**
     * ✅ Busca todos os canais originais
     */
    public Map<String, String> getOriginalChannels(Long matchId) {
        try {
            String baseKey = MATCH_PREFIX + matchId + ":original_channels";
            Map<Object, Object> channelsObj = redisTemplate.opsForHash().entries(baseKey);

            Map<String, String> channels = new HashMap<>();
            for (Map.Entry<Object, Object> entry : channelsObj.entrySet()) {
                channels.put(entry.getKey().toString(), entry.getValue().toString());
            }

            return channels;

        } catch (Exception e) {
            log.error("❌ [RedisDiscordMatch] Erro ao buscar canais originais de match {}", matchId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * ✅ Remove partida do Redis
     */
    public void removeMatch(Long matchId) {
        try {
            String baseKey = MATCH_PREFIX + matchId;

            // Deletar todas as chaves relacionadas
            redisTemplate.delete(baseKey + ":category");
            redisTemplate.delete(baseKey + ":channels:blue");
            redisTemplate.delete(baseKey + ":channels:red");
            redisTemplate.delete(baseKey + ":players:blue");
            redisTemplate.delete(baseKey + ":players:red");
            redisTemplate.delete(baseKey + ":original_channels");

            log.info("🗑️ [RedisDiscordMatch] Partida {} removida do Redis", matchId);

        } catch (Exception e) {
            log.error("❌ [RedisDiscordMatch] Erro ao remover partida {}", matchId, e);
        }
    }

    /**
     * ✅ Verifica se partida existe no Redis
     */
    public boolean matchExists(Long matchId) {
        try {
            String baseKey = MATCH_PREFIX + matchId + ":category";
            return Boolean.TRUE.equals(redisTemplate.hasKey(baseKey));

        } catch (Exception e) {
            log.error("❌ [RedisDiscordMatch] Erro ao verificar se match {} existe", matchId, e);
            return false;
        }
    }
}
