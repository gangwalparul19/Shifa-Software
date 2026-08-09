package com.shifa.oms.integration.meta;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves the dedicated "Meta Leads" system user (username {@code meta-leads},
 * provisioned by migration V49) into an {@link AuthPrincipal} used as the acting
 * owner for auto-imported Meta leads (spec {@code meta-lead-sync}, Req 7.2).
 * The user is provisioned by migration V52.
 *
 * <p>The lookup is cached after the first resolution — the system user's id and
 * username never change at runtime.
 */
@Component
public class MetaSystemActorProvider {

    /** Username of the system user provisioned by {@code V49__meta_leads_system_user.sql}. */
    public static final String SYSTEM_USERNAME = "meta-leads";

    private final UserRepository userRepository;
    private final AtomicReference<AuthPrincipal> cached = new AtomicReference<>();

    public MetaSystemActorProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * The acting principal for Meta lead capture.
     *
     * @throws IllegalStateException if the {@code meta-leads} user is missing (the V49
     *                               migration did not run)
     */
    public AuthPrincipal systemActor() {
        AuthPrincipal principal = cached.get();
        if (principal != null) {
            return principal;
        }
        User user = userRepository.findByUsername(SYSTEM_USERNAME)
                .orElseThrow(() -> new IllegalStateException(
                        "The Meta Leads system user ('" + SYSTEM_USERNAME + "') is missing — "
                                + "ensure migration V52 has run."));
        AuthPrincipal resolved = new AuthPrincipal(user.getId(), user.getUsername(), Role.SALESPERSON);
        cached.compareAndSet(null, resolved);
        return resolved;
    }
}
