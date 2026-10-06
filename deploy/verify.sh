#!/usr/bin/env bash
echo "---ACTIVE---"
systemctl is-active shifa-oms
echo "---NRESTARTS---"
systemctl show shifa-oms -p NRestarts --value
echo "---START-TS---"
systemctl show shifa-oms -p ExecMainStartTimestamp --value
echo "---JOURNAL---"
sudo journalctl -u shifa-oms -n 40 --no-pager | grep -iE "flyway|migration|Started Application|Tomcat started|ERROR" || echo "(no matching journal lines)"
echo "---HTTP---"
curl -s -o /dev/null -w "root=%{http_code}\n" https://shifa.weblithic.online/
curl -s -o /dev/null -w "states=%{http_code}\n" https://shifa.weblithic.online/api/states
echo "---BUNDLE---"
curl -s https://shifa.weblithic.online/ | grep -oE "main-[A-Z0-9]+\.js" | head -1
echo "VERIFY_COMPLETE"
