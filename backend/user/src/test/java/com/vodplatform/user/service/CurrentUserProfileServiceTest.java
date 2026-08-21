package com.vodplatform.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vodplatform.auth.dto.UserProfile;
import com.vodplatform.auth.exception.CurrentUserUnavailableException;
import com.vodplatform.auth.persistence.RoleEntity;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.persistence.UserStatus;
import com.vodplatform.auth.service.UserProfileMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CurrentUserProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleEntity role;

    private CurrentUserProfileService service;

    @BeforeEach
    void setUp() {
        service = new CurrentUserProfileService(
                userRepository,
                new UserProfileMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void getsProfileForAuthenticatedUserId() {
        UserEntity user = activeUser(UUID.randomUUID(), "viewer@example.com", "Viewer");
        when(role.getName()).thenReturn("ROLE_USER");
        user.addRole(role);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        UserProfile result = service.getProfile(user.getId());

        assertThat(result.id()).isEqualTo(user.getId());
        assertThat(result.email()).isEqualTo("viewer@example.com");
        assertThat(result.displayName()).isEqualTo("Viewer");
        assertThat(result.roles()).containsExactly("ROLE_USER");
    }

    @Test
    void updatesOnlyDisplayNameAndTimestampForAuthenticatedUserId() {
        UserEntity user = activeUser(UUID.randomUUID(), "viewer@example.com", "Viewer");
        when(role.getName()).thenReturn("ROLE_USER");
        user.addRole(role);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        UserProfile result = service.updateDisplayName(user.getId(), "Updated Viewer");

        assertThat(result.displayName()).isEqualTo("Updated Viewer");
        assertThat(user.getDisplayName()).isEqualTo("Updated Viewer");
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        assertThat(user.getEmail()).isEqualTo("viewer@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("password-hash");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userRepository).findById(user.getId());
    }

    @Test
    void rejectsMissingOrDisabledAuthenticatedUsersWithoutExposingAccountState() {
        UUID missingId = UUID.randomUUID();
        when(userRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile(missingId))
                .isInstanceOf(CurrentUserUnavailableException.class);

        UserEntity disabled = new UserEntity(
                UUID.randomUUID(),
                "disabled@example.com",
                "password-hash",
                "Disabled",
                UserStatus.DISABLED,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60)
        );
        when(userRepository.findById(disabled.getId())).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.updateDisplayName(disabled.getId(), "Still Disabled"))
                .isInstanceOf(CurrentUserUnavailableException.class);
    }

    private UserEntity activeUser(UUID id, String email, String displayName) {
        return new UserEntity(
                id,
                email,
                "password-hash",
                displayName,
                UserStatus.ACTIVE,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60)
        );
    }
}
