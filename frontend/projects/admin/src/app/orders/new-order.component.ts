import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import {
  FormArray,
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiError, Product, paiseToMoney, toPaise } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { StateTypeaheadComponent } from '../shared/state-typeahead.component';
import { StatesService } from '../shared/states.service';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { CatalogService } from './catalog.service';
import { OrdersService } from './orders.service';
import { OfflineOrderQueueService } from './offline-order-queue.service';
import { CustomersService } from '../customers/customers.service';
import { CustomerRisk, riskLabel, riskPillClass } from '../customers/customers.model';
import { LeadsService } from '../leads/leads.service';
import { LeadConvertRequest } from '../leads/leads.model';
import {
  CreateOrderLineItem,
  CreateOrderRequest,
  LEAD_SOURCE_OPTIONS,
  LeadSource,
} from './orders.model';

/** The three phases the payment-screenshot upload can be in. */
type UploadState = 'idle' | 'uploading' | 'done' | 'error';

/**
 * Salesperson / admin order-entry page (Req 7) — the admin UI for
 * {@code POST /api/orders}.
 *
 * <p>A reactive form captures the customer + shipping details, a dynamic list of
 * line items (each a product picked from the PUBLIC catalog, a 1..999 quantity,
 * and an editable rate pre-filled from the product's sale price), and the amount
 * received. When money changes hands a payment screenshot is uploaded first via
 * the two-step endpoint and its storage key is attached to the order. On success
 * the created order code is surfaced and the user is returned to the orders list.
 *
 * <p>Client-side validation mirrors the backend contract exactly (10-digit
 * mobile, 6-digit pincode, at least one item, non-negative amount, screenshot
 * required when {@code amountReceived > 0}); server validation errors are still
 * surfaced inline/as toasts with the entered values retained.
 */
