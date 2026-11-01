import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes, UrlTree } from '@angular/router';
import { AuthService, createRoleGuard, Role } from 'core';
import { AdminShellComponent } from './shell/admin-shell.component';

/*
 * Route components are loaded lazily (`loadComponent`) so the initial bundle only
 * carries the shell and the router. Each page becomes its own chunk, fetched the
 * first time the user opens it. Only the shell stays eager: every signed-in visit
 * renders it, so splitting it out would just add a request. All chunks are still
 * pre-cached by the service worker (`/*.js` in ngsw-config.json), so pages keep
 * working offline.
 */

const LOGIN_PATH = '/login';
const FORBIDDEN_PATH = '/forbidden';

/** Any authenticated staff member may see the dashboard shell. */
export const staffGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.ACCOUNTANT,
  Role.SALESPERSON,
  Role.TEAM_LEAD,
  Role.PACKING_USER,
  Role.PAYMENT_VERIFIER,
  Role.CA,
);

/** Admin-only sections (management/approval/configuration, Req 5.4). */
export const adminOnlyGuard = createRoleGuard(LOGIN_PATH, FORBIDDEN_PATH, Role.ADMIN);

/** CA (GST/accounting) dashboard is limited to CA and Admin (CA GST dashboard, Req 1). */
export const adminOrCaGuard = createRoleGuard(LOGIN_PATH, FORBIDDEN_PATH, Role.ADMIN, Role.CA);

/** Order entry (New Order) is limited to Salesperson and Admin (Req 7). */
export const salespersonGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.SALESPERSON,
);

/**
 * Order entry (New Order) — Salesperson, Admin AND Team Lead. A team lead may
 * punch orders on behalf of their team; the backend attributes the order to the
 * lead and scopes it back to them. Separate from {@link salespersonGuard} so a
 * team lead does NOT gain access to the salesperson-only Leads/Products pages.
 */
export const orderEntryGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.SALESPERSON,
  Role.TEAM_LEAD,
);

/**
 * Reports are open to Admin, Accountant AND Salesperson — the backend scopes a
 * salesperson to their own orders and blocks money/operations reports, so a
 * salesperson sees only their own sales/product/customer reports.
 */
export const reportsGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.ACCOUNTANT,
  Role.SALESPERSON,
  Role.CA,
);

/** Reconciliation/settlement + finance sections are limited to Accountant, CA and Admin. */
export const accountantGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.ACCOUNTANT,
  Role.CA,
);

/**
 * General Ledger / accounting module (general-ledger-accounting, Req 16.1–16.3).
 * View access is granted to ADMIN, ACCOUNTANT and CA. Post/reverse/CoA-edit are
 * additionally enforced ADMIN/ACCOUNTANT-only on the backend (CA is read-only,
 * Req 16.4, 16.5), and the voucher-entry UI hides its mutating affordances for CA.
 */
export const accountingGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.ACCOUNTANT,
  Role.CA,
);

/**
 * WhatsApp template management (V44) — ADMIN, ACCOUNTANT and TEAM_LEAD may add
 * and customize the one-tap message templates staff send.
 */
export const whatsappTemplatesGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.ACCOUNTANT,
  Role.TEAM_LEAD,
);

/**
 * Customers / CRM is open to Admin, Accountant and Salesperson. A salesperson is
 * scoped by the backend to only the customers derived from their own orders
 * (Req 5.4, 5.5); admin/accountant see every customer.
 */
export const customersGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.ACCOUNTANT,
  Role.SALESPERSON,
);

/** Packing (barcode scan) is limited to Packing_User and Admin (Req 11). */
export const packingGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.PACKING_USER,
);

/** Payment verification dashboard is limited to Payment_Verifier and Admin (product-audit §4.4). */
export const paymentVerifierGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.PAYMENT_VERIFIER,
);

/** Team-lead performance dashboard is limited to Team_Lead and Admin. */
export const teamLeadGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.TEAM_LEAD,
);

