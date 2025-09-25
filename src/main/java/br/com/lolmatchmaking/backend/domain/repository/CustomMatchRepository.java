package br.com.lolmatchmaking.backend.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.lolmatchmaking.backend.domain.entity.CustomMatch;

public interface CustomMatchRepository extends JpaRepository<CustomMatch, Long> {

    List<CustomMatch> findTop20ByOrderByCreatedAtDesc();

    List<CustomMatch> findByStatus(String status);

    List<CustomMatch> findTop10ByOrderByCreatedAtDesc();

    List<CustomMatch> findByTitleContaining(String title);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CustomMatch m SET m.ownerBackendId=:backendId, m.ownerHeartbeat=:now WHERE m.id=:id AND (m.ownerBackendId IS NULL OR m.ownerBackendId=:backendId OR m.ownerHeartbeat IS NULL OR m.ownerHeartbeat < :cutoff)")
    int tryClaimOwnership(@Param("id") Long id,
            @Param("backendId") String backendId,
            @Param("now") Long now,
            @Param("cutoff") Long cutoff);
}
