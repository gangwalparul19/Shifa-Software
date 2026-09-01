package com.shifa.oms.gst.filing.dto;

import com.shifa.oms.gst.filing.FilingSnapshot;
import com.shifa.oms.gst.filing.domain.ReturnType;

import java.time.LocalDateTime;

/**
 * One entry in a return's filing-snapshot history for the API (GST returns &amp; filing, Req 5.7):
 * its monotonic version, who filed it, and when — never the payload figures (the current figures are
 * served by the filing-aware provider / export). Reopening + re-filing appends a new higher-version
 * snapshot, so a list of these presents the full filing history oldest-version first.
 *
 * <p>Timestamps use {@link LocalDateTime} (Asia/Kolkata to the second, as stored) — the timestamp
 * type every other response DTO in the codebase uses.
 *
 * @param returnType the return type this snapshot captured (GSTR-1 or GSTR-3B)
 * @param month      the reporting month, 1–12
 * @param year       the four-digit reporting year
 * @param version    the monotonic snapshot version (from {@code 1}; highest is current — Req 5.6)
 * @param filedBy    the acting user who filed this version
 * @param filedAt    the filing timestamp (Asia/Kolkata, to the second — Req 5.1)
 */
public record SnapshotResponse(
        ReturnType returnType,
        int month,
        int year,
        int version,
        String filedBy,
        LocalDateTime filedAt) {

    /**
     * Maps a {@link FilingSnapshot} entity to the API payload (metadata only, no payload figures).
     *
     * @param snapshot the stored filing snapshot
     * @return the response payload
     */
    public static SnapshotResponse from(FilingSnapshot snapshot) {
        return new SnapshotResponse(
                snapshot.getReturnType(),
                snapshot.getPeriodMonth(),
                snapshot.getPeriodYear(),
                snapshot.getVersion(),
                snapshot.getFiledBy(),
                snapshot.getFiledAt());
    }
}
