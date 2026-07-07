# Implementation Plan: Shifa Herbal Remedies

## Overview

This plan implements the Shifa Herbal Remedies platform incrementally: a Java 21 / Spring Boot 3.x modular monolith (OMS) backed by MySQL 8, and an Angular workspace hosting the Storefront PWA and Admin Dashboard sharing `core`/`ui` libraries. Deployment targets the OCI Always Free tier with OCI Object Storage for images/PDFs/backups, plus WhatsApp Business API (Meta Cloud) and an external Courier API.

The sequence bootstraps project structure and the database schema first, then builds the pure domain core (payment/COD math, order status state machine, settlement/receivables) with jqwik property-based tests, then layers modules on top so each builds on the previous and wires into the running application. Angular cart/wishlist/checkout logic is covered by fast-check property tests. Each task references the requirements and design correctness properties it implements.

Property-based tests use **jqwik** (backend) and **fast-check** (frontend), each running a minimum of 100 iterations and tagged `Feature: shifa-herbal-remedies, Property {number}: {property_text}`.

## Tasks

- [x] 1. Bootstrap backend project structure and database foundation
  - [x] 1.1 Initialize Spring Boot 3.x / Java 21 project with modular package layout
    - Create Gradle/Maven project with modules as packages: `auth`, `product`, `order`, `statemachine`, `label`, `courier`, `notification`, `reconciliation`, `reporting`, `agent`, `dashboard`, `platform`, `common`
    - Add dependencies: Spring Web, Spring Data JPA, Spring Security, MySQL driver, Flyway (migrations), jqwik (test), Apache POI, OpenPDF/PDFBox, ZXing, OCI SDK
    - Configure `application.yml` profiles (local, prod) with MySQL datasource and JWT/OCI/WhatsApp/Courier placeholders
    - Define the JSON error envelope model and a `@RestControllerAdvice` global exception handler skeleton
    - _Requirements: 5.1_

  - [x] 1.2 Create MySQL schema via Flyway migrations
    - Author migration scripts (InnoDB, UTF8MB4) for `users`, `products`, `product_images`, `orders`, `line_items`, `payments`, `status_history`, `courier_companies`, `courier_records`, `receivables`, `cart_items`, `wishlist_items`, `outbox`, `backup_runs`
    - Use `DECIMAL(12,2)` for all monetary columns; add unique constraints (`users.username`, `products.sku`, `orders.order_code`, `courier_records.order_id`, cart/wishlist `(customer_id, product_id)`)
    - Add indexes on `orders.customer_mobile`, `orders.order_status`, `orders.created_by`, `orders.created_at`, `orders.order_code`, `courier_records.awb`
    - _Requirements: 6.2, 8.1, 22.1_

  - [x]* 1.3 Write migration/schema smoke test
    - Verify Flyway migrations apply cleanly against a test MySQL (Testcontainers) and all constraints/indexes exist
    - _Requirements: 6.2, 22.1_

- [x] 2. Bootstrap Angular workspace with two apps and shared libraries
  - [x] 2.1 Create Nx/Angular workspace with Storefront and Admin apps plus core/ui libraries
    - Generate `storefront` and `admin` applications and shared `core` (API client, auth interceptor, models) and `ui` (component) libraries
    - Configure environment files pointing at the REST API base URL
    - Install and configure Jest + fast-check for library-level property tests
    - _Requirements: 4.1_

  - [x] 2.2 Implement shared core API client and typed models
    - Define TypeScript interfaces mirroring backend DTOs (Product, Order, LineItem, PaymentStatus, OrderStatus, Receivable)
    - Implement HTTP client wrapper and JWT auth interceptor scaffolding (attach bearer token, handle 401/403)
    - _Requirements: 5.2, 5.3_

