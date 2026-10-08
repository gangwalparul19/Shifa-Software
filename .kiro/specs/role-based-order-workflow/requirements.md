# Requirements Document

## Introduction

This feature formalizes and extends the end-to-end operational order workflow for the Shifa OMS
dashboard-only internal application. The goal is to turn today's loosely-connected admin pages into
a coherent, role-driven, mobile-first operational flow that carries an order from lead capture
through admin approval, packing, handover to the delivery courier, courier dispatch, and a defined set
of downstream delivery outcomes — with the right notifications reaching the right people at each step.

The feature is an enhancement, not a rewrite. It reuses and extends the existing modules rather than
introducing parallel ones:

- **`auth`** — RBAC and the existing `Role` enum (`ADMIN`, `SALESPERSON`, `PACKING_USER`, `ACCOUNTANT`); no new role is added.
- **`order`** — order entry, `OrderEntity`, `OrderSource`, and `OrderStatusHistory` (adds a lead-source field).
- **`statemachine`** — `com.shifa.oms.statemachine.OrderStatus` and its legal-transition table
  (adds a `HANDED_TO_DELIVERY` stage plus explicit downstream outcome states and their triggering roles).
- **`notification`** — the WhatsApp (mock) client, templates, and transactional outbox drainer.
- **`mail`** — the email (mock) sender.
- **`adminnotification`** — durable in-app notifications, extended to be addressable per staff role/user.
- **`courier`** — the courier assignment (mock), tracking, and webhook integration that already
  drives status transitions automatically.
- **`audit`** — the "who did what" audit trail.
- **`reporting`** / **`dashboard`** — reporting exports and per-role KPI dashboards.

All external integrations (courier, WhatsApp, email) remain mock/sandbox, consistent with the current
project. Every screen and flow in this feature is **mobile-first**, because all staff roles use the
application primarily on mobile devices.

This document is intentionally scoped to **requirements only** (the "what"). Concrete state-machine
wiring, notification templates, API shapes, and UI layouts belong to the design phase.

## Glossary

- **OMS**: The Shifa Order Management System — the internal dashboard-only application described in `README.md`.
- **System**: The OMS backend and admin application acting together, unless a more specific component is named.
- **Order_Service**: The existing `com.shifa.oms.order` module responsible for order creation, retrieval, and lifecycle updates.
- **State_Machine**: The existing `com.shifa.oms.statemachine` component that owns `OrderStatus` and the legal-transition table.
- **Notification_Service**: The existing `com.shifa.oms.notification` module (WhatsApp mock + transactional outbox) together with the `mail` (email mock) and `adminnotification` (in-app) modules.
- **Outbox**: The existing transactional outbox that persists pending notifications and is drained by background drainers for reliable, retryable delivery.
- **Courier_Service**: The existing `com.shifa.oms.courier` module providing mock courier assignment, tracking, and inbound webhooks.
- **Audit_Service**: The existing `com.shifa.oms.audit` module that records actor, action, and timestamp for state changes.
- **Role**: One of the platform staff roles: `ADMIN`, `SALESPERSON`, `PACKING_USER` (Packer), `ACCOUNTANT`.
- **Admin**: A user holding the `ADMIN` role.
- **Salesperson**: A user holding the `SALESPERSON` role who captures leads and enters orders.
- **Packer**: A user holding the `PACKING_USER` role responsible for packing approved orders, recording the Handover, and dispatching orders to the courier.
- **Accountant**: A user holding the `ACCOUNTANT` role responsible for COD, settlement, receivables, and financial reporting.
- **Lead_Source**: The origin channel of a customer lead: `WHATSAPP`, `INSTAGRAM`, `FACEBOOK`, `GOOGLE`, `OFFLINE`, or `OTHER`.
- **Order_Status**: The lifecycle state of an order, held by `com.shifa.oms.statemachine.OrderStatus`.
- **Role_Dashboard**: The role-specific landing view presented to a staff member after login, tailored to that role's tasks and KPIs.
- **Notification_Matrix**: The defined mapping of lifecycle event → channel(s) → recipient role(s).
- **Terminal_Status**: An `Order_Status` with no outgoing legal transitions (for example `DELIVERED`-derived closed states, `REJECTED`, `CANCELLED`, `RTO`).
- **Handover**: The act of transferring physical custody of a packed order to the delivery courier, recorded by a Packer or Admin and reflected by the `HANDED_TO_DELIVERY` status.
- **Key_Milestone**: A customer-facing lifecycle transition that warrants a customer email — one of Order `APPROVED`, Order `DISPATCHED`, or Order `DELIVERED`.

