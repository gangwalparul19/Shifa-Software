package com.shifa.oms.shopify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.AppSettingsRepository;
import com.shifa.oms.settings.SettingsService;
import com.shifa.oms.shopify.dto.ShopifyOrderPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Shopify integration switch on the webhook: when OFF an incoming order is
 * acknowledged but NOT imported; when ON it is imported as before; a forged
 * signature is rejected either way.
 */
class ShopifyWebhookControllerTest {

    private static final byte[] BODY =
            "{\"id\":7412,\"name\":\"#25500\",\"total_price\":\"999.00\"}".getBytes(StandardCharsets.UTF_8);

    private AppSettings settings;
    private RecordingImport importService;

    /** Records import calls instead of touching the database (Java 25: no concrete mocks). */
    private static final class RecordingImport extends ShopifyOrderImportService {
        int calls;

        RecordingImport() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public ImportResult importOrder(ShopifyOrderPayload payload) {
            calls++;
            return new ImportResult("SHR-TEST", true);
        }
    }

    @BeforeEach
    void setUp() {
        settings = AppSettings.defaults();
        AppSettingsRepository repo = mock(AppSettingsRepository.class);
        when(repo.findById(AppSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
        importService = new RecordingImport();
        controller = new ShopifyWebhookController(
                new ShopifyHmacVerifier(new ShopifyProperties("")), importService, new ObjectMapper(),
                new SettingsService(repo));
    }

    private ShopifyWebhookController controller;

    @Test
    void integrationIsOffByDefault() {
        assertThat(AppSettings.defaults().isShopifySyncEnabled()).isFalse();
    }

    @Test
    void whenOffTheOrderIsAcknowledgedButNotImported() {
        settings.setShopifySyncEnabled(false);

        Map<String, Object> res = controller.receiveOrder(BODY, null);

        assertThat(importService.calls).isZero();
        assertThat(res).containsEntry("ignored", true).containsEntry("reason", "SHOPIFY_SYNC_DISABLED");
    }

    @Test
    void whenOnTheOrderIsImported() {
        settings.setShopifySyncEnabled(true);

        Map<String, Object> res = controller.receiveOrder(BODY, null);

        assertThat(importService.calls).isEqualTo(1);
        assertThat(res).containsEntry("orderCode", "SHR-TEST").containsEntry("created", true);
    }

    @Test
    void forgedSignatureIsRejectedEvenWhenOff() {
        AppSettingsRepository repo = mock(AppSettingsRepository.class);
        when(repo.findById(AppSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
        ShopifyWebhookController signed = new ShopifyWebhookController(
                new ShopifyHmacVerifier(new ShopifyProperties("real-secret")), importService,
                new ObjectMapper(), new SettingsService(repo));

        assertThatThrownBy(() -> signed.receiveOrder(BODY, "bm90LXZhbGlk"))
                .isInstanceOf(ApiException.class);
        assertThat(importService.calls).isZero();
    }
}
