#!/usr/bin/env bash
# Enables the QuikShipX status-tracking poller by setting the env flag in
# /etc/shifa/shifa.env, idempotently (removes any prior line, then appends).
# Takes effect on the next backend restart (the deploy does that).
set -e
ENV=/etc/shifa/shifa.env

sed -i '/^QUIKSHIPX_STATUS_FEED_AVAILABLE=/d' "$ENV"
echo 'QUIKSHIPX_STATUS_FEED_AVAILABLE=true' >> "$ENV"

# Poll active shipments every 15 minutes (courier statuses move slowly).
sed -i '/^QUIKSHIPX_TRACK_POLL_INTERVAL_MS=/d' "$ENV"
echo 'QUIKSHIPX_TRACK_POLL_INTERVAL_MS=900000' >> "$ENV"

echo "Current QuikShipX tracking settings in $ENV:"
grep -E '^QUIKSHIPX_(STATUS_FEED_AVAILABLE|TRACK_POLL_INTERVAL_MS)=' "$ENV"
