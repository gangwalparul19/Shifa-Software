package com.shifa.oms.account.dto;

import com.shifa.oms.auth.User;

/** The current customer's profile ({@code GET /api/account/profile}). */
public record ProfileResponse(
        Long id,
        String username,
        String fullName,
        String email,
        String mobile,
        String role) {

    public static ProfileResponse from(User user) {
        return new ProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getEmail(),
                user.getMobile(),
                user.getRole().name());
    }
}
