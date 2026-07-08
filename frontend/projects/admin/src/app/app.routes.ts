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

const LOGIN_PATH = '/login';
const FORBIDDEN_PATH = '/forbidden';

/** Any authenticated staff member may see the dashboard shell. */
export const staffGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.ACCOUNTANT,
  Role.SALESPERSON,
  Role.PACKING_USER,
);

/** Admin-only sections (management/approval/configuration, Req 5.4). */
export const adminOnlyGuard = createRoleGuard(LOGIN_PATH, FORBIDDEN_PATH, Role.ADMIN);

/** Order entry (New Order) is limited to Salesperson and Admin (Req 7). */
export const salespersonGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.SALESPERSON,
);

/** Reconciliation/settlement sections are limited to Accountant and Admin. */
export const accountantGuard = createRoleGuard(
  LOGIN_PATH,
  FORBIDDEN_PATH,
  Role.ADMIN,
  Role.ACCOUNTANT,
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
        component: NewOrderComponent,
        canActivate: [salespersonGuard],
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
        canActivate: [accountantGuard],
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
    ],
  },
];
