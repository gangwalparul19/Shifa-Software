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
        String remittedDateRaw = trimToNull(value(row, columns, "remitteddate"));

        if (awb == null && orderCode == null && clientOrderId == null) {
            return errorRow(rowNumber, awb, orderCode,
                    "Row has neither a tracking id, order code, nor client order id.");
        }
        BigDecimal remittedAmount;
        try {
            remittedAmount = amountRaw == null ? null : new BigDecimal(amountRaw.replace(",", ""));
        } catch (NumberFormatException e) {
            return errorRow(rowNumber, awb, orderCode, "Amount '" + amountRaw + "' is not a valid number.");
        }
        if (remittedAmount == null) {
            return errorRow(rowNumber, awb, orderCode, "Amount is required.");
        }
        LocalDate remittedDate = parseDate(remittedDateRaw);

        OrderEntity order = resolveOrder(awb, orderCode, clientOrderId);
        if (order == null) {
            String detail = clientOrderId != null
                    ? "AWB '" + awb + "' / order code '" + orderCode + "' / client order id '" + clientOrderId + "'"
                    : "AWB '" + awb + "' / order code '" + orderCode + "'";
            return new RemittanceRowResult(rowNumber, awb, orderCode, null, remittedAmount, null,
                    RemittanceRowResult.Status.ORDER_NOT_FOUND, "No order matches " + detail + ".");
        }

        List<ReceivableEntity> existing = receivableRepository
                .findByOrderIdAndType(order.getId(), ReceivableType.COD_RECEIVABLE);
        Optional<ReceivableEntity> unsettled = existing.stream().filter(r -> !r.isSettled()).findFirst();

        if (unsettled.isPresent()) {
            return settleAgainstReceivable(rowNumber, awb, orderCode, order, unsettled.get(), remittedAmount,
                    remittedDate, dryRun);
        }
        if (!existing.isEmpty()) {
            return new RemittanceRowResult(rowNumber, awb, orderCode, order.getOrderCode(), remittedAmount,
                    existing.get(0).getAmount(), RemittanceRowResult.Status.ALREADY_SETTLED,
                    "Order " + order.getOrderCode() + " was already settled on "
                            + existing.get(0).getSettledDate() + ".");
        }
        if (PRE_DELIVERY_STATUSES.contains(order.getOrderStatus())) {
            return deliverAndSettleFromRemittance(rowNumber, awb, orderCode, order, remittedAmount, remittedDate, dryRun);
        }
        return new RemittanceRowResult(rowNumber, awb, orderCode, order.getOrderCode(), remittedAmount, null,
                RemittanceRowResult.Status.NO_RECEIVABLE,
                "Order " + order.getOrderCode() + " (" + order.getOrderStatus()
                        + ") has no COD receivable and is not awaiting delivery — review manually.");
    }

    private RemittanceRowResult settleAgainstReceivable(int rowNumber, String awb, String orderCode,
                                                         OrderEntity order, ReceivableEntity receivable,
                                                         BigDecimal remittedAmount, LocalDate remittedDate,
                                                         boolean dryRun) {
        BigDecimal expected = receivable.getAmount();
        BigDecimal diff = expected.subtract(remittedAmount).abs();
        if (diff.compareTo(TOLERANCE) > 0) {
            return new RemittanceRowResult(rowNumber, awb, orderCode, order.getOrderCode(), remittedAmount, expected,
                    RemittanceRowResult.Status.MISMATCH,
                    "Order " + order.getOrderCode() + " expected " + expected + " but remittance shows "
                            + remittedAmount + " — review before settling manually.");
        }
        if (!dryRun) {
            receivable.settle(remittedDate != null ? remittedDate : LocalDate.now());
            receivableRepository.save(receivable);
            // Settling the courier's remittance is the moment the COD cash becomes
            // ours. If the order was left at DELIVERED with the COD outstanding (the
            // courier-COD-settlement flow — delivery no longer auto-settles), finish
            // the lifecycle now: advance Delivered → COD_Collected, clear the
            // customer outstanding, and book the cash to the ledger dated the
            // delivery/settlement. Guarded by the legal transition so a prepaid /
            // already-closed order is untouched.
            if (order.getOrderStatus() == OrderStatus.DELIVERED
                    && order.getOrderStatus().canTransitionTo(OrderStatus.COD_COLLECTED)) {
                orderWorkflowService.applyTransition(
                        order, OrderStatus.COD_COLLECTED, Actor.system("REMITTANCE_IMPORT", "RECONCILIATION"));
                order.setCustomerOutstanding(BigDecimal.ZERO);
                orderRepository.save(order);
                outboxEventPublisher.publishLedgerPost(LEDGER_SOURCE_ORDER_DELIVERY, order.getId());
            }
        }
        return new RemittanceRowResult(rowNumber, awb, orderCode, order.getOrderCode(), remittedAmount, expected,
                RemittanceRowResult.Status.SETTLED,
                (dryRun ? "Would settle" : "Settled") + " order " + order.getOrderCode() + " for " + remittedAmount + ".");
    }

    private RemittanceRowResult deliverAndSettleFromRemittance(int rowNumber, String awb, String orderCode,
                                                                OrderEntity order, BigDecimal remittedAmount,
                                                                LocalDate remittedDate, boolean dryRun) {
        if (dryRun) {
            return new RemittanceRowResult(rowNumber, awb, orderCode, order.getOrderCode(), remittedAmount,
                    order.getCodAmount(), RemittanceRowResult.Status.SETTLED,
                    "Would mark " + order.getOrderCode() + " Delivered + COD Collected and settle "
                            + remittedAmount + " (no delivery update was ever received for this order).");
        }
        Actor actor = Actor.system("REMITTANCE_IMPORT", "RECONCILIATION");
        orderWorkflowService.applyTransition(order, OrderStatus.DELIVERED, actor);
        orderWorkflowService.applyTransition(order, OrderStatus.COD_COLLECTED, actor);
        order.setCustomerOutstanding(BigDecimal.ZERO);
        orderRepository.save(order);

        Long courierCompanyId = courierRecordRepository.findByOrderId(order.getId())
                .map(CourierRecord::getCourierCompanyId).orElse(null);
        ReceivableEntity receivable = new ReceivableEntity(
                order.getId(), courierCompanyId, ReceivableType.COD_RECEIVABLE, order.getCodAmount());
        receivable.settle(remittedDate != null ? remittedDate : LocalDate.now());
        receivableRepository.save(receivable);
        // Book the COD cash to the ledger now that it's remitted (same source as the
        // settle-against-existing-receivable path above).
        outboxEventPublisher.publishLedgerPost(LEDGER_SOURCE_ORDER_DELIVERY, order.getId());

        return new RemittanceRowResult(rowNumber, awb, orderCode, order.getOrderCode(), remittedAmount,
                order.getCodAmount(), RemittanceRowResult.Status.SETTLED,
                "Marked " + order.getOrderCode() + " Delivered + COD Collected and settled " + remittedAmount
                        + " (no delivery update had been received for this order until now).");
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

    // --- Internal helpers ------------------------------------------------------

    private static RemittanceRowResult errorRow(int rowNumber, String awb, String orderCode, String message) {
        return new RemittanceRowResult(rowNumber, awb, orderCode, null, null, null,
                RemittanceRowResult.Status.ERROR, message);
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
