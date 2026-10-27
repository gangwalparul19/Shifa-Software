package com.shifa.oms.reconciliation;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.SpreadsheetParser;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.reconciliation.dto.RemittanceImportResponse;
import com.shifa.oms.reconciliation.dto.RemittanceRowResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Courier COD remittance import &amp; auto-match (enhancement: "Courier
 * remittance import &amp; auto-match", the accountant's heaviest manual
 * reconciliation chore).
 *
 * <p>The courier (QuikShipX) pays COD collections out in bulk and hands over a
 * remittance sheet listing every parcel they settled in that batch. The
 * accountant uploads that sheet here; each row is resolved to one of our orders
 * and, when it cleanly matches, the order is marked settled so at any moment it
 * is obvious which COD orders have actually been paid out by the courier and
 * which are still outstanding.
 *
 * <p>Accepts either a plain CSV or a real Excel workbook ({@code .xlsx}/
 * {@code .xls}), and understands two header vocabularies: the importer's own
 * generic {@code awb,orderCode,amount}, and QuikShipX's real remittance-sheet
 * columns ({@code Tracking ID}, {@code Client Order ID}, {@code COD Amount},
 * {@code Remitted Amount}, {@code Remitted Date}).
 *
 * <p>Each data row is processed in its OWN transaction by
 * {@link RemittanceRowProcessor} ({@link org.springframework.transaction.annotation.Propagation#REQUIRES_NEW}),
 * so one bad row (e.g. an illegal state transition) rolls back only that row and
 * is reported as an error — the rest of the batch still commits. {@code dryRun}
 * (default {@code true}) previews every row's outcome without persisting.
 */
@Service
public class RemittanceImportService {

    private static final Logger log = LoggerFactory.getLogger(RemittanceImportService.class);

    /** Canonical column → the header aliases (case/space-insensitive) that mean it. */
    private static final Map<String, List<String>> COLUMN_ALIASES = Map.ofEntries(
            Map.entry("awb", List.of("awb", "trackingid")),
            Map.entry("ordercode", List.of("ordercode")),
            Map.entry("clientorderid", List.of("clientorderid")),
            Map.entry("customername", List.of("customername")),
            Map.entry("amount", List.of("amount", "remittedamount")),
            Map.entry("codamount", List.of("codamount")),
            Map.entry("remitteddate", List.of("remitteddate")));

    /** Shared with {@link RemittanceRowProcessor#parseDate}. */
    static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"));

    /** Pulls a batch reference out of a filename like "Remittance Batch ID - 488 Downloaded On ...". */
    private static final Pattern BATCH_REFERENCE = Pattern.compile(
            "batch\\s*id\\s*[-:#]?\\s*([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    private final RemittanceRowProcessor rowProcessor;
    private final AuditService auditService;
    private final RemittanceBatchRepository batchRepository;
    private final RemittanceBatchLineRepository batchLineRepository;

    public RemittanceImportService(RemittanceRowProcessor rowProcessor, AuditService auditService,
                                   RemittanceBatchRepository batchRepository,
                                   RemittanceBatchLineRepository batchLineRepository) {
        this.rowProcessor = rowProcessor;
        this.auditService = auditService;
        this.batchRepository = batchRepository;
        this.batchLineRepository = batchLineRepository;
    }

    /**
     * Imports (or previews) a courier COD remittance file.
     *
     * @param fileBytes the raw uploaded file bytes
     * @param filename  the uploaded filename (drives CSV vs Excel parsing; may be null)
     * @param dryRun    when true, match + report only (nothing settled/transitioned)
     * @return the aggregate result with per-row outcomes
     */
    public RemittanceImportResponse importFile(byte[] fileBytes, String filename, boolean dryRun) {
        return importFile(fileBytes, filename, dryRun, null);
    }

    /**
     * Imports (or previews) a courier COD remittance file, recording the committed
     * import as a persisted {@link RemittanceBatch} (so the weekly settlement report
     * can list it) when {@code dryRun} is false.
     *
     * @param createdBy the acting user id to stamp on a committed batch, or {@code null}
     */
    public RemittanceImportResponse importFile(byte[] fileBytes, String filename, boolean dryRun, Long createdBy) {
        List<List<String>> rows;
        try {
            rows = SpreadsheetParser.parse(fileBytes, filename);
        } catch (IOException e) {
            throw new ValidationException("Could not read the uploaded file: " + e.getMessage());
        }
        if (rows.isEmpty()) {
            throw new ValidationException("The uploaded file is empty.");
        }

        Map<String, Integer> columns = headerIndex(rows.get(0));
        boolean hasAmount = columns.containsKey("amount") || columns.containsKey("codamount");
        boolean hasKey = columns.containsKey("awb") || columns.containsKey("ordercode")
                || columns.containsKey("clientorderid");
        if (!hasAmount || !hasKey) {
            throw new ValidationException(
                    "The file header must include an amount column (amount / Remitted Amount / COD Amount) "
                            + "and at least one of awb / Tracking ID / orderCode / Client Order ID.");
        }

        List<RemittanceRowResult> results = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            RemittanceRowResult result;
            try {
                // Each row is its own REQUIRES_NEW transaction: a failure here
                // rolls back only this row, not the whole batch.
                result = rowProcessor.processRow(i, rows.get(i), columns, dryRun);
            } catch (RuntimeException ex) {
                log.warn("Remittance row {} failed and was skipped: {}", i, ex.getMessage());
                result = RemittanceRowResult.of(i, null, null, null, null, null,
                        RemittanceRowResult.Status.ERROR,
                        "Row could not be processed: " + ex.getMessage());
            }
            results.add(result);
        }

        Long batchId = null;
        if (!dryRun) {
            batchId = persistBatch(filename, createdBy, results);
        }
        RemittanceImportResponse response = RemittanceImportResponse.of(dryRun, batchId, results);
        if (!dryRun) {
            auditService.record(AuditActions.COD_REMITTANCE_IMPORTED, AuditActions.ENTITY_RECEIVABLE,
                    batchId == null ? null : String.valueOf(batchId),
                    "COD remittance import: " + response.settled() + " settled, " + response.partiallySettled()
                            + " partial, " + response.mismatched() + " mismatched, " + response.alreadySettled()
                            + " already settled, " + response.notFound() + " not found, " + response.noReceivable()
                            + " with no receivable, " + response.errors() + " errors ("
                            + response.totalRows() + " rows); ₹" + response.totalSettled() + " settled, ₹"
                            + response.totalShortfall() + " shortfall.");
        }
        return response;
    }

    /** Backward-compatible CSV-only entry point (kept for existing callers/tests). */
    public RemittanceImportResponse importCsv(byte[] csvBytes, boolean dryRun) {
        return importFile(csvBytes, "remittance.csv", dryRun);
    }

    /**
     * Persists a committed import as a {@link RemittanceBatch} + one
     * {@link RemittanceBatchLine} per row, so the weekly settlement report can
     * query what was settled. The per-row COD settlements have already been
     * committed in their own {@code REQUIRES_NEW} transactions by the processor;
     * this write is the import's audit/report record. A failure here must not undo
     * those settlements, so it is caught and logged (the import still succeeds).
     */
    @Transactional
    protected Long persistBatch(String filename, Long createdBy, List<RemittanceRowResult> results) {
        try {
            RemittanceBatch batch = new RemittanceBatch(
                    clean(filename, 255), batchReference(filename), predominantRemittedDate(results), createdBy);
            RemittanceImportResponse counts = RemittanceImportResponse.of(false, null, results);
            batch.setTotalRows(counts.totalRows());
            batch.setSettledCount(counts.settled());
            batch.setPartialCount(counts.partiallySettled());
            batch.setMismatchCount(counts.mismatched());
            batch.setAlreadySettledCount(counts.alreadySettled());
            batch.setNotFoundCount(counts.notFound());
            batch.setNoReceivableCount(counts.noReceivable());
            batch.setErrorCount(counts.errors());
            batch.setTotalRemitted(counts.totalRemitted());
            batch.setTotalSettled(counts.totalSettled());
            batch.setTotalShortfall(counts.totalShortfall());
            batchRepository.save(batch);

            for (RemittanceRowResult r : results) {
                RemittanceBatchLine line = new RemittanceBatchLine(batch, r.rowNumber(), r.status());
                line.setAwb(clean(r.awb(), 80));
                line.setClientOrderId(clean(r.clientOrderId(), 120));
                line.setCustomerName(clean(r.customerName(), 160));
                line.setResolvedOrderId(r.resolvedOrderId());
                line.setResolvedOrderCode(clean(r.resolvedOrderCode(), 40));
                line.setExpectedAmount(r.expectedAmount());
                line.setRemittedAmount(r.remittedAmount());
                line.setSettledAmount(nz(r.settledAmount()));
                line.setShortfallAmount(nz(r.shortfallAmount()));
                line.setMessage(clean(r.message(), 500));
                line.setRemittedDate(r.remittedDate());
                batchLineRepository.save(line);
            }
            return batch.getId();
        } catch (RuntimeException ex) {
            // The per-row settlements already committed; failing to write the report
            // record must not fail the whole import. Log and continue.
            log.warn("Failed to persist remittance batch record (settlements still applied): {}", ex.getMessage());
            return null;
        }
    }

    /** The predominant remitted date across the rows (used to bucket the week); null when none present. */
    private static LocalDate predominantRemittedDate(List<RemittanceRowResult> results) {
        Map<LocalDate, Integer> counts = new LinkedHashMap<>();
        for (RemittanceRowResult r : results) {
            if (r.remittedDate() != null) {
                counts.merge(r.remittedDate(), 1, Integer::sum);
            }
        }
        LocalDate best = null;
        int bestCount = -1;
        for (Map.Entry<LocalDate, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static String batchReference(String filename) {
        if (filename == null) {
            return null;
        }
        Matcher m = BATCH_REFERENCE.matcher(filename);
        return m.find() ? m.group(1) : null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String clean(String v, int max) {
        if (v == null) {
            return null;
        }
        String trimmed = v.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> raw = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = normalize(header.get(i));
            if (!key.isEmpty()) {
                raw.putIfAbsent(key, i);
            }
        }
        Map<String, Integer> canonical = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : COLUMN_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                Integer idx = raw.get(alias);
                if (idx != null) {
                    canonical.put(entry.getKey(), idx);
                    break;
                }
            }
        }
        return canonical;
    }

    private static String normalize(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
