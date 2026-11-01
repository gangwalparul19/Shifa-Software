import { OrderStatus, Role } from 'core';
import {
  FAILED_DELIVERY_STATUSES,
  MANUAL_DELIVERY_STAGE_OPTIONS,
  MANUAL_NEXT_STAGES,
  MANUAL_PACKING_STAGES,
} from './orders.model';

/**
 * Pure permission / eligibility decisions for the Orders screens.
 *
 * <p>Everything here depends only on its arguments: an order (detail or summary
 * shape), plus explicit flags for the signed-in user's role where one is needed
 * (for example {@code isAdmin}). The components own the {@code AuthService} lookup
 * and pass the answer in, so these rules can be read and tested without Angular.
 * These are affordance gates only; the backend still authorises every action.
 */

// --- Structural order shapes -------------------------------------------------
// Minimal field sets so both OrderDetail and OrderSummary satisfy the parameters.

/** An order that carries a lifecycle status. */
export interface OrderStatusHolder {
  readonly orderStatus: OrderStatus | string;
}

/** An order that records where it came from (e.g. 'SHOPIFY'). */
export interface OrderSourceHolder {
  readonly source?: string | null;
}

/** An order that records how it is fulfilled (QUIKSHIPX / IN_HOUSE / STORE_PICKUP). */
export interface OrderDeliveryMethodHolder {
  readonly deliveryMethod?: string | null;
}

/** An order that mirrors a QuikShipX shipment. */
export interface OrderQuikShipHolder {
  readonly quikShipXStatus?: string | null;
  readonly quikShipXLabelUrl?: string | null;
}

// --- Role lists --------------------------------------------------------------

/** Roles that may punch (and edit / resubmit) an order. */
export const ORDER_ENTRY_ROLES: readonly Role[] = [Role.SALESPERSON, Role.ADMIN, Role.TEAM_LEAD];

/**
 * Roles that may export the orders list. Mirrors the backend's
 * {@code GET /api/admin/orders/export} role list: a payment verifier can open the
 * page (view-only) but the export endpoint rejects them.
 */
export const ORDER_EXPORT_ROLES: readonly Role[] = [
  Role.ADMIN,
  Role.ACCOUNTANT,
  Role.SALESPERSON,
  Role.TEAM_LEAD,
  Role.CA,
];

// --- Status sets -------------------------------------------------------------

/**
 * The pre-delivery statuses an order can be cancelled from (order-cancellation
 * feature; mirrors the backend state machine's CANCELLED edges). A delivered /
 * closed / returned / already-rejected order is deliberately excluded.
 */
const CANCELLABLE_STATUSES = new Set<string>([
  OrderStatus.PENDING_ADMIN_APPROVAL,
  OrderStatus.APPROVED,
  OrderStatus.LABEL_GENERATED,
  OrderStatus.PACKED,
  OrderStatus.HANDED_TO_DELIVERY,
  OrderStatus.COURIER_ASSIGNED,
  OrderStatus.DISPATCHED,
  OrderStatus.IN_TRANSIT,
  OrderStatus.OUT_FOR_DELIVERY,
]);

/**
 * The statuses an order can be permanently DELETED from (delete-order feature):
 * only orders that never entered the accounts ledger — awaiting approval,
 * rejected, payment-rejected, or cancelled. An approved/fulfilled order has a
 * posted Sales voucher, so it must be cancelled (not deleted) to keep the GL
 * intact; the backend also refuses such a delete with a 409.
 */
const DELETABLE_STATUSES = new Set<string>([
  OrderStatus.PENDING_ADMIN_APPROVAL,
  OrderStatus.REJECTED,
  OrderStatus.PAYMENT_REJECTED,
  OrderStatus.CANCELLED,
]);

// --- Delivery-method predicates ----------------------------------------------

/** Whether this order is fulfilled by Shifa's own team (no courier partner, no AWB). */
export function isOrderInHouse(order: OrderDeliveryMethodHolder | null | undefined): boolean {
  return !!order && order.deliveryMethod === 'IN_HOUSE';
}

/**
 * Whether this order is an in-shop / counter (POS) pickup — handed over at the
 * store with no delivery partner at all. Such an order is created already closed
 * and must show no courier / in-house fulfilment card.
 */
export function isOrderStorePickup(order: OrderDeliveryMethodHolder | null | undefined): boolean {
  return !!order && order.deliveryMethod === 'STORE_PICKUP';
}

