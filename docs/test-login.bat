@echo off
echo ========================================
echo Testing Login Credentials
echo ========================================
echo.
echo This will test if login works before capturing all screenshots
echo Browser will stay open so you can see what happens
echo.
pause

node test-login.js

echo.
echo ========================================
echo Check the output above:
echo - If login SUCCESSFUL: Run run-screenshot-capture.bat
echo - If login FAILED: Check credentials or URL
echo.
echo Screenshots saved:
echo - test-login-page.png (login form)
echo - test-form-filled.png (after filling)
echo - test-after-login.png or test-login-failed.png (result)
echo ========================================
pause
