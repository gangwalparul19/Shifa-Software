@echo off
echo ====================================
echo Uploading Updated Documentation Files
echo ====================================
echo.

set KEY="C:\E Drive\Client Work\shifa-app\shifa-key.pem"
set SERVER=ubuntu@13.207.62.222
set LOCAL_DIR=docs

echo Step 1: Uploading files to /tmp/ on server...
echo.

scp -i %KEY% "%LOCAL_DIR%\Shifa-Features-Guide.html" %SERVER%:/tmp/
if errorlevel 1 (
    echo ERROR: Failed to upload Shifa-Features-Guide.html
    pause
    exit /b 1
)
echo ✓ Shifa-Features-Guide.html uploaded to /tmp/

scp -i %KEY% "%LOCAL_DIR%\Shifa-Pricing-Interactive.html" %SERVER%:/tmp/
if errorlevel 1 (
    echo ERROR: Failed to upload Shifa-Pricing-Interactive.html
    pause
    exit /b 1
)
echo ✓ Shifa-Pricing-Interactive.html uploaded to /tmp/

echo.
echo Step 2: Moving files to /opt/shifa/public/docs/
echo.
echo Please run these commands on the server:
echo.
echo   ssh -i %KEY% %SERVER%
echo   sudo mv /tmp/Shifa-Features-Guide.html /opt/shifa/public/docs/
echo   sudo mv /tmp/Shifa-Pricing-Interactive.html /opt/shifa/public/docs/
echo   sudo chown shifa:shifa /opt/shifa/public/docs/*.html
echo   exit
echo.
echo Or run this one-liner:
echo   ssh -i %KEY% %SERVER% "sudo mv /tmp/Shifa-Features-Guide.html /opt/shifa/public/docs/ && sudo mv /tmp/Shifa-Pricing-Interactive.html /opt/shifa/public/docs/ && sudo chown shifa:shifa /opt/shifa/public/docs/*.html"
echo.
pause
