package com.vodplatform.catalog.service;

import com.vodplatform.catalog.exception.MovieNotFoundException;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Per-movie transaction serialization shared by upload commit and retry (ADR-010). */
@Service
public class MovieRowLocks {

    private final MovieRepository movieRepository;

    public MovieRowLocks(MovieRepository movieRepository) {
        this.movieRepository = Objects.requireNonNull(movieRepository, "movieRepository");
    }

    /**
     * Locks a movie in any status until the caller's transaction ends. This must be the first
     * statement in that transaction that loads the movie, so an existing managed instance cannot
     * hide the previous writer's committed state. Under this lock, read newest/in-flight, insert
     * the asset (or conditionally claim a FAILED retry), then insert the unpublished job. Commit
     * before publishing. Generate the asset id and finish raw PUT before entering this transaction.
     *
     * <p>For retry, read the asset's immutable movie id through a scalar projection or an earlier
     * transaction, so no VideoAsset is managed before acquiring this lock. Then reread the
     * newest/in-flight state; the conditional update's row count decides the retry outcome.
     * The worker does not acquire this lock. PESSIMISTIC_WRITE uses
     * FOR NO KEY UPDATE on PostgreSQL and FOR UPDATE on H2, as catalog update/archive do.
     *
     * @throws MovieNotFoundException if the movie does not exist
     * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedMovie lockForMediaWrite(UUID movieId) {
        Movie movie = movieRepository.findForUpdateById(Objects.requireNonNull(movieId, "movieId"))
                .orElseThrow(MovieNotFoundException::new);
        return new LockedMovie(movie.getId(), movie.getStatus());
    }
}
