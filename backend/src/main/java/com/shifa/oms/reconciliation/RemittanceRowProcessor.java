package com.shifa.oms.reconciliation;

import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.Actor;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.quikshipx.OrderShipment;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.dto.RemittanceRowResult;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes a SINGLE courier COD remittance row in its OWN transaction.
 *
 * <p>Pulled out of {@link RemittanceImportService} so each row's write
 * ({@code settle} / walk-forward {@code applyTransition}) runs under
 * {@link Propagation#REQUIRES_NEW}: a row that fails (e.g. an illegal state
 * transition throws from {@link OrderWorkflowService}) rolls back ONLY that row
 * and is reported as an error, instead of marking the whole batch's transaction
 * rollback-only and discarding every already-matched row. The matching logic is
 * identical to the previous in-service implementation.
 */
@Component
public class RemittanceRowProcessor {

    /** Amounts within this tolerance are treated as matching (courier sheets sometimes round). */
    private static final BigDecimal TOLERANCE = new BigDecimal("1.00");

    /** Orders short of DELIVERED that the remittance sheet itself proves were delivered + paid. */
    private static final java.util.Set<OrderStatus> PRE_DELIVERY_STATUSES = java.util.EnumSet.of(
            OrderStatus.COURIER_ASSIGNED, OrderStatus.DISPATCHED,
            OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.HANDED_TO_DELIVERY);

    /**
     * Statuses from which a COD order with no receivable yet can still be settled
     * straight from the remittance sheet (the catch-up path): any pre-delivery
     * status PLUS {@code DELIVERED} itself. A DELIVERED COD order with no receivable
     * row is the common "No receivable" confusion — the courier remitted it and it
     * clearly was delivered, so we settle it rather than flag it. A genuine
     * {@code NO_RECEIVABLE} is reserved for prepaid orders (no COD to collect) and
     * terminal non-collectible statuses (RTO / Cancelled / Rejected / etc.).
     */
    private static final java.util.Set<OrderStatus> COD_COLLECTIBLE_STATUSES;

    static {
        java.util.EnumSet<OrderStatus> collectible = java.util.EnumSet.copyOf(PRE_DELIVERY_STATUSES);
        collectible.add(OrderStatus.DELIVERED);
        COD_COLLECTIBLE_STATUSES = java.util.Collections.unmodifiableSet(collectible);
    }

    /**
     * Ledger auto-posting source key for the COD cash booked when the courier's
     * remittance is settled (mirrors {@code SourceType.ORDER_DELIVERY}; kept as a
     * literal so the reconciliation module does not depend on the ledger module).
     * The cash is recognised at REMITTANCE time — not at delivery — because a COD
     * order's cash is only ours once the courier actually remits it.
     */
    private static final String LEDGER_SOURCE_ORDER_DELIVERY = "ORDER_DELIVERY";

    /** Extracts a trailing run of digits — the QuikShipX shipper_order_id inside "shr083_19823". */
    private static final Pattern TRAILING_DIGITS = Pattern.compile("(\\d+)\\s*$");

    private final OrderRepository orderRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final ReceivableRepository receivableRepository;
    private final OrderWorkflowService orderWorkflowService;
    private final OutboxEventPublisher outboxEventPublisher;

