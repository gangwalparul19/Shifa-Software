import { Component, EventEmitter, Input, Output, computed, signal } from '@angular/core';
import { SortState } from 'core';

/**
 * A clickable, accessible sortable table header. Used as an attribute component
 * on a {@code <th>}:
 *
 * <pre>
 *   &lt;th adminSortHeader field="createdAt" [sort]="sort()" (sortChange)="onSort($event)"&gt;
 *     Date
 *   &lt;/th&gt;
 * </pre>
 *
 * <p>It renders the projected label plus an active/idle sort arrow, toggles the
 * direction when its field is the active one (else selects it ascending), and
 * exposes {@code aria-sort} for assistive tech. Only whitelist sortable columns
 * with this component so the backend never receives an unsupported sort field.
 */
@Component({
  selector: 'th[adminSortHeader]',
  standalone: true,
  host: {
    class: 'shifa-sortable',
    role: 'columnheader',
    '[attr.aria-sort]': 'ariaSort()',
    '[class.is-active]': 'active()',
    '(click)': 'emit()',
    '(keydown.enter)': 'emit()',
    '(keydown.space)': 'emit(); $event.preventDefault()',
    tabindex: '0',
  },
  template: `
    <span class="shifa-sortable__label"><ng-content /></span>
    <i
      class="ti shifa-sortable__icon"
      [class.ti-arrow-up]="dir() === 'asc'"
      [class.ti-arrow-down]="dir() === 'desc'"
      [class.ti-arrows-sort]="dir() === null"
      aria-hidden="true"
    ></i>
  `,
  styles: [
    `
      :host {
        cursor: pointer;
        user-select: none;
        white-space: nowrap;
      }
      :host:focus-visible {
        outline: 2px solid var(--shifa-green-600, #1f5d3f);
        outline-offset: -2px;
      }
      .shifa-sortable__icon {
        margin-left: 0.3rem;
        font-size: 0.85em;
        opacity: 0.35;
        vertical-align: middle;
        transition: opacity 0.15s ease;
      }
      :host(.is-active) .shifa-sortable__icon,
      :host:hover .shifa-sortable__icon {
        opacity: 0.9;
      }
    `,
  ],
})
export class SortableHeaderComponent {
  /** The backend sort field this column maps to (must be whitelisted). */
  @Input({ required: true }) field = '';

  /** The current table sort state. */
  @Input({ required: true })
  set sort(value: SortState) {
    this._sort.set(value);
  }
  private readonly _sort = signal<SortState>({ field: '', dir: 'asc' });

  /** Emits the field that was activated; the parent computes the next state. */
  @Output() sortChange = new EventEmitter<string>();

  protected readonly active = computed(() => this._sort().field === this.field);
  protected readonly dir = computed<'asc' | 'desc' | null>(() =>
    this._sort().field === this.field ? this._sort().dir : null,
  );

  protected ariaSort(): 'ascending' | 'descending' | 'none' {
    const d = this.dir();
    return d === 'asc' ? 'ascending' : d === 'desc' ? 'descending' : 'none';
  }

  protected emit(): void {
    this.sortChange.emit(this.field);
  }
}
