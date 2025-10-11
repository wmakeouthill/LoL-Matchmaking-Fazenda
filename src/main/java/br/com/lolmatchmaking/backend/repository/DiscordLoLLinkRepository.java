package br.com.lolmatchmaking.backend.repository;

import br.com.lolmatchmaking.backend.entity.DiscordLoLLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DiscordLoLLinkRepository extends JpaRepository<DiscordLoLLink, Long> {

    /**
     * Busca vinculação por Discord ID
     */
    Optional<DiscordLoLLink> findByDiscordIdAndActiveTrue(String discordId);

    /**
     * Busca vinculação por Game Name e Tag Line
     */
    Optional<DiscordLoLLink> findByGameNameAndTagLineAndActiveTrue(String gameName, String tagLine);

    /**
     * Busca todas as vinculações ativas
     */
    List<DiscordLoLLink> findByActiveTrue();

    /**
     * Busca vinculações por região
     */
    List<DiscordLoLLink> findByRegionAndActiveTrue(String region);

    /**
     * Verifica se existe vinculação para um Discord ID
     */
    boolean existsByDiscordIdAndActiveTrue(String discordId);

    /**
     * Busca vinculação por summoner name (se disponível)
     */
    @Query("SELECT d FROM DiscordLoLLink d WHERE d.summonerName = :summonerName AND d.active = true")
    Optional<DiscordLoLLink> findBySummonerNameAndActive(@Param("summonerName") String summonerName);

    /**
     * Desativa vinculação por Discord ID
     */
    @Modifying
    @Query("UPDATE DiscordLoLLink d SET d.active = false WHERE d.discordId = :discordId AND d.active = true")
    int deactivateByDiscordId(@Param("discordId") String discordId);
}