## Requirements

### Requirement 1: Staff Roles and Role-Based Access Control

**User Story:** As an Admin, I want the system to recognize four operational staff roles with clearly bounded permissions, so that each team member only sees and does what their job requires.

#### Acceptance Criteria

1. THE System SHALL support exactly four operational staff roles: `ADMIN`, `SALESPERSON`, `PACKING_USER`, and `ACCOUNTANT`.
2. THE System SHALL use the existing `com.shifa.oms.auth.Role` enum values `ADMIN`, `SALESPERSON`, `PACKING_USER`, and `ACCOUNTANT` without introducing a new role.
3. WHEN a user without an assigned role attempts to authenticate, THE System SHALL deny access and record an authentication failure.
4. WHERE a staff user holds the `ADMIN` role, THE System SHALL grant access to all operational functions defined in this document.
5. WHEN a staff user requests an action that is not permitted for the user's role, THE System SHALL reject the action with an authorization error and leave the order state unchanged.
6. THE System SHALL enforce each role's permissions on the backend independently of any front-end route guard.
7. WHEN an Admin assigns or changes a staff user's role, THE System SHALL record the change in the Audit_Service with the acting Admin's identity and a timestamp.

### Requirement 2: Role Permission Matrix

**User Story:** As an Admin, I want a single defined permission matrix per role, so that access decisions are consistent across every screen and endpoint.

#### Acceptance Criteria

1. THE System SHALL restrict order creation (lead capture and order entry) to the `SALESPERSON` and `ADMIN` roles.
2. THE System SHALL restrict order approval and rejection to the `ADMIN` role.
3. THE System SHALL restrict marking an order as packed to the `PACKING_USER` and `ADMIN` roles.
4. THE System SHALL restrict recording a Handover and dispatching to a courier to the `PACKING_USER` and `ADMIN` roles.
5. THE System SHALL restrict COD, settlement, receivables, and financial-report functions to the `ACCOUNTANT` and `ADMIN` roles.
6. WHILE a Salesperson is authenticated, THE System SHALL scope order list and report queries to orders that Salesperson created.
7. WHERE a staff user holds a role not listed for a given function, THE System SHALL exclude that function from the user's navigation and reject direct access to the corresponding endpoint.

### Requirement 3: Role-Specific Dashboards

**User Story:** As any staff member, I want a landing dashboard tailored to my role, so that I immediately see the tasks and metrics that matter to me.

#### Acceptance Criteria

1. WHEN a staff user completes authentication, THE System SHALL present a Role_Dashboard that corresponds to that user's role.
2. WHERE the authenticated user is a Salesperson, THE Role_Dashboard SHALL display that Salesperson's own leads and orders grouped by Order_Status, including counts of orders awaiting admin approval.
3. WHERE the authenticated user is an Admin, THE Role_Dashboard SHALL display the count of orders pending approval, orders in each active lifecycle stage, orders in exception states, the queue of packed orders awaiting Handover, and the queue of handed-over orders awaiting dispatch.
4. WHERE the authenticated user is a Packer, THE Role_Dashboard SHALL display the queue of approved orders awaiting packing, the count of orders packed today, the queue of packed orders awaiting Handover, and the queue of handed-over orders awaiting dispatch.
5. WHERE the authenticated user is an Accountant, THE Role_Dashboard SHALL display COD amounts pending collection, settled amounts, and outstanding receivables.
6. WHEN an order's Order_Status changes, THE System SHALL reflect the change in the relevant Role_Dashboard counts on the dashboard's next data refresh.

