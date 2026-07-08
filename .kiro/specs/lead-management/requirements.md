# Requirements Document

## Introduction

This feature adds **Lead Management & a Sales Pipeline** to the Shifa OMS — the first item from
`docs/Sales-Enablement-and-AI-Insights.md`. Today a customer only becomes visible in the system once
a salesperson has already punched a full order; the many leads that arrive via WhatsApp, Instagram,
Facebook, Google, and offline are tracked nowhere. This feature captures a lead the moment it arrives,
lets the salesperson work it through a pipeline (NEW → CONTACTED → QUOTED → WON / LOST), and converts a
won lead into an order with the details pre-filled.

It is an enhancement that reuses the existing stack and conventions:
- Reuses the existing `LeadSource` enum (`WHATSAPP, INSTAGRAM, FACEBOOK, GOOGLE, OFFLINE, OTHER`) and the
  four roles (`ADMIN`, `SALESPERSON`, `PACKING_USER`, `ACCOUNTANT`).
- Reuses salesperson scoping (`SalespersonScopeResolver`) so a salesperson sees only their own leads,
  exactly like orders.
- Reuses the order-creation path (`POST /api/orders`) for conversion, and the notification outbox for
  follow-up reminders, and the audit trail for status changes.
- A new additive Flyway migration creates the lead tables (next version after the current highest;
  never edits an applied migration). The UI follows the mobile-first redesign language already shipped.

This feature is the foundation for later funnel/conversion analytics and salesperson productivity
insights. It is scoped to **requirements only** here; design and tasks follow.

## Glossary

- **OMS**: The Shifa Order Management System (the admin application + its backend).
- **System**: The OMS backend and admin app acting together.
- **Lead**: A prospective sale captured before it becomes an order — a person who enquired via a channel.
- **Lead_Source**: The origin channel of the lead, from the existing `LeadSource` set.
- **Lead_Status**: The pipeline stage of a lead: `NEW`, `CONTACTED`, `QUOTED`, `WON`, or `LOST`.
- **Lead_Owner**: The salesperson who owns/created the lead (the `ADMIN` may reassign/act on any lead).
- **Pipeline**: The set of active (non-terminal) leads grouped by Lead_Status.
- **Terminal_Lead_Status**: `WON` or `LOST` — a lead with no further pipeline movement.
- **Follow_Up_Date**: A date the Lead_Owner intends to next contact the lead.
- **Lost_Reason**: The categorized reason a lead did not convert (e.g. price, out-of-stock, no-response, other).
- **Convert**: Creating an order from a `QUOTED`/active lead, which marks the lead `WON` and links it to the order.
- **Lead_Service / Order_Service / Notification_Service / Audit_Service**: existing/new backend modules.

## Requirements

### Requirement 1: Capture a Lead

**User Story:** As a Salesperson, I want to quickly capture a lead when an enquiry comes in, so that no prospect is lost and I can follow up.

#### Acceptance Criteria

1. WHEN a Salesperson or Admin submits a lead with at least a customer name and a Lead_Source, THE Lead_Service SHALL create the lead with Lead_Status `NEW`.
2. THE Lead_Service SHALL require the Lead_Source to be a value from the defined `LeadSource` set and SHALL accept an optional source note of at most 200 characters when the source is `OTHER`.
3. WHERE a mobile number is provided, THE System SHALL validate it as a 10-digit number and store it.
4. THE Lead_Service SHALL accept optional fields on capture: mobile, email, a free-text note, and an initial Follow_Up_Date.
5. WHEN a lead is created, THE System SHALL set the Lead_Owner to the creating Salesperson (or the acting Admin) and record the creation timestamp.
6. IF a lead-creation request omits the customer name or a valid Lead_Source, THEN THE Lead_Service SHALL reject it with a validation error and SHALL NOT create the lead.

### Requirement 2: Lead Pipeline and Status Transitions

**User Story:** As a Salesperson, I want to move a lead through clear pipeline stages, so that I always know what to work next.

#### Acceptance Criteria

1. THE Lead_Service SHALL support the Lead_Status values `NEW`, `CONTACTED`, `QUOTED`, `WON`, and `LOST`.
2. THE Lead_Service SHALL allow forward transitions `NEW → CONTACTED → QUOTED` and allow marking a lead `LOST` from any non-terminal status.
3. WHEN a lead is marked `LOST`, THE Lead_Service SHALL require a Lost_Reason from the defined set and SHALL persist it.
4. THE Lead_Service SHALL treat `WON` and `LOST` as Terminal_Lead_Status values and SHALL reject further status changes on a terminal lead.
5. THE Lead_Service SHALL set Lead_Status `WON` only through the Convert action (Requirement 4), not as a manual status pick.
6. WHEN a Lead_Status changes, THE System SHALL record the change (from-status, to-status, actor, timestamp) for audit.
7. IF a status transition is requested that is not permitted from the lead's current status, THEN THE Lead_Service SHALL reject it and leave the lead unchanged.

### Requirement 3: View and Search Leads (role-scoped)