- [x] 3. Implement domain core: payment and COD math
  - [x] 3.1 Implement Money/pricing computation using BigDecimal
    - Implement `line_total = rate × quantity`, `Total_Amount = Σ line_total`, `Remaining_Amount = Total_Amount − Amount_Received` with fixed scale (2) and no floating point
    - Implement validation rejecting `Amount_Received > Total_Amount`
    - _Requirements: 7.3, 7.4, 7.5, 7.10_

  - [x] 3.2 Implement payment classification and COD derivation
    - Derive `Payment_Status` and `COD_Amount` per rules: received=0 → COD/COD_Amount=Total; 0<received<Total → Partially_Paid/COD_Amount=Remaining; received=Total → Fully_Paid/COD_Amount=0
    - Implement the screenshot-required rule: submission permitted only if `Amount_Received = 0` or a screenshot key is present
    - _Requirements: 7.6, 7.7, 7.8, 7.9_

  - [x]* 3.3 Write property test for total amount computation
    - **Property 1: Total amount equals sum of line totals**
    - **Validates: Requirements 7.3, 7.4**

  - [x]* 3.4 Write property test for payment classification and COD math
    - **Property 2: Payment classification and COD math**
    - **Validates: Requirements 7.5, 7.7, 7.8, 7.9, 7.10**

  - [x]* 3.5 Write property test for mandatory payment screenshot
    - **Property 3: Payment screenshot mandatory when money received**
    - **Validates: Requirements 7.6**

- [x] 4. Implement domain core: order status state machine
  - [x] 4.1 Implement OrderStatus enum and transition table
    - Define the 15 statuses; encode allowed source→target transitions as an explicit table
    - Implement `transition(order, target, actor, source)` that rejects illegal transitions with `IllegalStatusTransitionException` (mapped to 409) and retains current status
    - Set initial status `Pending_Admin_Approval` on creation
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 4.2 Implement status history recording on accepted transitions
    - On each accepted transition, append exactly one `status_history` entry (from, to, actor, source, timestamp)
    - _Requirements: 8.4_

  - [x]* 4.3 Write property test for initial order status
    - **Property 4: New orders start in Pending_Admin_Approval**
    - **Validates: Requirements 3.6, 7.11, 8.2**

  - [x]* 4.4 Write property test for illegal transition rejection
    - **Property 5: Illegal status transitions are rejected and state is retained**
    - **Validates: Requirements 8.1, 8.3, 11.4**

  - [x]* 4.5 Write property test for status history recording
    - **Property 6: Every accepted transition is recorded in status history**
    - **Validates: Requirements 8.4**

- [x] 5. Implement domain core: settlement and receivables ledger
  - [x] 5.1 Implement settlement side effects on Delivered/RTO/Courier_Lost
    - Delivered + Fully_Paid → Closed, outstanding=0; Delivered + COD_Amount>0 → COD_Collected, outstanding=0, create COD_RECEIVABLE=COD_Amount
    - RTO → cancel COD_Amount (0), outstanding=0; Courier_Lost → create CLAIM_RECEIVABLE=net amount, outstanding=0
    - _Requirements: 16.1, 16.2, 16.3, 17.2, 17.3_

  - [x] 5.2 Implement receivable aggregation and settlement operations
    - Per-courier COD receivable and claim totals; unsettled COD list; prepaid/COD segregation; RTO exclusion from COD totals
    - Implement `settle(receivableId)` recording settlement date and reducing outstanding, idempotent for already-settled receivables
    - _Requirements: 18.1, 18.2, 18.3, 18.4, 18.5, 18.6_

  - [x]* 5.3 Write property test for settlement on delivery and RTO
    - **Property 8: Settlement on delivery and RTO**
    - **Validates: Requirements 16.1, 16.2, 16.3**

  - [x]* 5.4 Write property test for loss claim generation
    - **Property 9: Loss produces a claim for the full net amount**
    - **Validates: Requirements 17.2, 17.3**

  - [x]* 5.5 Write property test for reconciliation totals and segregation
    - **Property 10: Reconciliation totals, segregation, and RTO exclusion**
    - **Validates: Requirements 18.1, 18.2, 18.3, 18.4, 18.6**

  - [x]* 5.6 Write property test for settlement idempotency
    - **Property 11: Settling a receivable reduces outstanding and is idempotent**
    - **Validates: Requirements 18.5**

