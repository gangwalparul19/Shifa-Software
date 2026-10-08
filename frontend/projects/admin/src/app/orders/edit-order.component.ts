import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiError, AuthService, Product, Role, paiseToMoney, toPaise } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { StateTypeaheadComponent } from '../shared/state-typeahead.component';
import { ProductTypeaheadComponent } from '../shared/product-typeahead.component';
import { InrPipe } from '../shared/inr.pipe';
import { StatesService } from '../shared/states.service';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { CatalogService } from './catalog.service';
import { OrdersService } from './orders.service';
import {
  CreateOrderLineItem,
  LEAD_SOURCE_OPTIONS,
  LeadSource,
  OrderDiscountType,
  UpdateOrderRequest,
} from './orders.model';

/**
 * Admin edit-order page (edit-order feature) — the admin UI for
 * {@code PUT /api/admin/orders/{id}}.
 *
 * <p>Lets an ADMIN correct almost every detail a salesperson entered when
 * punching an order: customer identity, delivery address, line items (product/
 * quantity/rate), lead source, buyer GSTIN, order-level discount, and the order
 * note. Payment fields (amount received / screenshot) are intentionally not
 * editable here — payment stays governed by the existing payment-verification
 * flow. Only reachable while the order is still {@code Pending_Admin_Approval}
 * or {@code Approved}; the server rejects the edit with a 409 once fulfilment
 * has begun (label generated / packed / dispatched, etc.), which this page
 * surfaces as an error and a link back to the order.
 *
 * <p>Reuses the same product/state typeaheads and price-band validation as the
 * New Order form so the editing experience matches order entry.
 */