### Requirement 4: Lead Source Capture

**User Story:** As a Salesperson, I want to record where each lead came from when I enter an order, so that the business can measure which channels produce sales.

#### Acceptance Criteria

1. WHEN a Salesperson creates an order, THE Order_Service SHALL require a Lead_Source value from the set `WHATSAPP`, `INSTAGRAM`, `FACEBOOK`, `GOOGLE`, `OFFLINE`, `OTHER`.
2. IF an order-creation request omits a Lead_Source or supplies a value outside the defined set, THEN THE Order_Service SHALL reject the request with a validation error and SHALL NOT create the order.
3. THE Order_Service SHALL persist the Lead_Source on the order as a field distinct from the existing `OrderSource` provenance field.
4. THE System SHALL retain the recorded Lead_Source for the lifetime of the order so it is available to reporting.
5. WHERE a Salesperson selects the `OTHER` Lead_Source, THE Order_Service SHALL accept an optional free-text note of at most 200 characters describing the source.

### Requirement 5: Salesperson Order Entry

**User Story:** As a Salesperson, I want to punch in an order from customer details I collected over a lead channel, so that the order enters the workflow for approval.

#### Acceptance Criteria

1. WHEN a Salesperson submits an order with customer name, contact number, delivery address, and at least one line item, THE Order_Service SHALL create the order and assign it the initial Order_Status `PENDING_ADMIN_APPROVAL`.
2. IF an order-creation request has no line items, THEN THE Order_Service SHALL reject the request with a validation error.
3. WHEN an order is created, THE Order_Service SHALL record a status-history entry from no prior status to `PENDING_ADMIN_APPROVAL` with the creating Salesperson as the actor.
4. WHEN an order is created, THE Order_Service SHALL associate the order with the creating Salesperson so that ownership scoping and reporting can attribute the order to that Salesperson.
5. THE Order_Service SHALL generate a unique human-readable order code for each created order.

### Requirement 6: Admin Approval and Rejection

**User Story:** As an Admin, I want to review new orders and approve or reject them, so that only valid orders proceed to fulfillment.

#### Acceptance Criteria

1. WHILE an order is in `PENDING_ADMIN_APPROVAL`, THE System SHALL make the order visible in the Admin approval queue.
2. WHEN an Admin approves an order in `PENDING_ADMIN_APPROVAL`, THE State_Machine SHALL transition the order to `APPROVED`.
3. WHEN an Admin rejects an order in `PENDING_ADMIN_APPROVAL`, THE State_Machine SHALL transition the order to `REJECTED`.
4. WHEN an Admin rejects an order, THE Order_Service SHALL require a rejection reason of at least one non-whitespace character and SHALL persist the reason with the order.
5. IF an approval or rejection is requested for an order that is not in `PENDING_ADMIN_APPROVAL`, THEN THE State_Machine SHALL reject the transition and leave the Order_Status unchanged.
6. WHEN an Admin approves or rejects an order, THE Audit_Service SHALL record the Admin's identity, the action, the rejection reason where applicable, and a timestamp.

### Requirement 7: Notifications on Approval

**User Story:** As a Salesperson, I want to be notified when my order is approved and I want the customer informed, so that everyone knows the order is confirmed.

#### Acceptance Criteria

1. WHEN an order transitions to `APPROVED`, THE Notification_Service SHALL enqueue a WhatsApp message to the order's customer confirming the order.
2. WHEN an order transitions to `APPROVED`, THE Notification_Service SHALL enqueue an email to the order's customer confirming the order.
3. WHEN an order transitions to `APPROVED`, THE Notification_Service SHALL create an in-app notification addressed to the Salesperson who created the order, stating that the order was approved.
4. THE Notification_Service SHALL enqueue approval notifications through the existing Outbox so delivery is retryable.
5. IF the customer record has no valid mobile number, THEN THE Notification_Service SHALL skip the WhatsApp message, still deliver the remaining notifications, and record the skip.

