package com.vodplatform.auth.service;

import com.vodplatform.auth.dto.LoginRequest;
import com.vodplatform.auth.exception.InvalidCredentialsException;
import com.vodplatform.auth.persistence.AuthSessionEntity;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.persistence.UserRepository.LoginCredentials;
import com.vodplatform.auth.persistence.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtAccessTokenService jwtAccessTokenService;
    private final RefreshTokenService refreshTokenService;
    private final UserProfileMapper userProfileMapper;
    private final AuthSessionService sessions;
    private final Clock clock;
    private final String dummyPasswordHash;

    public LoginService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtAccessTokenService jwtAccessTokenService,
            RefreshTokenService refreshTokenService,
            UserProfileMapper userProfileMapper,
            AuthSessionService sessions,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtAccessTokenService = jwtAccessTokenService;
        this.refreshTokenService = refreshTokenService;
        this.userProfileMapper = userProfileMapper;
        this.sessions = sessions;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("invalid-login-placeholder");
    }

    @Transactional
    public IssuedAuthentication login(LoginRequest request) {
        return login(request, null);
    }

    @Transactional
    public IssuedAuthentication login(LoginRequest request, String presentedToken) {
        sessions.limitTransactionConnection();
        Optional<LoginCredentials> userCandidate = userRepository.findLoginCredentials(request.email());
        String passwordHash = userCandidate
                .map(LoginCredentials::getPasswordHash)
                .orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);
        UUID userId = userCandidate.filter(candidate -> passwordMatches).map(LoginCredentials::getId)
                .orElseThrow(InvalidCredentialsException::new);

        var previousToken = refreshTokenService.locate(presentedToken);
        // A browser may switch accounts. Lock both owners in PostgreSQL's UUID order.
        var previousOwner = previousToken.stream().map(RefreshTokenService.LocatedToken::userId);
        var owners = Stream.concat(Stream.of(userId), previousOwner)
                .distinct().toList();
        var lockedOwners = userRepository.lockAuthUsers(owners);
        UserEntity user = userRepository.findById(userId)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(InvalidCredentialsException::new);
        String currentHash = user.getPasswordHash();
        if (!currentHash.equals(passwordHash) && !passwordEncoder.matches(request.password(), currentHash)) {
            throw new InvalidCredentialsException();
        }
        if (passwordEncoder.upgradeEncoding(currentHash)) {
            user.updatePasswordHash(passwordEncoder.encode(request.password()));
        }

        previousToken.filter(location -> lockedOwners.contains(location.userId())).ifPresent(this::revokeValidSession);
        AuthSessionEntity session = sessions.start(userId, clock.instant());
        IssuedAccessToken accessToken = jwtAccessTokenService.issue(user, session);
        IssuedRefreshToken refreshToken = refreshTokenService.issue(user, session);
        return new IssuedAuthentication(
                userProfileMapper.toProfile(user),
                accessToken.value(),
                refreshToken.value(),
                accessToken.expiresInSeconds(), refreshToken.expiresAt()
        );
    }

    private void revokeValidSession(RefreshTokenService.LocatedToken location) {
        sessions.lock(location.sessionId(), location.userId()).ifPresent(session -> {
            var token = refreshTokenService.lock(location);
            Instant now = clock.instant();
            if (session.isActiveAt(now)) {
                token.filter(current -> current.getRevokedAt() == null && current.getExpiresAt().isAfter(now))
                        .filter(current -> current.getUser().getStatus() == UserStatus.ACTIVE)
                        .ifPresent(current -> sessions.revoke(session, now));
            }
        });
    }
}