@Component({
  selector: 'admin-edit-order',
  imports: [
    ReactiveFormsModule,
    PageHeaderComponent,
    StatePanelComponent,
    StateTypeaheadComponent,
    ProductTypeaheadComponent,
    InrPipe,
  ],
  templateUrl: './edit-order.component.html',
  styleUrl: './new-order.component.css',
})
export class EditOrderComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly orders = inject(OrdersService);
  private readonly auth = inject(AuthService);
  private readonly catalog = inject(CatalogService);
  private readonly statesService = inject(StatesService);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly orderId = signal<number | null>(null);
  protected readonly orderCode = signal<string>('');
  protected readonly orderStatus = signal<string>('');

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly notEditable = signal(false);

  protected readonly products = signal<Product[]>([]);
  protected readonly productsLoading = signal(true);
  protected readonly productsError = signal<string | null>(null);
  protected readonly states = signal<string[]>([]);

  protected readonly submitting = signal(false);
  protected readonly submitAttempted = signal(false);
  protected readonly serverErrors = signal<string[]>([]);

  protected readonly leadSourceOptions = LEAD_SOURCE_OPTIONS;

  protected readonly form = this.fb.nonNullable.group({
    customerName: ['', [Validators.required, Validators.maxLength(100)]],
    customerMobile: ['', [Validators.required, Validators.pattern(/^\d{10}$/)]],
    alternateMobile: ['', [Validators.pattern(/^\d{10}$/)]],
    customerEmail: ['', [Validators.email, Validators.maxLength(150)]],
    addressLine: ['', [Validators.required, Validators.maxLength(250)]],
    city: ['', [Validators.required, Validators.maxLength(100)]],
    state: ['', [Validators.required, Validators.maxLength(100)]],
    postalCode: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
    leadSource: ['' as '' | LeadSource, [Validators.required]],
    leadSourceNote: ['', [Validators.maxLength(200)]],
    buyerGstin: [
      '',
      [
        Validators.maxLength(15),
        Validators.pattern(/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]$/),
      ],
    ],
    items: this.fb.array([this.newItem()]),
    discountType: ['' as '' | OrderDiscountType],
    discountValue: [0, [Validators.min(0)]],
    notes: ['', [Validators.maxLength(1000)]],
  });

  /** A snapshot of the fields that drive totals, refreshed on every change. */
  private readonly model = signal<ReturnType<EditOrderComponent['snapshot']>>({
    items: [],
    leadSource: '',
    discountType: '',
    discountValue: 0,
  });

  protected readonly showLeadSourceNote = computed(() => this.model().leadSource === 'OTHER');

  ngOnInit(): void {
    this.loadProducts();
    this.loadStates();

    this.model.set(this.snapshot());
    this.form.valueChanges.subscribe(() => this.model.set(this.snapshot()));

    const idParam = this.route.snapshot.paramMap.get('id');
    const id = idParam ? Number(idParam) : NaN;
    if (!Number.isFinite(id) || id <= 0) {
      this.loadError.set('Invalid order.');
      this.loading.set(false);
      return;
    }
    this.orderId.set(id);
    this.loadOrder(id);
  }

  /** Retries loading the order after a failed initial load. */
  retryLoad(): void {
    const id = this.orderId();
    if (id !== null) {
      this.loadOrder(id);
    }
  }

  private loadOrder(id: number): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.orders.detail(id).subscribe({
      next: (o) => {
        this.orderCode.set(o.orderCode);
        this.orderStatus.set(o.orderStatus);
        this.notEditable.set(
          o.orderStatus !== 'PENDING_ADMIN_APPROVAL' && o.orderStatus !== 'APPROVED',
        );
        this.form.patchValue({
          customerName: o.customerName ?? '',
          customerMobile: o.customerMobile ?? '',
          alternateMobile: o.alternateMobile ?? '',
          addressLine: o.addressLine ?? '',
          city: o.city ?? '',
          state: o.state ?? '',
          postalCode: o.postalCode ?? '',
          buyerGstin: o.buyerGstin ?? '',
          notes: o.notes ?? '',
          discountType: (o.discountType ?? '') as '' | OrderDiscountType,
          discountValue: o.discountValue != null ? Number(o.discountValue) : 0,
        });
        const arr = this.items;
        while (arr.length) {
          arr.removeAt(0);
        }
        const lines = (o.items ?? []).filter((li) => li.productId != null);
        if (lines.length === 0) {
          arr.push(this.newItem());
        } else {
          for (const li of lines) {
            const g = this.newItem();
            g.controls['productId'].setValue(li.productId as number);
            g.controls['quantity'].setValue(Math.min(999, Math.max(1, li.quantity || 1)));
            g.controls['rate'].setValue(li.rate != null ? Number(li.rate) : null);
            arr.push(g);
          }
        }
        this.model.set(this.snapshot());
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load this order.');
        this.loading.set(false);
      },
    });
  }

  // --- Catalog + states ----------------------------------------------------

  loadProducts(): void {
    this.productsLoading.set(true);
    this.productsError.set(null);
    this.catalog.products().subscribe({
      next: (rows) => {
        this.products.set(rows);
        this.productsLoading.set(false);
      },
      error: () => {
        this.productsError.set('Could not load products. Please try again.');
        this.productsLoading.set(false);
      },
    });
  }

  loadStates(): void {
    this.statesService.activeNames().subscribe({
      next: (rows) => this.states.set(rows),
      error: () => this.states.set([]),
    });
  }

  // --- Line items ------------------------------------------------------------

  get items(): FormArray<FormGroup> {
    return this.form.controls.items as FormArray<FormGroup>;
  }

  private newItem(): FormGroup {
    return this.fb.nonNullable.group({
      productId: [null as number | null, [Validators.required]],
      quantity: [1, [Validators.required, Validators.min(1), Validators.max(999)]],
      rate: [null as number | null, [Validators.min(0)]],
    });
  }

  addItem(): void {
    this.items.push(this.newItem());
  }

  removeItem(index: number): void {
    if (this.items.length > 1) {
      this.items.removeAt(index);
    }
  }

  decQty(index: number): void {
    const control = this.items.at(index).controls['quantity'];
    const next = Math.max(1, (Number(control.value) || 1) - 1);
    control.setValue(next);
    control.markAsTouched();
  }

  incQty(index: number): void {
    const control = this.items.at(index).controls['quantity'];
    const next = Math.min(999, (Number(control.value) || 0) + 1);
    control.setValue(next);
    control.markAsTouched();
  }

  onProductChange(index: number): void {
    const group = this.items.at(index);
    const productId = group.controls['productId'].value as number | null;
    const product = this.products().find((p) => p.id === productId);
    if (product) {
      group.controls['rate'].setValue(Number(product.salePrice));
      this.model.set(this.snapshot());
    }
  }

  productForLine(index: number): Product | undefined {
    const id = this.items.at(index).controls['productId'].value as number | null;
    return id == null ? undefined : this.products().find((p) => p.id === id);
  }

  lineBand(index: number): { min: number; max: number; wtMl: string | null } | null {
    const p = this.productForLine(index);
    if (!p) {
      return null;
    }
    const min = Number(p.minimumRate ?? p.salePrice);
    const max = Number(p.mrp);
    return { min, max, wtMl: p.wtMl ?? null };
  }

  lineRateError(index: number): string | null {
    const band = this.lineBand(index);
    if (!band) {
      return null;
    }
    const raw = this.items.at(index).controls['rate'].value as number | null;
    if (raw === null || raw === undefined || (raw as unknown) === '') {
      return null;
    }
    const rate = Number(raw);
    if (Number.isNaN(rate)) {
      return null;
    }
    if (rate < band.min) {
      return `Below minimum ₹${band.min}`;
    }
    if (band.max && rate > band.max) {
      return `Above MRP ₹${band.max}`;
    }
    return null;
  }

  hasBandErrors(): boolean {
    return this.items.controls.some((_, i) => this.lineRateError(i) !== null);
  }

  itemInvalid(index: number, name: 'productId' | 'quantity' | 'rate'): boolean {
    const control = this.items.at(index).controls[name];
    return control.invalid && (control.touched || this.submitAttempted());
  }

  // --- Totals ----------------------------------------------------------------

  protected readonly lineTotals = computed(() =>
    this.model().items.map((it) => toPaise(it.rate ?? 0) * (it.quantity || 0)),
  );

  protected readonly subtotalPaise = computed(() =>
    this.lineTotals().reduce((sum, cents) => sum + cents, 0),
  );

  protected readonly discountPaise = computed(() => {
    const sub = this.subtotalPaise();
    const m = this.model();
    let d = 0;
    if (m.discountType === 'PERCENT') {
      const pct = Math.max(0, Math.min(100, m.discountValue || 0));
      d = Math.round((sub * pct) / 100);
    } else if (m.discountType === 'FLAT') {
      d = toPaise(m.discountValue || 0);
    }
    return Math.max(0, Math.min(sub, d));
  });

  protected readonly orderTotalPaise = computed(() => {
    const net = this.subtotalPaise() - this.discountPaise();
    return Math.round(net / 100) * 100;
  });

  formatMoney(paise: number): string {
    return `₹${paiseToMoney(paise)}`;
  }

  // --- Validation helpers ------------------------------------------------

  invalid(controlName: keyof EditOrderComponent['form']['controls']): boolean {
    const control = this.form.controls[controlName];
    return control.invalid && (control.touched || this.submitAttempted());
  }

  onGstinInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const upper = input.value.toUpperCase();
    if (upper !== input.value) {
      this.form.controls.buyerGstin.setValue(upper);
    }
  }

  private messageOf(err: HttpErrorResponse): string | null {
    const body = err.error as ApiError | undefined;
    return body?.message ?? null;
  }

  private snapshot(): {
    items: { productId: number | null; quantity: number; rate: number | null }[];
    leadSource: '' | LeadSource;
    discountType: '' | OrderDiscountType;
    discountValue: number;
  } {
    const raw = this.form.getRawValue();
    return {
      items: raw.items.map((it) => ({
        productId: (it['productId'] ?? null) as number | null,
        quantity: Number(it['quantity']) || 0,
        rate: it['rate'] === null || it['rate'] === undefined ? null : Number(it['rate']),
      })),
      leadSource: raw.leadSource,
      discountType: (raw.discountType ?? '') as '' | OrderDiscountType,
      discountValue: Number(raw.discountValue) || 0,
    };
  }

  // --- Submit ----------------------------------------------------------------

  async submit(): Promise<void> {
    this.submitAttempted.set(true);
    this.serverErrors.set([]);
    if (this.submitting() || this.notEditable()) {
      return;
    }
    this.items.controls.forEach((group) => group.markAllAsTouched());
    if (this.form.invalid || this.items.invalid || this.orderTotalPaise() <= 0 || this.hasBandErrors()) {
      this.form.markAllAsTouched();
      this.toasts.error('Please fix the highlighted fields before saving.');
      return;
    }

    const id = this.orderId();
    if (id === null) {
      return;
    }

    const confirmed = await this.confirm.confirm({
      title: 'Save order changes',
      message: `Save these changes to order ${this.orderCode()}? The new total will be ${this.formatMoney(this.orderTotalPaise())}.`,
      confirmLabel: 'Save changes',
      icon: 'ti-edit',
    });
    if (!confirmed) {
      return;
    }

    const raw = this.snapshot();
    const alternateMobile = this.form.controls.alternateMobile.value.trim();
    const email = this.form.controls.customerEmail.value.trim();
    const note = this.form.controls.leadSourceNote.value.trim();
    const orderNotes = this.form.controls.notes.value.trim();
    const buyerGstin = this.form.controls.buyerGstin.value.trim().toUpperCase();
    const isOther = raw.leadSource === 'OTHER';

    const payload: UpdateOrderRequest = {
      customerName: this.form.controls.customerName.value.trim(),
      customerMobile: this.form.controls.customerMobile.value.trim(),
      ...(alternateMobile ? { alternateMobile } : {}),
      ...(email ? { customerEmail: email } : {}),
      addressLine: this.form.controls.addressLine.value.trim(),
      city: this.form.controls.city.value.trim(),
      state: this.form.controls.state.value.trim(),
      postalCode: this.form.controls.postalCode.value.trim(),
      items: raw.items.map<CreateOrderLineItem>((it) => ({
        productId: it.productId as number,
        quantity: it.quantity,
        ...(it.rate != null ? { rate: it.rate } : {}),
      })),
      leadSource: raw.leadSource as LeadSource,
      ...(isOther && note ? { leadSourceNote: note } : {}),
      ...(orderNotes ? { notes: orderNotes } : {}),
      ...(buyerGstin ? { buyerGstin } : {}),
      ...(raw.discountType
        ? { discountType: raw.discountType as OrderDiscountType, discountValue: raw.discountValue || 0 }
        : {}),
    };

    this.submitting.set(true);
    // An admin edits via the admin endpoint (can also edit an APPROVED order);
    // the creating salesperson / team lead edits their OWN order via the
    // own-order endpoint (PENDING only). The server enforces both in either case.
    const save$ = this.auth.hasAnyRole(Role.ADMIN)
      ? this.orders.updateOrder(id, payload)
      : this.orders.updateOwnOrder(id, payload);
    save$.subscribe({
      next: (order) => {
        this.submitting.set(false);
        this.toasts.success(`Order ${order.orderCode} updated.`);
        void this.router.navigate(['/orders'], { queryParams: { q: order.orderCode } });
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        if (err.status === 409) {
          this.notEditable.set(true);
        }
        const body = err.error as ApiError | undefined;
        const details = body?.details ?? [];
        this.serverErrors.set(details.length ? details : []);
        this.toasts.error(this.messageOf(err) ?? 'Could not save the changes. Please try again.');
      },
    });
  }

  cancel(): void {
    const id = this.orderId();
    void this.router.navigate(['/orders'], id ? { queryParams: { q: this.orderCode() } } : undefined);
  }
}
