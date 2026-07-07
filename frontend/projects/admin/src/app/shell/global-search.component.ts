import {
  Component,
  ElementRef,
  HostListener,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { catchError, debounceTime, distinctUntilChanged, of, switchMap, tap } from 'rxjs';
import { toSignal } from '@angular/core/rxjs-interop';
import { GlobalSearchService, SearchResults } from './global-search.service';

const EMPTY_RESULTS: SearchResults = { orders: [], products: [], customers: [] };

/** A flattened result row used for keyboard navigation. */
interface FlatHit {
  kind: 'order' | 'product' | 'customer';
  primary: string;
  secondary: string;
  route: string;
  queryParams?: Record<string, string>;
}

/**
 * Global search for the admin top bar (Wave 2).
 *
 * <p>On desktop it renders an inline search input; on small screens it collapses
 * to an icon button that expands into a full-width overlay input. Typing (300ms
 * debounce) queries {@code GET /api/admin/search?q=} and shows a dropdown of
 * grouped results (Orders / Products / Customers). Each item deep-links to the
 * relevant page. Keyboard accessible: ArrowUp/ArrowDown move the active row,
 * Enter opens it, and Escape closes the panel. Clicking outside also closes it.
 */
@Component({
  selector: 'admin-global-search',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './global-search.component.html',
  styleUrl: './global-search.component.css',
})
export class GlobalSearchComponent {
  private readonly service = inject(GlobalSearchService);
  private readonly router = inject(Router);
  private readonly host = inject(ElementRef<HTMLElement>);

  private readonly inputEl = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  protected readonly query = new FormControl<string>('', { nonNullable: true });

  /** Whether the results panel is open. */
  protected readonly open = signal(false);
  /** Whether the mobile overlay input is expanded. */
  protected readonly expanded = signal(false);
  /** True while a request is in flight. */
  protected readonly loading = signal(false);
  /** Index of the keyboard-active row, or -1 for none. */
  protected readonly activeIndex = signal(-1);

  /** Latest results from the backend (empty until a query runs). */
  protected readonly results = toSignal(
    this.query.valueChanges.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      tap((term) => this.onQueryChange(term)),
      switchMap((term) => {
        const q = term.trim();
        if (q.length < 2) {
          return of(EMPTY_RESULTS);
        }
        return this.service.search(q).pipe(
          catchError(() => of(EMPTY_RESULTS)),
          tap(() => this.loading.set(false)),
        );
      }),
    ),
    { initialValue: EMPTY_RESULTS },
  );

  /** Flattened result rows in display order (for keyboard navigation). */
  protected readonly flatHits = computed<FlatHit[]>(() => {
    const r = this.results();
    const hits: FlatHit[] = [];
    for (const o of r.orders ?? []) {
      hits.push({
        kind: 'order',
        primary: o.orderCode,
        secondary: o.customerName,
        route: '/orders',
        queryParams: { q: o.orderCode },
      });
    }
    for (const p of r.products ?? []) {
      hits.push({ kind: 'product', primary: p.name, secondary: p.sku, route: '/products' });
    }
    for (const c of r.customers ?? []) {
      hits.push({
        kind: 'customer',
        primary: c.name,
        secondary: c.mobile,
        route: '/orders',
        queryParams: { q: c.mobile },
      });
    }
    return hits;
  });

  protected readonly hasResults = computed(() => this.flatHits().length > 0);
  protected readonly showNoResults = computed(
    () => !this.loading() && this.query.value.trim().length >= 2 && !this.hasResults(),
  );

  /** React to a new query value: manage loading / open / active-row state. */
  private onQueryChange(term: string): void {
    const q = term.trim();
    if (q.length < 2) {
      this.loading.set(false);
      this.open.set(false);
      return;
    }
    this.loading.set(true);
    this.open.set(true);
    this.activeIndex.set(-1);
  }

  /** Grouped result accessors used by the template. */
  protected readonly orders = computed(() => this.results().orders ?? []);
  protected readonly products = computed(() => this.results().products ?? []);
  protected readonly customers = computed(() => this.results().customers ?? []);

  /** Index of a group's first row within the flat list, for active highlighting. */
  flatIndexOf(kind: FlatHit['kind'], within: number): number {
    let idx = 0;
    for (const h of this.flatHits()) {
      if (h.kind === kind && within-- === 0) {
        return idx;
      }
      idx++;
    }
    return -1;
  }

  openMobile(): void {
    this.expanded.set(true);
    queueMicrotask(() => this.inputEl()?.nativeElement.focus());
  }

  onFocus(): void {
    if (this.hasResults()) {
      this.open.set(true);
    }
  }

  select(hit: FlatHit): void {
    void this.router.navigate([hit.route], { queryParams: hit.queryParams ?? {} });
    this.close();
  }

  selectAt(index: number): void {
    const hit = this.flatHits()[index];
    if (hit) {
      this.select(hit);
    }
  }

  close(): void {
    this.open.set(false);
    this.expanded.set(false);
    this.activeIndex.set(-1);
    this.query.setValue('', { emitEvent: false });
  }

  // --- Keyboard -----------------------------------------------------------

  @HostListener('keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      this.close();
      return;
    }
    if (!this.open()) {
      return;
    }
    const count = this.flatHits().length;
    if (count === 0) {
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.activeIndex.update((i) => (i + 1) % count);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.activeIndex.update((i) => (i <= 0 ? count - 1 : i - 1));
    } else if (event.key === 'Enter') {
      const i = this.activeIndex();
      if (i >= 0) {
        event.preventDefault();
        this.selectAt(i);
      }
    }
  }

  /** Close when clicking anywhere outside this component. */
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
      this.expanded.set(false);
    }
  }
}