- [x] 6. Checkpoint - domain core
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement Auth & RBAC
  - [x] 7.1 Implement JWT authentication and user/role model
    - Implement User entity, BCrypt password hashing, `/api/auth/login` and `/api/auth/refresh` issuing access/refresh tokens
    - Configure Spring Security filter chain; public catalog/search/PWA assets open, all other `/api/**` authenticated
    - _Requirements: 5.1, 5.2_

  - [x] 7.2 Implement role-based authorization and salesperson scoping
    - Apply `@PreAuthorize` per endpoint authority matrix; return 401 unauthenticated, 403 forbidden
    - Inject `createdBy = currentUser` predicate at repository layer for salesperson-scoped queries; Admin bypasses scoping
    - _Requirements: 5.3, 5.4, 5.5_

  - [x]* 7.3 Write property test for role scoping and authentication enforcement
    - **Property 25: Role scoping and authentication enforcement**
    - **Validates: Requirements 5.2, 5.3, 5.5**

  - [x] 7.4 Wire Angular auth into core library
    - Implement login flow, token storage/refresh, and route guards in Storefront (customer) and Admin (role-based) apps
    - _Requirements: 5.2, 5.3_

- [x] 8. Implement Product / Catalog module
  - [x] 8.1 Implement Admin product CRUD with SKU uniqueness
    - `POST/PUT /api/admin/products` for create/update with visibility flag; reject duplicate SKU with 409 duplicate-SKU error
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

  - [x] 8.2 Implement public catalog and search endpoints
    - `GET /api/catalog/products` returns only published products; `?q=` case-insensitive substring search on name/SKU; product detail returns 404/unavailable when not published
    - _Requirements: 1.1, 1.2, 1.3, 1.6, 1.7_

  - [x]* 8.3 Write property test for catalog and search visibility
    - **Property 16: Catalog and search show only matching published products**
    - **Validates: Requirements 1.1, 1.3, 6.4**

  - [x]* 8.4 Write unit tests for product edge cases
    - Duplicate SKU rejection (6.2), empty catalog message (1.6), no-match search message (1.5), unavailable product detail (1.7)
    - _Requirements: 1.5, 1.6, 1.7, 6.2_

  - [x] 8.5 Implement Storefront catalog UI with placeholder image logic
    - Catalog grid, product detail view, search box; substitute placeholder image when no published image exists
    - _Requirements: 1.1, 1.2, 1.4, 1.5, 1.6_

  - [x]* 8.6 Write fast-check property test for placeholder image selection
    - **Property 17: Placeholder image when no published image**
    - **Validates: Requirements 1.4**

- [x] 9. Implement Order / OMS module and Salesperson Order Entry
  - [x] 9.1 Implement Order aggregate persistence and creation
    - Persist Order with line items, payment fields, derived amounts (using domain core from tasks 3-4), initial status Pending_Admin_Approval, optimistic locking (`@Version`)
    - Upload payment screenshot to Object Storage; store object key
    - _Requirements: 7.1, 7.11, 8.2_

  - [x] 9.2 Implement Salesperson order-entry endpoint
    - `POST /api/orders`: require customer/address, line items with default sale price pre-fill and editable rate, Amount_Received capture, screenshot enforcement, payment classification
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9, 7.10, 7.11_

  - [x] 9.3 Implement order search and duplicate detection
    - `GET /api/orders?search=` by name/mobile/id/AWB (role-scoped); `GET /api/orders/duplicate-check?mobile=` flags prior orders
    - _Requirements: 22.1, 22.2_

  - [x]* 9.4 Write property test for search and duplicate detection
    - **Property 22: Search and duplicate detection**
    - **Validates: Requirements 22.1, 22.2**

  - [x] 9.5 Implement payment tracking view endpoints
    - `GET /api/orders/{id}` exposes Payment_Status/Total/Received/COD; `GET /api/orders/{id}/payment-screenshot` returns PAR URL gated by ACCOUNTANT/ADMIN
    - _Requirements: 21.1, 21.2_

