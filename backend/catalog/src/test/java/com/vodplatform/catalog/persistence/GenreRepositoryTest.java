package com.vodplatform.catalog.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.catalog.config.CatalogConfiguration;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = CatalogConfiguration.class)
class GenreRepositoryTest {

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void createGenres() {
        entityManager.persist(new Genre(uuid(3), "Drama", "drama"));
        entityManager.persist(new Genre(uuid(1), "Comedy", "comedy"));
        entityManager.persist(new Genre(uuid(2), "Action", "action"));
        entityManager.persist(new Genre(uuid(4), "Hành Động Hài", "hanh-dong-hai"));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void findsAGenreBySlug() {
        Genre genre = genreRepository.findBySlug("hanh-dong-hai").orElseThrow();

        assertThat(genre.getId()).isEqualTo(uuid(4));
        assertThat(genre.getName()).isEqualTo("Hành Động Hài");
        assertThat(genreRepository.findBySlug("kinh-di")).isEmpty();
        assertThat(genreRepository.findBySlug("Hành Động Hài")).isEmpty();
    }

    @Test
    void resolvesSeveralSlugsInOneQuery() {
        List<Genre> genres = genreRepository.findAllBySlugIn(Set.of("comedy", "drama", "kinh-di"));

        assertThat(genres).extracting(Genre::getId).containsExactlyInAnyOrder(uuid(1), uuid(3));
        assertThat(genreRepository.findAllBySlugIn(Set.of())).isEmpty();
    }

    @Test
    void listsEveryGenreOrderedByName() {
        assertThat(genreRepository.findAllByOrderByNameAscIdAsc())
                .extracting(Genre::getName)
                .containsExactly("Action", "Comedy", "Drama", "Hành Động Hài");
    }

    private static UUID uuid(int value) {
        return new UUID(0L, value);
    }
}
