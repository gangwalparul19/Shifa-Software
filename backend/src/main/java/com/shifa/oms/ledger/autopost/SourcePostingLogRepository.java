package com.shifa.oms.ledger.autopost;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link SourcePostingLog} (auto-posting idempotency, Reqs 8.4, 9.3,
 * 10.3, 11.4).
 *
 * <p>{@link #existsBySourceTypeAndSourceId} is the fast pre-check the drainer uses to skip a source
 * document that has already posted a voucher.
 */
public interface SourcePostingLogRepository extends JpaRepository<SourcePostingLog, Long> {

    /** True when the given source document has already been auto-posted. */
    boolean existsBySourceTypeAndSourceId(String sourceType, Long sourceId);

    /** The posting-log row for a source document, if any (source → voucher trace). */
    Optional<SourcePostingLog> findBySourceTypeAndSourceId(String sourceType, Long sourceId);
}
