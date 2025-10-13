package br.com.lolmatchmaking.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Serviço de gerenciamento de aceitação de partidas usando Redis distribuído.
 * 
 * Garante que aceitações de partidas sejam sincronizadas entre múltiplas
 * instâncias
 * do backend, evitando inconsistências.
 * 
 * Usa:
 * - Redis Hash para rastrear aceitações de jogadores
 * - Redis String para status da partida
 * - Redisson Distributed Lock para sincronização
 * - TTL de 30 segundos para limpeza automática
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisMatchAcceptanceService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    // ✅ CORRIGIDO: TTL de 1 minuto (timeout de aceitação)
    // CRÍTICO: Match found timeout é 30-60s, cache não precisa > 1min
    // Cleanup explícito em < 60s (não depende de TTL)
    private static final Duration ACCEPTANCE_TTL = Duration.ofMinutes(1);
    private static final String MATCH_PREFIX = "match:";

    /**
     * Cria uma nova partida pendente de aceitação
     */
    public void createPendingMatch(Long matchId, List<String> playerNames) {
        createPendingMatch(matchId, playerNames, null, null);
    }

    /**
     * ✅ SOBRECARGA: Cria uma nova partida pendente com informações dos times
     */
    public void createPendingMatch(Long matchId, List<String> playerNames, List<String> team1, List<String> team2) {
        String key = getMatchKey(matchId);

        log.info("🎯 Criando partida {} para aceitação com {} jogadores", matchId, playerNames.size());

        // Limpar dados anteriores se existirem
        clearMatch(matchId);

        // Inicializar todos os jogadores como "pending"
        Map<String, Object> acceptances = new HashMap<>();
        for (String playerName : playerNames) {
            acceptances.put(playerName, "pending");
        }

        redisTemplate.opsForHash().putAll(key + ":acceptances", acceptances);
        redisTemplate.expire(key + ":acceptances", ACCEPTANCE_TTL);

        // Armazenar metadados
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("matchId", matchId);
        metadata.put("status", "waiting");
        metadata.put("totalPlayers", playerNames.size());
        metadata.put("startTime", System.currentTimeMillis());

        // ✅ Salvar times se fornecidos
        if (team1 != null && !team1.isEmpty()) {
            metadata.put("team1", team1);
        }
        if (team2 != null && !team2.isEmpty()) {
            metadata.put("team2", team2);
        }

        redisTemplate.opsForHash().putAll(key + ":metadata", metadata);
        redisTemplate.expire(key + ":metadata", ACCEPTANCE_TTL);

        log.info("✅ Partida {} criada no Redis, aguardando aceitações (team1: {}, team2: {})",
                matchId, team1 != null ? team1.size() : 0, team2 != null ? team2.size() : 0);
    }

    /**
     * Registra aceitação de um jogador (com distributed lock)
     */
    public boolean acceptMatch(Long matchId, String summonerName) {
        RLock lock = redissonClient.getLock("match_acceptance_lock:" + matchId);

        try {
            if (lock.tryLock(2, 10, TimeUnit.SECONDS)) {
                try {
                    String key = getMatchKey(matchId);

                    log.info("✅ {} aceitou a partida {}", summonerName, matchId);

                    // Verificar se partida ainda está válida
                    Object status = redisTemplate.opsForHash().get(key + ":metadata", "status");
                    if (status == null || !"waiting".equals(status.toString())) {
                        log.warn("⚠️ Partida {} não está mais aguardando aceitações", matchId);
                        return false;
                    }

                    // Registrar aceitação
                    redisTemplate.opsForHash().put(key + ":acceptances", summonerName, "accepted");

                    // Verificar se todos aceitaram
                    if (checkAllAccepted(matchId)) {
                        redisTemplate.opsForHash().put(key + ":metadata", "status", "all_accepted");
                        log.info("🎉 Todos os jogadores aceitaram a partida {}", matchId);
                        return true;
                    }

                    log.info("⏳ {} aceitou, aguardando outros jogadores da partida {}", summonerName, matchId);
                    return true;

                } finally {
                    lock.unlock();
                }
            } else {
                log.warn("⏸️ Não foi possível adquirir lock para aceitação da partida {}", matchId);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Erro ao tentar adquirir lock para aceitação", e);
            return false;
        }
    }

    /**
     * Registra recusa de um jogador (com distributed lock)
     */
    public boolean declineMatch(Long matchId, String summonerName) {
        RLock lock = redissonClient.getLock("match_acceptance_lock:" + matchId);

        try {
            if (lock.tryLock(2, 10, TimeUnit.SECONDS)) {
                try {
                    String key = getMatchKey(matchId);

                    log.warn("❌ {} recusou a partida {}", summonerName, matchId);

                    // Registrar recusa
                    redisTemplate.opsForHash().put(key + ":acceptances", summonerName, "declined");

                    // Marcar partida como cancelada
                    redisTemplate.opsForHash().put(key + ":metadata", "status", "cancelled");
                    redisTemplate.opsForHash().put(key + ":metadata", "declinedBy", summonerName);

                    log.info("🚫 Partida {} cancelada por recusa de {}", matchId, summonerName);
                    return true;

                } finally {
                    lock.unlock();
                }
            } else {
                log.warn("⏸️ Não foi possível adquirir lock para recusa da partida {}", matchId);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Erro ao tentar adquirir lock para recusa", e);
            return false;
        }
    }

    /**
     * Verifica se todos os jogadores aceitaram
     */
    public boolean checkAllAccepted(Long matchId) {
        String key = getMatchKey(matchId);

        Map<Object, Object> acceptances = redisTemplate.opsForHash().entries(key + ":acceptances");

        if (acceptances.isEmpty()) {
            return false;
        }

        long acceptedCount = acceptances.values().stream()
                .filter(v -> "accepted".equals(v.toString()))
                .count();

        return acceptedCount == acceptances.size();
    }

    /**
     * Obtém status de aceitação de um jogador
     */
    public String getPlayerAcceptanceStatus(Long matchId, String summonerName) {
        String key = getMatchKey(matchId);
        Object status = redisTemplate.opsForHash().get(key + ":acceptances", summonerName);
        return status != null ? status.toString() : "unknown";
    }

    /**
     * Obtém todos os jogadores que aceitaram
     */
    public Set<String> getAcceptedPlayers(Long matchId) {
        String key = getMatchKey(matchId);
        Map<Object, Object> acceptances = redisTemplate.opsForHash().entries(key + ":acceptances");

        Set<String> acceptedPlayers = new HashSet<>();
        for (Map.Entry<Object, Object> entry : acceptances.entrySet()) {
            if ("accepted".equals(entry.getValue().toString())) {
                acceptedPlayers.add(entry.getKey().toString());
            }
        }

        return acceptedPlayers;
    }

    /**
     * Obtém todos os jogadores que ainda não aceitaram
     */
    public Set<String> getPendingPlayers(Long matchId) {
        String key = getMatchKey(matchId);
        Map<Object, Object> acceptances = redisTemplate.opsForHash().entries(key + ":acceptances");

        Set<String> pendingPlayers = new HashSet<>();
        for (Map.Entry<Object, Object> entry : acceptances.entrySet()) {
            if ("pending".equals(entry.getValue().toString())) {
                pendingPlayers.add(entry.getKey().toString());
            }
        }

        return pendingPlayers;
    }

    /**
     * Obtém status da partida
     */
    public String getMatchStatus(Long matchId) {
        String key = getMatchKey(matchId);
        Object status = redisTemplate.opsForHash().get(key + ":metadata", "status");
        return status != null ? status.toString() : "unknown";
    }

    /**
     * Verifica se partida expirou (timeout de 30s)
     */
    public boolean isMatchExpired(Long matchId) {
        String key = getMatchKey(matchId);
        Object startTimeObj = redisTemplate.opsForHash().get(key + ":metadata", "startTime");

        if (startTimeObj == null) {
            return true;
        }

        long startTime = ((Number) startTimeObj).longValue();
        long elapsedMs = System.currentTimeMillis() - startTime;

        return elapsedMs > 30000; // 30 segundos
    }

    /**
     * Marca partida como expirada
     */
    public void expireMatch(Long matchId) {
        String key = getMatchKey(matchId);

        log.warn("⏰ Partida {} expirou (timeout de aceitação)", matchId);

        redisTemplate.opsForHash().put(key + ":metadata", "status", "expired");

        // Manter por mais 5 segundos para logs
        redisTemplate.expire(key + ":metadata", Duration.ofSeconds(5));
        redisTemplate.expire(key + ":acceptances", Duration.ofSeconds(5));
    }

    /**
     * Limpa dados da partida
     */
    public void clearMatch(Long matchId) {
        String key = getMatchKey(matchId);

        log.info("🧹 Limpando dados de aceitação da partida {}", matchId);

        redisTemplate.delete(key + ":acceptances");
        redisTemplate.delete(key + ":metadata");
    }

    /**
     * Obtém status completo da partida
     */
    public Map<String, Object> getMatchAcceptanceStatus(Long matchId) {
        String key = getMatchKey(matchId);

        Map<String, Object> status = new HashMap<>();
        status.put("acceptances", redisTemplate.opsForHash().entries(key + ":acceptances"));
        status.put("metadata", redisTemplate.opsForHash().entries(key + ":metadata"));
        status.put("acceptedPlayers", getAcceptedPlayers(matchId));
        status.put("pendingPlayers", getPendingPlayers(matchId));
        status.put("isExpired", isMatchExpired(matchId));

        return status;
    }

    /**
     * Obtém contagem de aceitações
     */
    public int getAcceptedCount(Long matchId) {
        return getAcceptedPlayers(matchId).size();
    }

    /**
     * Obtém contagem de jogadores pendentes
     */
    public int getPendingCount(Long matchId) {
        return getPendingPlayers(matchId).size();
    }

    /**
     * ✅ NOVO: Obtém TODOS os jogadores da partida (aceitos + pendentes + recusados)
     */
    public List<String> getAllPlayers(Long matchId) {
        String key = getMatchKey(matchId);

        Set<Object> keys = redisTemplate.opsForHash().keys(key + ":acceptances");
        if (keys == null || keys.isEmpty()) {
            return new ArrayList<>();
        }

        return keys.stream()
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * ✅ NOVO: Obtém jogadores do time 1
     */
    public List<String> getTeam1Players(Long matchId) {
        String key = getMatchKey(matchId);
        Object team1 = redisTemplate.opsForHash().get(key + ":metadata", "team1");

        if (team1 instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> team1List = (List<String>) team1;
            return team1List;
        }

        return new ArrayList<>();
    }

    /**
     * ✅ NOVO: Obtém jogadores do time 2
     */
    public List<String> getTeam2Players(Long matchId) {
        String key = getMatchKey(matchId);
        Object team2 = redisTemplate.opsForHash().get(key + ":metadata", "team2");

        if (team2 instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> team2List = (List<String>) team2;
            return team2List;
        }

        return new ArrayList<>();
    }

    /**
     * ✅ NOVO: Obtém lista de partidas pendentes (para timeout check)
     */
    public List<Long> getPendingMatches() {
        // Buscar todas as chaves que começam com "match:" e terminam com ":metadata"
        Set<String> keys = redisTemplate.keys(MATCH_PREFIX + "*:metadata");

        if (keys == null || keys.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> matchIds = new ArrayList<>();
        for (String key : keys) {
            try {
                // Extrair matchId da chave "match:123:metadata"
                String matchIdStr = key.replace(MATCH_PREFIX, "").replace(":metadata", "");
                Long matchId = Long.parseLong(matchIdStr);

                // Verificar se ainda está waiting
                Object status = redisTemplate.opsForHash().get(key, "status");
                if ("waiting".equals(status)) {
                    matchIds.add(matchId);
                }
            } catch (Exception e) {
                log.warn("⚠️ Erro ao parsear matchId da chave: {}", key);
            }
        }

        return matchIds;
    }

    /**
     * ✅ NOVO: Obtém tempo de criação da partida
     */
    public java.time.Instant getMatchCreationTime(Long matchId) {
        String key = getMatchKey(matchId);
        Object startTime = redisTemplate.opsForHash().get(key + ":metadata", "startTime");

        if (startTime instanceof Long) {
            return java.time.Instant.ofEpochMilli((Long) startTime);
        }

        if (startTime instanceof Integer) {
            return java.time.Instant.ofEpochMilli(((Integer) startTime).longValue());
        }

        return null;
    }

    private String getMatchKey(Long matchId) {
        return MATCH_PREFIX + matchId;
    }
}