- [x] 10. Implement Admin Order Approval
  - [x] 10.1 Implement approval queue and approve/reject endpoints
    - `GET /api/admin/orders/approval-queue` lists Pending_Admin_Approval orders with review details
    - `POST .../approve` → Approved; `POST .../reject` requires reason → Rejected + stored reason (via state machine)
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

  - [x]* 10.2 Write unit tests for approval edge cases
    - Reject without reason is rejected; approve/reject use legal transitions only
    - _Requirements: 9.3, 9.4_

  - [x] 10.3 Implement Admin approval queue UI
    - Approval queue view rendering screenshot, line items, rates, amounts, payment status; approve/reject actions with reason capture
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

- [x] 11. Implement Label Service (barcode + PDF)
  - [x] 11.1 Implement internal company label generation on Approved
    - Generate label with order id, Code128 barcode encoding order code, customer details, line items, and COD_Amount when COD/Partially_Paid; set status Label_Generated; upload PDF to Object Storage
    - _Requirements: 10.1, 10.2, 10.3_

  - [x] 11.2 Implement single and bulk label PDF endpoints
    - Produce PDF with one internal-label block per requested order; return PAR URLs
    - _Requirements: 10.4_

  - [x]* 11.3 Write property test for label content completeness
    - **Property 18: Label content completeness**
    - **Validates: Requirements 10.1, 10.2, 12.3**

  - [x]* 11.4 Write property test for bulk label output
    - **Property 19: Bulk label output has one label per requested order**
    - **Validates: Requirements 10.4, 12.5**

- [x] 12. Implement Packing and Barcode Scan
  - [x] 12.1 Implement packing scan endpoint
    - `POST /api/packing/scan`: match barcode to order; Label_Generated → Packed; unrecognized barcode message; reject and show current status when not Label_Generated
    - _Requirements: 11.1, 11.3, 11.4_

  - [x]* 12.2 Write unit tests for scan edge cases
    - Unknown barcode message (11.3); scan on non-Label_Generated order rejected with current status (11.4)
    - _Requirements: 11.3, 11.4_

  - [x] 12.3 Implement packing scan UI in Admin app
    - Barcode input/scan view with success and error messaging
    - _Requirements: 11.1, 11.3, 11.4_

- [x] 13. Checkpoint - fulfillment pipeline
  - Ensure all tests pass, ask the user if questions arise.

- [x] 14. Implement Courier Integration and webhooks
  - [x] 14.1 Implement transactional outbox and drainer
    - Create outbox write on side-effecting transitions; scheduled drainer with bounded retries, timeout, and status/error tracking
    - _Requirements: 12.1, 12.4_

  - [x] 14.2 Implement courier AWB assignment on Packed
    - On Packed, request AWB + shipping label via Courier API (with COD_Amount); on success store AWB, generate courier shipping label PDF, set Courier_Assigned; on error/timeout retain Packed and produce Admin failure notification
    - _Requirements: 12.1, 12.2, 12.3, 12.4_

  - [x] 14.3 Implement courier tracking webhook and status mapping
    - `POST /api/webhooks/courier` (HMAC-validated, idempotent): map pickup→Dispatched, in-transit→In_Transit, out-for-delivery→Out_For_Delivery, delivered→Delivered, return→RTO, lost/damaged→Courier_Lost; apply only when legal; trigger settlement side effects
    - Scheduled poll reconciles missed webhooks
    - _Requirements: 13.1, 13.2, 17.1_

  - [x]* 14.4 Write property test for courier status mapping
    - **Property 7: Courier status mapping**
    - **Validates: Requirements 13.1, 13.2, 17.1**

  - [x]* 14.5 Write property test for integration failure handling
    - **Property 21: Integration failures are recorded without losing state**
    - **Validates: Requirements 12.4, 14.4**

  - [x]* 14.6 Write integration tests for courier wiring
    - Mock Courier API: AWB request/response + label retrieval (12.1, 12.2); webhook → status change (13.2)
    - _Requirements: 12.1, 12.2, 13.2_

  - [x] 14.7 Implement shipping label bulk print and customer tracking endpoint
    - `GET /api/track/{orderId}` returns current status, AWB, and courier tracking link; bulk shipping label PDF for orders with AWB
    - _Requirements: 12.5, 13.4_

