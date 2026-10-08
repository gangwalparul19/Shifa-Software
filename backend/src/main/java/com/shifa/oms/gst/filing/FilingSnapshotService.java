package com.shifa.oms.gst.filing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.gst.Gstr1ReturnService;
import com.shifa.oms.gst.domain.GstEngine;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.filing.domain.ReturnPeriod;
import com.shifa.oms.gst.filing.domain.ReturnType;
import com.shifa.oms.gst.filing.domain.SnapshotVersioning;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assembles, stores, and serves immutable, versioned {@link FilingSnapshot}s for a
 * {@link ReturnPeriod}/{@link ReturnType} (GST returns &amp; filing, Reqs 5.1, 5.2, 5.3, 5.4, 5.6,
 * 5.7, 5.8).
 *
 * <p><strong>Single figure source, no recompute on read.</strong> When a return is filed, the exact
 * figures presented at filing time are serialised to JSON and persisted (Reqs 5.2, 5.3). Thereafter a
 * filed period's figures are served back <em>verbatim</em> from the stored snapshot with no
 * recomputation (Reqs 2.2, 5.4) — {@link #currentGstr1Return(ReturnPeriod)} deserialises the current
 * GSTR-1 snapshot for the {@code FilingAwareGstr1Provider} to consume.
 *
 * <p><strong>Immutability &amp; versioning.</strong> {@link #capture} always <em>appends</em> a new
 * snapshot whose {@link FilingSnapshot#getVersion() version} is
 * {@link SnapshotVersioning#nextVersion(java.util.Collection)} of the existing versions (monotonic
 * from {@code 1}); the newest is designated current by pointing
 * {@link ReturnFiling#getCurrentSnapshotId()} at it (Reqs 5.1, 5.6). Existing snapshots are never
 * updated or deleted, so a reopen + re-file leaves the originally filed figures intact and auditable
 * (Req 5.5).
 *
 * <p><strong>Payloads.</strong> A GSTR-1 snapshot stores the full {@link Gstr1Return} (the seven
 * portal sections b2b, b2cl, b2cs, cdnr, cdnur, hsn, docs as assembled at filing time — Req 5.2). A
 * GSTR-3B snapshot stores the section-mapped output tax (CGST/SGST/IGST + total), ITC, and net
 * payable as computed at filing time (Req 5.3); ITC is carried as {@code 0.00} here (the CA enters it
 * on the dashboard) and net payable is {@code max(outputTotal − itc, 0)}.
 *
 * <p>If the figures cannot be assembled, {@link #capture} throws (storing no snapshot) so the caller
 * ({@code FilingStatusService.file}) can roll back and leave the filing status unchanged (Req 5.8).
 * Timestamps are Asia/Kolkata to the second (Req 5.1).
 */
@Service
@Transactional
public class FilingSnapshotService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final int SCALE = 2;
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(SCALE, ROUND);

    private final FilingSnapshotRepository snapshotRepository;
    private final ReturnFilingRepository returnFilingRepository;
    private final Gstr1ReturnService gstr1ReturnService;
    private final ObjectMapper objectMapper;

    public FilingSnapshotService(FilingSnapshotRepository snapshotRepository,
                                 ReturnFilingRepository returnFilingRepository,
                                 Gstr1ReturnService gstr1ReturnService,
                                 ObjectMapper objectMapper) {
        this.snapshotRepository = snapshotRepository;
        this.returnFilingRepository = returnFilingRepository;
        this.gstr1ReturnService = gstr1ReturnService;
        this.objectMapper = objectMapper;
    }

    /**
     * Assembles the exact figures for {@code (period, returnType)} and persists them as a new
     * immutable {@link FilingSnapshot}, designated the current snapshot for that filing (Reqs 5.1,
     * 5.2, 5.3, 5.6).
     *
     * <p>The owning {@link ReturnFiling} row is resolved (or created lazily) via the repository. The
     * snapshot's version is the next monotonic version for the filing, and the timestamp is the
     * current Asia/Kolkata time to the second.
     *
     * @param period     the return period whose figures are captured
     * @param returnType the return type (GSTR-1 or GSTR-3B)
     * @param actor      the acting user identifier recorded on the snapshot (Req 5.1)
     * @return the persisted, now-current snapshot
     * @throws ApiException when the return figures cannot be assembled, so the caller can roll back
     *                      and leave the status unchanged with no snapshot stored (Req 5.8)
     */
    public FilingSnapshot capture(ReturnPeriod period, ReturnType returnType, String actor) {
        String payloadJson = assemblePayload(period, returnType);

        ReturnFiling filing = returnFilingRepository
                .findByPeriodYearAndPeriodMonthAndReturnType(period.year(), period.month(), returnType)
                .orElseGet(() -> returnFilingRepository.save(
                        new ReturnFiling(period.year(), period.month(), returnType)));

        int version = SnapshotVersioning.nextVersion(existingVersions(filing.getId()));
        LocalDateTime filedAt = LocalDateTime.now(ZONE).withNano(0);

        FilingSnapshot snapshot = snapshotRepository.save(new FilingSnapshot(
                filing.getId(), returnType, period.year(), period.month(),
                version, payloadJson, actor, filedAt));

        filing.setCurrentSnapshotId(snapshot.getId());
        returnFilingRepository.save(filing);

        return snapshot;
    }

    /**
     * The current (highest-version) snapshot for {@code (period, returnType)}, served with no
     * recomputation (Reqs 2.2, 5.4). Returns the raw entity so callers can read its version, actor,
     * timestamp, and payload; {@link #currentGstr1Return(ReturnPeriod)} deserialises the GSTR-1
     * payload for the export/reconciliation provider.
     *
     * @return the current snapshot, or {@link Optional#empty()} when the period has never been filed
     */
    @Transactional(readOnly = true)
    public Optional<FilingSnapshot> currentSnapshot(ReturnPeriod period, ReturnType returnType) {
        return snapshotRepository.findTopByPeriodYearAndPeriodMonthAndReturnTypeOrderByVersionDesc(
                period.year(), period.month(), returnType);
    }

    /**
     * The current GSTR-1 snapshot for the period deserialised back into the exact {@link Gstr1Return}
     * that was filed — no recomputation (Reqs 2.2, 5.4). Consumed by the filing-aware export/
     * reconciliation provider (task 4.5) so filed figures always equal the stored figures.
     *
     * @return the stored {@link Gstr1Return}, or {@link Optional#empty()} when GSTR-1 has never been
     *         filed for the period
     */
    @Transactional(readOnly = true)
    public Optional<Gstr1Return> currentGstr1Return(ReturnPeriod period) {
        return currentSnapshot(period, ReturnType.GSTR1).map(this::toGstr1Return);
    }

    /**
     * Deserialises a GSTR-1 snapshot's JSON payload back into a {@link Gstr1Return} exactly as it was
     * stored (Req 5.4). Never recomputes; a corrupt/unreadable payload surfaces as an error rather
     * than a silently different figure.
     *
     * @param snapshot a GSTR-1 filing snapshot
     * @return the deserialised return
     */
    public Gstr1Return toGstr1Return(FilingSnapshot snapshot) {
        try {
            return objectMapper.readValue(snapshot.getPayloadJson(), Gstr1Return.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SNAPSHOT_READ_FAILED",
                    "The stored GSTR-1 filing snapshot could not be read.");
        }
    }

    /**
     * The full filing history for {@code (period, returnType)} — every stored snapshot in version
     * order, each carrying its version, actor, and filing timestamp (Reqs 5.5, 5.7). Snapshots are
     * append-only, so this returns the originally filed figures alongside every later re-filing.
     *
     * @return the snapshots ordered oldest version first (empty when never filed)
     */
    @Transactional(readOnly = true)
    public List<FilingSnapshot> history(ReturnPeriod period, ReturnType returnType) {
        return snapshotRepository.findByPeriodYearAndPeriodMonthAndReturnTypeOrderByVersionAsc(
                period.year(), period.month(), returnType);
    }

    // --- Internals ----------------------------------------------------------

    /** Assembles + serialises the exact filed figures for the return type; throws on failure (Req 5.8). */
    private String assemblePayload(ReturnPeriod period, ReturnType returnType) {
        YearMonth ym = period.yearMonth();
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        try {
            Gstr1Return computed = gstr1ReturnService.build(monthStart, monthEnd);
            Object payload = returnType == ReturnType.GSTR3B
                    ? gstr3bSnapshot(computed.reconciliation())
                    : computed;
            return objectMapper.writeValueAsString(payload);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SNAPSHOT_ASSEMBLY_FAILED",
                    "The " + returnType + " figures for " + period.month() + "/" + period.year()
                            + " could not be assembled, so the return was not filed.");
        }
    }

    /**
     * Section-mapped GSTR-3B figures for the snapshot (Req 5.3): output tax (CGST/SGST/IGST + total),
     * ITC, and net payable. ITC is {@code 0.00} (entered by the CA on the dashboard, not persisted
     * here); net payable is {@code max(outputTotal − itc, 0)} at money scale.
     */
    private Gstr3bSnapshot gstr3bSnapshot(GstEngine.Gstr3bSummary summary) {
        BigDecimal cgst = money(summary == null ? null : summary.outputCgst());
        BigDecimal sgst = money(summary == null ? null : summary.outputSgst());
        BigDecimal igst = money(summary == null ? null : summary.outputIgst());
        BigDecimal total = money(summary == null ? null : summary.outputTotal());
        BigDecimal taxable = money(summary == null ? null : summary.taxableOutward());
        BigDecimal itc = ZERO_MONEY;
        BigDecimal netPayable = total.subtract(itc).max(ZERO_MONEY).setScale(SCALE, ROUND);
        return new Gstr3bSnapshot(taxable, cgst, sgst, igst, total, itc, netPayable);
    }

    private List<Integer> existingVersions(Long returnFilingId) {
        List<FilingSnapshot> existing =
                snapshotRepository.findByReturnFilingIdOrderByVersionAsc(returnFilingId);
        List<Integer> versions = new ArrayList<>(existing.size());
        for (FilingSnapshot s : existing) {
            versions.add(s.getVersion());
        }
        return versions;
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? ZERO_MONEY : v.setScale(SCALE, ROUND);
    }

    /**
     * The GSTR-3B snapshot payload (Req 5.3): section-mapped output tax split, ITC, and net payable,
     * serialised as the {@code filing_snapshots.payload_json} for a GSTR-3B filing.
     *
     * @param taxableOutward the taxable value of outward supplies
     * @param outputCgst     output CGST
     * @param outputSgst     output SGST
     * @param outputIgst     output IGST
     * @param outputTotal    total output tax (CGST + SGST + IGST)
     * @param itc            input tax credit claimed (0.00 unless the CA enters it)
     * @param netPayable     net GST payable, {@code max(outputTotal − itc, 0)}
     */
    public record Gstr3bSnapshot(BigDecimal taxableOutward,
                                 BigDecimal outputCgst,
                                 BigDecimal outputSgst,
                                 BigDecimal outputIgst,
                                 BigDecimal outputTotal,
                                 BigDecimal itc,
                                 BigDecimal netPayable) {
    }
}
