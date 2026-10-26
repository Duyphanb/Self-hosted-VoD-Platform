package com.vodplatform.catalog.config;

import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieRepository;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registers the catalog entities and repositories. Entity scan packages from every module's
 * {@code @EntityScan} add up, so this does not narrow or replace the auth registration.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = Movie.class)
@EnableJpaRepositories(basePackageClasses = MovieRepository.class)
public class CatalogConfiguration {
}
