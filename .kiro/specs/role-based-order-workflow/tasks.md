# Implementation Plan: Role-Based Order Workflow

## Overview

This plan enhances the existing Shifa OMS modular monolith (Java 21 / Spring Boot 3.3.5 backend,
Angular 21 admin app) to deliver a role-driven, mobile-first order workflow. All changes are
additive and build on modules that already run end to end. The sequencing takes the backend domain
first (data model + migrations → state machine → central workflow service → notifications), then
the API endpoints, then the frontend, with tests placed alongside each unit of work.

Property-based tests (jqwik, already on the classpath) implement the 20 Correctness Properties from
`design.md §Correctness Properties`. Every PBT sub-task:

- runs a **minimum of 100 iterations** (`@Property(tries = 100)` or higher for state-machine walks),
- is tagged with a comment in the format `Feature: role-based-order-workflow, Property {n}: {text}`,
- exercises the pure component **in-memory** (no Spring/DB), and
- respects the **Java 25 runtime gotcha**: Mockito cannot mock concrete classes on this JVM — use
  real instances or small recording subclasses (e.g. a recording `OutboxEventPublisher`), never
  mocks of concrete classes.

## Tasks

- [x] 1. Data model and Flyway migrations
  - [x] 1.1 Add `LeadSource` enum
    - Create `com.shifa.oms.order.LeadSource` with values `WHATSAPP, INSTAGRAM, FACEBOOK, GOOGLE, OFFLINE, OTHER`, beside `OrderSource`
    - _Requirements: 4.1, 4.3_
    - _Design: §3.1, Components and Interfaces (new components)_

  - [x] 1.2 Add migration `V23__order_lead_source_and_customer_email.sql`
    - Add nullable `orders.lead_source VARCHAR(20)`, `orders.lead_source_note VARCHAR(200)`, `orders.customer_email VARCHAR(150)`; add index `ix_orders_lead_source`
    - Follow the project rule: new versioned migration only, never edit an applied one
    - _Requirements: 4.3, 4.4, 4.5_
    - _Design: §3.1, §3.4_

  - [x] 1.3 Add migration `V24__admin_notification_recipient_addressing.sql`
    - Add nullable `admin_notifications.recipient_role VARCHAR(20)`, `admin_notifications.recipient_user_id BIGINT`; add index `ix_admin_notifications_recipient`
    - _Requirements: 13.3, 13.4_
    - _Design: §3.3, §3.4_

  - [x] 1.4 Map new order fields on `OrderEntity`
    - Add `leadSource` (`@Enumerated(EnumType.STRING)`), `leadSourceNote`, `customerEmail` fields with JPA column mappings matching V23
    - _Requirements: 4.3, 4.4_
    - _Design: §3.1_

  - [x] 1.5 Map recipient addressing on `AdminNotification`
    - Add `recipientRole` and `recipientUserId` fields/columns to the `AdminNotification` entity to match V24; keep NULL/NULL rows as legacy admin broadcasts
    - _Requirements: 13.3, 13.4_
    - _Design: §3.3_

  - [x]* 1.6 Write integration test for migrations V23/V24 and Hibernate validation
    - Assert V23/V24 apply cleanly on the seeded V22 dataset and Hibernate `validate` passes against the new columns
    - _Requirements: 4.4, 13.3_
    - _Design: §10.3 (Flyway migrations V23/V24)_

