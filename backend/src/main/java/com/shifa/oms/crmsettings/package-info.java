/**
 * Single-row CRM configuration surface (CRM expansion Phase 3, Req 18.3):
 * follow-up default time-of-day, missed-follow-up grace window, default pipeline,
 * and default team-assignment rule. Mirrors the single-row
 * {@link com.shifa.oms.settings} pattern; consumed best-effort by the follow-up
 * pipeline with fallback to today's constants.
 */
package com.shifa.oms.crmsettings;
