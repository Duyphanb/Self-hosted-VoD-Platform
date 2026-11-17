package com.vodplatform.progress.config;

import com.vodplatform.progress.persistence.PlaybackProgress;
import com.vodplatform.progress.persistence.PlaybackProgressRepository;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = PlaybackProgress.class)
@EnableJpaRepositories(basePackageClasses = PlaybackProgressRepository.class)
public class ProgressConfiguration {
}