- [x] 2. Order status state machine and transition authority
  - [x] 2.1 Extend `OrderStatus` with new states and transition table
    - Add enum values `HANDED_TO_DELIVERY`, `CUSTOMER_REJECTED`, `DELIVERY_FAILED`
    - Extend `buildTransitions()` per the §4.1 table (add `PACKED→HANDED_TO_DELIVERY`, `HANDED_TO_DELIVERY→COURIER_ASSIGNED`, `HANDED_TO_DELIVERY→HANDED_TO_DELIVERY`, `OUT_FOR_DELIVERY→{CUSTOMER_REJECTED,DELIVERY_FAILED}`); move courier assignment gate off `PACKED`
    - Classify `CUSTOMER_REJECTED` and `DELIVERY_FAILED` as terminal (empty `allowedTargets`)
    - _Requirements: 9.1, 9.2, 9.5, 11.1, 11.2, 12.1, 12.2, 12.3, 12.7_
    - _Design: §2.1, §4.1_

  - [x]* 2.2 Write property tests for state machine legality, single-status, and terminals
    - **Property 1: An order is always in exactly one status** — _Validates: Requirements 12.1_
    - **Property 3: Transition legality matches the specified table** — _Validates: Requirements 6.5, 8.3, 10.5, 11.8, 12.3, 12.4_
    - **Property 6: Terminal states have no outgoing transitions** — _Validates: Requirements 12.7_
    - Use a random-walk generator over the §4.1 table; ≥100 iterations; tag each `@Property`; pure in-memory, no mocks of concrete classes
    - _Design: §Correctness Properties (1, 3, 6), §10.1_

  - [x] 2.3 Add pure `TransitionAuthority` component
    - Create `com.shifa.oms.statemachine.TransitionAuthority` holding `(from,to) → Set<Role>` (+ `SYSTEM`) per §4.1; expose `assertAuthorized(from,to,role)` and `permits(from,to,role)`
    - _Requirements: 1.5, 2.1, 2.2, 2.3, 2.4, 2.5, 12.5_
    - _Design: §4.2, Components and Interfaces (new components)_

  - [x]* 2.4 Write property test for role authorization
    - **Property 4: Every transition is authorized by role** — _Validates: Requirements 1.5, 2.1, 2.2, 2.3, 2.4, 2.5, 12.5_
    - Generate random legal `(from,to)` pairs and random `Role`; assert applied iff role permitted (SYSTEM for courier edges); ≥100 iterations; tag; pure, no concrete-class mocks
    - _Design: §Correctness Properties (4), §10.1_

- [x] 3. Central OrderWorkflowService and service refactors
  - [x] 3.1 Implement `OrderWorkflowService.applyTransition(order, target, actor)`
    - Centralize authorize (`TransitionAuthority`) → validate/apply (`OrderStatusStateMachine`) → persist status + one `status_history` row → write audit entry; record System actor for automatic transitions
    - _Requirements: 12.5, 12.6, 15.1, 15.5_
    - _Design: §2.2, §4.2, §4.3, Components and Interfaces (new components)_

  - [x]* 3.2 Write property test for history append
    - **Property 5: Each successful transition appends exactly one history row** — _Validates: Requirements 12.6, 15.1, 15.5_
    - Assert exactly one row with from/to/actor/source; System source for courier-driven edges; ≥100 iterations; tag; use a recording persistence stub, not concrete-class mocks
    - _Design: §Correctness Properties (5), §10.1_

  - [x] 3.3 Refactor `AdminOrderService` to route approve/reject through the workflow service
    - Approve `PENDING_ADMIN_APPROVAL→APPROVED` (auto `LABEL_GENERATED`) and reject `→REJECTED` (non-blank reason, persisted) via `OrderWorkflowService`; keep `ORDER_APPROVED`/`ORDER_REJECTED` audit
    - _Requirements: 6.2, 6.3, 6.4, 6.5, 6.6_
    - _Design: §6.2, §2.2_

  - [x]* 3.4 Write property test for rejection reason
    - **Property 12: Rejection requires and stores a non-blank reason** — _Validates: Requirements 6.4_
    - Accept iff reason has ≥1 non-whitespace char; persisted when accepted, status unchanged when rejected; ≥100 iterations; tag; no concrete-class mocks
    - _Design: §Correctness Properties (12), §10.1_

  - [x] 3.5 Refactor `PackingService`, `CourierAssignmentService`, `CourierStatusApplier` to route through the workflow service
    - Replace each service's local `applyTransition(...)` helper with calls to `OrderWorkflowService` so role checks and notifications live in one place (behavior-preserving; new endpoints/gating come in task 6)
    - _Requirements: 12.5, 12.6_
    - _Design: §2.2, Components and Interfaces (changed components)_

