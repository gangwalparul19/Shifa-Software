$ErrorActionPreference = 'Stop'
$root  = 'c:\E Drive\Shifa-Software'
$stage = Join-Path $root 'deploy-bundle'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stage 'store')  | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stage 'admin')  | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stage 'config') | Out-Null

Copy-Item (Join-Path $root 'backend\target\shifa-oms-0.0.1-SNAPSHOT.jar') (Join-Path $stage 'shifa-oms.jar')
Copy-Item (Join-Path $root 'frontend\dist\storefront\browser\*') (Join-Path $stage 'store') -Recurse
Copy-Item (Join-Path $root 'frontend\dist\admin\browser\*')     (Join-Path $stage 'admin') -Recurse
Copy-Item (Join-Path $root 'deploy\shifa.env.example')  (Join-Path $stage 'config')
Copy-Item (Join-Path $root 'deploy\shifa-oms.service')  (Join-Path $stage 'config')
Copy-Item (Join-Path $root 'deploy\nginx-shifa.conf')   (Join-Path $stage 'config')

$zip = Join-Path $root 'shifa-deploy.zip'
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip

$size = '{0:N1} MB' -f ((Get-Item $zip).Length / 1MB)
"ZIP_OK $size" | Out-File -FilePath (Join-Path $root 'zipsize.txt') -Encoding ascii
