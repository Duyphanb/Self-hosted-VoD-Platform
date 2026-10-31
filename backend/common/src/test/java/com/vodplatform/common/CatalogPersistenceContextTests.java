package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.auth.persistence.RefreshTokenEntity;
import com.vodplatform.auth.persistence.RefreshTokenRepository;
import com.vodplatform.auth.persistence.RoleEntity;
import com.vodplatform.auth.persistence.RoleRepository;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.catalog.persistence.Genre;
import com.vodplatform.catalog.persistence.GenreRepository;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieCredit;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.Person;
import com.vodplatform.catalog.persistence.PersonRepository;
import com.vodplatform.catalog.service.VideoStatusLookup;
import com.vodplatform.encoding.persistence.EncodingJob;
import com.vodplatform.encoding.persistence.EncodingJobRepository;
import com.vodplatform.encoding.persistence.VideoAsset;
import com.vodplatform.encoding.persistence.VideoAssetRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;

/**
 * The assembled application registers the catalog persistence next to auth's without changing
 * the auth registration. Uses the same web context configuration as the auth integration tests.
 */
@SpringBootTest(
        properties = {
                "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
                "auth.tokens.access-token-ttl=15m",
                "auth.tokens.refresh-token-ttl=7d"
        }
)
@AutoConfigureMockMvc
class CatalogPersistenceContextTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void catalogEntitiesAreRegisteredAlongsideTheAuthEntities() {
        Set<Class<?>> entityTypes = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(EntityType::getJavaType)
                .collect(Collectors.toSet());

        assertThat(entityTypes).containsExactlyInAnyOrder(
                UserEntity.class,
                RoleEntity.class,
                RefreshTokenEntity.class,
                Movie.class,
                Genre.class,
                Person.class,
                MovieCredit.class,
                VideoAsset.class,
                EncodingJob.class
        );
    }

    @Test
    void catalogRepositoriesAreRegisteredAlongsideTheAuthRepositories() {
        assertThat(applicationContext.getBeanNamesForType(MovieRepository.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(GenreRepository.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(PersonRepository.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(UserRepository.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(RoleRepository.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(RefreshTokenRepository.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(VideoAssetRepository.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(EncodingJobRepository.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(VideoStatusLookup.class)).hasSize(1);
    }
}
