# Requirements Document

## Introduction

Shifa OMS currently owns label generation (`LabelService`, internal Code128 PDF) and the complete
order lifecycle (`OrderWorkflowService` / `OrderStatusStateMachine` / `TransitionAuthority`), plus a
packing floor module (queues, Scan & Move, handover, dispatch, multi-label print).

The client's live operating model is different. The customer-facing store runs on **Shopify**. When a
Shopify order is placed it flows straight to **QuikShipX** (the courier aggregator at
`https://quikshipx.com/`), which generates the shipping label and owns every post-handoff status
transition. The Shifa packaging team already works out of the QuikShipX portal: download the label
there, then move the shipment from *Label Printed* to *Ready for Pickup* there.

This feature makes Shifa OMS the single tracking surface across **both** order channels while giving
QuikShipX authority over labels and fulfilment status:

- Orders punched in the Shifa Admin Portal are approved in Shifa, then **published to QuikShipX**
  tagged as `SHIFA_ADMIN`.
- Orders placed on Shopify are **ingested into Shifa OMS** from Shopify order webhooks and tagged as
  `SHOPIFY_API`.
- For both channels, fulfilment status, AWB/tracking data and the shipping label come **from
  QuikShipX** and are mirrored read-only into Shifa OMS.
- The Shifa Admin Portal shows, filters, reports and dashboards both channels with the channel
  clearly differentiated.
- The existing internal label PDF and packing Scan & Move flows are retained as a **configuration-gated
  fallback** for when QuikShipX is unavailable or an order is not QuikShipX-managed.

Scope decisions confirmed with the client:

- Shopify orders bypass admin approval (Shopify already committed them); Shifa-punched orders still
  require admin approval before publication to QuikShipX.
- Shopify-origin orders are read-only in Shifa OMS.
- The existing `order.OrderSource` enum is extended rather than a third channel concept introduced.
- **Out of scope (deferred to a follow-up spec):** QuikShipX COD remittance reconciliation, and
  pushing cancel/RTO from Shifa OMS to QuikShipX.
- QuikShipX access is specified behind a pluggable client contract with a mock implementation,
  matching the existing mock courier and WhatsApp integrations.

### Confirmed QuikShipX contract (`api_custom_docs_v1`, "Quickship X Delhivery")

The supplied specification documents **one** operation:

- `POST https://head.quikshipx.com/api/create-order-v1`, sole header `Content-Type: application/json`.
- **Authentication is carried in the request body**, not in a header: `shipper_details` holds
  `client_code`, `user_id` and `user_secret`. A TEST secret files the order under QuikShipX's *Test*
  section; a LIVE secret files it under *Pending*.
- Body sections: `customer_details`, `shipment_details`, `product_details` (array) and
  `shipper_details`. **Every value is a JSON string**, including amounts, dimensions and quantities.
- `customer_order_id` is documented as "Unique ID for the order from your system" and
  `customer_order_date` as a human-readable date such as `"14 March 2026"`.

Capabilities the specification does **not** define, which therefore bound this feature:

| Absent capability | Consequence |
| --- | --- |
| Any channel, source or tag field | The Order_Channel cannot be sent as a field. It is encoded in the QuikShipX_Order_Reference instead. |
| Documented response body | Shipment_Record field names are unconfirmed; parsing is tolerant and a submission is treated as accepted on a success response even when no identifier is returned. |
| Status webhook or status query endpoint | Status mirroring is specified but ships **disabled**, behind a capability flag, until QuikShipX publishes one. |
| Label endpoint | The QuikShipX label is downloaded from the QuikShipX portal by the packing team. Shifa_OMS exposes no QuikShipX label download. |
| Cancel endpoint | Cancelling in QuikShipX stays a portal action, consistent with the deferred cancel/RTO push-back. |

Decisions taken on the strength of that contract:

- **Channel differentiation travels in `customer_order_id`.** Shifa_OMS sends the
  QuikShipX_Order_Reference, a channel-prefixed form of the Shifa_OMS order code, which is also the
  correlation key used to match a QuikShipX shipment back to a Shifa_OMS order.
- **The create-order body needs data Shifa_OMS does not hold today** — pickup warehouse, package
  type, shipping mode, dead weight, dimensions, shipping amount and product category. These come
  from admin-managed Shipment_Defaults (Requirement 16), optionally overridden per product.
- **Status mirroring and its polling fallback remain fully specified** so that enabling them is a
  configuration change once QuikShipX exposes a status feed, not a redesign.

## Glossary

- **Shifa_OMS**: The existing Java 21 / Spring Boot modular monolith plus its Angular admin
  application, referred to as a whole.
- **Shopify_Store**: The client's customer-facing Shopify storefront, the system of record for
  Shopify-channel order content (items, customer, money).
- **QuikShipX**: The external courier aggregator at `https://quikshipx.com/`, the system of record
  for shipping labels, AWB/tracking data and fulfilment status, reached over the confirmed
  `create-order-v1` operation described in the Introduction.
- **QuikShipX_Order_Reference**: The value Shifa_OMS sends as `customer_order_id`. It is the
  Shifa_OMS order code prefixed with a configured channel prefix, `SHIFA-` for Order_Channel
  `SHIFA_ADMIN`. It is unique per order, stable across retries, and is the correlation key between a
  QuikShipX shipment and a Shifa_OMS order.
- **Shipment_Defaults**: The admin-managed values Shifa_OMS supplies for the QuikShipX
  `shipment_details` fields it does not otherwise hold, namely pickup warehouse identifier, package
  type, shipping mode, dead weight in grams, length, width, height, shipping amount and default
  product category.
- **Status_Feed_Available**: The configuration flag stating that QuikShipX exposes a status webhook
  or a status query operation. It is disabled by default because the confirmed contract defines
  neither.
- **Order_Channel**: The origin channel of an order, persisted as `order.OrderSource`. This feature
  adds the values `SHOPIFY_API` (placed on Shopify_Store) and `SHIFA_ADMIN` (punched in the Shifa
  Admin Portal by an ADMIN, SALESPERSON or TEAM_LEAD).
- **Shopify_Ingestor**: The Shifa_OMS component that receives Shopify order webhooks and creates or
  updates the corresponding Shifa_OMS order.
- **QuikShipX_Publisher**: The Shifa_OMS component that submits a `SHIFA_ADMIN` order to QuikShipX
  and records the resulting shipment identifiers.
- **QuikShipX_Status_Sync**: The Shifa_OMS component that receives QuikShipX shipment status events
  and applies the corresponding Shifa_OMS status transitions.
- **Status_Mapper**: The pure component that maps a QuikShipX status token to a Shifa_OMS
  `OrderStatus`, and computes the legal transition path from the order's current status to that
  target.
- **Integration_Event_Store**: The persistent record of every inbound Shopify_Store and QuikShipX
  event, holding the external event identifier, raw payload, receipt time, processing outcome and
  failure reason.
- **Shipment_Record**: The per-order QuikShipX shipment data held by Shifa_OMS: QuikShipX shipment
  identifier, AWB/tracking number, courier name, tracking URL, label URL and last synchronised
  status token with the timestamp of that status.
- **QuikShipX_Managed_Order**: An order whose fulfilment is owned by QuikShipX, that is, an order
  with a Shipment_Record and Fallback_Mode disabled.