- [x] 15. Implement Notification Service (WhatsApp)
  - [x] 15.1 Implement WhatsApp template registry and send-on-status
    - Registry mapping event → pre-approved Meta template + parameter mapping; send on Dispatched (order id, courier name, AWB, tracking link, ETA, COD_Amount when COD/Partially_Paid) and on Out_For_Delivery/Delivered/RTO/Courier_Lost; sends run via outbox
    - On failure, record it and flag order for Admin review
    - _Requirements: 14.1, 14.2, 14.3, 14.4_

  - [x]* 15.2 Write property test for WhatsApp notification content and template use
    - **Property 20: WhatsApp notification content and template use**
    - **Validates: Requirements 14.1, 14.2, 14.3**

  - [x]* 15.3 Write integration test for WhatsApp send via Meta Cloud
    - Mock Meta Cloud API: template send on Dispatched and failure recording/flagging
    - _Requirements: 14.1, 14.4_

  - [x] 15.4 Implement WhatsApp live-agent order lookup
    - `GET /api/agent/lookup?key=&value=` returns matching order status + tracking details by order id/mobile/AWB, or a no-match result
    - _Requirements: 15.1, 15.2_

  - [x]* 15.5 Write unit test for agent no-match result
    - Query with no matching order returns no-match message (15.2)
    - _Requirements: 15.2_

- [x] 16. Implement Reconciliation endpoints and UI
  - [x] 16.1 Implement reconciliation API
    - `GET /api/recon/receivables?courier=&type=`, `GET /api/recon/cod/unsettled`, `POST /api/recon/receivables/{id}/settle` (ACCOUNTANT/ADMIN) using ledger logic from task 5; claim-filed Admin notification on Courier_Lost
    - _Requirements: 17.4, 18.1, 18.2, 18.3, 18.4, 18.5, 18.6_

  - [x] 16.2 Implement reconciliation dashboard UI in Admin app
    - Per-courier COD and claim totals, unsettled COD list, prepaid/COD segregation, settle action
    - _Requirements: 18.1, 18.2, 18.3, 18.4, 18.5_

- [x] 17. Implement Reporting and Export
  - [x] 17.1 Implement report generation with date-range windowing
    - Daily/monthly/product-wise/state-wise reports; salesperson-wise detailed rows with all required columns; custom date-range filtering; salesperson-scoped
    - _Requirements: 20.1, 20.2, 20.3_

  - [x] 17.2 Implement Excel/PDF/Vyapar export
    - Excel (Apache POI) and PDF exports of displayed rows/columns; Vyapar-compatible CSV/Excel export; empty-range produces header-only file with a "no orders" message
    - _Requirements: 20.4, 23.1, 23.2_

  - [x]* 17.3 Write property test for report/metrics aggregation window
    - **Property 23: Reports and metrics aggregate exactly the orders within the selected window**
    - **Validates: Requirements 19.3, 19.4, 19.7, 20.2**

  - [x]* 17.4 Write property test for report and export content fidelity
    - **Property 24: Report and export content fidelity**
    - **Validates: Requirements 20.3, 20.4, 23.1**

  - [x]* 17.5 Write unit test for Vyapar empty-range export
    - Empty date range yields header-only file and no-orders message (23.2)
    - _Requirements: 23.2_

  - [x] 17.6 Implement reporting/export UI in Admin app
    - Report views with time-period/custom-range filters and export buttons (Excel, PDF, Vyapar)
    - _Requirements: 20.1, 20.2, 20.4, 23.1_

