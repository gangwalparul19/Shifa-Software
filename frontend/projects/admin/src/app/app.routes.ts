import { Routes } from '@angular/router';
import { createRoleGuard, Role } from 'core';
import { LoginComponent } from './auth/login.component';
import { ForbiddenComponent } from './auth/forbidden.component';
import { DashboardComponent } from './dashboard/dashboard.component';
import { AdminShellComponent } from './shell/admin-shell.component';
import { ApprovalQueueComponent } from './approval/approval-queue.component';
import { OrdersComponent } from './orders/orders.component';
import { NewOrderComponent } from './orders/new-order.component';
import { ProductsComponent } from './products/products.component';
import { InventoryComponent } from './inventory/inventory.component';
import { ScanComponent } from './packing/scan.component';
import { ReconciliationComponent } from './reconciliation/reconciliation.component';
import { ReportsComponent } from './reports/reports.component';
import { SettingsComponent } from './settings/settings.component';
import { UsersComponent } from './users/users.component';
import { SalespeopleComponent } from './salespeople/salespeople.component';
import { MyProfileComponent } from './my-profile/my-profile.component';
import { ProfileApprovalsComponent } from './profile-approvals/profile-approvals.component';
import { CustomersComponent } from './customers/customers.component';
import { ReturnsComponent } from './returns/returns.component';
import { NotificationsComponent } from './notifications/notifications.component';
import { AuditComponent } from './audit/audit.component';
import { SuppliersComponent } from './suppliers/suppliers.component';
import { PurchaseOrdersComponent } from './purchase-orders/purchase-orders.component';
import { ExpensesComponent } from './expenses/expenses.component';
import { ProfitLossComponent } from './finance/profit-loss.component';
import { LeadsComponent } from './leads/leads.component';
import { DueFollowUpsComponent } from './leads/due-follow-ups.component';
import { InsightsComponent } from './insights/insights.component';
import { BackupsComponent } from './backups/backups.component';
import { PaymentsComponent } from './payments/payments.component';
import { AnnouncementsComponent } from './announcements/announcements.component';
import { AnalyticsComponent } from './analytics/analytics.component';
import { TeamComponent } from './team/team.component';
import { WhatsappTemplatesComponent } from './whatsapp/whatsapp-templates.component';
import { CaGstDashboardComponent } from './ca-gst/ca-gst-dashboard.component';
import { TeamPerformanceComponent } from './team/team-performance.component';
import { LeaderboardComponent } from './leaderboard/leaderboard.component';
import { ChartOfAccountsComponent } from './ledger/chart-of-accounts.component';
import { VoucherEntryComponent } from './ledger/voucher-entry.component';
import { DayBookComponent } from './ledger/day-book.component';
import { TrialBalanceComponent } from './ledger/trial-balance.component';
import { LedgerStatementComponent } from './ledger/ledger-statement.component';

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

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: 'forbidden', component: ForbiddenComponent },
  {
    path: '',
    component: AdminShellComponent,
    canActivate: [staffGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      { path: 'dashboard', component: DashboardComponent },
      {
        path: 'approval-queue',
        component: ApprovalQueueComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Salesperson/admin order entry (Req 7). Registered before the
        // all-orders view; the server enforces the same role restriction.
        path: 'orders/new',
        // orderEntryGuard allows Team Lead in addition to Salesperson/Admin.
        component: NewOrderComponent,
        canActivate: [orderEntryGuard],
      },
      {
        // All-orders view; any staff may reach it (the backend scopes a
        // salesperson to their own orders, Req 5.5, 22.1).
        path: 'orders',
        component: OrdersComponent,
        canActivate: [staffGuard],
      },
      {
        // Due follow-ups view (SALESPERSON + ADMIN, Req 5.2). Registered before
        // the pipeline list so the more specific path wins. The backend scopes a
        // salesperson to their own leads.
        path: 'leads/follow-ups',
        component: DueFollowUpsComponent,
        canActivate: [salespersonGuard],
      },
      {
        // Leads pipeline / capture (SALESPERSON + ADMIN, Req 8). The backend
        // scopes a salesperson to their own leads; admins see all.
        path: 'leads',
        component: LeadsComponent,
        canActivate: [salespersonGuard],
      },
      {
        // Statistical Insights (ADMIN only, statistical-insights-engine Req 13).
        // The backend GET is open to SALESPERSON too, but the admin-only
        // recompute/dismiss actions live on this screen, so the route is gated
        // to ADMIN (mirrors the other admin-only management sections).
        path: 'insights',
        component: InsightsComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Customers / CRM (ADMIN + ACCOUNTANT + SALESPERSON). The backend scopes
        // a salesperson to only the customers derived from orders they created
        // (Req 5.4, 5.5); admin/accountant see every customer.
        path: 'customers',
        component: CustomersComponent,
        canActivate: [customersGuard],
      },
      {
        // Returns / Refunds (ADMIN + ACCOUNTANT view; mutations gated in the
        // UI + backend, Set B — Feature 2).
        path: 'returns',
        component: ReturnsComponent,
        canActivate: [accountantGuard],
      },
      {
        // Notifications center (ADMIN only, Set B — Feature 3).
        path: 'notifications',
        component: NotificationsComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Audit log (ADMIN only, Set B — Feature 4).
        path: 'audit',
        component: AuditComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Products (ADMIN + SALESPERSON). A salesperson gets read-only access —
        // the list + product detail — with all mutation affordances hidden in the
        // UI and enforced ADMIN-only on the backend.
        path: 'products',
        component: ProductsComponent,
        canActivate: [salespersonGuard],
      },
      {
        // Inventory / stock management (ADMIN only — the backend inventory
        // endpoints are guarded with hasRole('ADMIN'), Wave 3 Feature 1).
        path: 'inventory',
        component: InventoryComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Suppliers management (ADMIN only, Phase C2).
        path: 'suppliers',
        component: SuppliersComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Purchase orders (ADMIN only, Phase C2).
        path: 'purchase-orders',
        component: PurchaseOrdersComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Expenses (ADMIN + ACCOUNTANT, Phase C3).
        path: 'expenses',
        component: ExpensesComponent,
        canActivate: [accountantGuard],
      },
      {
        // Profit & Loss report (ADMIN + ACCOUNTANT, Phase C3).
        path: 'finance/pnl',
        component: ProfitLossComponent,
        canActivate: [accountantGuard],
      },
      {
        path: 'packing',
        component: ScanComponent,
        canActivate: [packingGuard],
      },
      {
        path: 'reconciliation',
        component: ReconciliationComponent,
        canActivate: [accountantGuard],
      },
      {
        // Reports are guarded for ADMIN + ACCOUNTANT; the backend additionally
        // scopes a salesperson to their own orders if reached directly (Req 20).
        path: 'reports',
        component: ReportsComponent,
        canActivate: [reportsGuard],
      },
      {
        // Company + GST configuration (ADMIN only, Req 5.4).
        path: 'settings',
        component: SettingsComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Staff user management (ADMIN only, Req 5.4).
        path: 'users',
        component: UsersComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Salespeople directory: onboarding profiles + ID verification (ADMIN only).
        path: 'salespeople',
        component: SalespeopleComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Self-service "My Profile" — any authenticated staff member. Changes are
        // submitted for admin approval (not applied directly).
        path: 'my-profile',
        component: MyProfileComponent,
        canActivate: [staffGuard],
      },
      {
        // Admin approval queue for staff profile change requests (ADMIN only).
        path: 'profile-approvals',
        component: ProfileApprovalsComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Database backups: run on-demand + review history (ADMIN only, Req 24).
        path: 'backups',
        component: BackupsComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Payment verification dashboard (PAYMENT_VERIFIER + ADMIN, product-audit §4.4).
        path: 'payments',
        component: PaymentsComponent,
        canActivate: [paymentVerifierGuard],
      },
      {
        // Staff announcement banners (ADMIN only, FEATURE-ROADMAP §8.4).
        path: 'announcements',
        component: AnnouncementsComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Customizable WhatsApp message templates (ADMIN / ACCOUNTANT / TEAM_LEAD, V44).
        path: 'whatsapp-templates',
        component: WhatsappTemplatesComponent,
        canActivate: [whatsappTemplatesGuard],
      },
      {
        // Analytics suite: targets & incentives, retention, forecasting
        // (ADMIN only, FEATURE-ROADMAP §6).
        path: 'analytics',
        component: AnalyticsComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // CA (Chartered Accountant) GST & accounting dashboard (ADMIN + CA).
        path: 'ca/gst',
        component: CaGstDashboardComponent,
        canActivate: [adminOrCaGuard],
      },
      {
        // General Ledger — Chart of Accounts (ADMIN + ACCOUNTANT + CA view; CoA
        // edits ADMIN/ACCOUNTANT-only server-side, CA read-only). Req 16.1–16.3.
        path: 'accounting/chart-of-accounts',
        component: ChartOfAccountsComponent,
        canActivate: [accountingGuard],
      },
      {
        // Manual double-entry voucher entry + reversal (ADMIN/ACCOUNTANT post;
        // CA read-only server-side, entry UI hidden for CA). Req 16.1–16.4.
        path: 'accounting/vouchers/new',
        component: VoucherEntryComponent,
        canActivate: [accountingGuard],
      },
      {
        // Day Book — chronological voucher listing (read). Req 13, 16.1–16.3.
        path: 'accounting/day-book',
        component: DayBookComponent,
        canActivate: [accountingGuard],
      },
      {
        // Trial Balance (read). Req 14, 16.1–16.3, 18.2.
        path: 'accounting/trial-balance',
        component: TrialBalanceComponent,
        canActivate: [accountingGuard],
      },
      {
        // Ledger statement — per-account statement with running balance (read).
        // The account is chosen in-page, so no route param is needed. Req 12.
        path: 'accounting/ledger-statement',
        component: LedgerStatementComponent,
        canActivate: [accountingGuard],
      },
      {
        // Team management: assign salespeople to a team lead (ADMIN only).
        path: 'team',
        component: TeamComponent,
        canActivate: [adminOnlyGuard],
      },
      {
        // Team-lead performance dashboard (TEAM_LEAD + ADMIN).
        path: 'team-performance',
        component: TeamPerformanceComponent,
        canActivate: [teamLeadGuard],
      },
      {
        // Sales leaderboard (ADMIN + SALESPERSON) — moved off the dashboard.
        path: 'leaderboard',
        component: LeaderboardComponent,
        canActivate: [salespersonGuard],
      },
    ],
  },
];
