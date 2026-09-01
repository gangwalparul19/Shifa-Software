import { Component, HostListener, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { AuthService, Role } from 'core';
import { AdminEventsService } from '../dashboard/admin-events.service';
import { routeFade } from '../shared/animations';
import { ConfirmService } from '../shared/confirm.service';
import { PwaService } from '../shared/pwa.service';
import { AnnouncementsService } from '../announcements/announcements.service';
import {
  Announcement,
  announcementAlertClass,
  announcementIcon,
} from '../announcements/announcements.model';
import { ToastsComponent } from '../shared/toasts.component';
import { GlobalSearchComponent } from './global-search.component';
import { NotificationBellComponent } from '../notifications/notification-bell.component';

/** A single navigable link (either standalone or a child inside a group). */
interface NavLink {
  kind: 'link';
  label: string;
  path: string;
  icon: string;
  disabled?: boolean;
  /** When true, the item is only shown to ADMIN users (Req 5.4). */
  adminOnly?: boolean;
  /**
   * When set, the item is shown only to users whose role is in this list
   * (evaluated in addition to {@link adminOnly}). Lets non-admin roles such as
   * SALESPERSON see specific entries (e.g. New Order, Req 7) without widening
   * the admin-only default.
   */
  roles?: Role[];
}

/** A collapsible group of related links rendered as a labelled section in the
 *  hamburger drawer. Group visibility follows its children's roles. */
interface NavGroup {
  kind: 'group';
  label: string;
  icon: string;
  children: NavLink[];
}

/** A top-level navigation entry: either a standalone link or a group. */
type NavEntry = NavLink | NavGroup;

/** One destination on the persistent bottom tab bar (Req 2). */
interface BottomTab {
  label: string;
  path: string;
  icon: string;
}

/**
 * Authenticated admin chrome, rebuilt mobile-first on the Tabler design system.
 * The shell is three pieces (Req 1):
 *
 * <ul>
 *   <li><b>Top app bar</b> — a hamburger trigger (left), the current screen
 *       title, and the account/notification cluster (global search, live SSE
 *       pill, per-user notification bell, user dropdown + sign out) on the
 *       right.</li>
 *   <li><b>Bottom tab bar</b> — a persistent, role-aware bar carrying exactly
 *       four most-used destinations for the signed-in user's role (Req 2).</li>
 *   <li><b>Hamburger menu</b> — an Angular-driven off-canvas drawer (overlay +
 *       Escape-close) listing the full navigation the role can access, grouped,
 *       including the Shifa Dashboard and lower-frequency admin pages
 *       (Req 3).</li>
 * </ul>
 *
 * <p>All behaviour is signal/Angular-driven (no Bootstrap JS dependency) to stay
 * robust and CSP-friendly. Data wiring (auth session, SSE feed) is unchanged and
 * every existing destination remains reachable via the bottom bar or the drawer
 * (Req 13).
 */
@Component({
  selector: 'admin-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    ToastsComponent,
    GlobalSearchComponent,
    NotificationBellComponent,
  ],
  templateUrl: './admin-shell.component.html',
  styleUrl: './admin-shell.component.css',
  animations: [routeFade],
})
export class AdminShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly events = inject(AdminEventsService);
  protected readonly pwa = inject(PwaService);
  private readonly announcementsService = inject(AnnouncementsService);
  private readonly router = inject(Router);
  private readonly confirm = inject(ConfirmService);

  // --- Staff announcement banners (FEATURE-ROADMAP §8.4) ------------------
  private static readonly DISMISSED_KEY = 'shifa.dismissedAnnouncements.v1';
  private readonly announcements = signal<Announcement[]>([]);
  private readonly dismissed = signal<Set<number>>(this.loadDismissed());
  /** Active announcements the current user has not dismissed. */
  protected readonly visibleAnnouncements = computed(() =>
    this.announcements().filter((a) => !this.dismissed().has(a.id)),
  );
  protected readonly annAlertClass = announcementAlertClass;
  protected readonly annIcon = announcementIcon;

  // --- Install app help (FEATURE-ROADMAP §8.1) ---------------------------
  /** Whether the "how to install" instructions overlay is open. */
  protected readonly installHelpOpen = signal(false);

  /**
   * Install action: fire the native prompt when the browser has offered one,
   * otherwise show platform-specific instructions (iOS Safari never fires the
   * prompt, and it only appears on HTTPS with the service worker active).
   */
  installApp(): void {
    if (this.pwa.installable()) {
      void this.pwa.promptInstall();
    } else {
      this.installHelpOpen.set(true);
    }
  }

  closeInstallHelp(): void {
    this.installHelpOpen.set(false);
  }

  constructor() {
    // The shell only mounts for authenticated staff (staffGuard), so it is safe
    // to fetch the active announcements immediately for the banner.
    if (this.auth.session()) {
      this.announcementsService.active().subscribe({
        next: (rows) => this.announcements.set(rows),
        error: () => {
          /* non-fatal — no banner */
        },
      });
    }

    // Keep the desktop sidebar's active section expanded: whenever the route
    // changes, ensure the group that owns the current page is open (other groups
    // stay as the user left them). Depends only on the URL (untracked writes).
    effect(() => {
      const url = this.currentUrl().split('?')[0];
      const label = this.groupLabelForUrl(url);
      if (!label) {
        return;
      }
      untracked(() => {
        if (!this.sidebarOpenGroups().has(label)) {
          const next = new Set(this.sidebarOpenGroups());
          next.add(label);
          this.sidebarOpenGroups.set(next);
        }
      });
    });
  }

  /** Dismisses an announcement banner for this user (remembered locally). */
  dismissAnnouncement(id: number): void {
    const next = new Set(this.dismissed());
    next.add(id);
    this.dismissed.set(next);
    try {
      localStorage.setItem(AdminShellComponent.DISMISSED_KEY, JSON.stringify([...next]));
    } catch {
      /* storage unavailable — non-fatal */
    }
  }

  private loadDismissed(): Set<number> {
    try {
      const raw = localStorage.getItem(AdminShellComponent.DISMISSED_KEY);
      return raw ? new Set<number>(JSON.parse(raw) as number[]) : new Set<number>();
    } catch {
      return new Set<number>();
    }
  }

  /** Whether the off-canvas hamburger navigation drawer is open. */
  protected readonly menuOpen = signal(false);
  /** Whether the top-bar user dropdown is open. */
  protected readonly userMenuOpen = signal(false);

  /** Nav groups currently expanded in the drawer (all collapsed by default). */
  private readonly openGroups = signal<Set<string>>(new Set());

  /** Whether a nav group is expanded. */
  isGroupOpen(label: string): boolean {
    return this.openGroups().has(label);
  }

  /** Expands/collapses a nav group (accordion-style, collapsed by default). */
  toggleGroup(label: string): void {
    const next = new Set(this.openGroups());
    if (next.has(label)) {
      next.delete(label);
    } else {
      next.add(label);
    }
    this.openGroups.set(next);
  }

  /**
   * Desktop sidebar group open-state (separate from the drawer's {@link openGroups}
   * so the persistent rail keeps its own expansion). Collapsed by default; the
   * group containing the active route is auto-expanded (see the constructor
   * effect) so the user always sees where they are without expanding everything.
   */
  private readonly sidebarOpenGroups = signal<Set<string>>(new Set());

  /** Whether a sidebar nav group is expanded. */
  isSidebarGroupOpen(label: string): boolean {
    return this.sidebarOpenGroups().has(label);
  }

  /** Expands/collapses a sidebar nav group (accordion; collapsed by default). */
  toggleSidebarGroup(label: string): void {
    const next = new Set(this.sidebarOpenGroups());
    if (next.has(label)) {
      next.delete(label);
    } else {
      next.add(label);
    }
    this.sidebarOpenGroups.set(next);
  }

  /** The group label whose child matches the given URL, or null for a standalone/link. */
  private groupLabelForUrl(url: string): string | null {
    const full = `/${url.replace(/^\//, '')}`;
    for (const entry of this.allNav) {
      if (entry.kind !== 'group') {
        continue;
      }
      if (entry.children.some((c) => full === c.path || full.startsWith(`${c.path}/`))) {
        return entry.label;
      }
    }
    return null;
  }

  /**
   * Full navigation model used by the hamburger drawer (Req 3). Standalone
   * links plus grouped sections; each child keeps its own {@link NavLink.adminOnly}
   * / {@link NavLink.roles} gating so role visibility is evaluated per-child.
   * This is the same grouped structure the drawer renders, so every authorised
   * destination — including the ones duplicated on the bottom bar and the
   * lower-frequency admin pages — stays reachable (Req 13.1).
   */
  private readonly allNav: NavEntry[] = [
    // Req 4/5 split: /dashboard is the DETAILED, role-aware dashboard reached
    // from the hamburger. The lighter Home summary is a later pass.
    // TODO(mobile-ui-redesign Req 4): add a lightweight Home summary landing
    // view and (optionally) redirect post-login there instead of /dashboard.
    { kind: 'link', label: 'Shifa Dashboard', path: '/dashboard', icon: 'ti-layout-dashboard' },
    {
      kind: 'group',
      label: 'Orders',
      icon: 'ti-receipt',
      children: [
        {
          kind: 'link',
          label: 'New Order',
          path: '/orders/new',
          icon: 'ti-plus',
          roles: [Role.SALESPERSON, Role.ADMIN, Role.TEAM_LEAD],
        },
        { kind: 'link', label: 'Approval Queue', path: '/approval-queue', icon: 'ti-checklist', adminOnly: true },
        {
          kind: 'link',
          label: 'Payments',
          path: '/payments',
          icon: 'ti-shield-check',
          roles: [Role.ADMIN, Role.PAYMENT_VERIFIER],
        },
        {
          kind: 'link',
          label: 'Orders',
          path: '/orders',
          icon: 'ti-receipt',
          // All-orders view; any staff may reach it (backend scopes a salesperson
          // to their own orders). Excludes roles with a dedicated home only.
          roles: [Role.ADMIN, Role.ACCOUNTANT, Role.SALESPERSON, Role.TEAM_LEAD],
        },
        {
          kind: 'link',
          label: 'Packing',
          path: '/packing',
          icon: 'ti-package',
          roles: [Role.ADMIN, Role.PACKING_USER],
        },
        {
          kind: 'link',
          label: 'Reconciliation',
          path: '/reconciliation',
          icon: 'ti-cash-register',
          roles: [Role.ADMIN, Role.ACCOUNTANT],
        },
        {
          kind: 'link',
          label: 'Returns',
          path: '/returns',
          icon: 'ti-arrow-back-up',
          roles: [Role.ADMIN, Role.ACCOUNTANT],
        },
      ],
    },
    {
      // CRM: leads pipeline, customers, and the salesperson directory/360.
      kind: 'group',
      label: 'CRM',
      icon: 'ti-users',
      children: [
        {
          kind: 'link',
          label: 'Leads',
          path: '/leads',
          icon: 'ti-user-plus',
          roles: [Role.SALESPERSON, Role.ADMIN],
        },
        {
          kind: 'link',
          label: 'Due follow-ups',
          path: '/leads/follow-ups',
          icon: 'ti-calendar-event',
          roles: [Role.SALESPERSON, Role.ADMIN],
        },
        {
          kind: 'link',
          label: 'Customers',
          path: '/customers',
          icon: 'ti-users',
          // Salesperson sees Customers too, scoped by the backend to their own
          // orders' customers (Req 5.4, 5.5); admin/accountant see everyone.
          roles: [Role.ADMIN, Role.ACCOUNTANT, Role.SALESPERSON],
        },
        {
          // Sales leaderboard + streak (light gamification), salesperson-facing.
          kind: 'link',
          label: 'Leaderboard',
          path: '/leaderboard',
          icon: 'ti-trophy',
          roles: [Role.ADMIN, Role.SALESPERSON],
        },
        {
          // Salesperson 360 — directory + performance leaderboard (ADMIN only).
          kind: 'link',
          label: 'Salespeople',
          path: '/salespeople',
          icon: 'ti-id-badge-2',
          adminOnly: true,
        },
        {
          kind: 'link',
          label: 'Teams',
          path: '/team',
          icon: 'ti-users-group',
          adminOnly: true,
        },
      ],
    },
    {
      kind: 'group',
      label: 'Catalog',
      icon: 'ti-building-store',
      children: [
        {
          kind: 'link',
          label: 'Products',
          path: '/products',
          icon: 'ti-leaf',
          // Read-only for salespeople (mutations hidden + backend ADMIN-guarded).
          roles: [Role.ADMIN, Role.SALESPERSON],
        },
        { kind: 'link', label: 'Inventory', path: '/inventory', icon: 'ti-packages', adminOnly: true },
      ],
    },
    {
      kind: 'group',
      label: 'Procurement',
      icon: 'ti-truck-delivery',
      children: [
        { kind: 'link', label: 'Suppliers', path: '/suppliers', icon: 'ti-building-warehouse', adminOnly: true },
        { kind: 'link', label: 'Purchase Orders', path: '/purchase-orders', icon: 'ti-clipboard-list', adminOnly: true },
      ],
    },
    {
      // Analytics & reporting (FEATURE-ROADMAP §6, statistical-insights-engine).
      kind: 'group',
      label: 'Analytics & Reports',
      icon: 'ti-chart-histogram',
      children: [
        {
          kind: 'link',
          label: 'Reports',
          path: '/reports',
          icon: 'ti-chart-histogram',
          // Salesperson sees their own sales/product/customer reports (scoped +
          // money/operations blocked server-side).
          roles: [Role.ADMIN, Role.ACCOUNTANT, Role.SALESPERSON, Role.CA],
        },
        {
          kind: 'link',
          label: 'GST & Accounting',
          path: '/ca/gst',
          icon: 'ti-receipt-tax',
          roles: [Role.ADMIN, Role.CA],
        },
        {
          kind: 'link',
          label: 'GST Filing',
          path: '/ca/gst/filing',
          icon: 'ti-file-invoice',
          roles: [Role.ADMIN, Role.CA],
        },
        {
          kind: 'link',
          label: 'GST Reconciliation',
          path: '/ca/gst/reconciliation',
          icon: 'ti-scale',
          roles: [Role.ADMIN, Role.CA],
        },
        { kind: 'link', label: 'Analytics', path: '/analytics', icon: 'ti-chart-dots', adminOnly: true },
        { kind: 'link', label: 'Insights', path: '/insights', icon: 'ti-bulb', adminOnly: true },
        {
          kind: 'link',
          label: 'Team Performance',
          path: '/team-performance',
          icon: 'ti-chart-arrows',
          roles: [Role.ADMIN, Role.TEAM_LEAD],
        },
      ],
    },
    {
      kind: 'group',
      label: 'Finance',
      icon: 'ti-report-money',
      children: [
        { kind: 'link', label: 'Expenses', path: '/expenses', icon: 'ti-cash', roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA] },
        { kind: 'link', label: 'Profit & Loss', path: '/finance/pnl', icon: 'ti-chart-pie', roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA] },
      ],
    },
    {
      // General Ledger / accounting module (general-ledger-accounting, Req 16).
      // Every link is gated to the finance roles (ADMIN/ACCOUNTANT/CA) matching
      // the route's accountingGuard; CA is read-only server-side.
      kind: 'group',
      label: 'Accounting',
      icon: 'ti-book',
      children: [
        {
          kind: 'link',
          label: 'Chart of Accounts',
          path: '/accounting/chart-of-accounts',
          icon: 'ti-sitemap',
          roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA],
        },
        {
          kind: 'link',
          label: 'Voucher Entry',
          path: '/accounting/vouchers/new',
          icon: 'ti-file-invoice',
          roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA],
        },
        {
          kind: 'link',
          label: 'Day Book',
          path: '/accounting/day-book',
          icon: 'ti-book-2',
          roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA],
        },
        {
          kind: 'link',
          label: 'Trial Balance',
          path: '/accounting/trial-balance',
          icon: 'ti-scale',
          roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA],
        },
        {
          kind: 'link',
          label: 'Ledger Statement',
          path: '/accounting/ledger-statement',
          icon: 'ti-list-details',
          roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA],
        },
        {
          kind: 'link',
          label: 'Balance Sheet',
          path: '/accounting/balance-sheet',
          icon: 'ti-scale-outline',
          roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA],
        },
        {
          kind: 'link',
          label: 'Profit & Loss',
          path: '/accounting/profit-and-loss',
          icon: 'ti-report-money',
          roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA],
        },
        {
          kind: 'link',
          label: 'Cash Flow',
          path: '/accounting/cash-flow',
          icon: 'ti-cash-banknote',
          roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA],
        },
      ],
    },
    {
      kind: 'group',
      label: 'Account & Settings',
      icon: 'ti-settings',
      children: [
        { kind: 'link', label: 'My Profile', path: '/my-profile', icon: 'ti-user-circle' },
        { kind: 'link', label: 'Users', path: '/users', icon: 'ti-users', adminOnly: true },
        { kind: 'link', label: 'Profile approvals', path: '/profile-approvals', icon: 'ti-user-check', adminOnly: true },
        { kind: 'link', label: 'Settings', path: '/settings', icon: 'ti-settings', adminOnly: true },
        { kind: 'link', label: 'Notifications', path: '/notifications', icon: 'ti-bell', adminOnly: true },
        { kind: 'link', label: 'Announcements', path: '/announcements', icon: 'ti-speakerphone', adminOnly: true },
        {
          kind: 'link',
          label: 'WhatsApp templates',
          path: '/whatsapp-templates',
          icon: 'ti-brand-whatsapp',
          roles: [Role.ADMIN, Role.ACCOUNTANT, Role.TEAM_LEAD],
        },
        { kind: 'link', label: 'Audit Log', path: '/audit', icon: 'ti-history', adminOnly: true },
        { kind: 'link', label: 'Backups', path: '/backups', icon: 'ti-database', adminOnly: true },
      ],
    },
  ];

  /**
   * Exactly-four most-used destinations per role for the bottom tab bar (Req 2).
   * These are the role's authorised primary tasks; the full navigation (and any
   * destination not listed here) remains reachable from the hamburger drawer.
   */
  private readonly bottomTabsByRole: Record<Role, BottomTab[]> = {
    // Req 2.2
    [Role.SALESPERSON]: [
      { label: 'New Order', path: '/orders/new', icon: 'ti-plus' },
      { label: 'Orders', path: '/orders', icon: 'ti-receipt' },
      { label: 'Customers', path: '/customers', icon: 'ti-users' },
      { label: 'Products', path: '/products', icon: 'ti-leaf' },
    ],
    // Req 2.3 — Handover & Dispatch have no dedicated route yet; both are
    // actions performed inside the packing area (POST /api/packing/{id}/handover
    // & /dispatch), so they point at /packing as sensible placeholders.
    // TODO(mobile-ui-redesign): point Handover/Dispatch at dedicated routes
    // once they exist.
    [Role.PACKING_USER]: [
      { label: 'Packing', path: '/packing', icon: 'ti-barcode' },
      { label: 'Handover', path: '/packing', icon: 'ti-transfer' },
      { label: 'Dispatch', path: '/packing', icon: 'ti-truck-delivery' },
      { label: 'Orders', path: '/orders', icon: 'ti-receipt' },
    ],
    // Req 2.4
    [Role.ACCOUNTANT]: [
      { label: 'Reconcile', path: '/reconciliation', icon: 'ti-cash-register' },
      { label: 'Reports', path: '/reports', icon: 'ti-chart-histogram' },
      { label: 'Expenses', path: '/expenses', icon: 'ti-cash' },
      { label: 'Orders', path: '/orders', icon: 'ti-receipt' },
    ],
    // Req 2.5
    [Role.ADMIN]: [
      { label: 'Approvals', path: '/approval-queue', icon: 'ti-checklist' },
      { label: 'Orders', path: '/orders', icon: 'ti-receipt' },
      { label: 'Products', path: '/products', icon: 'ti-leaf' },
      { label: 'Reports', path: '/reports', icon: 'ti-chart-histogram' },
    ],
    // Payment Verifier (product-audit §4.4): the payment queue is their home.
    [Role.PAYMENT_VERIFIER]: [
      { label: 'Payments', path: '/payments', icon: 'ti-shield-check' },
      { label: 'Orders', path: '/orders', icon: 'ti-receipt' },
    ],
    // CA (Chartered Accountant): the GST dashboard is their home; plus reports,
    // finance, and their profile.
    [Role.CA]: [
      { label: 'GST', path: '/ca/gst', icon: 'ti-receipt-tax' },
      { label: 'Reports', path: '/reports', icon: 'ti-chart-histogram' },
      { label: 'Finance', path: '/finance/pnl', icon: 'ti-cash' },
      { label: 'My Profile', path: '/my-profile', icon: 'ti-user-circle' },
    ],
    // Team Lead: read-only oversight — Dashboard, team-scoped Orders, and the
    // team Performance rollup are their day-to-day, plus their own profile.
    [Role.TEAM_LEAD]: [
      { label: 'Dashboard', path: '/dashboard', icon: 'ti-layout-dashboard' },
      { label: 'Orders', path: '/orders', icon: 'ti-receipt' },
      { label: 'Performance', path: '/team-performance', icon: 'ti-chart-arrows' },
      { label: 'My Profile', path: '/my-profile', icon: 'ti-user-circle' },
    ],
    // Customers never reach the staff shell; no bottom bar.
    [Role.CUSTOMER]: [],
  };

  /** The four bottom-bar destinations for the signed-in user's role (Req 2.1). */
  protected readonly bottomTabs = computed<BottomTab[]>(() => {
    const role = this.auth.session()?.role ?? null;
    return role ? (this.bottomTabsByRole[role] ?? []) : [];
  });

  /**
   * Navigation entries visible to the current user for the hamburger drawer.
   * Group children are filtered by role first; a group with no visible children
   * is dropped entirely, while a standalone admin-only link is hidden for
   * non-admins.
   */
  protected readonly navEntries = computed<NavEntry[]>(() => {
    const role = this.auth.session()?.role ?? null;
    const isAdmin = role === Role.ADMIN;
    const canSee = (link: NavLink) => {
      // An explicit role allow-list (e.g. New Order for SALESPERSON + ADMIN)
      // takes precedence so non-admin roles can see specific entries.
      if (link.roles) {
        return role !== null && link.roles.includes(role);
      }
      return !link.adminOnly || isAdmin;
    };
    const result: NavEntry[] = [];
    for (const entry of this.allNav) {
      if (entry.kind === 'link') {
        if (canSee(entry)) {
          result.push(entry);
        }
        continue;
      }
      const children = entry.children.filter(canSee);
      if (children.length > 0) {
        result.push({ ...entry, children });
      }
    }
    return result;
  });

  /** Current router URL (without query string), tracked for active-tab state. */
  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => this.router.url),
      startWith(this.router.url),
    ),
    { initialValue: this.router.url },
  );

  /** The current page title, derived from the active route for the top bar. */
  protected readonly pageTitle = computed(() => this.titleForUrl(this.currentUrl()));

  /**
   * The path of the bottom tab that best matches the current URL. The longest
   * matching prefix wins so that, for a SALESPERSON, visiting /orders/new
   * highlights "New Order" rather than the broader "Orders" tab (Req 2.6).
   */
  protected readonly activeTabPath = computed<string | null>(() => {
    const url = this.currentUrl().split('?')[0];
    let best: string | null = null;
    for (const tab of this.bottomTabs()) {
      if (url === tab.path || url.startsWith(`${tab.path}/`)) {
        if (best === null || tab.path.length > best.length) {
          best = tab.path;
        }
      }
    }
    return best;
  });

  /** Live connection state label for the top-bar SSE indicator. */
  protected readonly liveLabel = computed(() => {
    switch (this.events.status()) {
      case 'open':
        return 'Live';
      case 'connecting':
        return 'Connecting';
      default:
        return 'Offline';
    }
  });

  /** Flattens groups + standalone entries into a single list of links. */
  private allLinks(): NavLink[] {
    const links: NavLink[] = [];
    for (const entry of this.allNav) {
      if (entry.kind === 'link') {
        links.push(entry);
      } else {
        links.push(...entry.children);
      }
    }
    return links;
  }

  private titleForUrl(url: string): string {
    const path = url.split('?')[0].replace(/^\//, '');
    const match = this.allLinks().find((i) => i.path === `/${path}`);
    return match?.label ?? 'Dashboard';
  }

  toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  closeMenu(): void {
    this.menuOpen.set(false);
    // Reset groups so the drawer always opens with everything collapsed.
    this.openGroups.set(new Set());
  }

  toggleUserMenu(): void {
    this.userMenuOpen.update((open) => !open);
  }

  closeUserMenu(): void {
    this.userMenuOpen.set(false);
  }

  /** Escape closes the user menu or the hamburger drawer for keyboard users. */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.userMenuOpen()) {
      this.closeUserMenu();
    }
    if (this.menuOpen()) {
      this.closeMenu();
    }
  }

  /** Extracts the animation state key so the router outlet can transition. */
  routeState(outlet: RouterOutlet): string {
    return outlet?.activatedRouteData?.['animation'] ?? this.router.url;
  }

  async signOut(): Promise<void> {
    const confirmed = await this.confirm.confirm({
      title: 'Sign out',
      message: 'Sign out of the Shifa admin? You will need to log in again to continue.',
      confirmLabel: 'Sign out',
      icon: 'ti-logout',
    });
    if (!confirmed) {
      return;
    }
    this.auth.logout();
    void this.router.navigateByUrl('/login');
  }
}
