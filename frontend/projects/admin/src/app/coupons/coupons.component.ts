import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from 'core';
import { Coupon, CouponRequest, CouponType, CouponsService } from './coupons.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';

/**
 * Admin coupon management page (Phase D).
 *
 * <p>A Tabler-styled table of coupons (code, type, value, min cart, active,
 * validity window, usage/limit) with an add/edit modal and an active toggle.
 * Create/update go through {@link CouponsService}; a duplicate code (409) is
 * surfaced inline on the form. ADMIN-guarded by the route.
 */
@Component({
  selector: 'admin-coupons',
  imports: [ReactiveFormsModule, PageHeaderComponent, StatePanelComponent, DensityToggleComponent],
  templateUrl: './coupons.component.html',
  styleUrl: './coupons.component.css',
})
export class CouponsComponent implements OnInit {
  private readonly service = inject(CouponsService);
  private readonly fb = inject(FormBuilder);
  private readonly confirmService = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly coupons = signal<Coupon[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly togglingId = signal<number | null>(null);

  /** The coupon being edited (form open); null when the form is closed. */
  protected readonly editing = signal<Coupon | null>(null);
  protected readonly creating = signal(false);
  protected readonly formOpen = computed(() => this.creating() || this.editing() !== null);
  protected readonly formError = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.maxLength(40), Validators.pattern(/^[A-Za-z0-9_-]+$/)]],
    description: ['', [Validators.maxLength(255)]],
    type: ['PERCENT' as CouponType, [Validators.required]],
    value: ['', [Validators.pattern(/^\d{1,10}(\.\d{1,2})?$/)]],
    minCartAmount: ['', [Validators.pattern(/^\d{1,10}(\.\d{1,2})?$/)]],
    maxDiscountAmount: ['', [Validators.pattern(/^\d{1,10}(\.\d{1,2})?$/)]],
    active: [true],
    startsAt: [''],
    endsAt: [''],
    usageLimit: ['', [Validators.pattern(/^\d{1,7}$/)]],
    perCustomerLimit: ['', [Validators.pattern(/^\d{1,5}$/)]],
  });

  ngOnInit(): void {
    this.load();
  }

  /** Whether the currently selected type is a free-shipping coupon (hides value). */
  protected readonly isFreeShipping = computed(() => this.form.controls.type.value === 'FREE_SHIPPING');

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.list().subscribe({
      next: (items) => {
        this.coupons.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load coupons. Please try again.');
        this.loading.set(false);
      },
    });
  }

  typeLabel(type: CouponType): string {
    switch (type) {
      case 'PERCENT':
        return 'Percent';
      case 'FLAT':
        return 'Flat';
      case 'FREE_SHIPPING':
        return 'Free shipping';
      default:
        return type;
    }
  }

  valueLabel(coupon: Coupon): string {
    switch (coupon.type) {
      case 'PERCENT':
        return `${trimAmount(coupon.value)}%`;
      case 'FLAT':
        return `₹${coupon.value}`;
      case 'FREE_SHIPPING':
        return '—';
      default:
        return coupon.value;
    }
  }

  minCartLabel(coupon: Coupon): string {
    return coupon.minCartAmount ? `₹${coupon.minCartAmount}` : '—';
  }

  usageLabel(coupon: Coupon): string {
    return coupon.usageLimit != null ? `${coupon.usedCount} / ${coupon.usageLimit}` : `${coupon.usedCount}`;
  }

  windowLabel(coupon: Coupon): string {
    const from = coupon.startsAt ? fmt(coupon.startsAt) : '—';
    const to = coupon.endsAt ? fmt(coupon.endsAt) : '—';
    if (!coupon.startsAt && !coupon.endsAt) {
      return 'Always';
    }
    return `${from} → ${to}`;
  }

  // --- Add / edit form ---------------------------------------------------

  openCreate(): void {
    this.formError.set(null);
    this.editing.set(null);
    this.form.reset({
      code: '',
      description: '',
      type: 'PERCENT',
      value: '',
      minCartAmount: '',
      maxDiscountAmount: '',
      active: true,
      startsAt: '',
      endsAt: '',
      usageLimit: '',
      perCustomerLimit: '',
    });
    this.creating.set(true);
  }

  openEdit(coupon: Coupon): void {
    this.formError.set(null);
    this.creating.set(false);
    this.form.reset({
      code: coupon.code,
      description: coupon.description ?? '',
      type: coupon.type,
      value: coupon.value ?? '',
      minCartAmount: coupon.minCartAmount ?? '',
      maxDiscountAmount: coupon.maxDiscountAmount ?? '',
      active: coupon.active,
      startsAt: toLocalInput(coupon.startsAt),
      endsAt: toLocalInput(coupon.endsAt),
      usageLimit: coupon.usageLimit != null ? String(coupon.usageLimit) : '',
      perCustomerLimit: coupon.perCustomerLimit != null ? String(coupon.perCustomerLimit) : '',
    });
    this.editing.set(coupon);
  }

  closeForm(): void {
    this.creating.set(false);
    this.editing.set(null);
    this.formError.set(null);
  }

  save(): void {
    if (this.saving()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    const freeShipping = raw.type === 'FREE_SHIPPING';
    const request: CouponRequest = {
      code: raw.code.trim().toUpperCase(),
      description: raw.description.trim() || null,
      type: raw.type,
      value: freeShipping ? '0' : raw.value.trim() || '0',
      minCartAmount: raw.minCartAmount.trim() || null,
      maxDiscountAmount: raw.type === 'PERCENT' ? raw.maxDiscountAmount.trim() || null : null,
      active: raw.active,
      startsAt: fromLocalInput(raw.startsAt),
      endsAt: fromLocalInput(raw.endsAt),
      usageLimit: raw.usageLimit ? Number(raw.usageLimit) : null,
      perCustomerLimit: raw.perCustomerLimit ? Number(raw.perCustomerLimit) : null,
    };

    this.saving.set(true);
    this.formError.set(null);
    const editing = this.editing();
    const op$ = editing ? this.service.update(editing.id, request) : this.service.create(request);
    op$.subscribe({
      next: () => {
        this.saving.set(false);
        this.showToast('ok', editing ? 'Coupon updated.' : 'Coupon created.');
        this.closeForm();
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.formError.set(this.describeError(err));
      },
    });
  }

  // --- Active toggle ------------------------------------------------------

  async toggleActive(coupon: Coupon): Promise<void> {
    if (this.togglingId() !== null) {
      return;
    }
    // Deactivating a live coupon stops it working at checkout — confirm first.
    if (coupon.active) {
      const confirmed = await this.confirmService.confirm({
        title: 'Deactivate coupon',
        message: `Deactivate "${coupon.code}"? Customers will no longer be able to apply it at checkout.`,
        confirmLabel: 'Deactivate',
        danger: true,
        icon: 'ti-discount-off',
      });
      if (!confirmed) {
        return;
      }
    }
    this.togglingId.set(coupon.id);
    this.service.setActive(coupon.id, !coupon.active).subscribe({
      next: (updated) => {
        this.coupons.update((items) => items.map((c) => (c.id === updated.id ? updated : c)));
        this.togglingId.set(null);
        this.showToast('ok', updated.active ? `${coupon.code} activated.` : `${coupon.code} deactivated.`);
      },
      error: () => {
        this.togglingId.set(null);
        this.showToast('error', `Could not update ${coupon.code}.`);
      },
    });
  }

  // --- Helpers ------------------------------------------------------------

  private describeError(err: HttpErrorResponse): string {
    const body = err.error as ApiError | undefined;
    if (body?.code === 'DUPLICATE_COUPON_CODE') {
      return body.message || 'A coupon with that code already exists.';
    }
    if (body?.details?.length) {
      return body.details.join(' ');
    }
    return body?.message || 'Could not save. Please try again.';
  }

  private showToast(kind: 'ok' | 'error', text: string): void {
    if (kind === 'error') {
      this.toasts.error(text);
    } else {
      this.toasts.success(text);
    }
  }
}

/** Trims trailing ".00" / zeros from a percent amount for display (e.g. "10.00" -> "10"). */
function trimAmount(value: string): string {
  if (!value) {
    return value;
  }
  return value.replace(/\.00$/, '').replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
}

/** Formats an ISO datetime as a short date (dd MMM). */
function fmt(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return iso;
  }
  return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: '2-digit' });
}

/** Converts a backend ISO datetime to the value shape a datetime-local input expects. */
function toLocalInput(iso?: string | null): string {
  if (!iso) {
    return '';
  }
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return '';
  }
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Converts a datetime-local input value to an ISO-ish local datetime, or null. */
function fromLocalInput(value: string): string | null {
  const trimmed = value?.trim();
  if (!trimmed) {
    return null;
  }
  // datetime-local already yields "YYYY-MM-DDTHH:mm"; append seconds for the backend LocalDateTime.
  return trimmed.length === 16 ? `${trimmed}:00` : trimmed;
}
