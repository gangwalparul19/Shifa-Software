package com.shifa.oms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.auth.dto.LoginRequest;
import com.shifa.oms.auth.dto.RefreshRequest;
import com.shifa.oms.auth.dto.RegisterRequest;
import com.shifa.oms.auth.dto.TokenResponse;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.common.DuplicateResourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link AuthService} using a mocked {@link UserRepository} and a
 * real BCrypt encoder / JWT service (no database). Covers successful login,
 * credential-failure 401s, disabled accounts, and the refresh round-trip
 * (Req 5.1, 5.2).
 */
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-that-is-long-enough-for-hmac-sha256-signing");
        jwtService = new JwtService(properties, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        authService = new AuthService(userRepository, passwordEncoder, jwtService,
                new com.shifa.oms.mail.MockMailService(),
                new com.shifa.oms.mail.template.EmailRenderer(
                        com.shifa.oms.mail.template.EmailBrandProperties.defaults()));
    }

    private User activeAdmin(String rawPassword) {
        User user = new User("admin", passwordEncoder.encode(rawPassword), Role.ADMIN, "Admin", true);
        setId(user, 1L);
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
    void loginWithValidCredentialsIssuesTokenPair() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(activeAdmin("secret123")));

        TokenResponse response = authService.login(new LoginRequest("admin", "secret123"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.role()).isEqualTo("ADMIN");
        assertThat(response.username()).isEqualTo("admin");
        assertThat(response.expiresIn()).isPositive();
        // Issued tokens validate and carry the right identity/types.
        assertThat(jwtService.parse(response.accessToken(), TokenType.ACCESS).role()).isEqualTo(Role.ADMIN);
        assertThat(jwtService.parse(response.refreshToken(), TokenType.REFRESH).userId()).isEqualTo(1L);
    }

    @Test
    void loginWithWrongPasswordIsRejectedWith401() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(activeAdmin("secret123")));

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "wrong")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(401));
    }

    @Test
    void loginWithUnknownUserIsRejectedWith401() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "whatever")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(401));
    }

    @Test
    void loginToDisabledAccountIsRejected() {
        User disabled = new User("admin", passwordEncoder.encode("secret123"), Role.ADMIN, "Admin", false);
        setId(disabled, 1L);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "secret123")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("ACCOUNT_DISABLED"));
    }

    @Test
    void refreshWithValidRefreshTokenIssuesFreshPair() {
        User admin = activeAdmin("secret123");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        String refreshToken = authService.login(new LoginRequest("admin", "secret123")).refreshToken();
        TokenResponse refreshed = authService.refresh(new RefreshRequest(refreshToken));

        assertThat(jwtService.parse(refreshed.accessToken(), TokenType.ACCESS).username()).isEqualTo("admin");
    }

    // --- Customer self-registration (Phase B) -------------------------------

    @Test
    void registerCreatesCustomerAndReturnsTokenPair() {
        when(userRepository.existsByUsername("9876543210")).thenReturn(false);
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            setId(u, 42L);
            return u;
        });

        TokenResponse response = authService.register(
                new RegisterRequest("Neha Sharma", "9876543210", null, "secret123"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        Mockito.verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(saved.getUsername()).isEqualTo("9876543210");
        assertThat(saved.getMobile()).isEqualTo("9876543210");
        assertThat(saved.isActive()).isTrue();
        // Password is stored hashed, never in plaintext.
        assertThat(saved.getPasswordHash()).isNotEqualTo("secret123");
        assertThat(passwordEncoder.matches("secret123", saved.getPasswordHash())).isTrue();
        // Token pair usable for auto-login.
        assertThat(response.role()).isEqualTo("CUSTOMER");
        assertThat(jwtService.parse(response.accessToken(), TokenType.ACCESS).role()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    void registerUsesEmailAsUsernameWhenProvided() {
        when(userRepository.existsByUsername("neha@example.com")).thenReturn(false);
        when(userRepository.save(Mockito.any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            setId(u, 43L);
            return u;
        });

        authService.register(new RegisterRequest("Neha", "9876543210", "neha@example.com", "secret123"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        Mockito.verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("neha@example.com");
        assertThat(captor.getValue().getEmail()).isEqualTo("neha@example.com");
    }

    @Test
    void registerRejectsDuplicateUsernameWith409() {
        when(userRepository.existsByUsername("9876543210")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("Neha", "9876543210", null, "secret123")))
                .isInstanceOf(DuplicateResourceException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(409));

        Mockito.verify(userRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void refreshWithAnAccessTokenIsRejected() {
        User admin = activeAdmin("secret123");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        String accessToken = authService.login(new LoginRequest("admin", "secret123")).accessToken();

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(accessToken)))
                .isInstanceOf(InvalidTokenException.class);
    }
}