// --- Order-action gates ------------------------------------------------------

/**
 * Whether the "Fix & resubmit" action is offered (rejection-status rework
 * feature): only for a REJECTED / PAYMENT_REJECTED order and an order-entry role.
 * The backend enforces own-order ownership, so this is just the affordance gate.
 * A Shopify order is managed automatically from the store and is never resubmittable.
 */
export function canResubmitOrder(
  order: (OrderStatusHolder & OrderSourceHolder) | null | undefined,
  hasOrderEntryRole: boolean,
): boolean {
  if (order?.source === 'SHOPIFY') {
    return false;
  }
  return (
    !!order &&
    (order.orderStatus === OrderStatus.REJECTED ||
      order.orderStatus === OrderStatus.PAYMENT_REJECTED) &&
    hasOrderEntryRole
  );
}

/**
 * Whether the "Edit" action is offered (own-pending-edit feature). Editing is
 * allowed ONLY while the order is still PENDING approval, for an ADMIN or the
 * creating SALESPERSON/TEAM_LEAD. Once approved the order has been published to
 * QuikShipX, so it can no longer be edited (the backend enforces the same
 * PENDING-only window). A Shopify order is never editable here.
 */
export function canEditOrder(
  order: (OrderStatusHolder & OrderSourceHolder) | null | undefined,
  hasOrderEntryRole: boolean,
): boolean {
  if (!order) {
    return false;
  }
  if (order.source === 'SHOPIFY') {
    return false;
  }
  return order.orderStatus === OrderStatus.PENDING_ADMIN_APPROVAL && hasOrderEntryRole;
}

/**
 * Whether "Cancel order" is offered (order-cancellation feature): ADMIN only, and
 * only for a pre-delivery order. A delivered order is a Return, not a cancel.
 */
export function canCancelOrder(order: OrderStatusHolder | null | undefined, isAdmin: boolean): boolean {
  if (!order || !isAdmin) {
    return false;
  }
  return CANCELLABLE_STATUSES.has(order.orderStatus);
}

/**
 * Whether "Delete order" is offered (delete-order feature): ADMIN only, for
 * orders that never entered the accounts ledger.
 */
export function canDeleteOrder(order: OrderStatusHolder | null | undefined, isAdmin: boolean): boolean {
  if (!order || !isAdmin) {
    return false;
  }
  return DELETABLE_STATUSES.has(order.orderStatus);
}

/**
 * Whether manually assigning a courier is offered: ADMIN only, and only before
 * the order has been dispatched (late-stage orders already have a real courier,
 * or never will).
 */
export function canAssignCourierToOrder(
  order: OrderStatusHolder | null | undefined,
  isAdmin: boolean,
): boolean {
  if (!order || !isAdmin) {
    return false;
  }
  const late: (OrderStatus | string)[] = [
    OrderStatus.DISPATCHED,
    OrderStatus.IN_TRANSIT,
    OrderStatus.OUT_FOR_DELIVERY,
    OrderStatus.DELIVERED,
    OrderStatus.COD_COLLECTED,
    OrderStatus.CLOSED,
    OrderStatus.REJECTED,
    OrderStatus.CANCELLED,
  ];
  return !late.includes(order.orderStatus);
}

/** Whether the acting role may approve this order from the detail view (admin, pending only). */
export function canApproveOrder(order: OrderStatusHolder | null | undefined, isAdmin: boolean): boolean {
  return !!order && order.orderStatus === OrderStatus.PENDING_ADMIN_APPROVAL && isAdmin;
}

/**
 * Whether the admin may save the delivery method on its own (change-delivery-
 * method feature): admin only, pre-dispatch stages. A Shopify order is managed
 * from the store, so its delivery method is not changed here.
 */
export function canChangeDeliveryMethod(
  order: (OrderStatusHolder & OrderSourceHolder) | null | undefined,
  isAdmin: boolean,
): boolean {
  if (!order || order.source === 'SHOPIFY') {
    return false;
  }
  return (
    isAdmin &&
    (order.orderStatus === OrderStatus.PENDING_ADMIN_APPROVAL ||
      order.orderStatus === OrderStatus.APPROVED ||
      order.orderStatus === OrderStatus.LABEL_GENERATED ||
      order.orderStatus === OrderStatus.PACKED)
  );
}

