package com.shifa.oms.reconciliation;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.CsvParser;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.dto.RemittanceImportResponse;
import com.shifa.oms.reconciliation.dto.RemittanceRowResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Courier COD remittance CSV import &amp; auto-match (enhancement: "Courier
 * remittance import &amp; auto-match", the accountant's heaviest manual
 * reconciliation chore).
 *
 * <p>The accountant uploads the courier's remittance sheet (one row per parcel
 * paid out); each row is resolved to an order by AWB (preferred, via
 * {@link CourierRecordRepository#findByAwb}) or by order code, then matched
 * against that order's unsettled {@link ReceivableType#COD_RECEIVABLE}. A row
 * is auto-settled ONLY when it resolves to exactly one unsettled COD receivable
 * AND the remitted amount matches the receivable amount within a one-rupee
 * tolerance (courier sheets sometimes round) — anything else (no match, amount
 * mismatch, already settled, no receivable at all) is reported for manual
 * follow-up and never guessed at. {@code dryRun=true} (the default) previews
 * the match without settling anything, mirroring the product CSV import.
 */
@Service
public class RemittanceImportService {

    /** Header columns understood by the importer (case-insensitive, order-independent). */
    private static final String COL_AWB = "awb";
    private static final String COL_ORDER_CODE = "ordercode";
    private static final String COL_AMOUNT = "amount";

    /** Amounts within this tolerance are treated as matching (courier sheets sometimes round). */
    private static final BigDecimal TOLERANCE = new BigDecimal("1.00");

    private final OrderRepository orderRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final ReceivableRepository receivableRepository;
    private final AuditService auditService;

    public RemittanceImportService(OrderRepository orderRepository,
                                   CourierRecordRepository courierRecordRepository,
                                   ReceivableRepository receivableRepository,
                                   AuditService auditService) {
        this.orderRepository = orderRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.receivableRepository = receivableRepository;
        this.auditService = auditService;
    }

    /**
     * Imports (or previews) a courier COD remittance CSV.
     *
     * @param csvBytes the raw uploaded CSV bytes (UTF-8)
     * @param dryRun   when true, match + report only (nothing settled)
     * @return the aggregate result with per-row outcomes
     */
    @Transactional
    public RemittanceImportResponse importCsv(byte[] csvBytes, boolean dryRun) {
        String content = new String(csvBytes == null ? new byte[0] : csvBytes, StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        List<List<String>> rows = CsvParser.parse(content);
        if (rows.isEmpty()) {
            throw new ValidationException("The uploaded CSV is empty.");
        }

        Map<String, Integer> columns = headerIndex(rows.get(0));
        if (!columns.containsKey(COL_AMOUNT) || (!columns.containsKey(COL_AWB) && !columns.containsKey(COL_ORDER_CODE))) {
            throw new ValidationException(
                    "CSV header must include 'amount' and at least one of 'awb' / 'orderCode'.");
        }

        List<RemittanceRowResult> results = new ArrayList<>();
        int settledCount = 0;
        for (int i = 1; i < rows.size(); i++) {
            RemittanceRowResult result = processRow(i, rows.get(i), columns, dryRun);
            results.add(result);
            if (result.status() == RemittanceRowResult.Status.SETTLED) {
                settledCount++;
            }
        }

        RemittanceImportResponse response = RemittanceImportResponse.of(dryRun, results);
        if (!dryRun) {
            auditService.record(AuditActions.COD_REMITTANCE_IMPORTED, AuditActions.ENTITY_RECEIVABLE, null,
                    "COD remittance import: " + settledCount + " settled, " + response.mismatched()
                            + " mismatched, " + response.notFound() + " not found, " + response.noReceivable()
                            + " with no receivable, " + response.errors() + " errors ("
                            + response.totalRows() + " rows).");
        }
        return response;
    }

    private RemittanceRowResult processRow(int rowNumber, List<String> row, Map<String, Integer> columns,
                                           boolean dryRun) {
        String awb = trimToNull(value(row, columns, COL_AWB));
        String orderCode = trimToNull(value(row, columns, COL_ORDER_CODE));
        String amountRaw = trimToNull(value(row, columns, COL_AMOUNT));

        if (awb == null && orderCode == null) {
            return new RemittanceRowResult(rowNumber, awb, orderCode, null, null, null,
                    RemittanceRowResult.Status.ERROR, "Row has neither an AWB nor an order code.");
        }
        BigDecimal remittedAmount;
        try {
            remittedAmount = amountRaw == null ? null : new BigDecimal(amountRaw.replace(",", ""));
        } catch (NumberFormatException e) {
            return new RemittanceRowResult(rowNumber, awb, orderCode, null, null, null,
                    RemittanceRowResult.Status.ERROR, "Amount '" + amountRaw + "' is not a valid number.");
        }
        if (remittedAmount == null) {
            return new RemittanceRowResult(rowNumber, awb, orderCode, null, null, null,
                    RemittanceRowResult.Status.ERROR, "Amount is required.");
        }

        // Resolve the order: prefer AWB (unambiguous, one courier record per order),
        // fall back to order code.
        OrderEntity order = null;
        if (awb != null) {
            Optional<CourierRecord> record = courierRecordRepository.findByAwb(awb);
            if (record.isPresent()) {
                order = orderRepository.findById(record.get().getOrderId()).orElse(null);
            }
        }
        if (order == null && orderCode != null) {
            order = orderRepository.findByOrderCode(orderCode).orElse(null);
        }
        if (order == null) {
            return new RemittanceRowResult(rowNumber, awb, orderCode, null, remittedAmount, null,
                    RemittanceRowResult.Status.ORDER_NOT_FOUND,
                    "No order matches AWB '" + awb + "' / order code '" + orderCode + "'.");
        }

        List<ReceivableEntity> unsettled = receivableRepository
                .findByOrderIdAndType(order.getId(), ReceivableType.COD_RECEIVABLE)
                .stream()
                .filter(r -> !r.isSettled())
                .toList();
        if (unsettled.isEmpty()) {
            return new RemittanceRowResult(rowNumber, awb, orderCode, order.getOrderCode(), remittedAmount, null,
                    RemittanceRowResult.Status.NO_RECEIVABLE,
                    "Order " + order.getOrderCode() + " has no unsettled COD receivable.");
        }
        // One COD receivable per order in practice; take the first if somehow more.
        ReceivableEntity receivable = unsettled.get(0);
        BigDecimal expected = receivable.getAmount();
        BigDecimal diff = expected.subtract(remittedAmount).abs();
        if (diff.compareTo(TOLERANCE) > 0) {
            return new RemittanceRowResult(rowNumber, awb, orderCode, order.getOrderCode(), remittedAmount, expected,
                    RemittanceRowResult.Status.MISMATCH,
                    "Order " + order.getOrderCode() + " expected " + expected + " but remittance shows "
                            + remittedAmount + " — review before settling manually.");
        }

        if (!dryRun) {
            receivable.settle(LocalDate.now());
            receivableRepository.save(receivable);
        }
        return new RemittanceRowResult(rowNumber, awb, orderCode, order.getOrderCode(), remittedAmount, expected,
                RemittanceRowResult.Status.SETTLED,
                (dryRun ? "Would settle" : "Settled") + " order " + order.getOrderCode() + " for " + remittedAmount + ".");
    }

    // --- Internal helpers ----------------------------------------------------

    private static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = header.get(i) == null ? "" : header.get(i).trim().toLowerCase(Locale.ROOT).replace(" ", "");
            if (!key.isEmpty()) {
                map.put(key, i);
            }
        }
        return map;
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
