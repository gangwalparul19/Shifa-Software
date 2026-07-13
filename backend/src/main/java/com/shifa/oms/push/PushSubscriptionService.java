package com.shifa.oms.push;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores/removes staff Web Push subscriptions (FEATURE-ROADMAP §8.3).
 */
@Service
public class PushSubscriptionService {

    private final PushSubscriptionRepository repository;

    public PushSubscriptionService(PushSubscriptionRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers (or re-associates) a subscription for a user. An endpoint is
     * globally unique, so an existing row for the same endpoint is replaced —
     * handles a device whose logged-in user changed or whose keys rotated.
     */
    @Transactional
    public void subscribe(Long userId, String endpoint, String p256dh, String auth) {
        repository.deleteByEndpoint(endpoint);
        repository.save(new PushSubscriptionEntity(userId, endpoint, p256dh, auth));
    }

    /** Removes a subscription by endpoint (on logout / opt-out). */
    @Transactional
    public void unsubscribe(String endpoint) {
        repository.deleteByEndpoint(endpoint);
    }
}
