package com.shifa.oms.crmsettings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalTime;

/**
 * Single-row CRM configuration (CRM expansion Phase 3, Req 18.3), mapped to the
 * {@code crm_settings} table (migration V91), mirroring the single-row
 * {@code app_settings} pattern.
 *
 * <ul>
 *   <li>{@link #followUpDefaultTime} — the default time-of-day applied to a
 *       follow-up with no explicit time.</li>
 *   <li>{@link #missedGraceHours} — hours after a reminder that an un-acted
 *       follow-up becomes Missed (wired into {@code MissedFollowUpJob}).</li>
 *   <li>{@link #defaultPipelineId} — the pipeline new leads default to.</li>
 *   <li>{@link #defaultAssignmentRule} — {@code DIRECT} | {@code ROUND_ROBIN},
 *       the default for team assignment.</li>
 * </ul>
 */
@Entity
@Table(name = "crm_settings")
public class CrmSettings {

    /** The single settings row's fixed primary key. */
    public static final byte SINGLETON_ID = 1;

    public static final String RULE_DIRECT = "DIRECT";
    public static final String RULE_ROUND_ROBIN = "ROUND_ROBIN";

    @Id
    @Column(name = "id")
    private Byte id = SINGLETON_ID;

    @Column(name = "follow_up_default_time")
    private LocalTime followUpDefaultTime;

    @Column(name = "missed_grace_hours", nullable = false)
    private int missedGraceHours = 24;

    @Column(name = "default_pipeline_id")
    private Long defaultPipelineId;

    @Column(name = "default_assignment_rule", nullable = false, length = 20)
    private String defaultAssignmentRule = RULE_DIRECT;

    protected CrmSettings() {
        // Required by JPA.
    }

    /** A defaults row (follow-up 09:00, grace 24h, DIRECT assignment). */
    public static CrmSettings defaults() {
        CrmSettings settings = new CrmSettings();
        settings.id = SINGLETON_ID;
        settings.followUpDefaultTime = LocalTime.of(9, 0);
        settings.missedGraceHours = 24;
        settings.defaultAssignmentRule = RULE_DIRECT;
        return settings;
    }

    public Byte getId() {
        return id;
    }

    public LocalTime getFollowUpDefaultTime() {
        return followUpDefaultTime;
    }

    public void setFollowUpDefaultTime(LocalTime followUpDefaultTime) {
        this.followUpDefaultTime = followUpDefaultTime;
    }

    public int getMissedGraceHours() {
        return missedGraceHours;
    }

    public void setMissedGraceHours(int missedGraceHours) {
        this.missedGraceHours = missedGraceHours;
    }

    public Long getDefaultPipelineId() {
        return defaultPipelineId;
    }

    public void setDefaultPipelineId(Long defaultPipelineId) {
        this.defaultPipelineId = defaultPipelineId;
    }

    public String getDefaultAssignmentRule() {
        return defaultAssignmentRule;
    }

    public void setDefaultAssignmentRule(String defaultAssignmentRule) {
        this.defaultAssignmentRule = defaultAssignmentRule;
    }
}
