import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import {
  FormArray,
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { debounceTime, distinctUntilChanged, map } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiError, Product, paiseToMoney, toPaise } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { StateTypeaheadComponent } from '../shared/state-typeahead.component';
import { ProductTypeaheadComponent } from '../shared/product-typeahead.component';
import { StatesService } from '../shared/states.service';
import { PincodeService } from '../shared/pincode.service';
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
  OrderDiscountType,
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
    ProductTypeaheadComponent,
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
  private readonly pincodes = inject(PincodeService);
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

  /**
   * Reorder mode: opened as {@code /orders/new?reorderFrom=<orderId>}, it clones
   * a past order's customer + shipping details and line items into a fresh draft
   * so a repeat purchase takes seconds. Everything stays editable (unlike convert
   * mode nothing is locked); this just holds the source order code for the banner.
   */
  protected readonly reorderFromCode = signal<string | null>(null);
  /** True when the form is in reorder mode (set synchronously so autosave/draft logic can skip it). */
  private reorderActive = false;

  /**
   * Abandoned-order recovery: the form is auto-saved to localStorage as the
   * salesperson types, so a half-filled order survives an accidental navigation /
   * refresh. On a fresh New Order we OFFER to resume it (never auto-apply); the
   * draft is cleared on a successful save or when discarded.
   */
  private readonly DRAFT_KEY = 'shifa:new-order-draft';
  protected readonly draftAvailable = signal(false);

  // --- Product catalog (picker source) ------------------------------------
  protected readonly products = signal<Product[]>([]);
  protected readonly productsLoading = signal(true);
  protected readonly productsError = signal<string | null>(null);

  /** Best-sellers for one-tap quick-add (Tranche 3: favorites). */
  protected readonly favorites = signal<Product[]>([]);
  /** "Frequently bought together" suggestions for the current cart (upsell). */
  protected readonly suggestions = signal<Product[]>([]);

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

  /**
   * When a known mobile is entered, the customer + shipping fields are pre-filled
   * from that customer's LAST order (overridable). This holds the customer name
   * (or a generic label) for the "Filled from … last order" banner; null hides it.
   */
  protected readonly prefilledFromLast = signal<string | null>(null);

  // Risk badge helpers for the template.
  protected readonly riskPillClass = riskPillClass;
  protected readonly riskLabel = riskLabel;

  /**
   * The locality auto-detected from the entered pincode (product-audit PIN-code
   * auto-fill). Null until a 6-digit pincode resolves; drives a subtle
   * "Detected: <city>, <state>" hint under the pincode field.
   */
  protected readonly detectedLocation = signal<string | null>(null);
  /** True while a pincode lookup is in flight (shows a tiny spinner). */
  protected readonly pincodeLooking = signal(false);

  /** A snapshot of the form value, refreshed on every change to drive totals. */
  private readonly model = signal<ReturnType<NewOrderComponent['snapshot']>>({
    items: [],
    amountReceived: 0,
    leadSource: '',
    discountType: '',
    discountValue: 0,
  });

  /** Selectable lead-source options for the origin picker (Req 4.1). */
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
    // Optional buyer GSTIN (gst-filing-compliance Req 1): blank is valid; when
    // present it must match the standard 15-char GSTIN format (2 digits, 5
    // letters, 4 digits, 1 letter, 1 entity char, the fixed letter Z, 1 checksum
    // char). Mirrors the server's Gstin.isValid gate so a registered-buyer sale
    // classifies as B2B for GSTR-1.
    buyerGstin: [
      '',
      [
        Validators.maxLength(15),
        Validators.pattern(/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]$/),
      ],
    ],
    items: this.fb.array([this.newItem()]),
    amountReceived: [0, [Validators.required, Validators.min(0)]],
    // Optional order-level discount (product-catalog-pricing-gst Req 6).
    discountType: ['' as '' | OrderDiscountType],
    discountValue: [0, [Validators.min(0)]],
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

  /** The (unrounded) subtotal in paise: Σ line totals before any discount. */
  protected readonly subtotalPaise = computed(() =>
    this.lineTotals().reduce((sum, cents) => sum + cents, 0),
  );

  /**
   * The order-level discount in paise (product-catalog-pricing-gst Req 6):
   * PERCENT of the subtotal or a FLAT amount, clamped to [0, subtotal].
   */
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

  /**
   * The running order total in paise = (subtotal − discount) rounded to the
   * nearest whole rupee so the figure matches what the backend will charge
   * (product-audit §4.6; product-catalog-pricing-gst Req 6, 8).
   */
  protected readonly orderTotalPaise = computed(() => {
    const net = this.subtotalPaise() - this.discountPaise();
    return Math.round(net / 100) * 100;
  });

  /** Remaining balance after the amount received (may be negative if overpaid). */
  protected readonly remainingPaise = computed(
    () => this.orderTotalPaise() - toPaise(this.model().amountReceived),
  );

  ngOnInit(): void {
    this.loadProducts();
    this.loadFavorites();
    this.loadStates();

    // Refresh "frequently bought together" suggestions when the set of chosen
    // products changes (debounced; only when the product id set actually changes).
    this.form.controls.items.valueChanges
      .pipe(
        debounceTime(500),
        map(() => this.currentProductIds().join(',')),
        distinctUntilChanged(),
      )
      .subscribe(() => this.refreshSuggestions());
    // Keep the totals snapshot in sync with the reactive form.
    this.model.set(this.snapshot());
    this.form.valueChanges.subscribe(() => this.model.set(this.snapshot()));

    // Repeat-customer hint (Req 22.2): once a valid 10-digit mobile is entered,
    // ask the backend whether prior orders exist for it and surface a hint.
    this.form.controls.customerMobile.valueChanges
      .pipe(debounceTime(400), distinctUntilChanged())
      .subscribe((mobile) => this.checkDuplicateCustomer(mobile));

    // PIN-code auto-fill (product-audit): once a 6-digit pincode is entered, look
    // up its city + state via the India Post API and pre-fill those fields. It's
    // best-effort — a failed/blocked lookup just leaves the fields for manual entry.
    this.form.controls.postalCode.valueChanges
      .pipe(debounceTime(400), distinctUntilChanged())
      .subscribe((pincode) => this.autoFillFromPincode(pincode));

    // Convert-from-lead mode: seed customer + source from the lead and lock them.
    const leadIdParam = this.route.snapshot.queryParamMap.get('leadId');
    const leadId = leadIdParam ? Number(leadIdParam) : NaN;
    if (Number.isFinite(leadId) && leadId > 0) {
      this.initConvertMode(leadId);
      return;
    }

    // One-tap reorder: clone a past order into this draft (mutually exclusive
    // with convert mode).
    const reorderParam = this.route.snapshot.queryParamMap.get('reorderFrom');
    const reorderId = reorderParam ? Number(reorderParam) : NaN;
    if (Number.isFinite(reorderId) && reorderId > 0) {
      this.reorderActive = true;
      this.initReorderMode(reorderId);
    } else {
      // Blank New Order: offer to resume an abandoned draft, and auto-save as
      // the salesperson types.
      this.maybeOfferDraft();
    }

    // Autosave the in-progress order (debounced) so it can be recovered.
    this.form.valueChanges
      .pipe(debounceTime(800))
      .subscribe(() => this.saveDraft());
  }

  // --- Abandoned-order draft recovery -------------------------------------

  /** Whether the form currently holds enough to be worth saving as a draft. */
  private hasDraftContent(): boolean {
    const v = this.form.getRawValue();
    const anyItem = v.items.some((it) => it['productId'] != null);
    return !!(v.customerName?.trim() || v.customerMobile?.trim() || v.addressLine?.trim() || anyItem);
  }

  /** Persists the current form to localStorage (skipped in convert/reorder mode). */
  private saveDraft(): void {
    if (this.convertMode() || this.reorderActive || this.submitting()) {
      return;
    }
    try {
      if (!this.hasDraftContent()) {
        localStorage.removeItem(this.DRAFT_KEY);
        return;
      }
      const draft = { savedAt: Date.now(), value: this.form.getRawValue() };
      localStorage.setItem(this.DRAFT_KEY, JSON.stringify(draft));
    } catch {
      /* storage full / unavailable — non-fatal */
    }
  }

  /** Shows the "resume draft?" banner when a saved draft exists. */
  private maybeOfferDraft(): void {
    try {
      this.draftAvailable.set(!!localStorage.getItem(this.DRAFT_KEY));
    } catch {
      this.draftAvailable.set(false);
    }
  }

  /** Restores the saved draft into the form (customer + items + payment + notes). */
  resumeDraft(): void {
    let draft: { value: Record<string, unknown> } | null = null;
    try {
      const raw = localStorage.getItem(this.DRAFT_KEY);
      draft = raw ? JSON.parse(raw) : null;
    } catch {
      draft = null;
    }
    const v = draft?.value as Record<string, unknown> | undefined;
    if (!v) {
      this.draftAvailable.set(false);
      return;
    }
    // Rebuild the items list from the draft.
    const items = Array.isArray(v['items']) ? (v['items'] as Record<string, unknown>[]) : [];
    const arr = this.items;
    while (arr.length) {
      arr.removeAt(0);
    }
    if (items.length === 0) {
      arr.push(this.newItem());
    } else {
      for (const it of items) {
        const g = this.newItem();
        g.controls['productId'].setValue((it['productId'] as number | null) ?? null);
        g.controls['quantity'].setValue(Number(it['quantity']) || 1);
        g.controls['rate'].setValue(it['rate'] == null ? null : Number(it['rate']));
        arr.push(g);
      }
    }
    // Patch the scalar fields (ignore the items key — handled above).
    const { items: _drop, ...scalars } = v as { items?: unknown };
    this.form.patchValue(scalars as Record<string, unknown>);
    this.model.set(this.snapshot());
    this.draftAvailable.set(false);
    this.toasts.success('Resumed your saved order — review and save.');
  }

  /** Discards the saved draft and hides the banner. */
  discardDraft(): void {
    this.clearDraft();
    this.draftAvailable.set(false);
  }

  /** Removes any saved draft (called on a successful save). */
  private clearDraft(): void {
    try {
      localStorage.removeItem(this.DRAFT_KEY);
    } catch {
      /* non-fatal */
    }
  }

  /**
   * Loads a past order and clones its customer + shipping details and line items
   * into the form (One-tap reorder). Fields are patched WITHOUT emitting so the
   * mobile auto-prefill doesn't overwrite the cloned address with the customer's
   * latest order; everything remains editable and the salesperson just reviews
   * quantities and saves.
   */
  private initReorderMode(orderId: number): void {
    this.orders.detail(orderId).subscribe({
      next: (o) => {
        this.reorderFromCode.set(o.orderCode);
        this.form.patchValue(
          {
            customerName: o.customerName ?? '',
            customerMobile: o.customerMobile ?? '',
            alternateMobile: o.alternateMobile ?? '',
            addressLine: o.addressLine ?? '',
            city: o.city ?? '',
            state: o.state ?? '',
            postalCode: o.postalCode ?? '',
          },
          { emitEvent: false },
        );
        // Rebuild the items list from the source order's lines.
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
        this.toasts.success(`Loaded ${lines.length} item(s) from ${o.orderCode} — review and save.`);
      },
      error: () => this.toasts.error('Could not load that order to reorder.'),
    });
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
      this.prefilledFromLast.set(null);
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
    // Prefill customer + shipping details from the customer's last order.
    this.prefillFromLastOrder(mobile);
  }

  /**
   * Pre-fills the customer + shipping fields from the customer's most recent
   * order when a known mobile is entered, so a repeat customer's details aren't
   * re-typed. Everything stays editable — the salesperson can override any field
   * and proceed. Skipped in convert-from-lead mode (those fields are locked from
   * the lead). Best-effort: a not-found / failed lookup just leaves the form.
   *
   * <p>It runs only when the mobile CHANGES (the field is debounced +
   * distinctUntilChanged), so edits made after a prefill are never clobbered;
   * switching to a different known mobile re-fills from that customer.
   */
  private prefillFromLastOrder(mobile: string): void {
    if (this.convertMode()) {
      return;
    }
    this.orders.lastCustomerByMobile(mobile).subscribe({
      next: (p) => {
        if (!p.found) {
          this.prefilledFromLast.set(null);
          return;
        }
        const c = this.form.controls;
        if (p.customerName) c.customerName.setValue(p.customerName);
        if (p.customerEmail) c.customerEmail.setValue(p.customerEmail);
        if (p.alternateMobile) c.alternateMobile.setValue(p.alternateMobile);
        if (p.addressLine) c.addressLine.setValue(p.addressLine);
        if (p.city) c.city.setValue(p.city);
        if (p.state) c.state.setValue(p.state);
        if (p.postalCode) c.postalCode.setValue(p.postalCode);
        if (p.leadSource && this.leadSourceOptions.some((o) => o.value === p.leadSource)) {
          c.leadSource.setValue(p.leadSource as LeadSource);
        }
        if (p.leadSourceNote) c.leadSourceNote.setValue(p.leadSourceNote);
        this.prefilledFromLast.set(p.customerName || 'a previous order');
      },
      error: () => this.prefilledFromLast.set(null),
    });
  }

  /**
   * Resolves a 6-digit pincode to city + state (product-audit PIN-code auto-fill)
   * and pre-fills those fields. To avoid clobbering a salesperson's own typing it
   * only fills City/State when they are currently empty; the "Detected" hint is
   * always shown so they can copy it if they'd already typed something else.
   * Entirely best-effort: a malformed/blocked/no-match lookup clears the hint.
   */
  private autoFillFromPincode(pincode: string | null): void {
    if (!pincode || !/^\d{6}$/.test(pincode)) {
      this.detectedLocation.set(null);
      this.pincodeLooking.set(false);
      return;
    }
    this.pincodeLooking.set(true);
    this.pincodes.lookup(pincode).subscribe({
      next: (location) => {
        this.pincodeLooking.set(false);
        if (!location) {
          this.detectedLocation.set(null);
          return;
        }
        this.detectedLocation.set(`${location.city}, ${location.state}`);
        const city = this.form.controls.city;
        const state = this.form.controls.state;
        if (city.enabled && !city.value.trim()) {
          city.setValue(location.city);
          city.markAsDirty();
        }
        if (state.enabled && !state.value.trim()) {
          state.setValue(location.state);
          state.markAsDirty();
        }
      },
      error: () => {
        this.pincodeLooking.set(false);
        this.detectedLocation.set(null);
      },
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

  // --- Favorites (quick-add) + frequently-bought-together (Tranche 3) ------

  /** Loads the salesperson's best-sellers for one-tap quick-add (non-fatal). */
  private loadFavorites(): void {
    this.catalog.topProducts(8).subscribe({
      next: (rows) => this.favorites.set(rows),
      error: () => this.favorites.set([]),
    });
  }

  /** The product ids currently chosen across the line items. */
  private currentProductIds(): number[] {
    const ids: number[] = [];
    for (let i = 0; i < this.items.length; i++) {
      const pid = this.items.at(i).controls['productId'].value as number | null;
      if (pid != null) {
        ids.push(pid);
      }
    }
    return ids;
  }

  /** Fetches upsell suggestions for the current cart (excludes items already added). */
  private refreshSuggestions(): void {
    const ids = this.currentProductIds();
    if (ids.length === 0) {
      this.suggestions.set([]);
      return;
    }
    this.catalog.relatedProducts(ids, 3).subscribe({
      next: (rows) => this.suggestions.set(rows.filter((r) => !ids.includes(r.id))),
      error: () => this.suggestions.set([]),
    });
  }

  /**
   * Adds a product to the order in one tap: bumps the quantity if it's already a
   * line, else fills the first empty line (or appends a new one), pre-filling the
   * rate from the product's sale price.
   */
  quickAdd(product: Product): void {
    // Already in the cart → increment quantity (clamped to 999).
    for (let i = 0; i < this.items.length; i++) {
      const g = this.items.at(i);
      if ((g.controls['productId'].value as number | null) === product.id) {
        const qty = Number(g.controls['quantity'].value) || 0;
        g.controls['quantity'].setValue(Math.min(999, qty + 1));
        this.model.set(this.snapshot());
        return;
      }
    }
    // Otherwise reuse an empty line or append a new one.
    let target: FormGroup | null = null;
    for (let i = 0; i < this.items.length; i++) {
      const g = this.items.at(i);
      if ((g.controls['productId'].value as number | null) == null) {
        target = g;
        break;
      }
    }
    if (!target) {
      this.addItem();
      target = this.items.at(this.items.length - 1);
    }
    target.controls['productId'].setValue(product.id);
    target.controls['quantity'].setValue(1);
    target.controls['rate'].setValue(Number(product.salePrice));
    this.model.set(this.snapshot());
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

  /** When a product is chosen, pre-fill the rate with its sale (auto-fetch) price (Req 7.2, 5.1). */
  onProductChange(index: number): void {
    const group = this.items.at(index);
    const productId = group.controls['productId'].value as number | null;
    const product = this.products().find((p) => p.id === productId);
    if (product) {
      group.controls['rate'].setValue(Number(product.salePrice));
      this.model.set(this.snapshot());
    }
  }

  /** The product selected on a line, or undefined when none is chosen. */
  productForLine(index: number): Product | undefined {
    const id = this.items.at(index).controls['productId'].value as number | null;
    return id == null ? undefined : this.products().find((p) => p.id === id);
  }

  /**
   * The price band {min, max} + pack size for a line's product
   * (product-catalog-pricing-gst Req 5.2, 10.1). The floor falls back to the
   * sale price when no explicit minimum is set; null when no product is chosen.
   */
  lineBand(index: number): { min: number; max: number; wtMl: string | null } | null {
    const p = this.productForLine(index);
    if (!p) {
      return null;
    }
    const min = Number(p.minimumRate ?? p.salePrice);
    const max = Number(p.mrp);
    return { min, max, wtMl: p.wtMl ?? null };
  }

  /**
   * A validation message when a line's rate is outside its product's band
   * (mirrors the server rule so the salesperson sees it before submit), else null.
   */
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

  /** True when any line's rate is outside its product's price band. */
  hasBandErrors(): boolean {
    return this.items.controls.some((_, i) => this.lineRateError(i) !== null);
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

  // --- Guided wizard (product-audit §3.2) ---------------------------------

  /** The current wizard step (1=Customer, 2=Items, 3=Payment, 4=Review). */
  protected readonly step = signal(1);
  protected readonly totalSteps = 4;
  protected readonly stepLabels = ['Customer', 'Items', 'Payment', 'Review'];

  /** Form controls that belong to each step, validated before advancing. */
  private readonly stepControlNames: Record<number, string[]> = {
    1: [
      'customerName', 'customerMobile', 'alternateMobile', 'customerEmail',
      'leadSource', 'leadSourceNote', 'buyerGstin', 'addressLine', 'city', 'postalCode', 'state',
    ],
    2: [],
    3: ['amountReceived'],
    4: ['notes'],
  };

  /** Advance to the next step if the current one is valid. */
  nextStep(): void {
    if (this.validateStep(this.step())) {
      this.step.set(Math.min(this.totalSteps, this.step() + 1));
      this.scrollTop();
    }
  }

  /** Go back one step (no validation needed). */
  prevStep(): void {
    this.step.set(Math.max(1, this.step() - 1));
    this.scrollTop();
  }

  /** Jump to a step from the progress bar; forward jumps validate intervening steps. */
  goToStep(target: number): void {
    if (target < 1 || target > this.totalSteps) {
      return;
    }
    if (target > this.step()) {
      for (let s = this.step(); s < target; s++) {
        if (!this.validateStep(s)) {
          this.step.set(s);
          return;
        }
      }
    }
    this.step.set(target);
    this.scrollTop();
  }

  /** Validates the controls (and item/screenshot rules) owned by a step. */
  private validateStep(step: number): boolean {
    let ok = true;
    for (const name of this.stepControlNames[step] ?? []) {
      const control = this.form.get(name);
      if (control && control.enabled && control.invalid) {
        control.markAsTouched();
        ok = false;
      }
    }
    if (step === 2) {
      this.items.controls.forEach((group) => group.markAllAsTouched());
      // Block advancing when a line's price is outside its product band (Req 5.2),
      // mirroring the server rule so the salesperson fixes it here.
      if (this.items.invalid || this.orderTotalPaise() <= 0 || this.hasBandErrors()) {
        ok = false;
      }
    }
    if (step === 3 && this.screenshotRequired() && !this.screenshotKey()) {
      this.submitAttempted.set(true);
      ok = false;
    }
    if (!ok) {
      this.toasts.error('Please complete this step before continuing.');
    }
    return ok;
  }

  private scrollTop(): void {
    if (typeof window !== 'undefined') {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
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
    const altMobile = this.form.controls.alternateMobile.value.trim();
    const note = this.form.controls.leadSourceNote.value.trim();
    const orderNotes = this.form.controls.notes.value.trim();
    const buyerGstin = this.form.controls.buyerGstin.value.trim().toUpperCase();
    const isOther = raw.leadSource === 'OTHER';
    const payload: CreateOrderRequest = {
      customerName: this.form.controls.customerName.value.trim(),
      customerMobile: this.form.controls.customerMobile.value.trim(),
      ...(altMobile ? { alternateMobile: altMobile } : {}),
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
      // Optional buyer GSTIN (gst-filing-compliance Req 1), only when provided.
      ...(buyerGstin ? { buyerGstin } : {}),
      // Order-level discount (product-catalog-pricing-gst Req 6), only when set.
      ...(raw.discountType
        ? { discountType: raw.discountType as OrderDiscountType, discountValue: raw.discountValue || 0 }
        : {}),
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
      this.clearDraft();
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
        this.clearDraft();
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
      // Order-level discount (product-catalog-pricing-gst Req 6), only when set.
      ...(raw.discountType
        ? { discountType: raw.discountType as OrderDiscountType, discountValue: raw.discountValue || 0 }
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

  /**
   * Uppercases the buyer GSTIN as it is typed so it matches the canonical GSTIN
   * format (which uses uppercase letters) — otherwise the pattern validator would
   * reject a lowercase entry. Optional field, so an empty value stays valid.
   */
  onGstinInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const upper = input.value.toUpperCase();
    if (upper !== input.value) {
      this.form.controls.buyerGstin.setValue(upper);
    }
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
      amountReceived: Number(raw.amountReceived) || 0,
      leadSource: raw.leadSource,
      discountType: (raw.discountType ?? '') as '' | OrderDiscountType,
      discountValue: Number(raw.discountValue) || 0,
    };
  }
}
