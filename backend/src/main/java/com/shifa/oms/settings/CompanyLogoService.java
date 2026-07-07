package com.shifa.oms.settings;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/**
 * Manages the company logo used on invoices and labels (Feature 3): validates
 * and stores an uploaded image via the shared {@link StorageService} (the same
 * mechanism that backs payment screenshots and label/invoice PDFs), records the
 * resulting storage key on {@link AppSettings}, and loads the bytes on demand for
 * the PDF renderers.
 *
 * <p>Only PNG and JPEG images are accepted, up to {@value #MAX_LOGO_BYTES} bytes.
 * When no logo is configured (or it cannot be loaded) {@link #currentLogoPng()}
 * returns empty so renderers fall back to the text brand, keeping the layout
 * intact.
 */
@Service
public class CompanyLogoService {

    private static final Logger log = LoggerFactory.getLogger(CompanyLogoService.class);

    /** Storage key prefix for uploaded company logos. */
    static final String STORAGE_PREFIX = "settings/logo";

    /** Maximum accepted logo size (1 MB). */
    static final int MAX_LOGO_BYTES = 1_000_000;

    /** Accepted image content types. */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/jpg");

    private final SettingsService settingsService;
    private final StorageService storageService;

    public CompanyLogoService(SettingsService settingsService, StorageService storageService) {
        this.settingsService = settingsService;
        this.storageService = storageService;
    }

    /**
     * Validates and stores an uploaded logo image, recording its storage key on
     * the settings row.
     *
     * @param originalFilename the client filename (extension preserved), may be {@code null}
     * @param contentType      the MIME type; must be PNG or JPEG
     * @param content          the raw image bytes
     * @return the persisted settings (with the new {@code logoObjectKey})
     */
    public AppSettings uploadLogo(String originalFilename, String contentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new ValidationException("The logo file is empty.");
        }
        if (content.length > MAX_LOGO_BYTES) {
            throw new ValidationException("The logo file must be at most 1 MB.");
        }
        String normalized = contentType == null ? "" : contentType.trim().toLowerCase();
        if (!ALLOWED_CONTENT_TYPES.contains(normalized)) {
            throw new ValidationException("The logo must be a PNG or JPEG image.");
        }
        StorageService.StoredObjectRef ref =
                storageService.store(STORAGE_PREFIX, originalFilename, contentType, content);
        log.debug("Stored company logo under key {}", ref.key());
        return settingsService.updateLogoKey(ref.key());
    }

    /**
     * Clears the configured company logo (invoices/labels fall back to the text
     * brand). The stored object itself is intentionally left in place.
     *
     * @return the persisted settings (with {@code logoObjectKey} cleared)
     */
    public AppSettings clearLogo() {
        return settingsService.updateLogoKey(null);
    }

    /** Loads the configured logo object, or empty when none is configured/loadable. */
    public Optional<StorageService.StoredObject> loadLogo() {
        String key = settingsService.getSettings().getLogoObjectKey();
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return storageService.load(key);
    }

    /**
     * The configured logo bytes for the PDF renderers, or empty to fall back to
     * the text brand. Never throws — a rendering path must not fail because a
     * logo could not be loaded.
     */
    public Optional<byte[]> currentLogoPng() {
        try {
            return loadLogo().map(StorageService.StoredObject::content);
        } catch (RuntimeException e) {
            log.warn("Failed to load company logo for rendering: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
