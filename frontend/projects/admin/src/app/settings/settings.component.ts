import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { ApiError } from 'core';
import { AppSettings, SettingsService } from './settings.service';
import { DeliveryState, StatesService } from '../shared/states.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';

/** Accepted logo image types and max size (mirrors the backend rule). */
const LOGO_ACCEPTED_TYPES = ['image/png', 'image/jpeg'];
const LOGO_MAX_BYTES = 1024 * 1024;

/** IFSC format (mirrors the backend rule); blank is allowed by the pattern validator. */
const IFSC_PATTERN = /^[A-Za-z]{4}0[A-Za-z0-9]{6}$/;

/** Highest GST slab the backend accepts. */
const MAX_GST_SLAB = 28;

/**
 * Validates a comma-separated GST-slabs string: each entry must be a number in
 * the 0..28 range (mirrors the backend). Blank is allowed.
 */
function gstSlabsValidator(control: AbstractControl): ValidationErrors | null {
  const raw = (control.value ?? '').toString().trim();
  if (!raw) {
    return null;
  }
  const parts = raw.split(',').map((p: string) => p.trim());
  for (const part of parts) {
    if (part === '' || !/^\d{1,3}(\.\d{1,2})?$/.test(part)) {
      return { gstSlabs: true };
    }
    const value = Number(part);
    if (Number.isNaN(value) || value < 0 || value > MAX_GST_SLAB) {
      return { gstSlabs: true };
    }
  }
  return null;
}

/**
 * Admin company + GST settings page (Tabler light theme).
 *
 * <p>Loads the current settings from {@code GET /api/admin/settings} and saves
 * via {@code PUT /api/admin/settings}. The GST section has a clear "Enable GST on
 * invoices" toggle; when enabled, a valid 15-character GSTIN is required (inline
 * validation) — mirroring the backend rule. A helper note explains that enabling
 * GST switches per-order invoices to a GST Tax Invoice.
 *
 * <p>The company logo section previews the current logo, uploads a new one
 * (png/jpeg ≤1MB, validated client-side) and removes it. Success/error feedback
 * uses the shared admin toast service.
 */
