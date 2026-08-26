package com.shifa.oms.ledger;

import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath;
import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;
import com.shifa.oms.ledger.domain.DrCr;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Indian financial-year handling for the General Ledger (Reqs 4.1–4.4, 3.4).
 *
 * <p>A financial year runs 1 April – 31 March of the following calendar year (Req 4.1). This service:
 * <ul>
 *   <li>resolves (get-or-create) the financial year that <em>contains</em> a voucher date
 *       (Req 4.2) via {@link #financialYearFor(LocalDate)};</li>
 *   <li>closes a year and reports whether it is closed (Req 4.3) so posting can be rejected for a
 *       closed year;</li>
 *   <li>opens the next year, carrying each ledger account's closing balance forward as that account's
 *       opening balance in the next year (Req 3.4) via {@link #openNext(Long)}; and</li>
 *   <li>resolves a reporting period from either a financial-year id or an explicit date range
 *       (Req 4.4) via {@link #resolvePeriod(Long, LocalDate, LocalDate)} for the read services.</li>
 * </ul>
 *
 * <p>The closing-balance carry-forward reuses the pure {@link BalanceMath} sign convention: an
 * account's closing signed balance for the source year is its opening signed balance plus the net
 * signed movement of the year's posted voucher lines, and {@link BalanceMath#closingSide} turns that
 * signed balance into the {@code (amount, DEBIT/CREDIT side)} persisted as the next year's opening
 * balance.
 *
 * <p>Follows the codebase {@code Clock} dual-constructor convention (with {@code @Autowired} on the
 * primary constructor) so the "current" financial year and close timestamps are testable.
 */
@Service
public class FinancialYearService {

    /** Money scale matching the codebase-wide {@code BigDecimal} scale-2 {@code HALF_UP} convention. */
    private static final int MONEY_SCALE = 2;

    private final FinancialYearRepository financialYearRepository;
    private final OpeningBalanceRepository openingBalanceRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherLineRepository voucherLineRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final AccountGroupRepository accountGroupRepository;
    private final CurrentUserService currentUserService;
    private final Clock clock;

    @Autowired
    public FinancialYearService(FinancialYearRepository financialYearRepository,
                                OpeningBalanceRepository openingBalanceRepository,
                                VoucherRepository voucherRepository,
                                VoucherLineRepository voucherLineRepository,
                                LedgerAccountRepository ledgerAccountRepository,
                                AccountGroupRepository accountGroupRepository,
                                CurrentUserService currentUserService) {
        this(financialYearRepository, openingBalanceRepository, voucherRepository, voucherLineRepository,
                ledgerAccountRepository, accountGroupRepository, currentUserService, Clock.systemDefaultZone());
    }

    FinancialYearService(FinancialYearRepository financialYearRepository,
                         OpeningBalanceRepository openingBalanceRepository,
                         VoucherRepository voucherRepository,
                         VoucherLineRepository voucherLineRepository,
                         LedgerAccountRepository ledgerAccountRepository,
                         AccountGroupRepository accountGroupRepository,
                         CurrentUserService currentUserService,
                         Clock clock) {
        this.financialYearRepository = financialYearRepository;
        this.openingBalanceRepository = openingBalanceRepository;
        this.voucherRepository = voucherRepository;
        this.voucherLineRepository = voucherLineRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.accountGroupRepository = accountGroupRepository;
        this.currentUserService = currentUserService;
        this.clock = clock;
    }

    /**
     * The pure Indian-financial-year window that contains {@code date} (Reqs 4.1, 4.2): the year runs
     * from 1 April of the year the date belongs to (the date's own calendar year when its month is
     * April or later, otherwise the previous calendar year) to 31 March of the following year.
     *
     * @param date any date
     * @return the {@code (startDate, endDate, label)} of the Indian FY containing {@code date}
     */
    public static FinancialYearWindow windowFor(LocalDate date) {
        Objects.requireNonNull(date, "date");
        int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        LocalDate start = LocalDate.of(startYear, 4, 1);
        LocalDate end = LocalDate.of(startYear + 1, 3, 31);
        String label = String.format("%d-%02d", startYear, (startYear + 1) % 100);
        return new FinancialYearWindow(start, end, label);
    }

    /**
     * The financial year that contains {@code date} (Req 4.2), creating it if it does not yet exist
     * so posting always has a year to record against. The created year uses the Indian-FY window
     * from {@link #windowFor(LocalDate)} and is open.
     *
     * @param date the voucher date
     * @return the (existing or newly created) financial year containing {@code date}
     */
    @Transactional
    public FinancialYear financialYearFor(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return financialYearRepository
                .findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqual(date, date)
                .orElseGet(() -> {
                    FinancialYearWindow window = windowFor(date);
                    return financialYearRepository.findByStartDate(window.startDate())
                            .orElseGet(() -> financialYearRepository.save(
                                    new FinancialYear(window.startDate(), window.endDate(), window.label())));
                });
    }

    /** The financial year that contains today (per the injected {@link Clock}), get-or-create. */
    @Transactional
    public FinancialYear currentFinancialYear() {
        return financialYearFor(LocalDate.now(clock));
    }

    /**
     * Marks the financial year closed so posting into it is rejected (Req 4.3). Idempotent: closing an
     * already-closed year leaves it closed and does not overwrite the original close metadata.
     *
     * @param financialYearId the financial year to close
     * @return the closed financial year
     */
    @Transactional
    public FinancialYear close(Long financialYearId) {
        FinancialYear fy = requireFinancialYear(financialYearId);
        if (!fy.isClosed()) {
            fy.setClosed(true);
            fy.setClosedAt(LocalDateTime.now(clock));
            currentUserService.currentUser().ifPresent(principal -> fy.setClosedBy(principal.username()));
            financialYearRepository.save(fy);
        }
        return fy;
    }

    /** Whether the given financial year is closed (Req 4.3). */
    @Transactional(readOnly = true)
    public boolean isClosed(Long financialYearId) {
        return requireFinancialYear(financialYearId).isClosed();
    }

    /**
     * Whether the financial year containing {@code date} is closed (Req 4.3); {@code false} when no
     * financial year exists for the date yet (nothing to reject against). Used by posting to reject a
     * voucher whose date falls within a closed year.
     */
    @Transactional(readOnly = true)
    public boolean isClosedOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return financialYearRepository
                .findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqual(date, date)
                .map(FinancialYear::isClosed)
                .orElse(false);
    }

    /**
     * Opens the financial year following {@code financialYearId} and carries each ledger account's
     * closing balance in the source year forward as that account's opening balance in the next year
     * (Req 3.4). The next year is the one beginning the day after the source year's end date
     * (get-or-create).
     *
     * <p>For every ledger account that has either an opening balance or any posted voucher line in the
     * source year, the closing signed balance = opening signed balance + net signed movement of the
     * source year's posted lines (using the account's nature). Accounts whose closing balance is zero
     * carry nothing forward. Existing opening balances in the next year are overwritten so re-running
     * is convergent.
     *
     * @param financialYearId the source financial year whose closing balances are carried forward
     * @return the next financial year (now carrying the opening balances)
     */
    @Transactional
    public FinancialYear openNext(Long financialYearId) {
        FinancialYear source = requireFinancialYear(financialYearId);
        FinancialYear next = financialYearFor(source.getEndDate().plusDays(1));

        List<Long> voucherIds = voucherRepository.findByFinancialYearId(source.getId()).stream()
                .map(Voucher::getId)
                .toList();
        Map<Long, List<VoucherLine>> linesByLedger = (voucherIds.isEmpty()
                ? List.<VoucherLine>of()
                : voucherLineRepository.findByVoucherIdIn(voucherIds)).stream()
                .collect(Collectors.groupingBy(VoucherLine::getLedgerAccountId));

        Map<Long, OpeningBalance> openingByLedger = openingBalanceRepository
                .findByFinancialYearId(source.getId()).stream()
                .collect(Collectors.toMap(OpeningBalance::getLedgerAccountId, Function.identity()));

        Set<Long> ledgerIds = new LinkedHashSet<>();
        ledgerIds.addAll(openingByLedger.keySet());
        ledgerIds.addAll(linesByLedger.keySet());

        for (Long ledgerId : ledgerIds) {
            AccountNature nature = natureOf(ledgerId);

            BigDecimal signed = BigDecimal.ZERO;
            OpeningBalance opening = openingByLedger.get(ledgerId);
            if (opening != null && opening.getAmount() != null) {
                signed = signed.add(BalanceMath.signedDelta(nature, opening.getSide(), opening.getAmount()));
            }
            for (VoucherLine line : linesByLedger.getOrDefault(ledgerId, List.of())) {
                DrCr side = line.getDebit() != null ? DrCr.DEBIT : DrCr.CREDIT;
                BigDecimal amount = line.getDebit() != null ? line.getDebit() : line.getCredit();
                if (amount != null) {
                    signed = signed.add(BalanceMath.signedDelta(nature, side, amount));
                }
            }
            signed = signed.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            SidedBalance closing = BalanceMath.closingSide(nature, signed);
            if (closing.magnitude().signum() <= 0) {
                continue;
            }
            OpeningBalance carried = openingBalanceRepository
                    .findByLedgerAccountIdAndFinancialYearId(ledgerId, next.getId())
                    .orElse(null);
            if (carried != null) {
                carried.setAmount(closing.magnitude());
                carried.setSide(closing.side());
                openingBalanceRepository.save(carried);
            } else {
                openingBalanceRepository.save(
                        new OpeningBalance(ledgerId, next.getId(), closing.magnitude(), closing.side()));
            }
        }
        return next;
    }

    /**
     * Resolves a reporting period from either a financial-year id or an explicit {@code from}/{@code to}
     * date range (Req 4.4), for the Ledger view, Day Book, and Trial Balance. A financial-year id takes
     * precedence when both are supplied.
     *
     * @param financialYearId a financial year id, or {@code null}
     * @param from            the range start, or {@code null}
     * @param to              the range end, or {@code null}
     * @return the resolved {@code (from, to, financialYearId)} period
     * @throws ValidationException when neither a financial year nor a complete date range is supplied,
     *                             or the range end precedes its start
     */
    @Transactional(readOnly = true)
    public Period resolvePeriod(Long financialYearId, LocalDate from, LocalDate to) {
        if (financialYearId != null) {
            FinancialYear fy = requireFinancialYear(financialYearId);
            return new Period(fy.getStartDate(), fy.getEndDate(), fy.getId());
        }
        if (from != null && to != null) {
            if (to.isBefore(from)) {
                throw new ValidationException("The period end date must not be before the start date.");
            }
            return new Period(from, to, null);
        }
        throw new ValidationException("A financial year id or an explicit from/to date range is required.");
    }

    private FinancialYear requireFinancialYear(Long financialYearId) {
        Objects.requireNonNull(financialYearId, "financialYearId");
        return financialYearRepository.findById(financialYearId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Financial year " + financialYearId + " was not found."));
    }

    private AccountNature natureOf(Long ledgerAccountId) {
        LedgerAccount ledger = ledgerAccountRepository.findById(ledgerAccountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Ledger account " + ledgerAccountId + " was not found."));
        AccountGroup group = accountGroupRepository.findById(ledger.getAccountGroupId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Account group " + ledger.getAccountGroupId() + " was not found."));
        return group.getNature();
    }

    /**
     * The pure {@code (startDate, endDate, label)} window of an Indian financial year (Req 4.1).
     *
     * @param startDate 1 April of the start year
     * @param endDate   31 March of the following year
     * @param label     the human-readable label, e.g. {@code "2025-26"}
     */
    public record FinancialYearWindow(LocalDate startDate, LocalDate endDate, String label) {
    }

    /**
     * A resolved reporting period (Req 4.4): the inclusive date range plus the financial-year id it was
     * resolved from (or {@code null} when resolved from an explicit date range).
     *
     * @param from            the inclusive start date
     * @param to              the inclusive end date
     * @param financialYearId the financial year the period was resolved from, or {@code null}
     */
    public record Period(LocalDate from, LocalDate to, Long financialYearId) {
    }
}
