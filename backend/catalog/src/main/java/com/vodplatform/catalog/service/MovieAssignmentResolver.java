package com.vodplatform.catalog.service;

import com.vodplatform.catalog.exception.CatalogValidationException;
import com.vodplatform.catalog.persistence.CreditAssignment;
import com.vodplatform.catalog.persistence.CreditRole;
import com.vodplatform.catalog.persistence.Genre;
import com.vodplatform.catalog.persistence.GenreRepository;
import com.vodplatform.catalog.persistence.Person;
import com.vodplatform.catalog.persistence.PersonRepository;
import com.vodplatform.catalog.service.ValidatedMovie.CreditInput;
import com.vodplatform.catalog.service.ValidatedMovie.GenreInput;
import com.vodplatform.shared.error.ApiFieldError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns the validated genres and credits of a movie upsert into the rows a movie is assigned to.
 * Runs inside the movie write transaction.
 *
 * <p>Genres follow the {@code UpsertMovieRequest} rule: a genre with the same slug is reused and
 * keeps its stored name; otherwise a genre is created with the normalized name. Concurrent
 * creation of the same new genre is not yet handled and belongs to Issue 3.3 (#86).
 *
 * <p>Credits identified by {@code personId} reuse that person; an unknown id is a 400 on
 * {@code people[i].personId}. Name-only credits (ADR-009 name matching and person creation)
 * belong to Issue 3.3 (#86) and are rejected until then, see {@link #resolveByName}. A (person,
 * role) pair may appear once; the check runs on the resolved person.
 */
@Component
class MovieAssignmentResolver {

    static final String UNKNOWN_PERSON = "must identify an existing person";
    static final String PERSON_ID_REQUIRED = "is required; name-only credits are not supported yet";
    static final String DUPLICATE_CREDIT = "must not repeat the person and role of another entry";

    private final GenreRepository genreRepository;
    private final PersonRepository personRepository;

    MovieAssignmentResolver(GenreRepository genreRepository, PersonRepository personRepository) {
        this.genreRepository = genreRepository;
        this.personRepository = personRepository;
    }

    /**
     * Returns one genre per input, in input order, creating the genres that do not exist yet.
     */
    List<Genre> resolveGenres(List<GenreInput> inputs) {
        Map<String, Genre> existing = new HashMap<>();
        genreRepository.findAllBySlugIn(inputs.stream().map(GenreInput::slug).toList())
                .forEach(genre -> existing.put(genre.getSlug(), genre));
        List<Genre> genres = new ArrayList<>(inputs.size());
        for (GenreInput input : inputs) {
            Genre genre = existing.get(input.slug());
            if (genre == null) {
                genre = genreRepository.save(new Genre(UUID.randomUUID(), input.name(), input.slug()));
            }
            genres.add(genre);
        }
        return genres;
    }

    /**
     * Returns one credit per input, in input order.
     *
     * @throws CatalogValidationException if a person cannot be resolved or a (person, role) pair
     *                                    repeats
     */
    List<CreditAssignment> resolveCredits(List<CreditInput> inputs) {
        Set<UUID> personIds = new HashSet<>();
        inputs.stream().map(CreditInput::personId).filter(id -> id != null).forEach(personIds::add);
        Map<UUID, Person> peopleById = new HashMap<>();
        personRepository.findAllById(personIds).forEach(person -> peopleById.put(person.getId(), person));

        List<ApiFieldError> errors = new ArrayList<>();
        Set<CreditKey> assigned = new HashSet<>();
        List<CreditAssignment> credits = new ArrayList<>(inputs.size());
        for (CreditInput input : inputs) {
            Person person = input.personId() != null
                    ? peopleById.get(input.personId())
                    : resolveByName(input, errors);
            if (person == null) {
                if (input.personId() != null) {
                    errors.add(new ApiFieldError(input.field() + ".personId", UNKNOWN_PERSON));
                }
                continue;
            }
            if (!assigned.add(new CreditKey(person.getId(), input.role()))) {
                errors.add(new ApiFieldError(input.field(), DUPLICATE_CREDIT));
                continue;
            }
            credits.add(new CreditAssignment(person, input.role()));
        }
        if (!errors.isEmpty()) {
            throw new CatalogValidationException(errors);
        }
        return credits;
    }

    /**
     * Seam for Issue 3.3 (#86): name-only credits are matched case-insensitively against stored
     * person names (no match creates a person with a generated slug, one match reuses it, several
     * matches are a 400 asking for a {@code personId}), and equal names in one request resolve to
     * one person. Until that lands, a name-only credit is a 400 asking for a {@code personId}.
     *
     * @return the person, or null after recording an error
     */
    private Person resolveByName(CreditInput input, List<ApiFieldError> errors) {
        errors.add(new ApiFieldError(input.field() + ".personId", PERSON_ID_REQUIRED));
        return null;
    }

    private record CreditKey(UUID personId, CreditRole role) {
    }
}
