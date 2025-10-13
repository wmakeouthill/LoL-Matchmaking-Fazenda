package br.com.lolmatchmaking.backend.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Serviço de monitoramento de jogos em progresso usando Redis distribuído.
 * 
 * Garante que o estado dos jogos seja sincronizado entre múltiplas instâncias
 * do backend, permitindo monitoramento consistente.
 * 
 * Usa:
 * - Redis Hash para dados do jogo
 * - TTL de 2 horas (duração máxima de um jogo)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisGameMonitoringService {

    private final RedisTemplate<String, Object> redisTemplate;

    // ✅ CORRIGIDO: TTL de 1h10min (duração máxima de game)
    // CRÍTICO: Game típico: 30-60min, margem 10min = 1h10min total
    // Cleanup explícito em finishGame/cancelGame (não depende de TTL)
    private static final Duration GAME_TTL = Duration.ofMinutes(70);
    private static final String GAME_PREFIX = "game:";
    private static final String ACTIVE_GAMES_KEY = "active:games";

    @Data
    public static class GameData {
        private Long matchId;
        private String status; // monitoring, finished, cancelled
        private Instant startTime;
        private Instant lastCheck;
        private Map<String, Object> gameStats;
        private boolean isLive;
    }

    /**
     * Inicia monitoramento de um jogo
     */
    public void startMonitoring(Long matchId) {
        String key = getGameKey(matchId);

        log.info("🎮 Iniciando monitoramento do jogo da partida {}", matchId);

        Map<String, Object> gameData = new HashMap<>();
        gameData.put("matchId", matchId);
        gameData.put("status", "monitoring");
        gameData.put("startTime", Instant.now().toEpochMilli());
        gameData.put("lastCheck", Instant.now().toEpochMilli());
        gameData.put("isLive", true);

        redisTemplate.opsForHash().putAll(key, gameData);
        redisTemplate.expire(key, GAME_TTL);

        log.info("✅ Monitoramento iniciado para partida {}", matchId);
    }

    /**
     * Atualiza último check do jogo
     */
    public void updateLastCheck(Long matchId) {
        String key = getGameKey(matchId);
        redisTemplate.opsForHash().put(key, "lastCheck", Instant.now().toEpochMilli());
    }

    /**
     * Marca jogo como finalizado
     */
    public void finishGame(Long matchId, String winningTeam) {
        String key = getGameKey(matchId);

        log.info("🏁 Jogo da partida {} finalizado, vencedor: {}", matchId, winningTeam);

        redisTemplate.opsForHash().put(key, "status", "finished");
        redisTemplate.opsForHash().put(key, "isLive", false);
        redisTemplate.opsForHash().put(key, "finishTime", Instant.now().toEpochMilli());
        redisTemplate.opsForHash().put(key, "winner", winningTeam);

        // Manter dados por mais 5 minutos após finalização
        redisTemplate.expire(key, Duration.ofMinutes(5));
    }

    /**
     * Cancela monitoramento de um jogo
     */
    public void cancelMonitoring(Long matchId) {
        String key = getGameKey(matchId);

        log.warn("🚫 Cancelando monitoramento do jogo da partida {}", matchId);

        redisTemplate.opsForHash().put(key, "status", "cancelled");
        redisTemplate.opsForHash().put(key, "isLive", false);

        // Manter dados por 1 minuto após cancelamento
        redisTemplate.expire(key, Duration.ofMinutes(1));
    }

    /**
     * Verifica se jogo está sendo monitorado
     */
    public boolean isGameMonitored(Long matchId) {
        String key = getGameKey(matchId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * Verifica se jogo está ativo
     */
    public boolean isGameLive(Long matchId) {
        String key = getGameKey(matchId);
        Object isLive = redisTemplate.opsForHash().get(key, "isLive");
        return isLive != null && Boolean.parseBoolean(isLive.toString());
    }

    /**
     * Obtém status do jogo
     */
    public String getGameStatus(Long matchId) {
        String key = getGameKey(matchId);
        Object status = redisTemplate.opsForHash().get(key, "status");
        return status != null ? status.toString() : "unknown";
    }

    /**
     * Obtém dados completos do jogo
     */
    public GameData getGameData(Long matchId) {
        String key = getGameKey(matchId);
        Map<Object, Object> data = redisTemplate.opsForHash().entries(key);

        if (data.isEmpty()) {
            return null;
        }

        GameData gameData = new GameData();
        gameData.setMatchId(matchId);

        if (data.containsKey("status")) {
            gameData.setStatus(data.get("status").toString());
        }

        if (data.containsKey("startTime")) {
            long startTimeMs = ((Number) data.get("startTime")).longValue();
            gameData.setStartTime(Instant.ofEpochMilli(startTimeMs));
        }

        if (data.containsKey("lastCheck")) {
            long lastCheckMs = ((Number) data.get("lastCheck")).longValue();
            gameData.setLastCheck(Instant.ofEpochMilli(lastCheckMs));
        }

        if (data.containsKey("isLive")) {
            gameData.setLive(Boolean.parseBoolean(data.get("isLive").toString()));
        }

        // Obter estatísticas
        Map<Object, Object> stats = redisTemplate.opsForHash().entries(key + ":stats");
        if (!stats.isEmpty()) {
            Map<String, Object> gameStats = new HashMap<>();
            stats.forEach((k, v) -> gameStats.put(k.toString(), v));
            gameData.setGameStats(gameStats);
        }

        return gameData;
    }

    /**
     * Obtém lista de todos os jogos ativos
     */
    public List<Long> getActiveGames() {
        Set<String> keys = redisTemplate.keys(GAME_PREFIX + "*");

        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> activeGames = new ArrayList<>();
        for (String key : keys) {
            if (key.contains(":stats")) {
                continue; // Pular chaves de estatísticas
            }

            try {
                String matchIdStr = key.replace(GAME_PREFIX, "");
                Long matchId = Long.parseLong(matchIdStr);

                if (isGameLive(matchId)) {
                    activeGames.add(matchId);
                }
            } catch (NumberFormatException e) {
                log.warn("⚠️ Chave inválida encontrada: {}", key);
            }
        }

        return activeGames;
    }

    /**
     * Limpa dados de um jogo
     */
    public void clearGame(Long matchId) {
        String key = getGameKey(matchId);

        log.info("🧹 Limpando dados do jogo da partida {}", matchId);

        redisTemplate.delete(key);
        redisTemplate.delete(key + ":stats");
    }

    /**
     * Obtém tempo de jogo em segundos
     */
    public long getGameDurationSeconds(Long matchId) {
        GameData data = getGameData(matchId);

        if (data == null || data.getStartTime() == null) {
            return 0;
        }

        Instant now = Instant.now();
        return Duration.between(data.getStartTime(), now).getSeconds();
    }

    /**
     * Verifica se jogo está travado (sem updates há muito tempo)
     */
    public boolean isGameStale(Long matchId, int maxMinutesWithoutUpdate) {
        GameData data = getGameData(matchId);

        if (data == null || data.getLastCheck() == null) {
            return false;
        }

        Instant now = Instant.now();
        long minutesSinceLastCheck = Duration.between(data.getLastCheck(), now).toMinutes();

        return minutesSinceLastCheck > maxMinutesWithoutUpdate;
    }

    /**
     * ✅ NOVO: Obtém estatísticas do jogo como Map
     */
    public Map<String, Object> getGameStats(Long matchId) {
        GameData data = getGameData(matchId);

        if (data == null) {
            return new HashMap<>();
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("matchId", matchId);
        stats.put("status", data.getStatus());
        stats.put("startedAt", data.getStartTime() != null ? data.getStartTime().toEpochMilli() : null);
        stats.put("lastCheck", data.getLastCheck() != null ? data.getLastCheck().toEpochMilli() : null);

        // Se gameStats existir, incluir os dados nele
        if (data.getGameStats() != null) {
            stats.putAll(data.getGameStats());
        }

        return stats;
    }

    /**
     * ✅ NOVO: Atualiza estatísticas do jogo
     */
    public void updateGameStats(Long matchId, Map<String, Object> stats) {
        String key = getGameKey(matchId);

        if (stats.containsKey("team1Size")) {
            redisTemplate.opsForHash().put(key, "team1Size", stats.get("team1Size"));
        }
        if (stats.containsKey("team2Size")) {
            redisTemplate.opsForHash().put(key, "team2Size", stats.get("team2Size"));
        }
        if (stats.containsKey("startedAt")) {
            // startedAt já foi salvo no startMonitoring
        }

        // Atualizar lastCheck
        redisTemplate.opsForHash().put(key, "lastCheck", Instant.now().toEpochMilli());

        log.debug("📊 Estatísticas atualizadas para match {}", matchId);
    }

    /**
     * ✅ NOVO: Cancela monitoramento de jogo
     */
    public void cancelGame(Long matchId) {
        String key = getGameKey(matchId);

        log.info("❌ Cancelando monitoramento de jogo: {}", matchId);

        // Atualizar status
        redisTemplate.opsForHash().put(key, "status", "cancelled");
        redisTemplate.opsForHash().put(key, "endTime", Instant.now().toEpochMilli());

        // Remover da lista de jogos ativos
        redisTemplate.opsForSet().remove(ACTIVE_GAMES_KEY, matchId);

        // Reduzir TTL para 5 minutos (dados ainda disponíveis para consulta)
        redisTemplate.expire(key, Duration.ofMinutes(5));

        log.info("✅ Jogo {} cancelado no Redis", matchId);
    }

    private String getGameKey(Long matchId) {
        return GAME_PREFIX + matchId;
    }
}
