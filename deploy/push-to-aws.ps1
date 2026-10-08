# =============================================================================
# Shifa OMS — ONE-COMMAND deploy to the AWS EC2 instance (dashboard-only).
#
#   Backend : Spring Boot fat JAR  -> /opt/shifa/shifa-oms.jar  (systemd: shifa-oms)
#   Admin   : Angular bundle       -> /var/www/shifa/admin      (Nginx, served at /)
#   MySQL 8 : ON THE SAME EC2 BOX  -> a mysqldump backup is taken BEFORE restart
#             (restarting the backend auto-applies any NEW Flyway migrations).
#
# This is the ONLY script you need for a routine redeploy. It:
#   1. (optional) builds the backend JAR + admin bundle,
#   2. uploads the JAR + admin bundle + the server-side apply script,
#   3. runs deploy/aws-apply.sh ON THE SERVER (backup -> swap -> restart -> reload).
#
# Usage (PowerShell on your PC, from the repo root or anywhere):
#   powershell -ExecutionPolicy Bypass -File "deploy\push-to-aws.ps1" `
#       -KeyPath "C:\Users\Parul\Downloads\shifa-admin.pem"
#
# Params:
#   -KeyPath   (required) path to the EC2 private key (.pem)
#   -Ip        EC2 public IP            (default 13.234.22.207)
#   -SkipBuild reuse the already-built JAR + dist\admin (skip mvn + ng build)
# =============================================================================
param(
  [Parameter(Mandatory = $true)] [string] $KeyPath,
  [string] $Ip = '13.234.22.207',
  [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
$root      = 'c:\E Drive\Shifa-Software'
$remote    = "ubuntu@$Ip"
$jar       = Join-Path $root 'backend\target\shifa-oms-0.0.1-SNAPSHOT.jar'
$adminDist = Join-Path $root 'frontend\dist\admin\browser'
$applySh   = Join-Path $root 'deploy\aws-apply.sh'
# Accept a new host key on first connect (won't override a changed one) so an
# unattended run never hangs on the interactive fingerprint prompt.
$o = @('-o', 'StrictHostKeyChecking=accept-new')

if (-not (Test-Path $KeyPath)) { throw "SSH key not found: $KeyPath" }
if (-not (Test-Path $applySh)) { throw "Missing $applySh" }

# --- 1. Build (unless -SkipBuild) -------------------------------------------
if (-not $SkipBuild) {
  Write-Host '>> [1/4] Building backend JAR (mvn -DskipTests clean package)...' -ForegroundColor Cyan
  & mvn -q -DskipTests clean package -f (Join-Path $root 'backend\pom.xml')
  if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }

  Write-Host '>> [2/4] Building admin (production, base-href /)...' -ForegroundColor Cyan
  Push-Location (Join-Path $root 'frontend')
  & npm run build:admin
  if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Admin build failed.' }
  Pop-Location
} else {
  Write-Host '>> Skipping build (reusing existing JAR + dist\admin).' -ForegroundColor Yellow
}

if (-not (Test-Path $jar))       { throw "JAR not found: $jar (run without -SkipBuild)" }
if (-not (Test-Path $adminDist)) { throw "Admin bundle not found: $adminDist" }

# --- 2. Upload artifacts -----------------------------------------------------
Write-Host '>> [3/4] Uploading JAR + admin bundle + apply script...' -ForegroundColor Cyan
& ssh $o -i "$KeyPath" "$remote" "rm -rf ~/admin-dist"
if ($LASTEXITCODE -ne 0) { throw 'SSH failed (check key path / IP / security group port 22).' }
& scp $o -i "$KeyPath" "$jar" "${remote}:/home/ubuntu/shifa-oms.jar"
if ($LASTEXITCODE -ne 0) { throw 'SCP of JAR failed.' }
& scp $o -i "$KeyPath" -r "$adminDist" "${remote}:/home/ubuntu/admin-dist"
if ($LASTEXITCODE -ne 0) { throw 'SCP of admin bundle failed.' }
& scp $o -i "$KeyPath" "$applySh" "${remote}:/home/ubuntu/aws-apply.sh"
if ($LASTEXITCODE -ne 0) { throw 'SCP of apply script failed.' }

# --- 3. Backup + swap + restart ON THE SERVER --------------------------------
# The remote command is DOUBLE-quoted so cmd/PowerShell don't eat the ';' or the
# 'sed' single-quotes; the sed strips any Windows CR so bash runs it cleanly.
Write-Host '>> [4/4] Backup DB + swap in + restart (on the server)...' -ForegroundColor Cyan
& ssh $o -i "$KeyPath" "$remote" "sed -i 's/\r$//' ~/aws-apply.sh; bash ~/aws-apply.sh"
if ($LASTEXITCODE -ne 0) { throw 'Remote apply failed — check output above / journalctl on the server.' }

Write-Host ''
Write-Host ">> DONE. Live at http://$Ip/" -ForegroundColor Green
Write-Host '>> Verify migrations + startup:' -ForegroundColor Yellow
Write-Host "     ssh -i `"$KeyPath`" $remote `"sudo journalctl -u shifa-oms -n 60 --no-pager`""
