package com.shifa.oms.dashboard;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The staff Server-Sent Events stream (Req 11.2, 13.3, 17.4, 19.5, 19.6; design:
 * "Real-time notifications").
 *
 * <p>{@code GET /api/admin/events} opens a long-lived {@code text/event-stream}.
 * The connection is registered with the caller's {@link com.shifa.oms.auth.Role}
 * and user id so events are delivered to the right audience:
 * <ul>
 *   <li>Admins receive the admin-dashboard signals (operational
 *       {@code ORDER_PACKED}/{@code ORDER_STATUS_CHANGED}/… relayed by
 *       {@link OutboxSseRelay}, plus periodic {@code LIVE_STATS}/{@code ACTIVITY});</li>
 *   <li>Every staff role receives a lightweight {@code NOTIFICATION} event when a
 *       bell notification is addressed to their role or user id, so their unread
 *       badge updates live (scoped by {@link AdminSseBroker#sendToRecipients}).</li>
 * </ul>
 *
 * <p><strong>Authentication for EventSource.</strong> The browser
 * {@code EventSource} API cannot set an {@code Authorization} header, so this
 * stream additionally accepts the access token as an {@code ?access_token=}
 * query parameter, which {@link com.shifa.oms.auth.JwtAuthenticationFilter}
 * validates exactly like a bearer token. The stream requires an authenticated
 * staff role via {@code @PreAuthorize} (never a storefront {@code CUSTOMER}).
 */
@RestController
@RequestMapping("/api/admin/events")
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','CA','SALESPERSON','TEAM_LEAD','PACKING_USER','PAYMENT_VERIFIER')")
public class AdminEventController {

    private final AdminSseBroker broker;
    private final CurrentUserService currentUserService;

    public AdminEventController(AdminSseBroker broker, CurrentUserService currentUserService) {
        this.broker = broker;
        this.currentUserService = currentUserService;
    }

    /** Opens the staff SSE stream, scoped to the caller's role + user id. */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        AuthPrincipal principal = currentUserService.requireCurrentUser();
        return broker.register(principal.role(), principal.userId());
    }
}
