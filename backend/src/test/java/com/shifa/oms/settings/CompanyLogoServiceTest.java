package com.shifa.oms.settings;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.platform.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CompanyLogoService} (Feature B): setting a logo stores
 * the bytes and records the storage key on {@link AppSettings}; clearing resets
 * the key to {@code null}; a non-image upload is rejected. The
 * {@link StorageService} is an interface (mocked) and {@link SettingsService} is
 * a concrete collaborator constructed against a mocked repository.
 */
class CompanyLogoServiceTest {

    private AppSettingsRepository repository;
    private StorageService storageService;
    private SettingsService settingsService;
    private CompanyLogoService companyLogoService;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(AppSettingsRepository.class);
        storageService = Mockito.mock(StorageService.class);
        settingsService = new SettingsService(repository);
        companyLogoService = new CompanyLogoService(settingsService, storageService);

        when(repository.findById(AppSettings.SINGLETON_ID))
                .thenReturn(Optional.of(new AppSettings()));
        when(repository.save(any(AppSettings.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void uploadLogoStoresBytesAndRecordsKey() throws Exception {
        when(storageService.store(eq("settings/logo"), any(), eq("image/png"), any()))
                .thenReturn(new StorageService.StoredObjectRef("settings/logo/abc.png"));

        AppSettings saved = companyLogoService.uploadLogo("logo.png", "image/png", pngBytes());

        assertThat(saved.getLogoObjectKey()).isEqualTo("settings/logo/abc.png");
        verify(storageService).store(eq("settings/logo"), any(), eq("image/png"), any());
    }

    @Test
    void clearLogoResetsKeyToNull() {
        AppSettings saved = companyLogoService.clearLogo();

        assertThat(saved.getLogoObjectKey()).isNull();
    }

    @Test
    void uploadRejectsNonImageContentType() {
        assertThatThrownBy(() ->
                companyLogoService.uploadLogo("notes.txt", "text/plain", new byte[] {1, 2, 3}))
                .isInstanceOf(ValidationException.class);

        verify(storageService, never()).store(any(), any(), any(), any());
    }
}
