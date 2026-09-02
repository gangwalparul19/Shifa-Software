import { Component, Input, computed, signal } from '@angular/core';
import { OrderStatus, PaymentStatus } from 'core';

/** Which family of status the badge represents. */
export type StatusKind = 'order' | 'payment';

/** The consistent colour tones shared across the admin (mirrors the CSS groups). */
export type StatusTone = 'pending' | 'progress' | 'done' | 'bad' | 'neutral';

/**
 * Maps an order lifecycle status to a colour tone. The tones align with the
 * storefront's single-green "on track" language while adding admin-side nuance:
 * amber for work waiting on a human, blue for in-flight progress, green for
 * successful terminal states, and red for failures/returns.
 */
export function orderStatusTone(status: OrderStatus | string): StatusTone {
  switch (status) {
    case OrderStatus.PENDING_ADMIN_APPROVAL:
      return 'pending';
    case OrderStatus.REJECTED:
    case OrderStatus.CANCELLED:
    case OrderStatus.RTO:
    case OrderStatus.REDISPATCH:
    case OrderStatus.CUSTOMER_REJECTED:
    case OrderStatus.DELIVERY_FAILED:
      return 'bad';
    case OrderStatus.DELIVERED:
    case OrderStatus.COD_COLLECTED:
    case OrderStatus.CLOSED:
      return 'done';
    default:
      return 'progress';
  }
}

/** Maps a payment status to a colour tone consistent with the order tones. */
export function paymentStatusTone(status: PaymentStatus | string): StatusTone {
  switch (status) {
    case PaymentStatus.FULLY_PAID:
      return 'done';
    case PaymentStatus.PARTIALLY_PAID:
      return 'pending';
    case PaymentStatus.COD:
      return 'progress';
    default:
      return 'neutral';
  }
}

/** Humanises an enum-ish status value ("Pending_Admin_Approval" → "Pending Admin Approval"). */
export function humanizeStatus(status: string): string {
  return status
    .replaceAll('_', ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase())
    .replace(/\bRto\b/i, 'RTO')
    .replace(/\bCod\b/i, 'COD');
}

/**
 * Canonical badge-tone class for a staff verification status (VERIFIED → green,
 * REJECTED → red, PENDING/other → amber). Single source shared by the Users,
 * Salespeople and My Profile pages so the pill colours match app-wide.
 */
export function verificationBadgeClass(status: string | null | undefined): string {
  switch (status) {
    case 'VERIFIED':
      return 'tone-green';
    case 'REJECTED':
      return 'tone-red';
    case 'PENDING':
      return 'tone-amber';
    default:
      return 'tone-grey';
  }
}

/**
 * A consistent status pill reused across orders, approval, packing and the
 * dashboard. It renders a Tabler {@code .badge} carrying a {@code data-group}
 * tone attribute so the existing colour rules apply — one shared colour
 * language for every status in the admin.
 */
@Component({
  selector: 'admin-status-badge',
  standalone: true,
  template: `<span class="badge" [attr.data-group]="tone()">{{ label() }}</span>`,
})
export class StatusBadgeComponent {
  @Input({ required: true })
  set status(value: string) {
    this._status.set(value);
  }
  private readonly _status = signal('');

  /** Whether the value is an order lifecycle status or a payment status. */
  @Input() kind: StatusKind = 'order';

  /** Set false to show the raw value instead of a humanised label. */
  @Input() humanize = true;

  protected readonly tone = computed<StatusTone>(() =>
    this.kind === 'payment'
      ? paymentStatusTone(this._status())
      : orderStatusTone(this._status()),
  );

  protected readonly label = computed(() =>
    this.humanize ? humanizeStatus(this._status()) : this._status(),
  );
}