### Requirement 8: Packing Stage and Notifications

**User Story:** As a Packer, I want approved orders to appear in my packing queue and to mark them packed, so that fulfillment progresses and stakeholders are informed.

#### Acceptance Criteria

1. WHILE an order is `APPROVED` or `LABEL_GENERATED`, THE System SHALL include the order in the Packer's packing queue.
2. WHEN a Packer confirms an order as packed, THE State_Machine SHALL transition the order to `PACKED`.
3. IF a pack confirmation is requested for an order that is not in a packable status, THEN THE State_Machine SHALL reject the transition and leave the Order_Status unchanged.
4. WHEN an order transitions to `PACKED`, THE Notification_Service SHALL enqueue a WhatsApp message to the customer stating the order is packed.
5. WHEN an order transitions to `PACKED`, THE Notification_Service SHALL create in-app notifications addressed to the Salesperson who created the order and to the Admin role.
6. WHEN an order transitions to `PACKED`, THE Audit_Service SHALL record the Packer's identity and a timestamp.

### Requirement 9: Handover to Delivery Team

**User Story:** As a Packer, I want to hand a packed order over to the delivery courier, so that the order status reflects the handover and the order moves into the awaiting-dispatch queue.

#### Acceptance Criteria

1. THE State_Machine SHALL define a new Order_Status `HANDED_TO_DELIVERY` positioned after `PACKED` and before courier assignment.
2. THE State_Machine SHALL permit the transition from `PACKED` to `HANDED_TO_DELIVERY`.
3. WHEN a Packer or Admin records a Handover for a `PACKED` order, THE State_Machine SHALL transition the order to `HANDED_TO_DELIVERY`.
4. WHILE an order is `HANDED_TO_DELIVERY`, THE System SHALL include the order in the Packer's and Admin's queue of orders awaiting dispatch.
5. THE State_Machine SHALL permit the transition from `HANDED_TO_DELIVERY` to `COURIER_ASSIGNED`.
6. WHEN an order transitions to `HANDED_TO_DELIVERY`, THE Audit_Service SHALL record the acting user's identity and a timestamp.
7. WHEN an order transitions to `HANDED_TO_DELIVERY`, THE Notification_Service SHALL create an in-app notification addressed to the Admin role, and the order SHALL remain visible in the Packer's awaiting-dispatch queue.

### Requirement 10: Dispatch and Courier API Synchronization

**User Story:** As a Packer, I want to dispatch a handed-over order through the courier integration and have our status update automatically, so that tracking stays accurate without manual updates.

#### Acceptance Criteria

1. WHEN a Packer or Admin dispatches a `HANDED_TO_DELIVERY` order, THE Courier_Service SHALL assign a courier and THE State_Machine SHALL transition the order to `COURIER_ASSIGNED`.
2. WHEN a courier confirms pickup for a `COURIER_ASSIGNED` order, THE State_Machine SHALL transition the order to `DISPATCHED`.
3. WHEN the Courier_Service receives a tracking webhook, THE Courier_Service SHALL map the courier status to the corresponding Order_Status and request the matching State_Machine transition automatically.
4. IF a courier assignment attempt fails, THEN THE Courier_Service SHALL leave the order in its pre-dispatch status and record the failure for retry.
5. IF an inbound webhook maps to a transition that is not legal from the order's current status, THEN THE State_Machine SHALL reject the transition and leave the Order_Status unchanged.
6. WHEN an order transitions to `DISPATCHED`, THE Notification_Service SHALL enqueue a WhatsApp message to the customer stating the order was dispatched.
7. WHEN an order transitions to `DISPATCHED`, THE Notification_Service SHALL enqueue an email to the order's customer stating the order was dispatched, because `DISPATCHED` is a Key_Milestone.
8. WHEN an order transitions to `DISPATCHED`, THE Notification_Service SHALL create in-app notifications addressed to the Admin role, the Salesperson who created the order, and the Packer role.

### Requirement 11: Downstream Delivery Statuses

