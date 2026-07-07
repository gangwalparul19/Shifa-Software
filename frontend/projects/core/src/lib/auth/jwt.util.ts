import { Role } from '../models/auth.model';

/** The claims the backend {@code JwtService} embeds in an access token. */
export interface DecodedJwt {
  sub: string;
  uid: number;
  role: Role;
  typ: string;
  iat: number;
  exp: number;
}

/**
 * Decodes (without verifying — the server is authoritative) the payload of a
 * JWT so the client can read the current user's identity/role and expiry for
 * routing decisions. Returns {@code null} for anything that is not a
 * well-formed token with the expected claims.
 */
export function decodeJwtPayload(token: string | null | undefined): DecodedJwt | null {
  if (!token) {
    return null;
  }
  const parts = token.split('.');
  if (parts.length !== 3) {
    return null;
  }
  try {
    const json = base64UrlDecode(parts[1]);
    const claims = JSON.parse(json) as Partial<DecodedJwt>;
    if (
      typeof claims.sub !== 'string' ||
      typeof claims.uid !== 'number' ||
      typeof claims.role !== 'string' ||
      typeof claims.exp !== 'number'
    ) {
      return null;
    }
    return claims as DecodedJwt;
  } catch {
    return null;
  }
}

/** Whether the token is absent, malformed, or past its {@code exp} instant. */
export function isJwtExpired(token: string | null | undefined, nowSeconds = Date.now() / 1000): boolean {
  const claims = decodeJwtPayload(token);
  if (!claims) {
    return true;
  }
  return claims.exp <= nowSeconds;
}

function base64UrlDecode(value: string): string {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
  // `atob` is available in browsers and modern SSR runtimes; decode UTF-8 safely.
  const binary = atob(padded);
  return decodeURIComponent(
    binary
      .split('')
      .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
      .join(''),
  );
}
