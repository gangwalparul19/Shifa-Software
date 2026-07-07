import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { AuthService } from './auth.service';
import { Role } from '../models/auth.model';

/**
 * Builds a guard that requires an authenticated user (Req 5.2). Unauthenticated
 * visitors are redirected to {@code loginPath}, preserving the attempted URL in
 * a {@code returnUrl} query param so login can bounce them back.
 *
 * <p>Used by the Storefront for customer-only areas (order tracking, account).
 */
export function createAuthGuard(loginPath: string): CanActivateFn {
  return (_route, state): boolean | UrlTree => {
    const auth = inject(AuthService);
    const router = inject(Router);
    if (auth.isAuthenticated()) {
      return true;
    }
    return router.createUrlTree([loginPath], { queryParams: { returnUrl: state.url } });
  };
}

/**
 * Builds a role-based guard (Req 5.3, 5.4). An unauthenticated visitor is sent
 * to {@code loginPath}; an authenticated user without one of {@code allowedRoles}
 * is sent to {@code forbiddenPath} (an authorization-error view). The server
 * still enforces authorization on every request — this guard only improves the
 * client-side experience (defense in depth).
 *
 * <p>Used by the Admin app to gate role-specific sections.
 */
export function createRoleGuard(
  loginPath: string,
  forbiddenPath: string,
  ...allowedRoles: Role[]
): CanActivateFn {
  return (_route, state): boolean | UrlTree => {
    const auth = inject(AuthService);
    const router = inject(Router);
    if (!auth.isAuthenticated()) {
      return router.createUrlTree([loginPath], { queryParams: { returnUrl: state.url } });
    }
    if (auth.hasAnyRole(...allowedRoles)) {
      return true;
    }
    return router.createUrlTree([forbiddenPath]);
  };
}
