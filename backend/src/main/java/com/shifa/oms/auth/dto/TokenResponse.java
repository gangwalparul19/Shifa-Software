package com.shifa.oms.auth.dto;

/**
 * Token pair returned by login/refresh.
 *
 * @param accessToken  short-lived bearer token for API calls
 * @param refreshToken longer-lived token used to obtain a new access token
 * @param tokenType    always {@code "Bearer"}
 * @param expiresIn    access-token lifetime in seconds
 * @param role         the authenticated user's role (convenience for the client)
 * @param username     the authenticated user's username
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String role,
        String username) {
}
