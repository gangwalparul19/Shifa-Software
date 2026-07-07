package com.shifa.oms.dashboard;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The admin dashboard's Server-Sent Events stream (Req 11.2, 13.3, 17.4, 19.5,
 * 19.6; design: "Real-time notifications (Admin Dashboard)").
 *
 * <p>{@code GET /api/admin/events} opens a long-lived {@code text/event-stream}
 * that the {@link OutboxSseRelay} pushes typed events onto: {@code ORDER_PACKED}
 * (Req 11.2), {@code ORDER_STATUS_CHANGED} (Req 13.3), {@code CLAIM_FILED_REQUIRED}
 * (Req 17.4), {@code COURIER_ASSIGN_FAILED} (Req 12.4), {@code WHATSAPP_FAILED}
 * (Req 14.4), plus periodic {@code LIVE_STATS} (Req 19.5) and {@code ACTIVITY}
 * (Req 19.6) snapshots.
 *
 * <p><strong>Authentication for EventSource.</strong> The browser
 * {@code EventSource} API cannot set an {@code Authorization} header, so this
 * admin-only stream additionally accepts the access token as an
 * {@code ?access_token=} query parameter, which
 * {@link com.shifa.oms.auth.JwtAuthenticationFilter} validates exactly like a
 * bearer token (same signature/expiry/type checks). The stream still requires
 * the {@code ADMIN} authority via {@code @PreAuthorize}, so a missing or
 * non-admin token yields 401/403 before the stream opens. (A short-lived
 * one-time SSE ticket would be a stricter alternative; the query-param token was
 * chosen for v1 simplicity and is only read on this endpoint.)
 */
@RestController
@RequestMapping("/api/admin/events")
@PreAuthorize("hasRole('ADMIN')")
public class AdminEventController {

    private final AdminSseBroker broker;

    public AdminEventController(AdminSseBroker broker) {
        this.broker = broker;
    }

    /** Opens the admin SSE stream and registers the connection with the broker. */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broker.register();
    }
}
