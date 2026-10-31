package com.shifa.oms.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Derives the bounded identity component used by MFA throttling. A verified
 * MFA ticket gets its signed user id; malformed, expired, or wrong-type tickets
 * share one fixed fallback bucket so attackers cannot evade throttling by
 * sending a different random token on every request.
 */
@Component
public class MfaRateLimitKeyResolver {

    static final String INVALID_TICKET_KEY = "__mfa_invalid__";

    private final JwtService jwtService;

    @Autowired
    public MfaRateLimitKeyResolver(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /** Returns a stable user key for a valid MFA ticket, or a bounded fallback. */
    public String resolve(String mfaTicket) {
        try {
            JwtClaims claims = jwtService.parse(mfaTicket, TokenType.MFA);
            return "mfa-user:" + claims.userId();
        } catch (RuntimeException ignored) {
            // The authoritative MFA service still returns the normal 401. This
            // helper only chooses a safe rate-limit bucket before that happens.
            return INVALID_TICKET_KEY;
        }
    }
}
