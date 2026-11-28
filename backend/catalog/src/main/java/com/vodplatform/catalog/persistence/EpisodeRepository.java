package com.vodplatform.catalog.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EpisodeRepository extends JpaRepository<Episode, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Episode e where e.id = :id")
    Optional<Episode> findForUpdateById(UUID id);

    @Query("select e from Episode e where e.id = :id and e.status in :statuses and e.title.status in :statuses")
    Optional<Episode> findVisibleById(UUID id, Collection<MovieStatus> statuses);

    @Query("""
            select e from Episode e
            where e.title.id = :titleId and e.status in :statuses and e.title.status in :statuses
            order by e.ordinal
            """)
    Page<Episode> findVisibleByTitleId(UUID titleId, Collection<MovieStatus> statuses, Pageable pageable);
}
