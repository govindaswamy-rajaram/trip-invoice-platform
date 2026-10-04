package com.tripinvoice.itinerary.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.tripinvoice.itinerary.outbox.OutboxEvent;

import javax.persistence.LockModeType;
import javax.persistence.QueryHint;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Oldest unpublished rows, pessimistically locked. Hibernate renders this on PostgreSQL as
     * {@code ... ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED}, so several service instances
     * can relay events concurrently without publishing the same row twice.
     * The lock timeout hint value -2 is Hibernate's {@code LockOptions.SKIP_LOCKED}.
     * Must run inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "javax.persistence.lock.timeout", value = "-2"))
    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.createdAt, e.id")
    List<OutboxEvent> findUnpublishedForUpdate(Pageable pageable);
}
