# Requirements Document

## Introduction

Shifa Herbal Remedies requires a web-based e-commerce and order management platform that automates order processing across WhatsApp/Instagram enquiries and a public online store. The platform digitizes the salesperson order-entry workflow, admin approval, packing, courier dispatch, live tracking, COD (Cash on Delivery) settlement, loss/claim management, salesperson-wise reporting, and WhatsApp customer notifications.

The system supports five user roles (Admin, Accountant, Salesperson, Packing Department, Customer), a mobile-responsive Progressive Web App (PWA) storefront, a Shopify-style admin dashboard, and integrations with a Courier API and the WhatsApp Business API. This document defines the functional and quality requirements for Version 1.0. Items listed under "Future Considerations" are explicitly out of scope for this release.

## Glossary

- **Platform**: The complete Shifa Herbal Remedies web application, comprising the Storefront, Order_Management_System, and Admin_Dashboard.
- **Storefront**: The customer-facing e-commerce Progressive Web App for browsing products, cart, wishlist, and checkout.
- **Order_Management_System (OMS)**: The backend subsystem that manages orders through their lifecycle, including entry, approval, packing, dispatch, tracking, and settlement.
- **Admin_Dashboard**: The administrative interface presenting metrics, live statistics, reports, and management controls.
- **Order_Entry_Module**: The salesperson-facing subsystem for punching new orders.
- **Label_Service**: The subsystem that generates internal company labels and courier shipping labels as printable/PDF output.
- **Reconciliation_Service**: The subsystem that tracks COD and loss-claim receivables from courier companies.
- **Reporting_Service**: The subsystem that generates and exports reports.
- **Notification_Service**: The subsystem that sends WhatsApp messages via the WhatsApp Business API.
- **Courier_Integration**: The subsystem that communicates with the external Courier API for AWB generation, shipping labels, and status updates.
- **WhatsApp_Agent_Service**: The subsystem enabling a live agent to query order status from the database via the Storefront.
- **Admin**: A user role with full management, approval, and configuration privileges.
- **Accountant**: A user role with access to financial, payment, and reconciliation data.
- **Salesperson**: A user role that punches orders and views own sales reports.
- **Packing_User**: A user with the Packing Department role who packs orders and scans barcodes.
- **Customer**: A user who browses the Storefront, places orders, and tracks orders.
- **Order**: A record of a customer purchase containing customer details, line items, amounts, payment status, and lifecycle status.
- **Line_Item**: A single product with quantity and applied rate within an Order.
- **AWB**: Air Waybill number, a unique courier tracking identifier assigned by the courier company.
- **COD**: Cash on Delivery, an amount collected from the customer at delivery.
- **COD_Amount**: The order amount remaining to be collected on delivery (Total_Amount minus Amount_Received).
- **Total_Amount**: The sum of all Line_Item rates multiplied by their quantities for an Order.
- **Amount_Received**: The amount already paid by the customer at order entry.
- **Remaining_Amount**: Total_Amount minus Amount_Received.
- **Payment_Screenshot**: An uploaded image proving a customer payment.
- **Payment_Status**: One of Fully_Paid, Partially_Paid, or COD.
- **Order_Status**: The lifecycle state of an Order (see Requirement 8).
- **RTO**: Return To Origin, a courier status indicating an undelivered order returned to sender.
- **Courier_Company_Receivable**: A COD amount collected by the courier that is owed to Shifa Herbal Remedies.
- **Claim_Receivable**: A net order amount owed by the courier company for a lost or damaged shipment.
- **PWA**: Progressive Web App.
- **SKU**: Stock Keeping Unit, a unique product identifier.
- **MRP**: Maximum Retail Price.
- **Vyapar**: An external billing/accounting application that consumes exported CSV/Excel files.

## Requirements

### Requirement 1: Customer Storefront Browsing

**User Story:** As a Customer, I want to browse and view products in an online store, so that I can decide what to purchase.

#### Acceptance Criteria