- [x] 4. Notification layer (matrix, email channel, staff fan-out)
  - [x] 4.1 Add `NotificationEvent` values and pure `NotificationMatrix`
    - Add `APPROVED` and `PACKED` to `NotificationEvent` (extend `fromOrderStatus`); create `com.shifa.oms.notification.NotificationMatrix` as a table-driven `event → Set<NotificationSpec(channel, recipient)>` lookup encoding §5.1
    - _Requirements: 13.1, 13.2, 13.3, 13.7_
    - _Design: §5.1, Components and Interfaces (new/changed components)_

  - [x]* 4.2 Write property test for the matrix enqueue set
    - **Property 16: The enqueued notification set equals the matrix** — _Validates: Requirements 7.1, 7.2, 7.3, 8.4, 8.5, 9.7, 10.6, 10.7, 10.8, 11.3, 11.4, 11.5, 11.6, 11.7, 13.2, 13.3_
    - Assert enqueued set equals `NotificationMatrix(event)` against an in-memory recorder; ≥100 iterations; tag; no concrete-class mocks
    - _Design: §Correctness Properties (16), §10.1_

  - [x]* 4.3 Write property test for milestone email rule
    - **Property 17: Customer email is enqueued iff the event is a key milestone** — _Validates: Requirements 13.5, 13.6_
    - Email present iff event ∈ `{APPROVED, DISPATCHED, DELIVERED}`; ≥100 iterations; tag; pure, no concrete-class mocks
    - _Design: §Correctness Properties (17), §10.1_

  - [x] 4.4 Add `MailNotificationPublisher` and `EMAIL_NOTIFY` outbox event
    - Add `EMAIL_NOTIFY` outbox event type; create `MailNotificationPublisher.enqueue(...)` mirroring `WhatsAppNotificationPublisher`, resolving a plain-text milestone email; skip and record when `customer_email` is absent
    - _Requirements: 7.2, 7.5, 10.7, 11.4, 14.1_
    - _Design: §5.2, Components and Interfaces (new components)_

  - [x] 4.5 Add `EmailOutboxDrainer`
    - Mirror `WhatsAppOutboxDrainer`: keep `PENDING` with incremented `attempts`/`last_error`/backoff on failure, mark `SENT` once on success, mark `FAILED` and raise an ADMIN-role in-app notification on exhausting `maxAttempts`
    - _Requirements: 14.2, 14.3, 14.4, 14.5_
    - _Design: §5.2_

  - [x]* 4.6 Write property test for outbox delivery lifecycle
    - **Property 20: The outbox delivery lifecycle is safe and retryable** — _Validates: Requirements 14.1, 14.2, 14.3, 14.4, 14.5_
    - Drive a sequence of send outcomes; assert enqueue-in-txn, retain-on-fail, once-on-success, FAILED+admin-alert on exhaustion; ≥100 iterations; tag; use a recording sender, not concrete-class mocks
    - _Design: §Correctness Properties (20), §10.1_

  - [x] 4.7 Add `StaffNotificationDispatcher` fan-out and carry salesperson id in payloads
    - Create `com.shifa.oms.adminnotification.StaffNotificationDispatcher` writing role-addressed / user-addressed `AdminNotification` rows (composite de-dup on `source_event_id + recipient_role + recipient_user_id`); add per-user query and a staff-facing `GET /api/notifications`; extend `OutboxEventPublisher` order-scoped payloads with `salespersonUserId` from `OrderEntity.createdBy`
    - _Requirements: 13.3, 13.4_
    - _Design: §5.2, §6 (AdminNotificationController), Components and Interfaces (new/changed components)_

  - [x]* 4.8 Write property test for role-addressed fan-out
    - **Property 19: Role-addressed in-app notifications reach exactly that role** — _Validates: Requirements 13.4_
    - Generate active/inactive user populations; assert visible to exactly the active users of the role; ≥100 iterations; tag; no concrete-class mocks
    - _Design: §Correctness Properties (19), §10.1_

  - [x] 4.10 Wire `NotificationMatrix` into `OrderWorkflowService`
    - After each successful transition, consult the matrix and enqueue exactly its set through the WhatsApp/email publishers and staff dispatcher in the same transaction as the status change
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 8.4, 8.5, 9.7, 10.6, 10.7, 10.8, 11.3, 11.4, 11.5, 11.6, 11.7, 13.2, 14.1_
    - _Design: §2.2, §5.2_

  - [x]* 4.9 Write property test for missing-contact channel skip
    - **Property 18: Missing contact info skips only that customer channel** — _Validates: Requirements 7.5_
    - Missing mobile skips only WhatsApp, missing email skips only email; all other notifications still enqueue and the skip is recorded; ≥100 iterations; tag; no concrete-class mocks
    - _Design: §Correctness Properties (18), §10.1_

