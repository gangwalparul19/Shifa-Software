package com.shifa.oms.gst.filing;

import com.shifa.oms.gst.filing.domain.ReturnType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for the immutable, append-only {@link FilingSnapshot} rows
 * (GST returns &amp; filing, Reqs 5.1, 5.4, 5.6, 5.7).
 *
 * <p>Exposes only read + append (inherited {@code save}) operations — snapshots are never updated
 * or deleted (Req 5.5). The finders cover exactly what {@code FilingSnapshotService} needs:
 * <ul>
 *   <li>the whole filing history in version order (Req 5.7);</li>
 *   <li>the current (highest-version) snapshot to serve a filed period with no recompute
 *       (Reqs 2.2, 5.4);</li>
 *   <li>the existing versions to derive the next one on capture (Req 5.6).</li>
 * </ul>
 * Both a filing-scoped and a period-scoped variant are provided so a caller can look up snapshots
 * whether it holds the {@link ReturnFiling} id or just the (period, return type) coordinates.
 */
public interface FilingSnapshotRepository extends JpaRepository<FilingSnapshot, Long> {

    /** All snapshots for a filing, oldest version first — the filing history (Req 5.7). */
    List<FilingSnapshot> findByReturnFilingIdOrderByVersionAsc(Long returnFilingId);

    /** The current (highest-version) snapshot for a filing, if any (Reqs 5.4, 5.6). */
    Optional<FilingSnapshot> findTopByReturnFilingIdOrderByVersionDesc(Long returnFilingId);

    /**
     * All snapshots for a (period, return type), oldest version first — the filing history when the
     * caller holds only the period coordinates (Req 5.7).
     */
    List<FilingSnapshot> findByPeriodYearAndPeriodMonthAndReturnTypeOrderByVersionAsc(
            int periodYear, int periodMonth, ReturnType returnType);

    /**
     * The current (highest-version) snapshot for a (period, return type), if any — the figures served
     * for a filed period with no recomputation (Reqs 2.2, 5.4, 5.6).
     */
    Optional<FilingSnapshot> findTopByPeriodYearAndPeriodMonthAndReturnTypeOrderByVersionDesc(
            int periodYear, int periodMonth, ReturnType returnType);
}
