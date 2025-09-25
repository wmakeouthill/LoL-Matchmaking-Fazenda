package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.domain.entity.Player;
import br.com.lolmatchmaking.backend.domain.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseSeederService implements CommandLineRunner {

    private final PlayerRepository playerRepository;

    @Override
    public void run(String... args) {
        log.info("Verificando necessidade de seed do banco de dados...");

        // Verificar se já existem dados
        long playerCount = playerRepository.count();
        if (playerCount > 0) {
            log.info("Banco de dados já contém {} jogadores. Seed não necessário.", playerCount);
            return;
        }

        log.info("Banco de dados vazio. Iniciando seed...");
        seedPlayers();
        log.info("Seed do banco de dados concluído!");
    }

    private void seedPlayers() {
        // Jogadores de exemplo para desenvolvimento
        createPlayerIfNotExists("TestPlayer1#BR1", "br1", 1200);
        createPlayerIfNotExists("TestPlayer2#BR1", "br1", 1450);
        createPlayerIfNotExists("TestPlayer3#BR1", "br1", 900);
        createPlayerIfNotExists("TestPlayer4#BR1", "br1", 1600);
        createPlayerIfNotExists("TestPlayer5#BR1", "br1", 800);

        log.info("Criados 5 jogadores de teste");
    }

    private void createPlayerIfNotExists(String summonerName, String region, int mmr) {
        if (playerRepository.findBySummonerNameIgnoreCase(summonerName).isEmpty()) {
            Player player = Player.builder()
                    .summonerName(summonerName)
                    .region(region)
                    .currentMmr(mmr)
                    .peakMmr(mmr)
                    .customMmr(mmr)
                    .customPeakMmr(mmr)
                    .build();

            playerRepository.save(player);
            log.debug("Jogador criado: {}", summonerName);
        }
    }
}
