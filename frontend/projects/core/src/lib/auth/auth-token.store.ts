import { Injectable, signal } from '@angular/core';

const ACCESS_TOKEN_KEY = 'shifa.accessToken';
const REFRESH_TOKEN_KEY = 'shifa.refreshToken';

/**
 * Holds the JWT access/refresh tokens and mirrors them to `localStorage` so a
 * page reload keeps the session. This is the scaffold consumed by the auth
 * interceptor; the full login/refresh flow is wired in a later task.
 */
@Injectable({ providedIn: 'root' })
export class AuthTokenStore {
  private readonly accessToken$ = signal<string | null>(this.read(ACCESS_TOKEN_KEY));
  private readonly refreshToken$ = signal<string | null>(this.read(REFRESH_TOKEN_KEY));

  /** Reactive accessor for the current access token (null when signed out). */
  readonly accessToken = this.accessToken$.asReadonly();

  getAccessToken(): string | null {
    return this.accessToken$();
  }

  getRefreshToken(): string | null {
    return this.refreshToken$();
  }

  setTokens(accessToken: string, refreshToken?: string): void {
    this.accessToken$.set(accessToken);
    this.write(ACCESS_TOKEN_KEY, accessToken);
    if (refreshToken !== undefined) {
      this.refreshToken$.set(refreshToken);
      this.write(REFRESH_TOKEN_KEY, refreshToken);
    }
  }

  clear(): void {
    this.accessToken$.set(null);
    this.refreshToken$.set(null);
    this.remove(ACCESS_TOKEN_KEY);
    this.remove(REFRESH_TOKEN_KEY);
  }

  private read(key: string): string | null {
    if (typeof localStorage === 'undefined') {
      return null;
    }
    return localStorage.getItem(key);
  }

  private write(key: string, value: string): void {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(key, value);
    }
  }

  private remove(key: string): void {
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(key);
    }
  }
}
