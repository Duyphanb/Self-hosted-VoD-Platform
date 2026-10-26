package com.vodplatform.catalog.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import com.vodplatform.catalog.config.CatalogConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = CatalogConfiguration.class)
class MovieRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsAMovieWithGenresAndCreditsUsingTheV1Mapping() {
        Genre action = persist(new Genre(uuid(101), "Hành Động", "hanh-dong"));
        Genre drama = persist(new Genre(uuid(102), "Tâm Lý", "tam-ly"));
        Person director = persist(new Person(uuid(201), "Nguyễn Quang Dũng", "nguyen-quang-dung"));
        Person actor = persist(new Person(uuid(202), "Tuấn Trần", "tuan-tran"));
        String description = "Dòng 1\r\n\tDòng 2\n" + "ạ".repeat(5000);
        Movie movie = new Movie(
                uuid(1),
                "Đất Rừng Phương Nam",
                "dat-rung-phuong-nam",
                description,
                2023,
                MaturityRating.T13,
                MovieStatus.PUBLISHED,
                T0
        );
        movie.addGenre(action);
        movie.addGenre(drama);
        movie.addGenre(action);
        movie.addCredit(actor, CreditRole.ACTOR, 1);
        movie.addCredit(director, CreditRole.DIRECTOR, 0);
        movie.addCredit(director, CreditRole.ACTOR, 2);

        movieRepository.saveAndFlush(movie);
        entityManager.clear();

        Movie loaded = movieRepository.findById(uuid(1)).orElseThrow();
        assertThat(loaded.getTitle()).isEqualTo("Đất Rừng Phương Nam");
        assertThat(loaded.getSlug()).isEqualTo("dat-rung-phuong-nam");
        assertThat(loaded.getDescription()).isEqualTo(description);
        assertThat(loaded.getReleaseYear()).isEqualTo(2023);
        assertThat(loaded.getMaturityRating()).isEqualTo(MaturityRating.T13);
        assertThat(loaded.getPosterObjectKey()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(MovieStatus.PUBLISHED);
        assertThat(loaded.getCreatedAt()).isEqualTo(T0);
        assertThat(loaded.getUpdatedAt()).isEqualTo(T0);
        assertThat(loaded.getGenres()).extracting(Genre::getSlug).containsExactlyInAnyOrder("hanh-dong", "tam-ly");
        assertThat(loaded.getCredits())
                .extracting(credit -> credit.getPerson().getName(), MovieCredit::getRole, MovieCredit::getSortOrder)
                .containsExactly(
                        tuple("Nguyễn Quang Dũng", CreditRole.DIRECTOR, 0),
                        tuple("Tuấn Trần", CreditRole.ACTOR, 1),
                        tuple("Nguyễn Quang Dũng", CreditRole.ACTOR, 2)
                );
        assertThat(countRows("movie_genres")).isEqualTo(2);
        assertThat(countRows("movie_credits")).isEqualTo(3);
    }

    @Test
    void optionalMovieFieldsMayBeNull() {
        movieRepository.saveAndFlush(new Movie(uuid(1), "Phim", "phim", "Mô tả", null, null, MovieStatus.DRAFT, T0));
        entityManager.clear();

        Movie loaded = movieRepository.findById(uuid(1)).orElseThrow();
        assertThat(loaded.getReleaseYear()).isNull();
        assertThat(loaded.getMaturityRating()).isNull();
        assertThat(loaded.getGenres()).isEmpty();
        assertThat(loaded.getCredits()).isEmpty();
    }

    @Test
    void aPersonHasEachCreditRoleAtMostOncePerMovie() {
        Person person = new Person(uuid(201), "Trấn Thành", "tran-thanh");
        Movie movie = new Movie(uuid(1), "Bố Già", "bo-gia", "Mô tả", 2021, null, MovieStatus.DRAFT, T0);
        movie.addCredit(person, CreditRole.DIRECTOR, 0);

        assertThatIllegalArgumentException().isThrownBy(() -> movie.addCredit(person, CreditRole.DIRECTOR, 1));
        assertThat(movie.getCredits()).hasSize(1);
    }

    @Test
    void slugChecksDistinguishTheMovieItselfFromOtherMovies() {
        persist(movie(1, "Phim A", MovieStatus.DRAFT, T0));
        persist(movie(2, "Phim B", MovieStatus.ARCHIVED, T0));

        assertThat(movieRepository.existsBySlug("phim-1")).isTrue();
        assertThat(movieRepository.existsBySlug("phim-2")).isTrue();
        assertThat(movieRepository.existsBySlug("phim-3")).isFalse();
        assertThat(movieRepository.existsBySlugAndIdNot("phim-1", uuid(1))).isFalse();
        assertThat(movieRepository.existsBySlugAndIdNot("phim-1", uuid(2))).isTrue();
        assertThat(movieRepository.existsBySlugAndIdNot("phim-3", uuid(1))).isFalse();
    }

