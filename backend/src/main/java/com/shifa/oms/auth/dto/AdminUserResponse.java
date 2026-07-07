package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;

import java.time.LocalDateTime;

/**
 * Safe admin view of a staff/platform {@link User} for the user-management grid
 * ({@code /api/admin/users}). Deliberately omits the BCrypt password hash — the
 * credential is never exposed over the API.
 */
public record AdminUserResponse(
        Long id,
        String username,
        String fullName,
        Role role,
        boolean active,
        LocalDateTime createdAt) {

    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole(),
                user.isActive(),
                user.getCreatedAt());
    }
}
