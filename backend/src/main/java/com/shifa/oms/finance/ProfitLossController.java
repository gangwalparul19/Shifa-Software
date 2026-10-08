package com.shifa.oms.finance;

import com.shifa.oms.finance.dto.ProfitLossResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Admin profit &amp; loss endpoint ({@code /api/admin/finance/pnl}, Feature C3).
 * Open to ADMIN and ACCOUNTANT.
 */
@RestController
@RequestMapping("/api/admin/finance")
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','CA')")
public class ProfitLossController {

    private final ProfitLossService profitLossService;

    public ProfitLossController(ProfitLossService profitLossService) {
        this.profitLossService = profitLossService;
    }

    /**
     * The P&amp;L summary for the inclusive {@code [from, to]} date window.
     *
     * @param from inclusive lower-bound date, ISO {@code yyyy-MM-dd} (required)
     * @param to   inclusive upper-bound date, ISO {@code yyyy-MM-dd} (required)
     */
    @GetMapping("/pnl")
    public ProfitLossResponse pnl(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return profitLossService.report(from, to);
    }
}

