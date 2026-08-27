package com.vodplatform.auth.service;

import com.vodplatform.auth.dto.RegisterRequest;
import com.vodplatform.auth.dto.UserProfile;
import com.vodplatform.auth.exception.EmailAlreadyExistsException;
import com.vodplatform.auth.persistence.RoleEntity;
import com.vodplatform.auth.persistence.RoleRepository;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.persistence.UserStatus;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    static final String DEFAULT_ROLE = "ROLE_USER";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserProfileMapper userProfileMapper;

    public RegistrationService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            UserProfileMapper userProfileMapper
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.userProfileMapper = userProfileMapper;
    }

    @Transactional
    public UserProfile register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException();
        }

        RoleEntity role = roleRepository.findByName(DEFAULT_ROLE)
                .orElseThrow(() -> new IllegalStateException("Required role is not configured"));
        Instant now = Instant.now();
        UserEntity user = new UserEntity(
                UUID.randomUUID(),
                request.email(),
                passwordEncoder.encode(request.password()),
                request.displayName(),
                UserStatus.ACTIVE,
                now,
                now
        );
        user.addRole(role);

        try {
            UserEntity savedUser = userRepository.saveAndFlush(user);
            return userProfileMapper.toProfile(savedUser);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "ux_users_email")) {
                throw new EmailAlreadyExistsException();
            }
            throw exception;
        }
    }

    private boolean hasConstraint(Throwable exception, String expectedConstraint) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && expectedConstraint.equalsIgnoreCase(constraintViolation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

}
