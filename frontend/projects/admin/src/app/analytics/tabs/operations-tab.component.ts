import { Component, computed, input } from '@angular/core';
import { formatDateLabel } from '../../shared/date-label.util';
import { humanHours } from '../analytics-format.util';
import { OperationsReport, OutcomeRow, PaymentRiskRow, ReasonRow, SpeedStat } from '../analytics.model';

/** Fewest delivered + failed orders each payment type needs before the two are compared. */
const MIN_ORDERS_TO_COMPARE = 10;
/** Gap, in percentage points, above which pay on delivery is called out. */
const WORSE_BY_POINTS = 10;

/** Colour for each order stage (the label and number are always shown next to it). */
const OUTCOME_COLORS: Record<string, string> = {
  DELIVERED: '#1f5d3f',
  SHIPPED: '#0284c7',
  PROCESSING: '#0d9488',
  PENDING_APPROVAL: '#d97706',
  FAILED_RETURNED: '#dc2626',
  CANCELLED: '#94a3b8',
  REJECTED: '#7c3aed',
};
const FALLBACK_COLOR = '#94a3b8';

/** One coloured piece of the "where orders ended up" bar. */
interface OutcomeSegment extends OutcomeRow {
  color: string;
}

/** A reason with a bar width relative to the most common reason. */
interface ReasonBar extends ReasonRow {
  widthPct: number;
}

/** The sentence under the payment table. */
interface PaymentInsight {
  tone: 'warn' | 'ok' | 'note';
  text: string;
}

/**
 * The Operations tab of the Analytics page: how fast orders move through the
 * warehouse and courier, where orders end up, how well each payment type
 * delivers, and why orders were rejected or parcels came back.
 */
@Component({
  selector: 'admin-analytics-operations',
  standalone: true,
  imports: [],
  templateUrl: './operations-tab.component.html',
  styleUrl: './operations-tab.component.css',
})
export class OperationsTabComponent {
  readonly report = input.required<OperationsReport>();

  protected readonly headerLine = computed(() => {
    const r = this.report();
    const noun = r.orders === 1 ? 'order' : 'orders';
    return `${this.num(r.orders)} ${noun} placed from ${formatDateLabel(r.from)} to ${formatDateLabel(r.to)}`;
  });

  // --- Order outcomes -----------------------------------------------------

  /** Stages that have at least one order, for the stacked bar. */
  protected readonly outcomeSegments = computed<OutcomeSegment[]>(() =>
    this.report()
      .outcomes.filter((o) => o.orders > 0)
      .map((o) => ({ ...o, color: this.outcomeColor(o.key) })),
  );

  protected readonly outcomeSummary = computed(() => {
    const parts = this.outcomeSegments().map((s) => `${s.label}: ${s.orders} orders, ${this.pct(s.sharePct)}`);
    return `Where orders ended up. ${parts.join('. ')}.`;
  });

  // --- Payment type -------------------------------------------------------

  protected readonly paymentInsight = computed<PaymentInsight | null>(() => {
    const rows = this.report().paymentRisk;
    if (rows.length === 0) {
      return null;
    }
    const cod = rows.find((r) => r.key === 'COD');
    const paid = rows.find((r) => r.key === 'FULLY_PAID');
    if (!cod || !paid || !this.hasEnoughOrders(cod) || !this.hasEnoughOrders(paid)) {
      return {
        tone: 'note',
        text:
          `Not enough orders yet to compare pay on delivery with prepaid. ` +
          `Each needs at least ${MIN_ORDERS_TO_COMPARE} delivered or failed orders.`,
      };
    }
    const gap = Math.round((paid.successRatePct - cod.successRatePct) * 10) / 10;
    const codText = this.pct(cod.successRatePct);
    const paidText = this.pct(paid.successRatePct);
    if (gap > WORSE_BY_POINTS) {
      return {
        tone: 'warn',
        text:
          `Pay-on-delivery orders reach the customer ${this.num(gap)} points less often than fully prepaid ` +
          `orders (${codText} against ${paidText}). Consider asking for a part payment up front on ` +
          `pay-on-delivery orders to cut failed deliveries.`,
      };
    }
    if (gap < 0) {
      return {
        tone: 'ok',
        text: `Pay-on-delivery orders are delivered as often as fully prepaid orders (${codText} against ${paidText}).`,
      };
    }
    return {
      tone: 'ok',
      text:
        `Pay-on-delivery orders (${codText}) and fully prepaid orders (${paidText}) are delivered about equally ` +
        `often. The gap is ${this.num(gap)} points, so a larger payment up front is not needed for delivery reasons.`,
    };
  });

  // --- Reasons ------------------------------------------------------------

  protected readonly rejectBars = computed(() => this.toBars(this.report().rejectReasons));
  protected readonly rtoBars = computed(() => this.toBars(this.report().rtoReasons));
  protected readonly rejectTotal = computed(() => this.sumOrders(this.report().rejectReasons));
  protected readonly rtoTotal = computed(() => this.sumOrders(this.report().rtoReasons));

  // --- Display helpers ----------------------------------------------------

  protected hours(value: number | null | undefined): string {
    return humanHours(value);
  }

  protected num(value: number | null | undefined): string {
    return (value ?? 0).toLocaleString('en-IN', { maximumFractionDigits: 1 });
  }

  protected pct(value: number | null | undefined): string {
    return `${(value ?? 0).toLocaleString('en-IN', { maximumFractionDigits: 1 })}%`;
  }

  protected hasSamples(stat: SpeedStat): boolean {
    return stat.samples > 0;
  }

  protected outcomeColor(key: string): string {
    return OUTCOME_COLORS[key] ?? FALLBACK_COLOR;
  }

  /** Delivered or failed orders: the ones that have an outcome to measure. */
  protected finished(row: PaymentRiskRow): number {
    return row.delivered + row.failed;
  }

  /** CSS modifier for a success rate: green from 80, amber from 50, red below. */
  protected rateTone(pct: number): string {
    if (pct >= 80) {
      return 'ops-rate--good';
    }
    return pct >= 50 ? 'ops-rate--fair' : 'ops-rate--low';
  }

  /** A word next to the number, so colour is never the only signal. */
  protected rateWord(pct: number): string {
    if (pct >= 80) {
      return 'Good';
    }
    return pct >= 50 ? 'Fair' : 'Low';
  }

  protected rateIcon(pct: number): string {
    if (pct >= 80) {
      return 'ti-circle-check';
    }
    return pct >= 50 ? 'ti-alert-circle' : 'ti-alert-triangle';
  }

  protected insightIcon(tone: PaymentInsight['tone']): string {
    if (tone === 'warn') {
      return 'ti-alert-triangle';
    }
    return tone === 'ok' ? 'ti-circle-check' : 'ti-info-circle';
  }

  protected insightClass(tone: PaymentInsight['tone']): string {
    if (tone === 'warn') {
      return 'alert-warning';
    }
    return tone === 'ok' ? 'alert-success' : 'alert-info';
  }

  private hasEnoughOrders(row: PaymentRiskRow): boolean {
    return this.finished(row) >= MIN_ORDERS_TO_COMPARE;
  }

  private sumOrders(rows: readonly ReasonRow[]): number {
    return rows.reduce((total, r) => total + r.orders, 0);
  }

  private toBars(rows: readonly ReasonRow[]): ReasonBar[] {
    const max = Math.max(1, ...rows.map((r) => r.orders));
    return [...rows]
      .sort((a, b) => b.orders - a.orders)
      .map((r) => ({ ...r, widthPct: Math.max(r.orders > 0 ? 3 : 0, Math.round((r.orders / max) * 100)) }));
  }
}