1. THE Storefront SHALL display a catalog containing only products whose visibility is set to published, and SHALL display for each product the product name, product image, MRP, and sale price.
2. WHEN a Customer selects a published product, THE Storefront SHALL display the product detail view including the product name, description, images, MRP, sale price, and SKU.
3. WHEN a Customer submits a search term of at least 1 character, THE Storefront SHALL display all published products whose product name or SKU contains the search term as a case-insensitive substring.
4. IF a product has no available published image, THEN THE Storefront SHALL display a placeholder image in its place.
5. IF a Customer submits a search term that matches no published product, THEN THE Storefront SHALL display a message stating that no products match the search term.
6. IF the catalog contains no published products, THEN THE Storefront SHALL display a message stating that no products are currently available.
7. IF a Customer requests the product detail view for a product that is not published or no longer exists, THEN THE Storefront SHALL deny access and display a message stating that the product is unavailable.

### Requirement 2: Cart and Wishlist

**User Story:** As a Customer, I want to add products to a cart and a wishlist, so that I can save items and purchase them.

#### Acceptance Criteria

1. WHEN a Customer adds a product to the cart with a selected quantity between 1 and 999 inclusive, THE Storefront SHALL add the product as a cart Line_Item and update the displayed cart line item count.
2. WHEN a Customer changes the quantity of a cart Line_Item to a value between 1 and 999 inclusive, THE Storefront SHALL recalculate the cart subtotal as the sum over all Line_Items of sale price multiplied by quantity and SHALL display the updated cart subtotal.
3. WHEN a Customer removes a Line_Item from the cart, THE Storefront SHALL remove the Line_Item, recalculate and display the updated cart subtotal, and update the displayed cart line item count.
4. WHEN a Customer adds a product that is not already in the Customer wishlist to the wishlist, THE Storefront SHALL add the product to the Customer wishlist.
5. WHEN a Customer moves a wishlist product to the cart, THE Storefront SHALL add the product to the cart as a Line_Item with quantity 1 and remove the product from the wishlist.
6. IF a Customer attempts to add a product to the cart or set a cart Line_Item quantity to a value that is less than 1, greater than 999, or not a whole number, THEN THE Storefront SHALL reject the change, retain the existing cart contents, and display a validation message indicating the allowed quantity range.
7. WHEN a Customer adds a product that already exists as a cart Line_Item, THE Storefront SHALL increase that Line_Item quantity by the selected quantity, up to a maximum Line_Item quantity of 999, rather than creating a duplicate Line_Item.
8. IF a Customer adds a product that is already present in the Customer wishlist, THEN THE Storefront SHALL retain a single wishlist entry for that product and SHALL not create a duplicate entry.

### Requirement 3: Customer Checkout

**User Story:** As a Customer, I want to check out my cart, so that I can place an order.

#### Acceptance Criteria

1. WHEN a Customer proceeds to checkout with at least one cart Line_Item, THE Storefront SHALL request customer name (1 to 100 characters), mobile number, and a shipping address comprising address line (1 to 250 characters), city, state, and postal code.
2. IF a Customer attempts to checkout with an empty cart (zero Line_Items), THEN THE Storefront SHALL display a message stating that the cart is empty and SHALL prevent checkout.
3. IF any required checkout field (customer name, mobile number, address line, city, state, or postal code) is missing or empty, THEN THE Storefront SHALL display a validation message identifying each missing field, SHALL prevent order submission, and SHALL retain the previously entered checkout values.
4. IF the entered mobile number is not exactly 10 numeric digits, THEN THE Storefront SHALL reject the submission, display a validation message indicating an invalid mobile number, and retain the previously entered checkout values.
5. IF the entered postal code is not exactly 6 numeric digits, THEN THE Storefront SHALL reject the submission, display a validation message indicating an invalid postal code, and retain the previously entered checkout values.
6. WHEN a Customer submits a checkout in which all required fields are present, the mobile number is exactly 10 numeric digits, and the postal code is exactly 6 numeric digits, THE Order_Management_System SHALL create an Order with Order_Status set to Pending_Admin_Approval.
7. WHEN the Order_Management_System creates the Order, THE Storefront SHALL display an order confirmation containing the Order identifier to the Customer.

### Requirement 4: Progressive Web App and Responsiveness

**User Story:** As a user on a mobile device, I want the application to work as an installable, responsive app, so that I can use it easily from my phone.

#### Acceptance Criteria

1. THE Storefront SHALL render layouts adapted to viewport widths of mobile, tablet, and desktop devices.
2. THE Storefront SHALL provide a web app manifest and a service worker so that the Storefront is installable as a PWA.
3. WHERE the device browser supports PWA installation, THE Storefront SHALL present an install option to the Customer.

