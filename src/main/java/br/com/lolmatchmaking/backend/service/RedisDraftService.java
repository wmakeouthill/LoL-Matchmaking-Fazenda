package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.QueuePlayer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Serviço de gerenciamento de Draft usando Redis distribuído.
 * 
 * Garante que picks de campeões sejam sincronizados entre múltiplas instâncias
 * do backend, evitando picks duplicados.
 * 
 * Usa:
 * - Redis Set para campeões já pickados/banidos
 * - Redis Hash para mapear jogador → campeão
 * - Redisson Distributed Lock para sincronização
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisDraftService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;

    private static final Duration DRAFT_TTL = Duration.ofHours(1);
    private static final String DRAFT_PREFIX = "draft:";

    /**
     * Inicia um novo draft
     */
    public void startDraft(Long matchId, List<QueuePlayer> team1, List<QueuePlayer> team2) {
        String key = getDraftKey(matchId);

        log.info("🎨 Iniciando draft para partida {} no Redis", matchId);

        // Limpar draft anterior se existir
        clearDraft(matchId);

        // Inicializar estrutura de dados
        Map<String, Object> draftData = new HashMap<>();
        draftData.put("matchId", matchId);
        draftData.put("phase", "ban");
        draftData.put("currentTurn", 0);
        draftData.put("startTime", System.currentTimeMillis());

        redisTemplate.opsForHash().putAll(key + ":data", draftData);
        redisTemplate.expire(key + ":data", DRAFT_TTL);

        // Armazenar jogadores dos times
        team1.forEach(p -> redisTemplate.opsForSet().add(key + ":team1", p.getSummonerName()));
        team2.forEach(p -> redisTemplate.opsForSet().add(key + ":team2", p.getSummonerName()));

        redisTemplate.expire(key + ":team1", DRAFT_TTL);
        redisTemplate.expire(key + ":team2", DRAFT_TTL);

        log.info("✅ Draft iniciado para partida {}", matchId);
    }

    /**
     * Picka um campeão (com distributed lock)
     */
    public boolean pickChampion(Long matchId, String summonerName, Long championId) {
        RLock lock = redissonClient.getLock("draft_lock:" + matchId);

        try {
            // Tentar adquirir lock por até 5 segundos
            if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                try {
                    String key = getDraftKey(matchId);

                    log.info("🎯 {} tentando pickar campeão {} na partida {}", summonerName, championId, matchId);

                    // Verificar se campeão já foi pickado
                    Set<Object> pickedChampions = redisTemplate.opsForSet().members(key + ":picks");
                    if (pickedChampions != null && pickedChampions.contains(championId)) {
                        log.warn("⚠️ Campeão {} já foi pickado", championId);
                        return false;
                    }

                    // Verificar se campeão foi banido
                    Set<Object> bannedChampions = redisTemplate.opsForSet().members(key + ":bans");
                    if (bannedChampions != null && bannedChampions.contains(championId)) {
                        log.warn("⚠️ Campeão {} está banido", championId);
                        return false;
                    }

                    // Adicionar pick
                    redisTemplate.opsForSet().add(key + ":picks", championId);
                    redisTemplate.opsForHash().put(key + ":player_picks", summonerName, championId);

                    redisTemplate.expire(key + ":picks", DRAFT_TTL);
                    redisTemplate.expire(key + ":player_picks", DRAFT_TTL);

                    log.info("✅ {} pickou campeão {} com sucesso", summonerName, championId);
                    return true;

                } finally {
                    lock.unlock();
                }
            } else {
                log.warn("⏸️ Não foi possível adquirir lock para draft da partida {}", matchId);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Erro ao tentar adquirir lock para draft", e);
            return false;
        }
    }

    /**
     * Bane um campeão (com distributed lock)
     */
    public boolean banChampion(Long matchId, String summonerName, Long championId) {
        RLock lock = redissonClient.getLock("draft_lock:" + matchId);

        try {
            if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                try {
                    String key = getDraftKey(matchId);

                    log.info("🚫 {} tentando banir campeão {} na partida {}", summonerName, championId, matchId);

                    // Verificar se campeão já foi banido
                    Set<Object> bannedChampions = redisTemplate.opsForSet().members(key + ":bans");
                    if (bannedChampions != null && bannedChampions.contains(championId)) {
                        log.warn("⚠️ Campeão {} já foi banido", championId);
                        return false;
                    }

                    // Adicionar ban
                    redisTemplate.opsForSet().add(key + ":bans", championId);
                    redisTemplate.opsForHash().put(key + ":player_bans", summonerName, championId);

                    redisTemplate.expire(key + ":bans", DRAFT_TTL);
                    redisTemplate.expire(key + ":player_bans", DRAFT_TTL);

                    log.info("✅ {} baniu campeão {} com sucesso", summonerName, championId);
                    return true;

                } finally {
                    lock.unlock();
                }
            } else {
                log.warn("⏸️ Não foi possível adquirir lock para ban da partida {}", matchId);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Erro ao tentar adquirir lock para ban", e);
            return false;
        }
    }

    /**
     * Confirma o draft de um jogador
     */
    public boolean confirmDraft(Long matchId, String summonerName) {
        String key = getDraftKey(matchId);

        log.info("✅ {} confirmou draft da partida {}", summonerName, matchId);

        redisTemplate.opsForSet().add(key + ":confirmations", summonerName);
        redisTemplate.expire(key + ":confirmations", DRAFT_TTL);

        return true;
    }

    /**
     * Verifica se todos confirmaram o draft
     */
    public boolean allPlayersConfirmed(Long matchId, int totalPlayers) {
        String key = getDraftKey(matchId);

        Long confirmations = redisTemplate.opsForSet().size(key + ":confirmations");
        return confirmations != null && confirmations >= totalPlayers;
    }

    /**
     * ✅ NOVO: Verifica se todos confirmaram (busca total de jogadores do Redis)
     */
    public boolean allPlayersConfirmed(Long matchId) {
        String key = getDraftKey(matchId);

        // Buscar total de jogadores (team1 + team2)
        Long team1Size = redisTemplate.opsForSet().size(key + ":team1");
        Long team2Size = redisTemplate.opsForSet().size(key + ":team2");

        if (team1Size == null || team2Size == null) {
            log.warn("⚠️ Times não encontrados no Redis para match {}", matchId);
            return false;
        }

        int totalPlayers = team1Size.intValue() + team2Size.intValue();

        Long confirmations = redisTemplate.opsForSet().size(key + ":confirmations");
        boolean allConfirmed = confirmations != null && confirmations >= totalPlayers;

        log.info("📊 Match {} - Confirmações: {}/{}", matchId, confirmations, totalPlayers);

        return allConfirmed;
    }

    /**
     * Obtém picks de um jogador
     */
    public Long getPlayerPick(Long matchId, String summonerName) {
        String key = getDraftKey(matchId);
        Object pick = redisTemplate.opsForHash().get(key + ":player_picks", summonerName);
        return pick != null ? ((Number) pick).longValue() : null;
    }

    /**
     * Obtém todos os picks
     */
    @SuppressWarnings("unchecked")
    public Map<String, Long> getAllPicks(Long matchId) {
        String key = getDraftKey(matchId);
        Map<Object, Object> picks = redisTemplate.opsForHash().entries(key + ":player_picks");

        return picks.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> (String) e.getKey(),
                        e -> ((Number) e.getValue()).longValue()));
    }

    /**
     * Obtém todos os bans
     */
    public Set<Long> getAllBans(Long matchId) {
        String key = getDraftKey(matchId);
        Set<Object> bans = redisTemplate.opsForSet().members(key + ":bans");

        if (bans == null) {
            return new HashSet<>();
        }

        return bans.stream()
                .map(b -> ((Number) b).longValue())
                .collect(Collectors.toSet());
    }

    /**
     * Verifica se campeão está disponível para pick
     */
    public boolean isChampionAvailable(Long matchId, Long championId) {
        String key = getDraftKey(matchId);

        // Verificar se foi pickado
        Boolean isPicked = redisTemplate.opsForSet().isMember(key + ":picks", championId);
        if (Boolean.TRUE.equals(isPicked)) {
            return false;
        }

        // Verificar se foi banido
        Boolean isBanned = redisTemplate.opsForSet().isMember(key + ":bans", championId);
        return !Boolean.TRUE.equals(isBanned);
    }

    /**
     * Limpa draft (quando partida finaliza)
     */
    public void clearDraft(Long matchId) {
        String key = getDraftKey(matchId);

        log.info("🧹 Limpando draft da partida {}", matchId);

        redisTemplate.delete(key + ":data");
        redisTemplate.delete(key + ":picks");
        redisTemplate.delete(key + ":bans");
        redisTemplate.delete(key + ":player_picks");
        redisTemplate.delete(key + ":player_bans");
        redisTemplate.delete(key + ":confirmations");
        redisTemplate.delete(key + ":team1");
        redisTemplate.delete(key + ":team2");
    }

    /**
     * Obtém status completo do draft
     */
    public Map<String, Object> getDraftStatus(Long matchId) {
        String key = getDraftKey(matchId);

        Map<String, Object> status = new HashMap<>();
        status.put("picks", getAllPicks(matchId));
        status.put("bans", getAllBans(matchId));
        status.put("confirmations", redisTemplate.opsForSet().members(key + ":confirmations"));
        status.put("data", redisTemplate.opsForHash().entries(key + ":data"));

        return status;
    }

    private String getDraftKey(Long matchId) {
        return DRAFT_PREFIX + matchId;
    }
}
