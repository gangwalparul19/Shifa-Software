package com.shifa.oms.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process registry and fan-out for the admin dashboard's Server-Sent Events
 * stream (design: "Real-time notifications (Admin Dashboard)").
 *
 * <p>Each connected admin holds one long-lived {@link SseEmitter}. The broker
 * keeps the live set of emitters and pushes typed events to all of them; dead
 * connections (completed, timed out, or errored) are pruned automatically. SSE
 * is server&rarr;client only and fans out from this single process, which is
 * sufficient for the small admin user base [Free-tier].
 *
 * <p>The broker deliberately holds no persistence: the durable record of a
 * notification is the {@code outbox} row (Req 11.2 "persist when no admin
 * connected"). {@link OutboxSseRelay} decides <em>whether</em> to relay based on
 * {@link #hasActiveEmitters()} so nothing is lost when no admin is watching.
 */
@Component
public class AdminSseBroker {

    private static final Logger log = LoggerFactory.getLogger(AdminSseBroker.class);

    /** Default stream lifetime before the client must reconnect (30 minutes). */
    private static final long DEFAULT_TIMEOUT_MS = 30L * 60L * 1000L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * Registers a new admin connection and wires its lifecycle callbacks so it is
     * removed from the registry when it completes, times out, or errors.
     *
     * @return the created {@link SseEmitter} to return from the controller
     */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MS);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        // A comment/hello event opens the stream promptly so proxies flush headers.
        send("CONNECTED", java.util.Map.of("message", "admin event stream connected"), emitter);
        return emitter;
    }

    /** Whether at least one admin is currently connected (drives the relay, Req 11.2). */
    public boolean hasActiveEmitters() {
        return !emitters.isEmpty();
    }

    /** Number of currently connected admins (diagnostics/tests). */
    public int activeCount() {
        return emitters.size();
    }

    /**
     * Broadcasts a typed event to every connected admin. Emitters that fail to
     * receive are dropped; a send never throws to the caller (the relay must keep
     * draining even if a client disconnected mid-flush).
     *
     * @param eventName the SSE event name (e.g. {@code ORDER_PACKED}, {@code LIVE_STATS})
     * @param data      the JSON-serializable payload
     */
    public void broadcast(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            send(eventName, data, emitter);
        }
    }

    private void send(String eventName, Object data, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException e) {
            // Client went away between the liveness check and the send.
            emitters.remove(emitter);
            log.debug("Dropped a dead admin SSE emitter while sending {}: {}",
                    eventName, e.getMessage());
        }
    }
}
