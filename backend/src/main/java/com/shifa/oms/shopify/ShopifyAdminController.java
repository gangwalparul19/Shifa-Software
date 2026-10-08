package com.shifa.oms.shopify;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import com.shifa.oms.settings.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN operational endpoints for the Shopify integration (distinct from the
 * unauthenticated webhook receiver). Currently a one-click recover that pushes
 * any stuck Shopify orders forward so the channel is deterministic — every
 * Shopify order should reach QuikShipX "Tracking ID Assigned".
 */
@RestController
@RequestMapping("/api/admin/shopify")
@PreAuthorize("hasRole('ADMIN')")
public class ShopifyAdminController {

    private final ShopifyOrderImportService importService;
    private final AuditService auditService;
    private final SettingsService settingsService;

    public ShopifyAdminController(ShopifyOrderImportService importService, AuditService auditService,
                                  SettingsService settingsService) {
        this.importService = importService;
        this.auditService = auditService;
        this.settingsService = settingsService;
    }

    /** The Shopify integration switch: whether incoming Shopify orders are imported. */
    public record IntegrationSettings(boolean enabled) {
    }

    @GetMapping("/settings")
    public IntegrationSettings settings() {
        return new IntegrationSettings(settingsService.isShopifySyncEnabled());
    }

    /** Turns the Shopify integration on or off (audited). */
    @PutMapping("/settings")
    public IntegrationSettings updateSettings(@RequestBody IntegrationSettings request) {
        boolean enabled = request != null && request.enabled();
        settingsService.setShopifySyncEnabled(enabled);
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS, null,
                "Shopify integration turned " + (enabled ? "ON" : "OFF") + ".");
        return new IntegrationSettings(enabled);
    }

    /** Read-only list of Shopify orders not yet at Tracking ID Assigned (what recover would act on). */
    @GetMapping("/stuck")
    public java.util.List<ShopifyOrderImportService.StuckOrder> stuck() {
        return importService.listStuckShopifyOrders();
    }

    /**
     * Recovers stuck Shopify orders: auto-approves any left at Pending Admin
     * Approval (pre auto-approve imports) and re-publishes any sitting at Label
     * Generated to QuikShipX, so the drainer can allot their tracking id. Idempotent.
     */
    @PostMapping("/recover")
    public ShopifyOrderImportService.RecoverResult recover() {
        ShopifyOrderImportService.RecoverResult result = importService.recoverStuckShopifyOrders();
        auditService.record(AuditActions.ORDER_STATUS_CHANGED, AuditActions.ENTITY_ORDER, null,
                "Shopify recover: approved " + result.approvedFromPending()
                        + " pending + re-published " + result.republishedFromLabelGenerated()
                        + " label-generated order(s) to QuikShipX.");
        return result;
    }
}
