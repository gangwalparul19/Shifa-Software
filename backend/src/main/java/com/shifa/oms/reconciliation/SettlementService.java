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
import com.shifa.oms.order.UtrNormalizer;
import com.shifa.oms.quikshipx.OrderShipment;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.reconciliation.dto.SettlementBatchResponse;
import com.shifa.oms.reconciliation.dto.SettlementImportResponse;
import com.shifa.oms.reconciliation.dto.SettlementLineRequest;
import com.shifa.oms.reconciliation.dto.SettlementLineResponse;
import com.shifa.oms.reconciliation.dto.SettlementCandidateResponse;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.settings.BankAccount;
import com.shifa.oms.settings.BankAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final PlatformTransactionManager transactionManager;

    /** Compatibility constructor for direct unit tests; Spring uses the @Autowired constructor. */
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
        this(batchRepository, lineRepository, allocationRepository, orderRepository, paymentRepository,
                shipmentRepository, courierRecordRepository, receivableRepository, bankAccountRepository,
                auditService, null);
    }

    @Autowired
    public SettlementService(SettlementBatchRepository batchRepository,
                             SettlementLineRepository lineRepository,
                             SettlementAllocationRepository allocationRepository,
                             OrderRepository orderRepository,
                             OrderPaymentRepository paymentRepository,
                             OrderShipmentRepository shipmentRepository,
                             CourierRecordRepository courierRecordRepository,
                             ReceivableRepository receivableRepository,
                             BankAccountRepository bankAccountRepository,
                             AuditService auditService,
                             PlatformTransactionManager transactionManager) {
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
        this.transactionManager = transactionManager;
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
                .map(this::lineResponse).toList();
    }

    public SettlementImportResponse importFile(byte[] bytes, String filename, SettlementSource source,
                                               Long destinationAccountId, String externalReference,
                                               LocalDate statementDate, boolean dryRun, AuthPrincipal actor) {
        if (bytes == null || bytes.length == 0) throw new ValidationException("The settlement file is empty.");
        if (source == null) throw new ValidationException("A settlement source is required.");
        String reference = clean(externalReference);
        String hash = sha256(bytes);
        if (!dryRun) {
            Optional<SettlementBatch> existing = existingImport(hash, source, reference);
            if (existing.isPresent()) return existingResponse(existing.get());
        }
        if (source == SettlementSource.BANK_RECEIPT && destinationAccountId == null) {
            throw new ValidationException("A destination account is required for bank receipt imports.");
        }
        BankAccount destination = null;
        if (destinationAccountId != null) {
            destination = bankAccountRepository.findById(destinationAccountId)
                    .orElseThrow(() -> new ValidationException("The settlement destination account does not exist."));
            if (!destination.isActive()) throw new ValidationException("The settlement destination account is inactive.");
        }
        if (statementDate == null) statementDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        List<List<String>> rows;
        try {
            rows = SpreadsheetParser.parse(bytes, filename);
        } catch (IOException ex) {
            throw new ValidationException("Could not read the settlement file: " + ex.getMessage());
        }
        if (rows.isEmpty()) throw new ValidationException("The settlement file has no rows.");
        Map<String, Integer> columns = headerIndex(rows.get(0));
        if (!columns.containsKey("amount")) {
            throw new ValidationException("The settlement file needs an Amount, Net Amount, or Remitted Amount column.");
        }
        List<PlannedLine> planned = new ArrayList<>();
        Set<String> seenTargets = new HashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            planned.add(plan(i, rows.get(i), columns, source, destinationAccountId, statementDate, seenTargets));
        }
        SettlementImportResponse preview = response(dryRun, null, source, reference, statementDate, planned);
        if (dryRun) return preview;
        try {
            final LocalDate commitDate = statementDate;
            final BankAccount commitDestination = destination;
            if (transactionManager == null) {
                return persistImport(planned, source, reference, commitDate, commitDestination, clean(filename), hash, actor);
            }
            SettlementImportResponse committed = new TransactionTemplate(transactionManager)
                    .execute(status -> persistImport(planned, source, reference, commitDate, commitDestination,
                            clean(filename), hash, actor));
            if (committed == null) throw new IllegalStateException("Settlement commit returned no result.");
            return committed;
        } catch (DataIntegrityViolationException duplicate) {
            Optional<SettlementBatch> raced = existingImport(hash, source, reference);
            if (raced.isPresent()) return existingResponse(raced.get());
            throw duplicate;
        }
    }

    private SettlementImportResponse persistImport(List<PlannedLine> planned, SettlementSource source,
                                                   String reference, LocalDate statementDate, BankAccount destination,
                                                   String filename, String hash, AuthPrincipal actor) {
        SettlementBatch batch = new SettlementBatch(source, reference, statementDate, destination, filename, hash,
                actor == null ? null : actor.userId());
        batch.setLineCount(planned.size());
        batch.setTotalAmount(sum(planned.stream().map(PlannedLine::amount).toList()));
        batchRepository.saveAndFlush(batch);
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal suspense = BigDecimal.ZERO;
        for (PlannedLine p : planned) {
            SettlementLine line = p.toEntity(batch);
            lineRepository.saveAndFlush(line);
            if (p.status() == SettlementMatchStatus.MATCHED) {
                Candidate candidate = p.candidate();
                SettlementLineRequest automatic = new SettlementLineRequest(candidate.targetType(), candidate.orderId(),
                        candidate.paymentId(), candidate.receivableId(), p.amount(), "Automatic exact statement match");
                Target target = loadAndValidateTarget(line, automatic);
                String key = SettlementAllocation.canonicalKey(candidate.targetType(), candidate.orderId(),
                        candidate.paymentId(), candidate.receivableId());
                BigDecimal used = allocationRepository.sumByTargetKey(key);
                BigDecimal remaining = target.available().subtract(used).setScale(2);
                if (p.amount().compareTo(remaining) > 0) {
                    throw new ValidationException("The matched target has only ₹" + remaining + " remaining; the statement amount is ₹" + p.amount() + ".");
                }
                allocationRepository.save(new SettlementAllocation(line, candidate.targetType(), candidate.orderId(),
                        candidate.paymentId(), candidate.receivableId(), p.amount(), actor == null ? null : actor.userId()));
                allocated = allocated.add(p.amount());
            } else {
                suspense = suspense.add(p.amount());
            }
        }
        batch.setAllocatedAmount(allocated.setScale(2));
        batch.setSuspenseAmount(suspense.setScale(2));
        batch.setStatus(suspense.signum() == 0 ? SettlementBatchStatus.POSTED : SettlementBatchStatus.POSTED_WITH_EXCEPTIONS);
        batch.setPostedAt(java.time.LocalDateTime.now());
        batchRepository.save(batch);
        // AuditService intentionally keeps the application's established best-effort policy:
        // its write joins this commit transaction when successful, while an audit-store failure
        // is logged and does not turn a valid financial import into an orphaned retry.
        auditService.record(AuditActions.SETTLEMENT_BATCH_IMPORTED, AuditActions.ENTITY_SETTLEMENT_BATCH,
                String.valueOf(batch.getId()), "Imported " + source + " settlement hash " + hash
                        + ", reference " + (reference == null ? "(none)" : reference) + ": "
                        + planned.size() + " rows, total ₹" + batch.getTotalAmount()
                        + ", allocated ₹" + allocated + ", suspense ₹" + suspense + ".");
        return response(false, batch.getId(), source, reference, statementDate, planned,
                lineRepository.findByBatchIdOrderBySourceRowNumberAsc(batch.getId()));
    }

    private Optional<SettlementBatch> existingImport(String hash, SettlementSource source, String reference) {
        Optional<SettlementBatch> byHash = batchRepository.findByFileHash(hash);
        if (byHash.isPresent()) return byHash;
        if (reference == null) return Optional.empty();
        Optional<SettlementBatch> byReference = batchRepository.findBySourceChannelAndExternalReference(source, reference);
        if (byReference.isPresent() && !hash.equals(byReference.get().getFileHash())) {
            throw new ValidationException("This external reference was already imported with a different file.");
        }
        return byReference;
    }

    private SettlementImportResponse existingResponse(SettlementBatch batch) {
        List<SettlementLine> persisted = lineRepository.findByBatchIdOrderBySourceRowNumberAsc(batch.getId());
        Map<SettlementMatchStatus, Integer> counts = new EnumMap<>(SettlementMatchStatus.class);
        persisted.forEach(line -> counts.merge(line.getMatchStatus(), 1, Integer::sum));
        return new SettlementImportResponse(false, batch.getId(), batch.getSourceChannel(), batch.getExternalReference(),
                batch.getStatementDate(), batch.getLineCount(), batch.getTotalAmount(), batch.getAllocatedAmount(),
                batch.getSuspenseAmount(), counts, persisted.stream().map(this::lineResponse).toList());
    }

    @Transactional(readOnly = true)
    public List<SettlementCandidateResponse> candidatesForLine(Long lineId) {
        SettlementLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new ValidationException("Settlement line " + lineId + " does not exist."));
        SettlementSource source = line.getBatch().getSourceChannel();
        List<SettlementCandidateResponse> result = new ArrayList<>();
        if (source == SettlementSource.BANK_RECEIPT && line.getUtr() != null) {
            for (OrderPaymentRepository.PaymentMatch p : paymentRepository.findByUtr(line.getUtr(),
                    line.getBatch().getDestinationAccount() == null ? null : line.getBatch().getDestinationAccount().getId())) {
                orderRepository.findById(p.getOrderId()).ifPresent(o -> result.add(paymentCandidate(p, o)));
            }
        } else if (source == SettlementSource.SHOPIFY_PAYOUT) {
            if (line.getShopifyOrderId() != null) orderRepository.findByShopifyOrderId(line.getShopifyOrderId())
                    .filter(o -> o.getSource() == OrderSource.SHOPIFY).ifPresent(o -> result.add(orderCandidate(o)));
            if (result.isEmpty() && line.getOrderCode() != null) orderRepository.findByOrderCode(line.getOrderCode())
                    .filter(o -> o.getSource() == OrderSource.SHOPIFY).ifPresent(o -> result.add(orderCandidate(o)));
        } else if (source == SettlementSource.QUICKSHIPX_COD) {
            if (line.getOrderCode() != null) {
                OrderEntity order = orderRepository.findByOrderCode(line.getOrderCode()).orElse(null);
                if (order != null) receivableRepository.findByOrderIdAndType(order.getId(), ReceivableType.COD_RECEIVABLE).stream()
                        .filter(r -> !r.isSettled() && r.getAmount() != null && r.getAmount().signum() > 0)
                        .forEach(r -> result.add(receivableCandidate(r, order)));
            }
            if (result.isEmpty() && line.getAwb() != null) {
                Optional<Long> orderId = shipmentRepository.findByAwb(line.getAwb()).map(OrderShipment::getOrderId);
                if (orderId.isEmpty()) orderId = courierRecordRepository.findByAwb(line.getAwb()).map(CourierRecord::getOrderId);
                if (orderId.isPresent()) {
                    orderRepository.findById(orderId.get()).ifPresent(order ->
                            receivableRepository.findByOrderIdAndType(order.getId(), ReceivableType.COD_RECEIVABLE).stream()
                                    .filter(r -> !r.isSettled() && r.getAmount() != null && r.getAmount().signum() > 0)
                                    .forEach(r -> result.add(receivableCandidate(r, order))));
                }
            }
        }
        return result;
    }

    @Transactional
    public SettlementLineResponse resolve(Long lineId, SettlementLineRequest request, AuthPrincipal actor) {
        if (request == null || request.amount() == null || request.amount().signum() <= 0
                || request.amount().scale() > 2) {
            throw new ValidationException("Allocation amount must be positive and have at most two decimal places.");
        }
        String reason = request.reason() == null ? null : request.reason().trim();
        if (reason == null || reason.isEmpty()) throw new ValidationException("A manual settlement resolution reason is required.");
        if (reason.length() > 500) throw new ValidationException("The settlement resolution reason cannot exceed 500 characters.");
        SettlementLine line = lineRepository.findByIdForUpdate(lineId)
                .orElseThrow(() -> new ValidationException("Settlement line " + lineId + " does not exist."));
        SettlementBatch batch = batchRepository.findByIdForUpdate(line.getBatch().getId())
                .orElseThrow(() -> new ValidationException("The settlement batch no longer exists."));
        validateTargetShape(request);
        Target target = loadAndValidateTarget(line, request);
        String key = SettlementAllocation.canonicalKey(request.targetType(), request.orderId(), request.paymentId(), request.receivableId());
        Optional<SettlementAllocation> existing = allocationRepository.findBySettlementLineIdAndTargetKey(lineId, key);
        if (existing.isPresent()) {
            if (existing.get().getAmount().compareTo(request.amount()) != 0) {
                throw new ValidationException("This settlement target is already allocated with a different amount.");
            }
            return SettlementLineResponse.from(line, existing.get());
        }
        BigDecimal existingLine = allocationRepository.sumByLineId(lineId).setScale(2);
        BigDecimal remainingLine = line.getAmount().subtract(existingLine).setScale(2);
        if (remainingLine.signum() <= 0 || request.amount().compareTo(remainingLine) > 0) {
            throw new ValidationException("Allocation exceeds the line's remaining amount.");
        }
        BigDecimal targetUsed = allocationRepository.sumByTargetKey(key).setScale(2);
        BigDecimal targetRemaining = target.available().subtract(targetUsed).setScale(2);
        if (targetRemaining.signum() <= 0 || request.amount().compareTo(targetRemaining) > 0) {
            throw new ValidationException("Allocation exceeds the target's remaining available amount.");
        }
        SettlementAllocation allocation = new SettlementAllocation(line, request.targetType(), request.orderId(), request.paymentId(),
                request.receivableId(), request.amount().setScale(2), actor == null ? null : actor.userId(), key, reason);
        allocationRepository.saveAndFlush(allocation);
        BigDecimal allocated = existingLine.add(request.amount()).setScale(2);
        line.setAllocatedAmount(allocated);
        line.setSuspenseAmount(line.getAmount().subtract(allocated).max(BigDecimal.ZERO).setScale(2));
        line.setMatchStatus(line.getSuspenseAmount().signum() == 0 ? SettlementMatchStatus.RESOLVED : SettlementMatchStatus.MISMATCH);
        line.setMessage("Resolved manually by operator. Reason: " + reason);
        line.setResolvedOrderId(request.orderId());
        line.setResolvedPaymentId(request.paymentId());
        line.setResolvedReceivableId(request.receivableId());
        line.setResolvedBy(actor == null ? null : actor.userId());
        line.setResolvedAt(java.time.LocalDateTime.now());
        SettlementLine saved = lineRepository.saveAndFlush(line);
        recalculateBatch(batch);
        auditService.record(AuditActions.SETTLEMENT_SUSPENSE_RESOLVED, AuditActions.ENTITY_SETTLEMENT_LINE,
                String.valueOf(lineId), "Manual settlement resolution: actor="
                        + (actor == null ? "system" : actor.userId()) + ", batch=" + batch.getId()
                        + ", target=" + key + ", amount=₹" + request.amount().setScale(2)
                        + ", reason=" + reason + ".");
        return SettlementLineResponse.from(saved, allocation);
    }

    private void validateTargetShape(SettlementLineRequest r) {
        if (r.targetType() == null) throw new ValidationException("A settlement target type is required.");
        long count = (r.orderId() == null ? 0 : 1) + (r.paymentId() == null ? 0 : 1) + (r.receivableId() == null ? 0 : 1);
        if (r.targetType() == SettlementTargetType.ORDER_PAYMENT && (r.paymentId() == null || count != 1)) {
            throw new ValidationException("ORDER_PAYMENT requires paymentId only.");
        }
        if (r.targetType() == SettlementTargetType.SHOPIFY_ORDER && (r.orderId() == null || count != 1)) {
            throw new ValidationException("SHOPIFY_ORDER requires orderId only.");
        }
        if (r.targetType() == SettlementTargetType.COD_RECEIVABLE && (r.receivableId() == null || count != 1)) {
            throw new ValidationException("COD_RECEIVABLE requires receivableId only.");
        }
    }

    private Target loadAndValidateTarget(SettlementLine line, SettlementLineRequest r) {
        SettlementBatch batch = line.getBatch();
        SettlementSource source = batch.getSourceChannel();
        if (r.targetType() == SettlementTargetType.ORDER_PAYMENT) {
            if (source != SettlementSource.BANK_RECEIPT) throw new ValidationException("This settlement source cannot allocate an order payment.");
            if (batch.getDestinationAccount() == null) throw new ValidationException("A bank settlement line has no destination account.");
            var payment = paymentRepository.findByIdForUpdate(r.paymentId())
                    .orElseThrow(() -> new ValidationException("The payment target does not exist."));
            if (payment.getAmountReceived() == null || payment.getAmountReceived().signum() <= 0) {
                throw new ValidationException("The payment target has no positive amount available.");
            }
            if (!batch.getDestinationAccount().getId().equals(payment.getBankAccountId())) {
                throw new ValidationException("The payment belongs to a different settlement account.");
            }
            if (line.getUtr() == null || payment.getUtr() == null
                    || !normalizeUtr(line.getUtr()).equals(normalizeUtr(payment.getUtr()))) {
                throw new ValidationException("The payment UTR does not match this settlement line.");
            }
            Long paymentOrderId = paymentRepository.findMatchById(r.paymentId())
                    .map(OrderPaymentRepository.PaymentMatch::getOrderId)
                    .orElseThrow(() -> new ValidationException("The payment's order does not exist."));
            OrderEntity order = orderRepository.findByIdForUpdate(paymentOrderId)
                    .orElseThrow(() -> new ValidationException("The payment's order does not exist."));
            validateOrderReference(line, order, false, true);
            return new Target(payment.getAmountReceived(), payment.getAmountReceived());
        }
        if (r.targetType() == SettlementTargetType.SHOPIFY_ORDER) {
            if (source != SettlementSource.SHOPIFY_PAYOUT) throw new ValidationException("This settlement source cannot allocate a Shopify order.");
            OrderEntity order = orderRepository.findByIdForUpdate(r.orderId())
                    .orElseThrow(() -> new ValidationException("The Shopify order target does not exist."));
            if (order.getSource() != OrderSource.SHOPIFY || order.getShopifyOrderId() == null) {
                throw new ValidationException("The target is not a Shopify order.");
            }
            validateOrderReference(line, order, true);
            if (order.getAmountReceived() == null || order.getAmountReceived().signum() <= 0) {
                throw new ValidationException("The Shopify order has no positive settled amount available.");
            }
            return new Target(order.getAmountReceived(), order.getAmountReceived());
        }
        if (source != SettlementSource.QUICKSHIPX_COD) throw new ValidationException("This settlement source cannot allocate a COD receivable.");
        ReceivableEntity rec = receivableRepository.findByIdForUpdate(r.receivableId())
                .orElseThrow(() -> new ValidationException("The COD receivable target does not exist."));
        if (rec.getType() != ReceivableType.COD_RECEIVABLE || rec.isSettled()
                || rec.getAmount() == null || rec.getAmount().signum() <= 0) {
            throw new ValidationException("The COD receivable is not available.");
        }
        OrderEntity order = orderRepository.findByIdForUpdate(rec.getOrderId())
                .orElseThrow(() -> new ValidationException("The COD receivable's order does not exist."));
        validateOrderReference(line, order, false);
        return new Target(rec.getAmount(), rec.getAmount());
    }

    private void validateOrderReference(SettlementLine line, OrderEntity order, boolean shopify) {
        validateOrderReference(line, order, shopify, false);
    }

    private void validateOrderReference(SettlementLine line, OrderEntity order, boolean shopify, boolean allowUtr) {
        boolean hasReference = false;
        if (line.getOrderCode() != null) {
            hasReference = true;
            if (!line.getOrderCode().equals(order.getOrderCode())) {
                throw new ValidationException("The target does not belong to this settlement order reference.");
            }
        }
        if (line.getShopifyOrderId() != null) {
            hasReference = true;
            if (!shopify || !line.getShopifyOrderId().equals(order.getShopifyOrderId())) {
                throw new ValidationException("The target does not belong to this Shopify reference.");
            }
        }
        if (line.getAwb() != null) {
            hasReference = true;
            boolean awbMatches = shipmentRepository.findByAwb(line.getAwb()).map(s -> order.getId().equals(s.getOrderId())).orElse(false)
                    || courierRecordRepository.findByAwb(line.getAwb()).map(c -> order.getId().equals(c.getOrderId())).orElse(false);
            if (!awbMatches) throw new ValidationException("The target does not belong to this AWB.");
        }
        if (!hasReference && !(allowUtr && line.getUtr() != null)) {
            throw new ValidationException("The settlement line has no reference that proves target ownership.");
        }
    }

    private record Target(BigDecimal available, BigDecimal expected) { }
    private static String normalizeUtr(String s) { return UtrNormalizer.normalize(s); }
    private SettlementLineResponse lineResponse(SettlementLine line) {
        return allocationRepository.findBySettlementLineIdOrderByAllocatedAtDescIdDesc(line.getId()).stream()
                .findFirst().map(a -> SettlementLineResponse.from(line, a)).orElseGet(() -> SettlementLineResponse.from(line));
    }
    private SettlementCandidateResponse paymentCandidate(OrderPaymentRepository.PaymentMatch p, OrderEntity o) { String k="ORDER_PAYMENT:"+p.getPaymentId(); BigDecimal a=allocationRepository.sumByTargetKey(k); return new SettlementCandidateResponse(SettlementTargetType.ORDER_PAYMENT, p.getPaymentId(), o.getId(), o.getOrderCode(), o.getCustomerName(), p.getAmount(), a, p.getAmount().subtract(a).max(BigDecimal.ZERO), "UTR"); }
    private SettlementCandidateResponse orderCandidate(OrderEntity o) { String k="SHOPIFY_ORDER:"+o.getId(); BigDecimal a=allocationRepository.sumByTargetKey(k); return new SettlementCandidateResponse(SettlementTargetType.SHOPIFY_ORDER,o.getId(),o.getId(),o.getOrderCode(),o.getCustomerName(),o.getAmountReceived(),a,o.getAmountReceived().subtract(a).max(BigDecimal.ZERO),o.getShopifyOrderId()); }
    private SettlementCandidateResponse receivableCandidate(ReceivableEntity r, OrderEntity o) { String k="COD_RECEIVABLE:"+r.getId(); BigDecimal a=allocationRepository.sumByTargetKey(k); return new SettlementCandidateResponse(SettlementTargetType.COD_RECEIVABLE,r.getId(),o.getId(),o.getOrderCode(),o.getCustomerName(),r.getAmount(),a,r.getAmount().subtract(a).max(BigDecimal.ZERO),o.getOrderCode()); }

    private void recalculateBatch(SettlementBatch batch) {
        List<SettlementLine> lines = lineRepository.findByBatchIdOrderBySourceRowNumberAsc(batch.getId());
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal suspense = BigDecimal.ZERO;
        for (SettlementLine line : lines) {
            BigDecimal actual = allocationRepository.sumByLineId(line.getId()).setScale(2);
            line.setAllocatedAmount(actual);
            line.setSuspenseAmount(line.getAmount().subtract(actual).max(BigDecimal.ZERO).setScale(2));
            lineRepository.save(line);
            allocated = allocated.add(actual);
            suspense = suspense.add(line.getSuspenseAmount());
        }
        batch.setAllocatedAmount(allocated.setScale(2));
        batch.setSuspenseAmount(suspense.setScale(2));
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
        utr = normalizeUtr(utr);
        String amountRaw = value(row, columns, "amount");
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountRaw.replace(",", ""));
            if (amount.signum() <= 0 || amount.scale() > 2) throw new NumberFormatException();
            amount = amount.setScale(2);
        } catch (RuntimeException ex) {
            return PlannedLine.invalid(rowNumber, orderCode, shopifyId, awb, utr, BigDecimal.ZERO, "Amount is not valid.");
        }
        String dateRaw = value(row, columns, "date");
        LocalDate parsedDate = parseDate(dateRaw);
        if (dateRaw != null && parsedDate == null) {
            return PlannedLine.invalid(rowNumber, orderCode, shopifyId, awb, utr, amount,
                    "Transaction date is malformed; expected yyyy-MM-dd, d MMM yyyy, dd/MM/yyyy, or dd-MM-yyyy.");
        }
        boolean dateDefaulted = dateRaw == null;
        LocalDate date = parsedDate == null ? statementDate : parsedDate;
        String dateMessage = dateDefaulted ? " Transaction date was missing; defaulted to statement date " + statementDate + "." : "";
        if (source == SettlementSource.BANK_RECEIPT && utr == null) {
            return PlannedLine.invalid(rowNumber, orderCode, shopifyId, awb, utr, amount,
                    "Bank receipt lines require a UTR/transaction reference.");
        }
        List<Candidate> candidates = candidates(source, orderCode, shopifyId, awb, utr, bankAccountId);
        if (candidates.isEmpty()) {
            return PlannedLine.unmatched(rowNumber, orderCode, shopifyId, awb, utr, date, amount,
                    "No exact order/payment reference matched this line." + dateMessage).withDateDefaulted(dateDefaulted);
        }
        if (candidates.size() > 1) {
            return PlannedLine.ambiguous(rowNumber, orderCode, shopifyId, awb, utr, date, amount,
                    "More than one exact record matched this line." + dateMessage).withDateDefaulted(dateDefaulted);
        }
        Candidate candidate = candidates.get(0);
        String targetId = candidate.paymentId() != null ? "payment:" + candidate.paymentId()
                : candidate.receivableId() != null ? "receivable:" + candidate.receivableId()
                : "order:" + candidate.orderId();
        String targetKey = candidate.targetType() + ":" + targetId;
        if (!seenTargets.add(targetKey)) {
            return PlannedLine.duplicate(rowNumber, orderCode, shopifyId, awb, utr, date, amount,
                    "The same target appears more than once in this file." + dateMessage).withDateDefaulted(dateDefaulted);
        }
        BigDecimal expected = candidate.expectedAmount();
        BigDecimal variance = expected == null ? BigDecimal.ZERO : amount.subtract(expected).setScale(2);
        if (expected != null && variance.abs().compareTo(TOLERANCE) > 0) {
            return PlannedLine.mismatch(rowNumber, orderCode, shopifyId, awb, utr, date, amount, expected, variance,
                    candidate, "Amount differs from the matched record by more than ₹1." + dateMessage).withDateDefaulted(dateDefaulted);
        }
        return PlannedLine.matched(rowNumber, orderCode, shopifyId, awb, utr, date, amount, expected, variance,
                candidate, "Exact " + candidate.method() + " match." + dateMessage).withDateDefaulted(dateDefaulted);
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
            orderRepository.findByShopifyOrderId(shopifyId).ifPresent(o -> {
                Candidate c = candidateForOrder(source, o, SettlementMatchMethod.SHOPIFY_ORDER_ID);
                if (c != null) unique.put("order:" + o.getId(), c);
            });
        }
        if (orderCode != null && source != SettlementSource.BANK_RECEIPT) {
            orderRepository.findByOrderCode(orderCode).ifPresent(o -> {
                Candidate c = candidateForOrder(source, o, SettlementMatchMethod.ORDER_CODE);
                if (c != null) unique.put("order:" + o.getId(), c);
            });
        }
        if (awb != null && source != SettlementSource.BANK_RECEIPT) {
            courierRecordRepository.findByAwb(awb).ifPresent(r -> addCourierCandidate(unique, source, r.getOrderId(), SettlementMatchMethod.AWB));
            shipmentRepository.findByAwb(awb).ifPresent(s -> addCourierCandidate(unique, source, s.getOrderId(), SettlementMatchMethod.AWB));
        }
        return new ArrayList<>(unique.values());
    }

    private void addCourierCandidate(Map<String, Candidate> unique, SettlementSource source, Long orderId,
                                     SettlementMatchMethod method) {
        orderRepository.findById(orderId).ifPresent(o -> { Candidate c = candidateForOrder(source, o, method); if (c != null) unique.put("order:" + o.getId(), c); });
    }

    private Candidate candidateForOrder(SettlementSource source, OrderEntity order, SettlementMatchMethod method) {
        if (source == SettlementSource.QUICKSHIPX_COD) {
            List<ReceivableEntity> rows = receivableRepository.findByOrderIdAndType(order.getId(), ReceivableType.COD_RECEIVABLE)
                    .stream().filter(r -> !r.isSettled() && r.getAmount() != null && r.getAmount().signum() > 0).toList();
            if (rows.size() == 1) {
                ReceivableEntity r = rows.get(0);
                return new Candidate(SettlementTargetType.COD_RECEIVABLE, order.getId(), null, r.getId(),
                        r.getAmount(), method);
            }
            return null;
        }
        if (source == SettlementSource.SHOPIFY_PAYOUT) {
            if (order.getSource() != OrderSource.SHOPIFY || order.getShopifyOrderId() == null
                    || order.getAmountReceived() == null || order.getAmountReceived().signum() <= 0) return null;
            return new Candidate(SettlementTargetType.SHOPIFY_ORDER, order.getId(), null, null,
                    order.getAmountReceived(), method);
        }
        return null;
    }

    private SettlementImportResponse response(boolean dryRun, Long batchId, SettlementSource source,
                                              String reference, LocalDate date, List<PlannedLine> planned) {
        return response(dryRun, batchId, source, reference, date, planned, null);
    }

    private SettlementImportResponse response(boolean dryRun, Long batchId, SettlementSource source,
                                              String reference, LocalDate date, List<PlannedLine> planned,
                                              List<SettlementLine> persisted) {
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
        if (!dryRun && persisted != null) lines = persisted.stream().map(this::lineResponse).toList();
        return new SettlementImportResponse(dryRun, batchId, source, reference, date, planned.size(),
                total.setScale(2), allocated.setScale(2), suspense.setScale(2), counts, lines);
    }

    private record Candidate(SettlementTargetType targetType, Long orderId, Long paymentId,
                             Long receivableId, BigDecimal expectedAmount, SettlementMatchMethod method) { }

    private record PlannedLine(int rowNumber, String orderCode, String shopifyId, String awb, String utr,
                               LocalDate date, BigDecimal amount, BigDecimal expected, BigDecimal variance,
                               SettlementMatchStatus status, SettlementMatchMethod method, Candidate candidate,
                               String message, boolean dateDefaulted) {
        static PlannedLine invalid(int n, String oc, String so, String awb, String utr, BigDecimal amount, String msg) {
            return new PlannedLine(n, oc, so, awb, utr, null, amount, null, BigDecimal.ZERO, SettlementMatchStatus.INVALID, null, null, msg, false);
        }
        static PlannedLine unmatched(int n, String oc, String so, String awb, String utr, LocalDate d, BigDecimal a, String msg) {
            return new PlannedLine(n, oc, so, awb, utr, d, a, null, BigDecimal.ZERO, SettlementMatchStatus.UNMATCHED, null, null, msg, false);
        }
        static PlannedLine ambiguous(int n, String oc, String so, String awb, String utr, LocalDate d, BigDecimal a, String msg) {
            return new PlannedLine(n, oc, so, awb, utr, d, a, null, BigDecimal.ZERO, SettlementMatchStatus.AMBIGUOUS, null, null, msg, false);
        }
        static PlannedLine duplicate(int n, String oc, String so, String awb, String utr, LocalDate d, BigDecimal a, String msg) {
            return new PlannedLine(n, oc, so, awb, utr, d, a, null, BigDecimal.ZERO, SettlementMatchStatus.DUPLICATE_IN_FILE, null, null, msg, false);
        }
        static PlannedLine mismatch(int n, String oc, String so, String awb, String utr, LocalDate d, BigDecimal a, BigDecimal e, BigDecimal v, Candidate c, String msg) {
            return new PlannedLine(n, oc, so, awb, utr, d, a, e, v, SettlementMatchStatus.MISMATCH, c.method(), c, msg, false);
        }
        static PlannedLine matched(int n, String oc, String so, String awb, String utr, LocalDate d, BigDecimal a, BigDecimal e, BigDecimal v, Candidate c, String msg) {
            return new PlannedLine(n, oc, so, awb, utr, d, a, e, v, SettlementMatchStatus.MATCHED, c.method(), c, msg, false);
        }
        PlannedLine withDateDefaulted(boolean value) {
            return new PlannedLine(rowNumber, orderCode, shopifyId, awb, utr, date, amount, expected, variance,
                    status, method, candidate, message, value);
        }
        SettlementLine toEntity(SettlementBatch batch) {
            SettlementLine line = new SettlementLine(batch, rowNumber);
            line.setOrderCode(orderCode); line.setShopifyOrderId(shopifyId); line.setAwb(awb); line.setUtr(utr);
            line.setTransactionDate(date); line.setDateDefaulted(dateDefaulted); line.setAmount(amount); line.setExpectedAmount(expected); line.setVariance(variance);
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
                    candidate == null ? null : candidate.receivableId(), message, dateDefaulted, null);
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
