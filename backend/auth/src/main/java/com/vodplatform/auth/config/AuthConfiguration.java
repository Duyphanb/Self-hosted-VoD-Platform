package com.vodplatform.auth.config;

import com.vodplatform.auth.persistence.RoleEntity;
import com.vodplatform.auth.persistence.RoleRepository;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.security.Utf8AwareBcryptPasswordEncoder;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = {UserEntity.class, RoleEntity.class})
@EnableJpaRepositories(basePackageClasses = {UserRepository.class, RoleRepository.class})
public class AuthConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new Utf8AwareBcryptPasswordEncoder();
    }
}
