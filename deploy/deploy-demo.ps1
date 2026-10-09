# =============================================================================
# Weblithic/Shifa OMS — DEMO-ONLY deploy.  *** NEVER TOUCHES PROD ***
#
# This script is DELIBERATELY hard-wired to the DEMO box only. The production
# IP (15.252.230.73), the prod key, and the prod apply script (aws-apply.sh)
# DO NOT APPEAR anywhere in this file — so there is no flag, typo, or param that
# can make it deploy to production. Use this for every routine demo redeploy.
#
#   DEMO : 13.234.22.207   key shifa-admin.pem   DB shifa_demo
#          apply script: demo-apply.sh (backs up shifa_demo -> demo S3 bucket,
#          swaps JAR, publishes admin, restarts shifa-oms, reloads nginx).
#
# It:
#   1. (optional) builds the backend JAR + admin bundle,
#   2. uploads the JAR + admin bundle + demo-apply.sh to the DEMO box,
#   3. runs demo-apply.sh ON THE DEMO SERVER (backup -> swap -> restart -> reload).
#
# Restarting auto-applies any new Flyway migrations against shifa_demo.
#
# Usage (PowerShell, from the repo root):
#   powershell -ExecutionPolicy Bypass -File "deploy\deploy-demo.ps1"
#
# Options:
#   -SkipBuild      reuse the already-built JAR + dist\admin (no mvn / ng build)
#   -DemoKey <path> override the demo key (default C:\Users\Parul\Downloads\shifa-admin.pem)
# =============================================================================
param(
  [switch] $SkipBuild,
  [string] $DemoKey = 'C:\Users\Parul\Downloads\shifa-admin.pem'
)

$ErrorActionPreference = 'Stop'

# --- Hard-wired DEMO target (no prod values exist in this script) ------------
$DemoIp      = '13.234.22.207'
$ApplyScript = 'demo-apply.sh'

$root      = 'c:\E Drive\Shifa-Software'
$jar       = Join-Path $root 'backend\target\shifa-oms-0.0.1-SNAPSHOT.jar'
$adminDist = Join-Path $root 'frontend\dist\admin\browser'
$apply     = Join-Path $root ('deploy\' + $ApplyScript)
$remote    = "ubuntu@$DemoIp"
$o         = @('-o','StrictHostKeyChecking=accept-new','-o','ConnectTimeout=30')

# --- Safety guards -----------------------------------------------------------
if (-not (Test-Path $DemoKey)) { throw "DEMO SSH key not found: $DemoKey" }
if (-not (Test-Path $apply))   { throw "DEMO apply script not found: $apply" }
# Belt-and-suspenders: refuse to run if the apply script is anything but the
# demo one (guards against an accidental edit swapping in aws-apply.sh).
if ($ApplyScript -ne 'demo-apply.sh') { throw "Refusing: this script only runs demo-apply.sh." }

# --- 1. Build (unless -SkipBuild) -------------------------------------------
if (-not $SkipBuild) {
  Write-Host '>> [build] Backend JAR (mvn -DskipTests clean package)...' -ForegroundColor Cyan
  & mvn -q -DskipTests clean package -f (Join-Path $root 'backend\pom.xml')
  if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }

  Write-Host '>> [build] Admin bundle (npm run build:admin)...' -ForegroundColor Cyan
  Push-Location (Join-Path $root 'frontend')
  & npm run build:admin
  if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Admin build failed.' }
  Pop-Location
} else {
  Write-Host '>> Skipping build (reusing existing JAR + dist\admin).' -ForegroundColor Yellow
}

if (-not (Test-Path $jar))       { throw "JAR not found: $jar (run without -SkipBuild)" }
if (-not (Test-Path $adminDist)) { throw "Admin bundle not found: $adminDist" }
Write-Host (">> Artifact: {0:N0} bytes  {1}" -f (Get-Item $jar).Length, (Get-Item $jar).LastWriteTime) -ForegroundColor DarkGray

# --- 2. Upload to the DEMO box ----------------------------------------------
Write-Host ""
Write-Host ">> [DEMO] Uploading JAR + admin bundle + $ApplyScript to $DemoIp ..." -ForegroundColor Cyan
& ssh @o -i "$DemoKey" "$remote" "rm -rf ~/admin-dist"
if ($LASTEXITCODE -ne 0) { throw '[DEMO] SSH failed (key/IP/security-group port 22).' }
& scp @o -i "$DemoKey" "$jar" "${remote}:/home/ubuntu/shifa-oms.jar"
if ($LASTEXITCODE -ne 0) { throw '[DEMO] SCP of JAR failed.' }
& scp @o -i "$DemoKey" -r "$adminDist" "${remote}:/home/ubuntu/admin-dist"
if ($LASTEXITCODE -ne 0) { throw '[DEMO] SCP of admin bundle failed.' }
& scp @o -i "$DemoKey" "$apply" "${remote}:/home/ubuntu/$ApplyScript"
if ($LASTEXITCODE -ne 0) { throw '[DEMO] SCP of apply script failed.' }

# --- 3. Backup + swap + restart ON THE DEMO SERVER ---------------------------
Write-Host ">> [DEMO] Backup shifa_demo + swap + restart on the server ..." -ForegroundColor Cyan
& ssh @o -i "$DemoKey" "$remote" "sed -i 's/\r`$//' ~/$ApplyScript; bash ~/$ApplyScript"
if ($LASTEXITCODE -ne 0) { throw '[DEMO] remote apply failed - check journalctl on the demo box.' }

Write-Host ""
Write-Host ">> DEMO DONE -> https://shifa-demo.weblithic.online/" -ForegroundColor Green
Write-Host ">> Verify:" -ForegroundColor Yellow
Write-Host "     ssh -i `"$DemoKey`" $remote `"sudo journalctl -u shifa-oms -n 40 --no-pager`""
