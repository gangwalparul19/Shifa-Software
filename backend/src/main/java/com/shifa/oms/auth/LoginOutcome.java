package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.MfaChallengeResponse;
import com.shifa.oms.auth.dto.TokenResponse;

/**
 * The result of a password login: either the token pair is issued immediately
 * (no MFA, or MFA not active) or a second factor is owed and the caller must
 * complete the TOTP step.
 *
 * <p>Modelled as a single record carrying exactly one of the two payloads so the
 * controller can return the right 200 body without the service leaking HTTP
 * concerns. Use {@link #requiresMfa()} to branch.
 *
 * @param tokens    the issued token pair when login completed; {@code null} when
 *                  a challenge is owed
 * @param challenge the MFA challenge when a second factor is owed; {@code null}
 *                  when tokens were issued
 */
public record LoginOutcome(TokenResponse tokens, MfaChallengeResponse challenge) {

    /** Login completed — tokens issued, no second factor required. */
    public static LoginOutcome completed(TokenResponse tokens) {
        return new LoginOutcome(tokens, null);
    }

    /** A second factor is owed — the caller must complete the MFA challenge. */
    public static LoginOutcome challenge(MfaChallengeResponse challenge) {
        return new LoginOutcome(null, challenge);
    }

    /** Whether the caller must complete an MFA challenge before receiving tokens. */
    public boolean requiresMfa() {
        return challenge != null;
    }
}
