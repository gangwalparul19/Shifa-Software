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

/** Coerces a number|string|nullish money value to a finite number (0 on failure). */
function toNumber(value: number | string | null | undefined): number {
  const n = typeof value === 'number' ? value : Number(String(value ?? '').replace(/[₹,\s]/g, ''));
  return Number.isFinite(n) ? n : 0;
}

/**
 * Compact Indian-currency formatter for cramped spaces (lists, cards, table
 * cells) where full amounts eat too much width. Rules:
 *
 * <ul>
 *   <li>below ₹10,000 → shown in full (e.g. {@code ₹2,500}, {@code ₹6,500}),
 *       up to 4 significant digits, so small amounts stay exact and readable;</li>
 *   <li>₹10,000 and above → abbreviated with the Indian K / L / Cr suffixes to
 *       two decimals: {@code 12460 → ₹12.46K}, {@code 123456 → ₹1.23L},
 *       {@code 1073000 → ₹10.73L}, {@code 25000000 → ₹2.50Cr}.</li>
 * </ul>
 *
 * Trailing {@code .00} / {@code .X0} are trimmed (₹10.00K → ₹10K, ₹10.50K →
 * ₹10.5K) to keep it tight. Negatives are preserved. Pair with
 * {@link formatInr} as a {@code title} tooltip to reveal the full value on hover.
 */
export function formatCompactInr(value: number | string | null | undefined): string {
  const n = toNumber(value);
  const sign = n < 0 ? '-' : '';
  const abs = Math.abs(n);
  // Below ₹10k: show in full with up to 2 decimals (whole amounts drop them).
  if (abs < 10_000) {
    return `${sign}₹${abs.toLocaleString('en-IN', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2,
    })}`;
  }
  const units: ReadonlyArray<[number, string]> = [
    [1_00_00_000, 'Cr'], // crore
    [1_00_000, 'L'], // lakh
    [1_000, 'K'], // thousand
  ];
  for (const [factor, suffix] of units) {
    if (abs >= factor) {
      // Two decimals, then strip trailing zeros ("12.00" → "12", "12.50" → "12.5").
      const compact = (abs / factor).toFixed(2).replace(/\.?0+$/, '');
      return `${sign}₹${compact}${suffix}`;
    }
  }
  // Unreachable (abs >= 10k always matches ≥1K), but satisfies the type.
  return formatInr(n, 0);
}

/**
 * Compact money pipe: {@code {{ amount | compactInr }}} → "₹12.46K". Use it in
 * cramped lists/cards/table cells; bind {@code [title]="amount | inr"} alongside
 * so hovering reveals the exact value.
 */
@Pipe({ name: 'compactInr', standalone: true })
export class CompactInrPipe implements PipeTransform {
  transform(value: number | string | null | undefined): string {
    return formatCompactInr(value);
  }
}
