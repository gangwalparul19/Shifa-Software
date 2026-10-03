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

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private static final Map<String, List<String>> COLUMN_ALIASES = Map.of(
            "awb", List.of("awb", "trackingid"),
            "ordercode", List.of("ordercode"),
            "clientorderid", List.of("clientorderid"),
            "amount", List.of("amount", "remittedamount"),
            "codamount", List.of("codamount"),
            "remitteddate", List.of("remitteddate"));

    /** Shared with {@link RemittanceRowProcessor#parseDate}. */
    static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"));

    private final RemittanceRowProcessor rowProcessor;
    private final AuditService auditService;

    public RemittanceImportService(RemittanceRowProcessor rowProcessor, AuditService auditService) {
        this.rowProcessor = rowProcessor;
        this.auditService = auditService;
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
        int settledCount = 0;
        for (int i = 1; i < rows.size(); i++) {
            RemittanceRowResult result;
            try {
                // Each row is its own REQUIRES_NEW transaction: a failure here
                // rolls back only this row, not the whole batch.
                result = rowProcessor.processRow(i, rows.get(i), columns, dryRun);
            } catch (RuntimeException ex) {
                log.warn("Remittance row {} failed and was skipped: {}", i, ex.getMessage());
                result = new RemittanceRowResult(i, null, null, null, null, null,
                        RemittanceRowResult.Status.ERROR,
                        "Row could not be processed: " + ex.getMessage());
            }
            results.add(result);
            if (result.status() == RemittanceRowResult.Status.SETTLED) {
                settledCount++;
            }
        }

        RemittanceImportResponse response = RemittanceImportResponse.of(dryRun, results);
        if (!dryRun) {
            auditService.record(AuditActions.COD_REMITTANCE_IMPORTED, AuditActions.ENTITY_RECEIVABLE, null,
                    "COD remittance import: " + settledCount + " settled, " + response.mismatched()
                            + " mismatched, " + response.alreadySettled() + " already settled, "
                            + response.notFound() + " not found, " + response.noReceivable()
                            + " with no receivable, " + response.errors() + " errors ("
                            + response.totalRows() + " rows).");
        }
        return response;
    }

    /** Backward-compatible CSV-only entry point (kept for existing callers/tests). */
    public RemittanceImportResponse importCsv(byte[] csvBytes, boolean dryRun) {
        return importFile(csvBytes, "remittance.csv", dryRun);
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
