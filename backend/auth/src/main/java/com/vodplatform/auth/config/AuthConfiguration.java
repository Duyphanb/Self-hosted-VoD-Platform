package com.vodplatform.auth.config;

import com.vodplatform.auth.persistence.RoleEntity;
import com.vodplatform.auth.persistence.RoleRepository;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.security.VersionedBcryptPasswordEncoder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(AuthCookieProperties.class)
@EntityScan(basePackageClasses = {UserEntity.class, RoleEntity.class})
@EnableJpaRepositories(basePackageClasses = {UserRepository.class, RoleRepository.class})
@EnableScheduling
public class AuthConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new VersionedBcryptPasswordEncoder();
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler authPruningScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("auth-pruning-");
        return scheduler;
    }
}