/**
 * Blocks the whole admin shell while the signed-in user must set a new password
 * (an admin reset their password to the temporary one). Such a user is bounced
 * to {@code /change-password} until they choose a new password, which clears the
 * flag. Runs after {@link staffGuard}, so the user is already authenticated.
 */
export const forcePasswordChangeGuard: CanActivateFn = (): boolean | UrlTree => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.isAuthenticated() && auth.mustChangePassword()) {
    return router.createUrlTree(['/change-password']);
  }
  return true;
};

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./auth/login.component').then((m) => m.LoginComponent),
  },
  // Forced / self-service password change — outside the shell (no app chrome),
  // reachable while the force-change flag is set. Requires an authenticated
  // session; createAuthGuard-style check lives in the component via AuthService.
  {
    path: 'change-password',
    loadComponent: () =>
      import('./auth/change-password.component').then((m) => m.ChangePasswordComponent),
  },
  {
    path: 'forbidden',
    loadComponent: () => import('./auth/forbidden.component').then((m) => m.ForbiddenComponent),
  },
  // Public, unauthenticated customer order tracking (ENHANCEMENT 2.2) — opaque
  // token, outside the admin shell/guards.
  {
    path: 'track/:token',
    loadComponent: () =>
      import('./track/public-tracking.component').then((m) => m.PublicTrackingComponent),
  },
  // Public, unauthenticated self-service tracking by registered mobile number —
  // lists the customer's orders, pick one to see its live tracking. Outside the
  // admin shell/guards.
  {
    path: 'track-order',
    loadComponent: () =>
      import('./track/track-order.component').then((m) => m.TrackOrderComponent),
  },
  {
    path: '',
    component: AdminShellComponent,
    canActivate: [staffGuard, forcePasswordChangeGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        loadComponent: () =>
          import('./dashboard/dashboard.component').then((m) => m.DashboardComponent),
      },
      {
        path: 'approval-queue',
        loadComponent: () =>
          import('./approval/approval-queue.component').then((m) => m.ApprovalQueueComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Salesperson/admin order entry (Req 7). Registered before the
        // all-orders view; the server enforces the same role restriction.
        path: 'orders/new',
        // orderEntryGuard allows Team Lead in addition to Salesperson/Admin.
        loadComponent: () =>
          import('./orders/new-order.component').then((m) => m.NewOrderComponent),
        canActivate: [orderEntryGuard],
      },
      {
        // Edit-order: an ADMIN may correct any editable order (PENDING/APPROVED,
        // PUT /api/admin/orders/{id}); the creating SALESPERSON/TEAM_LEAD may edit
        // their OWN order while it is still PENDING (PUT /api/orders/{id}). The
        // component picks the endpoint by role; the server enforces ownership +
        // editable-status (404 out-of-scope, 400/409 once past the editable window).
        path: 'orders/:id/edit',
        loadComponent: () =>
          import('./orders/edit-order.component').then((m) => m.EditOrderComponent),
        canActivate: [orderEntryGuard],
      },
      {
        // All-orders view; any staff may reach it (the backend scopes a
        // salesperson to their own orders, Req 5.5, 22.1).
        path: 'orders',
        loadComponent: () => import('./orders/orders.component').then((m) => m.OrdersComponent),
        canActivate: [staffGuard],
      },
      {
        // Due follow-ups view (SALESPERSON + ADMIN, Req 5.2). Registered before
        // the pipeline list so the more specific path wins. The backend scopes a
        // salesperson to their own leads.
        path: 'leads/follow-ups',
        loadComponent: () =>
          import('./leads/due-follow-ups.component').then((m) => m.DueFollowUpsComponent),
        canActivate: [salespersonGuard],
      },
      {
        // Leads pipeline / capture (SALESPERSON + ADMIN, Req 8). The backend
        // scopes a salesperson to their own leads; admins see all.
        path: 'leads',
        loadComponent: () => import('./leads/leads.component').then((m) => m.LeadsComponent),
        canActivate: [salespersonGuard],
      },
      {
        // CRM tasks (SALESPERSON + TEAM_LEAD + ADMIN, CRM expansion Req 8). The
        // backend scopes own / team / all.
        path: 'tasks',
        loadComponent: () => import('./tasks/tasks.component').then((m) => m.TasksComponent),
        canActivate: [orderEntryGuard],
      },
      {
        // CRM meetings (SALESPERSON + TEAM_LEAD + ADMIN, CRM expansion Req 9).
        path: 'meetings',
        loadComponent: () =>
          import('./meetings/meetings.component').then((m) => m.MeetingsComponent),
        canActivate: [orderEntryGuard],
      },
      {
        // Unified CRM cockpit (ADMIN only, CRM expansion Req 14).
        path: 'crm-dashboard',
        loadComponent: () =>
          import('./crm-dashboard/crm-dashboard.component').then((m) => m.CrmDashboardComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        path: 'exceptions',
        loadComponent: () =>
          import('./exceptions/exceptions.component').then((m) => m.ExceptionsComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Statistical Insights (ADMIN only, statistical-insights-engine Req 13).
        // The backend GET is open to SALESPERSON too, but the admin-only
        // recompute/dismiss actions live on this screen, so the route is gated
        // to ADMIN (mirrors the other admin-only management sections).
        path: 'insights',
        loadComponent: () =>
          import('./insights/insights.component').then((m) => m.InsightsComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Customers / CRM (ADMIN + ACCOUNTANT + SALESPERSON). The backend scopes
        // a salesperson to only the customers derived from orders they created
        // (Req 5.4, 5.5); admin/accountant see every customer.
        path: 'customers',
        loadComponent: () =>
          import('./customers/customers.component').then((m) => m.CustomersComponent),
        canActivate: [customersGuard],
      },
      {
        // Returns / Refunds (ADMIN + ACCOUNTANT view; mutations gated in the
        // UI + backend, Set B — Feature 2).
        path: 'returns',
        loadComponent: () => import('./returns/returns.component').then((m) => m.ReturnsComponent),
        canActivate: [accountantGuard],
      },
      {
        // Staff notification center: ADMIN uses the broader admin notification
        // service, while other staff roles use their scoped notification service.
        path: 'notifications',
        loadComponent: () =>
          import('./notifications/notifications.component').then((m) => m.NotificationsComponent),
        canActivate: [staffGuard],
      },
      {
        // Audit log (ADMIN only, Set B — Feature 4).
        path: 'audit',
        loadComponent: () => import('./audit/audit.component').then((m) => m.AuditComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Products (ADMIN + SALESPERSON). A salesperson gets read-only access —
        // the list + product detail — with all mutation affordances hidden in the
        // UI and enforced ADMIN-only on the backend.
        path: 'products',
        loadComponent: () =>
          import('./products/products.component').then((m) => m.ProductsComponent),
        canActivate: [salespersonGuard],
      },
      {
        // Inventory / stock management (ADMIN only — the backend inventory
        // endpoints are guarded with hasRole('ADMIN'), Wave 3 Feature 1).
        path: 'inventory',
        loadComponent: () =>
          import('./inventory/inventory.component').then((m) => m.InventoryComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Suppliers management (ADMIN only, Phase C2).
        path: 'suppliers',
        loadComponent: () =>
          import('./suppliers/suppliers.component').then((m) => m.SuppliersComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Purchase orders (ADMIN only, Phase C2).
        path: 'purchase-orders',
        loadComponent: () =>
          import('./purchase-orders/purchase-orders.component').then(
            (m) => m.PurchaseOrdersComponent,
          ),
        canActivate: [adminOnlyGuard],
      },
      {
        // Expenses (ADMIN + ACCOUNTANT, Phase C3).
        path: 'expenses',
        loadComponent: () =>
          import('./expenses/expenses.component').then((m) => m.ExpensesComponent),
        canActivate: [accountantGuard],
      },
      {
        // Profit & Loss report (ADMIN + ACCOUNTANT, Phase C3).
        path: 'finance/pnl',
        loadComponent: () =>
          import('./finance/profit-loss.component').then((m) => m.ProfitLossComponent),
        canActivate: [accountantGuard],
      },
      {
        path: 'packing',
        loadComponent: () => import('./packing/scan.component').then((m) => m.ScanComponent),
        canActivate: [packingGuard],
      },
      {
        // RTO (returned to origin) scan page (label redesign feature): scan a
        // returned parcel's order barcode, choose a reason, mark it RTO.
        path: 'packing/rto',
        loadComponent: () => import('./packing/rto.component').then((m) => m.RtoComponent),
        canActivate: [packingGuard],
      },
      {
        // Daily pick-list / packing manifest (enhancement): every product
        // needed across all orders awaiting packing, aggregated onto one sheet.
        path: 'packing/pick-list',
        loadComponent: () =>
          import('./packing/pick-list.component').then((m) => m.PickListComponent),
        canActivate: [packingGuard],
      },
      {
        path: 'reconciliation',
        loadComponent: () =>
          import('./reconciliation/reconciliation.component').then((m) => m.ReconciliationComponent),
        canActivate: [accountantGuard],
      },
      {
        path: 'settlements',
        loadComponent: () =>
          import('./settlements/settlement.component').then((m) => m.SettlementComponent),
        canActivate: [accountantGuard],
      },
      {
        // Weekly COD settlement report: what the courier remitted + settled,
        // partial payments, shortfalls, and what's still to collect (V99).
        path: 'remittance-report',
        loadComponent: () =>
          import('./remittance-report/remittance-report.component').then(
            (m) => m.RemittanceReportComponent,
          ),
        canActivate: [accountantGuard],
      },
      {
        // Reports are guarded for ADMIN + ACCOUNTANT; the backend additionally
        // scopes a salesperson to their own orders if reached directly (Req 20).
        path: 'reports',
        loadComponent: () => import('./reports/reports.component').then((m) => m.ReportsComponent),
        canActivate: [reportsGuard],
      },
      {
        // Company + GST configuration (ADMIN only, Req 5.4).
        path: 'settings',
        loadComponent: () =>
          import('./settings/settings.component').then((m) => m.SettingsComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // CRM configuration: pipelines, stages, tags, sources, defaults,
        // permissions (ADMIN only, crm-expansion Phase 3, Req 16–19).
        path: 'crm-settings',
        loadComponent: () =>
          import('./crm-settings/crm-settings.component').then((m) => m.CrmSettingsComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Staff user management (ADMIN only, Req 5.4).
        path: 'users',
        loadComponent: () => import('./users/users.component').then((m) => m.UsersComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Salespeople directory: onboarding profiles + ID verification (ADMIN only).
        path: 'salespeople',
        loadComponent: () =>
          import('./salespeople/salespeople.component').then((m) => m.SalespeopleComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Self-service "My Profile" — any authenticated staff member. Changes are
        // submitted for admin approval (not applied directly).
        path: 'my-profile',
        loadComponent: () =>
          import('./my-profile/my-profile.component').then((m) => m.MyProfileComponent),
        canActivate: [staffGuard],
      },
      {
        // Admin approval queue for staff profile change requests (ADMIN only).
        path: 'profile-approvals',
        loadComponent: () =>
          import('./profile-approvals/profile-approvals.component').then(
            (m) => m.ProfileApprovalsComponent,
          ),
        canActivate: [adminOnlyGuard],
      },
      {
        // Database backups: run on-demand + review history (ADMIN only, Req 24).
        path: 'backups',
        loadComponent: () => import('./backups/backups.component').then((m) => m.BackupsComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Payment verification dashboard (PAYMENT_VERIFIER + ADMIN, product-audit §4.4).
        path: 'payments',
        loadComponent: () =>
          import('./payments/payments.component').then((m) => m.PaymentsComponent),
        canActivate: [paymentVerifierGuard],
      },
      {
        // Staff announcement banners (ADMIN only, FEATURE-ROADMAP §8.4).
        path: 'announcements',
        loadComponent: () =>
          import('./announcements/announcements.component').then((m) => m.AnnouncementsComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // In-house business-question assistant (ADMIN only; answers come from the OMS database).
        path: 'assistant',
        loadComponent: () =>
          import('./assistant/assistant.component').then((m) => m.AssistantComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Customizable WhatsApp message templates (ADMIN / ACCOUNTANT / TEAM_LEAD, V44).
        path: 'whatsapp-templates',
        loadComponent: () =>
          import('./whatsapp/whatsapp-templates.component').then(
            (m) => m.WhatsappTemplatesComponent,
          ),
        canActivate: [whatsappTemplatesGuard],
      },
      {
        // Analytics suite: targets & incentives, retention, forecasting
        // (ADMIN only, FEATURE-ROADMAP §6).
        path: 'analytics',
        loadComponent: () =>
          import('./analytics/analytics.component').then((m) => m.AnalyticsComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Daily sales per salesperson, daily incentive, month calendar, product-by-day report
        // (ADMIN only). The slabs themselves are edited under Settings → Incentives.
        path: 'incentives',
        loadComponent: () =>
          import('./incentives/incentives.component').then((m) => m.IncentivesComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Retired: the Portal / Shopify / All split now lives on the main
        // dashboard. Old bookmarks land there.
        path: 'channel-dashboard',
        redirectTo: 'dashboard',
        pathMatch: 'full',
      },
      {
        // Shopify sync: list stuck Shopify orders + one-click recover to QuikShipX (ADMIN only).
        path: 'shopify-sync',
        loadComponent: () =>
          import('./shopify-sync/shopify-sync.component').then((m) => m.ShopifySyncComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Order cancellation: cancel an order (even after an AWB) with a note,
        // propagating the cancellation to the courier (ADMIN only).
        path: 'order-cancellation',
        loadComponent: () =>
          import('./order-cancellation/order-cancellation.component').then(
            (m) => m.OrderCancellationComponent,
          ),
        canActivate: [adminOnlyGuard],
      },
      {
        path: 'deleted-orders',
        loadComponent: () =>
          import('./deleted-orders/deleted-orders.component').then((m) => m.DeletedOrdersComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // CA (Chartered Accountant) GST & accounting dashboard (ADMIN + CA).
        path: 'ca/gst',
        loadComponent: () =>
          import('./ca-gst/ca-gst-dashboard.component').then((m) => m.CaGstDashboardComponent),
        canActivate: [adminOrCaGuard],
      },
      {
        // GST returns filing workspace: calendar, prepare/file/reopen, snapshots,
        // filing-aware export (ADMIN + CA, gst-returns-filing Req 10.1).
        path: 'ca/gst/filing',
        loadComponent: () =>
          import('./ca-gst/gst-filing.component').then((m) => m.GstFilingComponent),
        canActivate: [adminOrCaGuard],
      },
      {
        // GST ledger reconciliation: five compared figures + drill-down
        // (ADMIN + CA, gst-returns-filing Req 10.1).
        path: 'ca/gst/reconciliation',
        loadComponent: () =>
          import('./ca-gst/gst-reconciliation.component').then((m) => m.GstReconciliationComponent),
        canActivate: [adminOrCaGuard],
      },
      {
        // General Ledger — Chart of Accounts (ADMIN + ACCOUNTANT + CA view; CoA
        // edits ADMIN/ACCOUNTANT-only server-side, CA read-only). Req 16.1–16.3.
        path: 'accounting/chart-of-accounts',
        loadComponent: () =>
          import('./ledger/chart-of-accounts.component').then((m) => m.ChartOfAccountsComponent),
        canActivate: [accountingGuard],
      },
      {
        // Manual double-entry voucher entry + reversal (ADMIN/ACCOUNTANT post;
        // CA read-only server-side, entry UI hidden for CA). Req 16.1–16.4.
        path: 'accounting/vouchers/new',
        loadComponent: () =>
          import('./ledger/voucher-entry.component').then((m) => m.VoucherEntryComponent),
        canActivate: [accountingGuard],
      },
      {
        // Day Book — chronological voucher listing (read). Req 13, 16.1–16.3.
        path: 'accounting/day-book',
        loadComponent: () => import('./ledger/day-book.component').then((m) => m.DayBookComponent),
        canActivate: [accountingGuard],
      },
      {
        // Trial Balance (read). Req 14, 16.1–16.3, 18.2.
        path: 'accounting/trial-balance',
        loadComponent: () =>
          import('./ledger/trial-balance.component').then((m) => m.TrialBalanceComponent),
        canActivate: [accountingGuard],
      },
      {
        // Ledger statement — per-account statement with running balance (read).
        // The account is chosen in-page, so no route param is needed. Req 12.
        path: 'accounting/ledger-statement',
        loadComponent: () =>
          import('./ledger/ledger-statement.component').then((m) => m.LedgerStatementComponent),
        canActivate: [accountingGuard],
      },
      {
        // Balance Sheet — assets vs liabilities-and-equity as at a date (read).
        // Financial statements Phase 2, Req 10.1, 10.2, 11.3.
        path: 'accounting/balance-sheet',
        loadComponent: () =>
          import('./ledger/balance-sheet.component').then((m) => m.BalanceSheetComponent),
        canActivate: [accountingGuard],
      },
      {
        // Profit & Loss — income vs expenses for a period (read). Req 10.1, 10.2, 11.3.
        path: 'accounting/profit-and-loss',
        loadComponent: () =>
          import('./ledger/profit-and-loss.component').then((m) => m.ProfitAndLossComponent),
        canActivate: [accountingGuard],
      },
      {
        // Cash Flow — direct-method cash/bank movement for a period (read).
        // Req 10.1, 10.2, 11.3.
        path: 'accounting/cash-flow',
        loadComponent: () => import('./ledger/cash-flow.component').then((m) => m.CashFlowComponent),
        canActivate: [accountingGuard],
      },
      {
        // Team management: assign salespeople to a team lead (ADMIN only).
        path: 'team',
        loadComponent: () => import('./team/team.component').then((m) => m.TeamComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Team-wise sales with status overview (ADMIN only) — linked from the dashboard.
        path: 'teams-overview',
        loadComponent: () =>
          import('./dashboard/teams-overview.component').then((m) => m.TeamsOverviewComponent),
        canActivate: [adminOnlyGuard],
      },
      {
        // Orders by delivery partner: QuikShipX / In-house (Ishika Enterprise) / POS (ADMIN only).
        path: 'delivery-partners',
        loadComponent: () =>
          import('./dashboard/delivery-partners-page.component').then(
            (m) => m.DeliveryPartnersPageComponent,
          ),
        canActivate: [adminOnlyGuard],
      },
      {
        // Team-lead performance dashboard (TEAM_LEAD + ADMIN).
        path: 'team-performance',
        loadComponent: () =>
          import('./team/team-performance.component').then((m) => m.TeamPerformanceComponent),
        canActivate: [teamLeadGuard],
      },
      {
        // Sales leaderboard (ADMIN + SALESPERSON) — moved off the dashboard.
        path: 'leaderboard',
        loadComponent: () =>
          import('./leaderboard/leaderboard.component').then((m) => m.LeaderboardComponent),
        canActivate: [salespersonGuard],
      },
    ],
  },
  // Anything that matches no route (a stale bookmark, a mistyped URL, a removed page)
  // used to fail navigation with NG04002 and leave a blank view. Send it home instead;
  // the shell's staffGuard then routes a signed-out visitor on to the login page.
  { path: '**', redirectTo: '' },
];
