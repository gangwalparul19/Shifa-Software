#!/usr/bin/env bash
echo "===ACTIVE==="
systemctl is-active shifa-oms
echo "===STARTUP + FLYWAY==="
sudo journalctl -u shifa-oms -n 120 --no-pager | grep -iE "Started Application|Flyway|migration|now at version|No migration|APPLICATION FAILED|No default constructor" | tail -n 8
echo "===STATES==="
curl -s -o /dev/null -w "states=%{http_code}\n" http://127.0.0.1:8080/api/states
