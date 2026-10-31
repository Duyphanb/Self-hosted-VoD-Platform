package com.vodplatform.encoding.config;

import com.vodplatform.encoding.persistence.VideoAsset;
import com.vodplatform.encoding.persistence.VideoAssetRepository;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Registers encoding's V1/V5 mappings; Flyway owns the production schema. */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = VideoAsset.class)
@EnableJpaRepositories(basePackageClasses = VideoAssetRepository.class)
public class EncodingConfiguration {
}
