package com.vodplatform.user.service;

import com.vodplatform.auth.dto.UserProfile;
import com.vodplatform.auth.exception.CurrentUserUnavailableException;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.persistence.UserStatus;
import com.vodplatform.auth.service.UserProfileMapper;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentUserProfileService {

    private final UserRepository userRepository;
    private final UserProfileMapper userProfileMapper;
    private final Clock clock;

    public CurrentUserProfileService(
            UserRepository userRepository,
            UserProfileMapper userProfileMapper,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.userProfileMapper = userProfileMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserProfile getProfile(UUID authenticatedUserId) {
        return userProfileMapper.toProfile(loadActiveUser(authenticatedUserId));
    }

    @Transactional
    public UserProfile updateDisplayName(UUID authenticatedUserId, String displayName) {
        UserEntity user = loadActiveUser(authenticatedUserId);
        user.updateDisplayName(displayName, clock.instant());
        return userProfileMapper.toProfile(user);
    }

    private UserEntity loadActiveUser(UUID authenticatedUserId) {
        return userRepository.findById(authenticatedUserId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(CurrentUserUnavailableException::new);
    }
}