### Requirement 5: User Roles and Access Control

**User Story:** As an Admin, I want distinct user roles with scoped permissions, so that each user accesses only the functions relevant to their role.

#### Acceptance Criteria

1. THE Platform SHALL support the roles Admin, Accountant, Salesperson, Packing_User, and Customer.
2. WHEN an unauthenticated user requests a role-protected page, THE Platform SHALL require authentication before granting access.
3. IF an authenticated user requests a function not permitted for the assigned role, THEN THE Platform SHALL deny access and display an authorization error message.
4. THE Platform SHALL grant an Admin access to all management, approval, and configuration functions.
5. THE Platform SHALL grant a Salesperson access to order entry and to reports limited to orders created by that Salesperson.

### Requirement 6: Product Management

**User Story:** As an Admin, I want to manage products, so that the catalog and pricing stay accurate.

#### Acceptance Criteria

1. WHEN an Admin creates a product with product name, SKU, MRP, and sale price, THE Order_Management_System SHALL store the product record.
2. IF an Admin submits a product with a SKU that already exists, THEN THE Order_Management_System SHALL reject the submission and display a duplicate-SKU error message.
3. WHEN an Admin updates a product field, THE Order_Management_System SHALL persist the updated value.
4. WHEN an Admin sets a product visibility to published, THE Storefront SHALL display the product in the catalog.

### Requirement 7: Salesperson Order Entry

**User Story:** As a Salesperson, I want to punch orders from WhatsApp/Instagram enquiries with editable rates and payment capture, so that I can record confirmed sales accurately.

#### Acceptance Criteria

1. WHEN a Salesperson creates an Order, THE Order_Entry_Module SHALL require customer name, mobile number, and complete shipping address.
2. WHEN a Salesperson adds a Line_Item, THE Order_Entry_Module SHALL require a product selection and a quantity, and SHALL pre-fill the rate with the product default sale price.
3. WHERE a Salesperson edits a Line_Item rate, THE Order_Entry_Module SHALL use the edited rate for that Line_Item.
4. WHEN a Line_Item rate or quantity changes, THE Order_Entry_Module SHALL recalculate Total_Amount as the sum over all Line_Items of rate multiplied by quantity.
5. WHEN a Salesperson enters Amount_Received, THE Order_Entry_Module SHALL calculate Remaining_Amount as Total_Amount minus Amount_Received.
6. IF Amount_Received is greater than 0 and no Payment_Screenshot is attached, THEN THE Order_Entry_Module SHALL prevent order submission and display a message requiring a Payment_Screenshot.
7. IF Amount_Received equals 0, THEN THE Order_Entry_Module SHALL set Payment_Status to COD.
8. IF Amount_Received is greater than 0 and less than Total_Amount, THEN THE Order_Entry_Module SHALL set Payment_Status to Partially_Paid and set COD_Amount to Remaining_Amount.
9. IF Amount_Received equals Total_Amount, THEN THE Order_Entry_Module SHALL set Payment_Status to Fully_Paid and set COD_Amount to 0.
10. IF Amount_Received is greater than Total_Amount, THEN THE Order_Entry_Module SHALL reject the entry and display a message stating that Amount_Received exceeds Total_Amount.
11. WHEN a Salesperson submits a valid Order, THE Order_Management_System SHALL store the Payment_Screenshot with the Order and set Order_Status to Pending_Admin_Approval.

### Requirement 8: Order Status Lifecycle

**User Story:** As an Admin, I want orders to follow a defined status lifecycle, so that every order has a clear, trackable state.

#### Acceptance Criteria

1. THE Order_Management_System SHALL represent Order_Status as exactly one of: Pending_Admin_Approval, Approved, Rejected, Label_Generated, Packed, Courier_Assigned, Dispatched, In_Transit, Out_For_Delivery, Delivered, COD_Collected, Closed, RTO, Redispatch, Cancelled.
2. WHEN an Order is created, THE Order_Management_System SHALL set its initial Order_Status to Pending_Admin_Approval.
3. IF a status transition is requested that is not permitted from the current Order_Status, THEN THE Order_Management_System SHALL reject the transition and retain the current Order_Status.
4. WHEN an Order_Status changes, THE Order_Management_System SHALL record the new status, the timestamp, and the actor or source that caused the change.

### Requirement 9: Admin Order Approval

