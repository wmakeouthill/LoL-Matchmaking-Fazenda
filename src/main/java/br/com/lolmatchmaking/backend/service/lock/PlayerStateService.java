package br.com.lolmatchmaking.backend.service.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * ✅ Service de Gerenciamento de Estado do Jogador
 * 
 * PROBLEMA RESOLVIDO:
 * - Jogador em múltiplos estados simultaneamente
 * - Jogador em partida sendo selecionado para nova partida
 * - Dados se perdendo durante transições de estado
 * - Race conditions em mudanças de estado
 * 
 * SOLUÇÃO:
 * - Estado centralizado no Redis
 * - Validação de transições de estado
 * - Lock durante mudança de estado (thread-safe)
 * - Estados bem definidos: AVAILABLE, IN_QUEUE, IN_MATCH_FOUND, IN_DRAFT,
 * IN_GAME
 * 
 * FLUXO DE ESTADOS:
 * AVAILABLE → IN_QUEUE → IN_MATCH_FOUND → IN_DRAFT → IN_GAME → AVAILABLE
 * 
 * CHAVES REDIS:
 * - state:player:{summonerName} → PlayerState atual
 * - lock:state:{summonerName} → Lock temporário para mudança de estado
 * 
 * REFERÊNCIA:
 * -
 * ARQUITETURA-CORRETA-SINCRONIZACAO.md#3-lock-de-estado-do-jogador-player-state-lock
 * - TODO-CORRECOES-PRODUCAO.md#13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerStateService {

    private final RedisTemplate<String, Object> redisTemplate;

    // Prefixos de chave
    private static final String STATE_PREFIX = "state:player:";
    private static final String STATE_LOCK_PREFIX = "lock:state:";

    // ✅ CORRIGIDO: TTL de 4 horas (ALINHADO com PlayerLock)
    // CRÍTICO: Deve ser >= PlayerLock TTL para evitar estado expirar antes da
    // sessão
    // Player pode ficar conectado por horas (draft longo + game longo)
    private static final Duration STATE_TTL = Duration.ofHours(4);

    // TTL do lock de mudança de estado: 5 segundos (tempo para processar mudança)
    private static final Duration STATE_LOCK_TTL = Duration.ofSeconds(5);

    /**
     * ✅ Atualiza estado do jogador (thread-safe com lock)
     * 
     * @param summonerName Nome do jogador
     * @param newState     Novo estado
     * @return true se conseguiu atualizar, false se falhou (transição inválida ou
     *         lock não adquirido)
     * 
     *         Exemplo de uso:
     * 
     *         <pre>
     *         if (playerStateService.setPlayerState(summonerName, PlayerState.IN_QUEUE)) {
     *             // Estado atualizado com sucesso
     *             addToQueue(summonerName);
     *         } else {
     *             // Falha ao atualizar estado (jogador já está em partida?)
     *             log.warn("Não foi possível adicionar jogador à fila");
     *         }
     *         </pre>
     */
    public boolean setPlayerState(String summonerName, PlayerState newState) {
        String stateKey = STATE_PREFIX + summonerName;
        String lockKey = STATE_LOCK_PREFIX + summonerName;

        // ✅ LOCK: Apenas uma thread pode mudar estado por vez
        Boolean lockAcquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "locked", STATE_LOCK_TTL);

        if (Boolean.FALSE.equals(lockAcquired)) {
            log.warn("⚠️ [PlayerState] Falha ao adquirir lock de estado para {}", summonerName);
            return false;
        }

        try {
            // Obter estado atual
            PlayerState currentState = getPlayerState(summonerName);

            // ✅ VALIDAR TRANSIÇÃO DE ESTADO
            if (!isValidTransition(currentState, newState)) {
                log.error("❌ [PlayerState] Transição de estado inválida: {} → {} para {}",
                        currentState, newState, summonerName);
                return false;
            }

            // ✅ ATUALIZAR ESTADO
            redisTemplate.opsForValue().set(stateKey, newState.name(), STATE_TTL);

            log.info("✅ [PlayerState] Estado atualizado: {} → {} ({})",
                    summonerName, currentState, newState);
            return true;

        } catch (Exception e) {
            log.error("❌ [PlayerState] Erro ao atualizar estado de {}", summonerName, e);
            return false;

        } finally {
            // ✅ SEMPRE LIBERAR LOCK
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * ✅ Obtém estado atual do jogador
     * 
     * @param summonerName Nome do jogador
     * @return Estado atual (AVAILABLE se não há estado definido)
     */
    public PlayerState getPlayerState(String summonerName) {
        String key = STATE_PREFIX + summonerName;

        try {
            Object stateObj = redisTemplate.opsForValue().get(key);

            if (stateObj == null) {
                return PlayerState.AVAILABLE; // Estado padrão
            }

            String stateName = (String) stateObj;
            return PlayerState.valueOf(stateName);

        } catch (IllegalArgumentException e) {
            log.warn("⚠️ [PlayerState] Estado inválido para {}, retornando AVAILABLE", summonerName);
            return PlayerState.AVAILABLE;

        } catch (Exception e) {
            log.error("❌ [PlayerState] Erro ao obter estado de {}", summonerName, e);
            return PlayerState.AVAILABLE;
        }
    }

    /**
     * ✅ Verifica se jogador pode entrar na fila
     * 
     * @param summonerName Nome do jogador
     * @return true se está AVAILABLE, false caso contrário
     */
    public boolean canJoinQueue(String summonerName) {
        PlayerState state = getPlayerState(summonerName);
        return state.canJoinQueue();
    }

    /**
     * ✅ Verifica se jogador está em partida
     * 
     * @param summonerName Nome do jogador
     * @return true se está em IN_MATCH_FOUND, IN_DRAFT ou IN_GAME
     */
    public boolean isInMatch(String summonerName) {
        PlayerState state = getPlayerState(summonerName);
        return state.isInMatch();
    }

    /**
     * ✅ Valida se transição de estado é permitida
     * 
     * Fluxo permitido:
     * - AVAILABLE → IN_QUEUE
     * - IN_QUEUE → IN_MATCH_FOUND ou AVAILABLE (saiu da fila)
     * - IN_MATCH_FOUND → IN_DRAFT ou AVAILABLE (recusou)
     * - IN_DRAFT → IN_GAME ou AVAILABLE (cancelou)
     * - IN_GAME → AVAILABLE (terminou jogo)
     * 
     * @param from Estado atual
     * @param to   Novo estado
     * @return true se transição é válida, false caso contrário
     */
    private boolean isValidTransition(PlayerState from, PlayerState to) {
        if (from == null) {
            from = PlayerState.AVAILABLE;
        }

        // Permitir transição para o mesmo estado (idempotência)
        if (from == to) {
            return true;
        }

        switch (from) {
            case AVAILABLE:
                // De AVAILABLE só pode ir para IN_QUEUE
                return to == PlayerState.IN_QUEUE;

            case IN_QUEUE:
                // De IN_QUEUE pode ir para IN_MATCH_FOUND (partida encontrada) ou AVAILABLE
                // (saiu da fila)
                return to == PlayerState.IN_MATCH_FOUND || to == PlayerState.AVAILABLE;

            case IN_MATCH_FOUND:
                // De IN_MATCH_FOUND pode ir para IN_DRAFT (aceitou) ou AVAILABLE
                // (recusou/timeout)
                return to == PlayerState.IN_DRAFT || to == PlayerState.AVAILABLE;

            case IN_DRAFT:
                // De IN_DRAFT pode ir para IN_GAME (draft completo) ou AVAILABLE (cancelou)
                return to == PlayerState.IN_GAME || to == PlayerState.AVAILABLE;

            case IN_GAME:
                // De IN_GAME só pode voltar para AVAILABLE (jogo terminou)
                return to == PlayerState.AVAILABLE;

            default:
                log.error("❌ [PlayerState] Estado desconhecido: {}", from);
                return false;
        }
    }

    /**
     * ✅ Força mudança de estado sem validação (usar apenas em emergências)
     * 
     * ⚠️ ATENÇÃO: Este método bypassa a validação de transições.
     * Use apenas para corrigir estados inconsistentes.
     * 
     * @param summonerName Nome do jogador
     * @param newState     Novo estado
     * @return true se conseguiu forçar mudança, false caso contrário
     */
    public boolean forceSetPlayerState(String summonerName, PlayerState newState) {
        String key = STATE_PREFIX + summonerName;

        try {
            redisTemplate.opsForValue().set(key, newState.name(), STATE_TTL);
            log.warn("⚠️ [PlayerState] Estado FORÇADAMENTE alterado para {}: {}",
                    summonerName, newState);
            return true;

        } catch (Exception e) {
            log.error("❌ [PlayerState] Erro ao forçar estado de {}", summonerName, e);
            return false;
        }
    }

    /**
     * ✅ Remove estado do jogador (volta para AVAILABLE)
     * 
     * @param summonerName Nome do jogador
     */
    public void clearPlayerState(String summonerName) {
        String key = STATE_PREFIX + summonerName;

        try {
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("🗑️ [PlayerState] Estado removido para {}", summonerName);
            }

        } catch (Exception e) {
            log.error("❌ [PlayerState] Erro ao remover estado de {}", summonerName, e);
        }
    }

    /**
     * ✅ Renova TTL do estado do jogador
     * 
     * Útil para manter estado ativo durante sessão longa.
     * 
     * @param summonerName Nome do jogador
     * @return true se conseguiu renovar, false caso contrário
     */
    public boolean renewPlayerState(String summonerName) {
        String key = STATE_PREFIX + summonerName;

        try {
            Boolean renewed = redisTemplate.expire(key, STATE_TTL);

            if (Boolean.TRUE.equals(renewed)) {
                log.debug("♻️ [PlayerState] TTL renovado para {}", summonerName);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("❌ [PlayerState] Erro ao renovar estado de {}", summonerName, e);
            return false;
        }
    }

    /**
     * ✅ Obtém TTL do estado do jogador (em segundos)
     * 
     * @param summonerName Nome do jogador
     * @return TTL em segundos, ou -1 se não há estado
     */
    public long getPlayerStateTtl(String summonerName) {
        String key = STATE_PREFIX + summonerName;

        try {
            Long ttl = redisTemplate.getExpire(key);
            return ttl != null ? ttl : -1;

        } catch (Exception e) {
            log.error("❌ [PlayerState] Erro ao obter TTL de {}", summonerName, e);
            return -1;
        }
    }

    /**
     * ✅ Realiza transição de estado com retry automático
     * 
     * Tenta múltiplas vezes se falhar por lock ocupado.
     * 
     * @param summonerName Nome do jogador
     * @param newState     Novo estado
     * @param maxRetries   Número máximo de tentativas
     * @param retryDelayMs Delay entre tentativas (ms)
     * @return true se conseguiu, false se falhou após todas as tentativas
     */
    public boolean setPlayerStateWithRetry(String summonerName, PlayerState newState,
            int maxRetries, long retryDelayMs) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (setPlayerState(summonerName, newState)) {
                return true;
            }

            if (attempt < maxRetries) {
                log.debug("⏳ [PlayerState] Tentativa {}/{} falhou, aguardando {}ms",
                        attempt, maxRetries, retryDelayMs);
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        log.error("❌ [PlayerState] Falha após {} tentativas para {}: {}",
                maxRetries, summonerName, newState);
        return false;
    }
}
