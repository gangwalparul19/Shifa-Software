import { Pipe, PipeTransform } from '@angular/core';

/**
 * Formats a number (or numeric string) as Indian Rupees, e.g. {@code 1234.5 →
 * "₹1,234.50"}. This is the single, app-wide money formatter — feature pages
 * used to each roll their own {@code money()}/{@code inr()} helper with divergent
 * rules (some 0-decimal, some 2, some without thousands separators) and a few
 * screens rendered the raw backend decimal string ("₹1234.5"). Use this instead.
 *
 * <p>Usage:
 * <pre>
 *   {{ order.totalAmount | inr }}          → ₹1,234.50   (2 decimals, default)
 *   {{ revenue | inr:0 }}                   → ₹1,235      (whole rupees)
 * </pre>
 *
 * Nullish / non-numeric input renders {@code ₹0.00} (or {@code ₹0}) so a missing
 * value never shows "₹" alone or "₹NaN".
 */
@Pipe({ name: 'inr', standalone: true })
export class InrPipe implements PipeTransform {
  transform(value: number | string | null | undefined, decimals: 0 | 2 = 2): string {
    return formatInr(value, decimals);
  }
}

/** Pure INR formatter shared by the {@link InrPipe} and component code. */
export function formatInr(value: number | string | null | undefined, decimals: 0 | 2 = 2): string {
  const n = typeof value === 'number' ? value : Number(String(value ?? '').replace(/[₹,\s]/g, ''));
  const safe = Number.isFinite(n) ? n : 0;
  return `₹${safe.toLocaleString('en-IN', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  })}`;
}
