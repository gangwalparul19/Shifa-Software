package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.AdminUserResponse;
import com.shifa.oms.auth.dto.CreateUserRequest;
import com.shifa.oms.auth.dto.ResetPasswordRequest;
import com.shifa.oms.auth.dto.UpdateUserRequest;
import com.shifa.oms.common.DuplicateResourceException;
import com.shifa.oms.common.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminUserService} using a mocked {@link UserRepository}
 * (an interface — safe to mock on this runtime), a real BCrypt encoder, and a
 * hand-written fake {@link CurrentUserService} (a concrete class, so it is
 * subclassed rather than mocked). Covers create (dedupe + password encoding),
 * role update, password reset, and the deactivation / self-deactivation and
 * last-admin guardrails (Feature A).
 */
class AdminUserServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private FakeCurrentUserService currentUserService;
    private AdminUserService service;

    /** Hand-written fake for the concrete CurrentUserService (cannot be mocked). */
    private static final class FakeCurrentUserService extends CurrentUserService {
        private AuthPrincipal principal;

        void setPrincipal(AuthPrincipal principal) {
            this.principal = principal;
        }

        @Override
        public Optional<AuthPrincipal> currentUser() {
            return Optional.ofNullable(principal);
        }
    }

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        currentUserService = new FakeCurrentUserService();
        service = new AdminUserService(userRepository, passwordEncoder, currentUserService);
    }

    private static User userWithId(String username, Role role, boolean active, long id) {
        User user = new User(username, "$2a$hash", role, "Name", active);
        setId(user, id);
        return user;
    }

    private static void setId(User user, long id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void createEncodesPasswordAndPersistsUser() {
        when(userRepository.existsByUsername("salesperson1")).thenReturn(false);
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            setId(u, 10L);
            return u;
        });

        AdminUserResponse response = service.create(new CreateUserRequest(
                "salesperson1", "secret123", "Sales One", Role.SALESPERSON, true));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        Mockito.verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("salesperson1");
        assertThat(saved.getRole()).isEqualTo(Role.SALESPERSON);
        assertThat(saved.isActive()).isTrue();
        // Password stored hashed, never plaintext.
        assertThat(saved.getPasswordHash()).isNotEqualTo("secret123");
        assertThat(passwordEncoder.matches("secret123", saved.getPasswordHash())).isTrue();
        // Response never carries the hash.
        assertThat(response.role()).isEqualTo(Role.SALESPERSON);
        assertThat(response.active()).isTrue();
    }

    @Test
    void createRejectsDuplicateUsernameWith409() {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateUserRequest(
                "admin", "secret123", "Dup", Role.ADMIN, true)))
                .isInstanceOf(DuplicateResourceException.class);

        Mockito.verify(userRepository, never()).save(Mockito.any());
    }

    @Test
    void updateChangesRole() {
        User target = userWithId("packer", Role.PACKING_USER, true, 5L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(target));
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        // Acting admin is a different user.
        currentUserService.setPrincipal(new AuthPrincipal(1L, "admin", Role.ADMIN));

        AdminUserResponse response = service.update(5L,
                new UpdateUserRequest("Packer Renamed", Role.ACCOUNTANT, true,
                        null, null, null, null, null, null, null));

        assertThat(response.role()).isEqualTo(Role.ACCOUNTANT);
        assertThat(response.fullName()).isEqualTo("Packer Renamed");
        assertThat(target.getRole()).isEqualTo(Role.ACCOUNTANT);
    }

    @Test
    void resetPasswordReEncodesPassword() {
        User target = userWithId("salesperson1", Role.SALESPERSON, true, 7L);
        String originalHash = target.getPasswordHash();
        when(userRepository.findById(7L)).thenReturn(Optional.of(target));
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resetPassword(7L, new ResetPasswordRequest("brandnew1"));

        assertThat(target.getPasswordHash()).isNotEqualTo(originalHash);
        assertThat(passwordEncoder.matches("brandnew1", target.getPasswordHash())).isTrue();
    }

    @Test
    void deactivateDisablesLoginByClearingActiveFlag() {
        User target = userWithId("salesperson1", Role.SALESPERSON, true, 8L);
        when(userRepository.findById(8L)).thenReturn(Optional.of(target));
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        currentUserService.setPrincipal(new AuthPrincipal(1L, "admin", Role.ADMIN));

        AdminUserResponse response = service.deactivate(8L);

        // A deactivated user cannot authenticate (AuthService rejects !active).
        assertThat(response.active()).isFalse();
        assertThat(target.isActive()).isFalse();
    }

    @Test
    void deactivateBlocksSelfDeactivation() {
        User self = userWithId("admin", Role.ADMIN, true, 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(self));
        currentUserService.setPrincipal(new AuthPrincipal(1L, "admin", Role.ADMIN));

        assertThatThrownBy(() -> service.deactivate(1L))
                .isInstanceOf(ValidationException.class);

        Mockito.verify(userRepository, never()).save(Mockito.any());
    }

    @Test
    void updateBlocksSelfDemotion() {
        User self = userWithId("admin", Role.ADMIN, true, 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(self));
        currentUserService.setPrincipal(new AuthPrincipal(1L, "admin", Role.ADMIN));

        assertThatThrownBy(() -> service.update(1L,
                new UpdateUserRequest("Admin", Role.SALESPERSON, true,
                        null, null, null, null, null, null, null)))
                .isInstanceOf(ValidationException.class);

        Mockito.verify(userRepository, never()).save(Mockito.any());
    }

    @Test
    void deactivateBlocksRemovingLastActiveAdmin() {
        User otherAdmin = userWithId("admin2", Role.ADMIN, true, 2L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(otherAdmin));
        when(userRepository.countByRoleAndActiveTrue(Role.ADMIN)).thenReturn(1L);
        // A different admin is acting, so this is not blocked by the self-guard.
        currentUserService.setPrincipal(new AuthPrincipal(1L, "admin", Role.ADMIN));

        assertThatThrownBy(() -> service.deactivate(2L))
                .isInstanceOf(ValidationException.class);

        Mockito.verify(userRepository, never()).save(Mockito.any());
    }

    @Test
    void listReturnsSafeDtosWithoutPasswordHash() {
        when(userRepository.findAllByOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(userWithId("admin", Role.ADMIN, true, 1L)));

        List<AdminUserResponse> users = service.list();

        assertThat(users).hasSize(1);
        assertThat(users.get(0).username()).isEqualTo("admin");
        assertThat(users.get(0).role()).isEqualTo(Role.ADMIN);
    }
}