- [x] 18. Checkpoint - back-office modules
  - Ensure all tests pass, ask the user if questions arise.

- [x] 19. Implement Admin Dashboard metrics and SSE
  - [x] 19.1 Implement metrics aggregation endpoint
    - `GET /api/admin/metrics?period=`: metric cards (sales, orders, pending/packed/dispatched/delivered, RTO, Courier Lost, COD pending, claim pending, conversion rate); time-period filters; sales graph with previous-period comparison and % change; top performers
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.7_

  - [x] 19.2 Implement SSE event stream and post-commit publishing
    - `GET /api/admin/events`: publish ORDER_PACKED, ORDER_STATUS_CHANGED, CLAIM_FILED_REQUIRED, COURIER_ASSIGN_FAILED, WHATSAPP_FAILED, LIVE_STATS after commit; persist notifications when no admin connected
    - _Requirements: 11.2, 13.3, 17.4, 19.5, 19.6_

  - [x]* 19.3 Write integration test for packed-order webhook/scan → SSE
    - Scan/status change emits corresponding SSE event after commit
    - _Requirements: 11.2, 13.3_

  - [x] 19.4 Implement Admin Dashboard UI with charts and live stats
    - Metric cards, sales graph (day/week/month with comparison), live stats, activity cards, top performers; subscribe to SSE for real-time updates
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.6, 19.7, 11.2, 13.3, 17.4_

- [x] 20. Implement Storefront cart, wishlist, checkout, and tracking
  - [x] 20.1 Implement cart logic in shared core library
    - Add/change-quantity/remove with quantity 1..999; merge duplicates capped at 999; recalc subtotal and line-item count; reject invalid (<1, >999, non-integer) quantities leaving cart unchanged
    - _Requirements: 2.1, 2.2, 2.3, 2.6, 2.7_

  - [x]* 20.2 Write fast-check property test for cart consistency
    - **Property 12: Cart consistency**
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.7**

  - [x]* 20.3 Write fast-check property test for invalid cart quantities
    - **Property 14: Invalid cart quantities are rejected without side effects**
    - **Validates: Requirements 2.6**

  - [x] 20.4 Implement wishlist logic in shared core library
    - Set semantics (at most one entry per product, duplicate add is no-op); move-to-cart adds with quantity ≥ 1 and removes from wishlist
    - _Requirements: 2.4, 2.5, 2.8_

  - [x]* 20.5 Write fast-check property test for wishlist semantics
    - **Property 13: Wishlist set semantics and move-to-cart**
    - **Validates: Requirements 2.4, 2.5, 2.8**

  - [x] 20.6 Implement checkout validation and submission
    - Validate required fields, 10-digit mobile, 6-digit postal code; retain values and flag invalid/missing fields; prevent empty-cart checkout; `POST /api/checkout` creates order (Pending_Admin_Approval) and shows confirmation with order id
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_

  - [x]* 20.7 Write fast-check property test for checkout validation
    - **Property 15: Checkout validation**
    - **Validates: Requirements 3.3, 3.4, 3.5**

  - [x] 20.8 Implement order tracking view in Storefront
    - Customer view of own order status, AWB, and courier tracking link via `/api/track/{orderCode}`
    - _Requirements: 13.4_

- [x] 21. Implement PWA capabilities
  - [x] 21.1 Add web app manifest, service worker, and responsive layout
    - Configure `@angular/pwa` manifest + service worker for installability and offline shell; present install option where supported; responsive layouts for mobile/tablet/desktop
    - _Requirements: 4.1, 4.2, 4.3_

  - [x]* 21.2 Write smoke tests for PWA and responsiveness
    - Verify manifest presence + service worker registration (4.2); layout checks at breakpoints (4.1, 4.3)
    - _Requirements: 4.1, 4.2, 4.3_

