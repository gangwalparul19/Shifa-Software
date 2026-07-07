import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AccountApi, AddressInput, CustomerAddress } from './account-api.service';

/**
 * Saved-addresses management (Phase B): list, add, edit, delete, and set the
 * default delivery address. All calls are scoped server-side to the signed-in
 * customer. The default address is what checkout prefills.
 */
@Component({
  selector: 'sf-account-addresses',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './account-addresses.component.html',
  styleUrl: './account.css',
})
export class AccountAddressesComponent {
  private readonly fb = inject(FormBuilder);
  private readonly accountApi = inject(AccountApi);

  protected readonly addresses = signal<CustomerAddress[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly showForm = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly saving = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    label: [''],
    fullName: ['', [Validators.required, Validators.maxLength(100)]],
    mobile: ['', [Validators.required, Validators.pattern(/^\d{10}$/)]],
    addressLine: ['', [Validators.required, Validators.maxLength(250)]],
    city: ['', [Validators.required, Validators.maxLength(100)]],
    state: ['', [Validators.required, Validators.maxLength(100)]],
    postalCode: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
    makeDefault: [false],
  });

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.accountApi.listAddresses().subscribe({
      next: (addresses) => {
        this.addresses.set(addresses);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('We could not load your addresses.');
        this.loading.set(false);
      },
    });
  }

  showError(control: keyof typeof this.form.controls): boolean {
    const c = this.form.controls[control];
    return c.invalid && c.touched;
  }

  startAdd(): void {
    this.editingId.set(null);
    this.form.reset({ makeDefault: this.addresses().length === 0 });
    this.showForm.set(true);
  }

  startEdit(address: CustomerAddress): void {
    this.editingId.set(address.id);
    this.form.reset({
      label: address.label ?? '',
      fullName: address.fullName,
      mobile: address.mobile,
      addressLine: address.addressLine,
      city: address.city,
      state: address.state,
      postalCode: address.postalCode,
      makeDefault: address.isDefault,
    });
    this.showForm.set(true);
  }

  cancel(): void {
    this.showForm.set(false);
    this.editingId.set(null);
  }

  save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const values = this.form.getRawValue();
    const input: AddressInput = {
      label: values.label.trim() || undefined,
      fullName: values.fullName.trim(),
      mobile: values.mobile.trim(),
      addressLine: values.addressLine.trim(),
      city: values.city.trim(),
      state: values.state.trim(),
      postalCode: values.postalCode.trim(),
      makeDefault: values.makeDefault,
    };
    this.saving.set(true);
    const id = this.editingId();
    const request$ = id
      ? this.accountApi.updateAddress(id, input)
      : this.accountApi.createAddress(input);
    request$.subscribe({
      next: () => {
        this.saving.set(false);
        this.showForm.set(false);
        this.editingId.set(null);
        this.load();
      },
      error: () => {
        this.saving.set(false);
        this.error.set('We could not save that address.');
      },
    });
  }

  setDefault(id: number): void {
    this.accountApi.setDefaultAddress(id).subscribe({
      next: () => this.load(),
      error: () => this.error.set('We could not update the default address.'),
    });
  }

  remove(id: number): void {
    this.accountApi.deleteAddress(id).subscribe({
      next: () => this.load(),
      error: () => this.error.set('We could not delete that address.'),
    });
  }
}