- [x] 5. Lead source capture in order entry
  - [x] 5.1 Extend order creation with lead source
    - Add `leadSource` (required enum), `leadSourceNote` (optional, ≤200 chars, only for `OTHER`), and optional `customerEmail` to `CreateOrderRequest`; validate presence/membership/note-length in `OrderService.createSalespersonOrder`; persist distinctly from `OrderSource`; include lead source in `OrderResponse`; keep initial status `PENDING_ADMIN_APPROVAL` with a `from=null` creation history row and `createdBy` set
    - _Requirements: 4.1, 4.2, 4.3, 4.5, 5.1, 5.2, 5.3, 5.4, 5.5_
    - _Design: §3.1, §6.1_

  - [x]* 5.2 Write property test for lead source validation
    - **Property 8: Lead source is validated against the defined set** — _Validates: Requirements 4.1, 4.2, 4.5_
    - Accept only in-set `leadSource`; `OTHER` note accepted iff ≤200 chars; ≥100 iterations; tag; no concrete-class mocks
    - _Design: §Correctness Properties (8), §10.1_

  - [x]* 5.3 Write property test for deterministic creation
    - **Property 2: Creation initializes the lifecycle deterministically** — _Validates: Requirements 5.1, 5.3, 5.4, 12.2_
    - Assert `PENDING_ADMIN_APPROVAL`, correct `createdBy`, single `from=null` creation history row; ≥100 iterations; tag; no concrete-class mocks
    - _Design: §Correctness Properties (2), §10.1_

  - [x]* 5.4 Write property test for lead source round-trip
    - **Property 9: Lead source persists distinctly and survives round-trip** — _Validates: Requirements 4.3, 4.4_
    - Reload yields same `leadSource`/note; `OrderSource` unchanged; fields never alias; ≥100 iterations; tag; no concrete-class mocks
    - _Design: §Correctness Properties (9), §10.1_

  - [x]* 5.5 Write property test for order code uniqueness
    - **Property 10: Order codes are unique** — _Validates: Requirements 5.5_
    - All generated order codes pairwise distinct; ≥100 iterations; tag; no concrete-class mocks
    - _Design: §Correctness Properties (10), §10.1_

  - [x]* 5.6 Write property test for line-item requirement
    - **Property 11: Orders require at least one line item** — _Validates: Requirements 5.2_
    - Empty line-item list rejected with validation error, nothing persisted; ≥100 iterations; tag; no concrete-class mocks
    - _Design: §Correctness Properties (11), §10.1_

- [x] 6. Checkpoint - backend domain and notifications
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Handover and dispatch endpoints, courier gating
  - [x] 7.1 Add handover action and remove immediate courier publish from pack
    - Add `PackingService.handover(id, actor)` and `POST /api/packing/{id}/handover` (`hasAnyRole('PACKING_USER','ADMIN')`) transitioning `PACKED→HANDED_TO_DELIVERY`; remove the `COURIER_ASSIGN` publish from `scan(...)`; 409 when not `PACKED`
    - _Requirements: 9.2, 9.3, 9.4, 9.6, 9.7, 2.4_
    - _Design: §6.3, §6.4_

  - [x] 7.2 Add dispatch action
    - Add `PackingService.dispatch(id, actor)` and `POST /api/packing/{id}/dispatch` (`hasAnyRole('PACKING_USER','ADMIN')`) that enqueues the `COURIER_ASSIGN` outbox event for a `HANDED_TO_DELIVERY` order
    - _Requirements: 10.1, 2.4_
    - _Design: §6.5_

  - [x] 7.3 Gate `CourierAssignmentService` on `HANDED_TO_DELIVERY`
    - Change the assignment gate from `PACKED` to `HANDED_TO_DELIVERY`; advance `HANDED_TO_DELIVERY→COURIER_ASSIGNED`; on courier error roll back and retain `HANDED_TO_DELIVERY`, keeping the event `PENDING` for retry
    - _Requirements: 10.1, 10.4, 9.5_
    - _Design: §6.5, Error Handling (courier assignment failure)_

  - [x] 7.4 Add courier tokens for the two new outcomes
    - Extend `CourierStatusMapper` with `customer_rejected|refused|rejected → CUSTOMER_REJECTED` and `delivery_failed|failed|undelivered|attempt_failed → DELIVERY_FAILED`; `CourierStatusApplier` applies only legal mapped transitions and fires matrix notifications for the resulting event
    - _Requirements: 10.3, 10.5, 11.1, 11.2_
    - _Design: §6.6, §4.3_

  - [x]* 7.5 Write property test for courier status mapping
    - **Property 15: Courier status mapping is total over its vocabulary** — _Validates: Requirements 10.3_
    - Recognised tokens (case/separator-insensitive) map to specified status incl. new outcomes; unrecognised/blank → empty; ≥100 iterations; tag; pure, no concrete-class mocks
    - _Design: §Correctness Properties (15), §10.1_

