/**
 * Builds the public Delhivery tracking page URL for an AWB (QuikShipX ships via
 * Delhivery). Returns {@code null} when there is nothing to track.
 *
 * Shared by the Orders and Packing screens, which used to each carry their own
 * copy of this rule.
 */
export function delhiveryTrackUrl(awb: string | null | undefined): string | null {
  const trimmed = (awb ?? '').trim();
  return trimmed ? `https://www.delhivery.com/track-v2/package/${encodeURIComponent(trimmed)}` : null;
}
