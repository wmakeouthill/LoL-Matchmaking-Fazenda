package br.com.lolmatchmaking.backend.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * ✅ Service de Lock para Draft (Pick/Ban)
 * 
 * PROBLEMA RESOLVIDO:
 * - Múltiplos jogadores tentando pick/ban simultaneamente
 * - Ordem de picks/bans não sincronizada entre jogadores
 * - Confirmação de draft sem coordenação
 * 
 * SOLUÇÃO:
 * - Lock de turno (quem pode agir agora)
 * - Lock de ação (prevenir múltiplas ações simultâneas)
 * - Lock de confirmação (coordenar modal de confirmação)
 * 
 * CHAVES REDIS:
 * - lock:draft:{matchId}:turn → Lock do turno atual
 * - lock:draft:{matchId}:action → Lock de ação (pick/ban)
 * - lock:draft:{matchId}:confirm → Lock de confirmação
 * - state:draft:{matchId}:current_turn → Turno atual (número)
 * - state:draft:{matchId}:current_player → Jogador do turno
 * 
 * REFERÊNCIA:
 * - ARQUITETURA-CORRETA-SINCRONIZACAO.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftLockService {

    private final RedisTemplate<String, Object> redisTemplate;

    // Prefixos de chave
    private static final String DRAFT_TURN_LOCK_PREFIX = "lock:draft:";
    private static final String DRAFT_ACTION_LOCK_PREFIX = "lock:draft:";
    private static final String DRAFT_CONFIRM_LOCK_PREFIX = "lock:draft:";
    private static final String DRAFT_STATE_PREFIX = "state:draft:";

    // TTLs
    private static final Duration TURN_LOCK_TTL = Duration.ofSeconds(60); // 1 minuto por turno
    private static final Duration ACTION_LOCK_TTL = Duration.ofSeconds(5); // 5 segundos por ação
    private static final Duration CONFIRM_LOCK_TTL = Duration.ofSeconds(30); // 30 segundos para confirmar

    /**
     * ✅ Adquire lock do turno do draft
     * 
     * @param matchId     ID da partida
     * @param currentTurn Número do turno atual
     * @return true se conseguiu adquirir, false se outro turno está ativo
     */
    public boolean acquireTurnLock(Long matchId, int currentTurn) {
        String key = DRAFT_TURN_LOCK_PREFIX + matchId + ":turn";

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, String.valueOf(currentTurn), TURN_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [DraftLock] Turn lock adquirido: match {} turno {}", matchId, currentTurn);
                return true;
            }

            // Verificar se é o mesmo turno
            String existingTurn = (String) redisTemplate.opsForValue().get(key);
            if (String.valueOf(currentTurn).equals(existingTurn)) {
                // Renovar TTL
                redisTemplate.expire(key, TURN_LOCK_TTL);
                return true;
            }

            log.warn("⚠️ [DraftLock] Turn lock ocupado: match {} turno atual {}, esperado {}",
                    matchId, existingTurn, currentTurn);
            return false;

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao adquirir turn lock para match {}", matchId, e);
            return false;
        }
    }

    /**
     * ✅ Libera lock do turno
     * 
     * @param matchId ID da partida
     */
    public void releaseTurnLock(Long matchId) {
        String key = DRAFT_TURN_LOCK_PREFIX + matchId + ":turn";

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("🔓 [DraftLock] Turn lock liberado: match {}", matchId);
            }

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao liberar turn lock de match {}", matchId, e);
        }
    }

    /**
     * ✅ Adquire lock de ação (pick ou ban)
     * 
     * Previne múltiplos picks/bans simultâneos na mesma partida.
     * 
     * @param matchId      ID da partida
     * @param summonerName Jogador que está fazendo a ação
     * @param action       Tipo de ação ("pick" ou "ban")
     * @return true se conseguiu adquirir, false se outra ação está em andamento
     */
    public boolean acquireActionLock(Long matchId, String summonerName, String action) {
        String key = DRAFT_ACTION_LOCK_PREFIX + matchId + ":action";
        String value = summonerName + ":" + action;

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, value, ACTION_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [DraftLock] Action lock adquirido: match {} {} por {}",
                        matchId, action, summonerName);
                return true;
            }

            log.warn("⚠️ [DraftLock] Action lock ocupado: match {}", matchId);
            return false;

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao adquirir action lock para match {}", matchId, e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de ação
     * 
     * @param matchId ID da partida
     */
    public void releaseActionLock(Long matchId) {
        String key = DRAFT_ACTION_LOCK_PREFIX + matchId + ":action";

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("🔓 [DraftLock] Action lock liberado: match {}", matchId);
            }

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao liberar action lock de match {}", matchId, e);
        }
    }

    /**
     * ✅ Adquire lock de edição de pick durante confirmação
     * 
     * Durante o modal de confirmação, jogadores podem editar seus picks.
     * Este lock previne múltiplas edições simultâneas.
     * 
     * @param matchId      ID da partida
     * @param summonerName Jogador que está editando
     * @return true se conseguiu adquirir lock, false se outra edição em andamento
     */
    public boolean acquireEditPickLock(Long matchId, String summonerName) {
        String key = DRAFT_ACTION_LOCK_PREFIX + matchId + ":edit_pick";
        String value = summonerName + ":edit";

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, value, ACTION_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [DraftLock] Edit pick lock adquirido: match {} por {}", matchId, summonerName);
                return true;
            }

            // Verificar se é o mesmo jogador editando
            String existing = (String) redisTemplate.opsForValue().get(key);
            if (existing != null && existing.startsWith(summonerName + ":")) {
                // Renovar TTL
                redisTemplate.expire(key, ACTION_LOCK_TTL);
                return true;
            }

            log.warn("⚠️ [DraftLock] Edit pick lock ocupado: match {} (atual: {})", matchId, existing);
            return false;

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao adquirir edit pick lock para match {}", matchId, e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de edição de pick
     * 
     * @param matchId ID da partida
     */
    public void releaseEditPickLock(Long matchId) {
        String key = DRAFT_ACTION_LOCK_PREFIX + matchId + ":edit_pick";

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("🔓 [DraftLock] Edit pick lock liberado: match {}", matchId);
            }

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao liberar edit pick lock de match {}", matchId, e);
        }
    }

    /**
     * ✅ Registra pick editado de um jogador
     * 
     * Thread-safe: Atualiza pick do jogador durante modal de confirmação.
     * 
     * @param matchId      ID da partida
     * @param summonerName Jogador
     * @param championId   Novo campeão escolhido
     * @return true se editado com sucesso
     */
    public boolean updatePlayerPick(Long matchId, String summonerName, int championId) {
        String key = DRAFT_STATE_PREFIX + matchId + ":pick:" + summonerName;
        String lockKey = DRAFT_ACTION_LOCK_PREFIX + matchId + ":update_pick:" + summonerName;

        try {
            // Lock individual para edição
            Boolean lockAcquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "updating", Duration.ofSeconds(5));

            if (Boolean.FALSE.equals(lockAcquired)) {
                log.warn("⚠️ [DraftLock] Jogador {} já está atualizando pick em match {}",
                        summonerName, matchId);
                return false;
            }

            try {
                // Atualizar pick
                redisTemplate.opsForValue().set(key, String.valueOf(championId), TURN_LOCK_TTL);

                log.info("✅ [DraftLock] Pick atualizado: match {} {} escolheu champion {}",
                        matchId, summonerName, championId);
                return true;

            } finally {
                redisTemplate.delete(lockKey);
            }

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao atualizar pick de {} em match {}",
                    summonerName, matchId, e);
            return false;
        }
    }

    /**
     * ✅ Obtém pick atual do jogador
     * 
     * @param matchId      ID da partida
     * @param summonerName Jogador
     * @return ID do campeão ou -1 se não definido
     */
    public int getPlayerPick(Long matchId, String summonerName) {
        String key = DRAFT_STATE_PREFIX + matchId + ":pick:" + summonerName;

        try {
            Object pick = redisTemplate.opsForValue().get(key);
            return pick != null ? Integer.parseInt(pick.toString()) : -1;

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao obter pick de {} em match {}", summonerName, matchId, e);
            return -1;
        }
    }

    /**
     * ✅ Adquire lock de confirmação do draft
     * 
     * Usado quando draft completa e precisa coordenar confirmação dos jogadores.
     * 
     * @param matchId ID da partida
     * @return true se conseguiu adquirir
     */
    public boolean acquireConfirmLock(Long matchId) {
        String key = DRAFT_CONFIRM_LOCK_PREFIX + matchId + ":confirm";

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "confirming", CONFIRM_LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                log.info("🔒 [DraftLock] Confirm lock adquirido: match {}", matchId);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao adquirir confirm lock para match {}", matchId, e);
            return false;
        }
    }

    /**
     * ✅ Libera lock de confirmação
     * 
     * @param matchId ID da partida
     */
    public void releaseConfirmLock(Long matchId) {
        String key = DRAFT_CONFIRM_LOCK_PREFIX + matchId + ":confirm";

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("🔓 [DraftLock] Confirm lock liberado: match {}", matchId);
            }

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao liberar confirm lock de match {}", matchId, e);
        }
    }

    /**
     * ✅ Define jogador do turno atual
     * 
     * @param matchId      ID da partida
     * @param summonerName Jogador do turno
     * @param turnNumber   Número do turno
     */
    public void setCurrentTurnPlayer(Long matchId, String summonerName, int turnNumber) {
        String key = DRAFT_STATE_PREFIX + matchId + ":current_player";

        try {
            redisTemplate.opsForValue().set(key, summonerName, TURN_LOCK_TTL);
            log.debug("✅ [DraftLock] Current player definido: match {} turno {} → {}",
                    matchId, turnNumber, summonerName);

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao definir current player de match {}", matchId, e);
        }
    }

    /**
     * ✅ Obtém jogador do turno atual
     * 
     * @param matchId ID da partida
     * @return Nome do jogador ou null
     */
    public String getCurrentTurnPlayer(Long matchId) {
        String key = DRAFT_STATE_PREFIX + matchId + ":current_player";

        try {
            Object player = redisTemplate.opsForValue().get(key);
            return player != null ? (String) player : null;

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao obter current player de match {}", matchId, e);
            return null;
        }
    }

    /**
     * ✅ Limpa todos os locks do draft
     * 
     * Chamar quando draft for cancelado ou completado.
     * 
     * @param matchId ID da partida
     */
    public void clearAllDraftLocks(Long matchId) {
        try {
            releaseTurnLock(matchId);
            releaseActionLock(matchId);
            releaseEditPickLock(matchId);
            releaseConfirmLock(matchId);

            // Limpar estado do jogador atual
            String playerKey = DRAFT_STATE_PREFIX + matchId + ":current_player";
            redisTemplate.delete(playerKey);

            // Limpar todos os picks
            String pickPattern = DRAFT_STATE_PREFIX + matchId + ":pick:*";
            Set<String> pickKeys = redisTemplate.keys(pickPattern);
            if (pickKeys != null && !pickKeys.isEmpty()) {
                redisTemplate.delete(pickKeys);
            }

            log.info("🗑️ [DraftLock] Todos os locks e picks limpos: match {}", matchId);

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao limpar locks de match {}", matchId, e);
        }
    }

    /**
     * ✅ Verifica se todos os jogadores confirmaram seus picks
     * 
     * @param matchId     ID da partida
     * @param playerNames Lista dos 10 jogadores
     * @return true se todos confirmaram
     */
    public boolean allPlayersConfirmed(Long matchId, java.util.List<String> playerNames) {
        try {
            int confirmedCount = 0;

            for (String playerName : playerNames) {
                String key = DRAFT_STATE_PREFIX + matchId + ":confirmed:" + playerName;
                if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                    confirmedCount++;
                }
            }

            boolean allConfirmed = confirmedCount == playerNames.size();

            log.debug("📊 [DraftLock] Confirmações: {}/{} para match {}",
                    confirmedCount, playerNames.size(), matchId);

            return allConfirmed;

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao verificar confirmações de match {}", matchId, e);
            return false;
        }
    }

    /**
     * ✅ Registra confirmação de pick de um jogador
     * 
     * @param matchId      ID da partida
     * @param summonerName Jogador que confirmou
     * @return true se confirmado com sucesso
     */
    public boolean confirmPlayerPick(Long matchId, String summonerName) {
        String key = DRAFT_STATE_PREFIX + matchId + ":confirmed:" + summonerName;

        try {
            // Verificar se já confirmou
            if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                log.debug("⚠️ [DraftLock] Jogador {} já confirmou pick em match {}",
                        summonerName, matchId);
                return true;
            }

            // Registrar confirmação
            redisTemplate.opsForValue().set(key, "confirmed", CONFIRM_LOCK_TTL);

            log.info("✅ [DraftLock] Pick confirmado: match {} por {}", matchId, summonerName);
            return true;

        } catch (Exception e) {
            log.error("❌ [DraftLock] Erro ao confirmar pick de {} em match {}",
                    summonerName, matchId, e);
            return false;
        }
    }
}
