import { InjectionToken } from '@angular/core';

/**
 * Base URL of the Spring Boot OMS REST API (e.g. `http://localhost:8080`).
 *
 * Each application provides this from its own `environment.apiBaseUrl` so the
 * `core` library stays free of app-specific environment imports.
 */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL');