**User Story:** As an Admin, I want to review and approve or reject punched orders, so that only verified orders proceed to fulfillment.

#### Acceptance Criteria

1. THE Admin_Dashboard SHALL display an approval queue containing all Orders with Order_Status Pending_Admin_Approval.
2. WHEN an Admin opens an Order for review, THE Admin_Dashboard SHALL display the Payment_Screenshot, Line_Items, applied rates, Total_Amount, Amount_Received, COD_Amount, and Payment_Status.
3. WHEN an Admin approves an Order, THE Order_Management_System SHALL set its Order_Status to Approved.
4. IF an Admin rejects an Order, THEN THE Order_Management_System SHALL require a rejection reason, set Order_Status to Rejected, and store the rejection reason with the Order.

### Requirement 10: Internal Company Label Generation

**User Story:** As a Packing_User, I want an internal company label generated for approved orders, so that I can identify and pack the correct items.

#### Acceptance Criteria

1. WHEN an Order Order_Status becomes Approved, THE Label_Service SHALL generate an internal company label containing the Order identifier, a scannable barcode encoding the Order identifier, the customer details, and the Line_Item list.
2. WHERE an Order Payment_Status is COD or Partially_Paid, THE Label_Service SHALL include the COD_Amount on the internal company label.
3. WHEN an internal company label is generated, THE Order_Management_System SHALL set the Order_Status to Label_Generated.
4. WHEN an Admin requests label printing for one or more Orders, THE Label_Service SHALL produce a PDF containing the internal company label for each requested Order.

### Requirement 11: Packing and Barcode Scan

**User Story:** As a Packing_User, I want to scan a packed order's barcode, so that the order is marked packed and the Admin is notified.

#### Acceptance Criteria

1. WHEN a Packing_User scans a barcode that matches an Order with Order_Status Label_Generated, THE Order_Management_System SHALL set that Order_Status to Packed.
2. WHEN an Order_Status becomes Packed, THE Admin_Dashboard SHALL display a real-time notification of the packed Order to the Admin.
3. IF a scanned barcode does not match any Order, THEN THE Order_Management_System SHALL display a message stating that the barcode is not recognized.
4. IF a Packing_User scans a barcode for an Order whose Order_Status is not Label_Generated, THEN THE Order_Management_System SHALL reject the scan and display the current Order_Status.

### Requirement 12: Courier Assignment and Shipping Label

**User Story:** As an Admin, I want the courier integration to generate an AWB and shipping label once an order is packed, so that the parcel can be dispatched.

#### Acceptance Criteria

1. WHEN an Order_Status becomes Packed, THE Courier_Integration SHALL request an AWB and shipping label from the Courier API using the Order details and COD_Amount.
2. WHEN the Courier API returns an AWB and shipping label, THE Order_Management_System SHALL store the AWB with the Order and set Order_Status to Courier_Assigned.
3. WHEN a shipping label is returned, THE Label_Service SHALL make the courier shipping label available as a PDF containing the AWB and, where the Order is COD or Partially_Paid, the COD_Amount.
4. IF the Courier API returns an error or does not respond within the configured timeout, THEN THE Order_Management_System SHALL retain Order_Status Packed and notify the Admin of the courier assignment failure.
5. WHEN an Admin requests shipping label printing for one or more Orders with an assigned AWB, THE Label_Service SHALL produce a PDF containing the courier shipping label for each requested Order.

### Requirement 13: Dispatch and Live Tracking

**User Story:** As a Customer, I want live tracking status on my order, so that I know where my parcel is.

#### Acceptance Criteria

1. WHEN the Courier API reports that a parcel has been picked up, THE Order_Management_System SHALL set the Order_Status to Dispatched.
2. WHEN the Courier API reports a delivery status update, THE Order_Management_System SHALL set the Order_Status to the corresponding state among In_Transit, Out_For_Delivery, Delivered, RTO, and Redispatch.
3. WHEN an Order_Status changes from a Courier API update, THE Admin_Dashboard SHALL reflect the updated status in real time.
4. THE Storefront SHALL allow a Customer to view the current Order_Status, AWB, and courier tracking link for the Customer's own Order.

### Requirement 14: WhatsApp Dispatch and Status Notifications

**User Story:** As a Customer, I want to receive WhatsApp updates about my order, so that I stay informed without asking.

