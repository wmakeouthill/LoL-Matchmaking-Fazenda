package br.com.lolmatchmaking.backend.service;

import br.com.lolmatchmaking.backend.entity.DiscordLoLLink;
import br.com.lolmatchmaking.backend.repository.DiscordLoLLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordLoLLinkService {

    private final DiscordLoLLinkRepository repository;

    /**
     * Cria ou atualiza uma vinculação Discord-LoL
     */
    @Transactional
    public DiscordLoLLink createOrUpdateLink(String discordId, String discordUsername,
            String gameName, String tagLine, String region) {
        try {
            // Buscar vinculação existente
            Optional<DiscordLoLLink> existingLink = repository.findByDiscordIdAndActiveTrue(discordId);

            if (existingLink.isPresent()) {
                // Atualizar vinculação existente
                DiscordLoLLink link = existingLink.get();
                link.setDiscordUsername(discordUsername);
                link.setGameName(gameName);
                link.setTagLine(tagLine);
                // Remover # duplicado se tagLine já começar com #
                String cleanTagLine = tagLine.startsWith("#") ? tagLine : "#" + tagLine;
                link.setSummonerName(gameName + cleanTagLine); // Usar gameName#tagLine como summoner_name
                link.setRegion(region);
                link.setVerified(true);
                link.setActive(true);

                log.info("🔄 [DiscordLoLLinkService] Vinculação atualizada: {} -> {}#{}",
                        discordUsername, gameName, tagLine);

                return repository.save(link);
            } else {
                // Criar nova vinculação
                DiscordLoLLink newLink = new DiscordLoLLink();
                newLink.setDiscordId(discordId);
                newLink.setDiscordUsername(discordUsername);
                newLink.setGameName(gameName);
                newLink.setTagLine(tagLine);
                // Remover # duplicado se tagLine já começar com #
                String cleanTagLine = tagLine.startsWith("#") ? tagLine : "#" + tagLine;
                newLink.setSummonerName(gameName + cleanTagLine); // Usar gameName#tagLine como summoner_name
                newLink.setRegion(region);
                newLink.setVerified(true);
                newLink.setActive(true);

                log.info("✅ [DiscordLoLLinkService] Nova vinculação criada: {} -> {}#{}",
                        discordUsername, gameName, tagLine);

                return repository.save(newLink);
            }
        } catch (Exception e) {
            log.error("❌ [DiscordLoLLinkService] Erro ao criar/atualizar vinculação", e);
            throw e;
        }
    }

    /**
     * Remove uma vinculação Discord-LoL
     */
    @Transactional
    public boolean removeLink(String discordId) {
        try {
            Optional<DiscordLoLLink> link = repository.findByDiscordIdAndActiveTrue(discordId);

            if (link.isPresent()) {
                int updated = repository.deactivateByDiscordId(discordId);
                log.info("🗑️ [DiscordLoLLinkService] Vinculação removida para Discord ID: {}", discordId);
                return updated > 0;
            } else {
                log.warn("⚠️ [DiscordLoLLinkService] Vinculação não encontrada para Discord ID: {}", discordId);
                return false;
            }
        } catch (Exception e) {
            log.error("❌ [DiscordLoLLinkService] Erro ao remover vinculação", e);
            return false;
        }
    }

    /**
     * Busca vinculação por Discord ID
     */
    public Optional<DiscordLoLLink> findByDiscordId(String discordId) {
        return repository.findByDiscordIdAndActiveTrue(discordId);
    }

    /**
     * Busca vinculação por Game Name e Tag Line
     */
    public Optional<DiscordLoLLink> findByGameNameAndTagLine(String gameName, String tagLine) {
        return repository.findByGameNameAndTagLineAndActiveTrue(gameName, tagLine);
    }

    /**
     * Busca todas as vinculações ativas
     */
    public List<DiscordLoLLink> getAllActiveLinks() {
        return repository.findByActiveTrue();
    }

    /**
     * Verifica se existe vinculação para um Discord ID
     */
    public boolean hasLink(String discordId) {
        return repository.existsByDiscordIdAndActiveTrue(discordId);
    }

    /**
     * Atualiza último uso da vinculação
     */
    @Transactional
    public void updateLastUsed(String discordId) {
        try {
            Optional<DiscordLoLLink> link = repository.findByDiscordIdAndActiveTrue(discordId);
            if (link.isPresent()) {
                DiscordLoLLink existingLink = link.get();
                existingLink.setLastUsed(LocalDateTime.now());
                repository.save(existingLink);
            }
        } catch (Exception e) {
            log.error("❌ [DiscordLoLLinkService] Erro ao atualizar último uso", e);
        }
    }
}
