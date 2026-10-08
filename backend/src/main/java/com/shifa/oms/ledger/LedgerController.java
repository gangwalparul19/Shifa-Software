package com.shifa.oms.ledger;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditEventRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.ledger.FinancialYearService.Period;
import com.shifa.oms.ledger.dto.AccountGroupRequest;
import com.shifa.oms.ledger.dto.AccountGroupResponse;
import com.shifa.oms.ledger.dto.DayBookResponse;
import com.shifa.oms.ledger.dto.FinancialYearResponse;
import com.shifa.oms.ledger.dto.LedgerAccountRequest;
import com.shifa.oms.ledger.dto.LedgerAccountResponse;
import com.shifa.oms.ledger.dto.LedgerStatementResponse;
import com.shifa.oms.ledger.dto.OpeningBalanceRequest;
import com.shifa.oms.ledger.dto.OpeningBalanceResponse;
import com.shifa.oms.ledger.dto.PostVoucherRequest;
import com.shifa.oms.ledger.dto.TrialBalanceResponse;
import com.shifa.oms.ledger.dto.VoucherAuditResponse;
import com.shifa.oms.ledger.dto.VoucherResponse;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.VoucherType;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for the General Ledger — the Chart of Accounts, financial years, opening balances,
 * double-entry vouchers, and the statutory read views (Ledger statement, Day Book, Trial Balance) —
 * under {@code /api/accounting/**} (Reqs 1–18).
 *
 * <p><strong>Role-based access (Req 16).</strong> The whole module is restricted to the finance
 * roles by the class-level {@code @PreAuthorize} — ADMIN, ACCOUNTANT, and CA may <em>read</em> every
 * endpoint (Reqs 16.1, 16.3, 18.1); any other role is denied (Req 16.2). Every <em>mutating</em>
 * endpoint (posting/reversing vouchers, editing the Chart of Accounts, recording opening balances,
 * closing a financial year) additionally carries a method-level
 * {@code @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")}, which overrides the class rule so the CA
 * role is <strong>read-only</strong> (Reqs 16.4, 16.5).
 *
 * <p>Each read view accepts <em>either</em> a {@code ?financialYearId=} <em>or</em> a
 * {@code ?from=&to=} date range (Req 4.4), and the Day Book additionally an optional
 * {@code ?voucherType=} filter parsed strictly via {@link VoucherType#fromName(String)} (Req 13.3).
 * The controller only adapts HTTP to the services + task-10.1 DTOs; all accounting law lives in the
 * services and the pure {@code ledger.domain} core.
 */
@RestController
@RequestMapping("/api/accounting")
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','CA')")
public class LedgerController {

    private static final String POST_PERMISSION = "hasAnyRole('ADMIN','ACCOUNTANT')";

    private final ChartOfAccountsService chartOfAccountsService;
    private final FinancialYearService financialYearService;
    private final OpeningBalanceService openingBalanceService;
    private final VoucherService voucherService;
    private final DayBookService dayBookService;
    private final LedgerViewService ledgerViewService;
    private final TrialBalanceService trialBalanceService;
    private final ReportPeriodResolver reportPeriodResolver;
    private final AccountGroupRepository accountGroupRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final FinancialYearRepository financialYearRepository;
    private final OpeningBalanceRepository openingBalanceRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherLineRepository voucherLineRepository;
    private final AuditEventRepository auditEventRepository;

    public LedgerController(ChartOfAccountsService chartOfAccountsService,
                            FinancialYearService financialYearService,
                            OpeningBalanceService openingBalanceService,
                            VoucherService voucherService,
                            DayBookService dayBookService,
                            LedgerViewService ledgerViewService,
                            TrialBalanceService trialBalanceService,
                            ReportPeriodResolver reportPeriodResolver,
                            AccountGroupRepository accountGroupRepository,
                            LedgerAccountRepository ledgerAccountRepository,
                            FinancialYearRepository financialYearRepository,
                            OpeningBalanceRepository openingBalanceRepository,
                            VoucherRepository voucherRepository,
                            VoucherLineRepository voucherLineRepository,
                            AuditEventRepository auditEventRepository) {
        this.chartOfAccountsService = chartOfAccountsService;
        this.financialYearService = financialYearService;
        this.openingBalanceService = openingBalanceService;
        this.voucherService = voucherService;
        this.dayBookService = dayBookService;
        this.ledgerViewService = ledgerViewService;
        this.trialBalanceService = trialBalanceService;
        this.reportPeriodResolver = reportPeriodResolver;
        this.accountGroupRepository = accountGroupRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.financialYearRepository = financialYearRepository;
        this.openingBalanceRepository = openingBalanceRepository;
        this.voucherRepository = voucherRepository;
        this.voucherLineRepository = voucherLineRepository;
        this.auditEventRepository = auditEventRepository;
    }

    // --- Chart of Accounts: account groups (Req 1) --------------------------

    /** Lists the account groups, ordered by nature then name (view; Reqs 1, 18.1). */
    @GetMapping("/account-groups")
    public List<AccountGroupResponse> listAccountGroups() {
        return accountGroupRepository.findAllByOrderByNatureAscNameAsc().stream()
                .map(AccountGroupResponse::from)
                .toList();
    }

    /** Creates an account group (post; Reqs 1.2–1.5). CA is denied by the method-level rule. */
    @PostMapping("/account-groups")
    @PreAuthorize(POST_PERMISSION)
    public AccountGroupResponse createAccountGroup(@Valid @RequestBody AccountGroupRequest request) {
        AccountGroup group = chartOfAccountsService.createGroup(
                request.name(), request.nature(), request.parentGroupId());
        return AccountGroupResponse.from(group);
    }

    // --- Chart of Accounts: ledger accounts (Req 2) -------------------------

    /**
     * Lists the ledger accounts ordered by name, each with the {@link AccountNature} derived from its
     * owning account group (Req 2.2; view). Natures are resolved from a single group lookup to avoid
     * an N+1.
     */
    @GetMapping("/ledgers")
    public List<LedgerAccountResponse> listLedgers() {
        Map<Long, AccountNature> natureByGroup = accountGroupRepository.findAll().stream()
                .collect(Collectors.toMap(AccountGroup::getId, AccountGroup::getNature));
        return ledgerAccountRepository.findAllByOrderByNameAsc().stream()
                .map(ledger -> LedgerAccountResponse.from(ledger, natureByGroup.get(ledger.getAccountGroupId())))
                .toList();
    }

    /** Creates a ledger account under an account group (post; Reqs 2.1–2.3). */
    @PostMapping("/ledgers")
    @PreAuthorize(POST_PERMISSION)
    public LedgerAccountResponse createLedger(@Valid @RequestBody LedgerAccountRequest request) {
        LedgerAccount ledger = chartOfAccountsService.createLedger(request.name(), request.accountGroupId());
        return LedgerAccountResponse.from(ledger, natureOfGroup(ledger.getAccountGroupId()));
    }

    /** Deletes a ledger account with no posted voucher lines (post; Req 2.4). */
    @DeleteMapping("/ledgers/{id}")
    @PreAuthorize(POST_PERMISSION)
    public ResponseEntity<Void> deleteLedger(@PathVariable Long id) {
        chartOfAccountsService.deleteLedger(id);
        return ResponseEntity.noContent().build();
    }

    // --- Financial years (Req 4) --------------------------------------------

    /** Lists the financial years, most recent first (view; Req 4). */
    @GetMapping("/financial-years")
    public List<FinancialYearResponse> listFinancialYears() {
        return financialYearRepository.findAllByOrderByStartDateDesc().stream()
                .map(FinancialYearResponse::from)
                .toList();
    }

    /** Closes a financial year so vouchers dated within it can no longer be posted (post; Req 4.3). */
    @PostMapping("/financial-years/{id}/close")
    @PreAuthorize(POST_PERMISSION)
    public FinancialYearResponse closeFinancialYear(@PathVariable Long id) {
        return FinancialYearResponse.from(financialYearService.close(id));
    }

    // --- Opening balances (Req 3) -------------------------------------------

    /**
     * Lists a financial year's opening balances together with its balancing check (view; Reqs 3.1,
     * 3.3). Accepts {@code ?financialYearId=} or a {@code ?from=&to=} range (Req 4.4), defaulting to
     * the current financial year; a date range is mapped to the financial year covering its start.
     */
    @GetMapping("/opening-balances")
    public OpeningBalancesResponse listOpeningBalances(
            @RequestParam(required = false) Long financialYearId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Period period = reportPeriodResolver.resolve(financialYearId, from, to);
        Long fyId = period.financialYearId() != null
                ? period.financialYearId()
                : financialYearService.financialYearFor(period.from()).getId();

        List<OpeningBalanceResponse> balances = openingBalanceRepository.findByFinancialYearId(fyId).stream()
                .map(OpeningBalanceResponse::from)
                .toList();
        OpeningBalanceResponse.Check check =
                OpeningBalanceResponse.Check.from(openingBalanceService.checkBalance(fyId));
        return new OpeningBalancesResponse(fyId, balances, check);
    }

    /** Records (upserts) a ledger account's opening balance for a financial year (post; Req 3.1). */
    @PostMapping("/opening-balances")
    @PreAuthorize(POST_PERMISSION)
    public OpeningBalanceResponse recordOpeningBalance(@Valid @RequestBody OpeningBalanceRequest request) {
        OpeningBalance opening = openingBalanceService.recordOpeningBalance(
                request.ledgerAccountId(), request.financialYearId(), request.amount(), request.side());
        return OpeningBalanceResponse.from(opening);
    }

    // --- Vouchers: Day Book, detail, post, reverse, audit (Reqs 5, 6, 7, 13, 15) ---

    /**
     * The Day Book — all posted vouchers in the period, chronologically (view; Req 13). Accepts
     * {@code ?financialYearId=} or {@code ?from=&to=} (Req 4.4) and an optional {@code ?voucherType=}
     * filter parsed strictly (Req 13.3).
     */
    @GetMapping("/vouchers")
    public DayBookResponse dayBook(
            @RequestParam(required = false) Long financialYearId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String voucherType) {
        VoucherType type = parseVoucherType(voucherType);
        return DayBookResponse.from(dayBookService.dayBook(financialYearId, from, to, type));
    }

    /** A single posted voucher with its lines (view; Reqs 13, 15.4). */
    @GetMapping("/vouchers/{id}")
    public VoucherResponse getVoucher(@PathVariable Long id) {
        Voucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Voucher " + id + " was not found."));
        return VoucherResponse.from(voucher, voucherLineRepository.findByVoucherIdOrderByLineOrderAsc(id));
    }

    /** Posts a balanced, immutable double-entry voucher (post; Reqs 5, 7). */
    @PostMapping("/vouchers")
    @PreAuthorize(POST_PERMISSION)
    public VoucherResponse postVoucher(@Valid @RequestBody PostVoucherRequest request) {
        Voucher voucher = voucherService.post(request.toCommand());
        return VoucherResponse.from(voucher, voucherLineRepository.findByVoucherIdOrderByLineOrderAsc(voucher.getId()));
    }

    /** Reverses a posted voucher with a balancing reversing voucher (post; Req 6). */
    @PostMapping("/vouchers/{id}/reverse")
    @PreAuthorize(POST_PERMISSION)
    public VoucherResponse reverseVoucher(@PathVariable Long id) {
        Voucher reversing = voucherService.reverse(id);
        return VoucherResponse.from(reversing,
                voucherLineRepository.findByVoucherIdOrderByLineOrderAsc(reversing.getId()));
    }

    /**
     * The audit trail for a voucher in chronological order (view; Reqs 15.4, 18.3): every recorded
     * financial audit event whose entity is this voucher (posting, and — when this is a reversing
     * voucher — its reversal), oldest first.
     */
    @GetMapping("/vouchers/{id}/audit")
    public List<VoucherAuditResponse> voucherAudit(@PathVariable Long id) {
        return auditEventRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(
                        AuditActions.ENTITY_VOUCHER, String.valueOf(id)).stream()
                .map(VoucherAuditResponse::from)
                .toList();
    }

    // --- Read views: Ledger statement, Trial Balance (Reqs 12, 14) ----------

    /**
     * The account statement for a ledger account over a period (view; Reqs 12.1–12.4): opening
     * balance, chronological lines with running balance, and closing balance. Accepts
     * {@code ?financialYearId=} or {@code ?from=&to=} (Req 4.4).
     */
    @GetMapping("/ledgers/{id}/statement")
    public LedgerStatementResponse ledgerStatement(
            @PathVariable Long id,
            @RequestParam(required = false) Long financialYearId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return LedgerStatementResponse.from(ledgerViewService.statement(id, financialYearId, from, to));
    }

    /**
     * The Trial Balance for a period (view; Reqs 14, 18.2): per-account closing balances with the
     * debit/credit totals and their difference. Accepts {@code ?financialYearId=} or {@code ?from=&to=}
     * (Req 4.4).
     */
    @GetMapping("/trial-balance")
    public TrialBalanceResponse trialBalance(
            @RequestParam(required = false) Long financialYearId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return TrialBalanceResponse.from(trialBalanceService.trialBalance(financialYearId, from, to));
    }

    // --- Helpers ------------------------------------------------------------

    /**
     * Strictly parses an optional Day Book voucher-type filter (Req 13.3): a {@code null}/blank value
     * means "all types", while an unsupported name is rejected with a {@link ValidationException}.
     */
    private static VoucherType parseVoucherType(String voucherType) {
        if (voucherType == null || voucherType.isBlank()) {
            return null;
        }
        return VoucherType.fromName(voucherType)
                .orElseThrow(() -> new ValidationException("Unsupported voucher type '" + voucherType + "'."));
    }

    /** The nature of an account group, or {@code null} if the group is missing (used post-create). */
    private AccountNature natureOfGroup(Long groupId) {
        return accountGroupRepository.findById(groupId).map(AccountGroup::getNature).orElse(null);
    }

    /**
     * Read view for a financial year's opening balances plus its balancing check
     * ({@code GET /api/accounting/opening-balances}, Reqs 3.1, 3.3).
     *
     * @param financialYearId the financial year the balances belong to
     * @param balances        the recorded opening balances for the year
     * @param check           the opening-balance balancing check for the year (Req 3.3)
     */
    public record OpeningBalancesResponse(Long financialYearId,
                                          List<OpeningBalanceResponse> balances,
                                          OpeningBalanceResponse.Check check) {
    }
}
