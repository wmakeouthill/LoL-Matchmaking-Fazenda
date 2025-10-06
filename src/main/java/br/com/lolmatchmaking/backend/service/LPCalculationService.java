package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Serviço responsável por calcular o LP (League Points) ganho ou perdido em
 * partidas customizadas.
 * 
 * O cálculo é baseado no sistema ELO/MMR:
 * - Jogadores com MMR mais alto ganham menos LP ao vencer e perdem mais ao
 * perder
 * - Jogadores com MMR mais baixo ganham mais LP ao vencer e perdem menos ao
 * perder
 * - A diferença de MMR entre os times afeta o ganho/perda de LP
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LPCalculationService {

    private final PlayerRepository playerRepository;

    // Constante K-Factor para cálculo de MMR (padrão ELO)
    private static final int K_FACTOR = 32;

    // MMR padrão para jogadores novos
    private static final int DEFAULT_MMR = 1000;

    /**
     * Calcula o LP ganho ou perdido para um jogador baseado no resultado da partida
     * 
     * @param playerMMR   MMR atual do jogador
     * @param opponentMMR MMR médio do time adversário
     * @param isWin       Se o jogador venceu a partida
     * @return LP ganho (positivo) ou perdido (negativo)
     */
    public int calculateLPChange(int playerMMR, int opponentMMR, boolean isWin) {
        // Calcular a chance esperada de vitória baseado na diferença de MMR
        double expectedScore = 1.0 / (1.0 + Math.pow(10.0, (opponentMMR - playerMMR) / 400.0));

        // Score real (1 para vitória, 0 para derrota)
        double actualScore = isWin ? 1.0 : 0.0;

        // Calcular mudança de LP usando fórmula ELO
        int lpChange = (int) Math.round(K_FACTOR * (actualScore - expectedScore));

        log.debug("Cálculo LP: playerMMR={}, opponentMMR={}, isWin={}, expectedScore={}, lpChange={}",
                playerMMR, opponentMMR, isWin, expectedScore, lpChange);

        return lpChange;
    }

    /**
     * Calcula as mudanças de LP para todos os jogadores de uma partida
     * 
     * @param team1Players Lista de nomes dos jogadores do time 1
     * @param team2Players Lista de nomes dos jogadores do time 2
     * @param winnerTeam   Time vencedor (1 ou 2)
     * @return Map com nome do jogador como chave e LP ganho/perdido como valor
     */
    public Map<String, Integer> calculateMatchLPChanges(List<String> team1Players, List<String> team2Players,
            int winnerTeam) {

        log.info("🎯 calculateMatchLPChanges - Time 1: {}, Time 2: {}, Vencedor: {}",
                team1Players, team2Players, winnerTeam);

        Map<String, Integer> lpChanges = new HashMap<>();

        try {
            // Calcular MMR médio de cada time
            int team1AverageMMR = calculateTeamAverageMMR(team1Players);
            int team2AverageMMR = calculateTeamAverageMMR(team2Players);

            log.info("📊 MMR médio - Time 1: {}, Time 2: {}", team1AverageMMR, team2AverageMMR);

            // Calcular LP para jogadores do time 1
            for (String playerName : team1Players) {
                if (playerName != null && !playerName.isEmpty()) {
                    int playerMMR = getPlayerCurrentMMR(playerName);
                    boolean isWin = (winnerTeam == 1);
                    int lpChange = calculateLPChange(playerMMR, team2AverageMMR, isWin);
                    lpChanges.put(playerName, lpChange);

                    log.info("👤 {} (MMR: {}) vs Time 2 (MMR: {}) - {} : {} LP",
                            playerName, playerMMR, team2AverageMMR,
                            isWin ? "VITÓRIA" : "DERROTA",
                            lpChange > 0 ? "+" + lpChange : lpChange);
                }
            }

            // Calcular LP para jogadores do time 2
            for (String playerName : team2Players) {
                if (playerName != null && !playerName.isEmpty()) {
                    int playerMMR = getPlayerCurrentMMR(playerName);
                    boolean isWin = (winnerTeam == 2);
                    int lpChange = calculateLPChange(playerMMR, team1AverageMMR, isWin);
                    lpChanges.put(playerName, lpChange);

                    log.info("👤 {} (MMR: {}) vs Time 1 (MMR: {}) - {} : {} LP",
                            playerName, playerMMR, team1AverageMMR,
                            isWin ? "VITÓRIA" : "DERROTA",
                            lpChange > 0 ? "+" + lpChange : lpChange);
                }
            }

        } catch (Exception e) {
            log.error("❌ Erro ao calcular mudanças de LP: {}", e.getMessage(), e);
        }

        return lpChanges;
    }

    /**
     * Calcula o MMR médio de um time
     * 
     * @param teamPlayers Lista de nomes dos jogadores do time
     * @return MMR médio do time
     */
    private int calculateTeamAverageMMR(List<String> teamPlayers) {
        if (teamPlayers == null || teamPlayers.isEmpty()) {
            return DEFAULT_MMR;
        }

        int totalMMR = 0;
        int validPlayers = 0;

        for (String playerName : teamPlayers) {
            if (playerName != null && !playerName.isEmpty()) {
                int mmr = getPlayerCurrentMMR(playerName);
                totalMMR += mmr;
                validPlayers++;
            }
        }

        return validPlayers > 0 ? Math.round((float) totalMMR / validPlayers) : DEFAULT_MMR;
    }

    /**
     * Obtém o MMR atual de um jogador (custom_mmr)
     * 
     * @param playerName Nome do jogador (Riot ID completo, ex: "Player#BR1")
     * @return MMR atual do jogador ou MMR padrão se não encontrado
     */
    private int getPlayerCurrentMMR(String playerName) {
        try {
            Optional<Player> playerOpt = playerRepository.findBySummonerName(playerName);

            if (playerOpt.isPresent()) {
                Player player = playerOpt.get();
                Integer customMmr = player.getCustomMmr();

                if (customMmr != null && customMmr > 0) {
                    return customMmr;
                }
            }

            log.debug("Jogador {} não encontrado ou sem MMR customizado, usando MMR padrão: {}",
                    playerName, DEFAULT_MMR);
            return DEFAULT_MMR;

        } catch (Exception e) {
            log.error("❌ Erro ao buscar MMR do jogador {}: {}", playerName, e.getMessage());
            return DEFAULT_MMR;
        }
    }

    /**
     * Calcula o LP total de uma partida (soma absoluta de todos os LPs
     * ganhos/perdidos)
     * 
     * @param lpChanges Map com mudanças de LP por jogador
     * @return LP total da partida
     */
    public int calculateTotalMatchLP(Map<String, Integer> lpChanges) {
        if (lpChanges == null || lpChanges.isEmpty()) {
            return 0;
        }

        return lpChanges.values().stream()
                .mapToInt(Math::abs)
                .sum();
    }

    /**
     * Atualiza as estatísticas do jogador após uma partida
     * 
     * @param playerName Nome do jogador
     * @param lpChange   LP ganho ou perdido
     * @param isWin      Se o jogador venceu
     */
    public void updatePlayerStats(String playerName, int lpChange, boolean isWin) {
        try {
            Optional<Player> playerOpt = playerRepository.findBySummonerName(playerName);

            if (playerOpt.isEmpty()) {
                log.warn("⚠️ Jogador {} não encontrado para atualizar estatísticas", playerName);
                return;
            }

            Player player = playerOpt.get();

            // Atualizar custom_lp
            Integer currentLp = player.getCustomLp() != null ? player.getCustomLp() : 0;
            player.setCustomLp(currentLp + lpChange);

            // Atualizar custom_mmr (custom_mmr = soma do MMR base + custom_lp)
            Integer baseMmr = player.getCurrentMmr() != null ? player.getCurrentMmr() : DEFAULT_MMR;
            player.setCustomMmr(baseMmr + player.getCustomLp());

            // Atualizar estatísticas de partidas customizadas
            Integer customGamesPlayed = player.getCustomGamesPlayed() != null ? player.getCustomGamesPlayed() : 0;
            player.setCustomGamesPlayed(customGamesPlayed + 1);

            if (isWin) {
                Integer customWins = player.getCustomWins() != null ? player.getCustomWins() : 0;
                player.setCustomWins(customWins + 1);
            } else {
                Integer customLosses = player.getCustomLosses() != null ? player.getCustomLosses() : 0;
                player.setCustomLosses(customLosses + 1);
            }

            // Atualizar custom_peak_mmr se necessário
            Integer customPeakMmr = player.getCustomPeakMmr() != null ? player.getCustomPeakMmr() : DEFAULT_MMR;
            if (player.getCustomMmr() > customPeakMmr) {
                player.setCustomPeakMmr(player.getCustomMmr());
            }

            playerRepository.save(player);

            log.info("✅ Jogador {} atualizado: LP {} (total: {}), MMR: {}",
                    playerName,
                    lpChange > 0 ? "+" + lpChange : lpChange,
                    player.getCustomLp(),
                    player.getCustomMmr());

        } catch (Exception e) {
            log.error("❌ Erro ao atualizar estatísticas do jogador {}: {}", playerName, e.getMessage(), e);
        }
    }
}
