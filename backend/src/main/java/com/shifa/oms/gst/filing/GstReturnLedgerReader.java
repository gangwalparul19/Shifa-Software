package com.shifa.oms.gst.filing;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.gst.filing.domain.ReturnPeriod;
import com.shifa.oms.ledger.AccountGroup;
import com.shifa.oms.ledger.AccountGroupRepository;
import com.shifa.oms.ledger.LedgerAccount;
import com.shifa.oms.ledger.VoucherLine;
import com.shifa.oms.ledger.VoucherLineRepository;
import com.shifa.oms.ledger.autopost.ControlAccount;
import com.shifa.oms.ledger.autopost.ControlAccountResolver;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath;
import com.shifa.oms.ledger.domain.DrCr;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads a GST control ledger's <strong>net signed movement</strong> over a return-period window, for
 * the Phase-3 GST returns reconciliation (GST returns &amp; filing, Reqs 7.2, 7.6, 8.5).
 *
 * <p>Given a {@link ControlAccount} — either {@link ControlAccount#GST_OUTPUT} (output-tax liability)
 * or {@link ControlAccount#GST_INPUT} (input-tax-credit asset) — and a {@link ReturnPeriod}, it:
 * <ol>
 *   <li>resolves the concrete ledger currently mapped to that control role via
 *       {@link ControlAccountResolver#resolveLedger(ControlAccount)} and derives the account's
 *       {@link AccountNature} from its owning {@link AccountGroup} (ledgers store no nature, it is
 *       derived from the group — the same rule {@code TrialBalanceService} follows);</li>
 *   <li>loads every posted line whose parent voucher's date falls within the period window
 *       {@code [firstDay 00:00:00, lastDay 23:59:59]} via
 *       {@link VoucherLineRepository#findForPeriod(LocalDate, LocalDate)} and keeps only the lines on
 *       that ledger;</li>
 *   <li>sums {@link BalanceMath#signedDelta(AccountNature, DrCr, BigDecimal)} across those lines —
 *       the identical sign convention the Trial Balance uses — rounded to money scale (2, HALF_UP).</li>
 * </ol>
 *
 * <p>The window is the calendar month: the voucher date is a {@link LocalDate}, so the inclusive
 * {@code [firstDay, lastDay]} range covers {@code firstDay 00:00:00} through {@code lastDay 23:59:59}.
 *
 * <p><strong>Availability distinction (Reqs 7.6, 8.5).</strong> A period with no movement returns a
 * signed sum of {@code 0.00} and is still {@linkplain LedgerMovement#available() available} — the
 * reconciliation continues down the reconciled path. When the control ledger is <em>not mapped</em>
 * (no {@code ledger_accounts.control_key} row) or its owning group is missing — i.e. the GL is
 * unavailable — this returns {@code 0.00} but flags the movement <em>unavailable</em> so the caller
 * ({@code ReconciliationService}) can omit that comparison (set {@code comparisonAvailable = false})
 * rather than failing the request.
 *
 * <p>Read-only ({@code @Transactional(readOnly = true)}); posts nothing to the ledger (advisory, A5).
 */
@Service
@Transactional(readOnly = true)
public class GstReturnLedgerReader {

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final ControlAccountResolver controlAccountResolver;
    private final VoucherLineRepository voucherLineRepository;
    private final AccountGroupRepository accountGroupRepository;

    public GstReturnLedgerReader(ControlAccountResolver controlAccountResolver,
                                 VoucherLineRepository voucherLineRepository,
                                 AccountGroupRepository accountGroupRepository) {
        this.controlAccountResolver = controlAccountResolver;
        this.voucherLineRepository = voucherLineRepository;
        this.accountGroupRepository = accountGroupRepository;
    }

    /**
     * The net signed movement of the given control ledger over the return period {@code (month, year)}.
     *
     * @param control the GST control role — {@link ControlAccount#GST_OUTPUT} or
     *                {@link ControlAccount#GST_INPUT}
     * @param month   the calendar month, 1–12
     * @param year    the four-digit calendar year
     * @return the signed net movement (2dp) with an availability flag
     */
    public LedgerMovement netMovement(ControlAccount control, int month, int year) {
        return netMovement(control, new ReturnPeriod(month, year));
    }

    /**
     * The net signed movement of the given control ledger over the given return period window
     * {@code [firstDay 00:00:00, lastDay 23:59:59]} (Reqs 7.2, 7.6, 8.5).
     *
     * @param control the GST control role — {@link ControlAccount#GST_OUTPUT} or
     *                {@link ControlAccount#GST_INPUT}
     * @param period  the return period whose calendar month bounds the window
     * @return the signed net movement (2dp, {@code 0.00} when there is no movement) with an
     *         {@linkplain LedgerMovement#available() availability} flag distinguishing "no movement"
     *         (available) from "GL/ledger unavailable" (unavailable, comparison omitted)
     */
    public LedgerMovement netMovement(ControlAccount control, ReturnPeriod period) {
        final LedgerAccount ledger;
        final AccountNature nature;
        try {
            ledger = controlAccountResolver.resolveLedger(control);
            AccountGroup group = accountGroupRepository.findById(ledger.getAccountGroupId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Account group " + ledger.getAccountGroupId() + " was not found."));
            nature = group.getNature();
        } catch (ResourceNotFoundException unavailable) {
            // The control ledger is not mapped (or its group is missing): the GL is unavailable for
            // this figure. Report 0.00 and flag unavailable so the comparison is omitted (Req 8.5).
            return LedgerMovement.unavailable();
        }

        LocalDate firstDay = period.yearMonth().atDay(1);
        LocalDate lastDay = period.yearMonth().atEndOfMonth();

        Long ledgerId = ledger.getId();
        List<VoucherLine> lines = voucherLineRepository.findForPeriod(firstDay, lastDay);

        BigDecimal signedSum = BigDecimal.ZERO;
        for (VoucherLine line : lines) {
            if (!ledgerId.equals(line.getLedgerAccountId())) {
                continue;
            }
            DrCr side = line.getDebit() != null ? DrCr.DEBIT : DrCr.CREDIT;
            BigDecimal amount = line.getDebit() != null ? line.getDebit() : line.getCredit();
            if (amount != null) {
                signedSum = signedSum.add(BalanceMath.signedDelta(nature, side, amount));
            }
        }

        return LedgerMovement.of(signedSum.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * The net signed movement of a control ledger over a period window, plus whether the ledger was
     * available to read (GST returns &amp; filing, Req 8.5).
     *
     * <p>{@code available == true} with any {@code movement} (including {@code 0.00}) means the ledger
     * was read successfully and the figure participates in reconciliation. {@code available == false}
     * (always {@code movement == 0.00}) means the control ledger is unmapped / the GL is unavailable,
     * so the caller omits that comparison rather than treating a spurious {@code 0.00} as reconciled.
     *
     * @param movement  the signed net movement at money scale (2, HALF_UP); {@code 0.00} when
     *                  unavailable or when there is genuinely no movement
     * @param available whether the control ledger was resolvable and read
     */
    public record LedgerMovement(BigDecimal movement, boolean available) {

        /** An available movement carrying the given signed sum. */
        public static LedgerMovement of(BigDecimal movement) {
            return new LedgerMovement(movement, true);
        }

        /** The unavailable sentinel: {@code 0.00} with {@code available == false} (Req 8.5). */
        public static LedgerMovement unavailable() {
            return new LedgerMovement(ZERO_MONEY, false);
        }
    }
}
