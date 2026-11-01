import { Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { formatDateLabel } from '../../shared/date-label.util';
import { formatInr } from '../../shared/inr.pipe';
import { StatePanelComponent } from '../../shared/state-panel.component';
import { LeadConversionRow, LeadFunnel, MixRow, OverviewReport } from '../analytics.model';

/** Codes whose plain sentence-case form would read wrongly. */
const SPECIAL_LABELS: Record<string, string> = {
  WHATSAPP: 'WhatsApp',
};

/** Headline numbers for the lead funnel. */
interface FunnelTotals {
  leads: number;
  won: number;
  ratePct: number;
}

/** One lost-lead reason with its share of all lost leads. */
interface LostReasonShare {
  label: string;
  count: number;
  sharePct: number;
}

/** One stage of the open pipeline. */
interface PipelineChip {
  label: string;
  count: number;
}

/**
 * The Acquisition tab of the Analytics page: which lead sources bring revenue,
 * how well leads from each source turn into customers, why leads are lost, and
 * what is in the open pipeline right now.
 */
@Component({
  selector: 'admin-analytics-acquisition',
  standalone: true,
  imports: [RouterLink, StatePanelComponent],
  templateUrl: './acquisition-tab.component.html',
  styleUrl: './acquisition-tab.component.css',
})
export class AcquisitionTabComponent {
  readonly overview = input<OverviewReport | null>(null);
  readonly funnel = input<LeadFunnel | null>(null);
  readonly loading = input(false);
  readonly error = input<string | null>(null);
  readonly retry = output<void>();

  // --- Revenue by lead source ---------------------------------------------

  protected readonly sourceRows = computed<MixRow[]>(() => this.overview()?.leadSourceMix ?? []);

  protected readonly salesPeriod = computed(() => {
    const o = this.overview();
    return o ? `${formatDateLabel(o.from)} to ${formatDateLabel(o.to)}` : '';
  });

  // --- Lead funnel --------------------------------------------------------

  /** Sources with the most leads first. */
  protected readonly funnelRows = computed<LeadConversionRow[]>(() =>
    [...(this.funnel()?.conversion.bySource ?? [])].sort((a, b) => b.leads - a.leads),
  );

  protected readonly funnelTotals = computed<FunnelTotals>(() => {
    const rows = this.funnelRows();
    const leads = rows.reduce((sum, r) => sum + r.leads, 0);
    const won = rows.reduce((sum, r) => sum + r.won, 0);
    return { leads, won, ratePct: leads > 0 ? Math.round((won * 1000) / leads) / 10 : 0 };
  });

  protected readonly lostTotal = computed(() => {
    const lost = this.funnel()?.lostReasons;
    if (!lost) {
      return 0;
    }
    return lost.total > 0 ? lost.total : lost.rows.reduce((sum, r) => sum + r.count, 0);
  });

  protected readonly lostRows = computed<LostReasonShare[]>(() => {
    const rows = this.funnel()?.lostReasons.rows ?? [];
    const total = this.lostTotal();
    return [...rows]
      .sort((a, b) => b.count - a.count)
      .map((r) => ({
        label: this.humanize(r.reason),
        count: r.count,
        sharePct: total > 0 ? Math.round((r.count * 1000) / total) / 10 : 0,
      }));
  });

  protected readonly pipelineChips = computed<PipelineChip[]>(() =>
    (this.funnel()?.pipeline.rows ?? []).map((r) => ({ label: this.humanize(r.status), count: r.count })),
  );

  protected readonly pipelineTotal = computed(() => {
    const pipeline = this.funnel()?.pipeline;
    if (!pipeline) {
      return 0;
    }
    return pipeline.total > 0 ? pipeline.total : pipeline.rows.reduce((sum, r) => sum + r.count, 0);
  });

  // --- Display helpers ----------------------------------------------------

  /** "WHATSAPP" becomes "WhatsApp"; "INBOUND_CALLS" becomes "Inbound calls". */
  protected humanize(code: string | null | undefined): string {
    const raw = (code ?? '').trim();
    if (!raw) {
      return 'Not recorded';
    }
    const special = SPECIAL_LABELS[raw.toUpperCase()];
    if (special) {
      return special;
    }
    const text = raw.replaceAll('_', ' ').toLowerCase();
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  protected money(value: number | null | undefined): string {
    return formatInr(value, 0);
  }

  protected num(value: number | null | undefined): string {
    return (value ?? 0).toLocaleString('en-IN');
  }

  protected pct(value: number | null | undefined): string {
    return `${(value ?? 0).toLocaleString('en-IN', { maximumFractionDigits: 1 })}%`;
  }

  /** A bar width from a 0-100 percentage, kept inside the track. */
  protected barWidth(value: number | null | undefined): number {
    return Math.min(100, Math.max(0, value ?? 0));
  }

  /** Delivery success: green from 80, amber from 50, red below. The number is always shown. */
  protected rateTone(pct: number): string {
    if (pct >= 80) {
      return 'acq-rate--good';
    }
    return pct >= 50 ? 'acq-rate--fair' : 'acq-rate--low';
  }
}
