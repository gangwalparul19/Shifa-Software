package com.shifa.oms.settings;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN switch for the status emails sent to customers (order approved, dispatched, delivered).
 *
 * <p>It is OFF until an admin turns it on. The switch covers customer emails only: the daily and
 * weekly report to the admins and the temporary-password email to the admin who resets a user are
 * not affected.
 */
@RestController
@RequestMapping("/api/admin/settings/customer-emails")
@PreAuthorize("hasRole('ADMIN')")
public class CustomerEmailSettingsController {

    /** The switch as sent to and from the Settings page. */
    public record CustomerEmailSwitch(boolean enabled) {
    }

    private final SettingsService settingsService;
    private final AuditService auditService;

    public CustomerEmailSettingsController(SettingsService settingsService, AuditService auditService) {
        this.settingsService = settingsService;
        this.auditService = auditService;
    }

    @GetMapping
    public CustomerEmailSwitch get() {
        return new CustomerEmailSwitch(settingsService.isCustomerEmailsEnabled());
    }

    /** Turns customer emails on or off (audited). */
    @PutMapping
    public CustomerEmailSwitch update(@RequestBody CustomerEmailSwitch request) {
        boolean enabled = request != null && request.enabled();
        settingsService.setCustomerEmailsEnabled(enabled);
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS, null,
                "Emails to customers turned " + (enabled ? "ON" : "OFF") + ".");
        return new CustomerEmailSwitch(enabled);
    }
}