**User Story:** As an Admin, I want out-for-delivery orders to resolve into clearly defined outcomes with the right people notified, so that delivery exceptions are visible and actionable.

#### Acceptance Criteria

1. THE State_Machine SHALL support the downstream outcomes Delivered, Customer-Rejected, Failed-to-Deliver, and Cancelled, reconciled with the existing `DELIVERED`, `RTO`, `REDISPATCH`, and `CANCELLED` states.
2. THE State_Machine SHALL define distinct statuses for a customer refusal and for a failed delivery attempt so the two outcomes are reportable separately.
3. WHEN an order transitions to `OUT_FOR_DELIVERY`, THE Notification_Service SHALL enqueue a WhatsApp message to the customer stating the order is out for delivery.
4. WHEN an order transitions to `DELIVERED`, THE Notification_Service SHALL enqueue a WhatsApp message to the customer, enqueue an email to the customer because `DELIVERED` is a Key_Milestone, and create in-app notifications addressed to the Admin role and the Salesperson who created the order.
5. WHEN an order transitions to a customer-rejected outcome, THE Notification_Service SHALL create in-app notifications addressed to the Admin role, the Salesperson who created the order, and the Accountant role.
6. WHEN an order transitions to a failed-delivery outcome, THE Notification_Service SHALL create an in-app notification addressed to the Admin role and the Packer role.
7. WHEN an order transitions to `CANCELLED`, THE Notification_Service SHALL create in-app notifications addressed to the Admin role and the Salesperson who created the order.
8. IF a delivery outcome transition is requested that is not legal from the order's current status, THEN THE State_Machine SHALL reject the transition and leave the Order_Status unchanged.

### Requirement 12: Order Status Lifecycle and Legal Transitions

**User Story:** As an Admin, I want a single authoritative order lifecycle with defined transitions and the role that triggers each, so that order progression is predictable and enforceable.

#### Acceptance Criteria

1. THE State_Machine SHALL hold each order in exactly one Order_Status at any time.
2. THE State_Machine SHALL assign every new order the initial status `PENDING_ADMIN_APPROVAL`.
3. THE State_Machine SHALL define an explicit legal-transition table that includes the `HANDED_TO_DELIVERY` stage and the downstream delivery outcomes from Requirement 11.
4. IF a transition is requested that is absent from the legal-transition table, THEN THE State_Machine SHALL reject it and leave the Order_Status unchanged.
5. THE State_Machine SHALL associate each defined transition with the Role permitted to trigger it, and THE System SHALL enforce that association.
6. WHEN any transition succeeds, THE Order_Service SHALL append a status-history entry recording the from-status, to-status, actor, and source.
7. THE State_Machine SHALL treat `CLOSED`, `COD_COLLECTED`, `REJECTED`, `CANCELLED`, `RTO`, and the customer-rejected and failed-delivery terminal outcomes as Terminal_Status values with no outgoing transitions except where an existing state (such as `DELIVERED`) already defines settlement transitions.

### Requirement 13: Notification Matrix

**User Story:** As an Admin, I want one defined matrix of which event triggers which channel to which recipient role, so that notifications are complete and consistent.

#### Acceptance Criteria

1. THE System SHALL define a Notification_Matrix mapping each lifecycle event to its channels (WhatsApp, email, in-app) and recipient roles.
2. WHEN a lifecycle event defined in the Notification_Matrix occurs, THE Notification_Service SHALL enqueue exactly the notifications specified by the matrix for that event.
3. THE Notification_Matrix SHALL address customer-facing messages to the order's customer via WhatsApp or email and staff-facing messages to the relevant Role via in-app notification.
4. WHERE a recipient role has more than one active user, THE Notification_Service SHALL make the in-app notification available to every active user holding that role.
5. THE Notification_Matrix SHALL enqueue a customer email only on the Key_Milestone transitions `APPROVED`, `DISPATCHED`, and `DELIVERED`, and SHALL NOT enqueue a customer email on any other lifecycle transition.
6. WHEN a lifecycle transition other than a Key_Milestone occurs, THE Notification_Service SHALL enqueue customer WhatsApp messages and staff in-app notifications as specified by the per-event requirements without enqueuing a customer email.
7. THE System SHALL make the Notification_Matrix the single source of truth referenced by Requirements 7, 8, 10, and 11.