- **Managed_Stage**: Any `OrderStatus` at or after `LABEL_GENERATED` for a QuikShipX_Managed_Order,
  namely `LABEL_GENERATED`, `PACKED`, `HANDED_TO_DELIVERY`, `COURIER_ASSIGNED`, `DISPATCHED`,
  `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `CUSTOMER_REJECTED`, `DELIVERY_FAILED`,
  `COD_COLLECTED`, `CLOSED`, `RTO` and `REDISPATCH`.
- **Fallback_Mode**: A per-order flag that returns fulfilment authority to Shifa_OMS, enabling the
  Internal_Label_Service and the Packing_Queue for that order.
- **Internal_Label_Service**: The existing `LabelService` that renders the internal Code128 PDF label.
- **Packing_Queue**: The existing packing work queues and Scan & Move flow (`/api/packing/*`).
- **Order_Workflow**: The existing `OrderWorkflowService.applyTransition(order, target, Actor)`, the
  single path for a status change (authorize, legality, one history row, audit, notification fan-out).
- **Transition_Authority**: The existing `statemachine/TransitionAuthority` that authorizes a
  transition for an Actor.
- **SYSTEM_Actor**: The existing non-human Actor used by Order_Workflow for machine-driven
  transitions.
- **Review_Queue**: The admin-facing list of ingested Shopify orders that Shifa_OMS could not map
  completely, each with the specific unmapped field.
- **Integration_Health_Console**: The ADMIN-only Shifa Admin Portal view of integration failures,
  unmapped status tokens and replay controls.
- **Channel_Report**: A report in the existing report catalogue that groups order metrics by
  Order_Channel.
- **Order_Query**: The existing order listing, search and detail read paths
  (`AdminOrderController`, `OrderService.search`, `OrderService.loadScoped`).

## Requirements

### Requirement 1: Order Channel Identification

**User Story:** As an admin, I want every order to carry its origin channel, so that I can tell a
Shopify order apart from an order punched in the Shifa Admin Portal.

#### Acceptance Criteria

1. THE Shifa_OMS SHALL persist for every order exactly one non-null Order_Channel value that is a
   member of the `order.OrderSource` enumeration.
2. WHEN the Shopify_Ingestor creates an order, THE Shifa_OMS SHALL set the Order_Channel of that
   order to `SHOPIFY_API` in the same transaction that persists the order, so that no order is
   readable without an Order_Channel value.
3. WHEN an ADMIN, SALESPERSON or TEAM_LEAD creates an order through `POST /api/orders`, THE Shifa_OMS
   SHALL set the Order_Channel of that order to `SHIFA_ADMIN`, ignoring any Order_Channel value
   supplied in the request.
4. IF a request would change the stored Order_Channel of an existing order, THEN THE Shifa_OMS SHALL
   reject that request with HTTP status 409 and an error message naming Order_Channel as immutable,
   SHALL leave the stored Order_Channel unchanged, and SHALL apply no other field change carried by
   that request.
5. THE Shifa_OMS SHALL expose the Order_Channel of an order as its stored enumeration value in the
   order list response and in the order detail response, for every order the caller is authorised to
   read.
6. FOR ALL orders created before this feature is deployed, THE Shifa_OMS SHALL retain the previously
   stored Order_Channel value and SHALL treat a stored `SALESPERSON` value as equivalent to
   `SHIFA_ADMIN` for filtering, reporting and dashboard grouping.
7. WHEN the schema migration for this feature is applied, THE Shifa_OMS SHALL set the Order_Channel of
   every existing order that holds no Order_Channel value to `SHIFA_ADMIN` and SHALL leave every
   already-populated Order_Channel value unchanged.
8. WHEN the Shopify_Ingestor processes a Shopify_Store event that resolves to an order already present
   in Shifa_OMS, THE Shifa_OMS SHALL leave the Order_Channel of that order unchanged.

### Requirement 2: Shopify Order Webhook Receipt

**User Story:** As an admin, I want Shopify orders to arrive in Shifa OMS automatically, so that the
Shifa Admin Portal shows every order the business has taken.

#### Acceptance Criteria

1. THE Shifa_OMS SHALL expose exactly one HTTP POST endpoint that accepts Shopify_Store order webhook
   deliveries without requiring a JWT and without requiring any other Shifa_OMS session credential.
2. WHEN a Shopify_Store webhook delivery is received, THE Shifa_OMS SHALL record the receipt time and
   SHALL verify the signature value carried by that delivery against the configured Shopify webhook
   secret using a comparison that examines every byte of both values regardless of the position of the
   first mismatch (constant-time comparison).
3. IF a Shopify_Store webhook delivery carries no signature value, carries an empty signature value,
   or carries a signature value that fails verification, THEN THE Shifa_OMS SHALL respond with HTTP
   status 401 within 5 seconds of the receipt time, SHALL write a rejection log entry naming the source
   and the receipt time without persisting the rejected payload, SHALL write no Integration_Event_Store
   record, and SHALL create no order and change no existing order.
4. WHEN a Shopify_Store webhook delivery passes signature verification, THE Shifa_OMS SHALL write one
   Integration_Event_Store record holding the raw payload, the Shopify event identifier, the receipt
   time and the outcome `RECEIVED`, and SHALL respond with HTTP status 200 within 5 seconds of the
   receipt time.
5. WHEN the Shifa_OMS has responded to a Shopify_Store delivery, THE Shifa_OMS SHALL process the stored
   Shopify_Store event outside the request thread and SHALL begin that processing within 60 seconds of
   writing the Integration_Event_Store record.
6. WHEN a Shopify_Store delivery whose Shopify event identifier already exists in the
   Integration_Event_Store is received, THE Shifa_OMS SHALL respond with HTTP status 200 within
   5 seconds of the receipt time, SHALL write no additional Integration_Event_Store record for that
   identifier, SHALL start no further processing of that event, and SHALL leave every order unchanged.
7. FOR ALL Shopify_Store events, processing the same event two or more times SHALL leave the resulting
   order state identical to processing that event once (idempotence property).
8. IF the body of a Shopify_Store webhook delivery exceeds the configured maximum payload size,
   evaluated before signature verification, whose default is 1 megabyte and whose configurable range is
   256 kilobytes to 8 megabytes, THEN THE Shifa_OMS SHALL respond with HTTP status 413 within 5 seconds
   of the receipt time, SHALL persist no part of the rejected payload, and SHALL create no order.
9. IF processing of a stored Shopify_Store event fails, THEN THE Shifa_OMS SHALL retry that processing
   up to 3 further times with exponential backoff beginning at 30 seconds.
10. IF every retry of processing a stored Shopify_Store event fails, THEN THE Shifa_OMS SHALL record the
    outcome `PROCESSING_FAILED` with the failure reason on that Integration_Event_Store record, SHALL
    leave every order unchanged, and SHALL raise an in-app notification to the ADMIN role.

### Requirement 3: Shopify Order Mapping

**User Story:** As an admin, I want an ingested Shopify order to carry the same customer, item and
money detail as the Shopify order, so that the Shifa Admin Portal reports match the store.

#### Acceptance Criteria

1. WHEN the Shopify_Ingestor processes a Shopify_Store order event, THE Shifa_OMS SHALL create exactly
   one order, within 60 seconds of that event being written to the Integration_Event_Store, holding the
   customer name, contact number, shipping address, every line item of that event with its name,
   quantity and amount for up to 200 line items, the order total and the Shopify order number from
   that event.
2. THE Shopify_Ingestor SHALL normalise the Shopify customer contact number by discarding every
   character other than the digits `0` through `9`, then discarding a leading `91` or a leading `0`
   when the remaining value is 11 or 12 digits, and SHALL store the result in
   `orders.customer_mobile` only when that result is exactly 10 digits.
3. WHEN a Shopify line item SKU, compared after removing leading and trailing whitespace and ignoring
   letter case, matches the SKU of exactly one Shifa_OMS product, THE Shopify_Ingestor SHALL link that
   line item to the matched product.
4. IF a Shopify line item SKU is absent, is empty after removing leading and trailing whitespace,
   matches no Shifa_OMS product or matches more than one Shifa_OMS product, THEN THE Shopify_Ingestor
   SHALL create the order with that line item recorded by name, quantity and amount without a product
   link, and SHALL add the order to the Review_Queue with the reason `UNMAPPED_SKU`.
5. IF a Shopify_Store order event yields no normalised contact number of exactly 10 digits, THEN THE
   Shopify_Ingestor SHALL create the order with the contact number left empty and SHALL add that order
   to the Review_Queue with the reason `MISSING_CONTACT`.
6. THE Shopify_Ingestor SHALL persist the Shopify order total exactly as supplied by the
   Shopify_Store, without recomputing that total from line items.
7. IF the sum of the line item amounts differs from the Shopify order total by more than 1 rupee, THEN
   THE Shopify_Ingestor SHALL add the order to the Review_Queue with the reason `TOTAL_MISMATCH` and
   SHALL leave the persisted order total equal to the Shopify order total.
8. THE Shopify_Ingestor SHALL record the Shopify order identifier and the Shopify order number on the
   order it creates.
9. THE Shifa_OMS SHALL expose the Review_Queue to ADMIN users showing, for each listed order, the
   Shopify order number and every reason recorded against that order.
10. WHEN a Shopify_Store event carries a Shopify order identifier already recorded on a Shifa_OMS
    order, THE Shopify_Ingestor SHALL resolve that event to that existing order and SHALL create no
    additional order.
11. IF a Shopify_Store order event supplies no address line, no city, no state or no postal code for
    the shipping address, THEN THE Shopify_Ingestor SHALL create the order with each absent field left
    empty and SHALL add the order to the Review_Queue with the reason `INCOMPLETE_ADDRESS`.
12. IF processing a Shopify_Store order event yields the same Review_Queue reason for an order two or
    more times, THEN THE Shopify_Ingestor SHALL record that reason exactly once against that order.

### Requirement 4: Shopify Orders Bypass Admin Approval

**User Story:** As an admin, I want Shopify orders to skip the approval queue, so that shipments
QuikShipX is already moving are not blocked by an internal gate.

#### Acceptance Criteria

1. WHEN the Shopify_Ingestor creates an order, THE Shifa_OMS SHALL record `PENDING_ADMIN_APPROVAL` as
   that order's status and SHALL write exactly one status history row for it with no source status.
2. THE Shifa_OMS SHALL exclude every order whose Order_Channel is `SHOPIFY_API`, including an order
   listed in the Review_Queue, from the admin approval queue that lists orders awaiting an ADMIN
   approval transition and from the count shown for that queue.
3. FOR ALL orders, the recorded status history SHALL form a contiguous chain in which the first row
   has no source status and each subsequent row's source status equals the previous row's target
   status (history-continuity invariant), and each transition applied through Order_Workflow SHALL add
   exactly one row to that chain.
4. IF an order whose Order_Channel is `SHIFA_ADMIN` has no recorded status history row showing a
   transition to `APPROVED` applied by an ADMIN Actor, THEN THE Shifa_OMS SHALL not submit that order
   to QuikShipX and SHALL leave that order at its current status.
5. WHEN the Shifa_OMS records the initial status `PENDING_ADMIN_APPROVAL` for an order whose
   Order_Channel is `SHOPIFY_API`, THE Shifa_OMS SHALL apply exactly one transition to `APPROVED`
   through Order_Workflow using the SYSTEM_Actor within 5 seconds of recording that status,
   irrespective of whether that order is listed in the Review_Queue.
6. IF the transition to `APPROVED` for an order whose Order_Channel is `SHOPIFY_API` fails, THEN THE
   Shifa_OMS SHALL leave that order at status `PENDING_ADMIN_APPROVAL` with its existing status
   history unchanged, SHALL record the failure with its reason in the Integration_Event_Store, and
   SHALL raise an in-app notification to the ADMIN role indicating that automatic approval of that
   order failed.
7. IF an order's Order_Channel is `SHIFA_ADMIN`, THEN THE Shifa_OMS SHALL not apply a transition to
   `APPROVED` using the SYSTEM_Actor.

### Requirement 5: Publishing Shifa Admin Orders to QuikShipX

**User Story:** As an admin, I want approved Shifa-punched orders to be created in QuikShipX
automatically and tagged as ours, so that the packaging team finds them in the QuikShipX portal
alongside the Shopify orders.

#### Acceptance Criteria

1. WHILE QuikShipX integration is enabled by configuration, WHEN an order whose Order_Channel is
   `SHIFA_ADMIN` reaches the status `APPROVED`, THE QuikShipX_Publisher SHALL submit that order to
   QuikShipX within 60 seconds of that status being recorded.
2. THE QuikShipX_Publisher SHALL submit every order to `POST /api/create-order-v1` at the configured
   QuikShipX base URL, carrying the header `Content-Type: application/json` and a body holding the
   `customer_details`, `shipment_details`, `product_details` and `shipper_details` sections, with the
   configured `client_code`, `user_id` and `user_secret` in `shipper_details` and every value
   expressed as a JSON string.
3. THE QuikShipX_Publisher SHALL send the QuikShipX_Order_Reference of an order as that submission's
   `customer_order_id`, and SHALL send the identical value on the first submission of that order and
   on every retry of that order.
4. FOR ALL orders, submitting the same order to QuikShipX two or more times SHALL result in exactly
   one Shipment_Record for that order (publication idempotence property).
5. WHEN QuikShipX returns a success response to a submission, THE Shifa_OMS SHALL persist against that
   order, within 5 seconds of receiving that response, a Shipment_Record holding the
   QuikShipX_Order_Reference sent and every one of the QuikShipX shipment identifier, the AWB/tracking
   number, the courier name, the tracking URL and the label URL that the response carries, leaving
   absent values absent.
6. IF a submission to QuikShipX fails with a transport error, exceeds a 30-second response timeout, or
   returns a temporary-failure response, THEN THE QuikShipX_Publisher SHALL retry that submission up to
   5 times with delays of 30, 60, 120, 240 and 480 seconds.
7. IF every retry of a submission to QuikShipX fails, THEN THE Shifa_OMS SHALL leave the order at
   status `APPROVED`, SHALL create no Shipment_Record for that order, SHALL record the failure with its
   reason and the number of attempts made in the Integration_Event_Store, and SHALL raise exactly one
   in-app notification to the ADMIN role naming the order whose publication failed.
8. WHILE QuikShipX integration is disabled by configuration, THE QuikShipX_Publisher SHALL submit no
   order to QuikShipX and THE Shifa_OMS SHALL enable Fallback_Mode on every order that reaches the
   status `APPROVED` while the integration is disabled.
9. THE Shifa_OMS SHALL submit no order whose Order_Channel is `SHOPIFY_API` to QuikShipX.
10. IF an order already holds a Shipment_Record, THEN THE QuikShipX_Publisher SHALL submit no further
    order-creation request for that order and SHALL leave the stored Shipment_Record unchanged.
11. IF QuikShipX rejects a submission as permanently invalid, THEN THE QuikShipX_Publisher SHALL attempt
    no retry of that submission, THE Shifa_OMS SHALL leave the order at status `APPROVED`, SHALL record
    the rejection in the Integration_Event_Store together with the name of every field QuikShipX
    rejected, and SHALL raise exactly one in-app notification to the ADMIN role naming that order.
12. WHERE the configured QuikShipX secret is the TEST secret, THE Shifa_OMS SHALL mark every
    Shipment_Record created from a submission made with that secret as a test shipment, and SHALL
    display that test marking wherever it displays that order's shipment data.
13. THE QuikShipX_Publisher SHALL format `customer_order_date` as the order's creation date in
    day, full month name and four-digit year form, such as `14 March 2026`, and SHALL send
    `shipment_pay_mode` as `1` when the order has an amount to collect on delivery and `2` otherwise,
    `cod_amount` as the amount remaining to be collected, and `0` as `cod_amount` when
    `shipment_pay_mode` is `2`.

### Requirement 6: QuikShipX Shipment Status Mirroring

**User Story:** As an admin, I want the Shifa Admin Portal to show the same shipment status the
packaging team sees in QuikShipX, so that I track orders in one place.

#### Acceptance Criteria

1. WHERE Status_Feed_Available is enabled by configuration, THE Shifa_OMS SHALL expose exactly one HTTP
   POST endpoint that accepts QuikShipX shipment status events without requiring a JWT and without
   requiring any other Shifa_OMS session credential.
2. WHEN a QuikShipX status event is received, THE Shifa_OMS SHALL record the receipt time and SHALL
   verify the signature value carried by that event against the configured QuikShipX webhook secret
   using a comparison that examines every byte of both values regardless of the position of the first
   mismatch (constant-time comparison).
3. IF a QuikShipX status event carries no signature value, carries an empty signature value, or carries
   a signature value that fails verification, THEN THE Shifa_OMS SHALL respond with HTTP status 401
   within 5 seconds of the receipt time, SHALL write a rejection log entry naming the source and the
   receipt time without persisting the rejected payload, SHALL write no Integration_Event_Store record,
   and SHALL change no order status and update no Shipment_Record.
4. WHEN a QuikShipX status event passes signature verification, THE Shifa_OMS SHALL write one
   Integration_Event_Store record holding the raw payload, the QuikShipX event identifier, the
   referenced shipment identifier, the receipt time and the outcome `RECEIVED`, and SHALL respond with
   HTTP status 200 within 5 seconds of the receipt time.
5. WHEN the Shifa_OMS has responded to a QuikShipX status event that passed signature verification, THE
   QuikShipX_Status_Sync SHALL begin processing that stored event outside the request thread within
   60 seconds of writing the Integration_Event_Store record and SHALL apply every resulting status
   change through Order_Workflow using the SYSTEM_Actor.
6. IF a QuikShipX status event references a shipment identifier that matches no Shipment_Record, THEN
   THE Shifa_OMS SHALL record the event in the Integration_Event_Store with the outcome
   `UNKNOWN_SHIPMENT` and the received shipment identifier, SHALL attempt no retry of that event, and
   SHALL change no order status and update no Shipment_Record.
7. IF a QuikShipX status event carries a status timestamp earlier than or equal to the last
   synchronised status timestamp of that Shipment_Record, THEN THE Shifa_OMS SHALL record the event
   with the outcome `SUPERSEDED`, SHALL change no order status, and SHALL leave the last synchronised
   status token and timestamp of that Shipment_Record unchanged.
8. FOR ALL Shipment_Records, each successive recorded value of the last synchronised status timestamp
   SHALL be greater than or equal to the value it replaces (status monotonicity invariant).
9. FOR ALL QuikShipX status events, processing the same QuikShipX event identifier two or more times
   SHALL leave the resulting order status, the status history rows of that order, and the last
   synchronised status token and timestamp of the Shipment_Record identical to processing that event
   once (idempotence property).
10. WHERE QuikShipX polling is enabled by configuration, THE Shifa_OMS SHALL query QuikShipX for the
    current status of every QuikShipX_Managed_Order whose status is none of `CLOSED`, `COD_COLLECTED`,
    `REJECTED`, `CANCELLED`, `RTO` and `REDISPATCH`, at the configured polling interval whose default
    is 15 minutes and whose configurable range is 5 minutes to 120 minutes, and SHALL apply every
    retrieved status through the same QuikShipX_Status_Sync path used for events.
11. WHEN a QuikShipX status event whose QuikShipX event identifier already exists in the
    Integration_Event_Store is received, THE Shifa_OMS SHALL respond with HTTP status 200 within
    5 seconds of the receipt time, SHALL write no additional Integration_Event_Store record for that
    identifier, SHALL start no further processing of that event, and SHALL change no order status and
    update no Shipment_Record.
12. IF processing of a stored QuikShipX status event fails, THEN THE QuikShipX_Status_Sync SHALL retry
    that processing up to 3 further times with exponential backoff beginning at 30 seconds.
13. IF every retry of processing a stored QuikShipX status event fails, THEN THE Shifa_OMS SHALL record
    the outcome `PROCESSING_FAILED` with the failure reason on that Integration_Event_Store record,
    SHALL leave the order status, its status history rows and the Shipment_Record identical to their
    values before that event was processed, and SHALL raise exactly one in-app notification to the
    ADMIN role within 60 seconds of recording that outcome.
14. WHILE Status_Feed_Available is disabled by configuration, THE Shifa_OMS SHALL accept no QuikShipX
    status event, SHALL query QuikShipX for no status, SHALL apply no status change to any
    QuikShipX_Managed_Order, and SHALL display against every QuikShipX_Managed_Order an indication that
    its fulfilment status is maintained in the QuikShipX portal.
15. WHERE Status_Feed_Available is enabled by configuration, THE QuikShipX_Status_Sync SHALL resolve the
    order a status event refers to by that event's shipment identifier when the event carries one, and
    otherwise by matching the event's order reference against the stored QuikShipX_Order_Reference.

### Requirement 7: Status Mapping and Legal Transition Paths

**User Story:** As an admin, I want QuikShipX statuses such as *Label Printed* and *Ready for Pickup*
to land on the right Shifa stage, so that the Orders page stage tabs stay meaningful.

#### Acceptance Criteria

1. THE Status_Mapper SHALL map each configured QuikShipX status token to exactly one Shifa_OMS
   `OrderStatus`, comparing a received token against a configured token after trimming leading and
   trailing whitespace and ignoring letter case, accepting configured tokens of 1 to 64 characters,
   permitting two or more distinct tokens to map to the same `OrderStatus`, and holding at most 200
   configured tokens.
2. THE Status_Mapper SHALL provide a default mapping, applied for every token that carries no
   configuration override, that maps the QuikShipX token for a printed label to `LABEL_GENERATED`, the
   token for ready-for-pickup to `PACKED`, the token for pickup completed to `DISPATCHED`, the token
   for in-transit to `IN_TRANSIT`, the token for out-for-delivery to `OUT_FOR_DELIVERY`, the token for
   delivered to `DELIVERED`, the token for a return to origin to `RTO`, and the token for a customer
   refusal to `CUSTOMER_REJECTED`.
3. WHEN an ADMIN requests the configured status mapping, THE Shifa_OMS SHALL return within 5 seconds
   every configured token together with its mapped `OrderStatus` and whether that entry comes from the
   default mapping or from a configuration override.
4. IF a QuikShipX status event carries a status token that the Status_Mapper does not map, THEN THE
   Shifa_OMS SHALL record the event with the outcome `UNMAPPED_STATUS` and the received token, SHALL
   change no order status, and SHALL raise exactly one in-app notification to the ADMIN role naming
   the unmapped token within 60 seconds of recording the event.
5. WHEN the mapped target status is not directly reachable from the order's current status, THE
   Status_Mapper SHALL compute the transition path with the fewest transitions from the current status
   to the target status, resolving two or more paths of equal length by selecting the path whose
   statuses come first in the `OrderStatusStateMachine` lifecycle order, and THE
   QuikShipX_Status_Sync SHALL apply every transition on that path in order through Order_Workflow
   inside a single transaction and SHALL record the event with the outcome `APPLIED` and the ordered
   list of statuses applied.
6. FOR ALL current-status and target-status pairs for which a legal path exists, every transition
   returned by the Status_Mapper SHALL be legal under `OrderStatusStateMachine`, the returned path
   SHALL contain no repeated status and at most 13 transitions, and two invocations for the same
   current-status and target-status pair SHALL return the identical ordered path (path legality and
   determinism property).
7. IF no legal transition path exists from the order's current status to the mapped target status,
   THEN THE Shifa_OMS SHALL record the event with the outcome `ILLEGAL_TRANSITION` together with the
   received token, the order's current status and the mapped target status, SHALL change no order
   status, and SHALL raise exactly one in-app notification to the ADMIN role within 60 seconds of
   recording the event.
8. WHEN the mapped target status equals the order's current status, THE QuikShipX_Status_Sync SHALL
   apply no transition, SHALL write no status history row, SHALL update only the last synchronised
   status token and timestamp of the Shipment_Record, and SHALL record the event with the outcome
   `NO_CHANGE`.
9. IF Order_Workflow rejects any transition on a computed path, THEN THE QuikShipX_Status_Sync SHALL
   roll back every transition already applied for that event so that the order status and its status
   history rows are identical to their values before the event was processed, SHALL record the event
   with the outcome `TRANSITION_FAILED` together with the rejected target status, and SHALL raise
   exactly one in-app notification to the ADMIN role within 60 seconds of recording the event.
10. IF an Actor that does not hold the ADMIN role requests the configured status mapping, THEN THE
    Shifa_OMS SHALL deny that request with an authorization error, SHALL return no mapping entry, and
    SHALL leave the configured status mapping unchanged.
11. IF a configured mapping entry names a target value that is not a member of `OrderStatus`, or two or
    more entries map the same trimmed and case-insensitive token, THEN THE Shifa_OMS SHALL reject that
    entry, SHALL retain the default mapping for the affected token, and SHALL raise exactly one in-app
    notification to the ADMIN role naming the rejected entry within 60 seconds of loading the
    configuration.

### Requirement 8: Integration Payload Serialization and Parsing

**User Story:** As a developer, I want the QuikShipX and Shopify payload translation to be provably
reversible, so that field-level integration bugs are caught before they reach production.

#### Acceptance Criteria

1. WHEN the QuikShipX_Publisher submits an order to QuikShipX, THE Shifa_OMS SHALL serialize that
   order within 2 seconds into a body carrying, as JSON strings, `customer_details` holding
   `customer_full_name`, `customer_phone_number`, `customer_full_address`, `customer_pincode`,
   `customer_order_id`, `customer_order_date`, `customer_address_type`, `customer_email_id`,
   `customer_alternate_phone_number` and `customer_landmark`; `shipment_details` holding
   `shipment_package_type`, `shipment_dead_weight_in_grams`, `shipment_length`, `shipment_width`,
   `shipment_height`, `shipment_pickup_warehouse_id`, `shipment_shipping_mode`, `shipment_pay_mode`,
   `order_amount`, `cod_amount`, `commodity_amount`, `shipping_amount`, `discount_amount` and
   `discount_coupon_name`; `product_details` holding one entry per order line, up to 200 entries, each
   with `product_name`, `product_category`, `product_sku_code`, `product_tax_rate`, `product_hsn_code`,
   `product_amount`, `product_discount` and `product_quantity`; and `shipper_details` holding
   `client_code`, `user_id` and `user_secret`.
2. WHEN QuikShipX returns an order-creation response, THE Shifa_OMS SHALL parse that response into a
   Shipment_Record, treating the QuikShipX shipment identifier, the AWB/tracking number, the courier
   name, the tracking URL and the label URL each as optional, and SHALL treat the submission as
   accepted on a success response even when that response carries none of those values.
3. WHEN the Shifa_OMS parses a QuikShipX status event payload, THE Shifa_OMS SHALL produce a status
   token of 1 to 100 characters after removing leading and trailing whitespace, a shipment identifier
   of 1 to 100 characters and a status timestamp carrying an explicit date, time and timezone offset
   normalised to a single time instant, and SHALL treat all three of those values as required fields
   of that payload.
4. WHEN the Shifa_OMS parses a Shopify_Store order webhook payload, THE Shifa_OMS SHALL produce a
   Shopify order model holding the Shopify order identifier, the Shopify order number, the customer
   name, the customer contact number as supplied, the shipping address, the order total and up to 200
   line items each with its name, SKU, quantity and amount, and SHALL complete that parsing within
   2 seconds for a payload up to the configured maximum payload size.
5. FOR ALL QuikShipX order payloads produced by the Shifa_OMS, parsing that payload back into a
   submission model SHALL produce a model in which every field equals the corresponding field of the
   model that produced the payload, with monetary amounts compared to 2 decimal places and line items
   compared by count, by position and by every line item value (round-trip property).
6. FOR ALL Shopify order models, serializing the model to a Shopify webhook payload and parsing that
   payload SHALL produce a model in which every field equals the corresponding field of the original
   model, with monetary amounts compared to 2 decimal places and line items compared by count, by
   position and by every line item value (round-trip property).
7. IF a QuikShipX or Shopify_Store payload omits a field the Shifa_OMS requires, THEN THE Shifa_OMS
   SHALL record that event in the Integration_Event_Store with the outcome `MALFORMED_PAYLOAD` and
   the name of every omitted required field, SHALL attempt no retry of that payload, SHALL create no
   order and change no existing order, and SHALL raise an in-app notification to the ADMIN role
   indicating that a payload could not be parsed.
8. WHEN a QuikShipX or Shopify_Store payload carries fields the Shifa_OMS does not recognise, THE
   Shifa_OMS SHALL parse that payload successfully, SHALL ignore those fields, and SHALL produce a
   model in which every field equals the corresponding field of the model parsed from the same
   payload with those unrecognised fields removed.
9. IF a field the Shifa_OMS requires is present in a QuikShipX or Shopify_Store payload but holds a
   value that cannot be parsed into that field's expected form, or holds a line item quantity below 1
   or above 9,999, or holds a monetary amount below 0.00 or above 999,999,999.99, or holds a text
   value longer than that field's permitted length, THEN THE Shifa_OMS SHALL record that event in the
   Integration_Event_Store with the outcome `MALFORMED_PAYLOAD`, the name of that field and the
   reason the value was rejected, SHALL attempt no retry of that payload, and SHALL create no order
   and change no existing order.
10. IF a QuikShipX or Shopify_Store payload carries more than 200 line items, THEN THE Shifa_OMS SHALL
    record that event in the Integration_Event_Store with the outcome `MALFORMED_PAYLOAD`, the name of
    the line item collection and the received line item count, and SHALL create no order and change no
    existing order.
11. THE Shifa_OMS SHALL compose `customer_full_address` from the order's address line, city and state
    joined in that order by a comma and a space, omitting each part that is empty, and SHALL send the
    order's postal code as `customer_pincode` rather than as part of `customer_full_address`.
12. THE Shifa_OMS SHALL send `product_tax_rate` as the product's stored GST rate, `product_hsn_code` as
    the product's stored HSN code, `product_amount` as the order line's unit rate, and
    `product_quantity` as the order line's quantity, sending an empty string for a stored value that is
    absent.

### Requirement 9: QuikShipX Authority Over Managed Stages

**User Story:** As an admin, I want QuikShipX to be the only source of fulfilment status for the
orders it manages, so that the Shifa Admin Portal never disagrees with the courier portal.

#### Acceptance Criteria

1. WHILE an order is a QuikShipX_Managed_Order, THE Transition_Authority SHALL deny every status
   transition requested by a human Actor of any role, responding with HTTP status 403 and an error
   message indicating that QuikShipX owns fulfilment for that order, SHALL leave the stored order
   status unchanged and SHALL write no status history row for that request.
2. WHILE an order is a QuikShipX_Managed_Order, THE Transition_Authority SHALL permit a transition
   into a Managed_Stage requested by the SYSTEM_Actor, leaving transition legality to be evaluated by
   Order_Workflow.
3. WHERE an order has Fallback_Mode enabled, THE Transition_Authority SHALL apply the pre-existing
   role-based transition rules to that order.
4. WHILE an order's Order_Channel is `SHOPIFY_API`, THE Shifa_OMS SHALL reject every request from any
   Actor that would change that order's customer details, shipping address, line items, line item
   quantities or money amounts, responding with HTTP status 409 and an error message indicating that
   Shopify_Store is the system of record for that order content, SHALL leave every stored field of
   that order unchanged, and SHALL apply no other field change carried by that request.
5. WHILE an order's Order_Channel is `SHOPIFY_API`, THE Shifa_OMS SHALL reject a cancellation request
   from any Actor other than the SYSTEM_Actor, responding with HTTP status 409 and an error message
   indicating that cancellation of that order originates outside Shifa_OMS, and SHALL leave the stored
   order status unchanged.
6. WHILE QuikShipX integration is enabled by configuration, THE Packing_Queue SHALL exclude every
   QuikShipX_Managed_Order from each of its three queues, awaiting packing, awaiting handover and
   awaiting dispatch, and from the order count reported for each of those queues.
7. WHERE an order has Fallback_Mode enabled, THE Packing_Queue SHALL include that order in exactly one
   queue, awaiting packing while its status is `LABEL_GENERATED`, awaiting handover while its status
   is `PACKED` and awaiting dispatch while its status is `HANDED_TO_DELIVERY`, and SHALL include that
   order in no queue while its status is any other `OrderStatus`.
8. THE Shifa_OMS SHALL record on every status history row exactly one non-null transition source, that
   source being either the identifier and role of the human Actor that requested the transition, or the
   SYSTEM_Actor together with the Integration_Event_Store identifier of the QuikShipX event that
   originated the transition when the transition originated from a QuikShipX event.
9. IF a human Actor submits a Packing_Queue scan, pack, handover or dispatch request for a
   QuikShipX_Managed_Order, THEN THE Shifa_OMS SHALL reject that request with HTTP status 403 and an
   error message indicating that the shipment is handled in the QuikShipX portal, and SHALL leave the
   stored order status unchanged.
10. WHILE an order holds no Shipment_Record, THE Transition_Authority SHALL apply the pre-existing
    role-based transition rules to that order.

### Requirement 10: Shipment Tracking Data and Label Access

**User Story:** As an admin, I want the AWB number, courier, tracking link and QuikShipX label on the
order in Shifa OMS, so that I can answer a customer query without opening the QuikShipX portal.

#### Acceptance Criteria

1. WHEN a caller whose role is ADMIN, ACCOUNTANT, SALESPERSON, TEAM_LEAD or PACKING_USER opens the
   order detail view of a QuikShipX_Managed_Order, THE Shifa_OMS SHALL display the AWB/tracking number
   complete and untruncated for values up to 64 characters, the courier name, and the last synchronised
   status token together with the timestamp of that status, each taken from that order's
   Shipment_Record.
2. WHILE the viewport width is 768 CSS pixels or greater, THE Shifa_OMS SHALL display the AWB/tracking
   number of every QuikShipX_Managed_Order in the order list view.
3. WHERE a Shipment_Record holds a tracking URL, THE Shifa_OMS SHALL present that tracking URL as a
   link in the order detail view that opens the QuikShipX tracking page for that shipment.
4. WHERE a Shipment_Record holds a label URL, THE Shifa_OMS SHALL present that label URL as a link in
   the order detail view to the ADMIN and PACKING_USER roles and to no other role, and SHALL fetch no
   label document from QuikShipX itself.
5. WHILE an order is a QuikShipX_Managed_Order whose Shipment_Record holds no label URL, THE Shifa_OMS
   SHALL display in the order detail view an indication that the shipping label is downloaded from the
   QuikShipX portal.
6. THE Shifa_OMS SHALL present the QuikShipX_Order_Reference of a QuikShipX_Managed_Order in the order
   detail view, so that a packer can locate that shipment in the QuikShipX portal.
7. WHILE an order is a QuikShipX_Managed_Order, THE Shifa_OMS SHALL hide the Internal_Label_Service
   print action for that order in the Shifa Admin Portal.
8. THE Shifa_OMS SHALL keep the existing internal label endpoints available to the ADMIN and
   PACKING_USER roles for every order, so that a Fallback_Mode label remains reachable.
9. WHEN a caller supplies an order search term of 3 or more characters, THE Shifa_OMS SHALL return
   every order within that caller's pre-existing role scope whose AWB/tracking number contains that
   term, ignoring letter case, through the existing order search endpoint.
10. WHERE an order holds no Shipment_Record, THE Shifa_OMS SHALL display no AWB/tracking number, no
    courier name, no tracking link and no label download action for that order, and SHALL present no
    error for their absence.
11. WHERE a Shipment_Record holds no tracking URL or holds no label URL, THE Shifa_OMS SHALL omit the
    corresponding tracking link or label download action while continuing to display every other
    Shipment_Record value that is present.
12. WHILE the viewport width is below 768 CSS pixels, THE Shifa_OMS SHALL display the AWB/tracking
    number of every QuikShipX_Managed_Order on that order's card in the order list view without
    page-level horizontal scrolling.

### Requirement 11: Channel Differentiation in the Admin Portal

**User Story:** As an admin, I want to see and filter which orders came from Shopify and which came
from our own portal, so that I can track each channel separately.

#### Acceptance Criteria

1. THE Shifa_OMS SHALL display exactly one channel badge on every order row in the order list view and
   on the order detail view, reading `Shopify` when that order's Order_Channel is `SHOPIFY_API` and
   `Shifa Admin` when that order's Order_Channel is `SHIFA_ADMIN` or the legacy stored value
   `SALESPERSON`.
2. THE Shifa_OMS SHALL offer a channel filter on the order list with exactly three selectable options,
   `Shopify` matching Order_Channel `SHOPIFY_API`, `Shifa Admin` matching Order_Channel `SHIFA_ADMIN`
   or the legacy stored value `SALESPERSON`, and `All channels` selected by default, and SHALL return
   only the orders matching the selected option, or every order within the caller's authorised scope
   while `All channels` is selected.
3. THE Shifa_OMS SHALL apply the channel filter server-side before pagination, so that every page of a
   paginated result set and the reported total result count contain only orders matching the selected
   option, and the number of orders returned across all pages equals that reported total count.
4. WHEN a caller requests the order list with both a channel filter option other than `All channels`
   and one of the 9 `statusGroup` lifecycle stage groups selected, THE Shifa_OMS SHALL return only the
   orders that satisfy both filters, and SHALL return an empty result set without an error when no
   order satisfies both.
5. WHEN a caller requests the order list with both a channel filter option other than `All channels`
   and a free-text search term of 1 to 100 characters, THE Shifa_OMS SHALL return only the orders that
   satisfy both, and SHALL return an empty result set without an error when no order satisfies both.
6. WHERE the caller's role is SALESPERSON or TEAM_LEAD, THE Order_Query SHALL exclude every order whose
   Order_Channel is `SHOPIFY_API` from the order list, from the order search results, from the reported
   total result count and from every order count derived from Order_Query, irrespective of which channel
   filter option that caller selects.
7. WHERE the caller's role is ADMIN or ACCOUNTANT, THE Shifa_OMS SHALL return orders of both
   Order_Channel values within that caller's authorised scope while `All channels` is selected.
8. WHILE the viewport width is 360 CSS pixels, THE Shifa_OMS SHALL render the channel filter control
   and the channel badge fully inside the viewport with no page-level horizontal scrolling, with the
   channel filter control presenting a touch target of at least 44 by 44 CSS pixels.
9. IF a caller whose role is SALESPERSON or TEAM_LEAD requests the order detail of an order whose
   Order_Channel is `SHOPIFY_API`, THEN THE Order_Query SHALL return the same not-found result it
   returns for an order outside that caller's scope and SHALL disclose no field of that order.
10. IF a channel filter value other than `SHOPIFY_API`, `SHIFA_ADMIN` or the `All channels` option is
    supplied, THEN THE Shifa_OMS SHALL reject that request with an error message naming the
    unrecognised channel value, SHALL return no orders and SHALL leave the caller's previously applied
    filter selection unchanged.

### Requirement 12: Channel Reporting and Dashboard Split

**User Story:** As an admin, I want dashboard and report numbers broken down by channel, so that I can
compare Shopify performance against the Shifa Admin Portal.

#### Acceptance Criteria

1. WHEN a caller requests the Channel_Report for a date window, THE Shifa_OMS SHALL return exactly one
   row per Order_Channel value, `SHOPIFY_API` and `SHIFA_ADMIN`, each row holding the order count, the
   revenue and the delivered count of the orders whose creation date falls within that window with both
   window boundary dates included, counting an order whose stored Order_Channel is the legacy value
   `SALESPERSON` in the `SHIFA_ADMIN` row, and SHALL return that report within 10 seconds for a window
   spanning up to 366 days.
2. WHERE the caller's role is ADMIN or ACCOUNTANT, THE Shifa_OMS SHALL offer the Channel_Report as a
   selectable report type in the existing report catalogue.
3. WHEN a caller whose role is ADMIN or ACCOUNTANT requests an Excel export or a PDF export of the
   Channel_Report through the existing report export path for a date window, THE Shifa_OMS SHALL produce
   a file holding the same rows, the same columns and the same values as the Channel_Report returned for
   that same date window.
4. FOR ALL date windows, the sum of the per-channel order counts in the Channel_Report SHALL equal the
   number of orders whose creation date falls within that window, with each such order counted in
   exactly one channel row (channel-partition invariant).
5. THE Shifa_OMS SHALL display on the ADMIN dashboard exactly one order count and exactly one revenue
   figure per Order_Channel, `SHOPIFY_API` and `SHIFA_ADMIN`, for the dashboard's existing reporting
   period, displaying zero for a channel that holds no order in that period, and counting the legacy
   stored value `SALESPERSON` as `SHIFA_ADMIN`.
6. THE Shifa_OMS SHALL compute the revenue of a Channel_Report row as the sum of the order totals of the
   orders in that row excluding every order whose status is `REJECTED` or `CANCELLED`, matching the
   existing revenue rule, and SHALL compute the delivered count of that row as the number of orders in
   that row whose status is `DELIVERED`, `COD_COLLECTED` or `CLOSED`.
7. THE Shifa_OMS SHALL keep every report type present in the report catalogue before this feature
   selectable, and for the same date window and the same caller role SHALL produce for each of those
   report types the same columns, the same rows and the same values as before this feature, with the
   Channel_Report added as an additional report type.
8. IF a caller whose role is other than ADMIN or ACCOUNTANT requests the Channel_Report or an export of
   the Channel_Report, THEN THE Shifa_OMS SHALL reject that request with an error indicating that the
   caller is not authorised for that report type, SHALL return no Channel_Report row and SHALL disclose
   no per-channel order count, revenue or delivered count.
9. IF a Channel_Report request omits either window boundary date, or holds a window end date earlier
   than its window start date, or holds a window spanning more than 366 days, THEN THE Shifa_OMS SHALL
   reject that request with an error naming the invalid date window, SHALL return no Channel_Report row
   and SHALL produce no export file.
10. WHEN a caller requests the Channel_Report for a date window in which no order was created, THE
    Shifa_OMS SHALL return both channel rows with an order count of 0, a revenue of 0 and a delivered
    count of 0, and SHALL return no error.

### Requirement 13: Internal Fulfilment Fallback

**User Story:** As an admin, I want to fall back to our own label and packing flow when QuikShipX is
unavailable, so that shipments continue when the courier integration is down.

#### Acceptance Criteria

1. THE Shifa_OMS SHALL provide exactly one system-wide configuration setting for QuikShipX integration
   whose value is either enabled or disabled, SHALL apply the value in effect at the time of each
   fulfilment decision, and SHALL treat the integration as disabled while no value is configured.
2. WHILE QuikShipX integration is disabled by configuration, THE Shifa_OMS SHALL run the pre-existing
   Internal_Label_Service label generation on approval and the pre-existing Packing_Queue flow for
   every order of every Order_Channel value, and SHALL create no Shipment_Record for those orders.
3. THE Shifa_OMS SHALL allow an ADMIN, and no other role, to enable Fallback_Mode for an individual
   order whose current status is not one of `DELIVERED`, `COD_COLLECTED`, `CLOSED`, `REJECTED`,
   `CANCELLED`, `RTO` or `REDISPATCH`, and SHALL treat a repeated enable request for an order that
   already has Fallback_Mode enabled as leaving that order unchanged.
4. WHEN an ADMIN enables Fallback_Mode for an order, THE Shifa_OMS SHALL generate that order's internal
   label through the Internal_Label_Service and SHALL keep the internal label print action available
   for that order to the ADMIN and PACKING_USER roles.
5. WHEN an ADMIN enables Fallback_Mode for an order, THE Shifa_OMS SHALL record exactly one audit event
   naming the ADMIN who enabled it, the order and the time of enabling, in the same transaction that
   persists the Fallback_Mode flag.
6. WHILE an order has Fallback_Mode enabled, THE QuikShipX_Status_Sync SHALL apply no status change to
   that order, and SHALL record every QuikShipX status event received for that order in the
   Integration_Event_Store with an outcome indicating the event was skipped because Fallback_Mode is
   enabled.
7. THE Shifa_OMS SHALL keep the existing `OrderStatusGroup` grouping of every `OrderStatus` value
   unchanged, so that each status belongs to exactly one group.
8. WHEN an ADMIN enables Fallback_Mode for an order, THE Shifa_OMS SHALL include that order in exactly
   one Packing_Queue queue, awaiting packing while its status is `LABEL_GENERATED`, awaiting handover
   while its status is `PACKED` and awaiting dispatch while its status is `HANDED_TO_DELIVERY`, and
   SHALL include that order in no Packing_Queue queue while its status holds any other value.
9. IF the Internal_Label_Service fails to render the internal label while Fallback_Mode is being
   enabled for an order, THEN THE Shifa_OMS SHALL keep Fallback_Mode enabled for that order, SHALL
   leave the order's status unchanged, SHALL return an error indicating that label generation failed,
   and SHALL keep the internal label print action available for a further attempt.
10. IF an ADMIN requests Fallback_Mode for an order whose current status is `DELIVERED`,
    `COD_COLLECTED`, `CLOSED`, `REJECTED`, `CANCELLED`, `RTO` or `REDISPATCH`, THEN THE Shifa_OMS SHALL
    leave Fallback_Mode disabled for that order, SHALL leave that order's status and Packing_Queue
    membership unchanged, SHALL record no audit event for the request, and SHALL return an error
    indicating that the order is not eligible for Fallback_Mode.

### Requirement 14: Integration Health and Replay

**User Story:** As an admin, I want to see integration failures and retry them, so that a dropped
webhook or a rejected push does not silently lose an order.

#### Acceptance Criteria

1. THE Shifa_OMS SHALL provide an Integration_Health_Console that accepts requests only from an Actor
   holding the ADMIN role.
2. WHEN an ADMIN requests the Integration_Health_Console failure list, THE Integration_Health_Console
   SHALL return within 5 seconds every Integration_Event_Store record whose recorded outcome is one of
   `PROCESSING_FAILED`, `UNKNOWN_SHIPMENT`, `UNMAPPED_STATUS`, `ILLEGAL_TRANSITION`,
   `TRANSITION_FAILED` and `MALFORMED_PAYLOAD` and whose receipt time falls inside the retention
   window, showing for each record the source, external event identifier, receipt time, outcome and
   failure reason, ordered by receipt time with the most recent first, in pages of at most 100 records,
   and SHALL return an empty list without an error when no such record exists.
3. WHEN an ADMIN requests the Integration_Health_Console publication-failure list, THE
   Integration_Health_Console SHALL return within 5 seconds every order whose Order_Channel is
   `SHIFA_ADMIN` that holds no Shipment_Record and for which an Integration_Event_Store record reports
   a QuikShipX publication failure after the final retry, showing for each order the order code, the
   failure reason, the number of submission attempts made and the time of the last attempt, ordered by
   that time with the most recent first, in pages of at most 100 orders, and SHALL return an empty list
   without an error when no such order exists.
4. WHEN an ADMIN invokes replay for an Integration_Event_Store record, THE Shifa_OMS SHALL begin
   processing the stored raw payload through the same path used for a live delivery from that source
   within 60 seconds of the invocation, SHALL record against that record the replay time, the
   identifier of the invoking ADMIN and the resulting outcome, and SHALL record an audit event naming
   that ADMIN and that record.
5. FOR ALL Integration_Event_Store records, replaying a record whose recorded outcome is `APPLIED` or
   `NO_CHANGE` SHALL leave the status of every affected order, the status history rows of every
   affected order, and the last synchronised status token and timestamp of every affected
   Shipment_Record identical to their values before that replay, and SHALL create no additional order
   and no additional Shipment_Record (replay idempotence property).
6. WHEN an ADMIN invokes retry for a failed QuikShipX publication, THE QuikShipX_Publisher SHALL
   resubmit that order to QuikShipX within 60 seconds of the invocation using the identical idempotency
   reference sent on the original submission of that order, and SHALL result in at most one
   Shipment_Record for that order across the original submission and every retry.
7. WHEN an ADMIN requests the ADMIN dashboard, THE Shifa_OMS SHALL display within 5 seconds the count
   of unresolved integration failures, counting each Integration_Event_Store record inside the
   retention window whose latest recorded outcome is one of the six outcomes named in criterion 2 and
   for which no later replay recorded the outcome `APPLIED` or `NO_CHANGE`, and SHALL display the value
   0 when no such record exists.
8. THE Shifa_OMS SHALL retain each Integration_Event_Store record together with its raw payload for a
   retention window whose default is 30 days from that record's receipt time and whose configurable
   range is 30 days to 365 days, and SHALL keep every retained record replayable throughout that
   window.
9. IF an Actor that does not hold the ADMIN role requests the Integration_Health_Console, invokes
   replay of an Integration_Event_Store record, or invokes retry of a failed QuikShipX publication,
   THEN THE Shifa_OMS SHALL deny that request with an authorization error, SHALL disclose no
   Integration_Event_Store record and no order field, SHALL start no replay and no resubmission, and
   SHALL leave every order unchanged.
10. IF a replay invoked by an ADMIN fails, THEN THE Shifa_OMS SHALL record the outcome
    `PROCESSING_FAILED` with the failure reason against that Integration_Event_Store record, SHALL
    leave the status of every affected order, its status history rows and every Shipment_Record
    identical to their values before that replay, and SHALL raise exactly one in-app notification to
    the ADMIN role naming that record within 60 seconds of recording that outcome.
11. IF an ADMIN invokes replay for an Integration_Event_Store record whose receipt time falls outside
    the retention window, or invokes a replay or a retry while a previous replay of that record or a
    previous resubmission of that order is still in progress, THEN THE Shifa_OMS SHALL reject that
    invocation with an error message indicating which of those conditions applies, SHALL start no
    further processing, and SHALL leave every order and every Shipment_Record unchanged.

### Requirement 15: Notifications, Audit and Backward Compatibility

**User Story:** As an admin, I want the existing notification and audit behaviour to stay correct
across both channels, so that customers are not messaged twice and every machine action is traceable.

#### Acceptance Criteria

1. WHEN the QuikShipX_Status_Sync applies a status change to an order, THE Shifa_OMS SHALL fan out
   notifications through the existing `NotificationMatrix` in the same transaction that persists that
   status change, enqueueing exactly one per-step customer WhatsApp message for that status, exactly one
   customer email only when the new status is `APPROVED`, `DISPATCHED` or `DELIVERED`, and exactly one
   in-app notification per staff role the `NotificationMatrix` targets for that status, attributing each
   to the SYSTEM_Actor.
2. WHERE an order's Order_Channel is `SHOPIFY_API` AND customer messaging suppression is enabled by
   configuration, THE Shifa_OMS SHALL enqueue no customer-facing WhatsApp message and no customer-facing
   email for that order, and SHALL enqueue every in-app notification the `NotificationMatrix` targets
   for that status change.
3. WHEN the Shopify_Ingestor creates an order, THE Shifa_OMS SHALL record exactly one audit event naming
   the created order, the Shopify order identifier and the Order_Channel `SHOPIFY_API`, in the same
   transaction that persists that order.
4. WHEN the QuikShipX_Publisher submits an order, THE Shifa_OMS SHALL record exactly one audit event
   naming that order and the QuikShipX shipment identifier returned by QuikShipX, in the same
   transaction that persists the Shipment_Record.
5. THE Shifa_OMS SHALL apply every schema change for this feature as one or more new Flyway migrations
   numbered V49 or higher, and Flyway validation SHALL pass against the recorded checksums of every
   migration numbered V48 and lower.
6. THE Shifa_OMS SHALL keep every field present in an existing order endpoint response before this
   feature present in that response with the same name, the same type and the same meaning, and SHALL
   add every field introduced by this feature as an additional field of that response.
7. WHILE QuikShipX integration is disabled by configuration, THE Shifa_OMS SHALL generate the internal
   label on approval, SHALL place each order in the Packing_Queue queue matching its status, SHALL
   permit exactly the status transitions the pre-existing role-based rules permitted, SHALL require no
   request field that was not required before this feature, and SHALL make no outbound call to
   QuikShipX.
8. THE Shifa_OMS SHALL read the QuikShipX credentials and the Shopify webhook secret from environment
   configuration, SHALL hold no value of either in the source repository, and SHALL disclose no value of
   either in an API response, a log entry, an audit event or an Integration_Event_Store record.
9. THE Shifa_OMS SHALL access QuikShipX through a single client contract for which exactly one
   implementation is active at a time, selected by configuration between a live implementation and a
   mock implementation, and the mock implementation SHALL make no outbound network request.
10. WHEN the QuikShipX_Status_Sync processes a status event whose mapped target status equals the order's
    current status, THE Shifa_OMS SHALL apply no status change, SHALL write no status history row, and
    SHALL enqueue no customer WhatsApp message, no customer email and no in-app notification for that
    event.
11. WHERE an order's Order_Channel is `SHIFA_ADMIN`, THE Shifa_OMS SHALL enqueue the same customer
    WhatsApp messages, customer emails and staff in-app notifications for each status change that the
    `NotificationMatrix` enqueued for that status change before this feature.
12. IF the QuikShipX credentials or the Shopify webhook secret are absent from the environment
    configuration, THEN THE Shifa_OMS SHALL submit no order to QuikShipX, SHALL record a failure in the
    Integration_Event_Store naming the missing configuration setting without its value, SHALL surface
    that failure in the Integration_Health_Console, and SHALL leave every order status unchanged.

### Requirement 16: Shipment Defaults and Publication Readiness

**User Story:** As an admin, I want to set our pickup warehouse, packaging and parcel defaults once, so
that orders can be published to QuikShipX without a salesperson entering shipping logistics on every
order.

#### Acceptance Criteria

1. THE Shifa_OMS SHALL hold exactly one set of Shipment_Defaults, comprising the pickup warehouse
   identifier, the package type, the shipping mode, the dead weight in grams, the length in
   centimetres, the width in centimetres, the height in centimetres, the shipping amount and the
   default product category.
2. WHEN an ADMIN requests the Shipment_Defaults, THE Shifa_OMS SHALL return every value in that set,
   and SHALL return an indication of which required values are unset.
3. THE Shifa_OMS SHALL allow an ADMIN, and no other role, to change the Shipment_Defaults, and SHALL
   record exactly one audit event naming the ADMIN and each changed value.
4. THE Shifa_OMS SHALL accept a package type of either `1` for a flyer or `2` for cardboard, a shipping
   mode of either `1` for surface or `2` for express, a dead weight of 1 to 50,000 grams, each dimension
   of 1 to 200 centimetres, and a shipping amount of 0.00 to 99,999.99, and SHALL reject any other value
   with an error naming the rejected field.
5. WHEN the QuikShipX_Publisher submits an order, THE Shifa_OMS SHALL supply the Shipment_Defaults for
   `shipment_package_type`, `shipment_shipping_mode`, `shipment_dead_weight_in_grams`,
   `shipment_length`, `shipment_width`, `shipment_height`, `shipment_pickup_warehouse_id` and
   `shipping_amount`, and SHALL supply the Shipment_Defaults product category for `product_category`
   for every order line whose product holds no category.
6. IF the pickup warehouse identifier is unset when an order becomes eligible for publication, THEN THE
   Shifa_OMS SHALL submit no order to QuikShipX, SHALL leave that order at status `APPROVED`, SHALL
   record the reason in the Integration_Event_Store naming the unset Shipment_Defaults value, and SHALL
   raise exactly one in-app notification to the ADMIN role.
7. WHERE a product holds a stored dead weight in grams, THE Shifa_OMS SHALL send
   `shipment_dead_weight_in_grams` as the sum over the order's lines of that product's stored dead
   weight multiplied by that line's quantity, using the Shipment_Defaults dead weight for a product
   that holds none.
8. THE Shifa_OMS SHALL display the Shipment_Defaults on the Settings page to the ADMIN role, and SHALL
   render that section within the viewport at a width of 360 CSS pixels without page-level horizontal
   scrolling.
