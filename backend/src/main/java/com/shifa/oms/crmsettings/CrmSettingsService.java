package com.shifa.oms.crmsettings;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.crmsettings.dto.CrmSettingsRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Single-row CRM-settings service (CRM expansion Phase 3, Req 18.3), mirroring
 * {@link com.shifa.oms.settings.SettingsService}: {@link #getSettings()} loads
 * the single row, creating a defaults row if missing so callers never see empty,
 * and {@link #update(CrmSettingsRequest)} persists the edited configuration.
 *
 * <p>Consumed BEST-EFFORT by the follow-up pipeline: the missed-grace window and
 * the follow-up default time-of-day fall back to today's constants when the row
 * / service is unavailable, so existing behaviour is unchanged until configured.
 */
@Service
public class CrmSettingsService {

    private final CrmSettingsRepository repository;

    public CrmSettingsService(CrmSettingsRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /** Loads the single settings row, creating a defaults row if none exists. */
    @Transactional
    public CrmSettings getSettings() {
        return repository.findById(CrmSettings.SINGLETON_ID)
                .orElseGet(() -> repository.save(CrmSettings.defaults()));
    }

    /** The configured missed-follow-up grace window in hours, if configured. */
    @Transactional(readOnly = true)
    public Optional<Integer> missedGraceHours() {
        return repository.findById(CrmSettings.SINGLETON_ID)
                .map(CrmSettings::getMissedGraceHours);
    }

    /** The configured follow-up default time-of-day, if configured. */
    @Transactional(readOnly = true)
    public Optional<LocalTime> followUpDefaultTime() {
        return repository.findById(CrmSettings.SINGLETON_ID)
                .map(CrmSettings::getFollowUpDefaultTime);
    }

    /** The configured default team-assignment rule, if configured. */
    @Transactional(readOnly = true)
    public Optional<String> defaultAssignmentRule() {
        return repository.findById(CrmSettings.SINGLETON_ID)
                .map(CrmSettings::getDefaultAssignmentRule);
    }

    /** Applies the update to the single settings row. */
    @Transactional
    public CrmSettings update(CrmSettingsRequest request) {
        CrmSettings settings = getSettings();
        settings.setFollowUpDefaultTime(request.followUpDefaultTime());
        if (request.missedGraceHours() != null) {
            settings.setMissedGraceHours(request.missedGraceHours());
        }
        settings.setDefaultPipelineId(request.defaultPipelineId());
        String rule = request.defaultAssignmentRule();
        if (rule != null && !rule.isBlank()) {
            String normalised = rule.trim().toUpperCase(java.util.Locale.ROOT);
            if (!normalised.equals(CrmSettings.RULE_DIRECT)
                    && !normalised.equals(CrmSettings.RULE_ROUND_ROBIN)) {
                throw new ValidationException(
                        "defaultAssignmentRule must be DIRECT or ROUND_ROBIN.");
            }
            settings.setDefaultAssignmentRule(normalised);
        }
        return repository.save(settings);
    }
}
