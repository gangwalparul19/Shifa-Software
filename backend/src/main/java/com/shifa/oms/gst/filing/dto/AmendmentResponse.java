package com.shifa.oms.gst.filing.dto;

import com.shifa.oms.gst.filing.ReturnAmendment;
import com.shifa.oms.gst.filing.domain.AmendmentStatus;
import com.shifa.oms.gst.filing.domain.AmendmentTable;

import java.time.LocalDateTime;

/**
 * A post-filing correction routed the GST way, for the API (GST returns &amp; filing, Reqs 3.2, 3.3,
 * 3.4, 3.6, 3.7, 3.8). Mirrors {@link ReturnAmendment}: the routed {@link #amendmentTable}
 * (null while PENDING / MANUAL_REVIEW), its {@link #status}, the original Filed_Period + document it
 * corrects, the target period it lands in (null while PENDING), and the originally filed vs latest
 * corrected values (serialised JSON, exactly as stored). Enums serialise as {@code name()}; the
 * document reference is a business identifier (order code / note number), not customer PII (Req 10.5).
 *
 * @param id                  the amendment id
 * @param amendmentTable      the GSTR-1 amendment section (B2BA/B2CSA/CDNRA), or {@code null} while
 *                            PENDING or MANUAL_REVIEW
 * @param status              the routing lifecycle status (PENDING/ROUTED/MANUAL_REVIEW)
 * @param originalPeriodMonth the month (1–12) of the Filed_Period being corrected
 * @param originalPeriodYear  the year of the Filed_Period being corrected
 * @param originalDocumentRef the corrected document reference (order code / note number)
 * @param targetPeriodMonth   the target period month, or {@code null} while PENDING
 * @param targetPeriodYear    the target period year, or {@code null} while PENDING
 * @param originalValueJson   the originally filed value as JSON (Req 3.4)
 * @param correctedValueJson  the latest corrected value as JSON (Req 3.8)
 * @param createdAt           when the amendment was first detected
 * @param updatedAt           when the amendment was last consolidated
 */
public record AmendmentResponse(
        Long id,
        AmendmentTable amendmentTable,
        AmendmentStatus status,
        int originalPeriodMonth,
        int originalPeriodYear,
        String originalDocumentRef,
        Integer targetPeriodMonth,
        Integer targetPeriodYear,
        String originalValueJson,
        String correctedValueJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * Maps a {@link ReturnAmendment} entity to the API payload.
     *
     * @param amendment the stored amendment
     * @return the response payload
     */
    public static AmendmentResponse from(ReturnAmendment amendment) {
        return new AmendmentResponse(
                amendment.getId(),
                amendment.getAmendmentTable(),
                amendment.getStatus(),
                amendment.getOriginalPeriodMonth(),
                amendment.getOriginalPeriodYear(),
                amendment.getOriginalDocumentRef(),
                amendment.getTargetPeriodMonth(),
                amendment.getTargetPeriodYear(),
                amendment.getOriginalValueJson(),
                amendment.getCorrectedValueJson(),
                amendment.getCreatedAt(),
                amendment.getUpdatedAt());
    }
}
