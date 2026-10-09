# =============================================================================
# Shifa OMS — build ONCE, deploy to BOTH servers (PROD + DEMO) in one go.
#
#   PROD : 15.252.230.73  key shifa-oms-prod.pem  DB shifa_dashboard
#          backup -> s3://shifa-db-backup/            (prod account 226615093151)
#   DEMO : 13.234.22.207  key shifa-admin.pem      DB shifa_demo
#          backup -> s3://shifa-oms-demo-files/demo/db-backups/ (demo acct 060451241643)
#
# Each box: uploads the SAME JAR + admin bundle, then runs its own apply script
# (backup DB -> swap JAR -> publish admin -> restart -> reload nginx). Restarting
# auto-applies any new Flyway migrations.
#
# Usage (from the repo root, PowerShell):
#   powershell -ExecutionPolicy Bypass -File "deploy\deploy-both.ps1"
#
# Options:
#   -SkipBuild        reuse the already-built JAR + dist\admin (no mvn / ng build)
#   -ProdOnly         deploy only to prod
#   -DemoOnly         deploy only to demo
#   -ProdKey <path>   override prod key   (default C:\Users\Parul\Downloads\shifa-oms-prod.pem)
#   -DemoKey <path>   override demo key   (default C:\Users\Parul\Downloads\shifa-admin.pem)
# =============================================================================
param(
  [switch] $SkipBuild,
  [switch] $ProdOnly,
  [switch] $DemoOnly,
  [string] $ProdKey = 'C:\Users\Parul\Downloads\shifa-oms-prod.pem',
  [string] $DemoKey = 'C:\Users\Parul\Downloads\shifa-admin.pem'
)

$ErrorActionPreference = 'Stop'
$root      = 'c:\E Drive\Shifa-Software'
$mvn       = 'C:\Users\Parul\AppData\Roaming\Kiro\User\globalStorage\pleiades.java-extension-pack-jdk\maven\latest\bin\mvn.cmd'
$jar       = Join-Path $root 'backend\target\shifa-oms-0.0.1-SNAPSHOT.jar'
$adminDist = Join-Path $root 'frontend\dist\admin\browser'
$o         = @('-o','StrictHostKeyChecking=accept-new','-o','ConnectTimeout=30')

$ProdIp = '15.252.230.73'
$DemoIp = '13.234.22.207'

# ----------------------------------------------------------------------------
function Deploy-One {
  param([string]$Name, [string]$Ip, [string]$Key, [string]$ApplyScript)

  $remote = "ubuntu@$Ip"
  $apply  = Join-Path $root ("deploy\" + $ApplyScript)
  if (-not (Test-Path $Key))   { throw "[$Name] SSH key not found: $Key" }
  if (-not (Test-Path $apply)) { throw "[$Name] apply script not found: $apply" }

  Write-Host ""
  Write-Host ">> [$Name] Uploading JAR + admin bundle + apply script to $Ip ..." -ForegroundColor Cyan
  & ssh @o -i "$Key" "$remote" "rm -rf ~/admin-dist"
  if ($LASTEXITCODE -ne 0) { throw "[$Name] SSH failed (key/IP/security-group port 22)." }
  & scp @o -i "$Key" "$jar" "${remote}:/home/ubuntu/shifa-oms.jar"
  if ($LASTEXITCODE -ne 0) { throw "[$Name] SCP of JAR failed." }
  & scp @o -i "$Key" -r "$adminDist" "${remote}:/home/ubuntu/admin-dist"
  if ($LASTEXITCODE -ne 0) { throw "[$Name] SCP of admin bundle failed." }
  & scp @o -i "$Key" "$apply" "${remote}:/home/ubuntu/$ApplyScript"
  if ($LASTEXITCODE -ne 0) { throw "[$Name] SCP of apply script failed." }

  Write-Host ">> [$Name] Backup DB + swap + restart on the server ..." -ForegroundColor Cyan
  & ssh @o -i "$Key" "$remote" "sed -i 's/\r`$//' ~/$ApplyScript; bash ~/$ApplyScript"
  if ($LASTEXITCODE -ne 0) { throw "[$Name] remote apply failed - check journalctl on the box." }
  Write-Host ">> [$Name] DONE." -ForegroundColor Green
}

# --- 1. Build ONCE -----------------------------------------------------------
if (-not $SkipBuild) {
  Write-Host '>> [build] Backend JAR (mvn -DskipTests clean package)...' -ForegroundColor Cyan
  & $mvn -q -DskipTests clean package -f (Join-Path $root 'backend\pom.xml')
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

# --- 2. Deploy ---------------------------------------------------------------
if (-not $DemoOnly) { Deploy-One -Name 'PROD' -Ip $ProdIp -Key $ProdKey -ApplyScript 'aws-apply.sh' }
if (-not $ProdOnly) { Deploy-One -Name 'DEMO' -Ip $DemoIp -Key $DemoKey -ApplyScript 'demo-apply.sh' }

Write-Host ""
Write-Host ">> ALL DONE." -ForegroundColor Green
if (-not $DemoOnly) { Write-Host "   PROD  -> https://shifa.weblithic.online/"      -ForegroundColor Green }
if (-not $ProdOnly) { Write-Host "   DEMO  -> https://shifa-demo.weblithic.online/" -ForegroundColor Green }
Write-Host ""
Write-Host ">> Verify (example):" -ForegroundColor Yellow
Write-Host "     ssh -i `"$ProdKey`" ubuntu@$ProdIp `"sudo journalctl -u shifa-oms -n 50 --no-pager`""
Write-Host "     ssh -i `"$DemoKey`" ubuntu@$DemoIp `"sudo journalctl -u shifa-oms -n 50 --no-pager`""
