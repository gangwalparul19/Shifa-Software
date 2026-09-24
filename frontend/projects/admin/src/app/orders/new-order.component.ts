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
import { ApiError, AuthService, Product, Role, paiseToMoney, toPaise } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { StateTypeaheadComponent } from '../shared/state-typeahead.component';
import { ProductTypeaheadComponent } from '../shared/product-typeahead.component';
import { InrPipe } from '../shared/inr.pipe';
import { HelpTipComponent } from '../shared/help-tip.component';
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
  AssignableCreator,
  CreateOrderLineItem,
  CreateOrderRequest,
  LEAD_SOURCE_OPTIONS,
  LeadSource,
  OrderDiscountType,
  UpdateOrderRequest,
} from './orders.model';

/** The three phases the payment-screenshot upload can be in. */
type UploadState = 'idle' | 'uploading' | 'done' | 'error';

/**
 * Maximum payment proofs per order, mirroring the server-side
 * {@code @Size(max = 10)} on {@code CreateOrderRequest.paymentScreenshotKeys} so
 * the limit is surfaced in the UI rather than as a 400 on submit.
 */
const MAX_PAYMENT_SCREENSHOTS = 10;

/** One payment proof being attached to a new order (V65). */
interface ScreenshotAttachment {
  /** The chosen file's name, shown as the proof's label. */
  readonly name: string;
  /** Object URL for the local thumbnail, revoked when the proof is removed. */
  previewUrl: string | null;
  /** Storage key returned by the upload; null until it succeeds. */
  key: string | null;
  state: 'uploading' | 'done' | 'error';
  /** Failure message for this specific proof, so one bad upload is retryable alone. */
  error: string | null;
}

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
    InrPipe,
    HelpTipComponent,
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
  private readonly auth = inject(AuthService);

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
   * Resubmit mode: opened as {@code /orders/new?resubmitFrom=<orderId>} for a
   * REJECTED / PAYMENT_REJECTED order the salesperson created. It loads the SAME
   * order's details (everything editable) so they can fix what the admin/payment
   * verifier flagged, and on save it calls {@code POST /api/orders/{id}/resubmit}
   * — moving the SAME order back to the approval queue (not creating a new one).
   */
  protected readonly resubmitOrderId = signal<number | null>(null);
  protected readonly resubmitFromCode = signal<string | null>(null);
  /** True when the form is in resubmit mode (set synchronously so autosave/draft logic can skip it). */
  private resubmitActive = false;
  /** Whether the resubmitted order was payment-rejected (drives the banner copy). */
  protected readonly resubmitPaymentRejected = signal(false);

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
  /**
   * Every payment proof attached to this order, in the order the salesperson
   * picked them (V65). An order often has more than one proof — a part payment
   * plus the balance, a UPI receipt plus a bank confirmation, or two screenshots
   * because the transaction did not fit one screen.
   *
   * <p>Each file is uploaded independently through the existing single-file
   * endpoint, so one slow or failed upload never blocks the others and a failed
   * one can be removed and retried on its own.
   */
  protected readonly screenshots = signal<ScreenshotAttachment[]>([]);

  /** Rejection message for a file that was never uploaded (e.g. not an image). */
  protected readonly uploadError = signal<string | null>(null);

  /** Upload phase across all attachments, driving the shared status line. */
  protected readonly uploadState = computed<UploadState>(() => {
    const all = this.screenshots();
    if (all.length === 0) {
      return 'idle';
    }
    if (all.some((s) => s.state === 'uploading')) {
      return 'uploading';
    }
    if (all.some((s) => s.state === 'done')) {
      return 'done';
    }
    return 'error';
  });

  /**
   * The PRIMARY proof's storage key — the first successfully uploaded one. Sent as
   * {@code paymentScreenshotKey}, so an order with a single proof posts exactly the
   * same payload as before V65 and the server's screenshot-required rule is
   * satisfied by any one successful upload.
   */
  protected readonly screenshotKey = computed<string | null>(
    () => this.screenshots().find((s) => s.state === 'done' && s.key)?.key ?? null,
  );

  /** Every successfully uploaded proof key, in attach order. */
  protected readonly screenshotKeys = computed<string[]>(() =>
    this.screenshots()
      .filter((s) => s.state === 'done' && s.key)
      .map((s) => s.key!),
  );

  /** The additional proofs beyond the primary, posted as {@code paymentScreenshotKeys}. */
  protected readonly extraScreenshotKeys = computed<string[]>(() => this.screenshotKeys().slice(1));

  /** Whether another proof may be attached (server accepts at most 10 per order). */
  protected readonly canAddScreenshot = computed(
    () => this.screenshots().length < MAX_PAYMENT_SCREENSHOTS,
  );

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
   * Same-day duplicate warning: set when an ACTIVE order for the entered mobile
   * already exists TODAY (possibly punched by a different salesperson). Holds the
   * existing order's code, who placed it, and whether that was the current user,
   * so the form warns before submit and blocks proceeding. Null = no same-day
   * duplicate. The server also hard-blocks this, so it is defense-in-depth.
   */
  protected readonly sameDayDuplicate = signal<{
    orderCode: string | null;
    salespersonName: string | null;
    createdByMe: boolean;
  } | null>(null);

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

  // Delivery method is no longer chosen by the salesperson at order entry —
  // every order defaults to IN_HOUSE; the admin picks/overrides the delivery
  // partner (QuikShipX vs in-house) when approving (in-house-delivery feature).

  /** Selectable lead-source options for the origin picker (Req 4.1). */
  protected readonly leadSourceOptions = LEAD_SOURCE_OPTIONS;
  /** Safe, salesperson-specific preference: reused only for a fresh order. */
  private static readonly PREFERRED_LEAD_SOURCE_KEY = 'shifa:new-order-preferred-lead-source';

  protected readonly form = this.fb.nonNullable.group({
    customerName: ['', [Validators.required, Validators.maxLength(100)]],
    customerMobile: ['', [Validators.required, Validators.pattern(/^\d{10}$/)]],
    alternateMobile: ['', [Validators.pattern(/^\d{10}$/)]],
    customerEmail: ['', [Validators.email, Validators.maxLength(150)]],
    addressLine: ['', [Validators.required, Validators.maxLength(250)]],
    city: ['', [Validators.required, Validators.maxLength(100)]],
    state: ['', [Validators.required, Validators.maxLength(100)]],
    postalCode: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
    leadSource: [this.loadPreferredLeadSource(), [Validators.required]],
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

  /** Whether the form is reworking a rejected order (drives titles + submit path). */
  protected readonly resubmitMode = computed(() => this.resubmitOrderId() !== null);

  // --- Place on behalf of (ADMIN only) ------------------------------------
  /**
   * Whether the acting user is an admin, who may place an order on behalf of a
   * salesperson/team lead. The picker is hidden for everyone else (and in
   * convert/resubmit modes, which have their own attribution).
   */
  protected readonly isAdmin = computed(() => this.auth.hasAnyRole(Role.ADMIN));

  /**
   * Who the order is being placed for: the admin themselves ('self') or another
   * user ('other'). Only meaningful when {@link isAdmin} and not converting/
   * resubmitting. Defaults to 'self'.
   */
  protected readonly placeFor = signal<'self' | 'other'>('self');

  /** Active salespeople + team leads the admin can attribute the order to. */
  protected readonly assignableCreators = signal<AssignableCreator[]>([]);

  /** The selected on-behalf user id (null until one is picked). */
  protected readonly onBehalfUserId = signal<number | null>(null);

  /** Whether the on-behalf picker should be shown at all. */
  protected readonly showOnBehalfPicker = computed(
    () => this.isAdmin() && !this.convertMode() && !this.resubmitMode(),
  );

  /** True when the admin chose "on behalf of" but hasn't picked a person yet. */
  protected readonly onBehalfMissing = computed(
    () => this.showOnBehalfPicker() && this.placeFor() === 'other' && this.onBehalfUserId() == null,
  );

  // --- India vs Outside India (destination) -------------------------------
  /**
   * Order destination: 'india' (default — structured city/state/pincode) or
   * 'outside' (a single free-text address, no city/state/pincode). Toggling this
   * enables/disables + (de)validates the structured address controls.
   */
  protected readonly destination = signal<'india' | 'outside'>('india');

  /** Whether the order ships outside India (drives the address layout + payload). */
  protected readonly isInternational = computed(() => this.destination() === 'outside');

  /** The destination country name for an international order (free text). */
  protected readonly countryName = signal<string>('');

  /** Whether the free-text lead-source note is shown (only for {@code OTHER}, Req 4.5). */
  protected readonly showLeadSourceNote = computed(() => this.model().leadSource === 'OTHER');

  // --- Leaner-form expanders (optional fields hidden by default) -----------
  // Keep the New Order form short for salespeople: alternate number / email /
  // GSTIN, an order-level discount, and an order note stay collapsed until asked
  // for. The underlying controls remain registered, so submit + validation are
  // unaffected; these are pre-opened when a value is already present (e.g.
  // reorder/convert prefill).
  protected readonly moreDetails = signal(false);
  protected readonly showDiscount = signal(false);
  protected readonly showNote = signal(false);

  protected toggleMoreDetails(): void {
    this.moreDetails.update((v) => !v);
  }
  protected toggleDiscount(): void {
    this.showDiscount.set(true);
  }
  protected toggleNote(): void {
    this.showNote.set(true);
  }

  /**
   * Reveals any collapsed expander whose field already carries a value, so
   * prefilled data (reorder / convert / prefill-from-last / resumed draft) is
   * never hidden behind a closed section.
   */
  private syncExpandersFromForm(): void {
    const c = this.form.controls;
    if (c.alternateMobile.value || c.customerEmail.value || c.buyerGstin.value) {
      this.moreDetails.set(true);
    }
    if (c.discountType.value) {
      this.showDiscount.set(true);
    }
    if (c.notes.value) {
      this.showNote.set(true);
    }
  }

  /**
   * Whether a payment screenshot is mandatory. Under the min-upfront policy a
   * payment (≥ ₹100 / full) is ALWAYS collected, so a screenshot is always
   * required once there is an order total to pay for.
   */
  protected readonly screenshotRequired = computed(() => this.orderTotalPaise() > 0);

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

  /**
   * How the currently entered amount classifies the payment, used to highlight
   * the matching shortcut and show a plain-language hint:
   * <ul>
   *   <li>{@code 'cod'}   — nothing received, full amount collected on delivery;</li>
   *   <li>{@code 'full'}  — the whole order total received now;</li>
   *   <li>{@code 'partial'} — some received now, the balance collected on delivery;</li>
   *   <li>{@code 'none'}  — no order total yet (no items), so nothing to classify.</li>
   * </ul>
   */
  protected readonly paymentKind = computed<'below' | 'partial' | 'full' | 'none'>(() => {
    const total = this.orderTotalPaise();
    if (total <= 0) {
      return 'none';
    }
    const received = toPaise(this.model().amountReceived);
    if (received < this.minUpfrontPaise()) {
      // Below the minimum upfront — not a valid Full/Partial payment (no ₹0/COD).
      return 'below';
    }
    if (received >= total) {
      return 'full';
    }
    return 'partial';
  });

  /**
   * Minimum amount (paise) that must be collected upfront — the client policy is
   * no ₹0/COD orders: at least ₹100, or the full total when the total is under
   * ₹100 (a small order can't require more than it costs). Mirrors the backend
   * {@code requireMinimumUpfront}.
   */
  protected readonly minUpfrontPaise = computed<number>(() => {
    const total = this.orderTotalPaise();
    return Math.min(10000, total); // ₹100 = 10000 paise
  });

  /** Whether the entered amount meets the minimum-upfront policy (blocks submit). */
  protected readonly paymentBelowMinimum = computed<boolean>(() => {
    const total = this.orderTotalPaise();
    if (total <= 0) {
      return false; // total handled separately; nothing to validate yet
    }
    return toPaise(this.model().amountReceived) < this.minUpfrontPaise();
  });

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

    // Remember only a safe workflow preference; customer and payment values are
    // never carried from one fresh order to another.
    this.form.controls.leadSource.valueChanges.subscribe((source) => {
      if (this.convertMode() || !source || source === 'OTHER') {
        return;
      }
      try {
        localStorage.setItem(NewOrderComponent.PREFERRED_LEAD_SOURCE_KEY, source);
      } catch {
        /* storage unavailable — non-fatal */
      }
    });

    // Place-on-behalf-of picker (ADMIN only): load the active salespeople +
    // team leads the admin can attribute the order to. Non-fatal — the picker
    // just stays empty on failure and the order defaults to a self order.
    if (this.isAdmin()) {
      this.orders.assignableCreators().subscribe({
        next: (people) => this.assignableCreators.set(people),
        error: () => this.assignableCreators.set([]),
      });
    }

    // Convert-from-lead mode: seed customer + source from the lead and lock them.
    const leadIdParam = this.route.snapshot.queryParamMap.get('leadId');
    const leadId = leadIdParam ? Number(leadIdParam) : NaN;
    if (Number.isFinite(leadId) && leadId > 0) {
      this.initConvertMode(leadId);
      return;
    }

    // Fix & resubmit a rejected order: load the SAME order for editing and, on
    // save, resubmit it (mutually exclusive with convert/reorder).
    const resubmitParam = this.route.snapshot.queryParamMap.get('resubmitFrom');
    const resubmitId = resubmitParam ? Number(resubmitParam) : NaN;
    if (Number.isFinite(resubmitId) && resubmitId > 0) {
      this.resubmitActive = true;
      this.initResubmitMode(resubmitId);
      // Autosave the in-progress edits (debounced); resubmit mode skips draft save.
      this.form.valueChanges.pipe(debounceTime(800)).subscribe(() => this.saveDraft());
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
    if (this.convertMode() || this.reorderActive || this.resubmitActive || this.submitting()) {
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
    this.syncExpandersFromForm();
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
            customerEmail: o.customerEmail ?? '',
            alternateMobile: o.alternateMobile ?? '',
            addressLine: o.addressLine ?? '',
            city: o.city ?? '',
            state: o.state ?? '',
            postalCode: o.postalCode ?? '',
            leadSource: o.leadSource ?? this.form.controls.leadSource.value,
            leadSourceNote: o.leadSourceNote ?? '',
            buyerGstin: o.buyerGstin ?? '',
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
        this.syncExpandersFromForm();
        this.toasts.success(`Loaded ${lines.length} item(s) from ${o.orderCode} — review and save.`);
      },
      error: () => this.toasts.error('Could not load that order to reorder.'),
    });
  }

  /**
   * Loads a REJECTED / PAYMENT_REJECTED order for rework (rejection-status rework
   * feature): pre-fills the SAME order's customer / shipping / line-item /
   * discount details for editing, keeping the order id so {@link submit} resubmits
   * it (rather than creating a new order). Guards that the order is actually
   * rejected; otherwise it bounces the user back to the order.
   */
  private initResubmitMode(orderId: number): void {
    this.orders.detail(orderId).subscribe({
      next: (o) => {
        if (o.orderStatus !== 'REJECTED' && o.orderStatus !== 'PAYMENT_REJECTED') {
          this.toasts.error(`Order ${o.orderCode} is not rejected, so it can't be resubmitted.`);
          void this.router.navigate(['/orders'], { queryParams: { q: o.orderCode } });
          return;
        }
        this.resubmitOrderId.set(o.id);
        this.resubmitFromCode.set(o.orderCode);
        this.resubmitPaymentRejected.set(o.orderStatus === 'PAYMENT_REJECTED');
        this.form.patchValue(
          {
            customerName: o.customerName ?? '',
            customerMobile: o.customerMobile ?? '',
            customerEmail: o.customerEmail ?? '',
            alternateMobile: o.alternateMobile ?? '',
            addressLine: o.addressLine ?? '',
            city: o.city ?? '',
            state: o.state ?? '',
            postalCode: o.postalCode ?? '',
            leadSource: o.leadSource ?? this.form.controls.leadSource.value,
            leadSourceNote: o.leadSourceNote ?? '',
            notes: o.notes ?? '',
            buyerGstin: o.buyerGstin ?? '',
            discountType: (o.discountType as OrderDiscountType | undefined) ?? '',
            discountValue: o.discountValue != null ? Number(o.discountValue) : 0,
          },
          { emitEvent: false },
        );
        // Rebuild the items list from the order's current lines.
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
        this.syncExpandersFromForm();
        this.toasts.success(`Loaded ${o.orderCode} — fix the flagged details and resubmit for approval.`);
      },
      error: () => this.toasts.error('Could not load that order to resubmit.'),
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
        this.syncExpandersFromForm();
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
  /** Admin picks whether the order is for themselves or on behalf of someone. */
  setPlaceFor(who: 'self' | 'other'): void {
    this.placeFor.set(who);
    if (who === 'self') {
      this.onBehalfUserId.set(null);
    }
  }

  /** Admin picks the salesperson/team lead to attribute the order to. */
  onBehalfSelected(value: string): void {
    const id = value ? Number(value) : NaN;
    this.onBehalfUserId.set(Number.isFinite(id) && id > 0 ? id : null);
  }

  /**
   * Switches the order destination between India (structured city/state/pincode)
   * and Outside India (a single free-text address). For an international order the
   * structured controls are cleared, de-validated and disabled — so they don't
   * block the form and the pincode auto-fill (guarded by `enabled`) is skipped;
   * switching back to India restores their required validators.
   */
  setDestination(dest: 'india' | 'outside'): void {
    this.destination.set(dest);
    const city = this.form.controls.city;
    const state = this.form.controls.state;
    const postalCode = this.form.controls.postalCode;
    if (dest === 'outside') {
      for (const c of [city, state, postalCode]) {
        c.clearValidators();
        c.setValue('');
        c.disable();
        c.updateValueAndValidity();
      }
      this.detectedLocation.set(null);
    } else {
      city.setValidators([Validators.required, Validators.maxLength(100)]);
      state.setValidators([Validators.required, Validators.maxLength(100)]);
      postalCode.setValidators([Validators.required, Validators.pattern(/^\d{6}$/)]);
      for (const c of [city, state, postalCode]) {
        c.enable();
        c.updateValueAndValidity();
      }
      this.countryName.set('');
    }
  }

  /** Captures the destination country name for an international order. */
  onCountryChange(value: string): void {
    this.countryName.set(value ?? '');
  }

  private checkDuplicateCustomer(mobile: string | null): void {
    if (!mobile || !/^\d{10}$/.test(mobile)) {
      this.priorOrderCount.set(null);
      this.sameDayDuplicate.set(null);
      this.customerRisk.set(null);
      this.prefilledFromLast.set(null);
      return;
    }
    this.orders.duplicateCheck(mobile).subscribe({
      next: (res) => {
        this.priorOrderCount.set(res.priorOrderCount);
        // Same-day duplicate: warn (and block) when an active order for this
        // mobile was already placed today, possibly by a different salesperson.
        this.sameDayDuplicate.set(
          res.hasTodayOrder
            ? {
                orderCode: res.todayOrderCode,
                salespersonName: res.todaySalespersonName,
                createdByMe: res.todayCreatedByMe,
              }
            : null,
        );
      },
      error: () => {
        this.priorOrderCount.set(null);
        this.sameDayDuplicate.set(null);
      },
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
        this.syncExpandersFromForm();
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

  /**
   * Enter-to-advance keyboard flow (item 3): pressing Enter in a single-line
   * field moves to the next wizard step instead of submitting the form early. It
   * is a no-op in quick (single-screen) mode, on the final step, or inside a
   * textarea.
   */
  onFormEnter(event: Event): void {
    if (this.quickMode()) {
      return;
    }
    const target = event.target as HTMLElement | null;
    const tag = target?.tagName?.toLowerCase();
    if (tag === 'textarea') {
      return;
    }
    if (this.step() < this.totalSteps) {
      event.preventDefault();
      this.nextStep();
    }
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

  /**
   * Attaches one or more payment proofs (V65). The file input is `multiple`, so the
   * salesperson can pick several at once, and may also add more in a later pass —
   * each selection APPENDS rather than replacing what is already attached.
   *
   * <p>Non-image files are rejected up front, and anything beyond the per-order cap
   * is refused with a message instead of being silently dropped. Each accepted file
   * is uploaded on its own so one failure is isolated and individually retryable.
   */
  onScreenshotSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const chosen = Array.from(input.files ?? []);
    // Always clear the input so re-picking the same file (after a failure or a
    // removal) fires a fresh change event.
    input.value = '';
    if (chosen.length === 0) {
      return;
    }

    const images = chosen.filter((f) => f.type.startsWith('image/'));
    const rejected = chosen.length - images.length;

    const room = MAX_PAYMENT_SCREENSHOTS - this.screenshots().length;
    const accepted = images.slice(0, Math.max(0, room));
    const overflow = images.length - accepted.length;

    const problems: string[] = [];
    if (rejected > 0) {
      problems.push(
        rejected === 1
          ? 'One file was skipped because it is not an image.'
          : `${rejected} files were skipped because they are not images.`,
      );
    }
    if (overflow > 0) {
      problems.push(
        `At most ${MAX_PAYMENT_SCREENSHOTS} screenshots can be attached, so ${overflow} more ${
          overflow === 1 ? 'was' : 'were'
        } not added.`,
      );
    }
    this.uploadError.set(problems.length > 0 ? problems.join(' ') : null);

    for (const file of accepted) {
      this.attachScreenshot(file);
    }
  }

  /** Appends one proof in the uploading state and starts its upload. */
  private attachScreenshot(file: File): void {
    const attachment: ScreenshotAttachment = {
      name: file.name,
      previewUrl: URL.createObjectURL(file),
      key: null,
      state: 'uploading',
      error: null,
    };
    this.screenshots.update((all) => [...all, attachment]);

    this.orders.uploadPaymentScreenshot(file).subscribe({
      next: (res) => this.updateScreenshot(attachment, { key: res.key, state: 'done', error: null }),
      error: (err: HttpErrorResponse) =>
        this.updateScreenshot(attachment, {
          state: 'error',
          error: this.messageOf(err) ?? 'Upload failed. Please try again.',
        }),
    });
  }

  /**
   * Applies a patch to one attachment by identity. Identity matching (rather than an
   * index) keeps the right proof updated even when the user removes another one while
   * an upload is still in flight.
   */
  private updateScreenshot(target: ScreenshotAttachment, patch: Partial<ScreenshotAttachment>): void {
    this.screenshots.update((all) => all.map((s) => (s === target ? { ...s, ...patch } : s)));
  }

  /** Removes one attached proof, releasing its local preview. */
  removeScreenshot(index: number): void {
    const attachment = this.screenshots()[index];
    if (!attachment) {
      return;
    }
    if (attachment.previewUrl) {
      URL.revokeObjectURL(attachment.previewUrl);
    }
    this.screenshots.update((all) => all.filter((_, i) => i !== index));
    this.uploadError.set(null);
  }

  /** Removes every attached proof. */
  clearScreenshot(): void {
    this.revokePreview();
    this.uploadError.set(null);
  }

  private revokePreview(): void {
    for (const attachment of this.screenshots()) {
      if (attachment.previewUrl) {
        URL.revokeObjectURL(attachment.previewUrl);
      }
    }
    this.screenshots.set([]);
  }

  /** Sets a payment amount using one-hand quick actions in the payment step. */
  setAmountReceived(amount: number): void {
    const total = this.orderTotalPaise() / 100;
    const safe = Math.max(0, Math.min(Number.isFinite(amount) ? amount : 0, total));
    this.form.controls.amountReceived.setValue(Number(safe.toFixed(2)));
    this.form.controls.amountReceived.markAsDirty();
    this.form.controls.amountReceived.markAsTouched();
    this.model.set(this.snapshot());
  }

  /** Convenience action for a fully prepaid order. */
  setPaidInFull(): void {
    this.setAmountReceived(this.orderTotalPaise() / 100);
  }

  /**
   * On a fresh order, the previously used lead source is the only default we
   * retain. Customer, address, products, discount and payment are intentionally
   * never copied between customers/orders.
   */
  private loadPreferredLeadSource(): '' | LeadSource {
    try {
      const saved = localStorage.getItem(NewOrderComponent.PREFERRED_LEAD_SOURCE_KEY) as LeadSource | null;
      return saved && this.leadSourceOptions.some((option) => option.value === saved) ? saved : '';
    } catch {
      return '';
    }
  }

  // --- Guided wizard (product-audit §3.2) ---------------------------------

  /** The current wizard step (1=Customer, 2=Items, 3=Payment, 4=Review). */
  protected readonly step = signal(1);
  protected readonly totalSteps = 4;
  protected readonly stepLabels = ['Customer', 'Items', 'Payment', 'Review'];

  /**
   * Quick-order mode: shows every section on one compact screen (no wizard
   * stepping) so an experienced salesperson can punch an order fast. The
   * preference is remembered in localStorage. Convert/reorder still work in
   * either mode. When on, the step gating in the template is bypassed and the
   * footer shows a single Save action.
   */
  private static readonly QUICK_MODE_KEY = 'shifa:new-order-quick-mode';
  protected readonly quickMode = signal(this.loadQuickMode());

  /** Toggles quick (single-screen) vs guided (wizard) entry and remembers it. */
  toggleQuickMode(): void {
    const next = !this.quickMode();
    this.quickMode.set(next);
    try {
      localStorage.setItem(NewOrderComponent.QUICK_MODE_KEY, next ? '1' : '0');
    } catch {
      /* storage unavailable — non-fatal */
    }
    if (!next) {
      // Returning to the wizard: start from the first step.
      this.step.set(1);
    }
  }

  private loadQuickMode(): boolean {
    try {
      return localStorage.getItem(NewOrderComponent.QUICK_MODE_KEY) === '1';
    } catch {
      return false;
    }
  }

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
    // NOTE: a same-day order for this mobile is only INFORMATIONAL here — it no
    // longer blocks advancing. A real duplicate (same customer + same item on the
    // same day) is enforced by the server at submit, since the items aren't known
    // until step 2. A customer may place several same-day orders for different items.
    if (step === 1 && this.onBehalfMissing()) {
      // Admin chose "on behalf of" but hasn't picked a person yet.
      this.submitAttempted.set(true);
      ok = false;
    }
    if (step === 2) {
      this.items.controls.forEach((group) => group.markAllAsTouched());
      // Block advancing when a line's price is outside its product band (Req 5.2),
      // mirroring the server rule so the salesperson fixes it here.
      if (this.items.invalid || this.orderTotalPaise() <= 0 || this.hasBandErrors()) {
        ok = false;
      }
    }
    if (step === 3) {
      // Min-upfront policy: a Full or Partial payment of at least ₹100 (or the
      // full total when under ₹100) must be collected — no ₹0 orders.
      if (this.paymentBelowMinimum()) {
        this.submitAttempted.set(true);
        ok = false;
      }
      if (this.screenshotRequired() && !this.screenshotKey()) {
        this.submitAttempted.set(true);
        ok = false;
      }
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
    if (this.paymentBelowMinimum()) {
      this.toasts.error(
        `At least ${this.formatMoney(this.minUpfrontPaise())} must be collected upfront (full or partial payment).`,
      );
      return;
    }
    if (this.screenshotRequired() && !this.screenshotKey()) {
      this.toasts.error('A payment screenshot is required to place the order.');
      return;
    }
    // Same-day duplicate is NOT blocked client-side: it's only a real duplicate
    // when the same customer repeats the same ITEM on the same day, which the
    // server checks against this order's line items and rejects with a clear 400
    // (surfaced via serverErrors/toast). Different-item same-day orders are allowed.
    // Admin chose "on behalf of" but didn't pick a person.
    if (this.onBehalfMissing()) {
      this.toasts.error('Select the salesperson or team lead to place this order on behalf of.');
      this.step.set(1);
      this.scrollTop();
      return;
    }
    // Outside-India order needs the destination country named.
    if (this.isInternational() && !this.countryName().trim()) {
      this.submitAttempted.set(true);
      this.toasts.error('Enter the destination country for an order outside India.');
      this.step.set(1);
      this.scrollTop();
      return;
    }

    const raw = this.snapshot();
    const converting = this.convertMode();
    const resubmitting = this.resubmitMode();
    const confirmed = await this.confirm.confirm({
      title: converting ? 'Convert lead to order' : resubmitting ? 'Resubmit for approval' : 'Create order',
      message: converting
        ? `Convert ${this.convertLeadName()} into an order with a total of ${this.formatMoney(this.orderTotalPaise())}? The lead will be marked Won.`
        : resubmitting
          ? `Resubmit order ${this.resubmitFromCode()} (total ${this.formatMoney(this.orderTotalPaise())}) back to the approval queue?`
          : `Create this order for ${this.form.controls.customerName.value} with a total of ${this.formatMoney(this.orderTotalPaise())}?`,
      confirmLabel: converting ? 'Convert' : resubmitting ? 'Resubmit' : 'Create order',
      icon: converting ? 'ti-shopping-cart-plus' : resubmitting ? 'ti-send' : 'ti-receipt',
    });
    if (!confirmed) {
      return;
    }

    if (converting) {
      this.submitConvert(raw);
      return;
    }

    if (resubmitting) {
      this.submitResubmit(raw);
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
      // For an international order the structured parts are blank (disabled controls
      // hold '') and the free-text address is in addressLine; the server accepts this.
      city: this.form.controls.city.value.trim(),
      state: this.form.controls.state.value.trim(),
      postalCode: this.form.controls.postalCode.value.trim(),
      // Destination country: only sent for an Outside-India order (domestic = India).
      ...(this.isInternational() ? { country: this.countryName().trim() } : {}),
      items: raw.items.map<CreateOrderLineItem>((it) => ({
        productId: it.productId as number,
        quantity: it.quantity,
        ...(it.rate != null ? { rate: it.rate } : {}),
      })),
      amountReceived: raw.amountReceived,
      ...(this.screenshotKey() ? { paymentScreenshotKey: this.screenshotKey()! } : {}),
      // Additional payment proofs beyond the primary (V65), omitted when there is
      // only one so the payload stays identical to the pre-V65 shape.
      ...(this.extraScreenshotKeys().length > 0
        ? { paymentScreenshotKeys: this.extraScreenshotKeys() }
        : {}),
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
      // Delivery method is no longer a salesperson choice — every order created
      // here defaults to in-house; the admin picks/overrides the delivery
      // partner (QuikShipX vs in-house) at approval time.
      deliveryMethod: 'IN_HOUSE',
      // Place on behalf of (ADMIN only): attribute the order to the chosen
      // salesperson/team lead. Sent only when the admin picked "on behalf of".
      ...(this.showOnBehalfPicker() && this.placeFor() === 'other' && this.onBehalfUserId() != null
        ? { onBehalfOfUserId: this.onBehalfUserId()! }
        : {}),
    };

    // Offline order creation is no longer possible: every order now collects an
    // upfront payment (≥ ₹100 / full) AND requires the payment screenshot to be
    // uploaded, both of which need a connection. (The old offline path only
    // supported ₹0/COD orders, which are no longer allowed.)
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      this.toasts.error(
        'You are offline. Placing an order needs a connection to collect the upfront payment and upload its screenshot — please try again when back online.',
      );
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
   * Resubmit-mode save (rejection-status rework feature): posts the corrected
   * order details (an {@link UpdateOrderRequest} — same shape as an edit, no
   * payment capture) to {@code POST /api/orders/{id}/resubmit}. The server
   * re-prices, clears the rejection, resets a prepaid order's payment
   * verification to PENDING, and moves the SAME order back to
   * {@code Pending_Admin_Approval}. Requires a connection (a server transaction),
   * so it can't be queued offline.
   */
  private submitResubmit(raw: ReturnType<NewOrderComponent['snapshot']>): void {
    const orderId = this.resubmitOrderId();
    if (orderId === null) {
      return;
    }
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      this.toasts.error('You are offline. Resubmitting an order needs a connection — please try again online.');
      return;
    }

    const email = this.form.controls.customerEmail.value.trim();
    const altMobile = this.form.controls.alternateMobile.value.trim();
    const note = this.form.controls.leadSourceNote.value.trim();
    const orderNotes = this.form.controls.notes.value.trim();
    const buyerGstin = this.form.controls.buyerGstin.value.trim().toUpperCase();
    const isOther = raw.leadSource === 'OTHER';
    const payload: UpdateOrderRequest = {
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
      leadSource: raw.leadSource as LeadSource,
      ...(isOther && note ? { leadSourceNote: note } : {}),
      ...(orderNotes ? { notes: orderNotes } : {}),
      ...(buyerGstin ? { buyerGstin } : {}),
      ...(raw.discountType
        ? { discountType: raw.discountType as OrderDiscountType, discountValue: raw.discountValue || 0 }
        : {}),
    };

    this.submitting.set(true);
    this.orders.resubmit(orderId, payload).subscribe({
      next: (order) => {
        this.submitting.set(false);
        this.toasts.success(`Order ${order.orderCode} resubmitted for approval`);
        void this.router.navigate(['/orders'], { queryParams: { q: order.orderCode } });
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        const body = err.error as ApiError | undefined;
        const details = body?.details ?? [];
        this.serverErrors.set(details.length ? details : []);
        this.toasts.error(this.messageOf(err) ?? 'Could not resubmit the order. Please try again.');
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
      // Additional payment proofs beyond the primary (V65), omitted when there is
      // only one so the payload stays identical to the pre-V65 shape.
      ...(this.extraScreenshotKeys().length > 0
        ? { paymentScreenshotKeys: this.extraScreenshotKeys() }
        : {}),
      ...(this.form.controls.notes.value.trim()
        ? { notes: this.form.controls.notes.value.trim() }
        : {}),
      // Order-level discount (product-catalog-pricing-gst Req 6), only when set.
      ...(raw.discountType
        ? { discountType: raw.discountType as OrderDiscountType, discountValue: raw.discountValue || 0 }
        : {}),
      // Delivery method is no longer a salesperson choice — defaults to in-house.
      deliveryMethod: 'IN_HOUSE',
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
