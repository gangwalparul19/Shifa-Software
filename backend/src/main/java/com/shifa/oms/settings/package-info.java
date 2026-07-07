/**
 * Settings module: a single-row, admin-controlled company + GST configuration.
 *
 * <p>{@link com.shifa.oms.settings.AppSettings} is the persisted row (V2
 * migration), {@link com.shifa.oms.settings.SettingsService} loads/creates and
 * updates it, and {@link com.shifa.oms.settings.SettingsController} exposes the
 * ADMIN-only {@code GET/PUT /api/admin/settings} endpoints. The invoice module
 * reads these settings to switch between a plain invoice and a GST tax invoice.
 */
package com.shifa.oms.settings;
