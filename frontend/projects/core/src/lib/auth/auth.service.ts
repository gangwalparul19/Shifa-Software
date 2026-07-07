import { Injectable, computed, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { map, tap } from 'rxjs/operators';
import { ApiClient } from '../http/api-client.service';
import { AuthTokenStore } from './auth-token.store';
import {
  AuthSession,
  LoginCredentials,
  RegisterRequest,
  Role,
  TokenResponse,
} from '../models/auth.model';
import { decodeJwtPayload } from './jwt.util';

/**
 * Application-facing authentication service shared by both apps.
 *
 * <ul>
 *   <li>{@link login} — exchanges credentials for a token pair and stores them
 *       (Req 5.2).</li>
 *   <li>{@link refresh} — swaps the stored refresh token for a fresh pair.</li>
 *   <li>{@link logout} — clears the stored session.</li>
 * </ul>
 *
 * The current {@link session} is derived reactively from the access token held
 * in {@link AuthTokenStore}, so it updates automatically when the token is set
 * at login or cleared by the {@code authInterceptor} on a 401. Route guards read
 * {@link isAuthenticated} / {@link hasAnyRole} to make navigation decisions,
 * while the server remains authoritative for every API call.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ApiClient);
  private readonly tokens = inject(AuthTokenStore);

  /** The decoded identity of the signed-in user, or {@code null} when signed out. */
  readonly session = computed<AuthSession | null>(() => {
    const claims = decodeJwtPayload(this.tokens.accessToken());
    if (!claims) {
      return null;
    }
    return { userId: claims.uid, username: claims.sub, role: claims.role };
  });

  /** Whether a user is currently signed in. */
  readonly isAuthenticated = computed<boolean>(() => this.session() !== null);

  /** The signed-in user's role, or {@code null}. */
  readonly role = computed<Role | null>(() => this.session()?.role ?? null);

  /** Authenticates and stores the resulting token pair. */
  login(credentials: LoginCredentials): Observable<AuthSession> {
    return this.api.post<TokenResponse>('/api/auth/login', credentials).pipe(
      tap((response) => this.tokens.setTokens(response.accessToken, response.refreshToken)),
      map(() => this.requireSession()),
    );
  }

  /**
   * Registers a new customer and stores the returned token pair (auto-login).
   * The registration endpoint is public; a 409 propagates when the username is
   * already taken so callers can surface a friendly message.
   */
  register(request: RegisterRequest): Observable<AuthSession> {
    return this.api.post<TokenResponse>('/api/auth/register', request).pipe(
      tap((response) => this.tokens.setTokens(response.accessToken, response.refreshToken)),
      map(() => this.requireSession()),
    );
  }

  /**
   * Obtains a new token pair from the stored refresh token. Errors (missing or
   * rejected refresh token) propagate so callers can route to login.
   */
  refresh(): Observable<AuthSession> {
    const refreshToken = this.tokens.getRefreshToken();
    return this.api
      .post<TokenResponse>('/api/auth/refresh', { refreshToken: refreshToken ?? '' })
      .pipe(
        tap((response) => this.tokens.setTokens(response.accessToken, response.refreshToken)),
        map(() => this.requireSession()),
      );
  }

  /** Clears the stored session (client-side sign-out). */
  logout(): void {
    this.tokens.clear();
  }

  /** Whether the signed-in user holds any of the given roles. */
  hasAnyRole(...roles: Role[]): boolean {
    const current = this.role();
    return current !== null && roles.includes(current);
  }

  private requireSession(): AuthSession {
    const session = this.session();
    if (!session) {
      throw new Error('Authentication succeeded but the token could not be decoded.');
    }
    return session;
  }
}
