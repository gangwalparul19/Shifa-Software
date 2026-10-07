/**
 * Admin "orders by delivery partner" dashboard models — mirror the backend
 * {@code DeliveryPartnerSummaryResponse}. Splits the order book across the three
 * fulfilment partners (QuikShipX courier / in-house "Ishika Enterprise" / POS
 * store) so an admin can compare in-transit, delivered, cancelled, COD-to-collect
 * and revenue per partner.
 */

/** One lifecycle-stage tally in a partner's status breakdown. */
export interface PartnerStatusCount {
  /** The OrderStatusGroup key, e.g. SHIPPED / DELIVERED / CANCELLED. */
  group: string;
  /** Human label for the stage. */
  label: string;
  /** Number of orders in this stage for the partner + window. */
  count: number;
}

/** Metrics for one delivery partner over the window. */
export interface PartnerStats {
  /** Partner key: QUIKSHIPX / IN_HOUSE / POS / TOTAL. */
  partner: string;
  /** Human label for the partner. */
  label: string;
  orderCount: number;
  revenue: number | string;
  /** Amount still to collect on delivery (customer outstanding) on active orders. */
  codToCollect: number | string;
  pending: number;
  processing: number;
  inTransit: number;
  delivered: number;
  cancelled: number;
  failedReturned: number;
  monthOrderCount: number;
  monthRevenue: number | string;
  statusBreakdown: PartnerStatusCount[];
}

/** The full delivery-partner summary payload. */
export interface DeliveryPartnerSummary {
  from: string | null;
  to: string | null;
  total: PartnerStats;
  quikShipX: PartnerStats;
  inHouse: PartnerStats;
  pos: PartnerStats;
}
