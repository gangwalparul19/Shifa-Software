package com.shifa.oms.ledger.statements;

import com.shifa.oms.ledger.statements.dto.BalanceSheetResponse;
import com.shifa.oms.ledger.statements.dto.CashFlowResponse;
import com.shifa.oms.ledger.statements.dto.ProfitAndLossResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * REST API for the three statutory <strong>Financial Statements</strong> — the Balance Sheet, the
 * Profit &amp; Loss statement, and the direct-method Cash Flow statement — under
 * {@code /api/accounting/**} (Financial Statements Reqs 1, 2, 4, 5, 6, 7, 10, 11, 13).
 *
 * <p><strong>Role-based access (Req 10).</strong> The whole controller is restricted to the finance
 * roles by the class-level {@code @PreAuthorize} — ADMIN, ACCOUNTANT, and CA (Reqs 10.1, 10.2, 10.3);
 * any other role is denied (Req 10.4). Every endpoint is a {@code GET}, so the statements are
 * inherently read-only and the CA role has no mutation path (Req 13).
 *
 * <p>Each statement accepts <em>either</em> a {@code ?financialYearId=} <em>or</em> a
 * {@code ?from=&to=} date range (Reqs 1.1, 1.2), plus an optional {@code ?comparative=true} that adds
 * the immediately preceding equal-length period (Req 7.1; defaults to {@code false} so a single
 * period is returned, Req 7.2). Period validation — neither FY nor range supplied → 400,
 * {@code from} after {@code to} → 400 (Reqs 1.3, 1.4) — is delegated entirely to the
 * {@code ReportPeriodResolver} inside the services (no bespoke controller logic); the resulting
 * {@code ValidationException} is rendered by the existing {@code GlobalExceptionHandler}.
 *
 * <p>Each endpoint returns the full recursive {@link com.shifa.oms.ledger.statements.dto.StatementNodeResponse}
 * tree, so drill-down into child groups and ledger leaves needs no separate endpoint (Reqs 5.3,
 * 13.4). The controller only adapts HTTP to the services + task-7.1 DTOs; all accounting law lives in
 * the services and the pure {@code ledger.statements.domain} core (Req 11).
 */
@RestController
@RequestMapping("/api/accounting")
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','CA')")
public class FinancialStatementsController {

    private final BalanceSheetService balanceSheetService;
    private final ProfitAndLossService profitAndLossService;
    private final CashFlowService cashFlowService;

    public FinancialStatementsController(BalanceSheetService balanceSheetService,
                                         ProfitAndLossService profitAndLossService,
                                         CashFlowService cashFlowService) {
        this.balanceSheetService = balanceSheetService;
        this.profitAndLossService = profitAndLossService;
        this.cashFlowService = cashFlowService;
    }

    /**
     * The Balance Sheet as at the resolved period's to-date (view; Reqs 1, 2, 3, 5.3, 7, 13.4).
     * Accepts {@code ?financialYearId=} or {@code ?from=&to=} plus optional {@code ?comparative=true}.
     */
    @GetMapping("/balance-sheet")
    public BalanceSheetResponse balanceSheet(
            @RequestParam(required = false) Long financialYearId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "false") boolean comparative) {
        return BalanceSheetResponse.from(
                balanceSheetService.balanceSheet(financialYearId, from, to, comparative));
    }

    /**
     * The Profit &amp; Loss statement for the resolved period (view; Reqs 1, 4, 5.3, 7, 13.4).
     * Accepts {@code ?financialYearId=} or {@code ?from=&to=} plus optional {@code ?comparative=true}.
     */
    @GetMapping("/profit-and-loss")
    public ProfitAndLossResponse profitAndLoss(
            @RequestParam(required = false) Long financialYearId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "false") boolean comparative) {
        return ProfitAndLossResponse.from(
                profitAndLossService.profitAndLoss(financialYearId, from, to, comparative));
    }

    /**
     * The direct-method Cash Flow statement for the resolved period (view; Reqs 1, 6, 5.3, 7, 13.4).
     * Accepts {@code ?financialYearId=} or {@code ?from=&to=} plus optional {@code ?comparative=true}.
     */
    @GetMapping("/cash-flow")
    public CashFlowResponse cashFlow(
            @RequestParam(required = false) Long financialYearId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "false") boolean comparative) {
        return CashFlowResponse.from(
                cashFlowService.cashFlow(financialYearId, from, to, comparative));
    }
}