#### Acceptance Criteria

1. WHEN an Order_Status becomes Dispatched, THE Notification_Service SHALL send the Customer a WhatsApp message containing the Order identifier, courier company name, AWB, courier tracking link, estimated delivery date, and, where the Order is COD or Partially_Paid, the COD_Amount to be paid.
2. WHEN an Order_Status becomes Out_For_Delivery, Delivered, RTO, or Redispatch, THE Notification_Service SHALL send the Customer a WhatsApp message stating the updated Order_Status.
3. THE Notification_Service SHALL send WhatsApp messages using message templates that have been pre-approved through Meta.
4. IF the WhatsApp Business API rejects or fails to deliver a message, THEN THE Notification_Service SHALL record the failure and flag the Order for Admin review.

### Requirement 15: WhatsApp Live Agent Order Query

**User Story:** As a Customer, I want to ask a live agent for my order status, so that I can get answers through WhatsApp.

#### Acceptance Criteria

1. WHEN a live agent submits an order query by Order identifier, mobile number, or AWB through the Storefront, THE WhatsApp_Agent_Service SHALL return the matching Order's current status and tracking details.
2. IF no Order matches the query, THEN THE WhatsApp_Agent_Service SHALL return a result stating that no matching order was found.

### Requirement 16: Prepaid and COD Settlement on Delivery

**User Story:** As an Accountant, I want order financial status to settle automatically on delivery, so that receivables are tracked correctly.

#### Acceptance Criteria

1. WHEN an Order with Payment_Status Fully_Paid reaches Order_Status Delivered, THE Order_Management_System SHALL set Order_Status to Closed and set the customer outstanding amount to 0.
2. WHEN an Order with a COD_Amount greater than 0 reaches Order_Status Delivered, THE Order_Management_System SHALL set Order_Status to COD_Collected, set the customer outstanding amount to 0, and record a Courier_Company_Receivable equal to the COD_Amount.
3. WHEN an Order_Status becomes RTO, THE Order_Management_System SHALL cancel the COD_Amount for that Order and set the customer outstanding amount to 0.

### Requirement 17: Courier Loss and Claim Management

**User Story:** As an Accountant, I want lost or damaged shipments converted into recoverable claims, so that no revenue is silently lost.

#### Acceptance Criteria

1. WHEN the Courier API reports an Order as lost, damaged, or missing, THE Order_Management_System SHALL set the Order_Status to Redispatch.
2. WHEN an Order_Status becomes Redispatch, THE Reconciliation_Service SHALL record a Claim_Receivable equal to the net Order amount, regardless of whether the Order is prepaid or COD.
3. WHEN an Order_Status becomes Redispatch, THE Order_Management_System SHALL set the customer outstanding amount for that Order to 0.
4. WHEN a Claim_Receivable is recorded, THE Admin_Dashboard SHALL notify the Admin that a claim needs to be filed for the associated AWB.

### Requirement 18: COD and Loss Reconciliation Dashboard

**User Story:** As an Accountant, I want a reconciliation dashboard, so that I can track amounts owed by courier companies.

#### Acceptance Criteria

1. THE Reconciliation_Service SHALL display, per courier company, the total Courier_Company_Receivable from delivered COD Orders.
2. THE Reconciliation_Service SHALL display, per courier company, the total Claim_Receivable from lost or damaged shipments.
3. THE Reconciliation_Service SHALL list delivered COD Orders whose Courier_Company_Receivable has not yet been marked settled.
4. THE Reconciliation_Service SHALL segregate Orders into prepaid and COD categories in the reconciliation views.
5. WHEN an Accountant marks a Courier_Company_Receivable or Claim_Receivable as settled, THE Reconciliation_Service SHALL record the settlement date and reduce the outstanding receivable for that courier company by the settled amount.
6. THE Reconciliation_Service SHALL exclude RTO Orders from Courier_Company_Receivable totals.

### Requirement 19: Admin Dashboard Metrics and Live Stats

**User Story:** As an Admin, I want a graphical dashboard with key metrics and live stats, so that I can monitor business performance at a glance.

#### Acceptance Criteria

