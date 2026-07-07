package com.shifa.oms.settings;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.settings.dto.SettingsRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SettingsService} with a mocked repository (no DB):
 * <ul>
 *   <li>create-on-missing returns a defaults row (GST disabled);</li>
 *   <li>an update with GST disabled succeeds even with a blank GSTIN;</li>
 *   <li>enabling GST requires a 15-character GSTIN, otherwise a validation error
 *       is raised and nothing is persisted.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock
    private AppSettingsRepository repository;

    private SettingsService service;

    @BeforeEach
    void setUp() {
        service = new SettingsService(repository);
    }

    @Test
    void getSettingsCreatesDefaultsRowWhenMissing() {
        when(repository.findById(AppSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repository.save(any(AppSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        AppSettings settings = service.getSettings();

        assertThat(settings.isGstEnabled()).isFalse();
        assertThat(settings.getLegalName()).isEqualTo("Shifa Herbal Remedies");
        assertThat(settings.isPricesIncludeGst()).isTrue();
        verify(repository).save(any(AppSettings.class));
    }

    @Test
    void updateWithGstDisabledSucceedsWithBlankGstin() {
        when(repository.findById(AppSettings.SINGLETON_ID)).thenReturn(Optional.of(new AppSettings()));
        when(repository.save(any(AppSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        AppSettings saved = service.update(request(false, "  "));

        assertThat(saved.isGstEnabled()).isFalse();
        assertThat(saved.getGstin()).isNull();
    }

    @Test
    void updateWithGstEnabledAndValidGstinSucceeds() {
        when(repository.findById(AppSettings.SINGLETON_ID)).thenReturn(Optional.of(new AppSettings()));
        when(repository.save(any(AppSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        AppSettings saved = service.update(request(true, "23ABCDE1234F1Z5"));

        assertThat(saved.isGstEnabled()).isTrue();
        assertThat(saved.getGstin()).isEqualTo("23ABCDE1234F1Z5");
    }

    @Test
    void updateWithGstEnabledAndMissingGstinIsRejected() {
        assertThatThrownBy(() -> service.update(request(true, null)))
                .isInstanceOf(ValidationException.class);
        verify(repository, never()).save(any(AppSettings.class));
    }

    @Test
    void updateWithGstEnabledAndWrongLengthGstinIsRejected() {
        assertThatThrownBy(() -> service.update(request(true, "TOO-SHORT")))
                .isInstanceOf(ValidationException.class);
        verify(repository, never()).save(any(AppSettings.class));
    }

    // --- Wave 3 settings expansion round-trip + validation ------------------

    @Test
    void updatePersistsWave3InvoiceAndBankFieldsRoundTrip() {
        when(repository.findById(AppSettings.SINGLETON_ID)).thenReturn(Optional.of(new AppSettings()));
        when(repository.save(any(AppSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        AppSettings saved = service.update(fullRequest(b -> {}));

        assertThat(saved.getInvoiceNumberPrefix()).isEqualTo("SHR/24-25/");
        assertThat(saved.getInvoiceTerms()).isEqualTo("Goods once sold are not returnable.");
        assertThat(saved.getBankName()).isEqualTo("HDFC Bank");
        assertThat(saved.getBankAccountName()).isEqualTo("Shifa Herbal Remedies");
        assertThat(saved.getBankAccountNumber()).isEqualTo("1234567890");
        assertThat(saved.getBankIfsc()).isEqualTo("HDFC0001234");
        assertThat(saved.getBankBranch()).isEqualTo("Indore Main");
        assertThat(saved.getGstSlabs()).isEqualTo("0,5,12,18,28");
    }

    @Test
    void updateUpperCasesIfscAndNormalisesSlabs() {
        when(repository.findById(AppSettings.SINGLETON_ID)).thenReturn(Optional.of(new AppSettings()));
        when(repository.save(any(AppSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        AppSettings saved = service.update(fullRequest(b -> {
            b.bankIfsc = "hdfc0001234";
            b.gstSlabs = " 0 , 5 ,12, 18 ";
        }));

        assertThat(saved.getBankIfsc()).isEqualTo("HDFC0001234");
        assertThat(saved.getGstSlabs()).isEqualTo("0,5,12,18");
    }

    @Test
    void updateRejectsInvalidIfsc() {
        assertThatThrownBy(() -> service.update(fullRequest(b -> b.bankIfsc = "NOTVALID")))
                .isInstanceOf(ValidationException.class);
        verify(repository, never()).save(any(AppSettings.class));
    }

    @Test
    void updateRejectsGstSlabOutOfRange() {
        assertThatThrownBy(() -> service.update(fullRequest(b -> b.gstSlabs = "0,5,40")))
                .isInstanceOf(ValidationException.class);
        verify(repository, never()).save(any(AppSettings.class));
    }

    @Test
    void updateRejectsNonNumericGstSlab() {
        assertThatThrownBy(() -> service.update(fullRequest(b -> b.gstSlabs = "0,five,12")))
                .isInstanceOf(ValidationException.class);
        verify(repository, never()).save(any(AppSettings.class));
    }

    private SettingsRequest request(boolean gstEnabled, String gstin) {
        return new SettingsRequest(
                gstEnabled,
                gstin,
                "Shifa Herbal Remedies",
                "Plot 5, Herbal Estate",
                "Indore",
                "Madhya Pradesh",
                "23",
                new BigDecimal("5.00"),
                true,
                "Thank you for your business.",
                "+91 90000 00000",
                "care@shifaherbal.example",
                5,
                null, null, null, null, null, null, null, null);
    }

    /** Mutable holder for the Wave 3 optional fields so tests can tweak individual values. */
    private static final class Wave3 {
        String invoiceNumberPrefix = "SHR/24-25/";
        String invoiceTerms = "Goods once sold are not returnable.";
        String bankName = "HDFC Bank";
        String bankAccountName = "Shifa Herbal Remedies";
        String bankAccountNumber = "1234567890";
        String bankIfsc = "HDFC0001234";
        String bankBranch = "Indore Main";
        String gstSlabs = "0,5,12,18,28";
    }

    private SettingsRequest fullRequest(java.util.function.Consumer<Wave3> customiser) {
        Wave3 b = new Wave3();
        customiser.accept(b);
        return new SettingsRequest(
                false,
                null,
                "Shifa Herbal Remedies",
                "Plot 5, Herbal Estate",
                "Indore",
                "Madhya Pradesh",
                "23",
                new BigDecimal("5.00"),
                true,
                "Thank you for your business.",
                "+91 90000 00000",
                "care@shifaherbal.example",
                5,
                b.invoiceNumberPrefix,
                b.invoiceTerms,
                b.bankName,
                b.bankAccountName,
                b.bankAccountNumber,
                b.bankIfsc,
                b.bankBranch,
                b.gstSlabs);
    }
}
