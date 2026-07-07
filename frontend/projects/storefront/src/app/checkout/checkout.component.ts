import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiError, AuthService, validateCheckout } from 'core';
import { catchError, of } from 'rxjs';
import { debounceTime, distinctUntilChanged, filter, switchMap, tap } from 'rxjs/operators';
import { CartService } from '../shared/cart.service';
import { CartItem } from '../shared/cart-item.model';
import { ToastService } from '../shared/toast.service';
import { AccountApi, CustomerAddress } from '../account/account-api.service';
import { PincodeService } from '../shared/pincode.service';
import { deliveryEstimate } from '../shared/shipping-info';
import { formatInr, paiseToMoney, toPaise } from '../shared/money';
import { CheckoutService, ValidateCouponResponse } from './checkout.service';
import {
  InitiatePaymentResponse,
  PaymentService,
} from './payment.service';

/** An applied coupon preview held on the checkout page (Phase D). */
interface AppliedCoupon {
  code: string;
  discountAmount: string;
  newTotal: string;
  freeShipping: boolean;
}

/** The payment method chosen at checkout (Phase E). */
type PaymentMethod = 'COD' | 'ONLINE';

/** In-page sandbox payment step state (Phase E). */
interface PaymentModal {
  orderId: number;
  orderCode: string;
  name: string;
  session: InitiatePaymentResponse;
}

/**
 * Checkout page (route 'checkout', Requirement 3).
 *
 * <p>Collects customer name (1..100), a 10-digit mobile, address line (1..250),
 * city, state and a 6-digit postal code, applying the Requirement 3 validation
 * rules: empty cart is blocked (3.2); missing fields, an invalid mobile (3.4) or
 * an invalid postal code (3.5) are flagged per-field, block submission, and
 * retain entered values (3.3).
 *
 * <p>On a valid submit the cart is posted to the public {@code POST /api/checkout}
 * endpoint via the shared {@link CheckoutService} (core ApiClient). The server
 * prices the cart, creates the Order in {@code Pending_Admin_Approval} (Req 3.6)
 * and returns the real order code, which is shown in the confirmation (Req 3.7);
 * the cart is then cleared. Server-side validation errors are surfaced to the
 * customer while the entered values are retained.
 */
@Component({
  selector: 'sf-checkout',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './checkout.component.html',
  styleUrl: './checkout.component.css',
})
export class CheckoutComponent {
  private readonly fb = inject(FormBuilder);
  private readonly checkout = inject(CheckoutService);
  private readonly payments = inject(PaymentService);
  private readonly auth = inject(AuthService);
  private readonly accountApi = inject(AccountApi);
  private readonly pincode = inject(PincodeService);
  private readonly toasts = inject(ToastService);
  protected readonly cart = inject(CartService);

  protected readonly submitted = signal(false);
  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly fieldErrors = signal<string[]>([]);
  protected readonly confirmation = signal<{
    reference: string;
    name: string;
    paidOnline: boolean;
  } | null>(null);

  // --- Payment method + sandbox payment step (Phase E) --------------------
  protected readonly paymentMethod = signal<PaymentMethod>('COD');
  protected readonly paymentModal = signal<PaymentModal | null>(null);
  protected readonly paying = signal(false);
  protected readonly paymentError = signal<string | null>(null);

  // --- Coupon state (Phase D) --------------------------------------------
  protected readonly couponCode = signal('');
  protected readonly applying = signal(false);
  protected readonly couponError = signal<string | null>(null);
  protected readonly appliedCoupon = signal<AppliedCoupon | null>(null);

  /** Whether the customer is signed in (enables prefill + save-address). */
  protected readonly loggedIn = signal(false);
  /** True once a saved default address was used to prefill the form. */
  protected readonly prefilled = signal(false);
  /** The customer's default address id, if any (so we don't offer to re-save it). */
  private defaultAddressId: number | null = null;

  // --- Pincode → city/state autofill --------------------------------------
  /** True while a pincode lookup is in flight (drives the "looking up…" hint). */
  protected readonly lookingUpPincode = signal(false);
  /** The city resolved from the entered pincode, for the delivery estimate. */
  protected readonly resolvedCity = signal<string | null>(null);
  /** Last city/state we autofilled, so we only overwrite our own values. */
  private lastAutoCity = '';
  private lastAutoState = '';

