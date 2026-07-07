import { Component, EventEmitter, Input, Output } from '@angular/core';

/**
 * A small, reusable pagination control for the admin's server-side paged tables
 * (orders, products, reconciliation). Renders "Prev / Page X of N / Next", the
 * total row count, and an optional page-size selector.
 *
 * <pre>
 *   &lt;admin-pagination
 *     [page]="page()" [totalPages]="totalPages()" [totalElements]="total()"
 *     [size]="size()" (pageChange)="goToPage($event)" (sizeChange)="setSize($event)" /&gt;
 * </pre>
 *
 * <p>Pages are zero-based on the wire but shown 1-based to the user. The control
 * is presentation-only; the parent owns the data fetch and state.
 */
@Component({
  selector: 'admin-pagination',
  standalone: true,
  template: `
    @if (totalElements > 0) {
      <div class="shifa-pager">
        <div class="shifa-pager__info text-secondary">
          {{ rangeStart() }}–{{ rangeEnd() }} of {{ totalElements }}
        </div>

        <div class="shifa-pager__controls">
          @if (showSize) {
            <div class="shifa-pager__size">
              <label class="text-secondary" [attr.for]="selectId">Rows</label>
              <select
                class="form-select form-select-sm"
                [id]="selectId"
                [value]="size"
                (change)="onSize($any($event.target).value)"
                aria-label="Rows per page"
              >
                @for (opt of sizeOptions; track opt) {
                  <option [value]="opt">{{ opt }}</option>
                }
              </select>
            </div>
          }

          <div class="btn-group btn-group-sm" role="group" aria-label="Pagination">
            <button
              type="button"
              class="btn btn-outline-secondary"
              (click)="pageChange.emit(page - 1)"
              [disabled]="page <= 0"
              aria-label="Previous page"
            >
              <i class="ti ti-chevron-left" aria-hidden="true"></i>
              <span class="d-none d-sm-inline ms-1">Prev</span>
            </button>
            <span class="btn btn-outline-secondary disabled shifa-pager__page" aria-current="page">
              Page {{ page + 1 }} of {{ Math.max(totalPages, 1) }}
            </span>
            <button
              type="button"
              class="btn btn-outline-secondary"
              (click)="pageChange.emit(page + 1)"
              [disabled]="page >= totalPages - 1"
              aria-label="Next page"
            >
              <span class="d-none d-sm-inline me-1">Next</span>
              <i class="ti ti-chevron-right" aria-hidden="true"></i>
            </button>
          </div>
        </div>
      </div>
    }
  `,
  styles: [
    `
      .shifa-pager {
        display: flex;
        align-items: center;
        justify-content: space-between;
        flex-wrap: wrap;
        gap: 0.5rem 1rem;
        padding: 0.75rem 1rem;
      }
      .shifa-pager__info {
        font-size: 0.85rem;
      }
      .shifa-pager__controls {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        flex-wrap: wrap;
      }
      .shifa-pager__size {
        display: flex;
        align-items: center;
        gap: 0.4rem;
        font-size: 0.85rem;
      }
      .shifa-pager__size .form-select {
        width: auto;
      }
      .shifa-pager__page {
        pointer-events: none;
        font-variant-numeric: tabular-nums;
      }
      @media (max-width: 575.98px) {
        .shifa-pager {
          justify-content: center;
        }
      }
    `,
  ],
})
export class PaginationComponent {
  /** Zero-based current page. */
  @Input() page = 0;
  /** Total number of pages. */
  @Input() totalPages = 0;
  /** Total number of rows across all pages. */
  @Input() totalElements = 0;
  /** Current page size (rows per page). */
  @Input() size = 20;

  /** Whether to show the rows-per-page selector. */
  @Input() showSize = true;
  /** Page-size options offered in the selector. */
  @Input() sizeOptions = [10, 20, 50, 100];

  /** Emits the requested zero-based page. */
  @Output() pageChange = new EventEmitter<number>();
  /** Emits the newly chosen page size. */
  @Output() sizeChange = new EventEmitter<number>();

  private static seq = 0;
  protected readonly Math = Math;
  protected readonly selectId = `pager-size-${++PaginationComponent.seq}`;

  protected rangeStart(): number {
    return this.totalElements === 0 ? 0 : this.page * this.size + 1;
  }
  protected rangeEnd(): number {
    return Math.min((this.page + 1) * this.size, this.totalElements);
  }

  protected onSize(value: string): void {
    this.sizeChange.emit(Number(value));
  }
}
