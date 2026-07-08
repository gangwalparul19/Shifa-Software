# Requirements Document

## Introduction

This feature is a mobile-first UI/UX redesign of the existing Shifa OMS admin application. It
re-presents the current pages to match the layout patterns in the client-provided wireframe
(`docs/wireframe.jpeg`) while using our own Shifa herbal product catalog and existing product
images — the wireframe's shoes/watches are mockup filler only.

This is a **presentation and navigation restructure**, not new business logic. It reuses the
existing backend APIs, pages, role model (`ADMIN`, `SALESPERSON`, `PACKING_USER`, `ACCOUNTANT`),
and the retained order lifecycle. No new backend endpoints or data are added. The existing Tabler
theme, Shifa green (`#1F5D3F`), and ApexCharts are reused; this is styling + navigation + component
layout work.

The headline change is the app shell: a persistent **bottom tab bar** carrying the four most-used
destinations for the signed-in user's role, with everything else — including the full detailed
dashboard — reached from a **top-bar hamburger menu**. Every screen is designed mobile-first and
remains usable on larger viewports.

All existing capabilities and role-based access must be preserved; the redesign only changes how
they are presented and navigated.

## Glossary

- **OMS**: The Shifa Order Management System — the internal admin application in `frontend/projects/admin`.
- **System**: The admin application (and the backend it already calls) acting together.
- **Role**: One of the operational staff roles: `ADMIN`, `SALESPERSON`, `PACKING_USER`, `ACCOUNTANT`.
- **App_Shell**: The persistent chrome — top app bar, hamburger menu, and bottom tab bar — that wraps every authenticated screen.
- **Bottom_Tab_Bar**: The persistent bottom navigation showing the four most-used destinations for the current Role.
- **Hamburger_Menu**: The top-bar menu that exposes the full navigation, including destinations not on the Bottom_Tab_Bar and the Detailed_Dashboard.
- **Home_Summary**: The concise, role-specific landing view reached from the Bottom_Tab_Bar.
- **Detailed_Dashboard**: The full dashboard (complete KPIs, charts, and all fulfilment queues) reached from the Hamburger_Menu.
- **KPI_Tile**: A compact metric card showing a value and, where applicable, a change indicator, laid out per the wireframe (two per row on mobile).
- **Status_Badge**: A colored label communicating an order's lifecycle state.
- **FAB**: A floating action button for the primary create action on a list screen.
- **Wireframe**: The client-provided reference image at `docs/wireframe.jpeg`.

## Requirements

### Requirement 1: Mobile-First App Shell

**User Story:** As any staff member using the app on a phone, I want a consistent mobile app shell, so that navigation and page chrome feel native and are reachable with one hand.

#### Acceptance Criteria

1. THE App_Shell SHALL present a top app bar containing the current screen title, a Hamburger_Menu trigger, and role/account access.
2. THE App_Shell SHALL present a persistent Bottom_Tab_Bar on authenticated screens.
3. THE App_Shell SHALL render in a single-column, mobile-first layout usable at a viewport width of 360 CSS pixels without horizontal scrolling.
4. THE App_Shell SHALL remain usable on viewports wider than 768 CSS pixels by adapting the same navigation model responsively.
5. WHERE a user is not authenticated, THE System SHALL NOT display the Bottom_Tab_Bar or Hamburger_Menu.
6. THE App_Shell SHALL reuse the existing Tabler theme and Shifa green brand color.

### Requirement 2: Role-Aware Bottom Tab Bar

**User Story:** As a staff member, I want the bottom bar to show the four things I use most for my role, so that my common tasks are one tap away.

#### Acceptance Criteria

1. THE Bottom_Tab_Bar SHALL display exactly four destinations for the authenticated user's Role.
2. WHEN the authenticated user is a `SALESPERSON`, THE Bottom_Tab_Bar SHALL present New Order, Orders, Customers, and Products.
3. WHEN the authenticated user is a `PACKING_USER`, THE Bottom_Tab_Bar SHALL present Packing (scan), Handover, Dispatch, and Orders.
4. WHEN the authenticated user is an `ACCOUNTANT`, THE Bottom_Tab_Bar SHALL present Reconciliation, Reports, Expenses, and Orders.
5. WHEN the authenticated user is an `ADMIN`, THE Bottom_Tab_Bar SHALL present Approvals, Orders, Products, and Reports.
6. WHEN a user selects a Bottom_Tab_Bar destination, THE System SHALL navigate to it and indicate the active tab.
7. THE System SHALL only place destinations on the Bottom_Tab_Bar that the Role is authorized to access.

### Requirement 3: Hamburger Menu (Full Navigation)

