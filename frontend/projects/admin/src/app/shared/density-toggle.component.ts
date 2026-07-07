import { Component, inject } from '@angular/core';
import { TableDensityService } from './table-density.service';

/**
 * A small comfortable/compact segmented control for data-table pages. Reflects
 * and updates the shared {@link TableDensityService}; the choice is persisted
 * and applied globally. Icon-only buttons carry accessible labels and a pressed
 * state for assistive tech.
 */
@Component({
  selector: 'admin-density-toggle',
  standalone: true,
  template: `
    <div
      class="btn-group btn-group-sm shifa-density"
      role="group"
      aria-label="Table density"
    >
      <button
        type="button"
        class="btn"
        [class.btn-primary]="!density.compact()"
        [class.active]="!density.compact()"
        [attr.aria-pressed]="!density.compact()"
        (click)="density.set('comfortable')"
        title="Comfortable rows"
      >
        <i class="ti ti-baseline-density-medium" aria-hidden="true"></i>
        <span class="visually-hidden">Comfortable rows</span>
      </button>
      <button
        type="button"
        class="btn"
        [class.btn-primary]="density.compact()"
        [class.active]="density.compact()"
        [attr.aria-pressed]="density.compact()"
        (click)="density.set('compact')"
        title="Compact rows"
      >
        <i class="ti ti-baseline-density-small" aria-hidden="true"></i>
        <span class="visually-hidden">Compact rows</span>
      </button>
    </div>
  `,
})
export class DensityToggleComponent {
  protected readonly density = inject(TableDensityService);
}
