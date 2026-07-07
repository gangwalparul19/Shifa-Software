package com.shifa.oms.returns.dto;

/**
 * Reject payload for a return ({@code POST /api/admin/returns/{id}/reject}).
 *
 * @param notes optional reason/notes recorded on rejection
 */
public record RejectReturnRequest(
        String notes
) {
}
