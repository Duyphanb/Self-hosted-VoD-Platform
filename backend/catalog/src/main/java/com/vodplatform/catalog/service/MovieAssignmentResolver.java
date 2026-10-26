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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns the validated genres and credits of a movie upsert into the rows a movie is assigned to,
 * following ADR-009. Runs inside the movie write transaction.
 *
 * <p>Genres follow the {@code UpsertMovieRequest} rule: a genre with the same slug is reused and
 * keeps its stored name; otherwise a genre is created with the normalized name. When a concurrent
 * request creates the same genre first, the insert does nothing
 * ({@link GenreRepository#insertIfAbsent}) and that request's genre is reused, so concurrent
 * creation of the same genre never fails.
 *
 * <p>Credits are resolved in two steps, so that the movie service can write the movie row between
 * them and no person is inserted before a duplicate movie slug is reported:
 * <ol>
 *   <li>{@link #resolveCredits} only reads. A {@code personId} identifies the person (an unknown id
 *       is a 400 on {@code people[i].personId}). A name-only entry is compared case-insensitively,
 *       with diacritics significant, against the stored names of all people: one match reuses that
 *       person, several matches are a 400 on {@code people[i].personId} asking for an id, and no
 *       match means the person will be created. Name-only entries whose normalized names are
 *       equal ignoring case resolve to the same person. A (person, role) pair may appear once; the
 *       check runs on the resolved person, so a name that resolves to a person already credited
 *       in the same role is a duplicate.</li>
 *   <li>{@link #assignCredits} creates each new person once, with a generated slug
 *       ({@link PersonWriter}), and returns the assignments in request order.</li>
 * </ol>
 */
@Component
class MovieAssignmentResolver {

    static final String UNKNOWN_PERSON = "must identify an existing person";
    static final String AMBIGUOUS_NAME = "is required because more than one person has this name";
    static final String DUPLICATE_CREDIT = "must not repeat the person and role of another entry";

    private final GenreRepository genreRepository;
    private final PersonRepository personRepository;
    private final PersonWriter personWriter;

    MovieAssignmentResolver(GenreRepository genreRepository, PersonRepository personRepository) {
        this.genreRepository = genreRepository;
        this.personRepository = personRepository;
        this.personWriter = new PersonWriter(personRepository);
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
            genres.add(genre != null ? genre : createOrReuse(input));
        }
        return genres;
    }

    /**
     * Inserts the genre, or does nothing when another transaction has committed a genre with the
     * same slug since the lookup, then reads the genre by slug, which returns either one.
     */
    private Genre createOrReuse(GenreInput input) {
        genreRepository.insertIfAbsent(UUID.randomUUID(), input.name(), input.slug());
        return genreRepository.findBySlug(input.slug())
                .orElseThrow(() -> new IllegalStateException("A genre with the requested name has another slug"));
    }

    /**
     * Resolves every credit without writing anything; see the class comment.
     *
     * @return one resolved credit per input, in input order
     * @throws CatalogValidationException if a person cannot be resolved or a (person, role) pair
     *                                    repeats
     */
    List<ResolvedCredit> resolveCredits(List<CreditInput> inputs) {
        Set<UUID> personIds = new HashSet<>();
        inputs.stream().map(CreditInput::personId).filter(Objects::nonNull).forEach(personIds::add);
        Map<UUID, Person> peopleById = new HashMap<>();
        personRepository.findAllById(personIds).forEach(person -> peopleById.put(person.getId(), person));
        Map<String, NameMatch> nameMatches = new HashMap<>();

        List<ApiFieldError> errors = new ArrayList<>();
        Set<CreditKey> assigned = new HashSet<>();
        List<ResolvedCredit> credits = new ArrayList<>(inputs.size());
        for (CreditInput input : inputs) {
            ResolvedCredit credit;
            if (input.personId() != null) {
                Person person = peopleById.get(input.personId());
                if (person == null) {
                    errors.add(new ApiFieldError(input.field() + ".personId", UNKNOWN_PERSON));
                    continue;
                }
                credit = new ResolvedCredit(person, null, input.role());
            } else {
                NameMatch match = nameMatches.computeIfAbsent(nameKey(input.name()), key -> matchName(input.name()));
                if (match.ambiguous()) {
                    errors.add(new ApiFieldError(input.field() + ".personId", AMBIGUOUS_NAME));
                    continue;
                }
                credit = new ResolvedCredit(match.person(), match.newName(), input.role());
            }
            if (!assigned.add(credit.key())) {
                errors.add(new ApiFieldError(input.field(), DUPLICATE_CREDIT));
                continue;
            }
            credits.add(credit);
        }
        if (!errors.isEmpty()) {
            throw new CatalogValidationException(errors);
        }
        return credits;
    }

    /**
     * Creates the people that name-only credits introduce, each once, and returns one assignment
     * per resolved credit, in the same order. Call it after the movie row is written.
     */
    List<CreditAssignment> assignCredits(List<ResolvedCredit> credits) {
        Map<String, Person> created = new HashMap<>();
        List<CreditAssignment> assignments = new ArrayList<>(credits.size());
        for (ResolvedCredit credit : credits) {
            Person person = credit.person() != null
                    ? credit.person()
                    : created.computeIfAbsent(nameKey(credit.newName()), key -> personWriter.create(credit.newName()));
            assignments.add(new CreditAssignment(person, credit.role()));
        }
        return assignments;
    }

    private NameMatch matchName(String name) {
        List<Person> matches = personRepository.findAllByNameIgnoringCase(name);
        return switch (matches.size()) {
            case 0 -> new NameMatch(null, name, false);
            case 1 -> new NameMatch(matches.getFirst(), null, false);
            default -> new NameMatch(null, null, true);
        };
    }

    /**
     * Groups name-only entries of one request: normalized names that are equal ignoring case,
     * as the database comparison treats them.
     */
    private static String nameKey(String normalizedName) {
        return normalizedName.toLowerCase(Locale.ROOT);
    }

    /**
     * A credit after {@link #resolveCredits}: an existing {@code person}, or the normalized
     * {@code newName} of a person {@link #assignCredits} creates. Exactly one of them is set.
     */
    record ResolvedCredit(Person person, String newName, CreditRole role) {

        ResolvedCredit {
            if ((person == null) == (newName == null)) {
                throw new IllegalArgumentException("Exactly one of person and newName must be set");
            }
            Objects.requireNonNull(role, "role");
        }

        private CreditKey key() {
            return person != null
                    ? new CreditKey(person.getId(), null, role)
                    : new CreditKey(null, nameKey(newName), role);
        }
    }

    /**
     * The outcome of matching one name: an existing person, a person to create under the name of
     * the first entry with that name, or an ambiguous name.
     */
    private record NameMatch(Person person, String newName, boolean ambiguous) {
    }

    /**
     * Identifies a (person, role) pair; a person to create is identified by its name key.
     */
    private record CreditKey(UUID personId, String newNameKey, CreditRole role) {
    }
}
