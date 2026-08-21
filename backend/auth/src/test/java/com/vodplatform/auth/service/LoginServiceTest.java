package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vodplatform.auth.dto.AuthResponse;
import com.vodplatform.auth.dto.LoginRequest;
import com.vodplatform.auth.dto.UserProfile;
import com.vodplatform.auth.exception.InvalidCredentialsException;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.persistence.UserStatus;
import java.time.Instant;
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

    private LoginService loginService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        loginService = new LoginService(
                userRepository,
                passwordEncoder,
                jwtAccessTokenService,
                refreshTokenService,
                userProfileMapper
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
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(user));
        when(user.getPasswordHash()).thenReturn("stored-bcrypt-hash");
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(passwordEncoder.matches(request.password(), "stored-bcrypt-hash")).thenReturn(true);
        when(jwtAccessTokenService.issue(user)).thenReturn(new IssuedAccessToken("access-token", 900));
        when(refreshTokenService.issue(user)).thenReturn(new IssuedRefreshToken(
                "refresh-token",
                "refresh-hash",
                Instant.parse("2030-01-08T00:00:00Z")
        ));
        when(userProfileMapper.toProfile(user)).thenReturn(profile);

        AuthResponse response = loginService.login(request);

        assertThat(response.user()).isEqualTo(profile);
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void unknownEmailUsesDummyHashAndReturnsGenericFailure() {
        LoginRequest request = new LoginRequest("missing@example.com", "wrong-password");
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.empty());
        when(passwordEncoder.matches(request.password(), "dummy-hash")).thenReturn(false);

        assertGenericFailure(request);
        verify(jwtAccessTokenService, never()).issue(user);
        verify(refreshTokenService, never()).issue(user);
    }

    @Test
    void wrongPasswordReturnsSameGenericFailure() {
        LoginRequest request = new LoginRequest("viewer@example.com", "wrong-password");
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(user));
        when(user.getPasswordHash()).thenReturn("stored-bcrypt-hash");
        when(passwordEncoder.matches(request.password(), "stored-bcrypt-hash")).thenReturn(false);

        assertGenericFailure(request);
    }

    @Test
    void disabledUserReturnsSameGenericFailure() {
        LoginRequest request = new LoginRequest("viewer@example.com", "correct-password");
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(user));
        when(user.getPasswordHash()).thenReturn("stored-bcrypt-hash");
        when(user.getStatus()).thenReturn(UserStatus.DISABLED);
        when(passwordEncoder.matches(request.password(), "stored-bcrypt-hash")).thenReturn(true);

        assertGenericFailure(request);
    }

    private void assertGenericFailure(LoginRequest request) {
        assertThatThrownBy(() -> loginService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
    }
}
