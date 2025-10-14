package br.com.lolmatchmaking.backend.service.redis;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;
import br.com.lolmatchmaking.backend.domain.repository.CustomMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * ⚡ Redis service para mapear jogadores → partidas ativas
 * 
 * PROBLEMA RESOLVIDO:
 * - Jogadores vendo drafts de outras partidas
 * - Falta de validação de ownership no WebSocket
 * - Frontend com matchId errado/desatualizado
 * 
 * SOLUÇÃO:
 * - Armazena player:current_match:{summonerName} → matchId
 * - Valida ownership antes de permitir ações
 * - TTL de 2 horas (duração máxima de uma partida)
 * 
 * CHAVES REDIS:
 * - player:current_match:{summonerName} → Long matchId
 * - match:players:{matchId} → Set<String> summonerNames
 * 
 * FLUXO:
 * 1. match_found → registerPlayerMatch(summonerName, matchId)
 * 2. draft_action → validateOwnership(summonerName, matchId)
 * 3. game_finished → clearPlayerMatch(summonerName)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisPlayerMatchService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CustomMatchRepository customMatchRepository;

    private static final String PLAYER_MATCH_PREFIX = "player:current_match:";
    private static final String MATCH_PLAYERS_PREFIX = "match:players:";
    private static final String PUUID_TO_PLAYER_PREFIX = "ws:puuid:to:player:";
    // ✅ CORRIGIDO: TTL de 1h30min (duração máxima de partida + margem)
    // CRÍTICO: Ownership não deve persistir além da duração real
    // Partida típica: 30-60min, margem 30min = 1h30min total
    // Cleanup explícito prioritário em finish/cancel
    private static final Duration TTL = Duration.ofMinutes(90);

    /**
     * ✅ Registra que um jogador está em uma partida específica
     * 
     * @param summonerName Nome do jogador (normalizado)
     * @param matchId      ID da partida
     */
    public void registerPlayerMatch(String summonerName, Long matchId) {
        try {
            String normalizedName = normalizePlayerName(summonerName);

            // ✅ VALIDAÇÃO MySQL: Verificar se a partida existe e está ativa
            Optional<CustomMatch> matchOpt = customMatchRepository.findById(matchId);
            if (matchOpt.isEmpty()) {
                log.error(
                        "🚨 [RedisPlayerMatch] Tentativa de registrar player {} em match {} que NÃO EXISTE no MySQL! BLOQUEADO!",
                        normalizedName, matchId);
                return;
            }

            CustomMatch match = matchOpt.get();
            String status = match.getStatus();
            boolean isActiveMatch = "match_found".equalsIgnoreCase(status) ||
                    "accepting".equalsIgnoreCase(status) ||
                    "accepted".equalsIgnoreCase(status) ||
                    "pending".equalsIgnoreCase(status) ||
                    "draft".equalsIgnoreCase(status) ||
                    "in_progress".equalsIgnoreCase(status);

            if (!isActiveMatch) {
                log.error(
                        "🚨 [RedisPlayerMatch] Tentativa de registrar player {} em match {} com status INVÁLIDO ({})! BLOQUEADO!",
                        normalizedName, matchId, status);
                return;
            }

            // ✅ Tudo OK, pode registrar no Redis
            String playerKey = PLAYER_MATCH_PREFIX + normalizedName;
            redisTemplate.opsForValue().set(playerKey, matchId, TTL);
            String matchKey = MATCH_PLAYERS_PREFIX + matchId;
            redisTemplate.opsForSet().add(matchKey, normalizedName);
            redisTemplate.expire(matchKey, TTL);
            log.info("✅ [RedisPlayerMatch] Registrado: {} → match {} (validado no MySQL: status={})",
                    normalizedName, matchId, status);
        } catch (Exception e) {
            log.error("❌ [RedisPlayerMatch] Erro ao registrar player: {}", summonerName, e);
        }
    }

    /**
     * ✅ Valida se um jogador pertence a uma partida específica
     * 
     * @param summonerName Nome do jogador
     * @param matchId      ID da partida
     * @return true se o jogador pertence à partida, false caso contrário
     */
    
    /**
     * ✅ NOVO: Valida constraint PUUID único
     */
    public boolean validatePuuidConstraint(String summonerName, String puuid) {
        try {
            String puuidKey = PUUID_TO_PLAYER_PREFIX + puuid;
            String existingPlayer = (String) redisTemplate.opsForValue().get(puuidKey);
            
            if (existingPlayer == null) {
                // PUUID não está vinculado a nenhum jogador
                return true;
            }
            
            if (existingPlayer.equals(summonerName.toLowerCase())) {
                // PUUID já está vinculado ao mesmo jogador
                return true;
            }
            
            // 🚨 CONFLITO: PUUID já vinculado a outro jogador
            log.error("🚨 [RedisPlayerMatch] PUUID CONFLITO! PUUID {} já vinculado a {} mas tentativa de vincular a {}", 
                puuid, existingPlayer, summonerName);
            
            return false;
            
        } catch (Exception e) {
            log.error("❌ [RedisPlayerMatch] Erro ao validar constraint PUUID", e);
            return false;
        }
    }

    /**
     * ✅ NOVO: Registra vinculação PUUID → summonerName
     */
    public void registerPuuidConstraint(String summonerName, String puuid) {
        try {
            String puuidKey = PUUID_TO_PLAYER_PREFIX + puuid;
            String normalizedName = summonerName.toLowerCase().trim();
            
            redisTemplate.opsForValue().set(puuidKey, normalizedName, TTL);
            log.debug("✅ [RedisPlayerMatch] PUUID constraint registrado: {} → {}", puuid, normalizedName);
            
        } catch (Exception e) {
            log.error("❌ [RedisPlayerMatch] Erro ao registrar PUUID constraint", e);
        }
    }

    /**
     * ✅ NOVO: Remove vinculação PUUID → summonerName
     */
    public void clearPuuidConstraint(String puuid) {
        try {
            String puuidKey = PUUID_TO_PLAYER_PREFIX + puuid;
            redisTemplate.delete(puuidKey);
            log.debug("✅ [RedisPlayerMatch] PUUID constraint removido: {}", puuid);
            
        } catch (Exception e) {
            log.error("❌ [RedisPlayerMatch] Erro ao remover PUUID constraint", e);
        }
    }

    /**
     * ✅ NOVO: Validação de ownership com PUUID (mais rigorosa)
     */
    public boolean validateOwnership(String summonerName, Long matchId, String puuid) {
        try {
            // 1. Validar constraint PUUID primeiro
            if (puuid != null && !puuid.isEmpty()) {
                boolean puuidValid = validatePuuidConstraint(summonerName, puuid);
                if (!puuidValid) {
                    log.error("❌ [RedisPlayerMatch] PUUID inválido para ownership: {} → {}", summonerName, puuid);
                    return false;
                }
            }
            
            // 2. Validar ownership normal
            return validateOwnership(summonerName, matchId);
            
        } catch (Exception e) {
            log.error("❌ [RedisPlayerMatch] Erro ao validar ownership com PUUID", e);
            return false;
        }
    }

    public boolean validateOwnership(String summonerName, Long matchId) {
        try {
            String normalizedName = normalizePlayerName(summonerName);

            // 1. Buscar matchId atual do jogador no Redis
            String playerKey = PLAYER_MATCH_PREFIX + normalizedName;
            Object currentMatchIdObj = redisTemplate.opsForValue().get(playerKey);

            if (currentMatchIdObj == null) {
                // ✅ CLEANUP INTELIGENTE: Redis não tem, mas MySQL pode ter!
                // Verificar MySQL (fonte da verdade)
                boolean inMySQLMatch = customMatchRepository.findById(matchId)
                        .map(match -> {
                            String status = match.getStatus();
                            // ✅ Verificar se partida está em status ativo
                            boolean isActive = "match_found".equalsIgnoreCase(status) ||
                                    "accepting".equalsIgnoreCase(status) ||
                                    "accepted".equalsIgnoreCase(status) ||
                                    "pending".equalsIgnoreCase(status) ||
                                    "draft".equalsIgnoreCase(status) ||
                                    "in_progress".equalsIgnoreCase(status);

                            if (!isActive) {
                                return false; // Match finalizada ou cancelada
                            }

                            // ✅ CORREÇÃO: Verificar com CASE-INSENSITIVE
                            // MySQL pode ter "FZD Ratoso#fzd" mas summonerName pode estar normalizado
                            String team1 = match.getTeam1PlayersJson();
                            String team2 = match.getTeam2PlayersJson();
                            boolean inTeam1 = team1 != null &&
                                    team1.toLowerCase().contains(summonerName.toLowerCase());
                            boolean inTeam2 = team2 != null &&
                                    team2.toLowerCase().contains(summonerName.toLowerCase());
                            return inTeam1 || inTeam2;
                        })
                        .orElse(false);

                if (inMySQLMatch) {
                    log.warn(
                            "🧹 [validateOwnership] ESTADO FANTASMA: {} está na match {} (MySQL) mas NÃO tem ownership no Redis!",
                            normalizedName, matchId);
                    log.warn("🧹 [validateOwnership] Recriando ownership no Redis...");
                    registerPlayerMatch(summonerName, matchId);
                    log.info("✅ [validateOwnership] Ownership recriado, validação OK");
                    return true;
                }

                log.warn("⚠️ [validateOwnership] Jogador {} não tem partida ativa no Redis nem no MySQL",
                        normalizedName);
                return false;
            }

            Long currentMatchId = Long.valueOf(currentMatchIdObj.toString());

            // 2. Verificar se o matchId corresponde
            if (!currentMatchId.equals(matchId)) {
                log.warn(
                        "⚠️ [validateOwnership] TENTATIVA DE ACESSO INDEVIDO! Jogador {} tentou acessar match {}, mas está em match {}",
                        normalizedName, matchId, currentMatchId);
                return false;
            }

            // ✅ VALIDAÇÃO MySQL: Redis diz que tem ownership, confirmar no MySQL
            boolean validInMySQL = customMatchRepository.findById(matchId)
                    .map(match -> {
                        String status = match.getStatus();
                        if (!"pending".equals(status) && !"draft".equals(status) && !"in_progress".equals(status)) {
                            log.warn(
                                    "🧹 [validateOwnership] Match {} está finalizada no MySQL (status={}), ownership Redis deve ser limpo!",
                                    matchId, status);
                            return false;
                        }

                        // ✅ CORREÇÃO: Verificar com CASE-INSENSITIVE
                        // MySQL pode ter "FZD Ratoso#fzd" mas summonerName pode estar normalizado
                        String team1 = match.getTeam1PlayersJson();
                        String team2 = match.getTeam2PlayersJson();
                        boolean inTeam1 = team1 != null &&
                                team1.toLowerCase().contains(summonerName.toLowerCase());
                        boolean inTeam2 = team2 != null &&
                                team2.toLowerCase().contains(summonerName.toLowerCase());
                        boolean isInMatch = inTeam1 || inTeam2;

                        if (!isInMatch) {
                            log.warn(
                                    "🧹 [validateOwnership] Jogador {} NÃO está na match {} no MySQL, ownership Redis deve ser limpo!",
                                    normalizedName, matchId);
                            log.warn("🔍 [validateOwnership] DEBUG: team1={}, team2={}, summonerName={}",
                                    team1, team2, summonerName);
                        }

                        return isInMatch;
                    })
                    .orElse(false);

            if (!validInMySQL) {
                log.warn("🧹 [validateOwnership] Ownership inválido no MySQL, limpando Redis...");
                clearPlayerMatch(summonerName); // Já valida MySQL antes de limpar
                return false;
            }

            log.debug("✅ [validateOwnership] Ownership validado: {} → match {} (Redis + MySQL OK)", normalizedName,
                    matchId);
            return true;

        } catch (Exception e) {
            log.error("❌ [validateOwnership] Erro ao validar ownership: summonerName={}, matchId={}",
                    summonerName, matchId, e);
            return false;
        }
    }

    /**
     * ✅ Busca o matchId atual de um jogador
     * 
     * @param summonerName Nome do jogador
     * @return matchId atual ou null se não estiver em partida
     */
    public Long getCurrentMatch(String summonerName) {
        try {
            String normalizedName = normalizePlayerName(summonerName);
            String playerKey = PLAYER_MATCH_PREFIX + normalizedName;
            Object matchIdObj = redisTemplate.opsForValue().get(playerKey);

            if (matchIdObj == null) {
                return null;
            }

            return Long.valueOf(matchIdObj.toString());

        } catch (Exception e) {
            log.error("❌ [RedisPlayerMatch] Erro ao buscar currentMatch: {}", summonerName, e);
            return null;
        }
    }

    /**
     * ✅ Busca todos os jogadores de uma partida
     * 
     * @param matchId ID da partida
     * @return Set de summonerNames
     */
    public Set<String> getMatchPlayers(Long matchId) {
        try {
            String matchKey = MATCH_PLAYERS_PREFIX + matchId;
            Set<Object> playersObj = redisTemplate.opsForSet().members(matchKey);

            if (playersObj == null) {
                return new HashSet<>();
            }

            Set<String> players = new HashSet<>();
            for (Object playerObj : playersObj) {
                players.add(playerObj.toString());
            }

            return players;

        } catch (Exception e) {
            log.error("❌ [RedisPlayerMatch] Erro ao buscar players de match {}", matchId, e);
            return new HashSet<>();
        }
    }

    /**
     * ✅ Remove o registro de um jogador (quando sai da partida)
     * 
     * @param summonerName Nome do jogador
     */
    public void clearPlayerMatch(String summonerName) {
        try {
            String normalizedName = normalizePlayerName(summonerName);
            Long matchId = getCurrentMatch(normalizedName);

            if (matchId == null) {
                log.debug("✅ [RedisPlayerMatch] Nada a limpar: {} não tem match no Redis", normalizedName);
                return;
            }

            // ✅ VALIDAÇÃO MySQL: Verificar se a partida está finalizada/cancelada antes de
            // limpar
            Optional<CustomMatch> matchOpt = customMatchRepository.findById(matchId);

            if (matchOpt.isEmpty()) {
                // ✅ Match não existe no MySQL, pode limpar Redis
                log.warn("🧹 [RedisPlayerMatch] Match {} não existe no MySQL, limpando Redis para {}",
                        matchId, normalizedName);
                String playerKey = PLAYER_MATCH_PREFIX + normalizedName;
                redisTemplate.delete(playerKey);
                String matchKey = MATCH_PLAYERS_PREFIX + matchId;
                redisTemplate.opsForSet().remove(matchKey, normalizedName);
                return;
            }

            CustomMatch match = matchOpt.get();
            String status = match.getStatus();
            boolean isFinished = "completed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status);

            if (!isFinished) {
                // 🚨 CRÍTICO: Match ainda está ativa no MySQL, NÃO PODE LIMPAR!
                log.error(
                        "🚨 [RedisPlayerMatch] BLOQUEADO! Tentativa de limpar {} da match {} que ainda está ATIVA no MySQL (status={})! Redis NÃO será limpo!",
                        normalizedName, matchId, status);
                return;
            }

            // ✅ Match está finalizada no MySQL, pode limpar Redis
            String playerKey = PLAYER_MATCH_PREFIX + normalizedName;
            redisTemplate.delete(playerKey);
            String matchKey = MATCH_PLAYERS_PREFIX + matchId;
            redisTemplate.opsForSet().remove(matchKey, normalizedName);
            log.info("🗑️ [RedisPlayerMatch] Removido: {} (match: {} finalizada com status={})",
                    normalizedName, matchId, status);
        } catch (Exception e) {
            log.error("❌ [RedisPlayerMatch] Erro ao remover player: {}", summonerName, e);
        }
    }

    /**
     * ✅ Remove TODOS os jogadores de uma partida (quando partida finaliza)
     * 
     * @param matchId ID da partida
     */
    public void clearMatchPlayers(Long matchId) {
        try {
            // ✅ VALIDAÇÃO MySQL: Verificar se a partida está finalizada antes de limpar
            Optional<CustomMatch> matchOpt = customMatchRepository.findById(matchId);

            if (matchOpt.isEmpty()) {
                // ✅ Match não existe no MySQL, pode limpar Redis
                log.warn("🧹 [RedisPlayerMatch] Match {} não existe no MySQL, limpando Redis", matchId);
            } else {
                CustomMatch match = matchOpt.get();
                String status = match.getStatus();
                boolean isFinished = "completed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status);

                if (!isFinished) {
                    // 🚨 CRÍTICO: Match ainda está ativa no MySQL, NÃO PODE LIMPAR!
                    log.error(
                            "🚨 [RedisPlayerMatch] BLOQUEADO! Tentativa de limpar jogadores da match {} que ainda está ATIVA no MySQL (status={})! Redis NÃO será limpo!",
                            matchId, status);
                    return;
                }

                log.info("🗑️ [RedisPlayerMatch] Match {} finalizada no MySQL (status={}), limpando Redis",
                        matchId, status);
            }

            // ✅ Tudo OK, pode limpar Redis
            // 1. Buscar todos os jogadores da partida
            Set<String> players = getMatchPlayers(matchId);

            // 2. Remover cada player → matchId
            for (String player : players) {
                String playerKey = PLAYER_MATCH_PREFIX + player;
                redisTemplate.delete(playerKey);
            }

            // 3. Remover o Set da partida
            String matchKey = MATCH_PLAYERS_PREFIX + matchId;
            redisTemplate.delete(matchKey);

            log.info("🗑️ [RedisPlayerMatch] Removidos {} jogadores de match {}", players.size(), matchId);

        } catch (Exception e) {
            log.error("❌ [RedisPlayerMatch] Erro ao remover players de match {}", matchId, e);
        }
    }

    /**
     * Normaliza nome do jogador (lowercase + trim)
     */
    private String normalizePlayerName(String summonerName) {
        if (summonerName == null) {
            return "";
        }
        return summonerName.toLowerCase().trim();
    }
}
