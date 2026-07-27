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
