package com.shifa.oms.ledger;

import com.shifa.oms.common.ValidationException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link ChartOfAccountsService#deleteLedger(Long)} — the referenced-ledger
 * delete guard (General Ledger, design Correctness Property 4).
 *
 * <p>Feature: general-ledger-accounting, Property 4: A referenced ledger cannot be deleted.
 *
 * <p>Property statement: for any ledger account, if one or more posted voucher lines reference it
 * then deleting it is rejected (and the ledger is NOT deleted); if no posted voucher line references
 * it then deletion succeeds.
 *
 * <p><b>Validates: Requirements 2.4</b>
 *
 * <p>This is a <em>service-level</em> property test. Per the Java 25 gotcha (concrete classes cannot
 * be Mockito-mocked), only the repository <em>interfaces</em> are mocked; {@link ChartOfAccountsService}
 * and the {@link LedgerAccount} entity are real. The single behaviour driven is the guard's decision
 * variable {@code VoucherLineRepository.existsByLedgerAccountId(id)}: {@code true} must block the
 * delete with a {@link ValidationException}, {@code false} must allow it.
 */
class ChartOfAccountsDeleteGuardPropertyTest {

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 4: A referenced ledger cannot be deleted
    // **Validates: Requirements 2.4**
    // ---------------------------------------------------------------------------------------------

    /**
     * For any ledger id/name and any "is referenced by a posted voucher line" flag: when referenced,
     * {@code deleteLedger} throws a {@link ValidationException} and never deletes the ledger; when not
     * referenced, {@code deleteLedger} completes and deletes the ledger exactly once.
     */
    @Property(tries = 200)
    void referencedLedgerCannotBeDeletedButUnreferencedCan(
            @ForAll @LongRange(min = 1L, max = 1_000_000L) long ledgerId,
            @ForAll("ledgerNames") String ledgerName,
            @ForAll boolean referencedByPostedLine) {

        AccountGroupRepository groupRepository = mock(AccountGroupRepository.class);
        LedgerAccountRepository ledgerRepository = mock(LedgerAccountRepository.class);
        VoucherLineRepository voucherLineRepository = mock(VoucherLineRepository.class);

        ChartOfAccountsService service =
                new ChartOfAccountsService(groupRepository, ledgerRepository, voucherLineRepository);

        // A real (un-mocked) ledger entity — only its name is read by the guard's error message.
        LedgerAccount ledger = new LedgerAccount(ledgerName, 7L, null, false);
        when(ledgerRepository.findById(ledgerId)).thenReturn(Optional.of(ledger));
        // The single decision variable under test (Req 2.4): does any posted voucher line reference it?
        when(voucherLineRepository.existsByLedgerAccountId(ledgerId)).thenReturn(referencedByPostedLine);

        if (referencedByPostedLine) {
            // Referenced -> rejected with a validation error, and the ledger is never removed.
            assertThatThrownBy(() -> service.deleteLedger(ledgerId))
                    .as("delete of a referenced ledger is rejected")
                    .isInstanceOf(ValidationException.class);
            verify(ledgerRepository, never()).delete(any(LedgerAccount.class));
        } else {
            // Not referenced -> deletion succeeds; the ledger is deleted exactly once.
            service.deleteLedger(ledgerId);
            verify(ledgerRepository, times(1)).delete(ledger);
        }
    }

    // --- Generators ------------------------------------------------------------------------------

    /** Non-blank ledger names (the guard trims/uses the name only for the rejection message). */
    @Provide
    Arbitrary<String> ledgerNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(30)
                .map(s -> "Ledger " + s);
    }
}