  protected readonly form = this.fb.nonNullable.group({
    customerName: ['', [Validators.required, Validators.maxLength(100)]],
    mobile: ['', [Validators.required, Validators.pattern(/^\d{10}$/)]],
    addressLine: ['', [Validators.required, Validators.maxLength(250)]],
    city: ['', [Validators.required, Validators.maxLength(100)]],
    state: ['', [Validators.required, Validators.maxLength(100)]],
    postalCode: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
    saveAddress: [false],
  });

  constructor() {
    if (this.auth.isAuthenticated()) {
      this.loggedIn.set(true);
      this.prefillFromAccount();
    }
    this.watchPincode();
  }

  /**
   * Debounced pincode → city/state autofill via the free India Post API. On a
   * valid 6-digit code we resolve the city/state and prefill those fields when
   * they're empty or still hold a previously auto-filled value (so we never
   * clobber what the customer typed). Any failure/timeout is a silent no-op —
   * the customer just types city/state manually — and it never blocks submit.
   */
  private watchPincode(): void {
    this.form.controls.postalCode.valueChanges
      .pipe(
        debounceTime(500),
        distinctUntilChanged(),
        tap(() => this.resolvedCity.set(null)),
        filter((code) => /^\d{6}$/.test(code)),
        tap(() => this.lookingUpPincode.set(true)),
        switchMap((code) => this.pincode.lookup(code)),
        takeUntilDestroyed(),
      )
      .subscribe((location) => {
        this.lookingUpPincode.set(false);
        if (!location) {
          return;
        }
        this.resolvedCity.set(location.city);
        this.autofill('city', location.city);
        this.autofill('state', location.state);
      });
  }

  /** Sets a field from a pincode lookup only if it's empty or our own prior value. */
  private autofill(control: 'city' | 'state', value: string): void {
    const ctrl = this.form.controls[control];
    const current = (ctrl.value ?? '').trim();
    const lastAuto = control === 'city' ? this.lastAutoCity : this.lastAutoState;
    if (current === '' || current === lastAuto) {
      ctrl.setValue(value);
      ctrl.markAsTouched();
      if (control === 'city') {
        this.lastAutoCity = value;
      } else {
        this.lastAutoState = value;
      }
    }
  }

  /** Delivery estimate for the summary — destination-aware when a pincode resolved. */
  protected readonly deliveryMessage = computed(() => deliveryEstimate(this.resolvedCity()));

  /**
   * Prefills the checkout form from the signed-in customer's default saved
   * address (falling back to profile name/mobile), so returning customers
   * check out faster. Values remain fully editable.
   */
  private prefillFromAccount(): void {
    this.accountApi
      .listAddresses()
      .pipe(catchError(() => of([] as CustomerAddress[])))
      .subscribe((addresses) => {
        const preferred = addresses.find((a) => a.isDefault) ?? addresses[0];
        if (preferred) {
          this.defaultAddressId = preferred.id;
          this.form.patchValue({
            customerName: preferred.fullName,
            mobile: preferred.mobile,
            addressLine: preferred.addressLine,
            city: preferred.city,
            state: preferred.state,
            postalCode: preferred.postalCode,
          });
          this.prefilled.set(true);
        } else {
          this.prefillFromProfile();
        }
      });
  }

  private prefillFromProfile(): void {
    this.accountApi
      .getProfile()
      .pipe(catchError(() => of(null)))
      .subscribe((profile) => {
        if (profile) {
          this.form.patchValue({
            customerName: profile.fullName ?? '',
            mobile: profile.mobile ?? '',
          });
        }
      });
  }

  protected readonly subtotalDisplay = computed(() => formatInr(this.cart.subtotal()));

  /** The applied coupon's discount, formatted for display (e.g. "₹50"). */
  protected readonly discountDisplay = computed(() => {
    const c = this.appliedCoupon();
    return c ? formatInr(c.discountAmount) : '';
  });

  /** The order total after any applied discount (Phase D). */
  protected readonly totalDisplay = computed(() => {
    const c = this.appliedCoupon();
    return formatInr(c ? c.newTotal : this.cart.subtotal());
  });

  lineTotal(item: CartItem): string {
    return formatInr(paiseToMoney(toPaise(item.salePrice) * item.quantity));
  }

