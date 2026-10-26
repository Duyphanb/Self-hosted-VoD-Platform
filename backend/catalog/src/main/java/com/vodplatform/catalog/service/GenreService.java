package com.vodplatform.catalog.service;

import com.vodplatform.catalog.dto.GenreResponse;
import com.vodplatform.catalog.dto.UpsertGenreRequest;
import com.vodplatform.catalog.exception.CatalogValidationException;
import com.vodplatform.catalog.exception.GenreConflictException;
import com.vodplatform.catalog.exception.GenreNotFoundException;
import com.vodplatform.catalog.persistence.CatalogConstraints;
import com.vodplatform.catalog.persistence.Genre;
import com.vodplatform.catalog.persistence.GenreRepository;
import com.vodplatform.catalog.service.ValidatedMovie.GenreInput;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin genre management (Issue 3.3): list, create and rename; genres are never deleted. Names are
 * normalized and the slug, the ADR-009 match key, is derived from the name. Returns DTOs only.
 *
 * <p>A genre whose name or slug another genre already has is a 409 {@code GENRE_CONFLICT}. Create
 * inserts with {@code ON CONFLICT DO NOTHING} ({@link GenreRepository#insertIfAbsent}), so two
 * concurrent creates of the same genre give one 201 and one 409, never a database error. Rename
 * checks the slug first; a duplicate written concurrently after that check fails on
 * {@code ux_genres_name} or {@code ux_genres_slug} at the flush and is reported as the same 409.
 */
@Service
public class GenreService {

    private final GenreRepository genreRepository;

    GenreService(GenreRepository genreRepository) {
        this.genreRepository = genreRepository;
    }

    /**
     * Every genre, ordered by name, then id.
     */
    @Transactional(readOnly = true)
    public List<GenreResponse> list() {
        return genreRepository.findAllByOrderByNameAscIdAsc().stream().map(GenreService::toResponse).toList();
    }

    /**
     * @throws CatalogValidationException 400 {@code VALIDATION_ERROR}
     * @throws GenreConflictException     409 {@code GENRE_CONFLICT}
     */
    @Transactional
    public GenreResponse create(UpsertGenreRequest request) {
        GenreInput input = NameRequestValidator.genre(request);
        UUID id = UUID.randomUUID();
        if (genreRepository.insertIfAbsent(id, input.name(), input.slug()) == 0) {
            throw new GenreConflictException();
        }
        return new GenreResponse(id, input.name(), input.slug());
    }

    /**
     * Renames a genre and recomputes its slug from the new name. Renaming a genre to its own name or
     * slug is allowed.
     *
     * @throws CatalogValidationException 400 {@code VALIDATION_ERROR}
     * @throws GenreNotFoundException     404 {@code GENRE_NOT_FOUND}
     * @throws GenreConflictException     409 {@code GENRE_CONFLICT}
     */
    @Transactional
    public GenreResponse rename(UUID genreId, UpsertGenreRequest request) {
        Objects.requireNonNull(genreId, "genreId");
        GenreInput input = NameRequestValidator.genre(request);
        Genre genre = genreRepository.findById(genreId).orElseThrow(GenreNotFoundException::new);
        if (genreRepository.existsBySlugAndIdNot(input.slug(), genreId)) {
            throw new GenreConflictException();
        }
        genre.rename(input.name(), input.slug());
        try {
            genreRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            if (CatalogConstraints.isViolationOf(exception, CatalogConstraints.GENRES_NAME)
                    || CatalogConstraints.isViolationOf(exception, CatalogConstraints.GENRES_SLUG)) {
                throw new GenreConflictException();
            }
            throw exception;
        }
        return toResponse(genre);
    }

    private static GenreResponse toResponse(Genre genre) {
        return new GenreResponse(genre.getId(), genre.getName(), genre.getSlug());
    }
}
