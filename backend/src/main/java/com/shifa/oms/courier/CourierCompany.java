package com.shifa.oms.courier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A courier company, mapped to the {@code courier_companies} table (design data
 * model). Holds the display name and a {@code tracking_url_template} used to
 * build a customer-facing tracking link from an AWB (Req 13.4), e.g.
 * {@code https://track.example.com/{awb}}.
 */
@Entity
@Table(name = "courier_companies")
public class CourierCompany {

    /** Placeholder replaced by the AWB when building a tracking link. */
    public static final String AWB_PLACEHOLDER = "{awb}";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "tracking_url_template", length = 300)
    private String trackingUrlTemplate;

    protected CourierCompany() {
        // Required by JPA.
    }

    public CourierCompany(String name, String trackingUrlTemplate) {
        this.name = name;
        this.trackingUrlTemplate = trackingUrlTemplate;
    }

    /**
     * Builds the customer tracking link for an AWB from this company's template
     * (Req 13.4), or {@code null} when no template/AWB is available.
     */
    public String trackingUrl(String awb) {
        if (trackingUrlTemplate == null || trackingUrlTemplate.isBlank() || awb == null || awb.isBlank()) {
            return null;
        }
        return trackingUrlTemplate.replace(AWB_PLACEHOLDER, awb);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTrackingUrlTemplate() {
        return trackingUrlTemplate;
    }
}
