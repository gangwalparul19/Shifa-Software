@echo off
REM Upload Documentation to Server
REM Run this from the docs folder

echo ========================================
echo  Upload Documentation to AWS Server
echo ========================================
echo.

REM Check if we're in the docs folder
if not exist "Shifa-Features-Guide.html" (
    echo ERROR: Shifa-Features-Guide.html not found!
    echo Please run this script from the docs folder
    pause
    exit /b 1
)

echo Files found:
dir /b Shifa-*.html
echo.
echo Screenshots folder:
dir /b screenshots\*.png | find /c ".png"
echo PNG files found
echo.

echo IMPORTANT: Make sure you have:
echo 1. SSH key file ready (your-key.pem)
echo 2. SCP or WinSCP installed
echo 3. Access to server 13.207.62.222
echo.

echo ========================================
echo Manual Upload Instructions:
echo ========================================
echo.
echo Using WinSCP (Recommended for Windows):
echo 1. Open WinSCP
echo 2. Connect to: ec2-user@13.207.62.222
echo 3. Use your SSH key file
echo 4. Navigate to: /tmp/
echo 5. Upload these files:
echo    - Shifa-Features-Guide.html
echo    - Shifa-Pricing-Interactive.html
echo    - screenshots/ folder (31 PNG files)
echo.
echo Using SCP (if you have Git Bash/WSL):
echo   scp -i your-key.pem Shifa-Features-Guide.html ec2-user@13.207.62.222:/tmp/
echo   scp -i your-key.pem Shifa-Pricing-Interactive.html ec2-user@13.207.62.222:/tmp/
echo   scp -i your-key.pem -r screenshots/ ec2-user@13.207.62.222:/tmp/
echo.
echo ========================================
echo After Upload, Run on Server:
echo ========================================
echo.
echo ssh -i your-key.pem ec2-user@13.207.62.222
echo.
echo sudo mkdir -p /opt/shifa/public/docs/screenshots
echo sudo cp /tmp/Shifa-Features-Guide.html /opt/shifa/public/docs/
echo sudo cp /tmp/Shifa-Pricing-Interactive.html /opt/shifa/public/docs/
echo sudo cp -r /tmp/screenshots/* /opt/shifa/public/docs/screenshots/
echo sudo chmod -R 755 /opt/shifa/public/docs/
echo sudo chown -R shifa:shifa /opt/shifa/public/docs/
echo.
echo ========================================
echo.

pause
