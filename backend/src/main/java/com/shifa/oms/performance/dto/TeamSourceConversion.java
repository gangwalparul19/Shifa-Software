package com.shifa.oms.performance.dto;

/**
 * Lead-source conversion for a team: how many leads came from a source and how
 * many were won, with the conversion rate (won / leads, 0–100%). Powers the
 * "which source converts best" view on the team-lead performance page.
 *
 * @param source         the lead source (enum name, e.g. WHATSAPP/INSTAGRAM)
 * @param leads          leads captured from this source
 * @param won            leads from this source that converted (WON)
 * @param conversionRate won / leads as a percentage (0–100)
 */
public record TeamSourceConversion(String source, long leads, long won, double conversionRate) {
}
