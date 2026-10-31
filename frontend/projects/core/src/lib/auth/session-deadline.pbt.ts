import * as fc from 'fast-check';
import {
  DEFAULT_SESSION_TTL_MS,
  idleDeadlineMillisFromHeader,
  isSessionDeadlineExpired,
  sessionDeadlineMillis,
} from './session-deadline.util';

describe('sliding session idle deadline hints', () => {
  it('seeds the browser timer from the signed idle hint; API headers can renew it', () => {
    fc.assert(
      fc.property(fc.integer({ min: 1, max: 4_000_000_000 }), fc.integer({ min: 1, max: 90_000_000 }),
        (sessionExp, delay) => {
          const claims = { iat: sessionExp - 1800, session_exp: sessionExp };
          expect(sessionDeadlineMillis(claims)).toBe(sessionExp * 1000);
          expect(sessionDeadlineMillis(claims)).not.toBe((sessionExp + delay) * 1000);
        }),
    );
  });

  it('bounds a token without a response hint to an initial 30-minute idle hint', () => {
    fc.assert(
      fc.property(fc.integer({ min: 1, max: 4_000_000_000 }), (issuedAt) => {
        expect(sessionDeadlineMillis({ iat: issuedAt })).toBe(issuedAt * 1000 + DEFAULT_SESSION_TTL_MS);
      }),
    );
  });

  it('treats the exact deadline as expired', () => {
    expect(isSessionDeadlineExpired(1_800_000, 1_800_000)).toBe(true);
    expect(isSessionDeadlineExpired(1_800_000, 1_799_999)).toBe(false);
  });

  it('accepts only numeric response-header hints and normalizes seconds to milliseconds', () => {
    fc.assert(
      fc.property(fc.integer({ min: 1, max: 4_000_000_000 }), (seconds) => {
        expect(idleDeadlineMillisFromHeader(String(seconds))).toBe(seconds * 1000);
      }),
    );
    expect(idleDeadlineMillisFromHeader('not-a-date')).toBeNull();
    expect(idleDeadlineMillisFromHeader('')).toBeNull();
  });
});
