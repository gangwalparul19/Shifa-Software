package com.shifa.oms.ledger.autopost;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.ledger.Voucher;
import com.shifa.oms.ledger.VoucherRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The at-most-once guard and source-to-voucher trace for decoupled auto-posting (Reqs 8.4, 9.3, 10.3,
 * 11.4, 18.3).
 *
 * <p>Auto-posting must never create a second voucher for the same source business document. Two layers
 * enforce this:
 * <ol>
 *   <li>the <strong>authoritative</strong> guard is the unique {@code (source_type, source_id)}
 *       constraint on the {@code vouchers} table — a duplicate post fails at the database regardless of
 *       any pre-check; and</li>
 *   <li>this service adds a fast pre-check plus a durable trace via {@link SourcePostingLog}
 *       ({@code ledger_source_postings}): {@link #alreadyPosted} lets the drainer skip a source
 *       document that has already posted (without attempting the post), and {@link #recordPosting}
 *       writes the {@code source → voucher} row after a successful post so the CA can trace any source
 *       document to its voucher (Req 18.3).</li>
 * </ol>
 *
 * <p>{@link #recordPosting} tolerates the unique constraint / a duplicate as a no-op, returning the
 * pre-existing trace row rather than raising — so a re-delivered outbox event or a double publish is
 * harmless. Constructor injection and {@code @Transactional} follow the module's service style; the
 * read methods are {@code readOnly}. This service is deliberately cohesive and dependency-light so the
 * later draft builder ({@code LedgerAutoPostingService}, task 8.3) and drainer
 * ({@code LedgerPostingDrainer}, task 8.7) can call it directly.
 */
@Service
@Transactional
public class SourcePostingGuard {

    private final SourcePostingLogRepository sourcePostingLogRepository;
    private final VoucherRepository voucherRepository;

    public SourcePostingGuard(SourcePostingLogRepository sourcePostingLogRepository,
                              VoucherRepository voucherRepository) {
        this.sourcePostingLogRepository = sourcePostingLogRepository;
        this.voucherRepository = voucherRepository;
    }

    /**
     * The fast pre-check: has this source document already been auto-posted (Reqs 8.4, 9.3, 10.3,
     * 11.4)?
     *
     * <p>Returns {@code true} when either a {@link SourcePostingLog} trace row exists for the source
     * document <em>or</em> a {@link Voucher} already carries its {@code (source_type, source_id)} key.
     * The voucher check is included so the guard is correct even if a voucher was posted without (or
     * before) its trace row was written — the voucher's unique key is the authoritative record.
     *
     * @param sourceType the kind of source document
     * @param sourceId   the source document's identifier
     * @return {@code true} when a voucher has already been posted for this source document
     * @throws ValidationException if {@code sourceType} or {@code sourceId} is null
     */
    @Transactional(readOnly = true)
    public boolean alreadyPosted(SourceType sourceType, Long sourceId) {
        String type = requireType(sourceType);
        Long id = requireId(sourceId);
        return sourcePostingLogRepository.existsBySourceTypeAndSourceId(type, id)
                || voucherRepository.findBySourceTypeAndSourceId(type, id).isPresent();
    }

    /**
     * Records that a source document has been auto-posted to the given voucher (Req 18.3), writing the
     * {@code source → voucher} trace row.
     *
     * <p>Idempotent by design: if a trace row already exists for the source document (whether from a
     * prior successful post or a concurrent insert that wins the unique {@code (source_type,
     * source_id)} constraint), this method is a no-op and returns the pre-existing row rather than
     * creating a second one or raising — matching the at-most-once guarantee (Reqs 8.4, 9.3, 10.3,
     * 11.4).
     *
     * @param sourceType the kind of source document
     * @param sourceId   the source document's identifier
     * @param voucherId  the id of the voucher posted for the source document
     * @return the persisted (or pre-existing) {@link SourcePostingLog} trace row
     * @throws ValidationException if {@code sourceType}, {@code sourceId}, or {@code voucherId} is null
     */
    public SourcePostingLog recordPosting(SourceType sourceType, Long sourceId, Long voucherId) {
        String type = requireType(sourceType);
        Long id = requireId(sourceId);
        if (voucherId == null) {
            throw new ValidationException("A voucher id is required to record a source posting.");
        }

        Optional<SourcePostingLog> existing = sourcePostingLogRepository.findBySourceTypeAndSourceId(type, id);
        if (existing.isPresent()) {
            // Already traced (Reqs 8.4/9.3/10.3/11.4) — no-op, keep the original trace row.
            return existing.get();
        }
        try {
            return sourcePostingLogRepository.save(new SourcePostingLog(type, id, voucherId));
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent insert won the unique (source_type, source_id) constraint; tolerate as a
            // no-op and return the row that now exists.
            return sourcePostingLogRepository.findBySourceTypeAndSourceId(type, id)
                    .orElseThrow(() -> duplicate);
        }
    }

    /**
     * The CA-visible trace: the id of the voucher posted for a source document, if any (Req 18.3).
     *
     * <p>Resolves from the {@link SourcePostingLog} trace row first (the fast path) and falls back to
     * the voucher's own {@code (source_type, source_id)} key, so the trace is available even for a
     * voucher posted without a trace row.
     *
     * @param sourceType the kind of source document
     * @param sourceId   the source document's identifier
     * @return the posted voucher's id, or {@link Optional#empty()} when the source has not been posted
     * @throws ValidationException if {@code sourceType} or {@code sourceId} is null
     */
    @Transactional(readOnly = true)
    public Optional<Long> voucherIdForSource(SourceType sourceType, Long sourceId) {
        String type = requireType(sourceType);
        Long id = requireId(sourceId);
        Optional<Long> fromLog = sourcePostingLogRepository.findBySourceTypeAndSourceId(type, id)
                .map(SourcePostingLog::getVoucherId);
        if (fromLog.isPresent()) {
            return fromLog;
        }
        return voucherRepository.findBySourceTypeAndSourceId(type, id).map(Voucher::getId);
    }

    private static String requireType(SourceType sourceType) {
        if (sourceType == null) {
            throw new ValidationException("A source type is required.");
        }
        return sourceType.name();
    }

    private static Long requireId(Long sourceId) {
        if (sourceId == null) {
            throw new ValidationException("A source id is required.");
        }
        return sourceId;
    }
}
