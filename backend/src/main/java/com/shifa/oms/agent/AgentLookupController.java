package com.shifa.oms.agent;

import com.shifa.oms.agent.dto.AgentLookupResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WhatsApp live-agent order lookup endpoint (Req 15.1, 15.2).
 *
 * <p>{@code GET /api/agent/lookup} returns the matching order's current status
 * and tracking details, or a structured no-match result (Req 15.2). It accepts
 * either an explicit {@code key}/{@code value} pair (key ∈
 * {@code orderCode|mobile|awb}) or one of the discrete {@code orderCode} /
 * {@code mobile} / {@code awb} parameters.
 *
 * <p><strong>Auth.</strong> Per the design, a live agent runs this query via the
 * storefront; it exposes order + payment details, so it is restricted to
 * authenticated staff roles (ADMIN / ACCOUNTANT / SALESPERSON) rather than being
 * public. Salesperson scoping is not applied here because a live agent must be
 * able to answer for any customer's order.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentLookupController {

    private final AgentLookupService agentLookupService;

    public AgentLookupController(AgentLookupService agentLookupService) {
        this.agentLookupService = agentLookupService;
    }

    /** Look up an order for a live agent (Req 15.1, 15.2). */
    @GetMapping("/lookup")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON')")
    public AgentLookupResponse lookup(
            @RequestParam(name = "key", required = false) String key,
            @RequestParam(name = "value", required = false) String value,
            @RequestParam(name = "orderCode", required = false) String orderCode,
            @RequestParam(name = "mobile", required = false) String mobile,
            @RequestParam(name = "awb", required = false) String awb) {
        if (key != null && !key.isBlank()) {
            return agentLookupService.lookup(key, value);
        }
        return agentLookupService.lookupByAny(orderCode, mobile, awb);
    }
}
