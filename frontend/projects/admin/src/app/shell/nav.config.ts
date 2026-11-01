import { Role } from 'core';
import { BottomTab, NavEntry } from './nav.model';

/**
 * Full navigation model used by the hamburger drawer (Req 3). Standalone
 * links plus grouped sections; each child keeps its own {@link NavLink.adminOnly}
 * / {@link NavLink.roles} gating so role visibility is evaluated per-child.
 * This is the same grouped structure the drawer renders, so every authorised
 * destination — including the ones duplicated on the bottom bar and the
 * lower-frequency admin pages — stays reachable (Req 13.1).
 */
export const ALL_NAV: NavEntry[] = [
  // Req 4/5 split: /dashboard is the DETAILED, role-aware dashboard reached
  // from the hamburger. The lighter Home summary is a later pass.
  // TODO(mobile-ui-redesign Req 4): add a lightweight Home summary landing
  // view and (optionally) redirect post-login there instead of /dashboard.
  {
    kind: 'link',
    label: 'Shifa Dashboard',
    path: '/dashboard',
    icon: 'ti-layout-dashboard',
    // CA, Payment Verifier and Packing_User are redirected away from /dashboard
    // to their own home (GST / Payments / Packing), so the generic dashboard
    // link would dead-end for them — show it only to the roles whose home
    // actually IS /dashboard.
    roles: [Role.ADMIN, Role.ACCOUNTANT, Role.SALESPERSON, Role.TEAM_LEAD],
  },
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
      { kind: 'link', label: 'Exception Center', path: '/exceptions', icon: 'ti-alert-triangle', adminOnly: true },
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
        // All-orders view; every staff role may reach it (route = staffGuard;
        // the backend scopes a salesperson to their own orders). Packing_User
        // and Payment_Verifier also have an Orders bottom tab, so the drawer
        // link is listed for them too to keep the drawer and tab bar in sync.
        roles: [
          Role.ADMIN,
          Role.ACCOUNTANT,
          Role.SALESPERSON,
          Role.TEAM_LEAD,
          Role.PACKING_USER,
          Role.PAYMENT_VERIFIER,
        ],
      },
      { kind: 'link', label: 'Cancel Order', path: '/order-cancellation', icon: 'ti-ban', adminOnly: true },
      { kind: 'link', label: 'Deleted orders', path: '/deleted-orders', icon: 'ti-trash', adminOnly: true },
      {
        kind: 'link',
        label: 'Packing',
        path: '/packing',
        icon: 'ti-package',
        roles: [Role.ADMIN, Role.PACKING_USER],
      },
      {
        kind: 'link',
        label: 'Pick-list',
        path: '/packing/pick-list',
        icon: 'ti-clipboard-list',
        // Daily pick-list / packing manifest. A packer bottom tab too; add the
        // drawer link so it isn't reachable only from the bottom bar.
        roles: [Role.ADMIN, Role.PACKING_USER],
      },
      {
        kind: 'link',
        label: 'Mark RTO',
        path: '/packing/rto',
        icon: 'ti-rotate-2',
        roles: [Role.ADMIN, Role.PACKING_USER],
      },
      {
        kind: 'link',
        label: 'Returns',
        path: '/returns',
        icon: 'ti-arrow-back-up',
        roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA],
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
        // CRM tasks (CRM expansion Req 8) — SALESPERSON / TEAM_LEAD / ADMIN.
        kind: 'link',
        label: 'Tasks',
        path: '/tasks',
        icon: 'ti-checkbox',
        roles: [Role.SALESPERSON, Role.TEAM_LEAD, Role.ADMIN],
      },
      {
        // CRM meetings (CRM expansion Req 9) — SALESPERSON / TEAM_LEAD / ADMIN.
        kind: 'link',
        label: 'Meetings',
        path: '/meetings',
        icon: 'ti-calendar-event',
        roles: [Role.SALESPERSON, Role.TEAM_LEAD, Role.ADMIN],
      },
      {
        // Unified CRM cockpit (CRM expansion Req 14) — ADMIN only.
        kind: 'link',
        label: 'CRM cockpit',
        path: '/crm-dashboard',
        icon: 'ti-layout-dashboard',
        adminOnly: true,
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
        label: 'Team-wise Sales',
        path: '/teams-overview',
        icon: 'ti-chart-bar',
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
      { kind: 'link', label: 'Analytics', path: '/analytics', icon: 'ti-chart-dots', adminOnly: true },
      // Daily sales per salesperson, the daily bonus they earned, month calendar and product report.
      { kind: 'link', label: 'Daily Sales & Incentives', path: '/incentives', icon: 'ti-coin', adminOnly: true },
      { kind: 'link', label: 'Delivery Partners', path: '/delivery-partners', icon: 'ti-truck-delivery', adminOnly: true },
      { kind: 'link', label: 'Shopify Sync', path: '/shopify-sync', icon: 'ti-brand-shopify', adminOnly: true },
      { kind: 'link', label: 'Ask Shifa', path: '/assistant', icon: 'ti-robot', adminOnly: true },
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
      {
        kind: 'link',
        label: 'Reconciliation',
        path: '/reconciliation',
        icon: 'ti-cash-register',
        // CA has read access (route accountantGuard + backend admits CA).
        roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA],
      },
      {
        kind: 'link',
        label: 'Settlement Report',
        path: '/remittance-report',
        icon: 'ti-report-money',
        // Weekly courier COD remittance report (what was settled + shortfalls).
        roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA],
      },
      {
        kind: 'link',
        label: 'Settlement Center',
        path: '/settlements',
        icon: 'ti-arrows-transfer-up',
        roles: [Role.ADMIN, Role.ACCOUNTANT, Role.CA],
      },
      // GST (tax) pages live with the rest of Finance. ADMIN and CA only, matching
      // the adminOrCaGuard on the /ca/gst routes.
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
      { kind: 'link', label: 'CRM settings', path: '/crm-settings', icon: 'ti-adjustments-cog', adminOnly: true },
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
export const BOTTOM_TABS_BY_ROLE: Record<Role, BottomTab[]> = {
  // Req 2.2
  [Role.SALESPERSON]: [
    { label: 'New Order', path: '/orders/new', icon: 'ti-plus' },
    { label: 'Orders', path: '/orders', icon: 'ti-receipt' },
    { label: 'Customers', path: '/customers', icon: 'ti-users' },
    { label: 'Products', path: '/products', icon: 'ti-leaf' },
  ],
  // Packing: the four real destinations a packer uses. Packing (scan + the
  // Orders-to-Pack / Awaiting Handover / status sections), the daily Pick-list,
  // Mark RTO (returned parcels), and the all-orders view. The old
  // Handover/Dispatch tabs were removed — both were dead links to /packing and
  // their wording predated the packing-workflow redesign.
  [Role.PACKING_USER]: [
    { label: 'Packing', path: '/packing', icon: 'ti-barcode' },
    { label: 'Pick-list', path: '/packing/pick-list', icon: 'ti-clipboard-list' },
    { label: 'Mark RTO', path: '/packing/rto', icon: 'ti-rotate-2' },
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
    { label: 'My Profile', path: '/my-profile', icon: 'ti-user-circle' },
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
