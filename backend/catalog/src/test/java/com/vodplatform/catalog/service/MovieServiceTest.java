package com.vodplatform.catalog.service;

import static com.vodplatform.catalog.persistence.CreditRole.ACTOR;
import static com.vodplatform.catalog.persistence.CreditRole.DIRECTOR;
import static com.vodplatform.catalog.persistence.MovieStatus.ARCHIVED;
import static com.vodplatform.catalog.persistence.MovieStatus.DRAFT;
import static com.vodplatform.catalog.persistence.MovieStatus.PUBLISHED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.vodplatform.catalog.config.CatalogConfiguration;
import com.vodplatform.catalog.dto.CreditedPerson;
import com.vodplatform.catalog.dto.MovieCreditInput;
import com.vodplatform.catalog.dto.MovieDetail;
import com.vodplatform.catalog.dto.MovieSummary;
import com.vodplatform.catalog.exception.CatalogValidationException;
import com.vodplatform.catalog.exception.MovieNotFoundException;
import com.vodplatform.catalog.exception.MovieSlugConflictException;
import com.vodplatform.catalog.persistence.CreditRole;
import com.vodplatform.catalog.persistence.Genre;
import com.vodplatform.catalog.persistence.GenreRepository;
import com.vodplatform.catalog.persistence.MaturityRating;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieCredit;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.MovieSort;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.persistence.Person;
import com.vodplatform.catalog.persistence.PersonRepository;
import com.vodplatform.catalog.service.ValidatedMovie.GenreInput;
import com.vodplatform.catalog.text.SlugNormalizer;
import com.vodplatform.shared.error.ApiException;
import com.vodplatform.shared.error.ApiFieldError;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;

