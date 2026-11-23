package com.vodplatform.auth.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    @Query("select count(u) > 0 from UserEntity u where lower(u.email) = lower(:email)")
    boolean existsByEmail(@Param("email") String email);

    @Override
    @EntityGraph(attributePaths = "roles")
    Optional<UserEntity> findById(UUID id);

    @EntityGraph(attributePaths = "roles")
    @Query("select u from UserEntity u where lower(u.email) = lower(:email)")
    Optional<UserEntity> findByEmail(@Param("email") String email);

    @Query("""
            select u.id as id, u.passwordHash as passwordHash
            from UserEntity u where lower(u.email) = lower(:email)
            """)
    Optional<LoginCredentials> findLoginCredentials(String email);

    @Query(value = "SELECT id FROM users WHERE id IN (:userIds) ORDER BY id FOR UPDATE", nativeQuery = true)
    List<UUID> lockAuthUsers(Collection<UUID> userIds);

    interface LoginCredentials {
        UUID getId();

        String getPasswordHash();
    }
}
