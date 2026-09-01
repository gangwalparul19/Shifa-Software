package com.shifa.oms.gst.filing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.gst.Gstr1ReturnService;
import com.shifa.oms.gst.domain.B2bRow;
import com.shifa.oms.gst.domain.B2clRow;
import com.shifa.oms.gst.domain.B2csRow;
import com.shifa.oms.gst.domain.CdnrRow;
import com.shifa.oms.gst.domain.CdnurRow;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.domain.HsnRow;
import com.shifa.oms.gst.filing.domain.AmendmentAttribution;
import com.shifa.oms.gst.filing.domain.AmendmentConsolidator;
import com.shifa.oms.gst.filing.domain.AmendmentCorrection;
import com.shifa.oms.gst.filing.domain.AmendmentRouter;
import com.shifa.oms.gst.filing.domain.AmendmentStatus;
import com.shifa.oms.gst.filing.domain.AmendmentTable;
import com.shifa.oms.gst.filing.domain.CorrectionDetector;
import com.shifa.oms.gst.filing.domain.CorrectionDetector.SectionFigures;
import com.shifa.oms.gst.filing.domain.FilingStatus;
import com.shifa.oms.gst.filing.domain.Gstr1Section;
import com.shifa.oms.gst.filing.domain.ReturnPeriod;
import com.shifa.oms.gst.filing.domain.ReturnType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Detects, routes, attributes, and consolidates <strong>post-filing GSTR-1 corrections</strong> into
 * {@code return_amendments} (GST returns &amp; filing, Reqs 3.1–3.8, 10.2).
 *
 * <p>The correctness-critical decisions all live in the pure {@code gst.filing.domain} core; this
 * service is the thin transactional shell that loads data, delegates to the domain, and persists:
 * <ul>
 *   <li><strong>Detect</strong> — when a document change falls in a period whose GSTR-1 is
 *       {@link FilingStatus#FILED}, recompute that period's sections via {@link Gstr1ReturnService}
 *       and diff them against the immutable current GSTR-1 snapshot with {@link CorrectionDetector}
 *       (a section counts as changed at a ₹0.01 delta — Req 3.1). A change in a not-yet-filed period
 *       needs no amendment: it flows into the still-open return normally, so detection is skipped.</li>
 *   <li><strong>Route</strong> — each changed section is routed via {@link AmendmentRouter}; a
 *       supported outward section (B2B/B2CS/CDNR) yields an {@link AmendmentTable}, while an
 *       unsupported section returns {@link Optional#empty()} and the correction is
 *       <em>never discarded</em> — it is held for manual CA review (Req 3.7).</li>
 *   <li><strong>Attribute</strong> — {@link AmendmentAttribution} finds the earliest open
 *       (non-FILED) period on or after the change to land the amendment in; when none is open yet the
 *       correction is held {@link AmendmentStatus#PENDING} and (re)attributed later, once a period
 *       opens, without altering any Filed_Period (Reqs 3.3, 3.6).</li>
 *   <li><strong>Consolidate</strong> — {@link AmendmentConsolidator} keeps exactly one amendment per
 *       {@code (targetPeriod, originalDocument)}, retaining the value originally filed and adopting
 *       the latest recomputed value (Reqs 3.4, 3.8). Persisted upserts are keyed by
 *       {@code (originalPeriod, originalDocumentRef)} so repeated deliveries are idempotent.</li>
 * </ul>
 *
 * <p><strong>Single-row-per-document interpretation.</strong> A single order change typically alters
 * both its primary outward section (B2B/B2CS/B2CL) and the Table-12 HSN / Table-13 document
 * summaries. To keep one auditable amendment per corrected document (and satisfy the unique
 * {@code (target, docRef)} key), the routing decision is taken across all changed sections
 * <em>routable-first</em>: if any changed section has a supported amendment table the amendment is
 * routed/held-pending there; only when <em>every</em> changed section is unsupported is the whole
 * correction flagged {@link AmendmentStatus#MANUAL_REVIEW}. Either way the correction is recorded and
 * never lost (Req 3.7).
 *
 * <p><strong>Decoupling.</strong> Detection is invoked out-of-band by {@link AmendmentDetectionListener}
 * (an {@code OutboxEventSink}); a detection failure is isolated and never rolls back the source
 * order/return/refund mutation. Amendment {@link #create}/{@link #edit}/{@link #review} are
 * method-gated to ADMIN/CA (Req 3.5).
 */
@Service
@Transactional
public class AmendmentService {

    private static final Logger log = LoggerFactory.getLogger(AmendmentService.class);
    private static final int SCALE = 2;
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(SCALE, ROUND);
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final ReturnFilingRepository returnFilingRepository;
    private final ReturnAmendmentRepository amendmentRepository;
    private final FilingSnapshotService filingSnapshotService;
    private final Gstr1ReturnService gstr1ReturnService;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public AmendmentService(ReturnFilingRepository returnFilingRepository,
                            ReturnAmendmentRepository amendmentRepository,
                            FilingSnapshotService filingSnapshotService,
                            Gstr1ReturnService gstr1ReturnService,
                            AuditService auditService,
                            CurrentUserService currentUserService,
                            ObjectMapper objectMapper) {
        this.returnFilingRepository = returnFilingRepository;
        this.amendmentRepository = amendmentRepository;
        this.filingSnapshotService = filingSnapshotService;
        this.gstr1ReturnService = gstr1ReturnService;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
    }

    /**
     * Detects and records any post-filing GSTR-1 correction for a changed document (Reqs 3.1–3.8).
     *
     * <p>No-op unless the document's calendar-month period has a FILED GSTR-1 (a change in an open
     * period needs no amendment). When the recomputed sections differ from the current snapshot by
     * ₹0.01 or more, the correction is routed, attributed to the next open period, and consolidated
     * into a single {@code return_amendments} row for {@code (originalPeriod, docRef)}.
     *
     * @param docDate the changed document's date (its calendar month is the Filed_Period corrected)
     * @param docRef  the changed document's reference (order code / note number), non-blank
     * @return the persisted amendment, or {@link Optional#empty()} when there is nothing to amend
     */
    public Optional<ReturnAmendment> onDocumentChanged(LocalDate docDate, String docRef) {
        if (docDate == null || docRef == null || docRef.isBlank()) {
            return Optional.empty();
        }
        ReturnPeriod period = new ReturnPeriod(docDate.getMonthValue(), docDate.getYear());

        // Only a FILED (locked) period needs an amendment; an open period absorbs the change directly.
        if (gstr1StatusOf(period) != FilingStatus.FILED) {
            return Optional.empty();
        }

        Optional<Gstr1Return> snapshot = filingSnapshotService.currentGstr1Return(period);
        if (snapshot.isEmpty()) {
            // Defensive: FILED but no snapshot (partially-migrated data) — nothing to diff against.
            return Optional.empty();
        }

        YearMonth ym = period.yearMonth();
        Gstr1Return recomputed = gstr1ReturnService.build(ym.atDay(1), ym.atEndOfMonth());

        Map<Gstr1Section, SectionFigures> snapshotFigures = sectionFigures(snapshot.get());
        Map<Gstr1Section, SectionFigures> recomputedFigures = sectionFigures(recomputed);

        Set<Gstr1Section> changed = CorrectionDetector.detect(snapshotFigures, recomputedFigures);
        if (changed.isEmpty()) {
            return Optional.empty();
        }

        // Route (routable-first) — Optional.empty() across all changed sections ⇒ manual review (Req 3.7).
        Optional<AmendmentTable> table = firstRoutable(changed);

        // Attribute the earliest open period on/after the change (Reqs 3.3, 3.6).
        Optional<ReturnPeriod> target = AmendmentAttribution.targetPeriod(period, gstr1StatusByPeriod());

        AmendmentValue original = aggregate(snapshotFigures, changed);
        AmendmentValue corrected = aggregate(recomputedFigures, changed);

        ReturnAmendment saved = upsert(period, docRef, table, target, changed, original, corrected);

        auditService.record(AuditActions.GST_AMENDMENT_DETECTED, AuditActions.ENTITY_GST,
                String.valueOf(saved.getId()),
                "Correction detected for " + docRef + " (filed " + period.month() + "/" + period.year()
                        + "), sections " + changed + " → " + saved.getStatus()
                        + (saved.getAmendmentTable() != null ? " " + saved.getAmendmentTable() : "") + ".");
        return Optional.of(saved);
    }

    /**
     * Opportunistically (re)attributes every {@link AmendmentStatus#PENDING} amendment to an open
     * target period now that one may have appeared (Req 3.6). Re-runs detection for each pending
     * document's Filed_Period, so a pending correction is promoted to {@link AmendmentStatus#ROUTED}
     * once the next return opens — without touching the locked Filed_Period.
     *
     * @return the number of amendments that changed status/target as a result
     */
    public int reattributePending() {
        List<ReturnAmendment> pending = amendmentRepository.findByStatus(AmendmentStatus.PENDING);
        int promoted = 0;
        for (ReturnAmendment a : pending) {
            LocalDate docDate = LocalDate.of(a.getOriginalPeriodYear(), a.getOriginalPeriodMonth(), 1);
            Optional<ReturnAmendment> result = onDocumentChanged(docDate, a.getOriginalDocumentRef());
            if (result.isPresent() && result.get().getStatus() != AmendmentStatus.PENDING) {
                promoted++;
            }
        }
        return promoted;
    }

    // --- CA-managed amendment operations (ADMIN/CA only — Req 3.5) -----------

    /**
     * Manually creates an amendment (ADMIN/CA only — Req 3.5). Used when a CA records a correction the
     * automatic detector did not raise.
     */
    @PreAuthorize("hasAnyRole('ADMIN','CA')")
    public ReturnAmendment create(int originalYear, int originalMonth, String docRef,
                                  AmendmentTable table, Integer targetYear, Integer targetMonth,
                                  String originalValueJson, String correctedValueJson) {
        if (docRef == null || docRef.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_REF",
                    "The original document reference is required.");
        }
        ReturnAmendment entity = new ReturnAmendment(originalYear, originalMonth, docRef.trim());
        entity.setAmendmentTable(table);
        entity.setTargetPeriodYear(targetYear);
        entity.setTargetPeriodMonth(targetMonth);
        entity.setOriginalValueJson(originalValueJson);
        entity.setCorrectedValueJson(correctedValueJson);
        entity.setStatus(resolveStatus(table, targetYear, targetMonth));
        ReturnAmendment saved = amendmentRepository.save(entity);
        auditReviewed(saved, "created");
        return saved;
    }

    /**
     * Edits an existing amendment's routing/values (ADMIN/CA only — Req 3.5).
     */
    @PreAuthorize("hasAnyRole('ADMIN','CA')")
    public ReturnAmendment edit(Long id, AmendmentTable table, Integer targetYear, Integer targetMonth,
                                String originalValueJson, String correctedValueJson,
                                AmendmentStatus status) {
        ReturnAmendment entity = requireAmendment(id);
        entity.setAmendmentTable(table);
        entity.setTargetPeriodYear(targetYear);
        entity.setTargetPeriodMonth(targetMonth);
        if (originalValueJson != null) {
            entity.setOriginalValueJson(originalValueJson);
        }
        if (correctedValueJson != null) {
            entity.setCorrectedValueJson(correctedValueJson);
        }
        entity.setStatus(status != null ? status : resolveStatus(table, targetYear, targetMonth));
        ReturnAmendment saved = amendmentRepository.save(entity);
        auditReviewed(saved, "edited");
        return saved;
    }

    /**
     * Resolves a {@link AmendmentStatus#MANUAL_REVIEW} amendment by assigning the CA-chosen amendment
     * table and target period, moving it to {@link AmendmentStatus#ROUTED} (ADMIN/CA only — Reqs 3.5,
     * 3.7).
     *
     * @param id            the amendment to resolve
     * @param resolvedTable the amendment table the CA selects (must not be {@code null})
     * @param targetYear    the target period year the amendment lands in (must not be {@code null})
     * @param targetMonth   the target period month (1–12, must not be {@code null})
     * @return the resolved, now-ROUTED amendment
     */
    @PreAuthorize("hasAnyRole('ADMIN','CA')")
    public ReturnAmendment review(Long id, AmendmentTable resolvedTable, Integer targetYear,
                                  Integer targetMonth) {
        if (resolvedTable == null || targetYear == null || targetMonth == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMENDMENT_REVIEW",
                    "An amendment table and a target period are required to resolve a review.");
        }
        ReturnAmendment entity = requireAmendment(id);
        entity.setAmendmentTable(resolvedTable);
        entity.setTargetPeriodYear(targetYear);
        entity.setTargetPeriodMonth(targetMonth);
        entity.setStatus(AmendmentStatus.ROUTED);
        ReturnAmendment saved = amendmentRepository.save(entity);
        auditReviewed(saved, "resolved");
        return saved;
    }

    /** Amendments in a given routing status (read side for the controller). */
    @Transactional(readOnly = true)
    public List<ReturnAmendment> byStatus(AmendmentStatus status) {
        return amendmentRepository.findByStatus(status);
    }

    // --- Internals ----------------------------------------------------------

    /**
     * Upserts a single amendment for {@code (originalPeriod, docRef)}, retaining the value originally
     * filed and adopting the latest recomputed value via {@link AmendmentConsolidator} (Reqs 3.4,
     * 3.8). One row per corrected document keeps re-deliveries idempotent and satisfies the unique
     * {@code (target, docRef)} key.
     */
    private ReturnAmendment upsert(ReturnPeriod originalPeriod, String docRef,
                                   Optional<AmendmentTable> table, Optional<ReturnPeriod> target,
                                   Set<Gstr1Section> changed, AmendmentValue original,
                                   AmendmentValue corrected) {
        ReturnAmendment existing = findForDocument(originalPeriod, docRef);
        ReturnAmendment entity = existing != null
                ? existing
                : new ReturnAmendment(originalPeriod.year(), originalPeriod.month(), docRef);

        // Retain the originally filed value on the first detection; keep it on later re-detections.
        if (existing == null || existing.getOriginalValueJson() == null) {
            entity.setOriginalValueJson(toJson(original));
        }

        if (table.isPresent() && target.isPresent()) {
            // ROUTED: consolidate via the pure domain (retain original, adopt latest corrected).
            ReturnPeriod tgt = target.get();
            AmendmentCorrection existingCorr = toCorrection(entity, tgt, originalPeriod, docRef);
            AmendmentCorrection incoming = new AmendmentCorrection(
                    tgt, originalPeriod, docRef, original.taxAmount(), corrected.taxAmount());
            AmendmentCorrection merged = AmendmentConsolidator.merge(existingCorr, incoming);

            entity.setAmendmentTable(table.get());
            entity.setTargetPeriodYear(tgt.year());
            entity.setTargetPeriodMonth(tgt.month());
            entity.setStatus(AmendmentStatus.ROUTED);
            entity.setCorrectedValueJson(toJson(new AmendmentValue(
                    corrected.taxableValue(), merged.correctedValue())));
        } else if (table.isPresent()) {
            // Routable, but no open target yet — hold PENDING and attribute later (Req 3.6).
            entity.setAmendmentTable(null);
            entity.setTargetPeriodYear(null);
            entity.setTargetPeriodMonth(null);
            entity.setStatus(AmendmentStatus.PENDING);
            entity.setCorrectedValueJson(toJson(corrected));
        } else {
            // No supported amendment table for any changed section — never discard (Req 3.7).
            entity.setAmendmentTable(null);
            entity.setTargetPeriodYear(null);
            entity.setTargetPeriodMonth(null);
            entity.setStatus(AmendmentStatus.MANUAL_REVIEW);
            entity.setCorrectedValueJson(toJson(corrected));
        }
        return amendmentRepository.save(entity);
    }

    /** The existing amendment for a corrected document in its Filed_Period, if any. */
    private ReturnAmendment findForDocument(ReturnPeriod originalPeriod, String docRef) {
        return amendmentRepository
                .findByOriginalPeriodYearAndOriginalPeriodMonth(originalPeriod.year(), originalPeriod.month())
                .stream()
                .filter(a -> docRef.equals(a.getOriginalDocumentRef()))
                .findFirst()
                .orElse(null);
    }

    /** Reconstructs the pure correction for an existing row so the consolidator can retain its original value. */
    private AmendmentCorrection toCorrection(ReturnAmendment entity, ReturnPeriod target,
                                             ReturnPeriod originalPeriod, String docRef) {
        if (entity.getId() == null || entity.getOriginalValueJson() == null) {
            return null;
        }
        AmendmentValue original = fromJson(entity.getOriginalValueJson());
        AmendmentValue corrected = entity.getCorrectedValueJson() != null
                ? fromJson(entity.getCorrectedValueJson())
                : original;
        return new AmendmentCorrection(target, originalPeriod, docRef,
                original.taxAmount(), corrected.taxAmount());
    }

    /** The current GSTR-1 filing status of a period ({@code NOT_STARTED} when no row exists — Req 1.2). */
    private FilingStatus gstr1StatusOf(ReturnPeriod period) {
        return returnFilingRepository
                .findByPeriodYearAndPeriodMonthAndReturnType(period.year(), period.month(), ReturnType.GSTR1)
                .map(ReturnFiling::getStatus)
                .orElse(FilingStatus.NOT_STARTED);
    }

    /** All known GSTR-1 filing statuses keyed by period, for attribution (Reqs 3.3, 3.6). */
    private Map<ReturnPeriod, FilingStatus> gstr1StatusByPeriod() {
        Map<ReturnPeriod, FilingStatus> byPeriod = new java.util.HashMap<>();
        for (ReturnFiling filing : returnFilingRepository.findAll()) {
            if (filing.getReturnType() != ReturnType.GSTR1) {
                continue;
            }
            byPeriod.put(new ReturnPeriod(filing.getPeriodMonth(), filing.getPeriodYear()),
                    filing.getStatus());
        }
        return byPeriod;
    }

    /** The amendment table of the first changed section that has one, or empty when all are unsupported. */
    private static Optional<AmendmentTable> firstRoutable(Set<Gstr1Section> changed) {
        for (Gstr1Section section : changed) {
            Optional<AmendmentTable> table = AmendmentRouter.route(section);
            if (table.isPresent()) {
                return table;
            }
        }
        return Optional.empty();
    }

    private static AmendmentStatus resolveStatus(AmendmentTable table, Integer targetYear,
                                                 Integer targetMonth) {
        if (table != null && targetYear != null && targetMonth != null) {
            return AmendmentStatus.ROUTED;
        }
        if (table != null) {
            return AmendmentStatus.PENDING;
        }
        return AmendmentStatus.MANUAL_REVIEW;
    }

    /** Aggregates the changed sections' figures (taxable + tax) into a single value for the amendment. */
    private static AmendmentValue aggregate(Map<Gstr1Section, SectionFigures> figures,
                                            Set<Gstr1Section> changed) {
        BigDecimal taxable = ZERO_MONEY;
        BigDecimal tax = ZERO_MONEY;
        for (Gstr1Section section : changed) {
            SectionFigures f = figures.getOrDefault(section, SectionFigures.ZERO);
            taxable = taxable.add(f.taxableValue());
            tax = tax.add(f.taxAmount());
        }
        return new AmendmentValue(taxable.setScale(SCALE, ROUND), tax.setScale(SCALE, ROUND));
    }

    /**
     * Per-section taxable value + total tax (CGST + SGST + IGST) for a GSTR-1 return, for the
     * {@link CorrectionDetector}. The Table-13 DOCS section carries no tax figures and is omitted (a
     * section absent on both sides is treated as zero, so it never registers as a change).
     */
    private static Map<Gstr1Section, SectionFigures> sectionFigures(Gstr1Return ret) {
        Map<Gstr1Section, SectionFigures> figures = new EnumMap<>(Gstr1Section.class);

        BigDecimal b2bTaxable = ZERO_MONEY;
        BigDecimal b2bTax = ZERO_MONEY;
        if (ret.b2b() != null) {
            for (B2bRow r : ret.b2b()) {
                b2bTaxable = b2bTaxable.add(nz(r.taxable()));
                b2bTax = b2bTax.add(sum(r.cgst(), r.sgst(), r.igst()));
            }
        }
        figures.put(Gstr1Section.B2B, SectionFigures.of(b2bTaxable, b2bTax));

        BigDecimal b2clTaxable = ZERO_MONEY;
        BigDecimal b2clTax = ZERO_MONEY;
        if (ret.b2cl() != null) {
            for (B2clRow r : ret.b2cl()) {
                b2clTaxable = b2clTaxable.add(nz(r.taxable()));
                b2clTax = b2clTax.add(nz(r.igst()));
            }
        }
        figures.put(Gstr1Section.B2CL, SectionFigures.of(b2clTaxable, b2clTax));

        BigDecimal b2csTaxable = ZERO_MONEY;
        BigDecimal b2csTax = ZERO_MONEY;
        if (ret.b2cs() != null) {
            for (B2csRow r : ret.b2cs()) {
                b2csTaxable = b2csTaxable.add(nz(r.taxable()));
                b2csTax = b2csTax.add(sum(r.cgst(), r.sgst(), r.igst()));
            }
        }
        figures.put(Gstr1Section.B2CS, SectionFigures.of(b2csTaxable, b2csTax));

        BigDecimal cdnrTaxable = ZERO_MONEY;
        BigDecimal cdnrTax = ZERO_MONEY;
        if (ret.cdnr() != null) {
            for (CdnrRow r : ret.cdnr()) {
                cdnrTaxable = cdnrTaxable.add(nz(r.taxable()));
                cdnrTax = cdnrTax.add(sum(r.cgst(), r.sgst(), r.igst()));
            }
        }
        figures.put(Gstr1Section.CDNR, SectionFigures.of(cdnrTaxable, cdnrTax));

        BigDecimal cdnurTaxable = ZERO_MONEY;
        BigDecimal cdnurTax = ZERO_MONEY;
        if (ret.cdnur() != null) {
            for (CdnurRow r : ret.cdnur()) {
                cdnurTaxable = cdnurTaxable.add(nz(r.taxable()));
                cdnurTax = cdnurTax.add(sum(r.cgst(), r.sgst(), r.igst()));
            }
        }
        figures.put(Gstr1Section.CDNUR, SectionFigures.of(cdnurTaxable, cdnurTax));

        BigDecimal hsnTaxable = ZERO_MONEY;
        BigDecimal hsnTax = ZERO_MONEY;
        if (ret.hsn() != null) {
            for (HsnRow r : ret.hsn()) {
                hsnTaxable = hsnTaxable.add(nz(r.taxable()));
                hsnTax = hsnTax.add(sum(r.cgst(), r.sgst(), r.igst()));
            }
        }
        figures.put(Gstr1Section.HSN, SectionFigures.of(hsnTaxable, hsnTax));

        return figures;
    }

    private ReturnAmendment requireAmendment(Long id) {
        return amendmentRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AMENDMENT_NOT_FOUND",
                        "The requested amendment does not exist."));
    }

    private void auditReviewed(ReturnAmendment amendment, String verb) {
        auditService.record(AuditActions.GST_AMENDMENT_REVIEWED, AuditActions.ENTITY_GST,
                String.valueOf(amendment.getId()),
                "Amendment for " + amendment.getOriginalDocumentRef() + " " + verb + " by "
                        + actor() + " → " + amendment.getStatus()
                        + (amendment.getAmendmentTable() != null ? " " + amendment.getAmendmentTable() : "")
                        + ".");
    }

    private String actor() {
        return currentUserService.currentUser().map(AuthPrincipal::username).orElse(SYSTEM_ACTOR);
    }

    private String toJson(AmendmentValue value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialise amendment value: {}", e.getMessage());
            return null;
        }
    }

    private AmendmentValue fromJson(String json) {
        try {
            return objectMapper.readValue(json, AmendmentValue.class);
        } catch (Exception e) {
            return new AmendmentValue(ZERO_MONEY, ZERO_MONEY);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO_MONEY : v;
    }

    private static BigDecimal sum(BigDecimal a, BigDecimal b, BigDecimal c) {
        return nz(a).add(nz(b)).add(nz(c));
    }

    /**
     * The monetary summary of a correction persisted as an amendment's original/corrected JSON: the
     * changed sections' aggregate taxable value and total tax (Reqs 3.4, 3.8).
     *
     * @param taxableValue the aggregate taxable value across the changed sections, scale-2
     * @param taxAmount    the aggregate tax (CGST + SGST + IGST) across the changed sections, scale-2
     */
    public record AmendmentValue(BigDecimal taxableValue, BigDecimal taxAmount) {
    }
}