**User Story:** As a staff member, I want a hamburger menu with everything else, so that less-frequent pages and the detailed dashboard are always reachable without cluttering the bottom bar.

#### Acceptance Criteria

1. WHEN a user opens the Hamburger_Menu, THE System SHALL list every destination the user's Role is authorized to access, including those already on the Bottom_Tab_Bar.
2. THE Hamburger_Menu SHALL include an entry for the Detailed_Dashboard.
3. THE Hamburger_Menu SHALL include the administrative and lower-frequency destinations not shown on the Bottom_Tab_Bar (for example Detailed_Dashboard, Settings, Users, Audit, Inventory, Suppliers, Purchase Orders, Returns, Notifications), subject to Role authorization.
4. WHERE a destination is not permitted for the user's Role, THE System SHALL omit it from the Hamburger_Menu.
5. WHEN a user selects a Hamburger_Menu destination, THE System SHALL navigate to it and close the menu.
6. THE Hamburger_Menu SHALL be dismissible without navigating (close/back/overlay tap).

### Requirement 4: Role Home Summary

**User Story:** As a staff member, I want a concise home summary when I open the app, so that I see my most relevant figures without the full dashboard.

#### Acceptance Criteria

1. WHEN an authenticated user lands on the Home_Summary, THE System SHALL present a concise, role-appropriate set of KPI_Tiles and the queues most relevant to that Role.
2. THE Home_Summary SHALL source its figures from the existing role dashboard summary API without introducing a new backend endpoint.
3. THE Home_Summary SHALL lay out KPI_Tiles at two per row on mobile viewports, consistent with the Wireframe.
4. THE Home_Summary SHALL provide navigation to the Detailed_Dashboard for the full breakdown.
5. WHERE the Role is `SALESPERSON`, THE Home_Summary SHALL scope all figures to that salesperson's own orders.

### Requirement 5: Detailed Dashboard

**User Story:** As an Admin, I want the full detailed dashboard under the hamburger, so that I can see the most accurate and complete operational picture when I need it.

#### Acceptance Criteria

1. WHEN a user opens the Detailed_Dashboard from the Hamburger_Menu, THE System SHALL present the complete role dashboard (full KPIs, charts, and all fulfilment queues) already provided by the application.
2. THE Detailed_Dashboard SHALL present KPI_Tiles and charts using the existing ApexCharts components in a mobile-first layout.
3. THE Detailed_Dashboard SHALL reflect operational OMS metrics (for example pending approval, packed, in-transit, delivered, COD outstanding, revenue) rather than storefront-style metrics.
4. WHILE data is loading, THE System SHALL present a non-blocking loading state.
5. WHERE a metric has no data, THE System SHALL render a zero/empty state rather than an error.

### Requirement 6: Orders List (Mobile Card + Filters)

**User Story:** As a staff member, I want a searchable, filterable orders list as cards, so that I can find and triage orders on a phone.

#### Acceptance Criteria

1. THE Orders list SHALL present orders as cards on viewports narrower than 768 CSS pixels, each showing order code, customer, amount, and a Status_Badge.
2. THE Orders list SHALL provide a search control over the existing order search fields.
3. THE Orders list SHALL provide status filter tabs mapped to the OMS OrderStatus lifecycle (for example All, Processing/active, Completed/delivered, Cancelled/exception).
4. WHERE the user is a `SALESPERSON`, THE Orders list SHALL show only that salesperson's own orders.
5. WHEN a user selects an order, THE System SHALL navigate to its Order Details.
6. THE Status_Badge SHALL use distinct colors per lifecycle group (for example in-progress, success, exception).

### Requirement 7: Order Details

**User Story:** As a staff member, I want a mobile order detail screen, so that I can see everything about an order and act on it.

#### Acceptance Criteria

1. THE Order Details screen SHALL display the Status_Badge, order code, customer name and contact, delivery address, order items, and the totals breakdown (subtotal, discount, shipping/other, total).
2. WHERE a customer mobile number is present, THE Order Details screen SHALL offer a one-tap call action.
3. THE Order Details screen SHALL display the payment method and status.
4. THE Order Details screen SHALL provide a Download/View Invoice action using the existing invoice endpoint.
5. WHERE the acting Role is permitted a lifecycle action on the order (for example approve/reject, pack, handover, dispatch), THE Order Details screen SHALL surface that action.
6. THE Order Details screen SHALL present its content in a single-column, mobile-first layout.

### Requirement 8: Products List

**User Story:** As an Admin, I want a searchable, filterable product list with quick add, so that I can manage the herbal catalog on mobile.

#### Acceptance Criteria