/**
 * Service behavior on the entity-generated H2 schema. PostgreSQL-only behavior (blocking on the
 * row lock, the concurrent slug race between two transactions) is covered by the catalog
 * PostgreSQL runtime smoke (Issue 3.5).
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.vodplatform.catalog.service.RecordingStatementInspector")
@ContextConfiguration(classes = {
        CatalogConfiguration.class,
        MovieService.class,
        MovieAssignmentResolver.class,
        MovieServiceTest.ClockConfiguration.class
})
class MovieServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");

    @Autowired
    private MovieService movieService;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(T0);
    }

    // ---- create ----

    @Test
    void createStoresTheMovieWithItsGenresAndCreditsAndReturnsTheDetail() {
        persist(new Genre(uuid(101), "Hành Động", "hanh-dong"));
        Person director = persist(person(201, "Nguyễn Quang Dũng"));
        Person actor = persist(person(202, "Tuấn Trần"));
        flushAndClear();

        MovieDetail created = movieService.create(new MovieRequestBuilder()
                .title(" Đất Rừng Phương Nam ")
                .description("Dòng 1\n\tDòng 2")
                .releaseYear(2023)
                .maturityRating(MaturityRating.T13)
                .genres(List.of("tâm   lý", "HÀNH ĐỘNG"))
                .people(List.of(credit(actor, ACTOR), credit(director, DIRECTOR), credit(director, ACTOR)))
                .status(PUBLISHED)
                .build());

        assertThat(created.id()).isNotNull();
        assertThat(created.title()).isEqualTo("Đất Rừng Phương Nam");
        assertThat(created.slug()).isEqualTo("dat-rung-phuong-nam");
        assertThat(created.description()).isEqualTo("Dòng 1\n\tDòng 2");
        assertThat(created.releaseYear()).isEqualTo(2023);
        assertThat(created.maturityRating()).isEqualTo(MaturityRating.T13);
        assertThat(created.status()).isEqualTo(PUBLISHED);
        // The existing genre keeps its stored name; the new one is stored with the normalized name.
        assertThat(created.genres()).containsExactly("Hành Động", "tâm lý");
        assertThat(created.people()).containsExactly(
                new CreditedPerson(uuid(202), "Tuấn Trần", ACTOR),
                new CreditedPerson(uuid(201), "Nguyễn Quang Dũng", DIRECTOR),
                new CreditedPerson(uuid(201), "Nguyễn Quang Dũng", ACTOR)
        );

        flushAndClear();
        Movie stored = movieRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getCreatedAt()).isEqualTo(T0);
        assertThat(stored.getUpdatedAt()).isEqualTo(T0);
        assertThat(stored.getGenres()).extracting(Genre::getSlug).containsExactlyInAnyOrder("hanh-dong", "tam-ly");
        assertThat(stored.getCredits())
                .extracting(entry -> entry.getPerson().getId(), MovieCredit::getRole, MovieCredit::getSortOrder)
                .containsExactly(tuple(uuid(202), ACTOR, 0), tuple(uuid(201), DIRECTOR, 1), tuple(uuid(201), ACTOR, 2));
        assertThat(genreRepository.findAll()).extracting(Genre::getSlug).containsExactlyInAnyOrder("hanh-dong", "tam-ly");
        assertThat(movieService.getMovie(created.id(), MovieVisibility.ANY_STATUS)).isEqualTo(created);
    }

    @Test
    void genresAreMatchedBySlugAcrossMovies() {
        MovieDetail first = movieService.create(new MovieRequestBuilder().slug("phim-1").genres(List.of("Hài")).build());
        MovieDetail second = movieService.create(new MovieRequestBuilder().slug("phim-2").genres(List.of(" hai ")).build());
        flushAndClear();

        assertThat(first.genres()).containsExactly("Hài");
        assertThat(second.genres()).containsExactly("Hài");
        assertThat(genreRepository.count()).isEqualTo(1);
        assertThat(countRows("movie_genres")).isEqualTo(2);
    }

    @Test
    void createRejectsASlugAnotherMovieUsesBeforeCreatingGenres() {
        persist(movie(1, "dat-rung", ARCHIVED));
        flushAndClear();

        MovieSlugConflictException conflict = catchThrowableOfType(
                MovieSlugConflictException.class,
                () -> movieService.create(new MovieRequestBuilder().slug("dat-rung").genres(List.of("Kinh Dị")).build())
        );

        assertSlugConflict(conflict);
        assertThat(genreRepository.findBySlug("kinh-di")).isEmpty();
        assertThat(movieRepository.count()).isEqualTo(1);
    }

    @Test
    void createReportsADuplicateSlugInsertedAfterThePreCheckAsAConflict() {
        persist(movie(1, "dat-rung", DRAFT));
        flushAndClear();

        // The pre-check misses the movie, as when another transaction commits it in between; the
        // insert then fails on ux_movies_slug, reported the way H2 names it.
        MovieSlugConflictException conflict = catchThrowableOfType(
                MovieSlugConflictException.class,
                () -> serviceWithStaleSlugCheck().create(new MovieRequestBuilder().slug("dat-rung").build())
        );

        assertSlugConflict(conflict);
    }

    @Test
    void createWritesTheMovieRowBeforeInsertingANewGenre() {
        RecordingStatementInspector.clear();

        movieService.create(new MovieRequestBuilder().slug("phim-moi").genres(List.of("Kinh Dị")).build());

        assertFirstRunsBefore("insert into movies ", "insert into genres ");
    }

    @Test
    void createReportsTheSlugConflictWhenTheOtherTransactionAlsoCreatedTheNewGenre() {
        // Two creates with the same new slug and the same new genre: the other transaction commits
        // both after this one's slug pre-check and genre lookup, so it tries to insert both again.
        persist(movie(1, "dat-rung", DRAFT));
        persist(new Genre(uuid(101), "Kinh Dị", "kinh-di"));
        flushAndClear();

        MovieSlugConflictException conflict = catchThrowableOfType(
                MovieSlugConflictException.class,
                () -> serviceWithStaleSlugCheckAndGenreLookup()
                        .create(new MovieRequestBuilder().slug("dat-rung").genres(List.of("Kinh Dị")).build())
        );

        assertSlugConflict(conflict);
    }

    static Stream<List<String>> newGenreRequestOrders() {
        return Stream.of(
                List.of("Tâm Lý", "Hài", "Kinh Dị", "Gia Đình"),
                List.of("Gia Đình", "Kinh Dị", "Hài", "Tâm Lý")
        );
    }

    @ParameterizedTest
    @MethodSource("newGenreRequestOrders")
    void newGenresAreInsertedInSlugOrderButReturnedInRequestOrder(List<String> names) {
        persist(new Genre(uuid(101), "Hài", "hai"));
        flushAndClear();
        GenreRepository genres = mock(GenreRepository.class, AdditionalAnswers.delegatesTo(genreRepository));
        List<GenreInput> inputs = names.stream().map(name -> new GenreInput(name, SlugNormalizer.slugify(name))).toList();

        List<Genre> resolved = new MovieAssignmentResolver(genres, personRepository).resolveGenres(inputs);

        // Every request inserts the same new genres in the same order, so two requests never wait
        // for each other's genres in opposite orders.
        ArgumentCaptor<String> insertedSlugs = ArgumentCaptor.forClass(String.class);
        verify(genres, times(3)).insertIfAbsent(any(), any(), insertedSlugs.capture());
        assertThat(insertedSlugs.getAllValues()).containsExactly("gia-dinh", "kinh-di", "tam-ly");
        assertThat(resolved).extracting(Genre::getSlug)
                .containsExactlyElementsOf(inputs.stream().map(GenreInput::slug).toList());
    }

    @Test
    void invalidRequestsAreRejectedBeforeAnythingIsWritten() {
        CatalogValidationException invalid = catchThrowableOfType(
                CatalogValidationException.class,
                () -> movieService.create(new MovieRequestBuilder().title("\u0000").genres(List.of("Mới")).build())
        );

        assertThat(invalid.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getFieldErrors())
                .extracting(ApiFieldError::field, ApiFieldError::message)
                .containsExactly(tuple("title", "must not contain control characters"));
        assertThat(movieRepository.count()).isZero();
        assertThat(genreRepository.count()).isZero();
    }

    // ---- credits ----

    @Test
    void anUnknownPersonIdIsRejectedOnItsField() {
        Person known = persist(person(201, "Trấn Thành"));
        flushAndClear();

        CatalogValidationException invalid = catchThrowableOfType(
                CatalogValidationException.class,
                () -> movieService.create(new MovieRequestBuilder()
                        .people(List.of(credit(known, DIRECTOR), new MovieCreditInput(uuid(299), "Trấn Thành", ACTOR)))
                        .build())
        );

        assertThat(invalid.getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(invalid.getFieldErrors())
                .extracting(ApiFieldError::field, ApiFieldError::message)
                .containsExactly(tuple("people[1].personId", "must identify an existing person"));
        assertThat(movieRepository.count()).isZero();
    }

    @Test
    void aPersonMayHaveEachRoleOnlyOnce() {
        Person person = persist(person(201, "Trấn Thành"));
        flushAndClear();

        CatalogValidationException invalid = catchThrowableOfType(
                CatalogValidationException.class,
                () -> movieService.create(new MovieRequestBuilder()
                        .people(List.of(credit(person, DIRECTOR), credit(person, ACTOR), credit(person, DIRECTOR)))
                        .build())
        );

        assertThat(invalid.getFieldErrors())
                .extracting(ApiFieldError::field, ApiFieldError::message)
                .containsExactly(tuple("people[2]", "must not repeat the person and role of another entry"));
    }

    @Test
    void aNameOnlyCreditWithoutAMatchCreatesThePersonWithAGeneratedSlug() {
        MovieDetail created = movieService.create(new MovieRequestBuilder()
                .people(List.of(byName("  Trấn   Thành ", ACTOR)))
                .build());
        flushAndClear();

        Person person = personRepository.findAll().getFirst();
        assertThat(personRepository.count()).isEqualTo(1);
        assertThat(person.getName()).isEqualTo("Trấn Thành");
        assertThat(person.getSlug()).isEqualTo("tran-thanh");
        assertThat(created.people()).containsExactly(new CreditedPerson(person.getId(), "Trấn Thành", ACTOR));
    }

    @Test
    void aNameOnlyCreditWithOneMatchReusesThatPersonIgnoringCaseButNotDiacritics() {
        persist(person(201, "Trấn Thành"));
        persist(new Person(uuid(202), "Tran Thanh", "tran-thanh-2"));
        flushAndClear();

        MovieDetail created = movieService.create(new MovieRequestBuilder()
                .people(List.of(byName("TRẤN THÀNH", ACTOR), byName("tran thanh", DIRECTOR)))
                .build());

        assertThat(created.people()).containsExactly(
                new CreditedPerson(uuid(201), "Trấn Thành", ACTOR),
                new CreditedPerson(uuid(202), "Tran Thanh", DIRECTOR)
        );
        assertThat(personRepository.count()).isEqualTo(2);
    }

    @Test
    void aNameOnlyCreditMatchingSeveralPeopleAsksForAPersonIdAndWritesNothing() {
        persist(new Person(uuid(201), "Ngô Thanh Vân", "ngo-thanh-van"));
        persist(new Person(uuid(202), "Ngô Thanh Vân", "ngo-thanh-van-2"));
        flushAndClear();

        CatalogValidationException invalid = catchThrowableOfType(
                CatalogValidationException.class,
                () -> movieService.create(new MovieRequestBuilder()
                        .people(List.of(byName("Lý Hải", DIRECTOR), byName("ngô thanh vân", ACTOR)))
                        .build())
        );

        assertThat(invalid.getFieldErrors())
                .extracting(ApiFieldError::field, ApiFieldError::message)
                .containsExactly(tuple("people[1].personId", "is required because more than one person has this name"));
        assertThat(movieRepository.count()).isZero();
        assertThat(personRepository.count()).isEqualTo(2);
    }

    @Test
    void namesEqualAfterNormalizationInOneRequestResolveToOnePersonCreatedOnce() {
        RecordingStatementInspector.clear();

        MovieDetail created = movieService.create(new MovieRequestBuilder()
                .people(List.of(byName("Lý Hải", DIRECTOR), byName(" lý   HẢI ", ACTOR)))
                .build());
        flushAndClear();

        Person person = personRepository.findAll().getFirst();
        assertThat(personRepository.count()).isEqualTo(1);
        // The person is created under the first entry's normalized name.
        assertThat(person.getName()).isEqualTo("Lý Hải");
        assertThat(person.getSlug()).isEqualTo("ly-hai");
        assertThat(created.people()).containsExactly(
                new CreditedPerson(person.getId(), "Lý Hải", DIRECTOR),
                new CreditedPerson(person.getId(), "Lý Hải", ACTOR)
        );
        assertThat(statementsStartingWith("insert into people ")).hasSize(1);
    }

    @Test
    void theDuplicateCreditCheckRunsAfterNamesAreResolved() {
        Person known = persist(person(201, "Trấn Thành"));
        flushAndClear();

        CatalogValidationException sameNewPerson = catchThrowableOfType(
                CatalogValidationException.class,
                () -> movieService.create(new MovieRequestBuilder()
                        .people(List.of(byName("Lý Hải", ACTOR), byName("LÝ HẢI", ACTOR)))
                        .build())
        );
        CatalogValidationException idAndName = catchThrowableOfType(
                CatalogValidationException.class,
                () -> movieService.create(new MovieRequestBuilder()
                        .people(List.of(credit(known, ACTOR), byName("trấn thành", DIRECTOR), byName("trấn thành", ACTOR)))
                        .build())
        );

        assertThat(sameNewPerson.getFieldErrors())
                .extracting(ApiFieldError::field, ApiFieldError::message)
                .containsExactly(tuple("people[1]", "must not repeat the person and role of another entry"));
        assertThat(idAndName.getFieldErrors())
                .extracting(ApiFieldError::field, ApiFieldError::message)
                .containsExactly(tuple("people[2]", "must not repeat the person and role of another entry"));
        assertThat(movieRepository.count()).isZero();
        assertThat(personRepository.count()).isEqualTo(1);
    }

    @Test
    void aPersonIdIdentifiesThePersonAndItsEntryNameIsIgnored() {
        Person known = persist(person(201, "Trấn Thành"));
        flushAndClear();

        MovieDetail created = movieService.create(new MovieRequestBuilder()
                .people(List.of(new MovieCreditInput(known.getId(), "Người Khác", ACTOR)))
                .build());

        assertThat(created.people()).containsExactly(new CreditedPerson(uuid(201), "Trấn Thành", ACTOR));
        assertThat(personRepository.count()).isEqualTo(1);
    }

    @Test
    void aNameOnlyCreditCreatesThePersonWithTheNextFreeSlugSuffix() {
        // Diacritics are significant for name matching, so "Lý Hải" is a new person, but its slug
        // base is taken.
        persist(new Person(uuid(201), "Ly Hai", "ly-hai"));
        persist(new Person(uuid(202), "Ly Hai", "ly-hai-2"));
        flushAndClear();

        MovieDetail created = movieService.create(new MovieRequestBuilder().people(List.of(byName("Lý Hải", DIRECTOR))).build());
        MovieDetail reused = movieService.create(new MovieRequestBuilder().slug("phim-2").people(List.of(byName("lý hải", ACTOR))).build());
        flushAndClear();

        UUID createdId = created.people().getFirst().personId();
        assertThat(personRepository.findById(createdId).orElseThrow().getSlug()).isEqualTo("ly-hai-3");
        // A later movie reuses the person a name-only credit created.
        assertThat(reused.people()).containsExactly(new CreditedPerson(createdId, "Lý Hải", ACTOR));
        assertThat(personRepository.count()).isEqualTo(3);
    }

    @Test
    void createWritesTheMovieRowBeforeCreatingAPerson() {
        RecordingStatementInspector.clear();

        movieService.create(new MovieRequestBuilder().slug("phim-moi").people(List.of(byName("Lý Hải", ACTOR))).build());

        assertFirstRunsBefore("insert into movies ", "insert into people ");
    }

    @Test
    void updateWritesTheMovieRowBeforeCreatingAPerson() {
        persist(movie(1, "phim-cu", PUBLISHED));
        flushAndClear();
        RecordingStatementInspector.clear();

        movieService.update(uuid(1), new MovieRequestBuilder().slug("phim-moi").people(List.of(byName("Lý Hải", ACTOR))).build());

        assertFirstRunsBefore("update movies ", "insert into people ");
    }

    @Test
    void aDuplicateSlugIsReportedBeforeAnyPersonIsCreated() {
        persist(movie(1, "dat-rung", DRAFT));
        flushAndClear();
        RecordingStatementInspector.clear();

        MovieSlugConflictException conflict = catchThrowableOfType(
                MovieSlugConflictException.class,
                () -> serviceWithStaleSlugCheck()
                        .create(new MovieRequestBuilder().slug("dat-rung").people(List.of(byName("Lý Hải", ACTOR))).build())
        );

        assertSlugConflict(conflict);
        assertThat(statementsStartingWith("insert into people ")).isEmpty();
    }

    @Test
    void reorderingCreditsGivenByNameChangesOnlyTheirSortOrder() {
        Person p1 = persist(person(201, "Người Một"));
        Person p2 = persist(person(202, "Người Hai"));
        Movie movie = movie(1, "phim", PUBLISHED);
        movie.addCredit(p1, ACTOR, 0);
        movie.addCredit(p2, DIRECTOR, 1);
        persist(movie);
        flushAndClear();

        movieService.update(uuid(1), new MovieRequestBuilder().slug("phim")
                .people(List.of(byName("người hai", DIRECTOR), credit(p1, DIRECTOR), byName("NGƯỜI MỘT", ACTOR)))
                .build());
        flushAndClear();

        assertThat(movieRepository.findById(uuid(1)).orElseThrow().getCredits())
                .extracting(entry -> entry.getPerson().getId(), MovieCredit::getRole, MovieCredit::getSortOrder)
                .containsExactly(tuple(uuid(202), DIRECTOR, 0), tuple(uuid(201), DIRECTOR, 1), tuple(uuid(201), ACTOR, 2));
        assertThat(countRows("movie_credits")).isEqualTo(3);
        assertThat(personRepository.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void newPeopleAreInsertedInSlugOrderWhileCreditsKeepTheRequestOrder(boolean reversed) {
        Person known = persist(person(201, "Ngô Thanh Vân"));
        flushAndClear();
        List<MovieCreditInput> credits = new ArrayList<>(List.of(
                byName("Trấn Thành", ACTOR),
                credit(known, DIRECTOR),
                byName("Lý Hải", DIRECTOR),
                byName("Ly Hai", ACTOR),
                byName("An Nhiên", ACTOR)
        ));
        if (reversed) {
            Collections.reverse(credits);
        }
        PersonRepository people = mock(PersonRepository.class, AdditionalAnswers.delegatesTo(personRepository));
        MovieService service = new MovieService(movieRepository, new MovieAssignmentResolver(genreRepository, people), clock);

        MovieDetail created = service.create(new MovieRequestBuilder().people(credits).build());
        flushAndClear();

        // Every request inserts the same new people in the same order: by slug base, then name, so
        // "Ly Hai" comes before "Lý Hải", which shares its base and gets the next suffix.
        ArgumentCaptor<String> insertedSlugs = ArgumentCaptor.forClass(String.class);
        verify(people, times(4)).insertIfSlugFree(any(), any(), insertedSlugs.capture());
        assertThat(insertedSlugs.getAllValues()).containsExactly("an-nhien", "ly-hai", "ly-hai-2", "tran-thanh");
        assertThat(personRepository.findAll()).extracting(Person::getName, Person::getSlug).containsExactlyInAnyOrder(
                tuple("Ngô Thanh Vân", "ngo-thanh-van"),
                tuple("An Nhiên", "an-nhien"),
                tuple("Ly Hai", "ly-hai"),
                tuple("Lý Hải", "ly-hai-2"),
                tuple("Trấn Thành", "tran-thanh")
        );
        // The credits, and their sort_order, still follow the request.
        List<Tuple> expected = new ArrayList<>();
        for (int index = 0; index < credits.size(); index++) {
            MovieCreditInput input = credits.get(index);
            expected.add(tuple(input.personId() != null ? known.getName() : input.name(), input.role(), index));
        }
        assertThat(movieRepository.findById(created.id()).orElseThrow().getCredits())
                .extracting(entry -> entry.getPerson().getName(), MovieCredit::getRole, MovieCredit::getSortOrder)
                .containsExactlyElementsOf(expected);
    }

    // ---- update ----

    @Test
    void updateFullyReplacesMetadataGenresAndCredits() {
        Genre kept = persist(new Genre(uuid(101), "Hài", "hai"));
        Genre removed = persist(new Genre(uuid(102), "Tâm Lý", "tam-ly"));
        Person p1 = persist(person(201, "Người Một"));
        Person p2 = persist(person(202, "Người Hai"));
        Person p3 = persist(person(203, "Người Ba"));
        Person p4 = persist(person(204, "Người Bốn"));
        Movie movie = new Movie(uuid(1), "Cũ", "phim-cu", "Mô tả cũ", 2020, MaturityRating.T16, PUBLISHED, T0);
        movie.addGenre(kept);
        movie.addGenre(removed);
        movie.addCredit(p1, ACTOR, 0);
        movie.addCredit(p2, DIRECTOR, 1);
        movie.addCredit(p3, ACTOR, 2);
        persist(movie);
        flushAndClear();
        clock.advance(Duration.ofHours(1));

        MovieDetail updated = movieService.update(uuid(1), new MovieRequestBuilder()
                .title("Mới")
                .slug("phim-moi")
                .description("Mô tả mới")
                .releaseYear(null)
                .maturityRating(null)
                .genres(List.of("HÀI", "Kinh Dị"))
                .people(List.of(credit(p2, DIRECTOR), credit(p4, ACTOR), credit(p1, ACTOR)))
                .status(DRAFT)
                .build());

        assertThat(updated.title()).isEqualTo("Mới");
        assertThat(updated.slug()).isEqualTo("phim-moi");
        assertThat(updated.description()).isEqualTo("Mô tả mới");
        assertThat(updated.releaseYear()).isNull();
        assertThat(updated.maturityRating()).isNull();
        assertThat(updated.status()).isEqualTo(DRAFT);
        assertThat(updated.genres()).containsExactly("Hài", "Kinh Dị");
        assertThat(updated.people()).extracting(CreditedPerson::personId, CreditedPerson::role)
                .containsExactly(tuple(uuid(202), DIRECTOR), tuple(uuid(204), ACTOR), tuple(uuid(201), ACTOR));

        flushAndClear();
        Movie stored = movieRepository.findById(uuid(1)).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("Mới");
        assertThat(stored.getReleaseYear()).isNull();
        assertThat(stored.getMaturityRating()).isNull();
        assertThat(stored.getCreatedAt()).isEqualTo(T0);
        assertThat(stored.getUpdatedAt()).isEqualTo(T0.plus(Duration.ofHours(1)));
        assertThat(stored.getGenres()).extracting(Genre::getSlug).containsExactlyInAnyOrder("hai", "kinh-di");
        assertThat(stored.getCredits())
                .extracting(entry -> entry.getPerson().getId(), MovieCredit::getRole, MovieCredit::getSortOrder)
                .containsExactly(tuple(uuid(202), DIRECTOR, 0), tuple(uuid(204), ACTOR, 1), tuple(uuid(201), ACTOR, 2));
        assertThat(countRows("movie_genres")).isEqualTo(2);
        assertThat(countRows("movie_credits")).isEqualTo(3);
        // Genres and people are shared catalog rows; replacing assignments never deletes them.
        assertThat(genreRepository.count()).isEqualTo(3);
        assertThat(personRepository.count()).isEqualTo(4);
    }

    @Test
    void reorderingTheSameCreditsChangesOnlyTheirSortOrder() {
        Person p1 = persist(person(201, "Người Một"));
        Person p2 = persist(person(202, "Người Hai"));
        Movie movie = movie(1, "phim", PUBLISHED);
        movie.addCredit(p1, ACTOR, 0);
        movie.addCredit(p2, DIRECTOR, 1);
        movie.addCredit(p1, DIRECTOR, 2);
        persist(movie);
        flushAndClear();
        List<MovieCreditInput> reversed = List.of(credit(p1, DIRECTOR), credit(p2, DIRECTOR), credit(p1, ACTOR));

        movieService.update(uuid(1), new MovieRequestBuilder().slug("phim").people(reversed).build());
        flushAndClear();
        movieService.update(uuid(1), new MovieRequestBuilder().slug("phim").people(reversed).build());
        flushAndClear();

        assertThat(movieRepository.findById(uuid(1)).orElseThrow().getCredits())
                .extracting(entry -> entry.getPerson().getId(), MovieCredit::getRole, MovieCredit::getSortOrder)
                .containsExactly(tuple(uuid(201), DIRECTOR, 0), tuple(uuid(202), DIRECTOR, 1), tuple(uuid(201), ACTOR, 2));
        assertThat(countRows("movie_credits")).isEqualTo(3);
    }

    @Test
    void anEmptyPeopleListRemovesEveryCredit() {
        Person person = persist(person(201, "Người Một"));
        Movie movie = movie(1, "phim", PUBLISHED);
        movie.addCredit(person, ACTOR, 0);
        persist(movie);
        flushAndClear();

        MovieDetail updated = movieService.update(uuid(1), new MovieRequestBuilder().slug("phim").people(List.of()).build());
        flushAndClear();

        assertThat(updated.people()).isEmpty();
        assertThat(countRows("movie_credits")).isZero();
        assertThat(personRepository.count()).isEqualTo(1);
    }

    @Test
    void updateKeepsTheMoviesOwnSlugButRejectsAnotherMoviesSlug() {
        persist(movie(1, "phim-1", PUBLISHED));
        persist(movie(2, "phim-2", DRAFT));
        flushAndClear();

        assertThat(movieService.update(uuid(1), new MovieRequestBuilder().slug("phim-1").title("Đổi tên").build()).title())
                .isEqualTo("Đổi tên");
        MovieSlugConflictException conflict = catchThrowableOfType(
                MovieSlugConflictException.class,
                () -> movieService.update(uuid(1), new MovieRequestBuilder().slug("phim-2").build())
        );

        assertSlugConflict(conflict);
    }

    @Test
    void updateReportsADuplicateSlugWrittenAfterThePreCheckAsAConflict() {
        persist(movie(1, "phim-1", PUBLISHED));
        persist(movie(2, "phim-2", DRAFT));
        flushAndClear();

        MovieSlugConflictException conflict = catchThrowableOfType(
                MovieSlugConflictException.class,
                () -> serviceWithStaleSlugCheck().update(uuid(1), new MovieRequestBuilder().slug("phim-2").build())
        );

        assertSlugConflict(conflict);
    }

    @Test
    void updateWritesTheMovieRowBeforeInsertingANewGenre() {
        persist(movie(1, "phim-cu", PUBLISHED));
        flushAndClear();
        RecordingStatementInspector.clear();

        movieService.update(uuid(1), new MovieRequestBuilder().slug("phim-moi").genres(List.of("Kinh Dị")).build());

        assertFirstRunsBefore("update movies ", "insert into genres ");
    }

    @Test
    void updateReportsTheSlugConflictWhenTheOtherTransactionAlsoCreatedTheNewGenre() {
        persist(movie(1, "phim-1", PUBLISHED));
        persist(movie(2, "phim-2", DRAFT));
        persist(new Genre(uuid(101), "Kinh Dị", "kinh-di"));
        flushAndClear();

        MovieSlugConflictException conflict = catchThrowableOfType(
                MovieSlugConflictException.class,
                () -> serviceWithStaleSlugCheckAndGenreLookup()
                        .update(uuid(1), new MovieRequestBuilder().slug("phim-2").genres(List.of("Kinh Dị")).build())
        );

        assertSlugConflict(conflict);
    }

    @Test
    void updateAndArchiveOfAMissingMovieAreNotFound() {
        assertMovieNotFound(catchThrowableOfType(
                ApiException.class, () -> movieService.update(uuid(99), new MovieRequestBuilder().build())));
        assertMovieNotFound(catchThrowableOfType(ApiException.class, () -> movieService.archive(uuid(99))));
    }

    @Test
    void updateAndArchiveLoadTheMovieWithARowLock() {
        persist(movie(1, "phim-1", PUBLISHED));
        persist(movie(2, "phim-2", PUBLISHED));
        flushAndClear();

        RecordingStatementInspector.clear();
        movieService.update(uuid(1), new MovieRequestBuilder().slug("phim-1").build());
        List<String> updateLocks = lockingMovieSelects();
        RecordingStatementInspector.clear();
        movieService.archive(uuid(2));
        List<String> archiveLocks = lockingMovieSelects();

        assertThat(updateLocks).hasSize(1);
        assertThat(archiveLocks).hasSize(1);
    }

    @Test
    void readsDoNotLockTheMovieRow() {
        persist(movie(1, "phim-1", PUBLISHED));
        flushAndClear();

        RecordingStatementInspector.clear();
        movieService.getMovie(uuid(1), MovieVisibility.PUBLISHED_ONLY);
        movieService.listPublished(0, 10, MovieSort.LATEST);
        movieService.listForAdmin(null, 0, 10, MovieSort.TITLE);

        assertThat(RecordingStatementInspector.statements()).isNotEmpty();
        assertThat(lockingMovieSelects()).isEmpty();
    }

    @Test
    void updateRestoresAnArchivedMovie() {
        persist(movie(1, "phim", ARCHIVED));
        flushAndClear();

        movieService.update(uuid(1), new MovieRequestBuilder().slug("phim").status(PUBLISHED).build());
        flushAndClear();

        assertThat(movieService.getMovie(uuid(1), MovieVisibility.PUBLISHED_ONLY).status()).isEqualTo(PUBLISHED);
    }

    // ---- archive ----

    @Test
    void archiveSoftDeletesAndIsIdempotent() {
        Genre genre = persist(new Genre(uuid(101), "Hài", "hai"));
        Person person = persist(person(201, "Người Một"));
        Movie movie = movie(1, "phim", PUBLISHED);
        movie.addGenre(genre);
        movie.addCredit(person, ACTOR, 0);
        persist(movie);
        flushAndClear();
        Instant archivedAt = T0.plus(Duration.ofHours(1));
        clock.set(archivedAt);

        movieService.archive(uuid(1));
        flushAndClear();
        clock.advance(Duration.ofHours(1));
        movieService.archive(uuid(1));
        flushAndClear();

        Movie stored = movieRepository.findById(uuid(1)).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ARCHIVED);
        assertThat(stored.getUpdatedAt()).isEqualTo(archivedAt);
        assertThat(stored.getGenres()).hasSize(1);
        assertThat(stored.getCredits()).hasSize(1);
        assertThat(movieService.getMovie(uuid(1), MovieVisibility.ANY_STATUS).status()).isEqualTo(ARCHIVED);
    }

    // ---- visibility ----

    @Test
    void nonPublishedMoviesAreNotFoundExactlyLikeMissingOnesWithoutAdminVisibility() {
        persist(movie(1, "cong-khai", PUBLISHED));
        persist(movie(2, "nhap", DRAFT));
        persist(movie(3, "luu-tru", ARCHIVED));
        flushAndClear();

        assertThat(movieService.getMovie(uuid(1), MovieVisibility.PUBLISHED_ONLY).slug()).isEqualTo("cong-khai");
        ApiException missing = catchThrowableOfType(
                ApiException.class, () -> movieService.getMovie(uuid(99), MovieVisibility.PUBLISHED_ONLY));
        ApiException draft = catchThrowableOfType(
                ApiException.class, () -> movieService.getMovie(uuid(2), MovieVisibility.PUBLISHED_ONLY));
        ApiException archived = catchThrowableOfType(
                ApiException.class, () -> movieService.getMovie(uuid(3), MovieVisibility.PUBLISHED_ONLY));

        assertMovieNotFound(missing);
        for (ApiException hidden : List.of(draft, archived)) {
            assertThat(hidden).isInstanceOf(MovieNotFoundException.class);
            assertThat(hidden)
                    .extracting(ApiException::getStatus, ApiException::getCode, ApiException::getMessage, ApiException::getFieldErrors)
                    .containsExactly(missing.getStatus(), missing.getCode(), missing.getMessage(), missing.getFieldErrors());
        }
    }

    @Test
    void adminVisibilityIncludesEveryStatusButNotMissingMovies() {
        persist(movie(1, "cong-khai", PUBLISHED));
        persist(movie(2, "nhap", DRAFT));
        persist(movie(3, "luu-tru", ARCHIVED));
        flushAndClear();

        assertThat(List.of(uuid(1), uuid(2), uuid(3)))
                .extracting(id -> movieService.getMovie(id, MovieVisibility.ANY_STATUS).status())
                .containsExactly(PUBLISHED, DRAFT, ARCHIVED);
        assertMovieNotFound(catchThrowableOfType(
                ApiException.class, () -> movieService.getMovie(uuid(99), MovieVisibility.ANY_STATUS)));
    }

    @Test
    void theVisibilityRuleAdmitsOnlyPublishedMoviesUnlessEveryStatusIsAllowed() {
        assertThat(MovieVisibility.PUBLISHED_ONLY.includes(PUBLISHED)).isTrue();
        assertThat(MovieVisibility.PUBLISHED_ONLY.includes(DRAFT)).isFalse();
        assertThat(MovieVisibility.PUBLISHED_ONLY.includes(ARCHIVED)).isFalse();
        assertThat(List.of(MovieStatus.values())).allMatch(MovieVisibility.ANY_STATUS::includes);
    }

    // ---- lists ----

    @Test
    void publishedListContainsOnlyPublishedMoviesInStablePages() {
        Genre comedy = persist(new Genre(uuid(101), "Hài", "hai"));
        Genre action = persist(new Genre(uuid(102), "Hành Động", "hanh-dong"));
        for (int id = 1; id <= 3; id++) {
            Movie movie = movie(id, "phim-" + id, PUBLISHED);
            movie.addGenre(action);
            movie.addGenre(comedy);
            persist(movie);
        }
        persist(movie(4, "phim-4", PUBLISHED, T0.plusSeconds(10)));
        persist(movie(5, "nhap", DRAFT, T0.plusSeconds(20)));
        persist(movie(6, "luu-tru", ARCHIVED, T0.plusSeconds(30)));
        flushAndClear();

        List<UUID> ids = new ArrayList<>();
        Page<MovieSummary> first = movieService.listPublished(0, 3, MovieSort.LATEST);
        first.forEach(summary -> ids.add(summary.id()));
        movieService.listPublished(1, 3, MovieSort.LATEST).forEach(summary -> ids.add(summary.id()));

        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getTotalPages()).isEqualTo(2);
        // Equal creation times fall back to the id, descending.
        assertThat(ids).containsExactly(uuid(4), uuid(3), uuid(2), uuid(1));
        assertThat(first.getContent().get(1)).isEqualTo(new MovieSummary(
                uuid(3), "Phim 3", "phim-3", "Mô tả", null, List.of("Hài", "Hành Động"), PUBLISHED));
    }

    @Test
    void titleOrderIgnoresCaseAndBreaksTiesById() {
        persist(movie(1, "b", PUBLISHED, T0, "banana"));
        persist(movie(2, "a1", PUBLISHED, T0, "apple"));
        persist(movie(3, "a2", PUBLISHED, T0, "Apple"));
        persist(movie(4, "c", PUBLISHED, T0, "Cherry"));
        flushAndClear();

        assertThat(movieService.listPublished(0, 10, MovieSort.TITLE).getContent())
                .extracting(MovieSummary::id)
                .containsExactly(uuid(2), uuid(3), uuid(1), uuid(4));
    }

    @Test
    void adminListFiltersByStatusOrListsEveryStatus() {
        persist(movie(1, "nhap-1", DRAFT, T0.plusSeconds(1)));
        persist(movie(2, "cong-khai", PUBLISHED, T0.plusSeconds(2)));
        persist(movie(3, "luu-tru", ARCHIVED, T0.plusSeconds(3)));
        persist(movie(4, "nhap-2", DRAFT, T0.plusSeconds(4)));
        flushAndClear();

        assertThat(movieService.listForAdmin(DRAFT, 0, 10, MovieSort.LATEST).getContent())
                .extracting(MovieSummary::id).containsExactly(uuid(4), uuid(1));
        assertThat(movieService.listForAdmin(ARCHIVED, 0, 10, MovieSort.LATEST).getContent())
                .extracting(MovieSummary::status).containsExactly(ARCHIVED);
        Page<MovieSummary> everyStatus = movieService.listForAdmin(null, 0, 3, MovieSort.LATEST);
        assertThat(everyStatus.getTotalElements()).isEqualTo(4);
        assertThat(everyStatus.getContent()).extracting(MovieSummary::id).containsExactly(uuid(4), uuid(3), uuid(2));
    }

    @Test
    void listsRejectPagesOutsideTheContractRange() {
        assertThat(movieService.listPublished(MovieService.MAX_PAGE, MovieService.MAX_PAGE_SIZE, MovieSort.TITLE)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> movieService.listPublished(-1, 20, MovieSort.LATEST));
        assertThatIllegalArgumentException().isThrownBy(() -> movieService.listPublished(10_001, 20, MovieSort.LATEST));
        assertThatIllegalArgumentException().isThrownBy(() -> movieService.listPublished(0, 0, MovieSort.LATEST));
        assertThatIllegalArgumentException().isThrownBy(() -> movieService.listForAdmin(null, 0, 101, MovieSort.LATEST));
    }

    // ---- helpers ----

    /**
     * Statements that select from {@code movies} with a row lock. H2 renders the pessimistic write
     * lock as {@code FOR UPDATE}; PostgreSQL would use {@code FOR NO KEY UPDATE}.
     */
    private static List<String> lockingMovieSelects() {
        return RecordingStatementInspector.statements().stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.startsWith("select") && sql.contains(" from movies ") && sql.contains(" for update"))
                .toList();
    }

    /**
     * Asserts that the first statement starting with {@code first} runs before the first statement
     * starting with {@code then}, and that both run.
     */
    private static void assertFirstRunsBefore(String first, String then) {
        List<String> statements = RecordingStatementInspector.statements().stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .toList();
        int firstIndex = indexOfFirstStartingWith(statements, first);
        int thenIndex = indexOfFirstStartingWith(statements, then);

        assertThat(firstIndex).as("'%s' in %s", first, statements).isNotNegative();
        assertThat(thenIndex).as("'%s' in %s", then, statements).isNotNegative();
        assertThat(firstIndex).as("'%s' before '%s' in %s", first, then, statements).isLessThan(thenIndex);
    }

    private static List<String> statementsStartingWith(String prefix) {
        return RecordingStatementInspector.statements().stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.startsWith(prefix))
                .toList();
    }

    private static int indexOfFirstStartingWith(List<String> statements, String prefix) {
        for (int index = 0; index < statements.size(); index++) {
            if (statements.get(index).startsWith(prefix)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * A service whose slug pre-checks always pass, so a duplicate slug reaches the database.
     */
    private MovieService serviceWithStaleSlugCheck() {
        return serviceWithStaleSlugCheck(genreRepository);
    }

    /**
     * A service whose slug pre-checks always pass and whose genre lookup finds no genre, so a
     * duplicate slug and a duplicate genre both reach the database.
     */
    private MovieService serviceWithStaleSlugCheckAndGenreLookup() {
        GenreRepository staleGenres = mock(GenreRepository.class, AdditionalAnswers.delegatesTo(genreRepository));
        doReturn(List.of()).when(staleGenres).findAllBySlugIn(any());
        return serviceWithStaleSlugCheck(staleGenres);
    }

    private MovieService serviceWithStaleSlugCheck(GenreRepository genres) {
        MovieRepository stale = mock(MovieRepository.class, AdditionalAnswers.delegatesTo(movieRepository));
        doReturn(false).when(stale).existsBySlug(anyString());
        doReturn(false).when(stale).existsBySlugAndIdNot(anyString(), any());
        return new MovieService(stale, new MovieAssignmentResolver(genres, personRepository), clock);
    }

    private static void assertSlugConflict(MovieSlugConflictException conflict) {
        assertThat(conflict).isNotNull();
        assertThat(conflict.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getCode()).isEqualTo("MOVIE_SLUG_CONFLICT");
    }

    private static void assertMovieNotFound(ApiException exception) {
        assertThat(exception).isInstanceOf(MovieNotFoundException.class);
        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.getCode()).isEqualTo("MOVIE_NOT_FOUND");
        assertThat(exception.getMessage()).isEqualTo("Movie not found");
        assertThat(exception.getFieldErrors()).isEmpty();
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private long countRows(String table) {
        return ((Number) entityManager.createNativeQuery("select count(*) from " + table).getSingleResult()).longValue();
    }

    private static MovieCreditInput credit(Person person, CreditRole role) {
        return new MovieCreditInput(person.getId(), null, role);
    }

    private static MovieCreditInput byName(String name, CreditRole role) {
        return new MovieCreditInput(null, name, role);
    }

    private static Person person(int id, String name) {
        return new Person(uuid(id), name, SlugNormalizer.personSlugBase(name));
    }

    private static Movie movie(int id, String slug, MovieStatus status) {
        return movie(id, slug, status, T0);
    }

    private static Movie movie(int id, String slug, MovieStatus status, Instant createdAt) {
        return movie(id, slug, status, createdAt, "Phim " + id);
    }

    private static Movie movie(int id, String slug, MovieStatus status, Instant createdAt, String title) {
        return new Movie(uuid(id), title, slug, "Mô tả", null, null, status, createdAt);
    }

    private static UUID uuid(int value) {
        return new UUID(0L, value);
    }

    @Configuration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        MutableClock clock() {
            return new MutableClock(T0);
        }
    }
}
