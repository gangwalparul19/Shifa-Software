import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import { ConfirmService } from '../shared/confirm.service';
import { renderTemplate } from '../shared/whatsapp.util';
import {
  WhatsappTemplate,
  WhatsappTemplatesService,
} from './whatsapp-templates.service';

/**
 * Manage customizable WhatsApp message templates (V44). ADMIN / ACCOUNTANT /
 * TEAM_LEAD can add, edit, enable/disable, reorder and delete the one-tap
 * quick-message templates shown on the order and customer screens.
 *
 * <p>Bodies may use {placeholder} tokens ({@code {name}}, {@code {orderCode}},
 * {@code {total}}, {@code {remaining}}, {@code {brand}}) which are substituted
 * against the order/customer in context when a salesperson taps the message.
 */
@Component({
  selector: 'admin-whatsapp-templates',
  imports: [ReactiveFormsModule, PageHeaderComponent, StatePanelComponent],
  template: `
    <admin-page-header
      title="WhatsApp templates"
      subtitle="Customize the one-tap messages staff send from the order and customer screens."
    >
      <button type="button" class="btn btn-primary" (click)="openCreate()">
        <i class="ti ti-plus me-1"></i> New template
      </button>
    </admin-page-header>

    @if (loading()) {
      <admin-state-panel variant="loading" skeleton="cards" loadingLabel="Loading templates…" />
    } @else if (loadError()) {
      <admin-state-panel variant="error" [title]="loadError()!" (retry)="load()" />
    } @else {
      <!-- Placeholder hint -->
      <div class="alert alert-info d-flex align-items-start gap-2 mb-3">
        <i class="ti ti-info-circle mt-1"></i>
        <div class="small">
          Use these placeholders in a message body — they're filled in automatically:
          <div class="mt-1 d-flex flex-wrap gap-1">
            @for (p of placeholders; track p) {
              <code class="shifa-wa-token" role="button" title="Insert" (click)="insertToken(p)">{{ '{' + p + '}' }}</code>
            }
          </div>
        </div>
      </div>

      @if (items().length === 0) {
        <admin-state-panel
          variant="empty"
          title="No templates yet"
          message="Add your first WhatsApp template to give staff a one-tap message."
        />
      } @else {
        <div class="row row-cards g-2 g-md-3">
          @for (t of items(); track t.id) {
            <div class="col-12 col-md-6 col-xl-4">
              <div class="card h-100" [class.opacity-75]="!t.active">
                <div class="card-body d-flex flex-column">
                  <div class="d-flex align-items-center gap-2 mb-1">
                    <i class="ti {{ t.icon }}"></i>
                    <span class="fw-semibold flex-fill text-truncate">{{ t.title }}</span>
                    <span class="badge" [class.bg-green-lt]="t.active" [class.bg-secondary-lt]="!t.active">
                      {{ t.active ? 'Active' : 'Hidden' }}
                    </span>
                  </div>
                  <p class="text-secondary small mb-2 shifa-wa-body">{{ t.body }}</p>
                  <div class="mt-auto d-flex gap-2">
                    <button type="button" class="btn btn-sm btn-outline-primary" (click)="openEdit(t)">
                      <i class="ti ti-edit me-1"></i> Edit
                    </button>
                    <button type="button" class="btn btn-sm btn-outline-secondary" (click)="toggle(t)">
                      <i class="ti {{ t.active ? 'ti-eye-off' : 'ti-eye' }} me-1"></i>
                      {{ t.active ? 'Hide' : 'Show' }}
                    </button>
                    <button type="button" class="btn btn-sm btn-outline-danger ms-auto" (click)="remove(t)" aria-label="Delete">
                      <i class="ti ti-trash"></i>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          }
        </div>
      }
    }

    <!-- Add / edit drawer -->
    @if (formOpen()) {
      <div class="shifa-backdrop" (click)="closeForm()"></div>
      <div class="offcanvas offcanvas-end show shifa-drawer" tabindex="-1">
        <div class="offcanvas-header">
          <h2 class="offcanvas-title">{{ editing() ? 'Edit template' : 'New template' }}</h2>
          <button type="button" class="btn-close" (click)="closeForm()" aria-label="Close"></button>
        </div>
        <div class="offcanvas-body">
          <form [formGroup]="form" (ngSubmit)="save()">
            <div class="mb-3">
              <label class="form-label required">Title</label>
              <input type="text" class="form-control" formControlName="title" placeholder="e.g. Confirm order" />
              <div class="form-hint">Short label shown on the quick-message button.</div>
            </div>

            <div class="mb-3">
              <label class="form-label">Icon</label>
              <div class="input-group">
                <span class="input-group-text"><i class="ti {{ form.controls.icon.value || 'ti-message-dots' }}"></i></span>
                <input type="text" class="form-control" formControlName="icon" placeholder="ti-message-dots" />
              </div>
              <div class="form-hint">A Tabler icon name (optional).</div>
            </div>

            <div class="mb-2">
              <label class="form-label required">Message</label>
              <textarea #bodyRef class="form-control" rows="5" formControlName="body"
                placeholder="Hi {name}, thank you for your order {orderCode}…"></textarea>
            </div>
            <div class="mb-3 d-flex flex-wrap gap-1">
              @for (p of placeholders; track p) {
                <code class="shifa-wa-token" role="button" title="Insert" (click)="insertToken(p, bodyRef)">{{ '{' + p + '}' }}</code>
              }
            </div>

            <!-- Live preview -->
            <div class="mb-3">
              <label class="form-label">Preview</label>
              <div class="shifa-wa-preview">{{ preview() }}</div>
            </div>

            <div class="row g-2 mb-3">
              <div class="col-6">
                <label class="form-label">Order</label>
                <input type="number" class="form-control" formControlName="sortOrder" min="0" />
                <div class="form-hint">Lower shows first.</div>
              </div>
              <div class="col-6 d-flex align-items-center">
                <label class="form-check form-switch mt-4">
                  <input class="form-check-input" type="checkbox" formControlName="active" />
                  <span class="form-check-label">Active</span>
                </label>
              </div>
            </div>

            <div class="d-flex gap-2">
              <button type="submit" class="btn btn-primary" [disabled]="saving()">
                <i class="ti ti-device-floppy me-1"></i> {{ editing() ? 'Save changes' : 'Create' }}
              </button>
              <button type="button" class="btn btn-link" (click)="closeForm()">Cancel</button>
            </div>
          </form>
        </div>
      </div>
    }
  `,
  styles: [
    `
      .shifa-wa-token {
        background: #e6f4ea;
        color: #1f5d3f;
        border-radius: 6px;
        padding: 1px 6px;
        cursor: pointer;
        font-size: 0.8rem;
      }
      .shifa-wa-body {
        white-space: pre-wrap;
        display: -webkit-box;
        -webkit-line-clamp: 4;
        -webkit-box-orient: vertical;
        overflow: hidden;
      }
      .shifa-wa-preview {
        background: #f6f8f7;
        border: 1px dashed #cbd5cf;
        border-radius: 8px;
        padding: 10px 12px;
        white-space: pre-wrap;
        min-height: 3rem;
        font-size: 0.9rem;
      }
      .shifa-drawer {
        width: min(460px, 100vw);
      }
      .shifa-backdrop {
        position: fixed;
        inset: 0;
        background: rgba(0, 0, 0, 0.4);
        z-index: 1040;
      }
    `,
  ],
})
export class WhatsappTemplatesComponent implements OnInit {
  private readonly service = inject(WhatsappTemplatesService);
  private readonly fb = inject(FormBuilder);
  private readonly toasts = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly items = signal<WhatsappTemplate[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly formOpen = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly editing = computed(() => this.editingId() != null);

  /** Placeholder tokens offered as insertable chips. */
  protected readonly placeholders = ['name', 'customerName', 'orderCode', 'total', 'remaining', 'brand'];

  protected readonly form = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(120)]],
    icon: ['ti-message-dots', [Validators.maxLength(40)]],
    body: ['', [Validators.required, Validators.maxLength(2000)]],
    active: [true],
    sortOrder: [0],
  });

  /** Live preview of the current body against sample order/customer context. */
  protected readonly bodyValue = signal('');
  protected readonly preview = computed(() =>
    renderTemplate(this.bodyValue(), {
      customerName: 'Rahul Sharma',
      orderCode: 'SHR-1024',
      total: 1499,
      remaining: 499,
    }),
  );

  ngOnInit(): void {
    this.load();
    this.form.controls.body.valueChanges.subscribe((v) => this.bodyValue.set(v ?? ''));
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.all().subscribe({
      next: (rows) => {
        this.items.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load templates. Please try again.');
        this.loading.set(false);
      },
    });
  }

  openCreate(): void {
    this.editingId.set(null);
    const nextOrder = this.items().reduce((m, t) => Math.max(m, t.sortOrder), 0) + 1;
    this.form.reset({ title: '', icon: 'ti-message-dots', body: '', active: true, sortOrder: nextOrder });
    this.bodyValue.set('');
    this.formOpen.set(true);
  }

  openEdit(t: WhatsappTemplate): void {
    this.editingId.set(t.id);
    this.form.reset({
      title: t.title,
      icon: t.icon,
      body: t.body,
      active: t.active,
      sortOrder: t.sortOrder,
    });
    this.bodyValue.set(t.body);
    this.formOpen.set(true);
  }

  closeForm(): void {
    this.formOpen.set(false);
  }

  /** Inserts a {token} into the body (at the end, or replacing the selection). */
  insertToken(token: string, textarea?: HTMLTextAreaElement): void {
    const chip = `{${token}}`;
    const current = this.form.controls.body.value ?? '';
    if (textarea && typeof textarea.selectionStart === 'number') {
      const start = textarea.selectionStart;
      const end = textarea.selectionEnd ?? start;
      const next = current.slice(0, start) + chip + current.slice(end);
      this.form.controls.body.setValue(next);
      queueMicrotask(() => {
        textarea.focus();
        const pos = start + chip.length;
        textarea.setSelectionRange(pos, pos);
      });
    } else {
      this.form.controls.body.setValue((current ? current + ' ' : '') + chip);
    }
  }

  save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    const v = this.form.getRawValue();
    const payload = {
      title: v.title.trim(),
      body: v.body.trim(),
      icon: v.icon?.trim() || 'ti-message-dots',
      active: v.active,
      sortOrder: v.sortOrder ?? 0,
    };
    const id = this.editingId();
    const req = id != null ? this.service.update(id, payload) : this.service.create(payload);
    req.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.formOpen.set(false);
        if (id != null) {
          this.items.update((list) => list.map((t) => (t.id === id ? saved : t)));
          this.toasts.success('Template updated');
        } else {
          this.items.update((list) => [...list, saved].sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id));
          this.toasts.success('Template created');
        }
      },
      error: () => {
        this.saving.set(false);
        this.toasts.error('Could not save the template.');
      },
    });
  }

  toggle(t: WhatsappTemplate): void {
    this.service
      .update(t.id, { title: t.title, body: t.body, icon: t.icon, active: !t.active, sortOrder: t.sortOrder })
      .subscribe({
        next: (saved) => this.items.update((list) => list.map((x) => (x.id === t.id ? saved : x))),
        error: () => this.toasts.error('Could not update the template.'),
      });
  }

  async remove(t: WhatsappTemplate): Promise<void> {
    const ok = await this.confirm.confirm({
      title: 'Delete template',
      message: `Delete the "${t.title}" template for everyone?`,
      confirmLabel: 'Delete',
      icon: 'ti-trash',
      danger: true,
    });
    if (!ok) {
      return;
    }
    this.service.delete(t.id).subscribe({
      next: () => {
        this.items.update((list) => list.filter((x) => x.id !== t.id));
        this.toasts.success('Template deleted');
      },
      error: () => this.toasts.error('Could not delete the template.'),
    });
  }
}
