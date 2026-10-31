import { Injectable, signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { decodeJwtPayload } from './jwt.util';
import { isSessionDeadlineExpired, idleDeadlineMillisFromHeader, sessionDeadlineMillis } from './session-deadline.util';

const ACCESS_TOKEN_KEY = 'shifa.accessToken';
const REFRESH_TOKEN_KEY = 'shifa.refreshToken';
const SESSION_DEADLINE_KEY = 'shifa.sessionExpiresAt';
const LEGACY_ACCESS_TOKEN_KEY = ACCESS_TOKEN_KEY;
const LEGACY_REFRESH_TOKEN_KEY = REFRESH_TOKEN_KEY;
const MAX_TIMER_MS = 2_147_483_647;

/**
 * Holds the current session in per-tab sessionStorage. Tokens deliberately do
 * not survive closing a tab/browser, and old localStorage keys are removed on
 * construction without attempting a migration.
 */
@Injectable({ providedIn: 'root' })
export class AuthTokenStore {
  private readonly accessToken$ = signal<string | null>(this.readSession(ACCESS_TOKEN_KEY));
  private readonly refreshToken$ = signal<string | null>(this.readSession(REFRESH_TOKEN_KEY));
  private readonly sessionExpiresAt$ = signal<number | null>(this.readDeadline());
  private readonly expiredSubject = new Subject<void>();
  private expiryTimer: ReturnType<typeof setTimeout> | null = null;

  /** Reactive accessor for the current access token (null when signed out). */
  readonly accessToken = this.accessToken$.asReadonly();
  /** Reactive sliding idle deadline in epoch milliseconds. */
  readonly sessionExpiresAt = this.sessionExpiresAt$.asReadonly();
  /** Fires when the scheduled idle deadline clears the session. */
  readonly expired: Observable<void> = this.expiredSubject.asObservable();

  constructor() {
    // Do not migrate persistent authentication state into the new tab-scoped
    // store. Removing these keys also prevents an older bundle from restoring
    // credentials after the new bundle has loaded.
    this.removeLocal(LEGACY_ACCESS_TOKEN_KEY);
    this.removeLocal(LEGACY_REFRESH_TOKEN_KEY);

    const access = this.accessToken$();
    if (access && this.sessionExpiresAt$() === null) {
      const claims = decodeJwtPayload(access);
      const deadline = sessionDeadlineMillis(claims);
      if (deadline !== null) {
        this.sessionExpiresAt$.set(deadline);
        this.writeSession(SESSION_DEADLINE_KEY, String(deadline));
      }
    }
    this.scheduleExpiry(this.sessionExpiresAt$());
  }

  getAccessToken(): string | null {
    return this.accessToken$();
  }

  getRefreshToken(): string | null {
    return this.refreshToken$();
  }

  getSessionExpiresAt(): number | null {
    return this.sessionExpiresAt$();
  }

  setTokens(accessToken: string, refreshToken?: string, sessionExpiresAt?: number): void {
    this.accessToken$.set(accessToken);
    this.writeSession(ACCESS_TOKEN_KEY, accessToken);
    if (refreshToken !== undefined) {
      this.refreshToken$.set(refreshToken);
      this.writeSession(REFRESH_TOKEN_KEY, refreshToken);
    }

    const claims = decodeJwtPayload(accessToken);
    const claimDeadline = sessionDeadlineMillis(claims);
    const deadline = sessionExpiresAt !== undefined && sessionExpiresAt > 0
      ? this.toEpochMillis(sessionExpiresAt)
      : claimDeadline;
    this.sessionExpiresAt$.set(deadline);
    if (deadline === null) {
      this.removeSession(SESSION_DEADLINE_KEY);
    } else {
      this.writeSession(SESSION_DEADLINE_KEY, String(deadline));
    }
    this.scheduleExpiry(deadline);
  }

  /**
   * Applies a successful own-API response's idle deadline hint. The value is
   * never authority, and an older response cannot move the timer backwards.
   */
  updateSessionDeadline(value: string | number | null | undefined,
                        expectedAccessToken?: string | null): void {
    if (expectedAccessToken !== undefined && this.accessToken$() !== expectedAccessToken) {
      return;
    }
    const deadline = typeof value === 'string'
      ? idleDeadlineMillisFromHeader(value)
      : this.toEpochMillis(value);
    if (deadline === null || this.accessToken$() === null) {
      return;
    }
    const current = this.sessionExpiresAt$();
    if (current !== null && deadline <= current) {
      return;
    }
    this.sessionExpiresAt$.set(deadline);
    this.writeSession(SESSION_DEADLINE_KEY, String(deadline));
    this.scheduleExpiry(deadline);
  }

  clear(): void {
    this.cancelExpiry();
    this.accessToken$.set(null);
    this.refreshToken$.set(null);
    this.sessionExpiresAt$.set(null);
    this.removeSession(ACCESS_TOKEN_KEY);
    this.removeSession(REFRESH_TOKEN_KEY);
    this.removeSession(SESSION_DEADLINE_KEY);
  }

  /** Returns true when the sliding idle deadline has passed. */
  isSessionExpired(now = Date.now()): boolean {
    return isSessionDeadlineExpired(this.sessionExpiresAt$(), now);
  }

  /** Clears an overdue session and reports whether it was expired. */
  expireIfNeeded(now = Date.now()): boolean {
    if (!this.isSessionExpired(now)) {
      return false;
    }
    this.clear();
    return true;
  }

  private scheduleExpiry(deadline: number | null): void {
    this.cancelExpiry();
    if (deadline === null) {
      return;
    }
    const delay = deadline - Date.now();
    if (delay <= 0) {
      queueMicrotask(() => {
        if (this.expireIfNeeded()) {
          this.expiredSubject.next();
        }
      });
      return;
    }
    const timer = setTimeout(() => {
      if (this.sessionExpiresAt$() !== deadline) {
        return;
      }
      if (this.expireIfNeeded()) {
        this.expiredSubject.next();
      } else {
        this.scheduleExpiry(deadline);
      }
    }, Math.min(delay, MAX_TIMER_MS));
    this.expiryTimer = timer;
    // Avoid keeping a Node-based test process alive for a browser-only timer.
    const unref = (timer as unknown as { unref?: () => void }).unref;
    unref?.call(timer);
  }

  private cancelExpiry(): void {
    if (this.expiryTimer !== null) {
      clearTimeout(this.expiryTimer);
      this.expiryTimer = null;
    }
  }

  private readDeadline(): number | null {
    const raw = this.readSession(SESSION_DEADLINE_KEY);
    if (!raw) {
      return null;
    }
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
  }

  private toEpochMillis(value: number | null | undefined): number | null {
    if (value === null || value === undefined || !Number.isFinite(value) || value <= 0) {
      return null;
    }
    return value < 100_000_000_000 ? value * 1000 : value;
  }

  private readSession(key: string): string | null {
    try {
      return typeof sessionStorage === 'undefined' ? null : sessionStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private writeSession(key: string, value: string): void {
    try {
      if (typeof sessionStorage !== 'undefined') {
        sessionStorage.setItem(key, value);
      }
    } catch {
      // Storage can be unavailable in private/blocked browser contexts.
    }
  }

  private removeSession(key: string): void {
    try {
      if (typeof sessionStorage !== 'undefined') {
        sessionStorage.removeItem(key);
      }
    } catch {
      // Best effort only.
    }
  }

  private removeLocal(key: string): void {
    try {
      if (typeof localStorage !== 'undefined') {
        localStorage.removeItem(key);
      }
    } catch {
      // Best effort only.
    }
  }
}