    public RemittanceRowProcessor(OrderRepository orderRepository,
                                  CourierRecordRepository courierRecordRepository,
                                  OrderShipmentRepository orderShipmentRepository,
                                  ReceivableRepository receivableRepository,
                                  OrderWorkflowService orderWorkflowService,
                                  OutboxEventPublisher outboxEventPublisher) {
        this.orderRepository = orderRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.orderShipmentRepository = orderShipmentRepository;
        this.receivableRepository = receivableRepository;
        this.orderWorkflowService = orderWorkflowService;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /**
     * Match + (unless {@code dryRun}) settle one row in its own transaction.
     * Any thrown exception rolls back only this row's transaction; the caller
     * turns it into an {@link RemittanceRowResult.Status#ERROR} row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RemittanceRowResult processRow(int rowNumber, List<String> row, Map<String, Integer> columns,
                                          boolean dryRun) {
        String awb = trimToNull(value(row, columns, "awb"));
        String orderCode = trimToNull(value(row, columns, "ordercode"));
        String clientOrderId = trimToNull(value(row, columns, "clientorderid"));
        String amountRaw = trimToNull(value(row, columns, "amount"));
        if (amountRaw == null) {
            amountRaw = trimToNull(value(row, columns, "codamount"));
        }
        String customerName = trimToNull(value(row, columns, "customername"));
        String remittedDateRaw = trimToNull(value(row, columns, "remitteddate"));

        if (awb == null && orderCode == null && clientOrderId == null) {
            return errorRow(rowNumber, awb, orderCode, clientOrderId, customerName,
                    "Row has neither a tracking id, order code, nor client order id.");
        }
        BigDecimal remittedAmount;
        try {
            remittedAmount = amountRaw == null ? null : new BigDecimal(amountRaw.replace(",", ""));
        } catch (NumberFormatException e) {
            return errorRow(rowNumber, awb, orderCode, clientOrderId, customerName,
                    "Amount '" + amountRaw + "' is not a valid number.");
        }
        if (remittedAmount == null) {
            return errorRow(rowNumber, awb, orderCode, clientOrderId, customerName, "Amount is required.");
        }
        LocalDate remittedDate = parseDate(remittedDateRaw);

        RowContext ctx = new RowContext(rowNumber, awb, orderCode, clientOrderId, customerName,
                remittedAmount, remittedDate, dryRun);

        OrderEntity order = resolveOrder(awb, orderCode, clientOrderId);
        if (order == null) {
            String detail = clientOrderId != null
                    ? "AWB '" + awb + "' / order code '" + orderCode + "' / client order id '" + clientOrderId + "'"
                    : "AWB '" + awb + "' / order code '" + orderCode + "'";
            return ctx.result(null, null, BigDecimal.ZERO, BigDecimal.ZERO,
                    RemittanceRowResult.Status.ORDER_NOT_FOUND, "No order matches " + detail + ".");
        }

        List<ReceivableEntity> existing = receivableRepository
                .findByOrderIdAndType(order.getId(), ReceivableType.COD_RECEIVABLE);
        Optional<ReceivableEntity> unsettled = existing.stream().filter(r -> !r.isSettled()).findFirst();

        if (unsettled.isPresent()) {
            return settleAgainstReceivable(ctx, order, unsettled.get());
        }
        if (!existing.isEmpty()) {
            ReceivableEntity prior = existing.get(0);
            return ctx.result(order, prior.getAmount(), BigDecimal.ZERO, BigDecimal.ZERO,
                    RemittanceRowResult.Status.ALREADY_SETTLED,
                    "Order " + order.getOrderCode() + " was already settled on " + prior.getSettledDate() + ".");
        }
        // No COD receivable row exists yet. If the order is a COD order (positive COD
        // amount) that is delivered or on its way, the remittance itself is proof the
        // courier collected + paid it out — so create and settle a receivable now
        // (the catch-up path), instead of the confusing "No receivable". This covers
        // the common case of a DELIVERED COD order whose receivable was never created.
        BigDecimal codAmount = order.getCodAmount();
        boolean isCod = codAmount != null && codAmount.signum() > 0;
        if (isCod && COD_COLLECTIBLE_STATUSES.contains(order.getOrderStatus())) {
            return deliverAndSettleFromRemittance(ctx, order);
        }
        // Genuine "nothing to settle" — spell out WHY so the accountant isn't puzzled
        // by a matching amount: either the order is prepaid (no COD component) or it
        // is in a terminal non-collectible state (RTO / Cancelled / Rejected / etc.).
        String reason = !isCod
                ? "Order " + order.getOrderCode() + " is a prepaid order (no COD to collect) — the remitted "
                        + ctx.remittedAmount() + " does not correspond to any COD receivable. Review manually."
                : "Order " + order.getOrderCode() + " is " + humanStatus(order.getOrderStatus())
                        + " — a non-collectible state, so it has no COD receivable to settle. Review manually.";
        return ctx.result(order, isCod ? codAmount : null, BigDecimal.ZERO, BigDecimal.ZERO,
                RemittanceRowResult.Status.NO_RECEIVABLE, reason);
    }

    private RemittanceRowResult settleAgainstReceivable(RowContext ctx, OrderEntity order,
                                                        ReceivableEntity receivable) {
        BigDecimal expected = receivable.getAmount();
        BigDecimal remittedAmount = ctx.remittedAmount();
        BigDecimal diff = remittedAmount.subtract(expected); // + = over-paid, - = short
        BigDecimal shortfall = expected.subtract(remittedAmount);

        // Over-paid beyond tolerance: never over-credit on a guess — flag for review.
        if (diff.compareTo(TOLERANCE) > 0) {
            return ctx.result(order, expected, BigDecimal.ZERO, BigDecimal.ZERO,
                    RemittanceRowResult.Status.MISMATCH,
                    "Order " + order.getOrderCode() + " expected " + expected + " but remittance OVER-paid "
                            + remittedAmount + " — review before settling manually.");
        }

        // Short-paid beyond tolerance: PARTIAL settle — credit the remitted amount,
        // keep the shortfall outstanding. Within tolerance (|diff| <= 1): full settle.
        boolean partial = shortfall.compareTo(TOLERANCE) > 0;
        BigDecimal settledAmount = partial ? remittedAmount : expected;
        BigDecimal outstandingShortfall = partial ? shortfall : BigDecimal.ZERO;

        if (!ctx.dryRun()) {
            if (partial) {
                receivable.settlePartial(remittedAmount, ctx.settleDate());
            } else {
                receivable.settle(ctx.settleDate());
            }
            receivableRepository.save(receivable);
            // Settling the courier's remittance is the moment the COD cash becomes
            // ours. If the order was left at DELIVERED with the COD outstanding (the
            // courier-COD-settlement flow — delivery no longer auto-settles), finish
            // the lifecycle now: advance Delivered → COD_Collected, set the remaining
            // customer outstanding to the shortfall (0 on a full settle), and book the
            // cash to the ledger. Guarded by the legal transition so a prepaid /
            // already-closed order is untouched.
            if (order.getOrderStatus() == OrderStatus.DELIVERED
                    && order.getOrderStatus().canTransitionTo(OrderStatus.COD_COLLECTED)) {
                orderWorkflowService.applyTransition(
                        order, OrderStatus.COD_COLLECTED, Actor.system("REMITTANCE_IMPORT", "RECONCILIATION"));
                order.setCustomerOutstanding(outstandingShortfall);
                orderRepository.save(order);
                outboxEventPublisher.publishLedgerPost(LEDGER_SOURCE_ORDER_DELIVERY, order.getId());
            }
        }

        if (partial) {
            return ctx.result(order, expected, settledAmount, outstandingShortfall,
                    RemittanceRowResult.Status.PARTIALLY_SETTLED,
                    (ctx.dryRun() ? "Would partially settle" : "Partially settled") + " order "
                            + order.getOrderCode() + " for " + remittedAmount + " of " + expected
                            + " — shortfall " + outstandingShortfall + " still outstanding.");
        }
        return ctx.result(order, expected, settledAmount, BigDecimal.ZERO,
                RemittanceRowResult.Status.SETTLED,
                (ctx.dryRun() ? "Would settle" : "Settled") + " order " + order.getOrderCode()
                        + " for " + remittedAmount + ".");
    }

    private RemittanceRowResult deliverAndSettleFromRemittance(RowContext ctx, OrderEntity order) {
        BigDecimal expected = order.getCodAmount();
        BigDecimal remittedAmount = ctx.remittedAmount();
        BigDecimal shortfall = expected.subtract(remittedAmount);
        boolean over = remittedAmount.subtract(expected).compareTo(TOLERANCE) > 0;
        boolean partial = !over && shortfall.compareTo(TOLERANCE) > 0;
        BigDecimal settledAmount = partial ? remittedAmount : (over ? expected : expected);
        BigDecimal outstandingShortfall = partial ? shortfall : BigDecimal.ZERO;
        RemittanceRowResult.Status status = partial
                ? RemittanceRowResult.Status.PARTIALLY_SETTLED
                : RemittanceRowResult.Status.SETTLED;

        // The order may be pre-delivery (needs the Delivered step first) or already
        // Delivered (only the Delivered → COD_Collected settle step is left — the
        // confusing "No receivable" case: delivered, but its COD receivable row was
        // never created).
        boolean alreadyDelivered = order.getOrderStatus() == OrderStatus.DELIVERED;
        String deliveredPhrase = alreadyDelivered ? "COD Collected" : "Delivered + COD Collected";

        if (ctx.dryRun()) {
            return ctx.result(order, expected, settledAmount, outstandingShortfall, status,
                    "Would mark " + order.getOrderCode() + " " + deliveredPhrase + " and "
                            + (partial ? "partially settle " + remittedAmount + " of " + expected
                                       : "settle " + remittedAmount)
                            + " (its COD receivable was never created — settling it from this remittance).");
        }
        Actor actor = Actor.system("REMITTANCE_IMPORT", "RECONCILIATION");
        if (!alreadyDelivered) {
            orderWorkflowService.applyTransition(order, OrderStatus.DELIVERED, actor);
        }
        orderWorkflowService.applyTransition(order, OrderStatus.COD_COLLECTED, actor);
        order.setCustomerOutstanding(outstandingShortfall);
        orderRepository.save(order);

        Long courierCompanyId = courierRecordRepository.findByOrderId(order.getId())
                .map(CourierRecord::getCourierCompanyId).orElse(null);
        ReceivableEntity receivable = new ReceivableEntity(
                order.getId(), courierCompanyId, ReceivableType.COD_RECEIVABLE, expected);
        if (partial) {
            receivable.settlePartial(remittedAmount, ctx.settleDate());
        } else {
            receivable.settle(ctx.settleDate());
        }
        receivableRepository.save(receivable);
        // Book the COD cash to the ledger now that it's remitted (same source as the
        // settle-against-existing-receivable path above).
        outboxEventPublisher.publishLedgerPost(LEDGER_SOURCE_ORDER_DELIVERY, order.getId());

        return ctx.result(order, expected, settledAmount, outstandingShortfall, status,
                "Marked " + order.getOrderCode() + " " + deliveredPhrase + " and "
                        + (partial ? "partially settled " + remittedAmount + " of " + expected
                                   : "settled " + remittedAmount)
                        + " (its COD receivable had not been created until now).");
    }

    // --- Order resolution -----------------------------------------------------

    private OrderEntity resolveOrder(String awb, String orderCode, String clientOrderId) {
        if (awb != null) {
            Optional<CourierRecord> record = courierRecordRepository.findByAwb(awb);
            if (record.isPresent()) {
                OrderEntity byRecord = orderRepository.findById(record.get().getOrderId()).orElse(null);
                if (byRecord != null) {
                    return byRecord;
                }
            }
            Optional<OrderShipment> shipment = orderShipmentRepository.findByAwb(awb);
            if (shipment.isPresent()) {
                OrderEntity byShipment = orderRepository.findById(shipment.get().getOrderId()).orElse(null);
                if (byShipment != null) {
                    return byShipment;
                }
            }
        }
        if (orderCode != null) {
            Optional<OrderEntity> byCode = orderRepository.findByOrderCode(orderCode);
            if (byCode.isPresent()) {
                return byCode.get();
            }
        }
        if (clientOrderId != null) {
            String shipperOrderId = extractShipperOrderId(clientOrderId);
            if (shipperOrderId != null) {
                Optional<OrderShipment> shipment = orderShipmentRepository.findByShipperOrderId(shipperOrderId);
                if (shipment.isPresent()) {
                    return orderRepository.findById(shipment.get().getOrderId()).orElse(null);
                }
            }
            Optional<OrderEntity> byCode = orderRepository.findByOrderCode(clientOrderId);
            if (byCode.isPresent()) {
                return byCode.get();
            }
        }
        return null;
    }

    private static String extractShipperOrderId(String clientOrderId) {
        Matcher m = TRAILING_DIGITS.matcher(clientOrderId);
        return m.find() ? m.group(1) : null;
    }

    /** A readable label for an order status in a review message (e.g. RTO → "returned (RTO)"). */
    private static String humanStatus(OrderStatus status) {
        return switch (status) {
            case RTO -> "returned to origin (RTO)";
            case REDISPATCH -> "lost/damaged in transit (Redispatch)";
            case CANCELLED -> "cancelled";
            case REJECTED -> "rejected";
            case PAYMENT_REJECTED -> "payment rejected";
            case CUSTOMER_REJECTED -> "refused by the customer";
            case DELIVERY_FAILED -> "a failed delivery";
            case CLOSED -> "closed (already settled as prepaid)";
            default -> status.name();
        };
    }

    // --- Internal helpers ------------------------------------------------------

    /**
     * The parsed inputs for one row, so the (now richer) {@link RemittanceRowResult}
     * is assembled in one place rather than threading a dozen args through each
     * outcome path.
     */
    private record RowContext(int rowNumber, String awb, String orderCode, String clientOrderId,
                              String customerName, BigDecimal remittedAmount, LocalDate remittedDate,
                              boolean dryRun) {
        LocalDate settleDate() {
            return remittedDate != null ? remittedDate : LocalDate.now();
        }

        RemittanceRowResult result(OrderEntity order, BigDecimal expected, BigDecimal settledAmount,
                                   BigDecimal shortfall, RemittanceRowResult.Status status, String message) {
            return new RemittanceRowResult(rowNumber, awb, orderCode, clientOrderId, customerName,
                    order != null ? order.getOrderCode() : null,
                    order != null ? order.getId() : null,
                    remittedAmount, expected, settledAmount, shortfall, remittedDate, status, message);
        }
    }

    private static RemittanceRowResult errorRow(int rowNumber, String awb, String orderCode,
                                                String clientOrderId, String customerName, String message) {
        return new RemittanceRowResult(rowNumber, awb, orderCode, clientOrderId, customerName, null, null,
                null, null, BigDecimal.ZERO, BigDecimal.ZERO, null, RemittanceRowResult.Status.ERROR, message);
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null) {
            return null;
        }
        for (java.time.format.DateTimeFormatter fmt : RemittanceImportService.DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, fmt);
            } catch (java.time.format.DateTimeParseException ignored) {
                // Try the next format.
            }
        }
        return null;
    }

    private static String value(List<String> row, Map<String, Integer> columns, String key) {
        Integer idx = columns.get(key);
        if (idx == null || idx >= row.size()) {
            return null;
        }
        return row.get(idx);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
