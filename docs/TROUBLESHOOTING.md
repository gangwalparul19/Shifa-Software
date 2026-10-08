# Troubleshooting Screenshot Capture

## Script Stopped / Browser Closed

If the script stopped and the browser closed immediately, follow these steps:

### Step 1: Test Login First

Run the login test script to verify credentials work:

```cmd
cd "c:\E Drive\Shifa-Software\docs"
test-login.bat
```

OR double-click: `test-login.bat`

This will:
1. Open browser
2. Navigate to login page
3. Show you what fields it found
4. Fill in credentials (admin/admin123)
5. Attempt login
6. Take screenshots at each step
7. Stay open for 10 seconds so you can see the result

**Screenshots created:**
- `test-login-page.png` - The login page
- `test-form-filled.png` - Form with credentials filled
- `test-after-login.png` - After successful login (or test-login-failed.png)

### Step 2: Check the Output

Look at the console output:

**✅ If you see "LOGIN SUCCESSFUL":**
- Great! Your credentials work
- Run the full screenshot capture: `run-screenshot-capture.bat`

**❌ If you see "LOGIN FAILED":**
- Check the URL is correct: http://13.207.62.222
- Verify credentials: admin / admin123
- Check server is running
- Look at the error screenshots

### Step 3: Common Issues and Fixes

#### Issue: "Could not find username field"

**Cause:** Login form selectors don't match
**Fix:** The test script will show you all input fields on the page. Check `test-login-page.png`

1. Look at the console output - it lists all input fields
2. Note the correct `name`, `id`, or `placeholder` attributes
3. Tell me what you see, and I'll update the selectors

#### Issue: "Could not find submit button"

**Cause:** Submit button selector doesn't match
**Fix:** The script will try pressing Enter as fallback

If that doesn't work:
1. Look at `test-form-filled.png`
2. Tell me what the button says (Login, Sign In, etc.)
3. I'll update the selector

#### Issue: Login form fills but stays on login page

**Possible causes:**
1. **Incorrect credentials** - Verify: admin / admin123
2. **Server issue** - Check http://13.207.62.222 works in your browser
3. **Network delay** - Script waits 5 seconds, might need more

**Fix:** Open `test-login-failed.png` and check for error messages

#### Issue: Browser closes immediately without any output

**Cause:** Playwright not installed correctly

**Fix:**
```cmd
cd "c:\E Drive\Shifa-Software\docs"
npm install
npx playwright install chromium
```

#### Issue: "Cannot find module 'playwright'"

**Fix:**
```cmd
cd "c:\E Drive\Shifa-Software\docs"
npm install
```

### Step 4: Manual Verification

Try logging in manually to verify credentials:

1. Open browser
2. Go to: http://13.207.62.222
3. Login with: admin / admin123
4. If it works manually but not in script, tell me - we'll adjust the wait times

### Step 5: Verbose Debug Mode

If test-login.bat passes but full script fails, we can add more debugging.

Tell me:
1. Which section it failed on (Dashboard, Orders, etc.)
2. The last message you saw before it stopped
3. Any error messages

### Common Error Messages

#### "TimeoutError: Timeout 15000ms exceeded"

**Meaning:** Page took too long to load

**Fix:** Increase timeout or check server performance

#### "Error: Page closed"

**Meaning:** Browser closed unexpectedly

**Fix:** Check if a popup or modal blocked navigation

#### "Error: net::ERR_CONNECTION_REFUSED"

**Meaning:** Server not running or wrong URL

**Fix:** Verify http://13.207.62.222 is accessible

### Getting Help

If you're stuck, tell me:

1. Output from `test-login.bat`
2. Contents of test screenshots (describe what you see)
3. Any error messages
4. Whether manual login works

I'll update the script with the correct selectors for your login page!

---

## Quick Fix Checklist

- [ ] Server running at http://13.207.62.222
- [ ] Credentials are admin / admin123
- [ ] Node.js installed (`node --version`)
- [ ] Dependencies installed (`npm install`)
- [ ] Chromium installed (`npx playwright install chromium`)
- [ ] Manual login works in browser
- [ ] Ran `test-login.bat` to verify
- [ ] Checked error screenshots

---

## Alternative: Run with More Wait Time

If pages are slow to load, edit `capture-screenshots.js` line 18:

```javascript
// Change this:
async function waitForPageLoad(page, timeout = 2000) {

// To this (5 seconds instead of 2):
async function waitForPageLoad(page, timeout = 5000) {
```

Then run again: `run-screenshot-capture.bat`
