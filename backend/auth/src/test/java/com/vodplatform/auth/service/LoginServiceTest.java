package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vodplatform.auth.service.IssuedAuthentication;
import com.vodplatform.auth.dto.LoginRequest;
import com.vodplatform.auth.dto.UserProfile;
import com.vodplatform.auth.exception.InvalidCredentialsException;
import com.vodplatform.auth.persistence.AuthSessionEntity;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.persistence.UserRepository.LoginCredentials;
import com.vodplatform.auth.persistence.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtAccessTokenService jwtAccessTokenService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private UserProfileMapper userProfileMapper;

    @Mock
    private UserEntity user;

    @Mock
    private AuthSessionService sessions;

    private final AuthSessionEntity session = new AuthSessionEntity(
            UUID.randomUUID(), USER_ID, NOW, NOW.plusSeconds(604800));

    private LoginService loginService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        loginService = new LoginService(
                userRepository,
                passwordEncoder,
                jwtAccessTokenService,
                refreshTokenService,
                userProfileMapper,
                sessions,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void returnsUserAndBothTokensForValidActiveUser() {
        LoginRequest request = new LoginRequest("viewer@example.com", "correct-password");
        UserProfile profile = new UserProfile(
                UUID.randomUUID(),
                request.email(),
                "Viewer",
                List.of("ROLE_USER")
        );
        candidate(request, "stored-bcrypt-hash", true);
        when(user.getPasswordHash()).thenReturn("stored-bcrypt-hash");
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(passwordEncoder.matches(request.password(), "stored-bcrypt-hash")).thenReturn(true);
        when(sessions.start(USER_ID, NOW)).thenReturn(session);
        when(jwtAccessTokenService.issue(user, session)).thenReturn(new IssuedAccessToken("access-token", 900));
        when(refreshTokenService.issue(user, session)).thenReturn(new IssuedRefreshToken(
                "refresh-token",
                "refresh-hash",
                Instant.parse("2030-01-08T00:00:00Z")
        ));
        when(userProfileMapper.toProfile(user)).thenReturn(profile);

        IssuedAuthentication response = loginService.login(request);

        assertThat(response.user()).isEqualTo(profile);
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void upgradesLegacyPasswordHashAfterSuccessfulAuthentication() {
        LoginRequest request = new LoginRequest("viewer@example.com", "correct-password");
        UserProfile profile = new UserProfile(
                UUID.randomUUID(),
                request.email(),
                "Viewer",
                List.of("ROLE_USER")
        );
        candidate(request, "legacy-bcrypt-hash", true);
        when(user.getPasswordHash()).thenReturn("legacy-bcrypt-hash");
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(passwordEncoder.matches(request.password(), "legacy-bcrypt-hash")).thenReturn(true);
        when(passwordEncoder.upgradeEncoding("legacy-bcrypt-hash")).thenReturn(true);
        when(passwordEncoder.encode(request.password())).thenReturn("versioned-bcrypt-hash");
        when(sessions.start(USER_ID, NOW)).thenReturn(session);
        when(jwtAccessTokenService.issue(user, session)).thenReturn(new IssuedAccessToken("access-token", 900));
        when(refreshTokenService.issue(user, session)).thenReturn(new IssuedRefreshToken(
                "refresh-token",
                "refresh-hash",
                Instant.parse("2030-01-08T00:00:00Z")
        ));
        when(userProfileMapper.toProfile(user)).thenReturn(profile);

        loginService.login(request);

        verify(user).updatePasswordHash("versioned-bcrypt-hash");
    }

    @Test
    void unknownEmailUsesDummyHashAndReturnsGenericFailure() {
        LoginRequest request = new LoginRequest("missing@example.com", "wrong-password");
        when(userRepository.findLoginCredentials(request.email())).thenReturn(Optional.empty());
        when(passwordEncoder.matches(request.password(), "dummy-hash")).thenReturn(false);

        assertGenericFailure(request);
        verify(jwtAccessTokenService, never()).issue(any(), any());
        verify(refreshTokenService, never()).issue(any(), any());
    }

    @Test
    void wrongPasswordReturnsSameGenericFailure() {
        LoginRequest request = new LoginRequest("viewer@example.com", "wrong-password");
        candidate(request, "stored-bcrypt-hash", false);
        when(passwordEncoder.matches(request.password(), "stored-bcrypt-hash")).thenReturn(false);

        assertGenericFailure(request);
    }

    @Test
    void disabledUserReturnsSameGenericFailure() {
        LoginRequest request = new LoginRequest("viewer@example.com", "correct-password");
        candidate(request, "stored-bcrypt-hash", true);
        when(user.getStatus()).thenReturn(UserStatus.DISABLED);
        when(passwordEncoder.matches(request.password(), "stored-bcrypt-hash")).thenReturn(true);

        assertGenericFailure(request);
    }

    private void assertGenericFailure(LoginRequest request) {
        assertThatThrownBy(() -> loginService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
    }

    private void candidate(LoginRequest request, String passwordHash, boolean passwordMatches) {
        LoginCredentials credentials = mock(LoginCredentials.class);
        when(credentials.getPasswordHash()).thenReturn(passwordHash);
        when(userRepository.findLoginCredentials(request.email())).thenReturn(Optional.of(credentials));
        if (passwordMatches) {
            when(credentials.getId()).thenReturn(USER_ID);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        }
    }
}