/** Whether the delivery method picked in the drawer differs from the saved one. */
export function isDeliveryMethodChanged(
  order: OrderDeliveryMethodHolder | null | undefined,
  pickedMethod: string,
): boolean {
  return !!order && (order.deliveryMethod ?? 'IN_HOUSE') !== pickedMethod;
}

// --- Status-based eligibility ------------------------------------------------

/** A return may be raised only for a delivered or RTO'd order. */
export function isOrderReturnEligible(order: OrderStatusHolder | null | undefined): boolean {
  return (
    !!order &&
    (order.orderStatus === OrderStatus.DELIVERED || order.orderStatus === OrderStatus.RTO)
  );
}

/**
 * Whether "retry delivery" should be surfaced: the order is in RTO (returned to
 * origin) or REDISPATCH (lost/damaged by the courier). Both are final for this
 * order (RTO already raised a GST credit note; REDISPATCH already zeroed the
 * customer's outstanding and filed a claim), so the only way to get the customer
 * their order is a NEW order via the one-tap Reorder flow, not reopening this one.
 */
export function isOrderRetryEligible(order: OrderStatusHolder | null | undefined): boolean {
  return (
    !!order &&
    (order.orderStatus === OrderStatus.RTO || order.orderStatus === OrderStatus.REDISPATCH)
  );
}

/**
 * Whether a delivery attempt on this order has failed or been refused — the
 * parcel is still in hand, so it can be retried, or closed out by marking it RTO.
 */
export function orderHasFailedDelivery(order: OrderStatusHolder | null | undefined): boolean {
  return !!order && FAILED_DELIVERY_STATUSES.includes(String(order.orderStatus));
}

/** Whether the order was collected on delivery (COD cash received from the customer). */
export function isCollectedOnDelivery(order: OrderStatusHolder): boolean {
  return order.orderStatus === OrderStatus.COD_COLLECTED;
}

/** Whether this status is a rejected state (admin REJECTED or payment-panel PAYMENT_REJECTED). */
export function isRejectedStatus(status: string | null | undefined): boolean {
  return status === 'REJECTED' || status === 'PAYMENT_REJECTED';
}

// --- Manual delivery status --------------------------------------------------

/**
 * The statuses an order can legally be moved to by hand right now, filtered by
 * what the caller's role is allowed to do:
 * <ul>
 *   <li>the warehouse steps (Packed / Handed to delivery) apply to ANY order and
 *       need ADMIN or PACKING_USER — they mirror the Packing page;</li>
 *   <li>the post-handover delivery stages are in-house only (a courier reports
 *       its own progress) and additionally allow the order's salesperson.</li>
 * </ul>
 * Empty when the order has no manual next step (terminal, or awaiting approval).
 */
export function allowedManualStagesFor(
  order: (OrderStatusHolder & OrderDeliveryMethodHolder) | null | undefined,
  isWarehouseStaff: boolean,
  isSalesperson: boolean,
): typeof MANUAL_DELIVERY_STAGE_OPTIONS {
  if (!order) {
    return [];
  }
  const next = MANUAL_NEXT_STAGES[String(order.orderStatus)] ?? [];
  return MANUAL_DELIVERY_STAGE_OPTIONS.filter((opt) => {
    if (!next.includes(opt.value)) {
      return false;
    }
    if (MANUAL_PACKING_STAGES.includes(opt.value)) {
      return isWarehouseStaff;
    }
    // Post-handover stages: in-house orders only.
    return isOrderInHouse(order) && (isWarehouseStaff || isSalesperson);
  });
}

// --- QuikShipX ---------------------------------------------------------------

/** Whether the order has a QuikShip courier label available to print. */
export function orderHasQuikShipLabel(order: OrderQuikShipHolder | null | undefined): boolean {
  return !!order && !!order.quikShipXLabelUrl;
}

/**
 * Whether a QuikShipX order is published but still has no tracking id (its
 * courier label — only produced at allot time — is absent while a QuikShipX
 * status exists). This is the "stuck at Confirmed, no AWB" state the admin can
 * recover with the retry action. In-house and store-pickup orders never go
 * through QuikShipX.
 */
export function orderNeedsTrackingId(
  order: (OrderQuikShipHolder & OrderDeliveryMethodHolder) | null | undefined,
): boolean {
  return (
    !!order &&
    !!order.quikShipXStatus &&
    !order.quikShipXLabelUrl &&
    !isOrderInHouse(order) &&
    !isOrderStorePickup(order)
  );
}
