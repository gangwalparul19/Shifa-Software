import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import {
  FormArray,
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router } from '@angular/router';
import { ApiError, Product, paiseToMoney, toPaise } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { CatalogService } from './catalog.service';
import { OrdersService } from './orders.service';
import { CreateOrderLineItem, CreateOrderRequest } from './orders.model';

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
  imports: [ReactiveFormsModule, PageHeaderComponent, StatePanelComponent],
  templateUrl: './new-order.component.html',
  styleUrl: './new-order.component.css',
})
export class NewOrderComponent implements OnInit, OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly orders = inject(OrdersService);
  private readonly catalog = inject(CatalogService);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);

  // --- Product catalog (picker source) ------------------------------------
  protected readonly products = signal<Product[]>([]);
  protected readonly productsLoading = signal(true);
  protected readonly productsError = signal<string | null>(null);

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

  /** A snapshot of the form value, refreshed on every change to drive totals. */
  private readonly model = signal<ReturnType<NewOrderComponent['snapshot']>>({
    items: [],
    amountReceived: 0,
  });

  protected readonly form = this.fb.nonNullable.group({
    customerName: ['', [Validators.required, Validators.maxLength(100)]],
    customerMobile: ['', [Validators.required, Validators.pattern(/^\d{10}$/)]],
    addressLine: ['', [Validators.required, Validators.maxLength(250)]],
    city: ['', [Validators.required, Validators.maxLength(100)]],
    state: ['', [Validators.required, Validators.maxLength(100)]],
    postalCode: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
    items: this.fb.array([this.newItem()]),
    amountReceived: [0, [Validators.required, Validators.min(0)]],
  });

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
    // Keep the totals snapshot in sync with the reactive form.
    this.model.set(this.snapshot());
    this.form.valueChanges.subscribe(() => this.model.set(this.snapshot()));
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
    const confirmed = await this.confirm.confirm({
      title: 'Create order',
      message: `Create this order for ${this.form.controls.customerName.value} with a total of ${this.formatMoney(this.orderTotalPaise())}?`,
      confirmLabel: 'Create order',
      icon: 'ti-receipt',
    });
    if (!confirmed) {
      return;
    }

    const payload: CreateOrderRequest = {
      customerName: this.form.controls.customerName.value.trim(),
      customerMobile: this.form.controls.customerMobile.value.trim(),
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
    };

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

  cancel(): void {
    void this.router.navigate(['/orders']);
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
  } {
    const raw = this.form.getRawValue();
    return {
      items: raw.items.map((it) => ({
        productId: (it['productId'] ?? null) as number | null,
        quantity: Number(it['quantity']) || 0,
        rate: it['rate'] === null || it['rate'] === undefined ? null : Number(it['rate']),
      })),
      amountReceived: Number(raw.amountReceived) || 0,
    };
  }
}
