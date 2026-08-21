package com.vodplatform.auth.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vodplatform.auth.config.AuthConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = AuthConfiguration.class)
class AuthPersistenceTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsActiveUserAndRoleAssignmentUsingFrozenSchemaMapping() {
        RoleEntity role = roleRepository.saveAndFlush(new RoleEntity("ROLE_USER"));
        Instant now = Instant.now();
        UserEntity user = new UserEntity(
                UUID.randomUUID(),
                "viewer@example.com",
                "$2a$10$placeholder",
                "Viewer",
                UserStatus.ACTIVE,
                now,
                now
        );
        user.addRole(role);

        userRepository.saveAndFlush(user);
        entityManager.clear();

        UserEntity persisted = userRepository.findById(user.getId()).orElseThrow();
        assertThat(persisted.getEmail()).isEqualTo("viewer@example.com");
        assertThat(persisted.getDisplayName()).isEqualTo("Viewer");
        assertThat(persisted.getPasswordHash()).isEqualTo("$2a$10$placeholder");
        assertThat(persisted.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(persisted.getRoles()).extracting(RoleEntity::getName).containsExactly("ROLE_USER");
        assertThat(userRepository.existsByEmail("viewer@example.com")).isTrue();
    }

    @Test
    void databaseRejectsDuplicateEmail() {
        Instant now = Instant.now();
        UserEntity firstUser = new UserEntity(
                UUID.randomUUID(),
                "duplicate@example.com",
                "$2a$10$first",
                "First Viewer",
                UserStatus.ACTIVE,
                now,
                now
        );
        UserEntity duplicateUser = new UserEntity(
                UUID.randomUUID(),
                "duplicate@example.com",
                "$2a$10$second",
                "Second Viewer",
                UserStatus.ACTIVE,
                now,
                now
        );
        userRepository.saveAndFlush(firstUser);

        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicateUser))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
