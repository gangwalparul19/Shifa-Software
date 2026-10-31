/**
 * The five platform roles (mirrors the backend {@code Role} enum, Req 5.1).
 * String values match the backend authority names so role checks line up.
 */
export enum Role {
  ADMIN = 'ADMIN',
  ACCOUNTANT = 'ACCOUNTANT',
  SALESPERSON = 'SALESPERSON',
  TEAM_LEAD = 'TEAM_LEAD',
  PACKING_USER = 'PACKING_USER',
  PAYMENT_VERIFIER = 'PAYMENT_VERIFIER',
  /** Chartered Accountant — accounting, taxation, GST dashboard/report (read-only finance). */
  CA = 'CA',
  CUSTOMER = 'CUSTOMER',
}

/** Credentials posted to {@code POST /api/auth/login}. */
export interface LoginCredentials {
  username: string;
  password: string;
}

/**
 * The 200 body returned by {@code POST /api/auth/login} when the password was
 * correct but TOTP multi-factor is active for the user (mirrors the backend
 * {@code MfaChallengeResponse}). It is deliberately NOT a 401 so the token
 * refresh interceptor leaves it alone.
 *
 * <ul>
 *   <li>{@code MFA_REQUIRED} — the user is enrolled; prompt for the current
 *       authenticator code and call {@code /api/auth/mfa/verify}. {@code
 *       otpauthUri}/{@code secret} are absent.</li>
 *   <li>{@code MFA_ENROLL} — MFA is on but the user has not enrolled; show the QR
 *       from {@code otpauthUri} (and the manual {@code secret}) to scan, then
 *       call {@code /api/auth/mfa/enroll/confirm}.</li>
 * </ul>
 */
export interface MfaChallenge {
  status: 'MFA_REQUIRED' | 'MFA_ENROLL';
  mfaTicket: string;
  /** otpauth:// provisioning URI for the QR code (enrollment only). */
  otpauthUri?: string | null;
  /** Base32 secret for manual entry (enrollment only). */
  secret?: string | null;
}

/** Submission for {@code /api/auth/mfa/verify} and {@code /api/auth/mfa/enroll/confirm}. */
export interface MfaVerifyRequest {
  mfaTicket: string;
  code: string;
}

/**
 * The result of {@link AuthService.login}: either the session was established
 * (no MFA) or a second factor is owed and the caller must complete the TOTP
 * step with the carried {@link MfaChallenge}.
 */
export type LoginResult =
  | { kind: 'authenticated'; session: AuthSession }
  | { kind: 'mfa'; challenge: MfaChallenge };

/**
 * Token pair returned by {@code /api/auth/login} and {@code /api/auth/refresh}
 * (mirrors the backend {@code TokenResponse}).
 */
export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  role: Role;
  username: string;
  /**
   * Whether the user must set a new password before using the app (an admin
   * reset their password to the temporary one). The client routes to the
   * change-password screen when true.
   */
  mustChangePassword?: boolean;
  /** Sliding idle-session deadline, epoch seconds from the server. */
  sessionExpiresAt?: number;
}

/** The decoded identity of the currently authenticated user. */
export interface AuthSession {
  userId: number;
  username: string;
  role: Role;
  /**
   * The user's full name, when the access token carries it. Absent for tokens
   * issued before the claim existed, so prefer {@code AuthService.displayName}
   * over reading this directly.
   */
  fullName?: string;
  /**
   * When true, the user signed in with an administrator-issued one-time
   * credential and must set a new strong password before using the app.
   * {@code pwd} claim so it survives a page reload.
   */
  mustChangePassword?: boolean;
  /** Current server-authoritative idle deadline, epoch milliseconds for UI scheduling. */
  sessionExpiresAt?: number;
}
