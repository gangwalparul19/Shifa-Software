package com.shifa.oms.push;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.push.dto.PushPublicKeyResponse;
import com.shifa.oms.push.dto.PushSubscribeRequest;
import com.shifa.oms.push.dto.PushUnsubscribeRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web Push subscription endpoints for staff (FEATURE-ROADMAP §8.3). Any
 * authenticated staff member can fetch the VAPID public key and register /
 * remove their browser subscription.
 */
@RestController
@RequestMapping("/api/notifications/push")
@PreAuthorize("isAuthenticated()")
public class PushController {

    private final PushSubscriptionService subscriptionService;
    private final WebPushService webPushService;
    private final CurrentUserService currentUserService;

    public PushController(PushSubscriptionService subscriptionService,
                          WebPushService webPushService,
                          CurrentUserService currentUserService) {
        this.subscriptionService = subscriptionService;
        this.webPushService = webPushService;
        this.currentUserService = currentUserService;
    }

    /** The VAPID public key + whether push is enabled server-side. */
    @GetMapping("/public-key")
    public PushPublicKeyResponse publicKey() {
        return new PushPublicKeyResponse(webPushService.isEnabled(), webPushService.publicKey());
    }

    /** Registers the caller's browser push subscription. */
    @PostMapping("/subscribe")
    public ResponseEntity<Void> subscribe(@Valid @RequestBody PushSubscribeRequest request) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        subscriptionService.subscribe(
                actor.userId(), request.endpoint(), request.keys().p256dh(), request.keys().auth());
        return ResponseEntity.noContent().build();
    }

    /** Removes a browser push subscription by endpoint. */
    @PostMapping("/unsubscribe")
    public ResponseEntity<Void> unsubscribe(@Valid @RequestBody PushUnsubscribeRequest request) {
        subscriptionService.unsubscribe(request.endpoint());
        return ResponseEntity.noContent().build();
    }
}
