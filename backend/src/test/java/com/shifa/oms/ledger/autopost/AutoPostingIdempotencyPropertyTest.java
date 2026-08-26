package com.shifa.oms.ledger.autopost;

import com.shifa.oms.ledger.Voucher;
import com.shifa.oms.ledger.VoucherRepository;
import com.shifa.oms.ledger.domain.VoucherType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link SourcePostingGuard} — the at-most-once auto-posting guard (General
 * Ledger, design Correctness Property 18).
 *
 * <p>Feature: general-ledger-accounting, Property 18: Auto-posting is idempotent per source document.
 *
 * <p>Property statement: for any source document, posting it more than once creates at most one
 * voucher — the second (and every subsequent) attempt is a no-op guarded by the source-posting log
 * plus the unique {@code vouchers(source_type, source_id)} key. Concretely, driving the drainer's
 * guard protocol for a source: the first {@link SourcePostingGuard#alreadyPosted} is {@code false},
 * {@link SourcePostingGuard#recordPosting} writes exactly one trace row, thereafter
 * {@code alreadyPosted} is {@code true} and {@link SourcePostingGuard#voucherIdForSource} returns the
 * recorded voucher id; a second {@code recordPosting} for the same source is a no-op that returns the
 * SAME original row/voucher id and never creates a second row. Independently, when a voucher already
 * carries the source key (even with no trace row) {@code alreadyPosted} is {@code true}. Distinct
 * source keys never interfere with one another.
 *
 * <p><b>Validates: Requirements 8.4, 9.3, 10.3, 11.4</b>
 *
 * <p>This is a <em>service-level</em> property test. Per the Java 25 gotcha (concrete classes cannot
 * be Mockito-mocked), only the repository <em>interfaces</em> are mocked; {@link SourcePostingGuard},
 * the {@link SourcePostingLog}, and the {@link Voucher} entity are real. The mocks are backed by an
 * in-memory store that faithfully emulates the persistence contract: {@code existsBy…} /
 * {@code findBy…} over recorded rows, {@code save} enforcing the unique {@code (source_type,
 * source_id)} key (throwing {@link DataIntegrityViolationException} on a duplicate), and
 * {@code VoucherRepository.findBySourceTypeAndSourceId} over posted vouchers.
 */
class AutoPostingIdempotencyPropertyTest {

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 18: Auto-posting is idempotent per source document
    // **Validates: Requirements 8.4, 9.3, 10.3, 11.4**
    // ---------------------------------------------------------------------------------------------

    /**
     * For any set of distinct source documents, running the guard protocol (and a redundant re-post)
     * for each yields at most one trace row per source, preserves the original voucher id, and never
     * lets one source's posting interfere with another's.
     */
    @Property(tries = 200)
    void repostingASourceDocumentIsANoOpAndNeverCreatesASecondVoucher(
            @ForAll("sourcePostings") List<SourceDoc> postings) {

        Fixture fx = new Fixture();
        SourcePostingGuard guard = fx.guard;

        for (SourceDoc doc : postings) {
            // Before any post: neither a trace row nor a voucher exists for this source.
            assertThat(guard.alreadyPosted(doc.type(), doc.id()))
                    .as("source not yet posted")
                    .isFalse();
            assertThat(guard.voucherIdForSource(doc.type(), doc.id()))
                    .as("no voucher id before posting")
                    .isEmpty();

            // First posting records exactly one trace row for the source → voucher.
            SourcePostingLog first = guard.recordPosting(doc.type(), doc.id(), doc.voucherId());
            assertThat(first.getVoucherId()).isEqualTo(doc.voucherId());

            // Now the source is posted and traceable to its voucher.
            assertThat(guard.alreadyPosted(doc.type(), doc.id()))
                    .as("source posted after recordPosting")
                    .isTrue();
            assertThat(guard.voucherIdForSource(doc.type(), doc.id()))
                    .as("voucher id is traceable")
                    .contains(doc.voucherId());

            // A second attempt for the SAME source — even claiming a different voucher id — is a
            // no-op: it returns the ORIGINAL trace row/voucher id and creates no second row.
            SourcePostingLog second = guard.recordPosting(doc.type(), doc.id(), doc.voucherId() + 777);
            assertThat(second.getVoucherId())
                    .as("re-post keeps the original voucher id")
                    .isEqualTo(doc.voucherId());
            assertThat(guard.voucherIdForSource(doc.type(), doc.id()))
                    .as("trace still points at the original voucher")
                    .contains(doc.voucherId());
        }

        // At-most-once across the whole run: exactly one persisted row per distinct source document,
        // and each still resolves to its own voucher id (no cross-source interference).
        assertThat(fx.logStore).hasSize(postings.size());
        for (SourceDoc doc : postings) {
            assertThat(guard.voucherIdForSource(doc.type(), doc.id())).contains(doc.voucherId());
        }
    }

    /**
     * When a {@link Voucher} already carries the source key (auto-posted before, or without a trace
     * row ever being written), {@link SourcePostingGuard#alreadyPosted} is {@code true} — the unique
     * {@code vouchers(source_type, source_id)} key is the authoritative at-most-once record, so a
     * fresh drainer never attempts a duplicate post.
     */
    @Property(tries = 200)
    void anExistingVoucherForTheSourceMakesItAlreadyPostedEvenWithoutATraceRow(
            @ForAll SourceType type,
            @ForAll @LongRange(min = 1L, max = 1_000_000L) long sourceId) {

        Fixture fx = new Fixture();

        // No trace row: alreadyPosted is false until a voucher exists.
        assertThat(fx.guard.alreadyPosted(type, sourceId)).isFalse();

        // A voucher stamped with the source key exists, but no ledger_source_postings row was written.
        fx.voucherStore.put(key(type.name(), sourceId), sampleVoucher(type, sourceId));

        assertThat(fx.guard.alreadyPosted(type, sourceId))
                .as("voucher key alone marks the source as already posted")
                .isTrue();
    }

    // --- Fixture: repository interface mocks backed by faithful in-memory stores ------------------

    /**
     * Real {@link SourcePostingGuard} over mocked repository interfaces whose behaviour is backed by
     * in-memory maps keyed by {@code (source_type, source_id)}, emulating the DB's uniqueness contract.
     */
    private static final class Fixture {
        final Map<String, SourcePostingLog> logStore = new HashMap<>();
        final Map<String, Voucher> voucherStore = new HashMap<>();
        final SourcePostingGuard guard;

        Fixture() {
            SourcePostingLogRepository logRepository = mock(SourcePostingLogRepository.class);
            VoucherRepository voucherRepository = mock(VoucherRepository.class);

            when(logRepository.existsBySourceTypeAndSourceId(anyString(), anyLong()))
                    .thenAnswer(inv -> logStore.containsKey(key(inv.getArgument(0), inv.getArgument(1))));

            when(logRepository.findBySourceTypeAndSourceId(anyString(), anyLong()))
                    .thenAnswer(inv -> Optional.ofNullable(logStore.get(key(inv.getArgument(0), inv.getArgument(1)))));

            when(logRepository.save(any(SourcePostingLog.class))).thenAnswer(inv -> {
                SourcePostingLog row = inv.getArgument(0);
                String k = key(row.getSourceType(), row.getSourceId());
                if (logStore.containsKey(k)) {
                    // Emulate the unique (source_type, source_id) constraint.
                    throw new DataIntegrityViolationException(
                            "duplicate ledger_source_postings (" + k + ")");
                }
                logStore.put(k, row);
                return row;
            });

            when(voucherRepository.findBySourceTypeAndSourceId(anyString(), anyLong()))
                    .thenAnswer(inv -> Optional.ofNullable(voucherStore.get(key(inv.getArgument(0), inv.getArgument(1)))));

            this.guard = new SourcePostingGuard(logRepository, voucherRepository);
        }
    }

    private static String key(String type, Object id) {
        return type + "#" + id;
    }

    /** A real posted {@link Voucher} stamped with the given source key (only the key is read here). */
    private static Voucher sampleVoucher(SourceType type, long sourceId) {
        return new Voucher(
                VoucherType.SALES,
                LocalDate.of(2025, 4, 1),
                1L,
                "SALES/2025-26/1",
                "auto-posted",
                "system",
                type.name(),
                sourceId,
                null);
    }

    // --- Generators ------------------------------------------------------------------------------

    /** A source document to post: its type, its (distinct) id, and the voucher id it posts to. */
    record SourceDoc(SourceType type, long id, long voucherId) {
    }

    /**
     * A list of distinct source documents. Distinct {@code sourceId}s (each mapped to one source
     * type) guarantee distinct {@code (source_type, source_id)} keys, so the property can assert that
     * cross-source postings never interfere.
     */
    @Provide
    Arbitrary<List<SourceDoc>> sourcePostings() {
        Arbitrary<Set<Long>> distinctIds = Arbitraries.longs()
                .between(1L, 1_000_000L)
                .set()
                .ofMinSize(1)
                .ofMaxSize(8);
        return distinctIds.map(ids -> {
            List<SourceDoc> docs = new ArrayList<>();
            long voucherId = 5000L;
            int i = 0;
            for (Long id : ids) {
                SourceType type = SourceType.values()[i % SourceType.values().length];
                docs.add(new SourceDoc(type, id, voucherId++));
                i++;
            }
            return docs;
        });
    }
}