1. THE Products list SHALL present products as rows/cards showing the product image, name, category, stock, and price, using our Shifa catalog and existing images.
2. THE Products list SHALL provide a search control and filter tabs (for example All, Active/Published, Inactive/Hidden, Low Stock).
3. THE Products list SHALL present a FAB for the create-product action.
4. WHEN a user selects a product, THE System SHALL navigate to its Product Details.
5. WHERE the user's Role is not permitted to manage products, THE System SHALL not present create/edit affordances.

### Requirement 9: Product Details

**User Story:** As an Admin, I want a mobile product detail screen, so that I can review and edit a product and see how it sells.

#### Acceptance Criteria

1. THE Product Details screen SHALL display the product image, name, SKU, active/visibility badge, price, stock, category, and description.
2. THE Product Details screen SHALL present concise sales/stock stats for the product where available.
3. WHERE the user's Role permits it, THE Product Details screen SHALL provide an edit action.
4. THE Product Details screen SHALL present its content in a single-column, mobile-first layout using the existing product image.

### Requirement 10: Add New Order (Mobile Form)

**User Story:** As a Salesperson, I want a clean sectioned mobile order-entry form, so that I can punch in an order quickly on my phone.

#### Acceptance Criteria

1. THE Add New Order screen SHALL group inputs into Customer details, Order details, and Payment details sections, per the Wireframe.
2. THE Add New Order screen SHALL provide product search, a quantity stepper, and price/discount inputs for order lines, reusing the existing order-entry product picker.
3. THE Add New Order screen SHALL include the existing Lead Source selection (and its optional note) required by order entry.
4. THE Add New Order screen SHALL provide Cancel and Save actions, and on save SHALL submit to the existing create-order API.
5. WHERE required fields are missing or invalid, THE System SHALL show inline validation and SHALL NOT submit.
6. THE Add New Order screen SHALL present controls with touch targets of at least 44 by 44 CSS pixels.

### Requirement 11: Customers List

**User Story:** As an Admin or Accountant, I want a mobile customers list, so that I can look up a customer and their order history.

#### Acceptance Criteria

1. THE Customers list SHALL present customers as rows with an avatar-initial, name, phone, orders count, and total spent, sourced from the existing customers API.
2. THE Customers list SHALL provide a search control.
3. WHEN a user selects a customer, THE System SHALL present that customer's detail/history using existing data.
4. WHERE the user's Role is not permitted to view customers, THE System SHALL not expose the Customers destination.

### Requirement 12: Reports (Mobile)

**User Story:** As an Admin or Accountant, I want mobile-friendly reports, so that I can review performance on a phone.

#### Acceptance Criteria

1. THE Reports screen SHALL provide filter tabs (for example Overview, Sales, Products, Customers) over the existing report data.
2. THE Reports screen SHALL present KPI_Tiles and at least one chart (for example a revenue trend) using ApexCharts.
3. THE Reports screen SHALL reuse the existing report endpoints, including the reports added for the order workflow (by lead source, status, salesperson, delivery outcome).
4. WHERE the user is a `SALESPERSON` reaching a report, THE System SHALL scope results to that salesperson's own orders.

### Requirement 13: Preserve Functionality and Access Control

**User Story:** As a product owner, I want the redesign to preserve every capability and access rule, so that no feature is lost in the visual refresh.

#### Acceptance Criteria

1. THE System SHALL retain access to every destination and action available before the redesign, either on the Bottom_Tab_Bar or in the Hamburger_Menu.
2. THE System SHALL continue to enforce role-based access exactly as before, with the backend remaining the source of truth for authorization.
3. THE System SHALL NOT add, remove, or alter any backend endpoint as part of this redesign.
4. WHERE a page existed before the redesign, THE System SHALL provide an equivalent redesigned page reachable by an authorized user.

### Requirement 14: Mobile-First Cross-Cutting Standards

**User Story:** As any staff member on a phone, I want every screen to meet consistent mobile standards, so that the whole app is comfortable on a small touch screen.

#### Acceptance Criteria

1. THE System SHALL render every redesigned screen in a single-column layout usable at 360 CSS pixels wide without horizontal scrolling.
2. THE System SHALL present interactive controls with touch targets of at least 44 by 44 CSS pixels.
3. THE System SHALL present collections (orders, products, customers) as cards rather than wide multi-column tables on viewports narrower than 768 CSS pixels.
4. THE System SHALL keep the Bottom_Tab_Bar reachable without horizontal scrolling on mobile viewports.
5. THE System SHALL preserve any existing offline-capable behavior (service worker / PWA) that the application already provides.
6. THE System SHALL apply the existing mobile density conventions (compact KPI tiles two-or-three per row, grouped under section headers) consistent with the current app.
