package com.vodplatform.auth.service;

import com.vodplatform.auth.dto.AuthResponse;
import com.vodplatform.auth.dto.LoginRequest;
import com.vodplatform.auth.exception.InvalidCredentialsException;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.persistence.UserStatus;
import java.util.Optional;
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
    private final String dummyPasswordHash;

    public LoginService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtAccessTokenService jwtAccessTokenService,
            RefreshTokenService refreshTokenService,
            UserProfileMapper userProfileMapper
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtAccessTokenService = jwtAccessTokenService;
        this.refreshTokenService = refreshTokenService;
        this.userProfileMapper = userProfileMapper;
        this.dummyPasswordHash = passwordEncoder.encode("invalid-login-placeholder");
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        Optional<UserEntity> userCandidate = userRepository.findByEmail(request.email());
        String passwordHash = userCandidate
                .map(UserEntity::getPasswordHash)
                .orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);
        UserEntity user = userCandidate
                .filter(candidate -> passwordMatches)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(InvalidCredentialsException::new);

        IssuedAccessToken accessToken = jwtAccessTokenService.issue(user);
        IssuedRefreshToken refreshToken = refreshTokenService.issue(user);
        return new AuthResponse(
                userProfileMapper.toProfile(user),
                accessToken.value(),
                refreshToken.value(),
                accessToken.expiresInSeconds()
        );
    }
}
