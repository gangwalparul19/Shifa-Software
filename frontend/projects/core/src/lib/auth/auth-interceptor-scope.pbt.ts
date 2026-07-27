import * as fc from 'fast-check';
import { isApiRequest } from './auth-request-scope';

/**
 * Property: the JWT auth interceptor must only attach the bearer token to our
 * own backend. `isApiRequest(url, apiBaseUrl)` decides that:
 *
 * - Relative URLs (no http(s) scheme) are same-origin → always eligible.
 * - Absolute URLs are eligible ONLY when they start with the configured API
 *   base URL.
 * - Any other absolute (third-party) host — e.g. the India Post pincode API —
 *   is NEVER eligible, so the token can't leak off-origin.
 *
 * Guards the security fix behind PIN-code auto-fill (which calls a third-party
 * absolute URL from an app that otherwise talks only to its own API).
 */
describe('authInterceptor token scoping (PBT)', () => {
  // A realistic API base like `http://localhost:8080` or `https://oms.shifa.app`.
  const apiBase: fc.Arbitrary<string> = fc
    .tuple(
      fc.constantFrom('http', 'https'),
      fc.domain(),
      fc.constantFrom('', ':8080', ':443'),
    )
    .map(([scheme, host, port]) => `${scheme}://${host}${port}`);

  // A relative API path such as `/api/orders` or `/api/states`.
  const relativePath: fc.Arbitrary<string> = fc
    .array(fc.constantFrom('api', 'orders', 'states', 'payments', 'me', 'admin'), {
      minLength: 1,
      maxLength: 4,
    })
    .map((parts) => `/${parts.join('/')}`);

  it('always attaches to relative (same-origin) paths regardless of base', () => {
    fc.assert(
      fc.property(relativePath, apiBase, (path, base) => {
        expect(isApiRequest(path, base)).toBe(true);
      }),
    );
  });

  it('attaches to absolute URLs that start with the configured API base', () => {
    fc.assert(
      fc.property(apiBase, relativePath, (base, path) => {
        expect(isApiRequest(`${base}${path}`, base)).toBe(true);
        // A trailing slash on the base must not change the decision.
        expect(isApiRequest(`${base}${path}`, `${base}/`)).toBe(true);
      }),
    );
  });

  it('never attaches to third-party absolute hosts (e.g. the pincode API)', () => {
    const thirdParty = fc.constantFrom(
      'https://api.postalpincode.in/pincode/560001',
      'https://evil.example.com/steal',
      'http://malicious.test/collect?x=1',
      'https://google.com',
    );
    fc.assert(
      fc.property(thirdParty, apiBase, (url, base) => {
        // The base is a different origin than any of these third-party URLs.
        fc.pre(!url.startsWith(base.replace(/\/+$/, '')));
        expect(isApiRequest(url, base)).toBe(false);
      }),
    );
  });

  it('never attaches to any absolute URL when no API base is configured', () => {
    const absolute = fc
      .tuple(fc.constantFrom('http', 'https'), fc.domain())
      .map(([scheme, host]) => `${scheme}://${host}/x`);
    fc.assert(
      fc.property(absolute, (url) => {
        expect(isApiRequest(url, '')).toBe(false);
      }),
    );
  });
});