### Requirement 14: Notification Delivery Reliability

**User Story:** As an Admin, I want failed notification deliveries to be retried automatically, so that transient outages do not silently drop customer or staff messages.

#### Acceptance Criteria

1. WHEN a notification is generated, THE Notification_Service SHALL persist the notification to the Outbox within the same transaction as the triggering status change.
2. WHILE an Outbox entry has not been successfully delivered, THE Notification_Service SHALL retry delivery on the existing drainer schedule.
3. IF a delivery attempt fails, THEN THE Notification_Service SHALL retain the Outbox entry and record the failure without losing the entry.
4. WHEN a delivery attempt succeeds, THE Notification_Service SHALL mark the Outbox entry delivered so it is not sent again.
5. IF an Outbox entry exceeds the configured maximum retry attempts, THEN THE Notification_Service SHALL mark the entry as failed and create an in-app notification addressed to the Admin role.

### Requirement 15: Audit Trail

**User Story:** As an Admin, I want every workflow action recorded with who did it and when, so that I can trace the full history of any order.

#### Acceptance Criteria

1. WHEN any Order_Status transition occurs, THE Audit_Service SHALL record the order identifier, from-status, to-status, acting user identity, and a timestamp.
2. WHEN an Admin approves or rejects an order, THE Audit_Service SHALL record the action and, for a rejection, the rejection reason.
3. WHEN a staff user's role is assigned or changed, THE Audit_Service SHALL record the change with the acting Admin's identity and a timestamp.
4. THE Audit_Service SHALL retain audit entries so they remain available for later review by an Admin.
5. WHERE an action is performed automatically by the System (for example a courier webhook transition), THE Audit_Service SHALL record the source as the System rather than a human user.

### Requirement 16: Operational Reporting

**User Story:** As an Admin or Accountant, I want reports across lead source, status, salesperson, and delivery outcome, so that I can measure channel performance and fulfillment health.

#### Acceptance Criteria

1. WHEN an Admin or Accountant requests an orders-by-lead-source report, THE Reporting_Service SHALL return order counts grouped by Lead_Source over the requested date range.
2. WHEN an Admin or Accountant requests an orders-by-status report, THE Reporting_Service SHALL return order counts grouped by Order_Status.
3. WHEN an Admin or Accountant requests an orders-by-salesperson report, THE Reporting_Service SHALL return order counts grouped by the creating Salesperson.
4. WHEN an Admin or Accountant requests a delivery-outcome report, THE Reporting_Service SHALL return the counts of delivered, customer-rejected, failed-to-deliver, and cancelled orders and the delivery success rate over the requested date range.
5. WHILE a Salesperson requests a report, THE Reporting_Service SHALL scope results to orders that Salesperson created.

### Requirement 17: Mobile-First Experience (Cross-Cutting)

**User Story:** As any staff member using the app on a phone, I want every screen designed for mobile first, so that I can work efficiently on a small touch screen.

#### Acceptance Criteria

1. THE System SHALL render every workflow screen in a single-column, mobile-first layout that remains usable at a viewport width of 360 CSS pixels without horizontal scrolling.
2. THE System SHALL present interactive controls with touch targets of at least 44 by 44 CSS pixels.
3. THE System SHALL present order collections as cards rather than wide multi-column tables on viewports narrower than 768 CSS pixels.
4. THE System SHALL provide primary navigation through a compact or bottom navigation pattern reachable without horizontal scrolling on mobile viewports.
5. WHERE a screen would otherwise require a wide table, THE System SHALL prioritize the fields most relevant to the acting role and defer secondary fields behind a detail view.
6. THE System SHALL apply the existing mobile density conventions (count cards three-per-row, money cards two-per-row, grouped under section headers) consistent with the current admin dashboard.
