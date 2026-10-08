@echo off
echo ========================================
echo Shifa OMS - Automated Screenshot Capture
echo ========================================
echo.

REM Check if Node.js is installed
where node >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Node.js is not installed!
    echo Please install Node.js from https://nodejs.org/
    echo.
    pause
    exit /b 1
)

echo Node.js version:
node --version
echo.

REM Check if node_modules exists
if not exist node_modules (
    echo Installing dependencies...
    call npm install
    echo.
    
    echo Installing Chromium browser...
    call npx playwright install chromium
    echo.
)

echo Starting screenshot capture...
echo This will take approximately 5-10 minutes
echo.
echo You will see a browser window open and navigate automatically
echo Do not close the browser - it will close automatically when done
echo.
pause

node capture-screenshots.js

echo.
echo ========================================
echo Screenshot capture complete!
echo.
echo Screenshots saved to: docs\screenshots\
echo Mobile screenshots: docs\screenshots\mobile\
echo.
echo Next step: AI will update the Features Guide HTML
echo ========================================
pause