  /** The current cart lines as validate/checkout item payloads. */
  private cartItems(): { productId: number; quantity: number }[] {
    return this.cart.items().map((item) => ({
      productId: item.productId,
      quantity: item.quantity,
    }));
  }

  /**
   * Previews the entered coupon against the cart (Phase D). On success the
   * discount + new total are shown; an inapplicable/expired code shows the
   * server's message (e.g. "A minimum cart amount of ₹X is required").
   */
  applyCoupon(): void {
    const code = this.couponCode().trim();
    this.couponError.set(null);
    if (!code) {
      this.couponError.set('Enter a coupon code.');
      return;
    }
    if (this.cart.isEmpty() || this.applying()) {
      return;
    }
    this.applying.set(true);
    this.checkout
      .validateCoupon({ code, items: this.cartItems() })
      .pipe(catchError(() => of(null)))
      .subscribe((response: ValidateCouponResponse | null) => {
        this.applying.set(false);
        if (!response) {
          this.couponError.set('We could not validate that coupon. Please try again.');
          return;
        }
        if (!response.valid) {
          this.appliedCoupon.set(null);
          this.couponError.set(response.message || 'This coupon is not valid.');
          return;
        }
        this.appliedCoupon.set({
          code: response.code,
          discountAmount: response.discountAmount,
          newTotal: response.newTotal,
          freeShipping: response.freeShipping,
        });
        this.toasts.success(`Coupon ${response.code} applied`);
      });
  }

  /** Removes the applied coupon (Phase D). */
  removeCoupon(): void {
    this.appliedCoupon.set(null);
    this.couponCode.set('');
    this.couponError.set(null);
  }

  /** Reflects the coupon input into the signal for template binding. */
  onCouponInput(value: string): void {
    this.couponCode.set(value);
  }

  /** Selects the payment method (Phase E). */
  selectPaymentMethod(method: PaymentMethod): void {
    this.paymentMethod.set(method);
  }

  /** The sandbox payment amount, formatted for the "Pay ₹X (Test)" button. */
  protected readonly paymentAmountDisplay = computed(() => {
    const modal = this.paymentModal();
    return modal ? formatInr(modal.session.amount) : '';
  });

  /** Whether a field should show its error (invalid and touched/submitted). */
  showError(control: keyof typeof this.form.controls): boolean {
    const c = this.form.controls[control];
    return c.invalid && (c.touched || this.submitted());
  }

  placeOrder(): void {
    this.submitted.set(true);
    this.errorMessage.set(null);
    this.fieldErrors.set([]);

    // Req 3.2 — block checkout with an empty cart.
    if (this.cart.isEmpty()) {
      return;
    }

    // Req 3.3, 3.4, 3.5 — block submission on any invalid field; values are
    // retained automatically because the form model is untouched on failure.
    // The pure `validateCheckout` predicate (shared core, property-tested) is
    // authoritative for the submit gate and produces the per-field messages.
    const values = this.form.getRawValue();
    const validation = validateCheckout({
      customerName: values.customerName,
      mobile: values.mobile,
      addressLine: values.addressLine,
      city: values.city,
      state: values.state,
      postalCode: values.postalCode,
    });
    if (!validation.valid) {
      this.form.markAllAsTouched();
      this.fieldErrors.set(validation.messages);
      this.errorMessage.set('Please correct the highlighted fields.');
      return;
    }

    if (this.submitting()) {
      return;
    }

    const request = {
      customerName: values.customerName,
      customerMobile: values.mobile,
      addressLine: values.addressLine,
      city: values.city,
      state: values.state,
      postalCode: values.postalCode,
      items: this.cartItems(),
      couponCode: this.appliedCoupon()?.code,
    };

    this.submitting.set(true);
    const method = this.paymentMethod();
    this.checkout.placeOrder(request).subscribe({
      next: (response) => {
        this.submitting.set(false);
        // Optionally save the entered address to the account (Phase B).
        this.maybeSaveAddress(values);
        // The order now exists server-side (priced + coupon applied); clear the cart.
        this.cart.clear();
        this.removeCoupon();
        if (method === 'ONLINE') {
          // Phase E: begin the online payment for the just-placed order.
          this.startOnlinePayment(response.id, response.orderCode, values.customerName);
        } else {
          // Req 3.7 — COD: show the confirmation using the real returned order code.
          this.showConfirmation(response.orderCode, values.customerName, false);
        }
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.handleError(err);
      },
    });
  }

