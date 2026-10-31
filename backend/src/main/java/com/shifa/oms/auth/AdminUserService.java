package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.AdminUserResponse;
import com.shifa.oms.auth.dto.CreateUserRequest;
import com.shifa.oms.auth.dto.PasswordResetResponse;
import com.shifa.oms.auth.dto.UpdateUserRequest;
import com.shifa.oms.common.DuplicateResourceException;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin staff/platform user-management application service (Feature A).
 *
 * <p>Backs the ADMIN-only {@code /api/admin/users} API: listing, creating,
 * updating, resetting a password, and toggling the active flag. Passwords are
 * always stored as BCrypt hashes via the shared {@link PasswordEncoder}; the
 * plaintext is never persisted and the hash is never returned (responses use the
 * safe {@link AdminUserResponse}).
 *
 * <p>Guardrails protect the platform from locking itself out:
 * <ul>
 *   <li>an admin may not deactivate or demote their <em>own</em> account
 *       (resolved via {@link CurrentUserService});</li>
 *   <li>the last remaining active {@link Role#ADMIN} may not be deactivated or
 *       demoted, regardless of who performs it.</li>
 * </ul>
 */
@Service
public class AdminUserService {

    private static final String RESET_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;
    private final Duration resetCredentialTtl;
    private final SecureRandom secureRandom;

    /** Spring constructor; reset TTL is configuration, never a fixed credential. */
    @Autowired
    public AdminUserService(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            CurrentUserService currentUserService,
                            @Value("${app.security.password-reset-ttl:PT24H}") Duration resetCredentialTtl) {
        this(userRepository, passwordEncoder, currentUserService, resetCredentialTtl, new SecureRandom());
    }

    /** Backward-compatible unit-test constructor. */
    public AdminUserService(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            CurrentUserService currentUserService) {
        this(userRepository, passwordEncoder, currentUserService, Duration.ofHours(24), new SecureRandom());
    }

    AdminUserService(UserRepository userRepository,
                     PasswordEncoder passwordEncoder,
                     CurrentUserService currentUserService,
                     Duration resetCredentialTtl,
                     SecureRandom secureRandom) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUserService = currentUserService;
        this.resetCredentialTtl = resetCredentialTtl == null || resetCredentialTtl.isNegative()
                || resetCredentialTtl.isZero() ? Duration.ofHours(24) : resetCredentialTtl;
        this.secureRandom = secureRandom == null ? new SecureRandom() : secureRandom;
    }

    /** All users, newest first, as safe DTOs (never exposing password hashes). */
    @Transactional(readOnly = true)
    public List<AdminUserResponse> list() {
        return userRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(AdminUserResponse::from)
                .toList();
    }

    /**
     * Creates a user with a BCrypt-encoded password. Rejects a duplicate username
     * with a 409. The account is active unless {@code active=false} is supplied.
     */
    @Transactional
    public AdminUserResponse create(CreateUserRequest request) {
        String username = request.username().trim();
        if (userRepository.existsByUsername(username)) {
            throw new DuplicateResourceException("USERNAME_TAKEN",
                    "A user already exists with username '" + username + "'.");
        }
        boolean active = request.active() == null || request.active();
        User user = new User(
                username,
                passwordEncoder.encode(request.password()),
                request.role(),
                request.fullName().trim(),
                active);
        return AdminUserResponse.from(userRepository.save(user));
    }

    /**
     * Updates a user's full name, role, and active flag. Enforces the
     * self-demotion / self-deactivation and last-admin guardrails before saving.
     */
    @Transactional
    public AdminUserResponse update(Long id, UpdateUserRequest request) {
        User user = require(id);
        boolean active = Boolean.TRUE.equals(request.active());

        if (isSelf(user) && user.getRole() == Role.ADMIN && request.role() != Role.ADMIN) {
            throw new ValidationException("You cannot change your own admin role.");
        }
        if (isSelf(user) && !active) {
            throw new ValidationException("You cannot deactivate your own account.");
        }
        // Protect the last active admin from being demoted or deactivated.
        if (user.getRole() == Role.ADMIN && user.isActive()
                && (request.role() != Role.ADMIN || !active)
                && isLastActiveAdmin()) {
            throw new ValidationException("At least one active admin must remain.");
        }

        boolean roleChanged = user.getRole() != request.role();
        boolean activeChanged = user.isActive() != active;
        user.setFullName(request.fullName().trim());
        user.setRole(request.role());
        user.setActive(active);
        if (roleChanged || activeChanged) {
            user.incrementSessionVersion();
        }
        // Contact + onboarding profile — the admin can edit every detail here
        // (blank strings normalised to null; the ID document/photo and the
        // verification decision keep their dedicated staff endpoints).
        user.setEmail(blankToNull(request.email()));
        user.setMobile(blankToNull(request.mobile()));
        user.setDateOfBirth(request.dateOfBirth());
        user.setAddress(blankToNull(request.address()));
        user.setJoinedOn(request.joinedOn());
        user.setIdProofType(request.idProofType());
        user.setIdProofNumber(blankToNull(request.idProofNumber()));
        return AdminUserResponse.from(userRepository.save(user));
    }

    /**
     * Replaces the password with a cryptographically random temporary credential
     * and forces a password change before the configured expiry deadline.
     * The raw credential is returned only in {@link PasswordResetResponse}.
     */
    @Transactional
    public PasswordResetResponse resetPassword(Long id, com.shifa.oms.auth.dto.ResetPasswordRequest request) {
        User user = require(id);
        String temporaryCredential = generateTemporaryCredential();
        LocalDateTime expiresAt = LocalDateTime.now().plus(resetCredentialTtl);
        user.setPasswordHash(passwordEncoder.encode(temporaryCredential));
        user.setMustChangePassword(true);
        user.setPasswordResetAt(LocalDateTime.now());
        user.setPasswordResetExpiresAt(expiresAt);
        user.setPasswordResetCount(user.getPasswordResetCount() + 1);
        user.incrementSessionVersion();
        User saved = userRepository.save(user);
        return new PasswordResetResponse(AdminUserResponse.from(saved), temporaryCredential, expiresAt);
    }

    /**
     * Turns the MFA (TOTP) requirement on or off for a user — the control for the
     * ADMIN-first sequenced rollout. Enabling a user who has not enrolled does
     * NOT lock them out: they are walked through enrollment at their next login.
     * Disabling leaves any enrolled secret intact (re-enabling resumes with it);
     * use {@link #resetMfa(Long)} to clear the secret entirely.
     */
    @Transactional
    public AdminUserResponse setMfaEnabled(Long id, boolean enabled) {
        User user = require(id);
        boolean changed = user.isMfaEnabled() != enabled;
        user.setMfaEnabled(enabled);
        if (changed) {
            user.incrementSessionVersion();
        }
        return AdminUserResponse.from(userRepository.save(user));
    }

    /**
     * Clears a user's MFA enrollment (secret + enrolled timestamp) so they must
     * re-enroll from scratch — the recovery path when a user loses their
     * authenticator device. Leaves {@code mfaEnabled} unchanged: if it is still
     * on, the user re-enrolls at next login; if off, nothing further happens.
     */
    @Transactional
    public AdminUserResponse resetMfa(Long id) {
        User user = require(id);
        user.clearMfaSecret();
        user.setMfaEnrolledAt(null);
        user.incrementSessionVersion();
        return AdminUserResponse.from(userRepository.save(user));
    }

    /** Activates a user (idempotent). */
    @Transactional
    public AdminUserResponse activate(Long id) {
        User user = require(id);
        if (!user.isActive()) {
            user.setActive(true);
            user.incrementSessionVersion();
        }
        return AdminUserResponse.from(userRepository.save(user));
    }

    /**
     * Deactivates a user so they can no longer authenticate (login/refresh reject
     * disabled accounts). Blocks self-deactivation and deactivating the last
     * active admin.
     */
    @Transactional
    public AdminUserResponse deactivate(Long id) {
        User user = require(id);
        if (isSelf(user)) {
            throw new ValidationException("You cannot deactivate your own account.");
        }
        if (user.getRole() == Role.ADMIN && user.isActive() && isLastActiveAdmin()) {
            throw new ValidationException("At least one active admin must remain.");
        }
        if (user.isActive()) {
            user.setActive(false);
            user.incrementSessionVersion();
        }
        return AdminUserResponse.from(userRepository.save(user));
    }

    private String generateTemporaryCredential() {
        StringBuilder value = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            value.append(RESET_ALPHABET.charAt(secureRandom.nextInt(RESET_ALPHABET.length())));
        }
        return value.toString();
    }

    private User require(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " does not exist."));
    }

    private boolean isSelf(User user) {
        return currentUserService.currentUser()
                .map(principal -> principal.userId().equals(user.getId()))
                .orElse(false);
    }

    private boolean isLastActiveAdmin() {
        return userRepository.countByRoleAndActiveTrue(Role.ADMIN) <= 1;
    }

    /** Trims a string and maps blank/empty to {@code null} for nullable columns. */
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
