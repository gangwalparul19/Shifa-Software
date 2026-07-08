package com.shifa.oms.insights.domain;

/**
 * Delivered-vs-returned counts over the current window (design &sect;Pure domain;
 * Req 7.1), the input to return-rate anomaly detection. The rate is
 * {@code returns / delivered} (0 when {@code delivered = 0}).
 *
 * @param delivered orders delivered in the window
 * @param returns   orders returned in the window
 */
public record ReturnStats(long delivered, long returns) {
}