- [x] 22. Implement scheduled database backups
  - [x] 22.1 Implement nightly backup job to Object Storage
    - `@Scheduled` (interval ≤ 24h) `mysqldump --single-transaction` → gzip → upload to Object Storage; record `backup_runs`; on failure record FAILED + emit Admin notification (SSE + persisted flag)
    - _Requirements: 24.1, 24.2_

  - [x]* 22.2 Write integration/smoke test for backup job
    - Mock Object Storage upload; verify success run recorded and failure produces Admin notification; scheduler interval ≤ 24h
    - _Requirements: 24.1, 24.2_

- [x] 23. Final checkpoint - full integration
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP, though property tests validate the financially critical logic (payment/COD math, state machine, settlement, reconciliation) and are strongly recommended.
- Each task references specific requirements for traceability; property test tasks reference their design correctness property by number.
- Backend property tests use jqwik; Angular property tests use fast-check; each runs ≥ 100 iterations and is tagged `Feature: shifa-herbal-remedies, Property {number}: {property_text}`.
- Checkpoints ensure incremental validation at natural boundaries (domain core, fulfillment pipeline, back-office modules, full integration).
- The domain core (tasks 3-5) is built and tested before any module depends on it, so payment math, the state machine, and settlement are proven before wiring integrations.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "2.2"] },
    { "id": 2, "tasks": ["3.1", "3.2"] },
    { "id": 3, "tasks": ["3.3", "3.4", "3.5", "4.1"] },
    { "id": 4, "tasks": ["4.2", "4.3", "4.4"] },
    { "id": 5, "tasks": ["4.5", "5.1"] },
    { "id": 6, "tasks": ["5.2", "5.3", "5.4"] },
    { "id": 7, "tasks": ["5.5", "5.6", "7.1"] },
    { "id": 8, "tasks": ["7.2", "7.4"] },
    { "id": 9, "tasks": ["7.3", "8.1"] },
    { "id": 10, "tasks": ["8.2", "8.5"] },
    { "id": 11, "tasks": ["8.3", "8.4", "8.6", "9.1"] },
    { "id": 12, "tasks": ["9.2"] },
    { "id": 13, "tasks": ["9.3", "9.5"] },
    { "id": 14, "tasks": ["9.4", "10.1"] },
    { "id": 15, "tasks": ["10.2", "10.3", "11.1"] },
    { "id": 16, "tasks": ["11.2", "11.3", "11.4", "12.1"] },
    { "id": 17, "tasks": ["12.2", "12.3", "14.1"] },
    { "id": 18, "tasks": ["14.2"] },
    { "id": 19, "tasks": ["14.3", "14.7"] },
    { "id": 20, "tasks": ["14.4", "14.5", "14.6", "15.1"] },
    { "id": 21, "tasks": ["15.2", "15.3", "15.4"] },
    { "id": 22, "tasks": ["15.5", "16.1"] },
    { "id": 23, "tasks": ["16.2", "17.1"] },
    { "id": 24, "tasks": ["17.2"] },
    { "id": 25, "tasks": ["17.3", "17.4", "17.5", "17.6"] },
    { "id": 26, "tasks": ["19.1", "19.2"] },
    { "id": 27, "tasks": ["19.3", "19.4"] },
    { "id": 28, "tasks": ["20.1", "20.4"] },
    { "id": 29, "tasks": ["20.2", "20.3", "20.5", "20.6"] },
    { "id": 30, "tasks": ["20.7", "20.8", "21.1"] },
    { "id": 31, "tasks": ["21.2", "22.1"] },
    { "id": 32, "tasks": ["22.2"] }
  ]
}
```
