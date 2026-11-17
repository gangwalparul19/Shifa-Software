# =============================================================================
# Shifa OMS: read-only checks around a PRODUCTION release.
#
#   -Stage Pre     BEFORE deploying: records a database fingerprint (baseline) and saves the
#                  live JAR + admin bundle aside on the server, so a rollback is one copy away.
#   -Stage Verify  AFTER deploying: journal (Flyway / startup), health, new-endpoint auth
#                  checks and the database fingerprint, compared with the baseline.
#
# Changes nothing in the database or the running service. The only server-side writes are the
# rollback copies made by -Stage Pre (in /home/ubuntu). Reports go to deploy\reports.
#
# Usage (from the repo root; run as a background process, SSH can take a minute):
#   powershell -ExecutionPolicy Bypass -File deploy\prod-release-check.ps1 -Stage Pre
#   powershell -ExecutionPolicy Bypass -File deploy\deploy-both.ps1 -SkipBuild -ProdOnly
#   powershell -ExecutionPolicy Bypass -File deploy\prod-release-check.ps1 -Stage Verify
#
# Every INVARIANT line must be [SAME] after the release (unless staff legitimately edited an old
# order meanwhile). Rollback: copy ~/shifa-oms.prev-<stamp>.jar to /opt/shifa/shifa-oms.jar,
# restore ~/admin.prev-<stamp> to /var/www/shifa/admin, restart shifa-oms. If a migration ran,
# restore the pre-deploy dump instead (the old JAR cannot boot against a newer schema).
# =============================================================================
param([Parameter(Mandatory = $true)][ValidateSet('Pre', 'Verify')][string]$Stage,
      [string]$Key = 'C:\Users\Parul\Downloads\shifa-oms-prod.pem',
      [string]$Ip = '15.252.230.73')

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$opts = @('-o', 'StrictHostKeyChecking=accept-new', '-o', 'ConnectTimeout=30', '-i', $Key)
$reports = Join-Path $root 'deploy\reports'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$lines = New-Object System.Collections.Generic.List[string]

function Say([string]$t) { $script:lines.Add($t) }
function Remote([string]$cmd) {
    $r = & ssh @opts "ubuntu@$Ip" $cmd 2>&1
    foreach ($l in @($r)) { Say ([string]$l) }
}

Say "Shifa OMS production $Stage check - $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') (local) - target $Ip"
& scp @opts (Join-Path $root 'deploy\prod-fingerprint.sh') "ubuntu@${Ip}:/home/ubuntu/prod-fingerprint.sh" 2>&1 | Out-Null
& scp @opts (Join-Path $root 'deploy\verify.sh') "ubuntu@${Ip}:/home/ubuntu/verify.sh" 2>&1 | Out-Null

if ($Stage -eq 'Pre') {
    Say ''
    Say '== host =='
    Remote 'echo service=$(systemctl is-active shifa-oms) restarts=$(systemctl show shifa-oms -p NRestarts --value); ls -l /opt/shifa/shifa-oms.jar; df -h / | tail -1; free -m | head -2; date'
    Say ''
    Say '== live site before =='
    Remote "curl -s -o /dev/null -w 'root=%{http_code} ' https://shifa.weblithic.online/; curl -s -o /dev/null -w 'health=%{http_code} ' https://shifa.weblithic.online/actuator/health; curl -s -o /dev/null -w 'states=%{http_code}\n' https://shifa.weblithic.online/api/states; curl -s https://shifa.weblithic.online/ | grep -oE 'main-[A-Z0-9]+[.]js' | head -1"
    Say ''
    Say '== database fingerprint (baseline) =='
    $fpStart = $lines.Count
    Remote "tr -d '\r' < ~/prod-fingerprint.sh | bash"
    $fp = $lines.GetRange($fpStart, $lines.Count - $fpStart)
    $fp | Out-File -Encoding utf8 (Join-Path $reports "baseline-$stamp.txt")
    $fp | Out-File -Encoding utf8 (Join-Path $reports 'baseline-latest.txt')
    Say ''
    Say '== save the live JAR + admin bundle aside (rollback copies) =='
    Remote "cp -p /opt/shifa/shifa-oms.jar ~/shifa-oms.prev-$stamp.jar && sudo cp -a /var/www/shifa/admin ~/admin.prev-$stamp && sudo chown -R ubuntu:ubuntu ~/admin.prev-$stamp && ls -l ~/shifa-oms.prev-$stamp.jar && echo ROLLBACK_COPIES_SAVED stamp=$stamp"
    Say ''
    Say 'PRE_COMPLETE'
    $lines | Out-File -Encoding utf8 (Join-Path $reports "preflight-$stamp.txt")
}
else {
    Say ''
    Say '== service and journal (verify.sh) =='
    Remote "tr -d '\r' < ~/verify.sh | bash"
    Say ''
    Say '== health and endpoint auth (expect 401 without a login) =='
    Remote "curl -s -o /dev/null -w 'health=%{http_code} ' https://shifa.weblithic.online/actuator/health; curl -s -o /dev/null -w 'crm_overview=%{http_code} ' https://shifa.weblithic.online/api/admin/crm/overview; curl -s -o /dev/null -w 'leads=%{http_code} ' https://shifa.weblithic.online/api/leads; curl -s -o /dev/null -w 'post_leads=%{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' https://shifa.weblithic.online/api/leads"
    Say ''
    Say '== database fingerprint (after) =='
    $fpStart = $lines.Count
    Remote "tr -d '\r' < ~/prod-fingerprint.sh | bash"
    $after = $lines.GetRange($fpStart, $lines.Count - $fpStart)
    $after | Out-File -Encoding utf8 (Join-Path $reports "after-$stamp.txt")
    $baseline = Get-Content (Join-Path $reports 'baseline-latest.txt') | Where-Object { $_ -like 'INVARIANT*' }
    $now = $after | Where-Object { $_ -like 'INVARIANT*' }
    Say ''
    Say '== invariants: before vs after =='
    $same = $true
    for ($i = 0; $i -lt [Math]::Max($baseline.Count, $now.Count); $i++) {
        $b = if ($i -lt $baseline.Count) { $baseline[$i] } else { '(missing)' }
        $n = if ($i -lt $now.Count) { $now[$i] } else { '(missing)' }
        if ($b -eq $n) { Say "[SAME] $n" } else { $same = $false; Say "[DIFF] before: $b"; Say "       after : $n" }
    }
    Say ("INVARIANTS_IDENTICAL=" + $same)
    Say ''
    Say 'VERIFY_COMPLETE'
    $lines | Out-File -Encoding utf8 (Join-Path $reports "verify-$stamp.txt")
}
$lines | ForEach-Object { Write-Host $_ }
