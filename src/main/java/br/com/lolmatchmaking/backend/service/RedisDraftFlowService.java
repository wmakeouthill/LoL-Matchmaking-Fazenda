package br.com.lolmatchmaking.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * ⚡ Redis service para gerenciar confirmações finais, timers e estados do draft
 * 
 * PROBLEMA SEM REDIS:
 * - ConcurrentHashMaps perdem dados se backend reiniciar
 * - Operações de contagem são lentas (iteração)
 * - Sem TTL automático = memory leaks
 * 
 * SOLUÇÃO COM REDIS:
 * - Operações atômicas (SADD, SCARD, HINCRBY) são instantâneas
 * - Dados persistentes mesmo com reinício do backend
 * - TTL automático de 1 hora
 * - Distributed locks para confirmações
 * 
 * ARQUITETURA:
 * - 1 Backend (Cloud Run)
 * - Múltiplos Electrons (1 por jogador)
 * - Cada Electron envia: POST /api/draft/{matchId}/final-confirm
 * - Backend precisa contar: "Todos os 10 jogadores confirmaram?"
 * 
 * CHAVES REDIS:
 * - draft_flow:{matchId}:final_confirmations → Set<summonerName>
 * - draft_flow:{matchId}:timer → Integer (30 → 0)
 * - draft_flow:{matchId}:state → Hash (JSON serializado do DraftState)
 * 
 * TTL: 1 hora (tempo suficiente para completar qualquer draft)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisDraftFlowService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "draft_flow:";
    private static final long TTL_SECONDS = 3600; // 1 hora

    /**
     * ✅ NOVO: Retorna um distributed lock para operações atômicas
     * Usado por DraftFlowService.processAction() para prevenir ações simultâneas
     */
    public RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    // ========================================
    // CONFIRMAÇÕES FINAIS (Modal de confirmação)
    // ========================================

    /**
     * Registra confirmação final de um jogador
     * 
     * @param matchId      ID da partida
     * @param summonerName Nome do invocador que confirmou
     * @return true se confirmação foi registrada com sucesso
     */
    public boolean confirmFinalDraft(Long matchId, String summonerName) {
        String lockKey = "draft_flow_confirm_lock:" + matchId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // Tentar adquirir lock por 10 segundos (timeout), segurar por 5 segundos
            // (lease)
            if (!lock.tryLock(10, 5, TimeUnit.SECONDS)) {
                log.warn("⚠️ [RedisDraftFlow] Não foi possível adquirir lock para matchId={}", matchId);
                return false;
            }

            String key = KEY_PREFIX + matchId + ":final_confirmations";

            // Adicionar ao Set (operação atômica do Redis)
            Long result = redisTemplate.opsForSet().add(key, summonerName.toLowerCase().trim());

            // Configurar TTL
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);

            if (result != null && result > 0) {
                log.info("✅ [RedisDraftFlow] Confirmação registrada: matchId={}, summoner={}",
                        matchId, summonerName);
                return true;
            } else {
                log.debug("🔄 [RedisDraftFlow] Confirmação duplicada: matchId={}, summoner={}",
                        matchId, summonerName);
                return true; // Duplicada, mas OK
            }

        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao confirmar draft: matchId={}, summoner={}",
                    matchId, summonerName, e);
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Verifica se todos os jogadores confirmaram (SUPER RÁPIDO)
     * 
     * @param matchId      ID da partida
     * @param totalPlayers Total de jogadores esperados (10)
     * @return true se todos confirmaram
     */
    public boolean allPlayersConfirmedFinal(Long matchId, int totalPlayers) {
        try {
            String key = KEY_PREFIX + matchId + ":final_confirmations";

            // SCARD = O(1) - INSTANTÂNEO no Redis
            Long count = redisTemplate.opsForSet().size(key);

            boolean allConfirmed = count != null && count >= totalPlayers;

            if (allConfirmed) {
                log.info("🎉 [RedisDraftFlow] TODOS confirmaram! matchId={}, count={}/{}",
                        matchId, count, totalPlayers);
            } else {
                log.debug("⏳ [RedisDraftFlow] Aguardando confirmações: matchId={}, count={}/{}",
                        matchId, count, totalPlayers);
            }

            return allConfirmed;

        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao verificar confirmações: matchId={}", matchId, e);
            return false;
        }
    }

    /**
     * Obtém lista de jogadores que já confirmaram
     * 
     * @param matchId ID da partida
     * @return Set com summonerNames que confirmaram
     */
    public Set<String> getConfirmedPlayers(Long matchId) {
        try {
            String key = KEY_PREFIX + matchId + ":final_confirmations";
            Set<Object> members = redisTemplate.opsForSet().members(key);

            if (members == null) {
                return Set.of();
            }

            Set<String> confirmed = new HashSet<>();
            for (Object member : members) {
                if (member instanceof String) {
                    confirmed.add((String) member);
                }
            }

            log.debug("📋 [RedisDraftFlow] Confirmados: matchId={}, count={}, players={}",
                    matchId, confirmed.size(), confirmed);

            return confirmed;

        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao obter confirmados: matchId={}", matchId, e);
            return Set.of();
        }
    }

    /**
     * Limpa todas as confirmações de uma partida
     * 
     * @param matchId ID da partida
     */
    public void clearFinalConfirmations(Long matchId) {
        try {
            String key = KEY_PREFIX + matchId + ":final_confirmations";
            redisTemplate.delete(key);
            log.info("🗑️ [RedisDraftFlow] Confirmações limpas: matchId={}", matchId);
        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao limpar confirmações: matchId={}", matchId, e);
        }
    }

    // ========================================
    // TIMER DO DRAFT (30 → 0 segundos)
    // ========================================

    /**
     * Inicializa o timer do draft (30 segundos)
     * 
     * @param matchId ID da partida
     */
    public void initTimer(Long matchId) {
        try {
            String key = KEY_PREFIX + matchId + ":timer";
            redisTemplate.opsForValue().set(key, 30);
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
            log.info("⏰ [RedisDraftFlow] Timer inicializado: matchId={}, timer=30s", matchId);
        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao inicializar timer: matchId={}", matchId, e);
        }
    }

    /**
     * Atualiza o timer (decremento ou reset)
     * 
     * @param matchId ID da partida
     * @param seconds Novo valor do timer
     */
    public void updateTimer(Long matchId, int seconds) {
        try {
            String key = KEY_PREFIX + matchId + ":timer";
            redisTemplate.opsForValue().set(key, seconds);
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("⏱️ [RedisDraftFlow] Timer atualizado: matchId={}, timer={}s", matchId, seconds);
        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao atualizar timer: matchId={}", matchId, e);
        }
    }

    /**
     * Obtém o timer atual
     * 
     * @param matchId ID da partida
     * @return Segundos restantes (default: 30)
     */
    public int getTimer(Long matchId) {
        try {
            String key = KEY_PREFIX + matchId + ":timer";
            Object value = redisTemplate.opsForValue().get(key);

            if (value instanceof Integer) {
                return (Integer) value;
            } else if (value instanceof Number) {
                return ((Number) value).intValue();
            }

            return 30; // Default

        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao obter timer: matchId={}", matchId, e);
            return 30;
        }
    }

    /**
     * Decrementa o timer (thread-safe)
     * 
     * @param matchId ID da partida
     * @return Novo valor do timer
     */
    public int decrementTimer(Long matchId) {
        try {
            String key = KEY_PREFIX + matchId + ":timer";

            // DECR é operação atômica do Redis
            Long newValue = redisTemplate.opsForValue().decrement(key);

            if (newValue == null) {
                return 0;
            }

            // Garantir que não fica negativo
            int timer = Math.max(0, newValue.intValue());

            if (timer == 0) {
                log.warn("⏰ [RedisDraftFlow] Timer ZEROU: matchId={}", matchId);
            }

            return timer;

        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao decrementar timer: matchId={}", matchId, e);
            return 0;
        }
    }

    /**
     * Reseta o timer para 30 segundos (após cada ação)
     * 
     * @param matchId ID da partida
     */
    public void resetTimer(Long matchId) {
        updateTimer(matchId, 30);
        log.info("🔄 [RedisDraftFlow] Timer RESETADO: matchId={}, timer=30s", matchId);
    }

    // ========================================
    // ESTADO DO DRAFT (Serialização completa)
    // ========================================

    /**
     * Salva o estado completo do draft no Redis
     * 
     * @param matchId ID da partida
     * @param state   Dados do estado
     */
    /**
     * ✅ REFATORADO: Salva JSON puro do MySQL direto no Redis (ZERO conversões!)
     * Este é o método preferencial - armazena EXATAMENTE o que está no MySQL
     */
    public void saveDraftStateJson(Long matchId, String pickBanDataJson) {
        try {
            String key = KEY_PREFIX + matchId + ":state";

            // Salvar STRING JSON DIRETAMENTE (sem parse/serialize!)
            redisTemplate.opsForValue().set(key, pickBanDataJson);
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);

            log.debug("💾 [RedisDraftFlow] JSON puro MySQL→Redis: matchId={}, {}bytes",
                    matchId, pickBanDataJson.length());

        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao salvar JSON puro: matchId={}", matchId, e);
        }
    }

    /**
     * ⚠️ DEPRECATED: Usar saveDraftStateJson() ao invés
     */
    @Deprecated
    public void saveDraftState(Long matchId, Map<String, Object> state) {
        try {
            String key = KEY_PREFIX + matchId + ":state";

            // Serializar para JSON
            String json = objectMapper.writeValueAsString(state);

            redisTemplate.opsForValue().set(key, json);
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);

            log.debug("💾 [RedisDraftFlow] Estado salvo: matchId={}, size={}bytes",
                    matchId, json.length());

        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao salvar estado: matchId={}", matchId, e);
        }
    }

    /**
     * ✅ REFATORADO: Retorna JSON puro do Redis (ZERO conversões!)
     * Retorna EXATAMENTE o que está armazenado (mesmo formato do MySQL)
     */
    public String getDraftStateJson(Long matchId) {
        try {
            String key = KEY_PREFIX + matchId + ":state";
            Object value = redisTemplate.opsForValue().get(key);

            if (value == null) {
                log.debug("📭 [RedisDraftFlow] JSON não encontrado no Redis: matchId={}", matchId);
                return null;
            }

            if (value instanceof String) {
                String json = (String) value;
                log.debug("📥 [RedisDraftFlow] JSON puro recuperado: matchId={}, {}bytes",
                        matchId, json.length());
                return json;
            }

            log.warn("⚠️ [RedisDraftFlow] Formato inválido: matchId={}, type={}",
                    matchId, value.getClass().getSimpleName());
            return null;

        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao recuperar JSON: matchId={}", matchId, e);
            return null;
        }
    }

    /**
     * Recupera o estado completo do draft
     * 
     * @param matchId ID da partida
     * @return Estado do draft ou null se não existe
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDraftState(Long matchId) {
        try {
            String key = KEY_PREFIX + matchId + ":state";
            Object value = redisTemplate.opsForValue().get(key);

            if (value == null) {
                log.debug("📭 [RedisDraftFlow] Estado não encontrado: matchId={}", matchId);
                return null;
            }

            if (value instanceof String) {
                // Desserializar JSON
                Map<String, Object> state = objectMapper.readValue((String) value, Map.class);
                log.debug("📥 [RedisDraftFlow] Estado recuperado: matchId={}", matchId);
                return state;
            }

            log.warn("⚠️ [RedisDraftFlow] Formato inválido de estado: matchId={}", matchId);
            return null;

        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao recuperar estado: matchId={}", matchId, e);
            return null;
        }
    }

    /**
     * Remove o estado do draft
     * 
     * @param matchId ID da partida
     */
    public void clearDraftState(Long matchId) {
        try {
            String key = KEY_PREFIX + matchId + ":state";
            redisTemplate.delete(key);
            log.info("🗑️ [RedisDraftFlow] Estado removido: matchId={}", matchId);
        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao remover estado: matchId={}", matchId, e);
        }
    }

    /**
     * Verifica se existe draft ativo no Redis
     * 
     * @param matchId ID da partida
     * @return true se draft existe
     */
    public boolean draftExists(Long matchId) {
        try {
            String key = KEY_PREFIX + matchId + ":state";
            Boolean hasKey = redisTemplate.hasKey(key);
            return hasKey != null && hasKey;
        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao verificar existência: matchId={}", matchId, e);
            return false;
        }
    }

    // ========================================
    // LIMPEZA COMPLETA (ao finalizar draft)
    // ========================================

    /**
     * Remove TODOS os dados do draft flow (confirmações + timer + estado)
     * Chamado quando draft é completado ou cancelado
     * 
     * @param matchId ID da partida
     */
    public void clearAllDraftData(Long matchId) {
        try {
            List<String> keys = List.of(
                    KEY_PREFIX + matchId + ":final_confirmations",
                    KEY_PREFIX + matchId + ":timer",
                    KEY_PREFIX + matchId + ":state");

            Long deleted = redisTemplate.delete(keys);
            log.info("🗑️ [RedisDraftFlow] Dados limpos: matchId={}, keys deletadas={}",
                    matchId, deleted);

        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao limpar dados: matchId={}", matchId, e);
        }
    }

    // ========================================
    // MÉTRICAS E DEBUGGING
    // ========================================

    /**
     * Obtém métricas do draft para debugging
     * 
     * @param matchId ID da partida
     * @return Mapa com métricas
     */
    public Map<String, Object> getDraftMetrics(Long matchId) {
        try {
            Map<String, Object> metrics = new HashMap<>();

            // Confirmações
            String confirmKey = KEY_PREFIX + matchId + ":final_confirmations";
            Long confirmCount = redisTemplate.opsForSet().size(confirmKey);
            Set<Object> confirmed = redisTemplate.opsForSet().members(confirmKey);

            // Timer
            int timer = getTimer(matchId);

            // Estado
            boolean stateExists = draftExists(matchId);

            metrics.put("matchId", matchId);
            metrics.put("confirmations", confirmCount);
            metrics.put("confirmedPlayers", confirmed);
            metrics.put("timer", timer);
            metrics.put("stateExists", stateExists);

            return metrics;

        } catch (Exception e) {
            log.error("❌ [RedisDraftFlow] Erro ao obter métricas: matchId={}", matchId, e);
            return Map.of("error", e.getMessage());
        }
    }
}
