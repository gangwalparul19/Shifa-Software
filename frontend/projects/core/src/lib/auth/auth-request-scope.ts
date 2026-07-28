/**
 * Pure token-scoping rule for the JWT auth interceptor, kept free of Angular
 * imports so it can be property-tested with the library jest runner.
 *
 * Decides whether a request targets our own backend and may therefore carry the
 * bearer token. Relative URLs (no scheme) are same-origin and always qualify; an
 * absolute URL only qualifies when it starts with the configured API base URL.
 * Every other absolute (third-party) host — e.g. the India Post pincode API used
 * by PIN-code auto-fill — must NEVER receive the token, so it returns false.
 */
export function isApiRequest(url: string, apiBaseUrl: string): boolean {
  if (!/^https?:\/\//i.test(url)) {
    return true;
  }
  const base = apiBaseUrl.replace(/\/+$/, '');
  return base.length > 0 && url.startsWith(base);
}

/**
 * Login, refresh, and registration establish authentication and must never
 * receive an existing bearer token. This protects a fresh login after a token
 * has expired or the app has moved to a new domain.
 */
export function isPublicAuthRequest(url: string, apiBaseUrl: string): boolean {
  const path = apiPath(url, apiBaseUrl);
  return path !== null && /^\/api\/auth\/(login|refresh|register)(?:[/?#]|$)/i.test(path);
}

function apiPath(url: string, apiBaseUrl: string): string | null {
  if (!/^https?:\/\//i.test(url)) {
    return url.startsWith('/') ? url : `/${url}`;
  }
  const base = apiBaseUrl.replace(/\/+$/, '');
  return base.length > 0 && url.startsWith(base) ? url.slice(base.length) || '/' : null;
}