- [x] 8. Per-role dashboard and reporting
  - [x] 8.1 Add role-shaped dashboard summary endpoint
    - Add `GET /api/dashboard/summary` (`hasAnyRole('ADMIN','SALESPERSON','PACKING_USER','ACCOUNTANT')`) building a role-specific payload from the caller's principal: salesperson (own leads/orders by status + awaiting-approval count), admin (pending-approval, per-stage, exception, awaiting-handover, awaiting-dispatch), packer (awaiting-packing, packed-today, awaiting-handover, awaiting-dispatch), accountant (COD pending, settled, receivables)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_
    - _Design: §6.7_

  - [x]* 8.2 Write property test for queue membership
    - **Property 13: A queue contains exactly the orders in its status set** — _Validates: Requirements 6.1, 8.1, 9.4_
    - Approval queue = `PENDING_ADMIN_APPROVAL`, packing = `{APPROVED, LABEL_GENERATED}`, awaiting-handover = `PACKED`, awaiting-dispatch = `HANDED_TO_DELIVERY`; ≥100 iterations; tag; pure, no concrete-class mocks
    - _Design: §Correctness Properties (13), §10.1_

  - [x] 8.3 Apply salesperson scoping in dashboard and reports
    - Ensure `SalespersonScopeResolver` scopes list/detail/report/dashboard queries to `createdBy` for salespeople while admin/accountant queries stay unscoped
    - _Requirements: 2.6, 5.5, 16.5_
    - _Design: §6.7, §6.8_

  - [x]* 8.4 Write property test for salesperson scoping
    - **Property 7: Salesperson queries return only that salesperson's orders** — _Validates: Requirements 2.6, 5.5, 16.5_
    - Mixed-creator order set; salesperson query returns exactly own orders; admin/accountant unscoped; ≥100 iterations; tag; pure, no concrete-class mocks
    - _Design: §Correctness Properties (7), §10.1_

  - [x] 8.5 Add report types and aggregation
    - Add `ReportType` values `ORDERS_BY_LEAD_SOURCE`, `ORDERS_BY_STATUS`, `ORDERS_BY_SALESPERSON`, `DELIVERY_OUTCOME` and the grouping/success-rate logic in the pure `ReportAggregator`/`ReportTableBuilder` (bucket NULL lead source as `UNSPECIFIED`); expose via `/api/reports/...` (`hasAnyRole('ADMIN','ACCOUNTANT')`, salesperson scoped)
    - _Requirements: 16.1, 16.2, 16.3, 16.4_
    - _Design: §6.8_

  - [x]* 8.6 Write property test for report groupings and success rate
    - **Property 14: Report groupings and the delivery success rate are exact** — _Validates: Requirements 3.6, 16.1, 16.2, 16.3, 16.4_
    - Group counts equal multiset grouping and sum to in-range count; success rate = `DELIVERED/(DELIVERED+CUSTOMER_REJECTED+DELIVERY_FAILED+CANCELLED)` (0 when denom 0); ≥100 iterations; tag; pure, no concrete-class mocks
    - _Design: §Correctness Properties (14), §10.1_

