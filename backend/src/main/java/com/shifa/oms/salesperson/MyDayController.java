package com.shifa.oms.salesperson;

import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.salesperson.dto.LeaderboardResponse;
import com.shifa.oms.salesperson.dto.MyDayResponse;
import com.shifa.oms.salesperson.dto.ReorderDueCustomer;
import com.shifa.oms.salesperson.dto.WinBackCustomer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Salesperson self-service "My Day" + "Win-back" API ({@code /api/my-day}).
 *
 * <p>Available to SALESPERSON (own data, scoped server-side) and ADMIN (whole
 * business). The caller is resolved from the security context; the role is never
 * trusted from the client.
 */
@RestController
@RequestMapping("/api/my-day")
@PreAuthorize("hasAnyRole('SALESPERSON','ADMIN')")
public class MyDayController {

    private final MyDayService myDayService;
    private final CurrentUserService currentUserService;

    public MyDayController(MyDayService myDayService, CurrentUserService currentUserService) {
        this.myDayService = myDayService;
        this.currentUserService = currentUserService;
    }

    /** Today + month-to-date figures vs. target, and payments to chase. */
    @GetMapping
    public MyDayResponse myDay() {
        return myDayService.forCaller(currentUserService.requireCurrentUser());
    }

    /** Lapsed customers (no order in the last {@code days} days), highest value first. */
    @GetMapping("/win-back")
    public List<WinBackCustomer> winBack(@RequestParam(value = "days", required = false) Integer days) {
        return myDayService.winBack(currentUserService.requireCurrentUser(), days);
    }

    /** Customers predicted (from cadence) to be due for a repeat order, most overdue first. */
    @GetMapping("/reorder-due")
    public List<ReorderDueCustomer> reorderDue() {
        return myDayService.reorderDue(currentUserService.requireCurrentUser());
    }

    /** This-month sales leaderboard + the caller's own rank and order streak. */
    @GetMapping("/leaderboard")
    public LeaderboardResponse leaderboard() {
        return myDayService.leaderboard(currentUserService.requireCurrentUser());
    }
}
