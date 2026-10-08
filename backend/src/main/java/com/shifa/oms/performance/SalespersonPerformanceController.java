package com.shifa.oms.performance;

import com.shifa.oms.performance.dto.SalespersonPerformanceDetail;
import com.shifa.oms.performance.dto.SalespersonPerformanceSummary;
import com.shifa.oms.performance.dto.SalesTargetRow;
import com.shifa.oms.performance.dto.SetSalesTargetRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * "Salesperson 360" performance endpoints for admins (FEATURE request).
 *
 * <p>Uses a dedicated {@code /api/admin/salespeople} base path (rather than
 * {@code /api/admin/staff}) so the leaderboard path never collides with the
 * staff directory's {@code /{id}} variable route. ADMIN only.
 */
@RestController
@RequestMapping("/api/admin/salespeople")
@PreAuthorize("hasRole('ADMIN')")
public class SalespersonPerformanceController {

    private final SalespersonPerformanceService performanceService;
    private final SalesTargetService salesTargetService;

    public SalespersonPerformanceController(SalespersonPerformanceService performanceService,
                                            SalesTargetService salesTargetService) {
        this.performanceService = performanceService;
        this.salesTargetService = salesTargetService;
    }

    /** Team leaderboard — headline metrics for every salesperson (best month first). */
    @GetMapping("/performance")
    public List<SalespersonPerformanceSummary> leaderboard() {
        return performanceService.leaderboard();
    }

    /** One salesperson's full 360: summary + daily trend + recent orders + lead metrics. */
    @GetMapping("/{id}/performance")
    public SalespersonPerformanceDetail detail(@PathVariable Long id,
                                               @RequestParam(required = false) Integer days) {
        return performanceService.detail(id, days);
    }

    /** Sales targets vs achievement for a month (FEATURE-ROADMAP §6.1); default current month. */
    @GetMapping("/targets")
    public List<SalesTargetRow> targets(@RequestParam(required = false) String month) {
        return salesTargetService.list(month);
    }

    /** Set/update a salesperson's monthly target + optional incentive (FEATURE-ROADMAP §6.1). */
    @PutMapping("/targets")
    public SalesTargetRow setTarget(@Valid @RequestBody SetSalesTargetRequest request) {
        return salesTargetService.setTarget(
                request.salespersonId(), request.month(), request.targetAmount(), request.incentivePct());
    }
}
