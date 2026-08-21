package com.vodplatform.auth.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    boolean existsByEmail(String email);

    @Override
    @EntityGraph(attributePaths = "roles")
    Optional<UserEntity> findById(UUID id);

    @EntityGraph(attributePaths = "roles")
    Optional<UserEntity> findByEmail(String email);
}
