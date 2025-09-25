package br.com.lolmatchmaking.backend.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import br.com.lolmatchmaking.backend.domain.entity.DiscordLolLink;
import java.util.Optional;

public interface DiscordLolLinkRepository extends JpaRepository<DiscordLolLink, Long> {
    Optional<DiscordLolLink> findByDiscordId(String discordId);

    Optional<DiscordLolLink> findBySummonerName(String summonerName);
}
