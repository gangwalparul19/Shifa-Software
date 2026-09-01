package com.shifa.oms.gst.filing;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.gst.filing.domain.FilingStatus;
import com.shifa.oms.gst.filing.domain.FilingStatusMachine;
import com.shifa.oms.gst.filing.domain.FilingTransition;
import com.shifa.oms.gst.filing.domain.ReturnPeriod;
import com.shifa.oms.gst.filing.domain.ReturnType;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The filing-status lifecycle guardian for GST returns &amp; filing (Reqs 1.2–1.8, 2.3–2.8, 5.8, 10.2).
 *
 * <p>Owns the {@code NOT_STARTED → PREPARED → FILED} transitions (and the ADMIN/CA-only
 * {@code FILED → PREPARED} reopen), delegating the legality decision to the pure
 * {@link FilingStatusMachine} and the immutable figure capture to {@link FilingSnapshotService}. A
 * missing {@link ReturnFiling} row denotes {@link FilingStatus#NOT_STARTED} (Req 1.2); rows are created
 * lazily on the first {@code prepare}.
 *
 * <p><strong>File is snapshot-first (Reqs 5.1, 5.8).</strong> {@link #file} asks
 * {@link FilingSnapshotService#capture} to assemble and persist the exact filed figures <em>before</em>
 * flipping the status to FILED. If the figures cannot be assembled, {@code capture} throws and the
 * whole transaction rolls back — no snapshot is stored and the status is left unchanged.
 *
 * <p><strong>Deterministic concurrency (Req 2.8).</strong> {@link ReturnFiling} carries a JPA
 * {@code @Version} column and a unique key on {@code (period_year, period_month, return_type)}.
 * Persisting via {@code saveAndFlush} forces the version check within the transaction, so a racing
 * file/re-file/reopen loses the version check and its {@link OptimisticLockingFailureException} is
 * mapped here to a {@code 409 Conflict} ({@link ApiException}) rather than a 500 — exactly one request
 * wins and the resulting status is deterministic. Rejected transitions (invalid transition, locked
 * period) are likewise surfaced as {@code 409 Conflict}, leaving the stored status unchanged.
 */
@Service
@Transactional
public class FilingStatusService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final int ACK_REFERENCE_MAX_LENGTH = 50;

    private final ReturnFilingRepository returnFilingRepository;
    private final FilingSnapshotService filingSnapshotService;
    private final AuditService auditService;

    public FilingStatusService(ReturnFilingRepository returnFilingRepository,
                               FilingSnapshotService filingSnapshotService,
                               AuditService auditService) {
        this.returnFilingRepository = returnFilingRepository;
        this.filingSnapshotService = filingSnapshotService;
        this.auditService = auditService;
    }

    /**
     * The current {@link FilingStatus} of GSTR-1 and GSTR-3B for a selected period (Reqs 1.2, 1.6). A
     * period/type with no filing row is reported as {@link FilingStatus#NOT_STARTED}.
     *
     * @param month the calendar month (1–12)
     * @param year  the four-digit calendar year
     * @return the pair of statuses for the period
     */
    @Transactional(readOnly = true)
    public PeriodFilingStatus status(int month, int year) {
        ReturnPeriod period = new ReturnPeriod(month, year);
        return new PeriodFilingStatus(
                statusOf(period, ReturnType.GSTR1),
                statusOf(period, ReturnType.GSTR3B));
    }

    /**
     * Marks a return prepared: {@code NOT_STARTED → PREPARED} (Reqs 1.3, 1.7). Loads or lazily creates
     * the filing row, validates the transition via {@link FilingStatusMachine#prepare}, persists, and
     * audits within the transaction.
     *
     * @param period     the return period
     * @param returnType the return type (GSTR-1 or GSTR-3B)
     * @param actor      the acting user identifier
     * @return the updated filing row
     * @throws ApiException {@code 409} when the return is not {@code NOT_STARTED} (invalid transition),
     *                      or when a concurrent change wins the version check
     */
    public ReturnFiling prepare(ReturnPeriod period, ReturnType returnType, String actor) {
        ReturnFiling filing = loadOrInit(period, returnType);

        FilingTransition transition = FilingStatusMachine.prepare(filing.getStatus());
        if (transition.rejected()) {
            throw transitionConflict(period, returnType, "prepare", filing.getStatus(),
                    transition.reason());
        }

        filing.setStatus(transition.newStatus());
        ReturnFiling saved = persist(filing);

        auditService.record(AuditActions.GST_RETURN_PREPARED, AuditActions.ENTITY_GST,
                entityId(period, returnType),
                describe(returnType, period) + " marked PREPARED by " + actor + ".");
        return saved;
    }

    /**
     * Files a prepared return: {@code PREPARED → FILED} (Reqs 1.4, 1.5, 1.8, 2.3, 5.1, 5.8, 10.2).
     *
     * <p>The snapshot is captured <em>before</em> the status flips: if the figures cannot be assembled
     * the {@link FilingSnapshotService#capture} call throws and the whole transaction rolls back,
     * leaving the status unchanged with no snapshot (Req 5.8). A supplied acknowledgement reference is
     * validated to 1–50 characters and stored (Req 1.5). The filing timestamp and actor are recorded in
     * Asia/Kolkata time (Req 1.4).
     *
     * @param period       the return period
     * @param returnType   the return type
     * @param ackReference optional 1–50 char portal acknowledgement reference (nullable/blank = none)
     * @param actor        the acting user identifier
     * @return the updated filing row
     * @throws ApiException {@code 400} when {@code ackReference} exceeds 50 characters; {@code 409} when
     *                      the return is not {@code PREPARED} (locked when already FILED), or a
     *                      concurrent change wins the version check
     */
    public ReturnFiling file(ReturnPeriod period, ReturnType returnType, String ackReference, String actor) {
        String normalisedAck = validateAckReference(ackReference);

        ReturnFiling filing = returnFilingRepository
                .findByPeriodYearAndPeriodMonthAndReturnType(period.year(), period.month(), returnType)
                .orElseGet(() -> new ReturnFiling(period.year(), period.month(), returnType));

        FilingTransition transition = FilingStatusMachine.file(filing.getStatus());
        if (transition.rejected()) {
            throw transitionConflict(period, returnType, "file", filing.getStatus(),
                    transition.reason());
        }

        // Assemble + store the immutable snapshot BEFORE flipping status. If this throws, the whole
        // transaction rolls back: no snapshot, status unchanged (Req 5.8).
        filingSnapshotService.capture(period, returnType, actor);

        filing.setStatus(transition.newStatus());
        filing.setFiledBy(actor);
        filing.setFiledAt(LocalDateTime.now(ZONE).withNano(0));
        if (normalisedAck != null) {
            filing.setAckReference(normalisedAck);
        }
        ReturnFiling saved = persist(filing);

        auditService.record(AuditActions.GST_RETURN_FILED, AuditActions.ENTITY_GST,
                entityId(period, returnType),
                describe(returnType, period) + " FILED by " + actor
                        + (normalisedAck != null ? " (ack " + normalisedAck + ")" : "") + ".");
        return saved;
    }

    /**
     * Reopens a filed return: {@code FILED → PREPARED}, restricted to ADMIN/CA (Reqs 2.4, 2.5, 2.6,
     * 2.7). The prior filing snapshot is retained untouched so the originally filed figures remain
     * auditable (Req 2.5); the reopen actor and Asia/Kolkata timestamp are recorded.
     *
     * @param period     the return period
     * @param returnType the return type
     * @param actor      the acting user identifier
     * @return the updated filing row
     * @throws ApiException {@code 409} when the return is not currently FILED, or a concurrent change
     *                      wins the version check
     */
    @PreAuthorize("hasAnyRole('ADMIN','CA')")
    public ReturnFiling reopen(ReturnPeriod period, ReturnType returnType, String actor) {
        ReturnFiling filing = returnFilingRepository
                .findByPeriodYearAndPeriodMonthAndReturnType(period.year(), period.month(), returnType)
                .orElseGet(() -> new ReturnFiling(period.year(), period.month(), returnType));

        FilingTransition transition = FilingStatusMachine.reopen(filing.getStatus());
        if (transition.rejected()) {
            throw transitionConflict(period, returnType, "reopen", filing.getStatus(),
                    transition.reason());
        }

        // Retain the prior snapshot: do NOT touch currentSnapshotId or any filing_snapshots row (Req 2.5).
        filing.setStatus(transition.newStatus());
        filing.setReopenedBy(actor);
        filing.setReopenedAt(LocalDateTime.now(ZONE).withNano(0));
        ReturnFiling saved = persist(filing);

        auditService.record(AuditActions.GST_RETURN_REOPENED, AuditActions.ENTITY_GST,
                entityId(period, returnType),
                describe(returnType, period) + " reopened to PREPARED by " + actor + ".");
        return saved;
    }

    // --- Internals ----------------------------------------------------------

    private FilingStatus statusOf(ReturnPeriod period, ReturnType returnType) {
        return returnFilingRepository
                .findByPeriodYearAndPeriodMonthAndReturnType(period.year(), period.month(), returnType)
                .map(ReturnFiling::getStatus)
                .orElse(FilingStatus.NOT_STARTED);
    }

    private ReturnFiling loadOrInit(ReturnPeriod period, ReturnType returnType) {
        return returnFilingRepository
                .findByPeriodYearAndPeriodMonthAndReturnType(period.year(), period.month(), returnType)
                .orElseGet(() -> new ReturnFiling(period.year(), period.month(), returnType));
    }

    /**
     * Persists the filing and forces the {@code @Version} check within the transaction so a losing
     * concurrent writer surfaces its {@link OptimisticLockingFailureException} here — mapped to a
     * deterministic {@code 409 Conflict} (Req 2.8) instead of falling through to a 500.
     */
    private ReturnFiling persist(ReturnFiling filing) {
        try {
            return returnFilingRepository.saveAndFlush(filing);
        } catch (OptimisticLockingFailureException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                    "This return was modified by another request. Please reload and try again.");
        }
    }

    /** Validates the optional acknowledgement reference: 1–50 chars when supplied (Req 1.5). */
    private String validateAckReference(String ackReference) {
        if (ackReference == null) {
            return null;
        }
        String trimmed = ackReference.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > ACK_REFERENCE_MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACK_REFERENCE",
                    "The acknowledgement reference must be 1 to " + ACK_REFERENCE_MAX_LENGTH
                            + " characters.");
        }
        return trimmed;
    }

    /** Maps a rejected {@link FilingTransition} to a {@code 409 Conflict} with a descriptive message. */
    private ApiException transitionConflict(ReturnPeriod period, ReturnType returnType, String action,
                                            FilingStatus current, FilingTransition.Reason reason) {
        if (reason == FilingTransition.Reason.PERIOD_LOCKED) {
            return new ApiException(HttpStatus.CONFLICT, "PERIOD_LOCKED",
                    describe(returnType, period) + " is FILED and its period is locked; it cannot be "
                            + action + "d.");
        }
        return new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                "Cannot " + action + " " + describe(returnType, period)
                        + ": its current status is " + current + ".");
    }

    private static String entityId(ReturnPeriod period, ReturnType returnType) {
        return returnType + "-" + period.month() + "/" + period.year();
    }

    private static String describe(ReturnType returnType, ReturnPeriod period) {
        return returnType + " for " + period.month() + "/" + period.year();
    }

    /**
     * The current filing statuses of both return types for a selected period (Reqs 1.2, 1.6).
     *
     * @param gstr1  the GSTR-1 filing status
     * @param gstr3b the GSTR-3B filing status
     */
    public record PeriodFilingStatus(FilingStatus gstr1, FilingStatus gstr3b) {
    }
}
