package com.shifa.oms.reconciliation.dto;

import java.time.LocalDate;

/**
 * Request body for marking a receivable settled (Req 18.5). The settlement
 * {@code date} is optional; when omitted the service records today's date.
 *
 * @param date the settlement date, or {@code null} to default to today
 */
public record SettleReceivableRequest(LocalDate date) {
}
