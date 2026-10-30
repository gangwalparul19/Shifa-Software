package com.shifa.oms.reconciliation;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.common.SpreadsheetParser;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderPaymentRepository;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.quikshipx.OrderShipment;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.reconciliation.dto.SettlementBatchResponse;
import com.shifa.oms.reconciliation.dto.SettlementImportResponse;
import com.shifa.oms.reconciliation.dto.SettlementLineRequest;
import com.shifa.oms.reconciliation.dto.SettlementLineResponse;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.settings.BankAccount;
import com.shifa.oms.settings.BankAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Operational settlement import ledger. It records matching decisions and
 * allocations but deliberately does not post vouchers or mutate COD lifecycle
 * state in this first slice.
 */
@Service
public class SettlementService {

    private static final BigDecimal TOLERANCE = new BigDecimal("1.00");
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"));

    private final SettlementBatchRepository batchRepository;
    private final SettlementLineRepository lineRepository;
    private final SettlementAllocationRepository allocationRepository;
    private final OrderRepository orderRepository;
    private final OrderPaymentRepository paymentRepository;
    private final OrderShipmentRepository shipmentRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final ReceivableRepository receivableRepository;
    private final BankAccountRepository bankAccountRepository;
    private final AuditService auditService;

    public SettlementService(SettlementBatchRepository batchRepository,
                             SettlementLineRepository lineRepository,
                             SettlementAllocationRepository allocationRepository,
                             OrderRepository orderRepository,
                             OrderPaymentRepository paymentRepository,
                             OrderShipmentRepository shipmentRepository,
                             CourierRecordRepository courierRecordRepository,
                             ReceivableRepository receivableRepository,
                             BankAccountRepository bankAccountRepository,
                             AuditService auditService) {
        this.batchRepository = batchRepository;
        this.lineRepository = lineRepository;
        this.allocationRepository = allocationRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.shipmentRepository = shipmentRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.receivableRepository = receivableRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<SettlementBatchResponse> listBatches() {
        return batchRepository.findTop100ByOrderByCreatedAtDescIdDesc().stream()
                .map(SettlementBatchResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<SettlementLineResponse> lines(Long batchId) {
        if (!batchRepository.existsById(batchId)) {
            throw new ValidationException("Settlement batch " + batchId + " does not exist.");
        }
        return lineRepository.findByBatchIdOrderBySourceRowNumberAsc(batchId).stream()
                .map(SettlementLineResponse::from).toList();
    }

    public SettlementImportResponse importFile(byte[] bytes, String filename, SettlementSource source,
                                               Long destinationAccountId, String externalReference,
                                               LocalDate statementDate, boolean dryRun, AuthPrincipal actor) {
        if (bytes == null || bytes.length == 0) {
            throw new ValidationException("The settlement file is empty.");
        }
        if (source == SettlementSource.BANK_RECEIPT && destinationAccountId == null) {
            throw new ValidationException("A destination account is required for bank receipt imports.");
        }
        BankAccount destination = null;
        if (destinationAccountId != null) {
            destination = bankAccountRepository.findById(destinationAccountId)
                    .orElseThrow(() -> new ValidationException("The settlement destination account does not exist."));
            if (!destination.isActive()) {
                throw new ValidationException("The settlement destination account is inactive.");
            }
        }
        String hash = sha256(bytes);
        if (!dryRun && (batchRepository.findByFileHash(hash).isPresent()
                || (externalReference != null && batchRepository.findBySourceChannelAndExternalReference(source, externalReference).isPresent()))) {
            throw new ValidationException("This settlement file or external reference was already imported.");
        }
        List<List<String>> rows;
        try {
            rows = SpreadsheetParser.parse(bytes, filename);
        } catch (IOException ex) {
            throw new ValidationException("Could not read the settlement file: " + ex.getMessage());
        }
        if (rows.isEmpty()) {
            throw new ValidationException("The settlement file has no rows.");
        }
        Map<String, Integer> columns = headerIndex(rows.get(0));
        if (!columns.containsKey("amount")) {
            throw new ValidationException("The settlement file needs an Amount, Net Amount, or Remitted Amount column.");
        }

        List<PlannedLine> planned = new ArrayList<>();
        Set<String> seenTargets = new HashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            PlannedLine line = plan(i, rows.get(i), columns, source, destinationAccountId,
                    statementDate, seenTargets);
            planned.add(line);
        }
        SettlementImportResponse preview = response(dryRun, null, source, externalReference, statementDate, planned);
        if (dryRun) return preview;

        SettlementBatch batch = new SettlementBatch(source, clean(externalReference), statementDate, destination,
                clean(filename), hash, actor == null ? null : actor.userId());
        batch.setLineCount(planned.size());
        batch.setTotalAmount(sum(planned.stream().map(PlannedLine::amount).toList()));
        batchRepository.save(batch);

        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal suspense = BigDecimal.ZERO;
        List<SettlementLine> savedLines = new ArrayList<>();
        for (PlannedLine p : planned) {
            SettlementLine line = p.toEntity(batch);
            lineRepository.save(line);
            savedLines.add(line);
            if (p.status() == SettlementMatchStatus.MATCHED) {
                BigDecimal amount = p.amount();
                Candidate candidate = p.candidate();
                SettlementAllocation allocation = new SettlementAllocation(line, candidate.targetType(), candidate.orderId(),
                        candidate.paymentId(), candidate.receivableId(), amount, actor == null ? null : actor.userId());
                allocationRepository.save(allocation);
                allocated = allocated.add(amount);
            } else {
                suspense = suspense.add(p.amount());
            }
        }
        batch.setAllocatedAmount(allocated.setScale(2));
        batch.setSuspenseAmount(suspense.setScale(2));
        batch.setStatus(suspense.signum() == 0 ? SettlementBatchStatus.POSTED : SettlementBatchStatus.POSTED_WITH_EXCEPTIONS);
        batch.setPostedAt(java.time.LocalDateTime.now());
        batchRepository.save(batch);
        auditService.record(AuditActions.SETTLEMENT_BATCH_IMPORTED, AuditActions.ENTITY_SETTLEMENT_BATCH,
                String.valueOf(batch.getId()), "Imported " + source + " settlement "
                        + (externalReference == null ? "" : externalReference) + ": "
                        + planned.size() + " rows, ₹" + batch.getTotalAmount() + ", suspense ₹" + suspense + ".");
        return response(false, batch.getId(), source, externalReference, statementDate, planned);
    }

    @Transactional
    public SettlementLineResponse resolve(Long lineId, SettlementLineRequest request, AuthPrincipal actor) {
        SettlementLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new ValidationException("Settlement line " + lineId + " does not exist."));
        if (line.getMatchStatus() == SettlementMatchStatus.MATCHED || line.getMatchStatus() == SettlementMatchStatus.RESOLVED) {
            throw new ValidationException("This settlement line is already allocated.");
        }
        if (request.amount().compareTo(line.getAmount()) > 0) {
            throw new ValidationException("Allocation cannot exceed the settlement-line amount.");
        }
        SettlementAllocation allocation = new SettlementAllocation(line, request.targetType(), request.orderId(),
                request.paymentId(), request.receivableId(), request.amount(), actor == null ? null : actor.userId());
        allocationRepository.save(allocation);
        line.setAllocatedAmount(request.amount());
        line.setSuspenseAmount(line.getAmount().subtract(request.amount()).max(BigDecimal.ZERO));
        line.setMatchStatus(line.getSuspenseAmount().signum() == 0 ? SettlementMatchStatus.RESOLVED : SettlementMatchStatus.MISMATCH);
        line.setMessage("Resolved manually by operator.");
        line.setResolvedBy(actor == null ? null : actor.userId());
        line.setResolvedAt(java.time.LocalDateTime.now());
        SettlementLine saved = lineRepository.save(line);
        recalculateBatch(line.getBatch());
        auditService.record(AuditActions.SETTLEMENT_SUSPENSE_RESOLVED, AuditActions.ENTITY_SETTLEMENT_LINE,
                String.valueOf(lineId), "Resolved settlement suspense line with ₹" + request.amount() + ".");
        return SettlementLineResponse.from(saved);
    }

    private void recalculateBatch(SettlementBatch batch) {
        List<SettlementLine> lines = lineRepository.findByBatchIdOrderBySourceRowNumberAsc(batch.getId());
        BigDecimal allocated = sum(lines.stream().map(SettlementLine::getAllocatedAmount).toList());
        BigDecimal suspense = sum(lines.stream().map(SettlementLine::getSuspenseAmount).toList());
        batch.setAllocatedAmount(allocated);
        batch.setSuspenseAmount(suspense);
        batch.setStatus(suspense.signum() == 0 ? SettlementBatchStatus.POSTED : SettlementBatchStatus.POSTED_WITH_EXCEPTIONS);
        batchRepository.save(batch);
    }

    private PlannedLine plan(int rowNumber, List<String> row, Map<String, Integer> columns,
                             SettlementSource source, Long bankAccountId, LocalDate statementDate,
                             Set<String> seenTargets) {
        String orderCode = value(row, columns, "ordercode");
        String shopifyId = value(row, columns, "shopifyorderid");
        String awb = value(row, columns, "awb");
        String utr = value(row, columns, "utr");
        String amountRaw = value(row, columns, "amount");
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountRaw.replace(",", "")).setScale(2);
        } catch (RuntimeException ex) {
            return PlannedLine.invalid(rowNumber, orderCode, shopifyId, awb, utr, BigDecimal.ZERO, "Amount is not valid.");
        }
        LocalDate date = parseDate(value(row, columns, "date"));
        if (date == null) {
            date = statementDate;
        }
        if (source == SettlementSource.BANK_RECEIPT && utr == null) {
            return PlannedLine.invalid(rowNumber, orderCode, shopifyId, awb, utr, amount,
                    "Bank receipt lines require a UTR/transaction reference.");
        }
        List<Candidate> candidates = candidates(source, orderCode, shopifyId, awb, utr, bankAccountId);
        if (candidates.isEmpty()) {
            return PlannedLine.unmatched(rowNumber, orderCode, shopifyId, awb, utr, date, amount,
                    "No exact order/payment reference matched this line.");
        }
        if (candidates.size() > 1) {
            return PlannedLine.ambiguous(rowNumber, orderCode, shopifyId, awb, utr, date, amount,
                    "More than one exact record matched this line.");
        }
        Candidate candidate = candidates.get(0);
        String targetId = candidate.paymentId() != null ? "payment:" + candidate.paymentId()
                : candidate.receivableId() != null ? "receivable:" + candidate.receivableId()
                : "order:" + candidate.orderId();
        String targetKey = candidate.targetType() + ":" + targetId;
        if (!seenTargets.add(targetKey)) {
            return PlannedLine.duplicate(rowNumber, orderCode, shopifyId, awb, utr, date, amount,
                    "The same target appears more than once in this file.");
        }
        BigDecimal expected = candidate.expectedAmount();
        BigDecimal variance = expected == null ? BigDecimal.ZERO : amount.subtract(expected).setScale(2);
        if (expected != null && variance.abs().compareTo(TOLERANCE) > 0) {
            return PlannedLine.mismatch(rowNumber, orderCode, shopifyId, awb, utr, date, amount, expected, variance,
                    candidate, "Amount differs from the matched record by more than ₹1.");
        }
        return PlannedLine.matched(rowNumber, orderCode, shopifyId, awb, utr, date, amount, expected, variance,
                candidate, "Exact " + candidate.method() + " match.");
    }

    private List<Candidate> candidates(SettlementSource source, String orderCode, String shopifyId,
                                       String awb, String utr, Long bankAccountId) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        if (utr != null && source == SettlementSource.BANK_RECEIPT) {
            for (OrderPaymentRepository.PaymentMatch p : paymentRepository.findByUtr(utr, bankAccountId)) {
                unique.put("payment:" + p.getPaymentId(), new Candidate(SettlementTargetType.ORDER_PAYMENT,
                        p.getOrderId(), p.getPaymentId(), null, p.getAmount(), SettlementMatchMethod.UTR));
            }
        }
        if (shopifyId != null && source == SettlementSource.SHOPIFY_PAYOUT) {
            orderRepository.findByShopifyOrderId(shopifyId).ifPresent(o -> unique.put("order:" + o.getId(),
                    new Candidate(SettlementTargetType.SHOPIFY_ORDER, o.getId(), null, null,
                            o.getAmountReceived(), SettlementMatchMethod.SHOPIFY_ORDER_ID)));
        }
        if (orderCode != null && source != SettlementSource.BANK_RECEIPT) {
            orderRepository.findByOrderCode(orderCode).ifPresent(o -> unique.put("order:" + o.getId(),
                    candidateForOrder(source, o, SettlementMatchMethod.ORDER_CODE)));
        }
        if (awb != null && source != SettlementSource.BANK_RECEIPT) {
            courierRecordRepository.findByAwb(awb).ifPresent(r -> addCourierCandidate(unique, source, r.getOrderId(), SettlementMatchMethod.AWB));
            shipmentRepository.findByAwb(awb).ifPresent(s -> addCourierCandidate(unique, source, s.getOrderId(), SettlementMatchMethod.AWB));
        }
        return new ArrayList<>(unique.values());
    }

    private void addCourierCandidate(Map<String, Candidate> unique, SettlementSource source, Long orderId,
                                     SettlementMatchMethod method) {
        orderRepository.findById(orderId).ifPresent(o -> unique.put("order:" + o.getId(),
                candidateForOrder(source, o, method)));
    }

    private Candidate candidateForOrder(SettlementSource source, OrderEntity order, SettlementMatchMethod method) {
        if (source == SettlementSource.QUICKSHIPX_COD) {
            List<ReceivableEntity> rows = receivableRepository.findByOrderIdAndType(order.getId(), ReceivableType.COD_RECEIVABLE)
                    .stream().filter(r -> !r.isSettled()).toList();
            if (rows.size() == 1) {
                ReceivableEntity r = rows.get(0);
                return new Candidate(SettlementTargetType.COD_RECEIVABLE, order.getId(), null, r.getId(),
                        r.getAmount(), method);
            }
            return new Candidate(SettlementTargetType.COD_RECEIVABLE, order.getId(), null, null,
                    order.getCodAmount(), method);
        }
        return new Candidate(source == SettlementSource.SHOPIFY_PAYOUT ? SettlementTargetType.SHOPIFY_ORDER : SettlementTargetType.ORDER_PAYMENT,
                order.getId(), null, null, order.getAmountReceived(), method);
    }

    private SettlementImportResponse response(boolean dryRun, Long batchId, SettlementSource source,
                                              String reference, LocalDate date, List<PlannedLine> planned) {
        Map<SettlementMatchStatus, Integer> counts = new EnumMap<>(SettlementMatchStatus.class);
        BigDecimal total = BigDecimal.ZERO, allocated = BigDecimal.ZERO, suspense = BigDecimal.ZERO;
        List<SettlementLineResponse> lines = new ArrayList<>();
        for (PlannedLine p : planned) {
            counts.merge(p.status(), 1, Integer::sum);
            total = total.add(p.amount());
            if (p.status() == SettlementMatchStatus.MATCHED) allocated = allocated.add(p.amount());
            else suspense = suspense.add(p.amount());
            if (dryRun) lines.add(p.preview());
        }
        return new SettlementImportResponse(dryRun, batchId, source, reference, date, planned.size(),
                total.setScale(2), allocated.setScale(2), suspense.setScale(2), counts, lines);
    }

    private record Candidate(SettlementTargetType targetType, Long orderId, Long paymentId,
                             Long receivableId, BigDecimal expectedAmount, SettlementMatchMethod method) { }

    private record PlannedLine(int rowNumber, String orderCode, String shopifyId, String awb, String utr,
                               LocalDate date, BigDecimal amount, BigDecimal expected, BigDecimal variance,
                               SettlementMatchStatus status, SettlementMatchMethod method, Candidate candidate,
                               String message) {
        static PlannedLine invalid(int n, String oc, String so, String awb, String utr, BigDecimal amount, String msg) {
            return new PlannedLine(n, oc, so, awb, utr, null, amount, null, BigDecimal.ZERO, SettlementMatchStatus.INVALID, null, null, msg);
        }
        static PlannedLine unmatched(int n, String oc, String so, String awb, String utr, LocalDate d, BigDecimal a, String msg) {
            return new PlannedLine(n, oc, so, awb, utr, d, a, null, BigDecimal.ZERO, SettlementMatchStatus.UNMATCHED, null, null, msg);
        }
        static PlannedLine ambiguous(int n, String oc, String so, String awb, String utr, LocalDate d, BigDecimal a, String msg) {
            return new PlannedLine(n, oc, so, awb, utr, d, a, null, BigDecimal.ZERO, SettlementMatchStatus.AMBIGUOUS, null, null, msg);
        }
        static PlannedLine duplicate(int n, String oc, String so, String awb, String utr, LocalDate d, BigDecimal a, String msg) {
            return new PlannedLine(n, oc, so, awb, utr, d, a, null, BigDecimal.ZERO, SettlementMatchStatus.DUPLICATE_IN_FILE, null, null, msg);
        }
        static PlannedLine mismatch(int n, String oc, String so, String awb, String utr, LocalDate d, BigDecimal a, BigDecimal e, BigDecimal v, Candidate c, String msg) {
            return new PlannedLine(n, oc, so, awb, utr, d, a, e, v, SettlementMatchStatus.MISMATCH, c.method(), c, msg);
        }
        static PlannedLine matched(int n, String oc, String so, String awb, String utr, LocalDate d, BigDecimal a, BigDecimal e, BigDecimal v, Candidate c, String msg) {
            return new PlannedLine(n, oc, so, awb, utr, d, a, e, v, SettlementMatchStatus.MATCHED, c.method(), c, msg);
        }
        SettlementLine toEntity(SettlementBatch batch) {
            SettlementLine line = new SettlementLine(batch, rowNumber);
            line.setOrderCode(orderCode); line.setShopifyOrderId(shopifyId); line.setAwb(awb); line.setUtr(utr);
            line.setTransactionDate(date); line.setAmount(amount); line.setExpectedAmount(expected); line.setVariance(variance);
            line.setMatchStatus(status); line.setMatchMethod(method); line.setMessage(message);
            if (candidate != null) {
                line.setResolvedOrderId(candidate.orderId()); line.setResolvedPaymentId(candidate.paymentId());
                line.setResolvedReceivableId(candidate.receivableId());
            }
            if (status == SettlementMatchStatus.MATCHED) line.setAllocatedAmount(amount);
            else line.setSuspenseAmount(amount);
            return line;
        }
        SettlementLineResponse preview() {
            return new SettlementLineResponse(null, rowNumber, orderCode, shopifyId, awb, utr, date, amount, expected,
                    variance, status == SettlementMatchStatus.MATCHED ? amount : BigDecimal.ZERO,
                    status == SettlementMatchStatus.MATCHED ? BigDecimal.ZERO : amount, status, method,
                    candidate == null ? null : candidate.orderId(), candidate == null ? null : candidate.paymentId(),
                    candidate == null ? null : candidate.receivableId(), message);
        }
    }

    private static String value(List<String> row, Map<String, Integer> columns, String key) {
        Integer index = columns.get(key);
        return index == null || index >= row.size() ? null : clean(row.get(index));
    }

    private static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> raw = new HashMap<>();
        for (int i = 0; i < header.size(); i++) raw.putIfAbsent(normalize(header.get(i)), i);
        Map<String, Integer> out = new HashMap<>();
        Map<String, List<String>> aliases = Map.of(
                "amount", List.of("amount", "netamount", "grossamount", "remittedamount", "codamount"),
                "ordercode", List.of("ordercode", "clientorderid"),
                "shopifyorderid", List.of("shopifyorderid", "shopifyid", "shopifyordernumber"),
                "awb", List.of("awb", "trackingid", "waybill"),
                "utr", List.of("utr", "utrnumber", "transactionid", "transactionreference"),
                "date", List.of("date", "settlementdate", "transactiondate", "remitteddate"));
        aliases.forEach((key, values) -> values.stream().map(raw::get).filter(java.util.Objects::nonNull).findFirst().ifPresent(i -> out.put(key, i)));
        return out;
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    private static String clean(String value) { if (value == null) return null; String s = value.trim(); return s.isEmpty() ? null : s; }
    private static LocalDate parseDate(String raw) { if (raw == null) return null; for (DateTimeFormatter f : DATES) try { return LocalDate.parse(raw, f); } catch (DateTimeParseException ignored) { } return null; }
    private static BigDecimal sum(List<BigDecimal> values) { return values.stream().filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2); }
    private static String sha256(byte[] bytes) { try { byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder s = new StringBuilder(); for (byte b : d) s.append(String.format("%02x", b)); return s.toString(); } catch (Exception e) { throw new ValidationException("Could not fingerprint settlement file."); } }
}
