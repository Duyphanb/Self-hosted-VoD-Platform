package com.vodplatform.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.vodplatform.catalog.config.CatalogConfiguration;
import com.vodplatform.catalog.dto.PersonResponse;
import com.vodplatform.catalog.dto.UpsertPersonRequest;
import com.vodplatform.catalog.exception.CatalogValidationException;
import com.vodplatform.catalog.exception.PersonNotFoundException;
import com.vodplatform.catalog.persistence.CreditRole;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieCredit;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.persistence.Person;
import com.vodplatform.catalog.persistence.PersonRepository;
import com.vodplatform.shared.error.ApiException;
import com.vodplatform.shared.error.ApiFieldError;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;

/**
 * Admin people management on the entity-generated H2 schema (PostgreSQL mode): generated slugs,
 * renames and the search. Writes that race with another transaction are covered by
 * {@link CatalogConcurrencyTest}.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.vodplatform.catalog.service.RecordingStatementInspector")
@ContextConfiguration(classes = {CatalogConfiguration.class, PersonService.class, CatalogWriteRetry.class})
class PersonServiceTest {

    @Autowired
    private PersonService personService;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private CatalogWriteRetry writeRetry;

    // ---- create ----

    @Test
    void createStoresTheNormalizedNameWithAGeneratedSlug() {
        PersonResponse created = personService.create(new UpsertPersonRequest("  Trấn   Thành "));
        flushAndClear();

        assertThat(created.name()).isEqualTo("Trấn Thành");
        assertThat(created.slug()).isEqualTo("tran-thanh");
        Person stored = personRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Trấn Thành");
        assertThat(stored.getSlug()).isEqualTo("tran-thanh");
    }

    @Test
    void namesAreNotUniqueAndCollidingSlugsGetTheNextSuffix() {
        List<String> slugs = new ArrayList<>();
        for (String name : List.of("Trấn Thành", "trấn thành", "Tran Thanh", "TRẤN  THÀNH")) {
            slugs.add(personService.create(new UpsertPersonRequest(name)).slug());
        }

        assertThat(slugs).containsExactly("tran-thanh", "tran-thanh-2", "tran-thanh-3", "tran-thanh-4");
        assertThat(personRepository.count()).isEqualTo(4);
    }

    @Test
    void theFirstFreeSuffixFillsAGapAndOtherTailsDoNotCollide() {
        persist(1, "Trấn Thành", "tran-thanh");
        persist(2, "Trấn Thành", "tran-thanh-3");
        persist(3, "Trấn Thành Jr", "tran-thanh-jr");
        persist(4, "Trấn Thành", "tran-thanh-02");
        flushAndClear();

        assertThat(personService.create(new UpsertPersonRequest("Trấn Thành")).slug()).isEqualTo("tran-thanh-2");
        assertThat(personService.create(new UpsertPersonRequest("Trấn Thành")).slug()).isEqualTo("tran-thanh-4");
    }

    @Test
    void aNameWithoutLettersOrDigitsUsesThePersonBase() {
        assertThat(personService.create(new UpsertPersonRequest("★ ★")).slug()).isEqualTo("person");
        assertThat(personService.create(new UpsertPersonRequest("!!!")).slug()).isEqualTo("person-2");
    }

    @Test
    void theSlugBaseIsTruncatedTo240CharactersBeforeTheSuffix() {
        String longName = "a".repeat(250);
        // The 240th character is a separator, so the truncated base also loses that hyphen.
        String nameWithSeparator = "b".repeat(239) + " " + "c".repeat(15);

        String first = personService.create(new UpsertPersonRequest(longName)).slug();
        String second = personService.create(new UpsertPersonRequest(longName)).slug();
        String trimmed = personService.create(new UpsertPersonRequest(nameWithSeparator)).slug();

        assertThat(first).isEqualTo("a".repeat(240));
        assertThat(second).isEqualTo("a".repeat(240) + "-2");
        assertThat(trimmed).isEqualTo("b".repeat(239));
    }

    @Test
    void createRejectsInvalidNamesWithTheContractMessages() {
        assertNameError(() -> personService.create(new UpsertPersonRequest(null)), "must not be null");
        assertNameError(() -> personService.create(new UpsertPersonRequest("   ")), "must not be blank");
        assertNameError(() -> personService.create(new UpsertPersonRequest("Trấn\tThành")), "must not contain control characters");
        assertNameError(() -> personService.create(new UpsertPersonRequest("a".repeat(256))), "size must be between 1 and 255");

        assertThat(personService.create(new UpsertPersonRequest("ă".repeat(255))).name()).hasSize(255);
        assertThat(personRepository.count()).isEqualTo(1);
    }

    @Test
    void aSlugTakenAfterItWasChosenMovesTheCreateToTheNextSuffix() {
        persist(1, "Trấn Thành", "tran-thanh");
        flushAndClear();
        // The first lookup misses the existing slug, as when another transaction commits it after
        // the lookup; the insert then does nothing and the second lookup sees it.
        PersonService service = new PersonService(repositoryWithOneStaleSlugLookup(), writeRetry);

        PersonResponse created = service.create(new UpsertPersonRequest("Trấn Thành"));

        assertThat(created.slug()).isEqualTo("tran-thanh-2");
        assertThat(personRepository.count()).isEqualTo(2);
    }

    // ---- rename ----

    @Test
    void renameRegeneratesTheSlugAndUpdatesTheMovieCredits() {
        Person person = persist(1, "Tuấn Trần", "tuan-tran");
        persist(2, "Trấn Thành", "tran-thanh");
        Movie movie = new Movie(uuid(10), "Phim", "phim", "Mô tả", null, null, MovieStatus.PUBLISHED, Instant.EPOCH);
        movie.addCredit(person, CreditRole.ACTOR, 0);
        entityManager.persist(movie);
        flushAndClear();

        PersonResponse renamed = personService.rename(uuid(1), new UpsertPersonRequest(" Trấn  Thành "));
        flushAndClear();

        assertThat(renamed).isEqualTo(new PersonResponse(uuid(1), "Trấn Thành", "tran-thanh-2"));
        assertThat(personRepository.findById(uuid(1)).orElseThrow().getSlug()).isEqualTo("tran-thanh-2");
        assertThat(movieRepository.findById(uuid(10)).orElseThrow().getCredits())
                .extracting(credit -> credit.getPerson().getName(), MovieCredit::getRole)
                .containsExactly(tuple("Trấn Thành", CreditRole.ACTOR));
    }

    @Test
    void renameKeepsTheSlugWhenItsBaseIsUnchanged() {
        persist(1, "Trấn Thành", "tran-thanh");
        persist(2, "Trấn Thành", "tran-thanh-2");
        flushAndClear();

        assertThat(personService.rename(uuid(2), new UpsertPersonRequest("TRẤN THÀNH")))
                .isEqualTo(new PersonResponse(uuid(2), "TRẤN THÀNH", "tran-thanh-2"));
        // Kept even after the base itself becomes free.
        personService.rename(uuid(1), new UpsertPersonRequest("Lý Hải"));
        assertThat(personService.rename(uuid(2), new UpsertPersonRequest("Trấn Thành")).slug()).isEqualTo("tran-thanh-2");
    }

    @Test
    void renameExcludesThePersonItselfFromTheCollisionCheck() {
        persist(1, "Lý Hải", "ly-hai");
        persist(2, "Lý Hải 2", "ly-hai-2");
        flushAndClear();

        // The new base ly-hai is taken by person 1; ly-hai-2 belongs to the renamed person itself.
        assertThat(personService.rename(uuid(2), new UpsertPersonRequest("Lý Hải")).slug()).isEqualTo("ly-hai-2");
    }

    @Test
    void aSlugTakenAfterItWasChosenMovesTheRenameToTheNextSuffix() {
        persist(1, "Trấn Thành", "tran-thanh");
        persist(2, "Lý Hải", "ly-hai");
        flushAndClear();
        PersonService service = new PersonService(repositoryWithOneStaleSlugLookup(), writeRetry);

        PersonResponse renamed = service.rename(uuid(2), new UpsertPersonRequest("Trấn Thành"));
        flushAndClear();

        // The first update fails on ux_people_slug inside its savepoint; the transaction stays
        // usable and the retry takes the next suffix.
        assertThat(renamed).isEqualTo(new PersonResponse(uuid(2), "Trấn Thành", "tran-thanh-2"));
        assertThat(personRepository.findById(uuid(2)).orElseThrow().getSlug()).isEqualTo("tran-thanh-2");
    }

    @Test
    void renameLocksThePersonRowBeforeAnythingElseReadsPeople() {
        persist(1, "Lý Hải", "ly-hai");
        persist(2, "Trấn Thành", "tran-thanh");
        flushAndClear();

        // The slug base is unchanged, so the slug is kept without a slug lookup.
        RecordingStatementInspector.clear();
        personService.rename(uuid(1), new UpsertPersonRequest("LÝ HẢI"));
        List<String> keptSlug = peopleReads();
        flushAndClear();
        // The slug base changes, so the slug is chosen from a slug lookup.
        RecordingStatementInspector.clear();
        personService.rename(uuid(1), new UpsertPersonRequest("Trấn Thành"));
        List<String> newSlug = peopleReads();

        for (List<String> reads : List.of(keptSlug, newSlug)) {
            assertThat(reads).isNotEmpty();
            assertThat(reads.get(0)).as("%s", reads).contains(" where ").contains(" for update");
            assertThat(reads).filteredOn(sql -> sql.contains(" for update")).hasSize(1);
        }
        assertThat(newSlug).filteredOn(sql -> sql.contains(" like ")).hasSize(1);
    }

    @Test
    void aRenameAfterAnotherRenameAndAReclaimOfTheOldSlugTakesTheNextSuffix() {
        persist(1, "Lý Hải", "ly-hai");
        flushAndClear();
        // Another rename moves the person away from ly-hai, and a new "Lý Hải" reclaims it.
        personService.rename(uuid(1), new UpsertPersonRequest("Trấn Thành"));
        PersonResponse reclaimed = personService.create(new UpsertPersonRequest("Lý Hải"));
        flushAndClear();

        // Decided on the current row: the base changes from tran-thanh to ly-hai, which is taken.
        PersonResponse renamed = personService.rename(uuid(1), new UpsertPersonRequest("LÝ HẢI"));
        flushAndClear();
        // The person's own ly-hai-2 never counts as a collision: it is kept for the base ly-hai-2,
        // and again for the base ly-hai rather than moving to ly-hai-3.
        PersonResponse numbered = personService.rename(uuid(1), new UpsertPersonRequest("Lý Hải 2"));
        flushAndClear();
        PersonResponse back = personService.rename(uuid(1), new UpsertPersonRequest("Lý Hải"));
        flushAndClear();

        assertThat(reclaimed.slug()).isEqualTo("ly-hai");
        assertThat(renamed).isEqualTo(new PersonResponse(uuid(1), "LÝ HẢI", "ly-hai-2"));
        assertThat(numbered).isEqualTo(new PersonResponse(uuid(1), "Lý Hải 2", "ly-hai-2"));
        assertThat(back).isEqualTo(new PersonResponse(uuid(1), "Lý Hải", "ly-hai-2"));
        assertThat(personRepository.findAll()).extracting(Person::getId, Person::getSlug)
                .containsExactlyInAnyOrder(tuple(uuid(1), "ly-hai-2"), tuple(reclaimed.id(), "ly-hai"));
    }

    @Test
    void renameOfAMissingPersonIsNotFoundButAnInvalidBodyIsReportedFirst() {
        ApiException missing = catchThrowableOfType(
                ApiException.class, () -> personService.rename(uuid(99), new UpsertPersonRequest("Lý Hải")));

        assertThat(missing).isInstanceOf(PersonNotFoundException.class);
        assertThat(missing.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getCode()).isEqualTo("PERSON_NOT_FOUND");
        assertThat(missing.getMessage()).isEqualTo("Person not found");
        assertNameError(() -> personService.rename(uuid(99), new UpsertPersonRequest("")), "must not be blank");
    }

    // ---- search ----

    @Test
    void searchMatchesTheSlugNormalizedTermAsASubstringOrderedByNameThenId() {
        persist(1, "Nguyễn Văn A", "nguyen-van-a");
        persist(2, "Trần Nguyên", "tran-nguyen");
        persist(3, "Lê Văn B", "le-van-b");
        persist(4, "Nguyễn Văn A", "nguyen-van-a-2");
        flushAndClear();

        for (String term : List.of("nguyen", "NGUYỄN", " Nguyên ")) {
            assertThat(personService.search(term, 0, 20).getContent())
                    .as(term)
                    .extracting(PersonResponse::id)
                    .containsExactly(uuid(1), uuid(4), uuid(2));
        }
        assertThat(personService.search("văn b", 0, 20).getContent())
                .containsExactly(new PersonResponse(uuid(3), "Lê Văn B", "le-van-b"));
        assertThat(personService.search("kim", 0, 20)).isEmpty();
    }

    @Test
    void anAbsentOrEmptyTermListsEveryoneInPages() {
        for (int id = 1; id <= 5; id++) {
            persist(id, "Người " + (6 - id), "nguoi-" + (6 - id));
        }
        flushAndClear();

        for (String term : new String[] {null, "", "  ", "!!!"}) {
            Page<PersonResponse> first = personService.search(term, 0, 2);
            assertThat(first.getTotalElements()).isEqualTo(5);
            assertThat(first.getTotalPages()).isEqualTo(3);
            assertThat(first.getContent()).extracting(PersonResponse::id).containsExactly(uuid(5), uuid(4));
        }
        assertThat(personService.search(null, 2, 2).getContent()).extracting(PersonResponse::id).containsExactly(uuid(1));
    }

    @Test
    void searchRejectsATermLongerThanTheContractAllowsAndPagesOutsideTheRange() {
        assertThat(personService.search("😀".repeat(100), 0, 20)).isEmpty();
        CatalogValidationException invalid = catchThrowableOfType(
                CatalogValidationException.class, () -> personService.search("a".repeat(101), 0, 20));

        assertThat(invalid.getFieldErrors())
                .extracting(ApiFieldError::field, ApiFieldError::message)
                .containsExactly(tuple("q", "size must be between 0 and 100"));
        assertThatIllegalArgumentException().isThrownBy(() -> personService.search(null, -1, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> personService.search(null, 10_001, 20));
        assertThatIllegalArgumentException().isThrownBy(() -> personService.search(null, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> personService.search(null, 0, 101));
    }

    // ---- helpers ----

    /**
     * Statements that read {@code people}, lower-cased, in order. H2 renders the row lock as
     * {@code FOR UPDATE}; PostgreSQL would use {@code FOR NO KEY UPDATE}. The rename update itself
     * runs as plain JDBC ({@code PersonRenamingImpl}), which the inspector does not see.
     */
    private static List<String> peopleReads() {
        return RecordingStatementInspector.statements().stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.startsWith("select") && sql.contains(" from people "))
                .toList();
    }

    /**
     * A repository whose first slug lookup returns no slugs, as if it ran before another
     * transaction committed them.
     */
    private PersonRepository repositoryWithOneStaleSlugLookup() {
        PersonRepository stale = mock(PersonRepository.class, AdditionalAnswers.delegatesTo(personRepository));
        AtomicBoolean firstLookup = new AtomicBoolean(true);
        doAnswer(invocation -> firstLookup.getAndSet(false)
                ? List.of()
                : personRepository.findSlugsWithBase(invocation.getArgument(0)))
                .when(stale).findSlugsWithBase(anyString());
        return stale;
    }

    private static void assertNameError(Runnable call, String message) {
        CatalogValidationException invalid = catchThrowableOfType(CatalogValidationException.class, call::run);

        assertThat(invalid).as(message).isNotNull();
        assertThat(invalid.getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(invalid.getFieldErrors())
                .extracting(ApiFieldError::field, ApiFieldError::message)
                .containsExactly(tuple("name", message));
    }

    private Person persist(int id, String name, String slug) {
        Person person = new Person(uuid(id), name, slug);
        entityManager.persist(person);
        return person;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static UUID uuid(int value) {
        return new UUID(0L, value);
    }
}
