/**
 * Production environment configuration for the Admin Dashboard.
 * `apiBaseUrl` points at the Spring Boot OMS REST API.
 */
export const environment = {
  production: true,
  // Same-origin: Nginx serves the app and reverse-proxies /api to the backend,
  // so the ApiClient calls "/api/..." with no host (no CORS needed).
  apiBaseUrl: '',
};
