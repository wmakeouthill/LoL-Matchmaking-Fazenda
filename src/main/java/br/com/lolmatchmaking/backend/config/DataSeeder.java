package br.com.lolmatchmaking.backend.config;

import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
// @Component // ✅ DESABILITADO: Players só devem ser criados quando jogadores
// reais logam
@Profile({ "development", "test" })
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final PlayerRepository playerRepository;

    @Override
    public void run(String... args) {
        if (playerRepository.count() == 0) {
            seedPlayers();
        }
    }

    private void seedPlayers() {
        log.info("Criando dados de teste para jogadores...");

        // Criando alguns jogadores de exemplo
        Player[] testPlayers = {
                Player.builder()
                        .summonerName("TestPlayer1")
                        .puuid("test-puuid-1")
                        .region("br1")
                        .currentMmr(1500)
                        .peakMmr(1600)
                        .customMmr(1500)
                        .customPeakMmr(1600)
                        .build(),

                Player.builder()
                        .summonerName("TestPlayer2")
                        .puuid("test-puuid-2")
                        .region("br1")
                        .currentMmr(1400)
                        .peakMmr(1500)
                        .customMmr(1400)
                        .customPeakMmr(1500)
                        .build(),

                Player.builder()
                        .summonerName("TestPlayer3")
                        .puuid("test-puuid-3")
                        .region("br1")
                        .currentMmr(1600)
                        .peakMmr(1700)
                        .customMmr(1600)
                        .customPeakMmr(1700)
                        .build(),

                Player.builder()
                        .summonerName("TestPlayer4")
                        .puuid("test-puuid-4")
                        .region("br1")
                        .currentMmr(1300)
                        .peakMmr(1400)
                        .customMmr(1300)
                        .customPeakMmr(1400)
                        .build(),

                Player.builder()
                        .summonerName("TestPlayer5")
                        .puuid("test-puuid-5")
                        .region("br1")
                        .currentMmr(1550)
                        .peakMmr(1650)
                        .customMmr(1550)
                        .customPeakMmr(1650)
                        .build()
        };

        for (Player player : testPlayers) {
            playerRepository.save(player);
        }

        log.info("Criados {} jogadores de teste", testPlayers.length);
    }
}
