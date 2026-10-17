package com.shifa.oms.activity;

import com.shifa.oms.activity.dto.ActivityResponse;
import com.shifa.oms.activity.dto.LogActivityRequest;
import com.shifa.oms.activity.dto.TimelineItem;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.crm.CustomerInsightService;
import com.shifa.oms.crm.dto.CustomerOrderRow;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The unified-activity read + log surface (CRM expansion Phase 2, Task 9;
 * Req 11). Three endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/leads/{id}/activity} is served by {@code LeadController}
 *       (scoped through {@code LeadService}); this controller serves the
 *       customer timeline and the generic log.</li>
 *   <li>{@code GET /api/admin/customers/{mobile}/activity} — the per-customer
 *       timeline = union of {@code crm_activities} for the mobile + that
 *       customer's order history (Customer 360), newest first (Req 11.4). Shares
 *       the {@code /api/admin/customers} audience; a SALESPERSON is scoped to
 *       customers derived from their own orders by {@link CustomerInsightService}
 *       (out-of-scope mobile 404s).</li>
 *   <li>{@code POST /api/activities/log} — the frontend logs a lightweight
 *       CALL / WHATSAPP (or other) touch initiated from the CRM (Req 11.1),
 *       actor-scoped.</li>
 * </ul>
 */
@RestController
public class ActivityController {

    private final ActivityService activityService;
    private final CustomerInsightService customerInsightService;
    private final CurrentUserService currentUserService;

    public ActivityController(ActivityService activityService,
                              CustomerInsightService customerInsightService,
                              CurrentUserService currentUserService) {
        this.activityService = activityService;
        this.customerInsightService = customerInsightService;
        this.currentUserService = currentUserService;
    }

    /**
     * The per-customer timeline: crm_activities for the mobile merged with the
     * customer's order history, newest first (Req 11.4). Scoped exactly like the
     * other {@code /api/admin/customers} CRM endpoints.
     */
    @GetMapping("/api/admin/customers/{mobile}/activity")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON')")
    public List<TimelineItem> customerTimeline(@PathVariable String mobile) {
        // Authorise + scope: profile() 404s when the mobile has no orders in scope.
        List<CustomerOrderRow> orders = customerInsightService.profile(mobile).orders();

        List<TimelineItem> items = new ArrayList<>();
        for (ActivityResponse a : activityService.timelineForCustomer(mobile)) {
            items.add(TimelineItem.activity(a));
        }
        for (CustomerOrderRow o : orders) {
            items.add(new TimelineItem("ORDER", "ORDER_" + o.orderStatus(),
                    "Order " + o.orderCode(),
                    o.total() == null ? null : "Total " + o.total(), null, o.date()));
        }
        items.sort(Comparator.comparing(TimelineItem::at,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    /**
     * Logs a lightweight CRM-initiated touch (CALL / WHATSAPP / ...) on a
     * lead/customer (Req 11.1), attributed to the caller. Any authenticated staff
     * user may log their own touch.
     */
    @PostMapping("/api/activities/log")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON','TEAM_LEAD')")
    public ActivityResponse log(@Valid @RequestBody LogActivityRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        ActivityEntry saved = activityService.record(
                request.type(), request.leadId(), request.customerMobile(), actor,
                request.summary(), request.detail());
        return saved == null ? null : ActivityResponse.from(saved);
    }
}
