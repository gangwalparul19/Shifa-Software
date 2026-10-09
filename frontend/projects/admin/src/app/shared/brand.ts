/**
 * Single white-label brand surface for the Angular admin app.
 *
 * The admin app is a static build with no runtime server config, so per-client
 * (or demo) branding of the user-facing chrome is centralized here: change these
 * values (and the PWA manifest / index.html title + the /logo.png asset) for a
 * new client build — nothing else in the app hard-codes the brand name.
 *
 * NOTE: this covers only the frontend chrome (public tracking page header,
 * WhatsApp message templates fallback). The seller's legal name / address /
 * GSTIN on invoices and labels come from the backend app_settings table, and
 * the backend fallbacks come from the app.brand.* config — this constant does
 * NOT need to match those, but keeping it in sync is tidy.
 */
export const BRAND = {
  /**
   * Human-readable brand/store name shown to customers + staff.
   * This is the per-build value for THIS deployment (the demo/client build);
   * change it for each client. (Prod/Shifa uses its own build with 'Shifa
   * Herbal Remedies'.)
   */
  name: 'Weblithic',
} as const;
