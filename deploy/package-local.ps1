# =============================================================================
# Build the backend JAR + both Angular apps on THIS Windows PC and package them
# into shifa-deploy.zip (ready to scp to the OCI VM).
# Run from anywhere:  powershell -ExecutionPolicy Bypass -File deploy\package-local.ps1
# =============================================================================
$ErrorActionPreference = 'Stop'
$root = 'c:\E Drive\Shifa-Software'

Write-Host '>> [1/4] Building backend JAR (mvn -DskipTests package)...' -ForegroundColor Cyan
& mvn -q -DskipTests clean package -f (Join-Path $root 'backend\pom.xml')
if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }

Write-Host '>> [2/4] Building storefront (production)...' -ForegroundColor Cyan
Push-Location (Join-Path $root 'frontend')
& npx ng build storefront --configuration production
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Storefront build failed.' }

Write-Host '>> [3/4] Building admin (production, base-href /admin/)...' -ForegroundColor Cyan
& npx ng build admin --configuration production --base-href /admin/
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Admin build failed.' }
Pop-Location

Write-Host '>> [4/4] Packaging shifa-deploy.zip...' -ForegroundColor Cyan
$stage = Join-Path $root 'deploy-bundle'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stage 'store') | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stage 'admin') | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stage 'config') | Out-Null

Copy-Item (Join-Path $root 'backend\target\shifa-oms-0.0.1-SNAPSHOT.jar') (Join-Path $stage 'shifa-oms.jar')
Copy-Item (Join-Path $root 'frontend\dist\storefront\browser\*') (Join-Path $stage 'store') -Recurse
Copy-Item (Join-Path $root 'frontend\dist\admin\browser\*')     (Join-Path $stage 'admin') -Recurse
Copy-Item (Join-Path $root 'deploy\shifa.env.example') (Join-Path $stage 'config')
Copy-Item (Join-Path $root 'deploy\shifa-oms.service') (Join-Path $stage 'config')
Copy-Item (Join-Path $root 'deploy\nginx-shifa.conf')  (Join-Path $stage 'config')
Copy-Item (Join-Path $root 'deploy\apply-on-vm.sh')    (Join-Path $stage 'config')

$zip = Join-Path $root 'shifa-deploy.zip'
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip

$size = '{0:N1} MB' -f ((Get-Item $zip).Length / 1MB)
Write-Host ">> DONE. Created $zip ($size)" -ForegroundColor Green
Write-Host ''
Write-Host 'Next: upload and apply on the VM:' -ForegroundColor Yellow
Write-Host "  scp -i `"<KEY>`" `"$zip`" ubuntu@130.210.52.12:/home/ubuntu/"
Write-Host '  ssh -i "<KEY>" ubuntu@130.210.52.12 "bash -s" < deploy\apply-on-vm.sh'
