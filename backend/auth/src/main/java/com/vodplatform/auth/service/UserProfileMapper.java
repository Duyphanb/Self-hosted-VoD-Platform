package com.vodplatform.auth.service;

import com.vodplatform.auth.dto.UserProfile;
import com.vodplatform.auth.persistence.RoleEntity;
import com.vodplatform.auth.persistence.UserEntity;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class UserProfileMapper {

    public UserProfile toProfile(UserEntity user) {
        List<String> roles = user.getRoles().stream()
                .map(RoleEntity::getName)
                .sorted(Comparator.naturalOrder())
                .toList();
        return new UserProfile(user.getId(), user.getEmail(), user.getDisplayName(), roles);
    }
}
