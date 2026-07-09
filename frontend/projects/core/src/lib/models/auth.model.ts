/**
 * The five platform roles (mirrors the backend {@code Role} enum, Req 5.1).
 * String values match the backend authority names so role checks line up.
 */
export enum Role {
  ADMIN = 'ADMIN',
  ACCOUNTANT = 'ACCOUNTANT',
  SALESPERSON = 'SALESPERSON',
  PACKING_USER = 'PACKING_USER',
  CUSTOMER = 'CUSTOMER',
}

/** Credentials posted to {@code POST /api/auth/login}. */
export interface LoginCredentials {
  username: string;
  password: string;
}

/**
 * Token pair returned by {@code /api/auth/login} and {@code /api/auth/refresh}
 * (mirrors the backend {@code TokenResponse}).
 */
export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  role: Role;
  username: string;
}

/** The decoded identity of the currently authenticated user. */
export interface AuthSession {
  userId: number;
  username: string;
  role: Role;
}
