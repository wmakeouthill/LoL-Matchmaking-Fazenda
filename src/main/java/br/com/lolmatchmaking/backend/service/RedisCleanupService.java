package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import br.com.lolmatchmaking.backend.service.lock.PlayerState;
import br.com.lolmatchmaking.backend.service.lock.PlayerStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ✅ Serviço de Limpeza Inteligente do Redis
 * 
 * PROBLEMA:
 * - Chaves órfãs no Redis após partidas finalizadas
 * - game_ack, game_retry, match_vote continuam no Redis
 * - PlayerState IN_MATCH_FOUND sem partida correspondente
 * 
 * SOLUÇÃO:
 * - Limpeza periódica de chaves baseada em partidas ativas no MySQL
 * - Correção automática de PlayerState inconsistente
 * - Remoção de chaves temporárias de partidas finalizadas
 * 
 * FREQUÊNCIA:
 * - A cada 5 minutos (não impacta performance)
 * - Apenas em ambientes de produção/staging
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisCleanupService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CustomMatchRepository customMatchRepository;
    private final PlayerStateService playerStateService;
    private final RedisMatchVoteService redisMatchVoteService;

    // Padrões de chaves para limpeza
    private static final Pattern GAME_ACK_PATTERN = Pattern.compile("game_ack:(\\d+):.*");
    private static final Pattern GAME_RETRY_PATTERN = Pattern.compile("game_retry:(\\d+)");
    private static final Pattern MATCH_VOTE_PATTERN = Pattern.compile("match_vote:(\\d+):.*");
    private static final Pattern PLAYER_STATE_PATTERN = Pattern.compile("state:player:(.+)");

    // Constantes de status
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_MATCH_FOUND = "match_found";
    private static final String STATUS_DRAFT = "draft";

    /**
     * ✅ Limpeza periódica de chaves órfãs
     * Executa a cada 5 minutos
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000) // 5 min delay, 1 min initial
    public void cleanupOrphanedKeys() {
        try {
            log.info("🧹 [RedisCleanup] ===== INICIANDO LIMPEZA PERIÓDICA =====");

            // 1. Limpar chaves de game_ack e game_retry
            int gameKeysCleanedCount = cleanupGameKeys();

            // 2. Limpar chaves de match_vote
            int voteKeysCleanedCount = cleanupMatchVoteKeys();

            // 3. Corrigir PlayerState inconsistente
            int playerStatesFixedCount = fixInconsistentPlayerStates();

            log.info("✅ [RedisCleanup] Limpeza concluída: {} game keys, {} vote keys, {} player states corrigidos",
                    gameKeysCleanedCount, voteKeysCleanedCount, playerStatesFixedCount);

        } catch (Exception e) {
            log.error("❌ [RedisCleanup] Erro durante limpeza periódica", e);
        }
    }

    /**
     * ✅ Limpa chaves game_ack e game_retry de partidas finalizadas
     */
    private int cleanupGameKeys() {
        try {
            // Buscar todas as partidas ativas no MySQL
            List<CustomMatch> activeMatches = customMatchRepository.findByStatus(STATUS_IN_PROGRESS);
            Set<Long> activeMatchIds = activeMatches.stream()
                    .map(CustomMatch::getId)
                    .collect(Collectors.toSet());

            log.debug("🎯 [RedisCleanup] {} partidas ativas no MySQL", activeMatchIds.size());

            int cleanedCount = 0;
            cleanedCount += cleanupGameAckKeys(activeMatchIds);
            cleanedCount += cleanupGameRetryKeys(activeMatchIds);

            if (cleanedCount > 0) {
                log.info("✅ [RedisCleanup] Limpas {} chaves game_ack/game_retry de partidas finalizadas", cleanedCount);
            }

            return cleanedCount;

        } catch (Exception e) {
            log.error("❌ [RedisCleanup] Erro ao limpar chaves de game", e);
            return 0;
        }
    }

    private int cleanupGameAckKeys(Set<Long> activeMatchIds) {
        int count = 0;
        Set<String> gameAckKeys = redisTemplate.keys("game_ack:*");
        if (gameAckKeys != null) {
            for (String key : gameAckKeys) {
                Matcher matcher = GAME_ACK_PATTERN.matcher(key);
                if (matcher.matches()) {
                    Long matchId = Long.parseLong(matcher.group(1));
                    if (!activeMatchIds.contains(matchId)) {
                        redisTemplate.delete(key);
                        count++;
                        log.debug("🗑️ [RedisCleanup] Removido: {}", key);
                    }
                }
            }
        }
        return count;
    }

    private int cleanupGameRetryKeys(Set<Long> activeMatchIds) {
        int count = 0;
        Set<String> gameRetryKeys = redisTemplate.keys("game_retry:*");
        if (gameRetryKeys != null) {
            for (String key : gameRetryKeys) {
                Matcher matcher = GAME_RETRY_PATTERN.matcher(key);
                if (matcher.matches()) {
                    Long matchId = Long.parseLong(matcher.group(1));
                    if (!activeMatchIds.contains(matchId)) {
                        redisTemplate.delete(key);
                        count++;
                        log.debug("🗑️ [RedisCleanup] Removido: {}", key);
                    }
                }
            }
        }
        return count;
    }

    /**
     * ✅ Limpa chaves match_vote de partidas finalizadas
     */
    private int cleanupMatchVoteKeys() {
        try {
            // Buscar todas as partidas ativas (incluindo draft)
            List<CustomMatch> matchFoundMatches = customMatchRepository.findByStatus(STATUS_MATCH_FOUND);
            List<CustomMatch> draftMatches = customMatchRepository.findByStatus(STATUS_DRAFT);
            List<CustomMatch> inProgressMatches = customMatchRepository.findByStatus(STATUS_IN_PROGRESS);

            Set<Long> activeMatchIds = new HashSet<>();
            matchFoundMatches.forEach(m -> activeMatchIds.add(m.getId()));
            draftMatches.forEach(m -> activeMatchIds.add(m.getId()));
            inProgressMatches.forEach(m -> activeMatchIds.add(m.getId()));

            log.debug("🎯 [RedisCleanup] {} partidas ativas/draft no MySQL", activeMatchIds.size());

            int cleanedCount = 0;

            Set<String> matchVoteKeys = redisTemplate.keys("match_vote:*");
            if (matchVoteKeys != null) {
                // Agrupar chaves por matchId
                Map<Long, List<String>> keysByMatch = groupVoteKeysByMatch(matchVoteKeys);

                // Limpar chaves de partidas inativas
                for (Map.Entry<Long, List<String>> entry : keysByMatch.entrySet()) {
                    Long matchId = entry.getKey();
                    if (!activeMatchIds.contains(matchId)) {
                        // Usar o método do service para limpar corretamente
                        redisMatchVoteService.clearVotes(matchId);
                        cleanedCount += entry.getValue().size();
                        log.debug("🗑️ [RedisCleanup] Removidas {} chaves de votação do match {}",
                                entry.getValue().size(), matchId);
                    }
                }
            }

            if (cleanedCount > 0) {
                log.info("✅ [RedisCleanup] Limpas {} chaves match_vote de partidas finalizadas", cleanedCount);
            }

            return cleanedCount;

        } catch (Exception e) {
            log.error("❌ [RedisCleanup] Erro ao limpar chaves de votação", e);
            return 0;
        }
    }

    private Map<Long, List<String>> groupVoteKeysByMatch(Set<String> matchVoteKeys) {
        Map<Long, List<String>> keysByMatch = new HashMap<>();
        for (String key : matchVoteKeys) {
            Matcher matcher = MATCH_VOTE_PATTERN.matcher(key);
            if (matcher.matches()) {
                Long matchId = Long.parseLong(matcher.group(1));
                keysByMatch.computeIfAbsent(matchId, k -> new ArrayList<>()).add(key);
            }
        }
        return keysByMatch;
    }

    /**
     * ✅ Corrige PlayerState inconsistente
     * 
     * Cenários:
     * 1. Player com IN_MATCH_FOUND mas não há partida match_found
     * 2. Player com IN_DRAFT mas não há partida em draft
     * 3. Player com IN_GAME mas não há partida in_progress
     */
    private int fixInconsistentPlayerStates() {
        try {
            int fixedCount = 0;

            // Buscar todas as chaves de estado de jogador
            Set<String> playerStateKeys = redisTemplate.keys("state:player:*");
            if (playerStateKeys == null || playerStateKeys.isEmpty()) {
                return 0;
            }

            log.debug("🎯 [RedisCleanup] Verificando {} estados de jogador", playerStateKeys.size());

            // Buscar partidas ativas por status
            Map<String, Set<String>> activePlayersByStatus = new HashMap<>();

            // match_found
            List<CustomMatch> matchFoundMatches = customMatchRepository.findByStatus(STATUS_MATCH_FOUND);
            Set<String> matchFoundPlayers = extractAllPlayers(matchFoundMatches);
            activePlayersByStatus.put(STATUS_MATCH_FOUND, matchFoundPlayers);

            // draft
            List<CustomMatch> draftMatches = customMatchRepository.findByStatus(STATUS_DRAFT);
            Set<String> draftPlayers = extractAllPlayers(draftMatches);
            activePlayersByStatus.put(STATUS_DRAFT, draftPlayers);

            // in_progress
            List<CustomMatch> inProgressMatches = customMatchRepository.findByStatus(STATUS_IN_PROGRESS);
            Set<String> inProgressPlayers = extractAllPlayers(inProgressMatches);
            activePlayersByStatus.put(STATUS_IN_PROGRESS, inProgressPlayers);

            // Verificar cada estado de jogador
            for (String key : playerStateKeys) {
                fixedCount += checkAndFixPlayerState(key, matchFoundPlayers, draftPlayers, inProgressPlayers);
            }

            if (fixedCount > 0) {
                log.info("✅ [RedisCleanup] Corrigidos {} estados de jogador inconsistentes", fixedCount);
            }

            return fixedCount;

        } catch (Exception e) {
            log.error("❌ [RedisCleanup] Erro ao corrigir estados de jogador", e);
            return 0;
        }
    }

    private int checkAndFixPlayerState(String key, Set<String> matchFoundPlayers,
            Set<String> draftPlayers, Set<String> inProgressPlayers) {
        try {
            Matcher matcher = PLAYER_STATE_PATTERN.matcher(key);
            if (!matcher.matches()) {
                return 0;
            }

            String playerName = matcher.group(1);
            PlayerState currentState = playerStateService.getPlayerState(playerName);

            boolean shouldFix = false;
            String reason = "";

            switch (currentState) {
                case IN_MATCH_FOUND:
                    if (!matchFoundPlayers.contains(playerName.toLowerCase())) {
                        shouldFix = true;
                        reason = "IN_MATCH_FOUND mas não há partida match_found";
                    }
                    break;

                case IN_DRAFT:
                    if (!draftPlayers.contains(playerName.toLowerCase())) {
                        shouldFix = true;
                        reason = "IN_DRAFT mas não há partida em draft";
                    }
                    break;

                case IN_GAME:
                    if (!inProgressPlayers.contains(playerName.toLowerCase())) {
                        shouldFix = true;
                        reason = "IN_GAME mas não há partida in_progress";
                    }
                    break;

                case AVAILABLE, IN_QUEUE:
                    // Estes estados não precisam de validação com partidas
                    break;
                default:
                    break;
            }

            if (shouldFix) {
                // Corrigir estado para AVAILABLE
                boolean fixed = playerStateService.setPlayerState(playerName, PlayerState.AVAILABLE);
                if (fixed) {
                    log.info("✅ [RedisCleanup] Estado corrigido: {} ({}→AVAILABLE) - {}",
                            playerName, currentState, reason);
                    return 1;
                } else {
                    log.warn("⚠️ [RedisCleanup] Falha ao corrigir estado de {}", playerName);
                }
            }

            return 0;

        } catch (Exception e) {
            log.warn("⚠️ [RedisCleanup] Erro ao verificar estado: {}", key, e);
            return 0;
        }
    }

    /**
     * Extrai todos os nomes de jogadores de uma lista de partidas
     */
    private Set<String> extractAllPlayers(List<CustomMatch> matches) {
        Set<String> players = new HashSet<>();

        for (CustomMatch match : matches) {
            if (match.getTeam1PlayersJson() != null) {
                List<String> team1 = parsePlayerNames(match.getTeam1PlayersJson());
                team1.forEach(name -> players.add(name.toLowerCase()));
            }
            if (match.getTeam2PlayersJson() != null) {
                List<String> team2 = parsePlayerNames(match.getTeam2PlayersJson());
                team2.forEach(name -> players.add(name.toLowerCase()));
            }
        }

        return players;
    }

    /**
     * Parse JSON de jogadores
     */
    private List<String> parsePlayerNames(String playersJson) {
        if (playersJson == null || playersJson.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // Remove colchetes e aspas, e faz split por vírgula
            String cleaned = playersJson.replace("[", "").replace("]", "").replace("\"", "");
            if (cleaned.trim().isEmpty()) {
                return new ArrayList<>();
            }
            return Arrays.stream(cleaned.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (Exception e) {
            log.warn("⚠️ [RedisCleanup] Erro ao fazer parse de playersJson: {}", playersJson, e);
            return new ArrayList<>();
        }
    }

    /**
     * ✅ Limpeza manual forçada (para uso em endpoints de debug)
     */
    public Map<String, Integer> forceCleanup() {
        log.info("🧹 [RedisCleanup] ===== LIMPEZA MANUAL FORÇADA =====");

        Map<String, Integer> results = new HashMap<>();
        results.put("gameKeys", cleanupGameKeys());
        results.put("voteKeys", cleanupMatchVoteKeys());
        results.put("playerStates", fixInconsistentPlayerStates());

        log.info("✅ [RedisCleanup] Limpeza manual concluída: {}", results);
        return results;
    }
}
