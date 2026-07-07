/*
 * Public API Surface of core
 *
 * Shared API client, auth interceptor scaffold, and typed models mirroring the
 * backend DTOs. Consumed by both the storefront and admin applications.
 */

// Models (Product, Order, LineItem, PaymentStatus, OrderStatus, Receivable, ...)
export * from './lib/models';

// Configuration token
export * from './lib/tokens/api-base-url.token';

// HTTP client wrapper
export * from './lib/http/api-client.service';

// Catalog helpers (placeholder image selection, stock status)
export * from './lib/catalog/product-image.util';
export * from './lib/catalog/stock-status.util';

// Decimal-safe money helpers
export * from './lib/money/money.util';

// Reviews & ratings star helpers (Phase C)
export * from './lib/reviews/star-rating.util';

// Storefront cart / wishlist / checkout pure logic (Requirements 2, 3)
export * from './lib/cart/cart-logic';
export * from './lib/wishlist/wishlist-logic';
export * from './lib/checkout/checkout-validation';

// Auth scaffolding
export * from './lib/auth/auth-token.store';
export * from './lib/auth/auth-events.service';
export * from './lib/auth/auth.interceptor';

// Auth flow: login/refresh service, JWT helpers, and route guards
export * from './lib/auth/auth.service';
export * from './lib/auth/jwt.util';
export * from './lib/auth/auth.guards';

// Application wiring helper
export * from './lib/core.providers';