    @Test
    void publishedListContainsOnlyPublishedMoviesAndPaginates() {
        for (int id = 1; id <= 5; id++) {
            persist(movie(id, "Phim " + id, MovieStatus.PUBLISHED, T0.plusSeconds(id)));
        }
        persist(movie(6, "Nháp", MovieStatus.DRAFT, T0.plusSeconds(60)));
        persist(movie(7, "Lưu trữ", MovieStatus.ARCHIVED, T0.plusSeconds(70)));
        flushAndClear();

        Page<Movie> first = movieRepository.findPublished(null, null, PageRequest.of(0, 2, MovieSort.LATEST.sort()));
        Page<Movie> last = movieRepository.findPublished(null, null, PageRequest.of(2, 2, MovieSort.LATEST.sort()));

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(first.getContent()).extracting(Movie::getId).containsExactly(uuid(5), uuid(4));
        assertThat(last.getContent()).extracting(Movie::getId).containsExactly(uuid(1));
        assertThat(movieRepository.findPublished(null, null, PageRequest.of(3, 2)).getContent()).isEmpty();
    }

    @Test
    void publishedListFiltersByGenreSlugAndReleaseYear() {
        Genre action = persist(new Genre(uuid(101), "Hành Động", "hanh-dong"));
        Genre comedy = persist(new Genre(uuid(102), "Hài", "hai"));
        Movie both = movie(1, "Cả hai", MovieStatus.PUBLISHED, T0.plusSeconds(1), 2023);
        both.addGenre(action);
        both.addGenre(comedy);
        Movie actionOnly = movie(2, "Hành động", MovieStatus.PUBLISHED, T0.plusSeconds(2), 2024);
        actionOnly.addGenre(action);
        Movie draftAction = movie(3, "Nháp", MovieStatus.DRAFT, T0.plusSeconds(3), 2024);
        draftAction.addGenre(action);
        Movie comedyOnly = movie(4, "Hài", MovieStatus.PUBLISHED, T0.plusSeconds(4), 2024);
        comedyOnly.addGenre(comedy);
        List.of(both, actionOnly, draftAction, comedyOnly).forEach(this::persist);
        flushAndClear();
        PageRequest page = PageRequest.of(0, 10, MovieSort.LATEST.sort());

        Page<Movie> actionMovies = movieRepository.findPublished("hanh-dong", null, page);
        assertThat(actionMovies.getContent()).extracting(Movie::getId).containsExactly(uuid(2), uuid(1));
        assertThat(actionMovies.getTotalElements()).isEqualTo(2);
        assertThat(movieRepository.findPublished("hai", null, page).getContent())
                .extracting(Movie::getId).containsExactly(uuid(4), uuid(1));
        assertThat(movieRepository.findPublished(null, 2024, page).getContent())
                .extracting(Movie::getId).containsExactly(uuid(4), uuid(2));
        assertThat(movieRepository.findPublished("hanh-dong", 2024, page).getContent())
                .extracting(Movie::getId).containsExactly(uuid(2));
        Page<Movie> unknownGenre = movieRepository.findPublished("kinh-di", null, page);
        assertThat(unknownGenre.getContent()).isEmpty();
        assertThat(unknownGenre.getTotalElements()).isZero();
    }

    @Test
    void movieInSeveralGenresIsCountedOnceWhenFilteringByGenre() {
        Genre action = persist(new Genre(uuid(101), "Hành Động", "hanh-dong"));
        Genre comedy = persist(new Genre(uuid(102), "Hài", "hai"));
        Genre drama = persist(new Genre(uuid(103), "Tâm Lý", "tam-ly"));
        Movie severalGenres = movie(1, "A nhiều thể loại", MovieStatus.PUBLISHED, T0, 2024);
        severalGenres.addGenre(action);
        severalGenres.addGenre(comedy);
        severalGenres.addGenre(drama);
        persist(severalGenres);
        Movie comedyOnly = movie(2, "B chỉ hài", MovieStatus.PUBLISHED, T0, 2024);
        comedyOnly.addGenre(comedy);
        persist(comedyOnly);
        flushAndClear();

        // Page size 1 with two matches forces Spring Data to run the count query.
        Page<Movie> first = movieRepository.findPublished("hai", null, PageRequest.of(0, 1, MovieSort.TITLE.sort()));
        Page<Movie> second = movieRepository.findPublished("hai", null, PageRequest.of(1, 1, MovieSort.TITLE.sort()));

        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(Movie::getId).containsExactly(uuid(1));
        assertThat(second.getContent()).extracting(Movie::getId).containsExactly(uuid(2));
    }

