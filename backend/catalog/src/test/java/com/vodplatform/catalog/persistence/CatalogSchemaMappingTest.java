package com.vodplatform.catalog.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.vodplatform.catalog.config.CatalogConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ContextConfiguration;

/**
 * Checks the entity-generated H2 schema against the Flyway V1 catalog tables. PostgreSQL parity is
 * enforced at runtime by {@code spring.jpa.hibernate.ddl-auto=validate} in the Compose backend.
 */
@DataJpaTest
@ContextConfiguration(classes = CatalogConfiguration.class)
class CatalogSchemaMappingTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-01T08:00:00Z");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private PersonRepository personRepository;

    @Test
    void catalogConfigurationRegistersExactlyTheCatalogEntities() {
        Set<Class<?>> entityTypes = entityManager.getMetamodel().getEntities().stream()
                .map(EntityType::getJavaType)
                .collect(Collectors.toSet());

        assertThat(entityTypes).containsExactlyInAnyOrder(Movie.class, Genre.class, Person.class, MovieCredit.class);
    }

    @Test
    void columnsMatchTheV1CatalogTablesWithoutTheSearchVector() {
        List<Object[]> rows = nativeRows("""
                select lower(table_name), lower(column_name), data_type, character_maximum_length, is_nullable
                from information_schema.columns
                where table_schema = 'PUBLIC'
                """);
        Set<String> columns = rows.stream().map(CatalogSchemaMappingTest::describeColumn).collect(Collectors.toSet());

        // V1 declares status, maturity_rating and credit_role as varchar(20) with CHECK value lists;
        // H2 stores an @Enumerated(STRING) column as a native ENUM restricted to the same values.
        assertThat(columns).containsExactlyInAnyOrder(
                "movies.id uuid NOT NULL",
                "movies.title varchar(255) NOT NULL",
                "movies.slug varchar(255) NOT NULL",
                "movies.description text NOT NULL",
                "movies.release_year int NULL",
                "movies.maturity_rating enum NULL",
                "movies.poster_object_key varchar(512) NULL",
                "movies.status enum NOT NULL",
                "movies.created_at timestamptz NOT NULL",
                "movies.updated_at timestamptz NOT NULL",
                "genres.id uuid NOT NULL",
                "genres.name varchar(100) NOT NULL",
                "genres.slug varchar(120) NOT NULL",
                "movie_genres.movie_id uuid NOT NULL",
                "movie_genres.genre_id uuid NOT NULL",
                "people.id uuid NOT NULL",
                "people.name varchar(255) NOT NULL",
                "people.slug varchar(255) NOT NULL",
                "movie_credits.movie_id uuid NOT NULL",
                "movie_credits.person_id uuid NOT NULL",
                "movie_credits.credit_role enum NOT NULL",
                "movie_credits.sort_order int NULL"
        );
    }

    @Test
    void primaryKeysMatchV1() {
        assertThat(constraintColumns("PRIMARY KEY")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "movies", Set.of("id"),
                "genres", Set.of("id"),
                "people", Set.of("id"),
                "movie_genres", Set.of("movie_id", "genre_id"),
                "movie_credits", Set.of("movie_id", "person_id", "credit_role")
        ));
    }

    @Test
    void uniqueConstraintsUseTheV1Names() {
        List<Object[]> rows = nativeRows("""
                select lower(tc.table_name), lower(tc.constraint_name), lower(kcu.column_name)
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on kcu.constraint_schema = tc.constraint_schema and kcu.constraint_name = tc.constraint_name
                where tc.table_schema = 'PUBLIC' and tc.constraint_type = 'UNIQUE'
                """);
        Set<String> uniqueConstraints = rows.stream()
                .map(row -> row[0] + "." + row[1] + "(" + row[2] + ")")
                .collect(Collectors.toSet());

        assertThat(uniqueConstraints).containsExactlyInAnyOrder(
                "movies.ux_movies_slug(slug)",
                "genres.ux_genres_name(name)",
                "genres.ux_genres_slug(slug)",
                "people.ux_people_slug(slug)"
        );
    }

    @Test
    void enumValuesMatchTheV1CheckConstraints() {
        assertThat(Arrays.stream(MovieStatus.values()).map(Enum::name)).containsExactly("DRAFT", "PUBLISHED", "ARCHIVED");
        assertThat(Arrays.stream(CreditRole.values()).map(Enum::name)).containsExactly("ACTOR", "DIRECTOR");
        assertThat(Arrays.stream(MaturityRating.values()).map(Enum::name)).containsExactly("P", "K", "T13", "T16", "T18");
    }

    @Test
    void duplicateMovieSlugIsReportedAsUxMoviesSlug() {
        movieRepository.saveAndFlush(movie(1, "dat-rung-phuong-nam"));

        Throwable failure = catchThrowable(() -> movieRepository.saveAndFlush(movie(2, "dat-rung-phuong-nam")));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(CatalogConstraints.isViolationOf(failure, CatalogConstraints.MOVIES_SLUG)).isTrue();
        assertThat(CatalogConstraints.isViolationOf(failure, CatalogConstraints.PEOPLE_SLUG)).isFalse();
    }

    @Test
    void duplicateGenreNameIsReportedAsUxGenresName() {
        genreRepository.saveAndFlush(new Genre(uuid(11), "Hành Động", "hanh-dong"));

        Throwable failure = catchThrowable(() -> genreRepository.saveAndFlush(new Genre(uuid(12), "Hành Động", "hanh-dong-2")));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(CatalogConstraints.isViolationOf(failure, CatalogConstraints.GENRES_NAME)).isTrue();
        assertThat(CatalogConstraints.isViolationOf(failure, CatalogConstraints.GENRES_SLUG)).isFalse();
    }

    @Test
    void duplicateGenreSlugIsReportedAsUxGenresSlug() {
        genreRepository.saveAndFlush(new Genre(uuid(11), "Hài", "hai"));

        Throwable failure = catchThrowable(() -> genreRepository.saveAndFlush(new Genre(uuid(12), "Hai", "hai")));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(CatalogConstraints.isViolationOf(failure, CatalogConstraints.GENRES_SLUG)).isTrue();
        assertThat(CatalogConstraints.isViolationOf(failure, CatalogConstraints.GENRES_NAME)).isFalse();
    }

    @Test
    void duplicatePersonSlugIsReportedAsUxPeopleSlug() {
        personRepository.saveAndFlush(new Person(uuid(21), "Trấn Thành", "tran-thanh"));

        Throwable failure = catchThrowable(() -> personRepository.saveAndFlush(new Person(uuid(22), "Tran Thanh", "tran-thanh")));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(CatalogConstraints.isViolationOf(failure, CatalogConstraints.PEOPLE_SLUG)).isTrue();
        assertThat(CatalogConstraints.isViolationOf(failure, CatalogConstraints.MOVIES_SLUG)).isFalse();
    }

    @Test
    void peopleNamesAreNotUnique() {
        personRepository.saveAndFlush(new Person(uuid(21), "Ngô Thanh Vân", "ngo-thanh-van"));
        personRepository.saveAndFlush(new Person(uuid(22), "Ngô Thanh Vân", "ngo-thanh-van-2"));

        assertThat(personRepository.count()).isEqualTo(2);
    }

    private Map<String, Set<String>> constraintColumns(String constraintType) {
        List<Object[]> rows = nativeRows("""
                select lower(tc.table_name), lower(kcu.column_name)
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on kcu.constraint_schema = tc.constraint_schema and kcu.constraint_name = tc.constraint_name
                where tc.table_schema = 'PUBLIC' and tc.constraint_type = '%s'
                """.formatted(constraintType));
        Map<String, Set<String>> columnsByTable = new TreeMap<>();
        rows.forEach(row -> columnsByTable.computeIfAbsent((String) row[0], table -> new TreeSet<>()).add((String) row[1]));
        return columnsByTable;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> nativeRows(String sql) {
        return entityManager.createNativeQuery(sql).getResultList();
    }

    private static String describeColumn(Object[] row) {
        String type = switch ((String) row[2]) {
            case "UUID" -> "uuid";
            case "INTEGER" -> "int";
            case "TIMESTAMP WITH TIME ZONE" -> "timestamptz";
            case "CHARACTER VARYING" -> "varchar(" + row[3] + ")";
            case "CHARACTER LARGE OBJECT" -> "text";
            case "ENUM" -> "enum";
            default -> "unexpected " + row[2];
        };
        String nullability = "YES".equals(row[4]) ? "NULL" : "NOT NULL";
        return row[0] + "." + row[1] + " " + type + " " + nullability;
    }

    private static Movie movie(int id, String slug) {
        return new Movie(uuid(id), "Phim " + id, slug, "Mô tả", null, null, MovieStatus.DRAFT, CREATED_AT);
    }

    private static UUID uuid(int value) {
        return new UUID(0L, value);
    }
}
