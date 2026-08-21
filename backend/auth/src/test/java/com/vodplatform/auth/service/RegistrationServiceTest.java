package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vodplatform.auth.dto.RegisterRequest;
import com.vodplatform.auth.dto.UserProfile;
import com.vodplatform.auth.exception.EmailAlreadyExistsException;
import com.vodplatform.auth.persistence.RoleEntity;
import com.vodplatform.auth.persistence.RoleRepository;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserRepository;
import com.vodplatform.auth.persistence.UserStatus;
import java.util.Optional;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.hibernate.exception.ConstraintViolationException;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private RoleEntity role;

    private BCryptPasswordEncoder passwordEncoder;
    private RegistrationService registrationService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        registrationService = new RegistrationService(userRepository, roleRepository, passwordEncoder);
    }

    @Test
    void registersActiveUserWithBcryptPasswordAndDefaultRole() {
        RegisterRequest request = new RegisterRequest(
                "viewer@example.com",
                "strong-password",
                "Viewer"
        );
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(role));
        when(role.getName()).thenReturn("ROLE_USER");
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserProfile profile = registrationService.register(request);

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        UserEntity savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo(request.email());
        assertThat(savedUser.getDisplayName()).isEqualTo(request.displayName());
        assertThat(savedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(savedUser.getRoles()).containsExactly(role);
        assertThat(savedUser.getPasswordHash()).isNotEqualTo(request.password());
        assertThat(passwordEncoder.matches(request.password(), savedUser.getPasswordHash())).isTrue();
        assertThat(profile.id()).isEqualTo(savedUser.getId());
        assertThat(profile.email()).isEqualTo(request.email());
        assertThat(profile.displayName()).isEqualTo(request.displayName());
        assertThat(profile.roles()).containsExactly("ROLE_USER");
    }

    @Test
    void rejectsKnownDuplicateEmailBeforeEncodingOrSaving() {
        RegisterRequest request = new RegisterRequest(
                "viewer@example.com",
                "strong-password",
                "Viewer"
        );
        when(userRepository.existsByEmail(request.email())).thenReturn(true);

        assertThatThrownBy(() -> registrationService.register(request))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(roleRepository, never()).findByName(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void mapsUniqueConstraintRaceToDuplicateEmailConflict() {
        RegisterRequest request = new RegisterRequest(
                "viewer@example.com",
                "strong-password",
                "Viewer"
        );
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(role));
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate email",
                        new ConstraintViolationException(
                                "duplicate email",
                                new SQLException("duplicate email"),
                                "ux_users_email"
                        )
                ));

        assertThatThrownBy(() -> registrationService.register(request))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void doesNotMaskUnrelatedPersistenceFailuresAsDuplicateEmail() {
        RegisterRequest request = new RegisterRequest(
                "viewer@example.com",
                "strong-password",
                "Viewer"
        );
        DataIntegrityViolationException persistenceFailure = new DataIntegrityViolationException(
                "unrelated constraint"
        );
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(role));
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenThrow(persistenceFailure);

        assertThatThrownBy(() -> registrationService.register(request))
                .isSameAs(persistenceFailure);
    }
}