    @Test
    void addingAnotherInstanceOfAnAssignedGenreDoesNotDuplicateTheAssignment() {
        Genre comedy = persist(new Genre(uuid(102), "Hài", "hai"));
        flushAndClear();
        Movie movie = movie(1, "Một phim", MovieStatus.PUBLISHED, T0, 2024);
        movie.addGenre(comedy);
        movie.addGenre(new Genre(uuid(102), "Hài", "hai"));
        assertThat(movie.getGenres()).hasSize(1);

        persist(movie);
        flushAndClear();

        assertThat(movieRepository.findPublished("hai", null, PageRequest.of(0, 10, MovieSort.TITLE.sort())).getTotalElements())
                .isEqualTo(1);
    }

    @Test
    void latestOrderBreaksCreationTimeTiesByIdDescending() {
        persist(movie(1, "A", MovieStatus.PUBLISHED, T0));
        persist(movie(2, "B", MovieStatus.PUBLISHED, T0));
        persist(movie(3, "C", MovieStatus.PUBLISHED, T0.plusSeconds(1)));
        persist(movie(4, "D", MovieStatus.PUBLISHED, T0));
        flushAndClear();

        Page<Movie> page = movieRepository.findPublished(null, null, PageRequest.of(0, 10, MovieSort.LATEST.sort()));

        assertThat(page.getContent()).extracting(Movie::getId).containsExactly(uuid(3), uuid(4), uuid(2), uuid(1));
    }

    @Test
    void tiedPagesNeitherRepeatNorSkipMovies() {
        for (int id = 1; id <= 7; id++) {
            persist(movie(id, "Cùng tên", MovieStatus.PUBLISHED, T0));
        }
        flushAndClear();

        List<UUID> latest = new ArrayList<>();
        List<UUID> title = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            movieRepository.findPublished(null, null, PageRequest.of(page, 2, MovieSort.LATEST.sort()))
                    .forEach(movie -> latest.add(movie.getId()));
            movieRepository.findPublished(null, null, PageRequest.of(page, 2, MovieSort.TITLE.sort()))
                    .forEach(movie -> title.add(movie.getId()));
        }

        assertThat(latest).containsExactly(uuid(7), uuid(6), uuid(5), uuid(4), uuid(3), uuid(2), uuid(1));
        assertThat(title).containsExactly(uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), uuid(6), uuid(7));
    }

    @Test
    void titleOrderIgnoresCaseAndBreaksTiesByIdAscending() {
        persist(movie(1, "banana", MovieStatus.PUBLISHED, T0));
        persist(movie(2, "apple", MovieStatus.PUBLISHED, T0));
        persist(movie(3, "Apple", MovieStatus.PUBLISHED, T0));
        persist(movie(4, "Cherry", MovieStatus.PUBLISHED, T0));
        flushAndClear();

        Page<Movie> page = movieRepository.findPublished(null, null, PageRequest.of(0, 10, MovieSort.TITLE.sort()));

        // Case-sensitive ordering would put "Apple" and "Cherry" before "apple" and "banana".
        assertThat(page.getContent()).extracting(Movie::getId).containsExactly(uuid(2), uuid(3), uuid(1), uuid(4));
    }

    @Test
    void adminListFiltersByStatusOrListsEveryStatus() {
        persist(movie(1, "Nháp 1", MovieStatus.DRAFT, T0.plusSeconds(1)));
        persist(movie(2, "Công khai", MovieStatus.PUBLISHED, T0.plusSeconds(2)));
        persist(movie(3, "Lưu trữ", MovieStatus.ARCHIVED, T0.plusSeconds(3)));
        persist(movie(4, "Nháp 2", MovieStatus.DRAFT, T0.plusSeconds(4)));
        flushAndClear();
        PageRequest page = PageRequest.of(0, 10, MovieSort.LATEST.sort());

        assertThat(movieRepository.findByStatus(MovieStatus.DRAFT, page).getContent())
                .extracting(Movie::getId).containsExactly(uuid(4), uuid(1));
        assertThat(movieRepository.findByStatus(MovieStatus.ARCHIVED, page).getContent())
                .extracting(Movie::getId).containsExactly(uuid(3));
        Page<Movie> everyStatus = movieRepository.findAll(PageRequest.of(0, 3, MovieSort.LATEST.sort()));
        assertThat(everyStatus.getTotalElements()).isEqualTo(4);
        assertThat(everyStatus.getContent()).extracting(Movie::getId).containsExactly(uuid(4), uuid(3), uuid(2));
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

    private static Movie movie(int id, String title, MovieStatus status, Instant createdAt) {
        return movie(id, title, status, createdAt, null);
    }

    private static Movie movie(int id, String title, MovieStatus status, Instant createdAt, Integer releaseYear) {
        return new Movie(uuid(id), title, "phim-" + id, "Mô tả", releaseYear, null, status, createdAt);
    }

    private static UUID uuid(int value) {
        return new UUID(0L, value);
    }
}