- [x] 9. Checkpoint - endpoints and reporting
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Frontend (mobile-first admin app)
  - [x] 10.1 Make `DashboardComponent` role-aware with role-aware navigation
    - Read `AuthService.session()?.role`, render the role's card set from `GET /api/dashboard/summary`; keep the post-login `'' → 'dashboard'` redirect; ensure nav groups with no visible children are dropped
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 2.7_
    - _Design: §7.1_

  - [x] 10.2 Add Lead Source field to New Order
    - Add a Lead Source select (`WHATSAPP/INSTAGRAM/FACEBOOK/GOOGLE/OFFLINE/OTHER`) and a ≤200-char note field shown when `OTHER`; send with the create request
    - _Requirements: 4.1, 4.5_
    - _Design: §7.3_

  - [x] 10.3 Add Handover and Dispatch actions to Packing
    - Add Handover (for `PACKED`) and Dispatch (for `HANDED_TO_DELIVERY`) buttons calling the new endpoints; reflect awaiting-handover / awaiting-dispatch queues
    - _Requirements: 9.4, 10.1_
    - _Design: §7.3_

  - [x] 10.4 Make the notification bell per-user
    - `NotificationBellComponent` shows notifications addressed to the current user's role or user id via the staff-facing endpoint, not just admin broadcasts
    - _Requirements: 13.4_
    - _Design: §7.3_

  - [x] 10.5 Apply the mobile-first pass to workflow screens
    - Single-column at 360px with no horizontal scroll, ≥44×44px touch targets, cards over tables below 768px prioritizing role-relevant fields with secondary fields behind a detail view, compact/bottom navigation, existing count-3/money-2 density
    - _Requirements: 17.1, 17.2, 17.3, 17.4, 17.5, 17.6_
    - _Design: §7.2_

  - [x]* 10.6 Write component/snapshot tests for the mobile-first screens
    - Verify single-column layout, ≥44px targets, and card rendering below 768px at a 360px viewport for the dashboard, New Order, and Packing screens
    - _Requirements: 17.1, 17.2, 17.3_
    - _Design: §10.5_

- [x] 11. Wiring and integration tests
  - [x]* 11.1 Write webhook → transition synchronization integration test
    - Post signed courier webhooks through `CourierWebhookController` → `CourierStatusApplier`; assert advancement for legal tokens (incl. new `CUSTOMER_REJECTED`/`DELIVERY_FAILED`) and no change for illegal/duplicate updates; 1–3 representative payloads
    - _Requirements: 10.3, 10.5_
    - _Design: §10.3 (webhook → transition sync)_

  - [x]* 11.2 Write endpoint role-guard integration tests
    - MockMvc calls to approve/reject, pack, handover, dispatch, dashboard summary, and reports with each role → 200/403 per the matrix; confirm backend enforcement independent of route guards
    - _Requirements: 1.6, 2.7_
    - _Design: §10.3 (endpoint role guards)_

- [x] 12. Final checkpoint - full build and test pass
  - Ensure all backend tests pass (`mvn -f "backend/pom.xml" test`) and the admin app builds (`npm --prefix frontend run build:admin`); ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation sub-tasks are never optional.
- Each task references specific requirements clauses and design sections for traceability.
- All 20 Correctness Properties from `design.md` are covered by dedicated PBT sub-tasks placed next to the implementation they validate.
- Every PBT sub-task runs ≥100 iterations, is tagged `Feature: role-based-order-workflow, Property {n}: {text}`, exercises pure logic in-memory, and avoids Mockito mocks of concrete classes (Java 25 runtime gotcha) by using real instances / recording subclasses.
- All migrations are new versioned files (V23, V24); no applied migration is edited. New columns are nullable and safe on the seeded V22 dataset.
- Checkpoints provide incremental validation at the end of the backend domain, endpoints, and the full stack.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "2.1"] },
    { "id": 1, "tasks": ["1.4", "1.5", "2.2", "2.3", "4.1"] },
    { "id": 2, "tasks": ["1.6", "2.4", "3.1", "4.2", "4.3", "4.4", "5.1", "8.5"] },
    { "id": 3, "tasks": ["3.2", "3.3", "3.5", "4.5", "4.7", "5.2", "5.3", "5.4", "5.5", "5.6", "8.1", "8.3", "8.6"] },
    { "id": 4, "tasks": ["3.4", "4.6", "4.8", "4.10", "7.1", "7.3", "7.4", "8.2", "8.4", "10.1", "10.2", "10.4"] },
    { "id": 5, "tasks": ["4.9", "7.2", "7.5", "11.1"] },
    { "id": 6, "tasks": ["10.3", "11.2"] },
    { "id": 7, "tasks": ["10.5"] },
    { "id": 8, "tasks": ["10.6"] }
  ]
}
```