**User Story:** As a Salesperson, I want to see and search my own leads grouped by stage, so that I can prioritize my pipeline; as an Admin I want to see everyone's leads.

#### Acceptance Criteria

1. WHILE a Salesperson is authenticated, THE System SHALL return only leads owned by that Salesperson.
2. WHERE the authenticated user is an Admin, THE System SHALL return all leads unscoped.
3. THE System SHALL provide a pipeline view that groups active leads by Lead_Status with a count per stage.
4. THE System SHALL provide a search over lead name and mobile, and a filter by Lead_Status and Lead_Source.
5. WHEN a user opens a lead, THE System SHALL show its details, status history, Follow_Up_Date, note, and (when WON) the linked order.
6. THE System SHALL restrict lead read/write access to the `SALESPERSON` and `ADMIN` roles and enforce this on the backend independent of the front-end.

### Requirement 4: Convert a Lead to an Order

**User Story:** As a Salesperson, I want to convert a won lead into an order without re-typing, so that entry is fast and the lead is marked won.

#### Acceptance Criteria

1. WHEN a Salesperson converts an active lead, THE System SHALL open order entry pre-filled with the lead's customer name, mobile, email, and Lead_Source.
2. WHEN the converted order is successfully created, THE Lead_Service SHALL set the lead's Lead_Status to `WON` and link the created order to the lead.
3. THE System SHALL carry the lead's Lead_Source onto the created order so channel attribution is preserved.
4. IF order creation fails validation, THEN THE lead SHALL remain in its pre-conversion status and no order SHALL be created.
5. THE Lead_Service SHALL prevent converting a lead that is already `WON` or `LOST`.
6. WHEN a lead is converted, THE System SHALL record the conversion in the audit trail with the acting user and timestamp.

### Requirement 5: Follow-ups and Reminders

**User Story:** As a Salesperson, I want to set and be reminded of follow-ups, so that I contact leads on time.

#### Acceptance Criteria

1. THE Lead_Service SHALL allow setting or updating a lead's Follow_Up_Date.
2. THE System SHALL provide a "due follow-ups" view listing the acting user's leads whose Follow_Up_Date is today or earlier and which are not terminal.
3. WHEN a lead's Follow_Up_Date becomes due, THE Notification_Service SHALL create an in-app notification addressed to the Lead_Owner, enqueued through the existing outbox.
4. WHERE a lead has no Follow_Up_Date, THE System SHALL omit it from the due-follow-ups view without error.
5. WHEN the Lead_Owner completes a follow-up (advances status or sets a new date), THE System SHALL clear or update the due state accordingly.

### Requirement 6: Lead Reporting and Insights

**User Story:** As an Admin, I want pipeline and conversion insights, so that I can measure channel effectiveness and team performance.

#### Acceptance Criteria

1. WHEN an Admin requests a leads-by-source report, THE System SHALL return lead counts grouped by Lead_Source over a requested date range.
2. WHEN an Admin requests a conversion report, THE System SHALL return, per Lead_Source and per Salesperson, the number of leads, the number `WON`, and the conversion rate (`WON` / total) over the range.
3. WHEN an Admin requests a pipeline snapshot, THE System SHALL return the current count of active leads in each Lead_Status.
4. WHEN an Admin requests a lost-reason report, THE System SHALL return counts of `LOST` leads grouped by Lost_Reason over the range.
5. WHILE a Salesperson requests any lead report, THE System SHALL scope results to that Salesperson's own leads.
6. THE System SHALL surface the salesperson's own pipeline counts and due-follow-up count on their role dashboard.

### Requirement 7: Data, Access, and Consistency

**User Story:** As a product owner, I want leads stored safely and consistently with the rest of the system, so that the feature is reliable and reportable.

#### Acceptance Criteria

1. THE System SHALL persist leads in a new table created by a new additive Flyway migration, without editing any applied migration.
2. THE System SHALL enforce role-based access (SALESPERSON/ADMIN) on every lead endpoint on the backend, independent of the Angular route guards.
3. THE System SHALL retain a lead and its status history after conversion so historical funnel analysis remains possible.
4. WHERE a lead references a customer already known from prior orders (same mobile), THE System SHALL surface the prior-order count at capture/convert time (reusing existing duplicate detection).
5. THE System SHALL record every lead status change and conversion in the Audit_Service.

### Requirement 8: Mobile-First Lead UI

**User Story:** As a Salesperson on a phone, I want the lead screens to match the rest of the app, so that capturing and working leads is fast on mobile.

#### Acceptance Criteria

1. THE System SHALL present the leads list/pipeline as mobile-first cards (single column at 360px, ≥44px touch targets), consistent with the shipped redesign.
2. THE System SHALL present a capture form and a quick status-advance action reachable in as few taps as possible on mobile.
3. THE System SHALL expose Leads in the navigation for `SALESPERSON` and `ADMIN` (bottom tab or hamburger), consistent with the role-aware navigation.
4. THE System SHALL use colored status pills for Lead_Status consistent with the app's status-pill language.