@Component({
  selector: 'admin-new-order',
  imports: [
    ReactiveFormsModule,
    PageHeaderComponent,
    StatePanelComponent,
    StateTypeaheadComponent,
  ],
  templateUrl: './new-order.component.html',
  styleUrl: './new-order.component.css',
})
export class NewOrderComponent implements OnInit, OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly orders = inject(OrdersService);
  protected readonly offlineQueue = inject(OfflineOrderQueueService);
  private readonly customers = inject(CustomersService);
  private readonly catalog = inject(CatalogService);
  private readonly statesService = inject(StatesService);
  private readonly leads = inject(LeadsService);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  // --- Convert-from-lead mode (design §Convert Flow, Req 4) --------------
  /**
   * When the form is opened as {@code /orders/new?leadId=N} it runs in convert
   * mode: the customer identity + lead source are seeded from the lead and the
   * customer/source fields are locked (the server forces them from the lead), and
   * on save it posts {@code POST /api/leads/{id}/convert} instead of the plain
   * order-create endpoint — creating the order and marking the lead WON.
   */
  protected readonly convertLeadId = signal<number | null>(null);
  protected readonly convertLeadName = signal<string>('');

  // --- Product catalog (picker source) ------------------------------------
  protected readonly products = signal<Product[]>([]);
  protected readonly productsLoading = signal(true);
  protected readonly productsError = signal<string | null>(null);

  /** Selectable delivery states for the state typeahead (from GET /api/states). */
  protected readonly states = signal<string[]>([]);

  // --- Payment screenshot upload ------------------------------------------
  protected readonly uploadState = signal<UploadState>('idle');
  protected readonly screenshotKey = signal<string | null>(null);
  protected readonly screenshotName = signal<string | null>(null);
  protected readonly screenshotPreview = signal<string | null>(null);
  protected readonly uploadError = signal<string | null>(null);

  // --- Submit state -------------------------------------------------------
  protected readonly submitting = signal(false);
  protected readonly submitAttempted = signal(false);
  protected readonly serverErrors = signal<string[]>([]);

  /**
   * Number of prior orders for the entered mobile (repeat-customer hint, Req 22.2).
   * Null until a valid 10-digit mobile has been checked; 0 means a new customer.
   */
  protected readonly priorOrderCount = signal<number | null>(null);

  /**
   * Delivery-reliability risk for the entered customer mobile (FEATURE-ROADMAP
   * §1.2). Null until a valid mobile has been checked; drives a prepaid nudge for
   * MEDIUM/HIGH-risk customers so a salesperson can avoid a likely COD failure.
   */
  protected readonly customerRisk = signal<CustomerRisk | null>(null);

  // Risk badge helpers for the template.
  protected readonly riskPillClass = riskPillClass;
  protected readonly riskLabel = riskLabel;

  /** A snapshot of the form value, refreshed on every change to drive totals. */
  private readonly model = signal<ReturnType<NewOrderComponent['snapshot']>>({
    items: [],
    amountReceived: 0,
    leadSource: '',
  });

  /** Selectable lead-source options for the origin picker (Req 4.1). */
  protected readonly leadSourceOptions = LEAD_SOURCE_OPTIONS;

  protected readonly form = this.fb.nonNullable.group({
    customerName: ['', [Validators.required, Validators.maxLength(100)]],
    customerMobile: ['', [Validators.required, Validators.pattern(/^\d{10}$/)]],
    customerEmail: ['', [Validators.email, Validators.maxLength(150)]],
    addressLine: ['', [Validators.required, Validators.maxLength(250)]],
    city: ['', [Validators.required, Validators.maxLength(100)]],
    state: ['', [Validators.required, Validators.maxLength(100)]],
    postalCode: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
    leadSource: ['' as '' | LeadSource, [Validators.required]],
    leadSourceNote: ['', [Validators.maxLength(200)]],
    items: this.fb.array([this.newItem()]),
    amountReceived: [0, [Validators.required, Validators.min(0)]],
    notes: ['', [Validators.maxLength(1000)]],
  });

  /** Whether the form is converting a lead (drives titles, locked fields, submit path). */
  protected readonly convertMode = computed(() => this.convertLeadId() !== null);

  /** Whether the free-text lead-source note is shown (only for {@code OTHER}, Req 4.5). */
  protected readonly showLeadSourceNote = computed(() => this.model().leadSource === 'OTHER');

  /** Whether a payment screenshot is mandatory (mirrors the backend rule). */
  protected readonly screenshotRequired = computed(() => this.model().amountReceived > 0);

  /** Per-line totals in paise (rate × quantity), aligned to the item rows. */
  protected readonly lineTotals = computed(() =>
    this.model().items.map((it) => toPaise(it.rate ?? 0) * (it.quantity || 0)),
  );

  /** The running order total in paise. */
  protected readonly orderTotalPaise = computed(() =>
    this.lineTotals().reduce((sum, cents) => sum + cents, 0),
  );

  /** Remaining balance after the amount received (may be negative if overpaid). */
  protected readonly remainingPaise = computed(
    () => this.orderTotalPaise() - toPaise(this.model().amountReceived),
  );

  ngOnInit(): void {
    this.loadProducts();
    this.loadStates();
    // Keep the totals snapshot in sync with the reactive form.
    this.model.set(this.snapshot());
    this.form.valueChanges.subscribe(() => this.model.set(this.snapshot()));

    // Repeat-customer hint (Req 22.2): once a valid 10-digit mobile is entered,
    // ask the backend whether prior orders exist for it and surface a hint.
    this.form.controls.customerMobile.valueChanges
      .pipe(debounceTime(400), distinctUntilChanged())
      .subscribe((mobile) => this.checkDuplicateCustomer(mobile));

    // Convert-from-lead mode: seed customer + source from the lead and lock them.
    const leadIdParam = this.route.snapshot.queryParamMap.get('leadId');
    const leadId = leadIdParam ? Number(leadIdParam) : NaN;
    if (Number.isFinite(leadId) && leadId > 0) {
      this.initConvertMode(leadId);
    }
  }

  /**
   * Loads the lead being converted and pre-fills the customer identity + lead
   * source (Req 4, design §Convert Flow step 1). Those fields are then locked
   * because the convert endpoint forces them from the lead server-side.
   */
  private initConvertMode(leadId: number): void {
    this.convertLeadId.set(leadId);
    this.leads.detail(leadId).subscribe({
      next: (lead) => {
        if (lead.status === 'WON' || lead.status === 'LOST') {
          this.toasts.error('This lead is already closed and cannot be converted.');
          void this.router.navigate(['/leads']);
          return;
        }
        this.convertLeadName.set(lead.customerName);
        this.form.patchValue({
          customerName: lead.customerName,
          customerMobile: lead.customerMobile ?? '',
          customerEmail: lead.customerEmail ?? '',
          leadSource: lead.leadSource,
          leadSourceNote: lead.leadSourceNote ?? '',
        });
        // The customer identity + source are forced from the lead on the server;
        // lock them so they can't be re-pointed at a different customer.
        this.form.controls.customerName.disable();
        this.form.controls.customerMobile.disable();
        this.form.controls.customerEmail.disable();
        this.form.controls.leadSource.disable();
        this.form.controls.leadSourceNote.disable();
      },
      error: () => {
        this.toasts.error('Could not load the lead to convert.');
        void this.router.navigate(['/leads']);
      },
    });
  }

  ngOnDestroy(): void {
    this.revokePreview();
  }

  // --- Catalog ------------------------------------------------------------

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

  /** Loads the admin-managed delivery states that back the state typeahead. */
  loadStates(): void {
    this.statesService.activeNames().subscribe({
      next: (rows) => this.states.set(rows),
      // Non-fatal: the field still accepts free-typed text if the list fails.
      error: () => this.states.set([]),
    });
  }

  /**
   * Looks up how many prior orders exist for the entered mobile so the form can
   * show a repeat-customer hint (Req 22.2). Only runs for a well-formed 10-digit
   * number; anything else clears the hint. Failures are non-fatal (hint hidden).
   */
  private checkDuplicateCustomer(mobile: string | null): void {
    if (!mobile || !/^\d{10}$/.test(mobile)) {
      this.priorOrderCount.set(null);
      this.customerRisk.set(null);
      return;
    }
    this.orders.duplicateCheck(mobile).subscribe({
      next: (res) => this.priorOrderCount.set(res.priorOrderCount),
      error: () => this.priorOrderCount.set(null),
    });
    // Delivery-reliability risk nudge (FEATURE-ROADMAP §1.2): non-fatal, hidden on failure.
    this.customers.risk(mobile).subscribe({
      next: (risk) => this.customerRisk.set(risk),
      error: () => this.customerRisk.set(null),
    });
  }

  // --- Line items ---------------------------------------------------------

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

  /** Quantity stepper (−) for a line, clamped to the backend's 1..999 range. */
  decQty(index: number): void {
    const control = this.items.at(index).controls['quantity'];
    const next = Math.max(1, (Number(control.value) || 1) - 1);
    control.setValue(next);
    control.markAsTouched();
  }

  /** Quantity stepper (+) for a line, clamped to the backend's 1..999 range. */
  incQty(index: number): void {
    const control = this.items.at(index).controls['quantity'];
    const next = Math.min(999, (Number(control.value) || 0) + 1);
    control.setValue(next);
    control.markAsTouched();
  }

  /** When a product is chosen, pre-fill the rate with its sale price (Req 7.2). */
  onProductChange(index: number): void {
    const group = this.items.at(index);
    const productId = group.controls['productId'].value as number | null;
    const product = this.products().find((p) => p.id === productId);
    if (product) {
      group.controls['rate'].setValue(Number(product.salePrice));
    }
  }

  productName(index: number): string {
    const productId = this.items.at(index).controls['productId'].value as number | null;
    return this.products().find((p) => p.id === productId)?.name ?? '';
  }

  // --- Payment screenshot -------------------------------------------------

  onScreenshotSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    if (!file.type.startsWith('image/')) {
      this.uploadError.set('Please choose an image file.');
      this.uploadState.set('error');
      input.value = '';
      return;
    }
    this.revokePreview();
    this.screenshotPreview.set(URL.createObjectURL(file));
    this.screenshotName.set(file.name);
    this.screenshotKey.set(null);
    this.uploadError.set(null);
    this.uploadState.set('uploading');
    this.orders.uploadPaymentScreenshot(file).subscribe({
      next: (res) => {
        this.screenshotKey.set(res.key);
        this.uploadState.set('done');
      },
      error: (err: HttpErrorResponse) => {
        this.uploadError.set(this.messageOf(err) ?? 'Upload failed. Please try again.');
        this.uploadState.set('error');
      },
    });
    // Allow re-selecting the same file after an error.
    input.value = '';
  }

  clearScreenshot(): void {
    this.revokePreview();
    this.screenshotName.set(null);
    this.screenshotKey.set(null);
    this.uploadError.set(null);
    this.uploadState.set('idle');
  }

  private revokePreview(): void {
    const url = this.screenshotPreview();
    if (url) {
      URL.revokeObjectURL(url);
    }
    this.screenshotPreview.set(null);
  }

  // --- Submit -------------------------------------------------------------

  async submit(): Promise<void> {
    this.submitAttempted.set(true);
    this.serverErrors.set([]);
    if (this.submitting()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.toasts.error('Please fix the highlighted fields before submitting.');
      return;
    }
    if (this.screenshotRequired() && !this.screenshotKey()) {
      this.toasts.error('A payment screenshot is required when an amount is received.');
      return;
    }

    const raw = this.snapshot();
    const converting = this.convertMode();
    const confirmed = await this.confirm.confirm({
      title: converting ? 'Convert lead to order' : 'Create order',
      message: converting
        ? `Convert ${this.convertLeadName()} into an order with a total of ${this.formatMoney(this.orderTotalPaise())}? The lead will be marked Won.`
        : `Create this order for ${this.form.controls.customerName.value} with a total of ${this.formatMoney(this.orderTotalPaise())}?`,
      confirmLabel: converting ? 'Convert' : 'Create order',
      icon: converting ? 'ti-shopping-cart-plus' : 'ti-receipt',
    });
    if (!confirmed) {
      return;
    }

    if (converting) {
      this.submitConvert(raw);
      return;
    }

    const email = this.form.controls.customerEmail.value.trim();
    const note = this.form.controls.leadSourceNote.value.trim();
    const orderNotes = this.form.controls.notes.value.trim();
    const isOther = raw.leadSource === 'OTHER';
    const payload: CreateOrderRequest = {
      customerName: this.form.controls.customerName.value.trim(),
      customerMobile: this.form.controls.customerMobile.value.trim(),
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
      amountReceived: raw.amountReceived,
      ...(this.screenshotKey() ? { paymentScreenshotKey: this.screenshotKey()! } : {}),
      leadSource: raw.leadSource as LeadSource,
      // Only send the note when OTHER is chosen (it's meaningless otherwise, Req 4.5).
      ...(isOther && note ? { leadSourceNote: note } : {}),
      ...(orderNotes ? { notes: orderNotes } : {}),
    };

    // Offline capture (FEATURE-ROADMAP §8.1): a COD order (no money collected) is
    // queued locally and synced on reconnect. An order that takes payment needs a
    // screenshot upload, which requires a connection — so it's blocked offline.
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      if (raw.amountReceived > 0) {
        this.toasts.error(
          'You are offline. A paid order needs its payment screenshot uploaded — please try again when back online.',
        );
        return;
      }
      this.offlineQueue.enqueue(payload, this.orderTotalPaise());
      this.toasts.success(
        `Saved offline for ${payload.customerName} — it will sync automatically when you reconnect.`,
      );
      void this.router.navigate(['/orders']);
      return;
    }

    this.submitting.set(true);
    this.orders.createOrder(payload).subscribe({
      next: (order) => {
        this.submitting.set(false);
        this.toasts.success(`Order ${order.orderCode} created`);
        void this.router.navigate(['/orders'], { queryParams: { q: order.orderCode } });
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        const body = err.error as ApiError | undefined;
        const details = body?.details ?? [];
        this.serverErrors.set(details.length ? details : []);
        this.toasts.error(this.messageOf(err) ?? 'Could not create the order. Please try again.');
      },
    });
  }

  /**
   * Convert-mode save (Req 4, design §Convert Flow steps 2-4): posts the
   * order-shaped payload (address + items + payment only) to
   * {@code POST /api/leads/{id}/convert}. The server seeds the customer + lead
   * source from the lead, creates the order via the shared order-creation path,
   * and marks the lead WON with the linked order id. On failure the lead stays
   * unchanged (server-side rollback).
   */
  private submitConvert(raw: ReturnType<NewOrderComponent['snapshot']>): void {
    const leadId = this.convertLeadId();
    if (leadId === null) {
      return;
    }
    // Convert runs a server-side transaction (order + lead update) — it can't be
    // queued offline (FEATURE-ROADMAP §8.1).
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      this.toasts.error('You are offline. Converting a lead needs a connection — please try again when back online.');
      return;
    }
    const payload: LeadConvertRequest = {
      addressLine: this.form.controls.addressLine.value.trim(),
      city: this.form.controls.city.value.trim(),
      state: this.form.controls.state.value.trim(),
      postalCode: this.form.controls.postalCode.value.trim(),
      items: raw.items.map<CreateOrderLineItem>((it) => ({
        productId: it.productId as number,
        quantity: it.quantity,
        ...(it.rate != null ? { rate: it.rate } : {}),
      })),
      amountReceived: raw.amountReceived,
      ...(this.screenshotKey() ? { paymentScreenshotKey: this.screenshotKey()! } : {}),
      ...(this.form.controls.notes.value.trim()
        ? { notes: this.form.controls.notes.value.trim() }
        : {}),
    };

    this.submitting.set(true);
    this.leads.convert(leadId, payload).subscribe({
      next: (lead) => {
        this.submitting.set(false);
        this.toasts.success(`${lead.customerName} converted — order created.`);
        void this.router.navigate(['/orders']);
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        const body = err.error as ApiError | undefined;
        const details = body?.details ?? [];
        this.serverErrors.set(details.length ? details : []);
        this.toasts.error(this.messageOf(err) ?? 'Could not convert the lead. Please try again.');
      },
    });
  }

  cancel(): void {
    void this.router.navigate([this.convertMode() ? '/leads' : '/orders']);
  }

  // --- Helpers ------------------------------------------------------------

  /** Formats a paise integer as a ₹ money string. */
  formatMoney(paise: number): string {
    return `₹${paiseToMoney(paise)}`;
  }

  /** True when a control should show its invalid state (touched or submitted). */
  invalid(controlName: keyof NewOrderComponent['form']['controls']): boolean {
    const control = this.form.controls[controlName];
    return control.invalid && (control.touched || this.submitAttempted());
  }

  itemInvalid(index: number, name: 'productId' | 'quantity' | 'rate'): boolean {
    const control = this.items.at(index).controls[name];
    return control.invalid && (control.touched || this.submitAttempted());
  }

  private messageOf(err: HttpErrorResponse): string | null {
    const body = err.error as ApiError | undefined;
    return body?.message ?? null;
  }

  /** A plain snapshot of the fields that drive totals + the payload. */
  private snapshot(): {
    items: { productId: number | null; quantity: number; rate: number | null }[];
    amountReceived: number;
    leadSource: '' | LeadSource;
  } {
    const raw = this.form.getRawValue();
    return {
      items: raw.items.map((it) => ({
        productId: (it['productId'] ?? null) as number | null,
        quantity: Number(it['quantity']) || 0,
        rate: it['rate'] === null || it['rate'] === undefined ? null : Number(it['rate']),
      })),
      amountReceived: Number(raw.amountReceived) || 0,
      leadSource: raw.leadSource,
    };
  }
}
