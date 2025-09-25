package br.com.lolmatchmaking.backend.domain.repository;

import br.com.lolmatchmaking.backend.domain.entity.DiscordConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DiscordConfigRepository extends JpaRepository<DiscordConfig, Long> {

    Optional<DiscordConfig> findByIsActiveTrue();

    Optional<DiscordConfig> findByGuildId(String guildId);
}
