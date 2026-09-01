package com.shifa.oms.gst.filing;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.gst.GstAccountingService;
import com.shifa.oms.gst.domain.GstEngine;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.dto.GstOrderRow;
import com.shifa.oms.gst.filing.GstReturnLedgerReader.LedgerMovement;
import com.shifa.oms.gst.filing.domain.ReconciliationFigure;
import com.shifa.oms.gst.filing.domain.ReconciliationMath;
import com.shifa.oms.gst.filing.domain.ReconciliationTolerance;
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
import com.shifa.oms.ledger.statements.ProfitAndLossResult;
import com.shifa.oms.ledger.statements.ProfitAndLossService;
import com.shifa.oms.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The advisory <strong>GST returns reconciliation engine</strong> (GST returns &amp; filing,
 * Reqs 7, 8, 9). It ties a return period's figures — sourced from the single figure source
 * {@link FilingAwareGstr1Provider} — back to the Phase-1 General Ledger control ledgers
 * ({@link ControlAccount#GST_OUTPUT} / {@link ControlAccount#GST_INPUT}) and the Phase-2
 * {@link ProfitAndLossService} revenue, so the CA can see where the return and the books agree or
 * diverge before filing.
 *
 * <p>{@link #reconcile(int, int)} produces a {@link ReconciliationSummary} of the five compared
 * figures (Req 9.1):
 * <ol>
 *   <li><strong>GSTR-1 total output tax</strong> ({@code cgst + sgst + igst} from the provider's
 *       {@link Gstr1Return#reconciliation() reconciliation} summary) vs the {@code GST_OUTPUT} ledger
 *       movement (Reqs 7.1–7.5);</li>
 *   <li><strong>GSTR-3B output tax</strong> (the same period's output-tax total) vs
 *       {@code GST_OUTPUT} (Req 8.1);</li>
 *   <li><strong>GSTR-3B ITC</strong> vs the {@code GST_INPUT} ledger movement (Req 8.2). The
 *       reconciliation summary carries no ITC (the CA enters ITC on the dashboard), so the return-side
 *       ITC is {@code 0.00} here;</li>
 *   <li><strong>Net GST payable</strong> {@code floorZero(output − ITC)} vs the ledger net liability
 *       {@code floorZero(GST_OUTPUT − GST_INPUT)} (Req 8.3);</li>
 *   <li><strong>Taxable outward turnover</strong> vs {@link ProfitAndLossService} revenue for the same
 *       window (Req 8.4).</li>
 * </ol>
 *
 * <p>Each figure is built by {@link ReconciliationMath#figure(String, BigDecimal, BigDecimal, BigDecimal)}
 * using the configured {@link ReconciliationTolerance} (from
 * {@code app_settings.gst_reconciliation_tolerance}). When a ledger movement is unavailable
 * ({@link LedgerMovement#available()} {@code == false}) or the P&amp;L cannot be computed, the affected
 * comparison is <strong>omitted</strong> ({@code comparisonAvailable = false}) rather than failing the
 * request (Reqs 7.6, 8.5); the return figure is still presented. The period is reconciled iff every
 * <em>compared</em> figure is reconciled (Reqs 9.2, 9.6). An empty period yields a zero-on-both-sides
 * summary (Req 9.5).
 *
 * <p>{@link #drillDown(int, int, String)} lists the rows contributing to a figure (Req 9.3):
 * return-backed figures reuse {@link GstAccountingService#orders(LocalDate, LocalDate, String,
 * BigDecimal, String)}; ledger-backed figures list the period's {@link VoucherLine}s on the control
 * ledger. An unknown figure or a figure with no contributors yields an empty list, never an error
 * (Req 9.7).
 *
 * <p>Read-only and <strong>advisory</strong>: it posts nothing to the ledger and never blocks filing
 * (Reqs 7.7, 10.3, A5). Follows the {@code Clock} dual-constructor convention (with {@code @Autowired}
 * on the primary constructor) so the default month can be resolved for callers that omit it.
 */
@Service("gstFilingReconciliationService")
@Transactional(readOnly = true)
public class ReconciliationService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /** Stable figure keys for the five compared figures (drill-down routing, DTO mapping). */
    public static final String KEY_GSTR1_OUTPUT_TAX = "GSTR1_OUTPUT_TAX";
    public static final String KEY_GSTR3B_OUTPUT_TAX = "GSTR3B_OUTPUT_TAX";
    public static final String KEY_GSTR3B_ITC = "GSTR3B_ITC";
    public static final String KEY_NET_GST_PAYABLE = "NET_GST_PAYABLE";
    public static final String KEY_TAXABLE_OUTWARD_TURNOVER = "TAXABLE_OUTWARD_TURNOVER";

    private final FilingAwareGstr1Provider gstr1Provider;
    private final GstReturnLedgerReader ledgerReader;
    private final ProfitAndLossService profitAndLossService;
    private final GstAccountingService gstAccountingService;
    private final SettingsService settingsService;
    private final ControlAccountResolver controlAccountResolver;
    private final VoucherLineRepository voucherLineRepository;
    private final AccountGroupRepository accountGroupRepository;
    private final Clock clock;

    @Autowired
    public ReconciliationService(FilingAwareGstr1Provider gstr1Provider,
                                 GstReturnLedgerReader ledgerReader,
                                 ProfitAndLossService profitAndLossService,
                                 GstAccountingService gstAccountingService,
                                 SettingsService settingsService,
                                 ControlAccountResolver controlAccountResolver,
                                 VoucherLineRepository voucherLineRepository,
                                 AccountGroupRepository accountGroupRepository) {
        this(gstr1Provider, ledgerReader, profitAndLossService, gstAccountingService, settingsService,
                controlAccountResolver, voucherLineRepository, accountGroupRepository, Clock.system(ZONE));
    }

    ReconciliationService(FilingAwareGstr1Provider gstr1Provider,
                          GstReturnLedgerReader ledgerReader,
                          ProfitAndLossService profitAndLossService,
                          GstAccountingService gstAccountingService,
                          SettingsService settingsService,
                          ControlAccountResolver controlAccountResolver,
                          VoucherLineRepository voucherLineRepository,
                          AccountGroupRepository accountGroupRepository,
                          Clock clock) {
        this.gstr1Provider = gstr1Provider;
        this.ledgerReader = ledgerReader;
        this.profitAndLossService = profitAndLossService;
        this.gstAccountingService = gstAccountingService;
        this.settingsService = settingsService;
        this.controlAccountResolver = controlAccountResolver;
        this.voucherLineRepository = voucherLineRepository;
        this.accountGroupRepository = accountGroupRepository;
        this.clock = clock;
    }

    /**
     * Reconciles the return period {@code (month, year)}: builds the five compared figures against the
     * GST control ledgers and the P&amp;L revenue, marking each figure (and the period) reconciled /
     * unreconciled and omitting any comparison whose ledger or statement source is unavailable
     * (Reqs 7, 8, 9.1, 9.2, 9.5, 9.6).
     *
     * @param month the calendar month, 1–12
     * @param year  the four-digit calendar year
     * @return the reconciliation summary (advisory — nothing is posted to the ledger)
     */
    public ReconciliationSummary reconcile(int month, int year) {
        return reconcile(new ReturnPeriod(month, year));
    }

    /**
     * Reconciles a return period, defaulting to the <strong>current month</strong> (from the injected
     * {@link Clock}) when {@code month} or {@code year} is {@code null} — so the controller can expose
     * optional {@code month}/{@code year} query parameters (design: {@code Clock} dual-constructor for
     * the default month).
     *
     * @param month the calendar month 1–12, or {@code null} for the current month
     * @param year  the four-digit calendar year, or {@code null} for the current year
     * @return the reconciliation summary for the resolved period
     */
    public ReconciliationSummary reconcile(Integer month, Integer year) {
        return reconcile(resolvePeriod(month, year));
    }

    private ReconciliationSummary reconcile(ReturnPeriod period) {
        BigDecimal tolerance = ReconciliationTolerance
                .of(settingsService.getSettings().getGstReconciliationTolerance()).amount();

        // Single figure source: snapshot when FILED, computed otherwise.
        GstEngine.Gstr3bSummary summary = gstr1Provider.forPeriod(period).reconciliation();
        BigDecimal gstr1OutputTax = money(summary.outputCgst())
                .add(money(summary.outputSgst()))
                .add(money(summary.outputIgst()));
        BigDecimal gstr3bOutputTax = money(summary.outputTotal());
        // The reconciliation summary carries no ITC — the CA enters it on the dashboard (design).
        BigDecimal gstr3bItc = ZERO_MONEY;
        BigDecimal netPayableReturn = ReconciliationMath.floorZero(gstr3bOutputTax.subtract(gstr3bItc));
        BigDecimal taxableOutward = money(summary.taxableOutward());

        // Ledger movements over the period window.
        LedgerMovement gstOutput = ledgerReader.netMovement(ControlAccount.GST_OUTPUT, period);
        LedgerMovement gstInput = ledgerReader.netMovement(ControlAccount.GST_INPUT, period);
        boolean netLedgerAvailable = gstOutput.available() && gstInput.available();
        BigDecimal ledgerNetLiability = ReconciliationMath
                .floorZero(money(gstOutput.movement()).subtract(money(gstInput.movement())));

        // P&L revenue for the same window (Req 8.4); omitted if unavailable.
        PlRevenue plRevenue = profitAndLossRevenue(period);

        List<ComparedFigure> figures = new ArrayList<>(5);
        figures.add(new ComparedFigure(KEY_GSTR1_OUTPUT_TAX,
                ReconciliationMath.figure("GSTR-1 total output tax", gstr1OutputTax,
                        money(gstOutput.movement()), tolerance),
                gstOutput.available()));
        figures.add(new ComparedFigure(KEY_GSTR3B_OUTPUT_TAX,
                ReconciliationMath.figure("GSTR-3B output tax", gstr3bOutputTax,
                        money(gstOutput.movement()), tolerance),
                gstOutput.available()));
        figures.add(new ComparedFigure(KEY_GSTR3B_ITC,
                ReconciliationMath.figure("GSTR-3B input tax credit (ITC)", gstr3bItc,
                        money(gstInput.movement()), tolerance),
                gstInput.available()));
        figures.add(new ComparedFigure(KEY_NET_GST_PAYABLE,
                ReconciliationMath.figure("Net GST payable", netPayableReturn,
                        ledgerNetLiability, tolerance),
                netLedgerAvailable));
        figures.add(new ComparedFigure(KEY_TAXABLE_OUTWARD_TURNOVER,
                ReconciliationMath.figure("Taxable outward turnover", taxableOutward,
                        plRevenue.revenue(), tolerance),
                plRevenue.available()));

        boolean periodReconciled = figures.stream()
                .filter(f -> f.comparisonAvailable())
                .allMatch(f -> f.figure().reconciled());

        return new ReconciliationSummary(period.month(), period.year(), periodReconciled, figures);
    }

    /** Resolves a nullable month/year to a concrete period, defaulting to the current month (clock). */
    private ReturnPeriod resolvePeriod(Integer month, Integer year) {
        LocalDate today = LocalDate.now(clock);
        int m = month != null ? month : today.getMonthValue();
        int y = year != null ? year : today.getYear();
        return new ReturnPeriod(m, y);
    }

    /**
     * The rows contributing to a compared figure for the period, for the reconciliation drill-down
     * (Req 9.3). Return-backed figures (GSTR-1 / GSTR-3B output tax, taxable outward turnover) list the
     * period's contributing orders; ledger-backed figures (ITC, net payable) list the period's posted
     * {@link VoucherLine}s on the relevant control ledger. An unknown {@code figureKey} or a figure
     * with no contributors yields an empty list, never an error (Reqs 9.3, 9.7).
     *
     * @param month     the calendar month, 1–12
     * @param year      the four-digit calendar year
     * @param figureKey one of the {@code KEY_*} figure keys
     * @return the contributing rows (identifier + type + signed contribution); empty when none
     */
    public List<DrillDownRow> drillDown(int month, int year, String figureKey) {
        ReturnPeriod period = new ReturnPeriod(month, year);
        if (figureKey == null) {
            return List.of();
        }
        return switch (figureKey) {
            case KEY_GSTR1_OUTPUT_TAX, KEY_GSTR3B_OUTPUT_TAX -> orderRows(period, false);
            case KEY_TAXABLE_OUTWARD_TURNOVER -> orderRows(period, true);
            case KEY_GSTR3B_ITC -> ledgerRows(ControlAccount.GST_INPUT, period);
            case KEY_NET_GST_PAYABLE -> ledgerRows(ControlAccount.GST_OUTPUT, period);
            default -> List.of();
        };
    }

    /**
     * The contributing orders for a return-backed figure, one row per order, its signed contribution
     * being the order's tax (or taxable value when {@code taxableContribution}).
     */
    private List<DrillDownRow> orderRows(ReturnPeriod period, boolean taxableContribution) {
        LocalDate from = period.yearMonth().atDay(1);
        LocalDate to = period.yearMonth().atEndOfMonth();
        List<GstOrderRow> orders = gstAccountingService.orders(from, to, null, null, null);
        List<DrillDownRow> rows = new ArrayList<>(orders.size());
        for (GstOrderRow o : orders) {
            BigDecimal contribution = money(taxableContribution ? o.taxable() : o.tax());
            rows.add(new DrillDownRow(o.orderCode(), "ORDER", contribution));
        }
        return rows;
    }

    /**
     * The posted voucher lines on a control ledger over the period window, one row per line, its signed
     * contribution being {@link BalanceMath#signedDelta(AccountNature, DrCr, BigDecimal)}. Returns an
     * empty list (not an error) when the control ledger is unmapped / the GL is unavailable (Req 9.7).
     */
    private List<DrillDownRow> ledgerRows(ControlAccount control, ReturnPeriod period) {
        final LedgerAccount ledger;
        final AccountNature nature;
        try {
            ledger = controlAccountResolver.resolveLedger(control);
            AccountGroup group = accountGroupRepository.findById(ledger.getAccountGroupId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Account group " + ledger.getAccountGroupId() + " was not found."));
            nature = group.getNature();
        } catch (ResourceNotFoundException unavailable) {
            return List.of();
        }

        LocalDate firstDay = period.yearMonth().atDay(1);
        LocalDate lastDay = period.yearMonth().atEndOfMonth();
        Long ledgerId = ledger.getId();

        List<DrillDownRow> rows = new ArrayList<>();
        for (VoucherLine line : voucherLineRepository.findForPeriod(firstDay, lastDay)) {
            if (!Objects.equals(ledgerId, line.getLedgerAccountId())) {
                continue;
            }
            DrCr side = line.getDebit() != null ? DrCr.DEBIT : DrCr.CREDIT;
            BigDecimal amount = line.getDebit() != null ? line.getDebit() : line.getCredit();
            if (amount == null) {
                continue;
            }
            BigDecimal contribution = money(BalanceMath.signedDelta(nature, side, amount));
            rows.add(new DrillDownRow("VL-" + line.getId(), "VOUCHER_LINE", contribution));
        }
        return rows;
    }

    /**
     * The Phase-2 P&amp;L revenue (total income) for the period window (Req 8.4), with an availability
     * flag: when the P&amp;L cannot be computed (GL/statements unavailable) the comparison is omitted
     * rather than failing (Req 8.5).
     */
    private PlRevenue profitAndLossRevenue(ReturnPeriod period) {
        YearMonth ym = period.yearMonth();
        try {
            ProfitAndLossResult result =
                    profitAndLossService.profitAndLoss(null, ym.atDay(1), ym.atEndOfMonth(), false);
            return new PlRevenue(money(result.statement().totalIncome()), true);
        } catch (RuntimeException unavailable) {
            return new PlRevenue(ZERO_MONEY, false);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO_MONEY : value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * A reconciliation summary for a return period: the five compared figures and whether the period is
     * fully reconciled (every compared figure within tolerance — Reqs 9.1, 9.2, 9.6).
     *
     * @param month            the calendar month, 1–12
     * @param year             the four-digit calendar year
     * @param periodReconciled {@code true} iff every {@linkplain ComparedFigure#comparisonAvailable
     *                         compared} figure is reconciled
     * @param figures          the five compared figures in presentation order
     */
    public record ReconciliationSummary(int month, int year, boolean periodReconciled,
                                        List<ComparedFigure> figures) {
    }

    /**
     * One compared figure in a {@link ReconciliationSummary}: the pure {@link ReconciliationFigure}
     * arithmetic plus its stable {@code key} and whether the comparison was available (the ledger /
     * statement source could be read — Req 8.5). When {@code comparisonAvailable} is {@code false} the
     * figure's ledger value and difference are not meaningful and the figure is excluded from the
     * period-reconciled determination.
     *
     * @param key                 the stable figure key (one of the {@code KEY_*} constants)
     * @param figure              the compared-figure arithmetic
     * @param comparisonAvailable whether the ledger / statement source was available
     */
    public record ComparedFigure(String key, ReconciliationFigure figure, boolean comparisonAvailable) {
    }

    /**
     * One contributing row behind a reconciliation figure (Req 9.3): a business identifier (order code
     * or voucher-line ref), its {@code type}, and its signed contribution to the figure.
     *
     * @param identifier         the contributing document's identifier
     * @param type               the contributor type ({@code "ORDER"} or {@code "VOUCHER_LINE"})
     * @param signedContribution the signed amount this row contributes to the figure
     */
    public record DrillDownRow(String identifier, String type, BigDecimal signedContribution) {
    }

    /** Internal carrier for the P&amp;L revenue and whether it could be computed. */
    private record PlRevenue(BigDecimal revenue, boolean available) {
    }
}