  // --- Online payment: sandbox step (Phase E) -----------------------------

  /**
   * Initiates the online payment for a just-placed order and opens the in-page
   * sandbox payment step. If initiation fails the order is still placed (COD),
   * so we fall back to the standard confirmation.
   */
  private startOnlinePayment(orderId: number, orderCode: string, name: string): void {
    this.paymentError.set(null);
    this.payments.initiate({ orderId }).subscribe({
      next: (session) => {
        this.paymentModal.set({ orderId, orderCode, name, session });
      },
      error: () => {
        // Could not start payment — the order is placed and will be handled as COD.
        this.showConfirmation(orderCode, name, false);
      },
    });
  }

  /**
   * Completes the sandbox test payment. The sandbox returned a signed
   * {@code clientToken} ({@code paymentId:signature}) at initiate; the client
   * simply echoes those two values to {@code confirm}, where the server verifies
   * the signature. No amount or signature is computed client-side.
   *
   * <p><strong>Real-gateway seam</strong>: replace this method's token-splitting
   * with the provider's checkout widget (opened with {@code keyId} +
   * {@code gatewayOrderId}); the widget hands back {@code gatewayPaymentId} +
   * {@code signature} to pass straight to {@code confirm}. The server contract is
   * unchanged, and no secret is ever echoed by the client.
   */
  payNow(): void {
    const modal = this.paymentModal();
    if (!modal || this.paying()) {
      return;
    }
    const token = modal.session.clientToken ?? '';
    const separator = token.indexOf(':');
    if (separator < 0) {
      this.paymentError.set('This sandbox session is invalid. Please try again.');
      return;
    }
    const gatewayPaymentId = token.substring(0, separator);
    const signature = token.substring(separator + 1);

    this.paying.set(true);
    this.paymentError.set(null);
    this.payments
      .confirm({
        orderId: modal.orderId,
        gatewayOrderId: modal.session.gatewayOrderId,
        gatewayPaymentId,
        signature,
      })
      .subscribe({
        next: (result) => {
          this.paying.set(false);
          if (result.paid) {
            this.paymentModal.set(null);
            this.showConfirmation(result.orderCode, modal.name, true);
          } else {
            this.paymentError.set('Payment could not be verified. Please try again.');
          }
        },
        error: () => {
          this.paying.set(false);
          this.paymentError.set('We could not confirm your payment. Please try again.');
        },
      });
  }

  /**
   * Cancels the sandbox payment step. The order is already placed, so we show
   * the standard confirmation (it will be handled as pay-on-delivery / COD).
   */
  cancelPayment(): void {
    const modal = this.paymentModal();
    this.paymentModal.set(null);
    this.paymentError.set(null);
    if (modal) {
      this.showConfirmation(modal.orderCode, modal.name, false);
    }
  }

  private showConfirmation(reference: string, name: string, paidOnline: boolean): void {
    this.confirmation.set({ reference, name, paidOnline });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  /**
   * Saves the checkout address to the customer's account when signed in and the
   * "save address" box is ticked and it isn't already their default. Best-effort:
   * failures are swallowed so they never disrupt the confirmed order.
   */
  private maybeSaveAddress(values: ReturnType<typeof this.form.getRawValue>): void {
    if (!this.loggedIn() || !values.saveAddress) {
      return;
    }
    this.accountApi
      .createAddress({
        fullName: values.customerName,
        mobile: values.mobile,
        addressLine: values.addressLine,
        city: values.city,
        state: values.state,
        postalCode: values.postalCode,
        makeDefault: this.defaultAddressId === null,
      })
      .pipe(catchError(() => of(null)))
      .subscribe();
  }

  /** Surfaces server-side validation / error messages while retaining entered values. */
  private handleError(err: HttpErrorResponse): void {
    const body = err.error as ApiError | undefined;
    if (body?.details?.length) {
      this.fieldErrors.set(body.details);
      this.errorMessage.set(body.message ?? 'Please correct the highlighted fields.');
    } else if (body?.message) {
      this.errorMessage.set(body.message);
    } else {
      this.errorMessage.set(
        'We could not place your order right now. Please try again in a moment.',
      );
    }
  }
}
