import { DecodedJwt } from './jwt.util';

/** The backend's default sliding authenticated-session idle timeout. */
export const DEFAULT_SESSION_TTL_MS = 30 * 60 * 1000;

/**
 * Converts the signed JWT idle-expiry hint to epoch milliseconds. The hint is
 * only used to seed the browser timer; successful API response headers refresh
 * it and the server-side session row remains authoritative.
 */
export function sessionDeadlineMillis(
  claims: Pick<DecodedJwt, 'session_exp' | 'iat'> | null | undefined,
  ttlMs = DEFAULT_SESSION_TTL_MS,
): number | null {
  if (!claims) {
    return null;
  }
  if (typeof claims.session_exp === 'number' && Number.isFinite(claims.session_exp)) {
    return claims.session_exp * 1000;
  }
  if (typeof claims.iat === 'number' && Number.isFinite(claims.iat)) {
    return claims.iat * 1000 + ttlMs;
  }
  return null;
}

export function isSessionDeadlineExpired(deadlineMs: number | null | undefined, nowMs = Date.now()): boolean {
  return deadlineMs !== null && deadlineMs !== undefined && nowMs >= deadlineMs;
}

/** Converts the exposed server idle-expiry header (epoch ms or seconds). */
export function idleDeadlineMillisFromHeader(value: string | null | undefined): number | null {
  if (!value || !/^\d+(?:\.\d+)?$/.test(value.trim())) {
    return null;
  }
  const parsed = Number(value.trim());
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return null;
  }
  return parsed < 100_000_000_000 ? parsed * 1000 : parsed;
}
