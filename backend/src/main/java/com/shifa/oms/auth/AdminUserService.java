package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.AdminUserResponse;
import com.shifa.oms.auth.dto.CreateUserRequest;
import com.shifa.oms.auth.dto.ResetPasswordRequest;
import com.shifa.oms.auth.dto.UpdateUserRequest;
import com.shifa.oms.common.DuplicateResourceException;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;

    public AdminUserService(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            CurrentUserService currentUserService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUserService = currentUserService;
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

        user.setFullName(request.fullName().trim());
        user.setRole(request.role());
        user.setActive(active);
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

    /** Re-encodes and sets a new password for the user. */
    @Transactional
    public AdminUserResponse resetPassword(Long id, ResetPasswordRequest request) {
        User user = require(id);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        return AdminUserResponse.from(userRepository.save(user));
    }

    /** Activates a user (idempotent). */
    @Transactional
    public AdminUserResponse activate(Long id) {
        User user = require(id);
        user.setActive(true);
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
        user.setActive(false);
        return AdminUserResponse.from(userRepository.save(user));
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
