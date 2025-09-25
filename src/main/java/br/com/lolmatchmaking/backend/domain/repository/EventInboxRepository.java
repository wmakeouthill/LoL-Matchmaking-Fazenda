package br.com.lolmatchmaking.backend.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import br.com.lolmatchmaking.backend.domain.entity.EventInbox;
import java.util.List;
import java.util.Optional;

public interface EventInboxRepository extends JpaRepository<EventInbox, Long> {
    Optional<EventInbox> findByEventId(String eventId);

    List<EventInbox> findByProcessedFalseOrderByCreatedAtAsc();
}