@Component({
  selector: 'admin-settings',
  imports: [ReactiveFormsModule, PageHeaderComponent],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css',
})
export class SettingsComponent implements OnInit, OnDestroy {
  private readonly service = inject(SettingsService);
  private readonly statesService = inject(StatesService);
  private readonly fb = inject(FormBuilder);
  private readonly confirmService = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);

  // --- Category tabs ------------------------------------------------------
  /** The settings categories, split into tabs so the page isn't one long scroll. */
  protected readonly settingsTabs = [
    { key: 'gst', label: 'GST & Invoice', icon: 'ti-receipt-tax' },
    { key: 'company', label: 'Company', icon: 'ti-building-store' },
    { key: 'bank', label: 'Bank', icon: 'ti-building-bank' },
    { key: 'states', label: 'States', icon: 'ti-map-pin' },
  ] as const;

  /** Which settings category is currently shown. */
  protected readonly activeTab = signal<'gst' | 'company' | 'bank' | 'states'>('gst');

  /** Switch the visible settings category. */
  setTab(key: 'gst' | 'company' | 'bank' | 'states'): void {
    this.activeTab.set(key);
  }

  // --- Company logo state -------------------------------------------------
  /** Object URL for the current logo preview, or null when no logo is set. */
  protected readonly logoUrl = signal<string | null>(null);
  protected readonly hasLogo = signal(false);
  protected readonly logoBusy = signal(false);
  protected readonly logoError = signal<string | null>(null);

  /** The current object URL, tracked so it can be revoked to avoid leaks. */
  private currentObjectUrl: string | null = null;

  // --- Delivery states (order-entry typeahead master list) ----------------
  protected readonly states = signal<DeliveryState[]>([]);
  protected readonly statesLoading = signal(true);
  protected readonly statesError = signal<string | null>(null);
  protected readonly stateBusyId = signal<number | 'new' | null>(null);
  /** Free-text field for adding a new delivery state. */
  protected readonly newStateName = this.fb.nonNullable.control('', [
    Validators.required,
    Validators.maxLength(100),
  ]);

  protected readonly form = this.fb.nonNullable.group({
    gstEnabled: [false],
    gstin: ['', [Validators.maxLength(20)]],
    legalName: ['', [Validators.required, Validators.maxLength(200)]],
    addressLine: ['', [Validators.maxLength(250)]],
    city: ['', [Validators.maxLength(100)]],
    state: ['', [Validators.maxLength(100)]],
    stateCode: ['', [Validators.maxLength(4)]],
    gstRatePercent: ['5.00', [Validators.required, Validators.pattern(/^\d{1,3}(\.\d{1,2})?$/)]],
    pricesIncludeGst: [true],
    invoiceFooterNote: ['', [Validators.maxLength(500)]],
    contactPhone: ['', [Validators.maxLength(20)]],
    contactEmail: ['', [Validators.maxLength(120)]],
    // --- Wave 3: invoice & tax ---------------------------------------------
    invoiceNumberPrefix: ['', [Validators.maxLength(40)]],
    invoiceTerms: ['', [Validators.maxLength(2000)]],
    gstSlabs: ['', [Validators.maxLength(100), gstSlabsValidator]],
    // --- Wave 3: bank details ----------------------------------------------
    bankName: ['', [Validators.maxLength(120)]],
    bankAccountName: ['', [Validators.maxLength(120)]],
    bankAccountNumber: ['', [Validators.maxLength(40)]],
    bankIfsc: ['', [Validators.maxLength(20), Validators.pattern(IFSC_PATTERN)]],
    bankBranch: ['', [Validators.maxLength(120)]],
  });

  ngOnInit(): void {
    // GSTIN is required and exactly 15 characters only when GST is enabled.
    this.form.controls.gstEnabled.valueChanges.subscribe((enabled) => {
      this.applyGstinValidators(enabled);
    });
    this.load();
    this.loadStates();
  }

  ngOnDestroy(): void {
    this.revokeObjectUrl();
  }

  // --- Delivery states ----------------------------------------------------

  /** Loads the full delivery-state master list for the management table. */
  loadStates(): void {
    this.statesLoading.set(true);
    this.statesError.set(null);
    this.statesService.listAll().subscribe({
      next: (rows) => {
        this.states.set(rows);
        this.statesLoading.set(false);
      },
      error: () => {
        this.statesError.set('Could not load delivery states.');
        this.statesLoading.set(false);
      },
    });
  }

  /** Adds a new delivery state from the free-text field. */
  addState(): void {
    if (this.stateBusyId() !== null) {
      return;
    }
    if (this.newStateName.invalid) {
      this.newStateName.markAsTouched();
      return;
    }
    const name = this.newStateName.value.trim();
    if (!name) {
      return;
    }
    this.stateBusyId.set('new');
    this.statesService.create({ name }).subscribe({
      next: (created) => {
        this.stateBusyId.set(null);
        this.states.update((list) =>
          [...list, created].sort(
            (a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name),
          ),
        );
        this.newStateName.reset('');
        this.toasts.success(`Added "${created.name}".`);
      },
      error: (err: HttpErrorResponse) => {
        this.stateBusyId.set(null);
        this.toasts.error(this.describeError(err, 'Could not add the state.'));
      },
    });
  }

  /** Enables / disables a state (controls whether it appears in the order picker). */
  toggleState(state: DeliveryState): void {
    if (this.stateBusyId() !== null) {
      return;
    }
    this.stateBusyId.set(state.id);
    this.statesService
      .update(state.id, { name: state.name, active: !state.active, sortOrder: state.sortOrder })
      .subscribe({
        next: (updated) => {
          this.stateBusyId.set(null);
          this.states.update((list) => list.map((s) => (s.id === updated.id ? updated : s)));
        },
        error: (err: HttpErrorResponse) => {
          this.stateBusyId.set(null);
          this.toasts.error(this.describeError(err, 'Could not update the state.'));
        },
      });
  }

  /** Removes a state from the master list, after confirmation. */
  async removeState(state: DeliveryState): Promise<void> {
    if (this.stateBusyId() !== null) {
      return;
    }
    const confirmed = await this.confirmService.confirm({
      title: 'Remove state',
      message: `Remove "${state.name}" from the delivery-state list?`,
      confirmLabel: 'Remove',
      danger: true,
      icon: 'ti-trash',
    });
    if (!confirmed) {
      return;
    }
    this.stateBusyId.set(state.id);
    this.statesService.delete(state.id).subscribe({
      next: () => {
        this.stateBusyId.set(null);
        this.states.update((list) => list.filter((s) => s.id !== state.id));
        this.toasts.success(`Removed "${state.name}".`);
      },
      error: (err: HttpErrorResponse) => {
        this.stateBusyId.set(null);
        this.toasts.error(this.describeError(err, 'Could not remove the state.'));
      },
    });
  }

  private applyGstinValidators(gstEnabled: boolean): void {
    const gstin = this.form.controls.gstin;
    if (gstEnabled) {
      gstin.setValidators([
        Validators.required,
        Validators.minLength(15),
        Validators.maxLength(15),
      ]);
    } else {
      gstin.setValidators([Validators.maxLength(20)]);
    }
    gstin.updateValueAndValidity({ emitEvent: false });
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.get().subscribe({
      next: (s) => {
        this.form.reset({
          gstEnabled: s.gstEnabled,
          gstin: s.gstin ?? '',
          legalName: s.legalName ?? '',
          addressLine: s.addressLine ?? '',
          city: s.city ?? '',
          state: s.state ?? '',
          stateCode: s.stateCode ?? '',
          gstRatePercent: s.gstRatePercent ?? '5.00',
          pricesIncludeGst: s.pricesIncludeGst,
          invoiceFooterNote: s.invoiceFooterNote ?? '',
          contactPhone: s.contactPhone ?? '',
          contactEmail: s.contactEmail ?? '',
          invoiceNumberPrefix: s.invoiceNumberPrefix ?? '',
          invoiceTerms: s.invoiceTerms ?? '',
          gstSlabs: s.gstSlabs ?? '',
          bankName: s.bankName ?? '',
          bankAccountName: s.bankAccountName ?? '',
          bankAccountNumber: s.bankAccountNumber ?? '',
          bankIfsc: s.bankIfsc ?? '',
          bankBranch: s.bankBranch ?? '',
        });
        this.applyGstinValidators(s.gstEnabled);
        this.loading.set(false);
        this.applyLogoState(s);
      },
      error: () => {
        this.loadError.set('Could not load settings. Please try again.');
        this.loading.set(false);
      },
    });
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
    const request: AppSettings = {
      gstEnabled: raw.gstEnabled,
      gstin: raw.gstin.trim() || null,
      legalName: raw.legalName.trim(),
      addressLine: raw.addressLine.trim() || null,
      city: raw.city.trim() || null,
      state: raw.state.trim() || null,
      stateCode: raw.stateCode.trim() || null,
      gstRatePercent: raw.gstRatePercent.trim(),
      pricesIncludeGst: raw.pricesIncludeGst,
      invoiceFooterNote: raw.invoiceFooterNote.trim() || null,
      contactPhone: raw.contactPhone.trim() || null,
      contactEmail: raw.contactEmail.trim() || null,
      invoiceNumberPrefix: raw.invoiceNumberPrefix.trim() || null,
      invoiceTerms: raw.invoiceTerms.trim() || null,
      gstSlabs: raw.gstSlabs.trim() || null,
      bankName: raw.bankName.trim() || null,
      bankAccountName: raw.bankAccountName.trim() || null,
      bankAccountNumber: raw.bankAccountNumber.trim() || null,
      bankIfsc: raw.bankIfsc.trim().toUpperCase() || null,
      bankBranch: raw.bankBranch.trim() || null,
    };

    this.saving.set(true);
    this.formError.set(null);
    this.service.update(request).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.toasts.success('Settings saved.');
        this.applyGstinValidators(saved.gstEnabled);
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.formError.set(this.describeError(err));
      },
    });
  }

  // --- Company logo -------------------------------------------------------

  /** Handles a file chosen in the logo input: validate then upload. */
  onLogoSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    // Reset the input so re-selecting the same file re-triggers change.
    input.value = '';
    if (!file) {
      return;
    }
    this.logoError.set(null);
    if (!LOGO_ACCEPTED_TYPES.includes(file.type)) {
      this.logoError.set('Please choose a PNG or JPEG image.');
      return;
    }
    if (file.size > LOGO_MAX_BYTES) {
      this.logoError.set('The image is too large. Maximum size is 1MB.');
      return;
    }
    this.uploadLogo(file);
  }

  private uploadLogo(file: File): void {
    this.logoBusy.set(true);
    this.logoError.set(null);
    this.service.uploadLogo(file).subscribe({
      next: (saved) => {
        this.logoBusy.set(false);
        this.toasts.success('Logo updated.');
        this.applyLogoState(saved);
      },
      error: (err: HttpErrorResponse) => {
        this.logoBusy.set(false);
        const message = this.describeError(err, 'Could not upload the logo. Please try again.');
        this.logoError.set(message);
        this.toasts.error(message);
      },
    });
  }

  async removeLogo(): Promise<void> {
    if (this.logoBusy()) {
      return;
    }
    const confirmed = await this.confirmService.confirm({
      title: 'Remove logo',
      message: 'Remove the company logo? It will no longer appear on invoices and labels.',
      confirmLabel: 'Remove',
      danger: true,
      icon: 'ti-photo-off',
    });
    if (!confirmed) {
      return;
    }
    this.logoBusy.set(true);
    this.logoError.set(null);
    this.service.deleteLogo().subscribe({
      next: (saved) => {
        this.logoBusy.set(false);
        this.toasts.success('Logo removed.');
        this.applyLogoState(saved);
      },
      error: (err: HttpErrorResponse) => {
        this.logoBusy.set(false);
        const message = this.describeError(err, 'Could not remove the logo. Please try again.');
        this.logoError.set(message);
        this.toasts.error(message);
      },
    });
  }

  /** Reflects the presence of a logo from settings and (re)loads the preview. */
  private applyLogoState(settings: AppSettings): void {
    const present = !!settings.logoObjectKey;
    this.hasLogo.set(present);
    if (present) {
      this.loadLogoPreview();
    } else {
      this.revokeObjectUrl();
      this.logoUrl.set(null);
    }
  }

  private loadLogoPreview(): void {
    this.service.getLogo().subscribe({
      next: (blob) => {
        this.revokeObjectUrl();
        this.currentObjectUrl = URL.createObjectURL(blob);
        this.logoUrl.set(this.currentObjectUrl);
      },
      error: () => {
        // Keep hasLogo true (the settings say one exists) but drop the preview.
        this.revokeObjectUrl();
        this.logoUrl.set(null);
      },
    });
  }

  private revokeObjectUrl(): void {
    if (this.currentObjectUrl) {
      URL.revokeObjectURL(this.currentObjectUrl);
      this.currentObjectUrl = null;
    }
  }

  private describeError(err: HttpErrorResponse, fallback = 'Could not save settings. Please try again.'): string {
    const body = err.error as ApiError | undefined;
    if (body?.details?.length) {
      return body.details.join(' ');
    }
    return body?.message || fallback;
  }
}