1. THE Admin_Dashboard SHALL display metric cards for Total Sales, Total Orders, Pending Orders, Packed Orders, Dispatched Orders, Delivered Orders, RTO Count, Redispatch Count, Total COD Pending from Courier, Total Loss Claim Pending from Courier, and Conversion Rate.
2. THE Admin_Dashboard SHALL provide time-period filters for Today, Yesterday, Last 7 Days, Last 30 Days, This Month, Last Month, Quarterly, Yearly, and a custom date range.
3. WHEN an Admin selects a time-period filter, THE Admin_Dashboard SHALL recalculate and display all metric cards for the selected period.
4. THE Admin_Dashboard SHALL display a sales graph with day-wise, week-wise, and monthly views including a comparison line against the previous period and the percentage change relative to the previous period.
5. THE Admin_Dashboard SHALL display live statistics for real-time order count, today's collection, total COD amount to collect from courier, and total loss amount to claim from courier.
6. THE Admin_Dashboard SHALL display activity cards for orders to fulfill, payments to capture, RTO alerts, WhatsApp notifications sent, COD settlements pending, and courier claims pending.
7. THE Admin_Dashboard SHALL display top performers for top salesperson, top selling product, and top state within the selected time period.

### Requirement 20: Reporting and Export

**User Story:** As an Admin, I want reports with export options, so that I can analyze sales and share records.

#### Acceptance Criteria

1. THE Reporting_Service SHALL generate daily, monthly, product-wise, and state-wise reports.
2. WHEN a user specifies a custom date range, THE Reporting_Service SHALL generate the selected report restricted to Orders within that date range.
3. THE Reporting_Service SHALL generate a salesperson-wise report in which each row contains customer name, mobile number, product name and quantity, Total_Amount, Amount_Received, COD_Amount, Payment_Status, Order_Status, COD settlement status, loss claim status, AWB, and order date.
4. WHEN a user requests an export, THE Reporting_Service SHALL produce the current report as an Excel file and as a PDF file containing the displayed rows and columns.

### Requirement 21: Payment Tracking

**User Story:** As an Accountant, I want order-level payment details, so that I can verify and audit payments.

#### Acceptance Criteria

1. THE Order_Management_System SHALL display, for each Order, the Payment_Status, Total_Amount, Amount_Received, and COD_Amount.
2. WHEN an Accountant requests a Payment_Screenshot for an Order, THE Order_Management_System SHALL allow the Payment_Screenshot to be viewed and downloaded.

### Requirement 22: Search and Duplicate Detection

**User Story:** As a Salesperson, I want to search orders and detect repeat customers, so that I can find records and identify repeat orders.

#### Acceptance Criteria

1. WHEN a user searches by customer name, mobile number, Order identifier, or AWB, THE Order_Management_System SHALL return all Orders matching the search term.
2. WHEN a Salesperson enters a customer mobile number during order entry, THE Order_Entry_Module SHALL indicate whether one or more prior Orders exist for that mobile number.

### Requirement 23: Billing Export to Vyapar

**User Story:** As an Accountant, I want to export billing data compatible with Vyapar, so that I can maintain accounts in the existing tool.

#### Acceptance Criteria

1. WHEN an Accountant requests a billing export for a selected date range, THE Reporting_Service SHALL produce a CSV or Excel file whose columns and format are compatible with Vyapar import.
2. IF the selected date range contains no Orders, THEN THE Reporting_Service SHALL produce an export file containing only the header row and display a message stating that no orders were found.

### Requirement 24: Data Backup

**User Story:** As an Admin, I want daily data backups, so that business data can be recovered after a failure.

#### Acceptance Criteria

1. THE Platform SHALL create a backup of the application database at least once every 24 hours.
2. IF a scheduled backup fails, THEN THE Platform SHALL notify the Admin of the backup failure.

## Future Considerations (Out of Scope for Version 1.0)

The following capabilities are explicitly deferred and are not part of this release:

- Online payment gateway integration for the Storefront.
- Automatic tracking SMS notifications.
- GST invoice generation.
- Inventory management and stock alerts.
- Customer WhatsApp chat support beyond the live-agent order query.
- Automatic COD remittance retrieval from the Courier API.
- Automatic loss-claim filing to the courier company.
- CRM integration (Zoho, HubSpot, Salesforce), lead tracking, follow-up automation, and customer lifetime value analysis.



NOTE::
Tech should be Angular, Java and MySQL.

Also deployment would be Oracle Cloud using there free isntance so plan accordingly keeping in mind the free tier for all the sources.