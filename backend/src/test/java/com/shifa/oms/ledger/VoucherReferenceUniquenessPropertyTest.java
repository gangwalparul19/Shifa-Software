package com.shifa.oms.ledger;

import com.shifa.oms.ledger.domain.VoucherType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link VoucherReferenceSequencer} — per-type-per-financial-year voucher
 * reference uniqueness (General Ledger, design Correctness Property 10).
 *
 * <p>Feature: general-ledger-accounting, Property 10: Voucher references are unique within type and
 * financial year.
 *
 * <p>Property statement: for any sequence of vouchers posted within the same voucher type and
 * financial year, all assigned voucher references are distinct.
 *
 * <p><b>Validates: Requirements 5.8</b>
 *
 * <p>This is a <em>service-level</em> property test. Per the project's Java 25 gotcha (Mockito
 * cannot mock concrete classes), the collaborator mocked here is the Spring Data
 * <em>interface</em> {@link LedgerVoucherSequenceRepository}, backed by a simple in-memory map keyed
 * on the composite natural key {@code (voucher_type, financial_year_id)}. The fake faithfully
 * emulates the real pessimistic-lock allocate: {@code findByIdForUpdate} returns the current row (or
 * empty on first use) and {@code save} persists the incremented {@code next_value}. Because the
 * production row lock only serialises concurrent allocations (each still gets a distinct number), a
 * single-threaded fake reproduces the observable numbering exactly.
 */
class VoucherReferenceUniquenessPropertyTest {

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 10: Voucher references are unique within type and
    //          financial year
    // **Validates: Requirements 5.8**
    // ---------------------------------------------------------------------------------------------

    /**
     * Req 5.8: allocating N references for the SAME (voucher type, financial year) yields N distinct
     * references whose running numbers increase monotonically 1..N, and every reference is formatted
     * as {@code <prefix>/<fy label>/<zero-padded number>}.
     */
    @Property(tries = 200)
    void referencesForSameTypeAndYearAreAllDistinct(
            @ForAll("voucherTypes") VoucherType type,
            @ForAll("fyLabels") String fyLabel,
            @ForAll @IntRange(min = 1, max = 60) int count) {

        Fixture f = newFixture();
        FinancialYear fy = financialYear(1L, fyLabel);

        List<String> references = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            references.add(f.sequencer.allocate(type, fy));
        }

        // All N references are distinct (the property under test).
        assertThat(references).doesNotHaveDuplicates();
        assertThat(new HashSet<>(references)).hasSize(count);

        // The running numbers increase monotonically 1..N and the format is <prefix>/<label>/<num>.
        String prefix = VoucherReferenceSequencer.prefixFor(type);
        for (int i = 0; i < count; i++) {
            assertThat(references.get(i))
                    .isEqualTo(VoucherReferenceSequencer.format(prefix, fyLabel, i + 1L));
        }
    }

    /**
     * Req 5.8: uniqueness is scoped to (type, FY). Two DIFFERENT (type, FY) pairs each start their
     * own series at 1 — so their raw numbers repeat — yet the fully formatted references never
     * collide, because they differ by the type prefix and/or the financial-year label. The combined
     * set of all references stays fully distinct.
     */
    @Property(tries = 200)
    void referencesAcrossDifferentTypeYearPairsNeverCollide(
            @ForAll("voucherTypes") VoucherType typeA,
            @ForAll("voucherTypes") VoucherType typeB,
            @ForAll("fyLabels") String labelA,
            @ForAll("fyLabels") String labelB,
            @ForAll @IntRange(min = 1, max = 30) int count) {

        // Constrain to genuinely different (type, FY) pairs, with distinct FY ids per label.
        boolean samePair = typeA == typeB && labelA.equals(labelB);
        if (samePair) {
            return; // covered by the same-pair property above
        }

        Fixture f = newFixture();
        FinancialYear fyA = financialYear(1L, labelA);
        FinancialYear fyB = financialYear(labelA.equals(labelB) ? 1L : 2L, labelB);

        List<String> refsA = new ArrayList<>();
        List<String> refsB = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            refsA.add(f.sequencer.allocate(typeA, fyA));
            refsB.add(f.sequencer.allocate(typeB, fyB));
        }

        // Each series is internally distinct...
        assertThat(refsA).doesNotHaveDuplicates();
        assertThat(refsB).doesNotHaveDuplicates();

        // ...and the two series never share a reference, even though their raw numbers overlap 1..N.
        Set<String> intersection = new HashSet<>(refsA);
        intersection.retainAll(refsB);
        assertThat(intersection).isEmpty();

        // The full population of references is distinct.
        Set<String> all = new HashSet<>(refsA);
        all.addAll(refsB);
        assertThat(all).hasSize(refsA.size() + refsB.size());
    }

    // --- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<VoucherType> voucherTypes() {
        return Arbitraries.of(VoucherType.values());
    }

    /** Realistic Indian FY labels like {@code "2025-26"} spanning a range of start years. */
    @Provide
    Arbitrary<String> fyLabels() {
        return Arbitraries.integers().between(2000, 2099)
                .map(y -> y + "-" + String.format("%02d", (y + 1) % 100));
    }

    // --- In-memory sequence-repository fixture (mocked interface) --------------------------------

    /**
     * A {@link VoucherReferenceSequencer} wired over a Mockito-mocked
     * {@link LedgerVoucherSequenceRepository} interface backed by an in-memory map. The map is keyed
     * on {@code voucher_type|financial_year_id} (the composite natural key), so each (type, FY) pair
     * keeps its own running counter exactly like the real {@code ledger_voucher_sequences} table.
     */
    private static final class Fixture {
        final VoucherReferenceSequencer sequencer;

        Fixture(VoucherReferenceSequencer sequencer) {
            this.sequencer = sequencer;
        }
    }

    private static Fixture newFixture() {
        Map<String, LedgerVoucherSequence> rows = new ConcurrentHashMap<>();
        LedgerVoucherSequenceRepository repo = mock(LedgerVoucherSequenceRepository.class);

        when(repo.findByIdForUpdate(any(), anyLong())).thenAnswer(inv -> {
            String type = inv.getArgument(0);
            long fyId = inv.getArgument(1);
            return Optional.ofNullable(rows.get(key(type, fyId)));
        });
        when(repo.save(any(LedgerVoucherSequence.class))).thenAnswer(inv -> {
            LedgerVoucherSequence row = inv.getArgument(0);
            rows.put(key(row.getVoucherType(), row.getFinancialYearId()), row);
            return row;
        });

        return new Fixture(new VoucherReferenceSequencer(repo));
    }

    private static String key(String voucherType, long financialYearId) {
        return voucherType + "|" + financialYearId;
    }

    // --- FinancialYear construction (JPA-generated id has no public setter) -----------------------

    /** Build a {@link FinancialYear} carrying a fixed id + label for the sequencer to read. */
    private static FinancialYear financialYear(long id, String label) {
        LocalDate start = LocalDate.of(2000, 4, 1);
        FinancialYear fy = new FinancialYear(start, start.plusYears(1).minusDays(1), label);
        setId(fy, id);
        return fy;
    }

    private static void setId(Object entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not assign id on " + entity.getClass(), e);
        }
    }
}
