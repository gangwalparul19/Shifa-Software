import { Component, HostListener, computed, inject, signal } from '@angular/core';
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

/** A collapsible group of related links rendered as a dropdown (desktop) or a
 *  labelled section (mobile). Group visibility follows its children's roles. */
interface NavGroup {
  kind: 'group';
  label: string;
  icon: string;
  children: NavLink[];
}

/** A top-level navigation entry: either a standalone link or a group. */
type NavEntry = NavLink | NavGroup;

/**
 * Authenticated admin chrome, rebuilt on the Tabler design system: a branded
 * vertical sidebar (Shifa leaf mark + grouped navigation with Tabler icons), a
 * sticky top navbar showing the current page title/breadcrumb, a live SSE
 * status pill, and a user dropdown (name, role, logout). The sidebar collapses
 * to an Angular-driven off-canvas drawer on small screens with a smooth slide +
 * backdrop fade. The routed view animates in via {@link routeFade}.
 *
 * <p>All behaviour is signal/Angular-driven (no Bootstrap JS dependency) to stay
 * robust and CSP-friendly. Data wiring (auth session, SSE feed) is unchanged.
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
  private readonly router = inject(Router);
  private readonly confirm = inject(ConfirmService);

  /** Whether the current user is an ADMIN (gates the notifications bell). */
  protected readonly isAdmin = computed(() => this.auth.session()?.role === Role.ADMIN);

  /** Whether the off-canvas sidebar is open (mobile only). */
  protected readonly sidebarOpen = signal(false);
  /** Whether the top-bar user dropdown is open. */
  protected readonly userMenuOpen = signal(false);
  /** Label of the currently open desktop nav group, or null when none is open. */
  protected readonly openGroup = signal<string | null>(null);

  /**
   * Top-level navigation model. Twelve flat items are grouped into five
   * top-level entries (Dashboard, Orders, Catalog, Reports, Settings). Each
   * child keeps its own {@link NavLink.adminOnly} flag so role gating is
   * evaluated per-child (Req 5.4).
   */
  private readonly allNav: NavEntry[] = [
    { kind: 'link', label: 'Dashboard', path: '/dashboard', icon: 'ti-layout-dashboard' },
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
          roles: [Role.SALESPERSON, Role.ADMIN],
        },
        { kind: 'link', label: 'Approval Queue', path: '/approval-queue', icon: 'ti-checklist' },
        { kind: 'link', label: 'Orders', path: '/orders', icon: 'ti-receipt' },
        { kind: 'link', label: 'Packing', path: '/packing', icon: 'ti-package' },
        { kind: 'link', label: 'Reconciliation', path: '/reconciliation', icon: 'ti-cash-register' },
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
      kind: 'link',
      label: 'Customers',
      path: '/customers',
      icon: 'ti-users',
      roles: [Role.ADMIN, Role.ACCOUNTANT],
    },
    {
      kind: 'group',
      label: 'Catalog',
      icon: 'ti-building-store',
      children: [
        { kind: 'link', label: 'Products', path: '/products', icon: 'ti-leaf' },
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
    { kind: 'link', label: 'Reports', path: '/reports', icon: 'ti-chart-histogram' },
    {
      kind: 'group',
      label: 'Finance',
      icon: 'ti-report-money',
      children: [
        { kind: 'link', label: 'Expenses', path: '/expenses', icon: 'ti-cash', roles: [Role.ADMIN, Role.ACCOUNTANT] },
        { kind: 'link', label: 'Profit & Loss', path: '/finance/pnl', icon: 'ti-chart-pie', roles: [Role.ADMIN, Role.ACCOUNTANT] },
      ],
    },
    {
      kind: 'group',
      label: 'Settings',
      icon: 'ti-settings',
      children: [
        { kind: 'link', label: 'Users', path: '/users', icon: 'ti-users', adminOnly: true },
        { kind: 'link', label: 'Settings', path: '/settings', icon: 'ti-settings', adminOnly: true },
        { kind: 'link', label: 'Audit Log', path: '/audit', icon: 'ti-history', adminOnly: true },
      ],
    },
  ];

  /**
   * Navigation entries visible to the current user. Group children are filtered
   * by role first; a group with no visible children is dropped entirely, while a
   * standalone admin-only link is hidden for non-admins.
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

  /** Current router URL (without query string), tracked for active-group state. */
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

  /**
   * Titles for routes reachable without a nav link (e.g. Notifications, opened
   * from the top-bar bell). Keeps {@link titleForUrl} resolving them for the
   * breadcrumb/top-bar even though they are absent from {@link allNav}.
   */
  private readonly extraTitles: Record<string, string> = {
    '/notifications': 'Notifications',
  };

  private titleForUrl(url: string): string {
    const path = url.split('?')[0].replace(/^\//, '');
    const match = this.allLinks().find((i) => i.path === `/${path}`);
    return match?.label ?? this.extraTitles[`/${path}`] ?? 'Dashboard';
  }

  /** True when any of the group's child routes is the active route. */
  isGroupActive(group: NavGroup): boolean {
    const url = this.currentUrl().split('?')[0];
    return group.children.some(
      (child) => url === child.path || url.startsWith(`${child.path}/`),
    );
  }

  /** Toggles a desktop dropdown group; only one group is open at a time. */
  toggleGroup(label: string): void {
    this.openGroup.update((open) => (open === label ? null : label));
  }

  /** Closes any open desktop dropdown group. */
  closeGroups(): void {
    this.openGroup.set(null);
  }

  toggleSidebar(): void {
    this.sidebarOpen.update((open) => !open);
  }

  closeSidebar(): void {
    this.sidebarOpen.set(false);
    this.closeGroups();
  }

  toggleUserMenu(): void {
    this.userMenuOpen.update((open) => !open);
  }

  closeUserMenu(): void {
    this.userMenuOpen.set(false);
  }

  /** Escape closes any open dropdown / user menu / mobile nav for keyboard users. */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.openGroup()) {
      this.closeGroups();
    }
    if (this.userMenuOpen()) {
      this.closeUserMenu();
    }
    if (this.sidebarOpen()) {
      this.closeSidebar();
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
