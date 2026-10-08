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
import { AuthService, Role } from 'core';
import { GlobalSearchService, SearchResults } from './global-search.service';

const EMPTY_RESULTS: SearchResults = { orders: [], products: [], customers: [] };

/** A flattened result row used for keyboard navigation. */
interface FlatHit {
  kind: 'action' | 'order' | 'product' | 'customer';
  primary: string;
  secondary: string;
  route: string;
  icon?: string;
  queryParams?: Record<string, string>;
}

/** A role-aware quick action (navigate to a key page / start a workflow). */
interface QuickAction {
  label: string;
  hint: string;
  icon: string;
  route: string;
  queryParams?: Record<string, string>;
  roles: Role[];
}

/**
 * Role-aware quick actions shown at the top of the palette. Each is visible only
 * to the roles that can use it (matching the route guards), so a salesperson
 * sees "New order"/"My day" while an admin sees the approval queue, etc.
 */
const QUICK_ACTIONS: QuickAction[] = [
  { label: 'New order', hint: 'Create a new order', icon: 'ti ti-plus', route: '/orders/new',
    roles: [Role.ADMIN, Role.SALESPERSON, Role.TEAM_LEAD] },
  { label: 'Approval queue', hint: 'Orders awaiting approval', icon: 'ti ti-checkbox', route: '/approvals',
    roles: [Role.ADMIN] },
  { label: 'Orders', hint: 'All orders', icon: 'ti ti-receipt', route: '/orders',
    roles: [Role.ADMIN, Role.ACCOUNTANT, Role.SALESPERSON, Role.TEAM_LEAD, Role.CA] },
  { label: 'My day', hint: 'Today, targets & follow-ups', icon: 'ti ti-sun', route: '/dashboard',
    roles: [Role.SALESPERSON] },
  { label: 'My leads', hint: 'Lead pipeline', icon: 'ti ti-user-plus', route: '/leads',
    roles: [Role.ADMIN, Role.SALESPERSON] },
  { label: 'Payments to verify', hint: 'Payment verification queue', icon: 'ti ti-shield-check', route: '/payments',
    roles: [Role.ADMIN, Role.PAYMENT_VERIFIER] },
  { label: 'Packing', hint: 'Packing & scan', icon: 'ti ti-package', route: '/packing',
    roles: [Role.ADMIN, Role.PACKING_USER] },
  { label: 'Team performance', hint: 'Team KPIs & leaderboard', icon: 'ti ti-trophy', route: '/team-performance',
    roles: [Role.ADMIN, Role.TEAM_LEAD] },
  { label: 'GST & accounting', hint: 'GST dashboard', icon: 'ti ti-file-invoice', route: '/ca/gst',
    roles: [Role.ADMIN, Role.CA] },
  { label: 'Reports', hint: 'Sales & money reports', icon: 'ti ti-chart-bar', route: '/reports',
    roles: [Role.ADMIN, Role.ACCOUNTANT, Role.SALESPERSON, Role.CA] },
  { label: 'Reconciliation', hint: 'COD reconciliation', icon: 'ti ti-cash', route: '/reconciliation',
    roles: [Role.ADMIN, Role.ACCOUNTANT] },
];

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
  private readonly auth = inject(AuthService);
  private readonly host = inject(ElementRef<HTMLElement>);

  /** The quick actions visible to the current user's role. */
  private readonly myActions = QUICK_ACTIONS.filter((a) => this.auth.hasAnyRole(...a.roles));

  /** The current (debounced) query term, lower-cased + trimmed, as a signal. */
  private readonly term = signal('');

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
    for (const a of this.quickActions()) {
      hits.push({
        kind: 'action',
        primary: a.label,
        secondary: a.hint,
        route: a.route,
        icon: a.icon,
        queryParams: a.queryParams,
      });
    }
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
  /** Only "no matches" when a real (>=2 char) search returned nothing AND no action matched. */
  protected readonly showNoResults = computed(
    () => !this.loading() && this.query.value.trim().length >= 2 && !this.hasResults(),
  );

  /** React to a new query value: manage loading / open / active-row state. */
  private onQueryChange(term: string): void {
    const q = term.trim();
    this.term.set(q.toLowerCase());
    this.activeIndex.set(-1);
    if (q.length < 2) {
      // Short/empty query: no backend hit, but keep the panel open to show
      // the role's quick actions (filtered by whatever was typed).
      this.loading.set(false);
      this.open.set(true);
      return;
    }
    this.loading.set(true);
    this.open.set(true);
  }

  /** Role-aware quick actions, filtered by the typed term (empty term = all). */
  protected readonly quickActions = computed<QuickAction[]>(() => {
    const t = this.term();
    if (!t) {
      return this.myActions;
    }
    return this.myActions.filter(
      (a) => a.label.toLowerCase().includes(t) || a.hint.toLowerCase().includes(t),
    );
  });

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

  /** Focuses the search field from anywhere (Ctrl/Cmd+K global shortcut). */
  focusSearch(): void {
    this.expanded.set(true);
    queueMicrotask(() => this.inputEl()?.nativeElement.focus());
  }

  onFocus(): void {
    // Opening the field shows the quick actions even before anything is typed.
    this.term.set(this.query.value.trim().toLowerCase());
    this.open.set(true);
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
    this.term.set('');
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
