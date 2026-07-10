# Automated Screenshot Capture Guide

This guide explains how to use the automated screenshot capture script to capture all 45+ screenshots for the Shifa OMS Features Guide in one go.

## Prerequisites

1. **Node.js installed** (version 14 or higher)
   - Check: `node --version`
   - Download from: https://nodejs.org/

2. **Server running** at http://13.207.62.222
   - Ensure the application is accessible
   - Test accounts should be available

3. **Windows Command Prompt or PowerShell**

## Installation Steps

### Step 1: Navigate to docs folder

```cmd
cd "c:\E Drive\Shifa-Software\docs"
```

### Step 2: Install dependencies

```cmd
npm install
```

This will install Playwright and all required dependencies.

### Step 3: Install Chromium browser

```cmd
npm run install-browser
```

Or manually:
```cmd
npx playwright install chromium
```

This downloads the Chromium browser that Playwright will use for automation (~150MB download).

## Running the Screenshot Capture

### Option 1: Run with visible browser (Recommended first time)

```cmd
node capture-screenshots.js
```

This will:
- Open a visible Chrome browser window
- Login as different users (admin, salesperson, packer, accountant)
- Navigate to each page
- Capture screenshots automatically
- Save to `docs/screenshots/` folder
- Take approximately **5-10 minutes** to complete

You can watch the browser navigate and capture screenshots in real-time.

### Option 2: Run headless (faster)

Edit `capture-screenshots.js` line 102:
```javascript
// Change this line:
const browser = await chromium.launch({ 
  headless: false, // Change to true

// To:
const browser = await chromium.launch({ 
  headless: true,
```

Then run:
```cmd
node capture-screenshots.js
```

Headless mode is faster but you won't see the browser.

## What Gets Captured

The script captures **45+ screenshots** organized in 14 sections:

### Desktop Screenshots (`docs/screenshots/`):
1. ✅ admin-dashboard.png
2. ✅ salesperson-dashboard.png
3. ✅ packer-dashboard.png
4. ✅ accountant-dashboard.png
5. ✅ new-order-form-top.png
6. ✅ new-order-form-bottom.png
7. ✅ orders-list.png
8. ✅ order-detail.png
9. ✅ approval-queue.png
10. ✅ packing-queues.png
11. ✅ barcode-scanner.png
12. ✅ courier-records.png
13. ✅ receivables-list.png
14. ✅ expenses-page.png
15. ✅ pnl-report.png
16. ✅ products-list.png
17. ✅ inventory-page.png
18. ✅ purchase-orders.png
19. ✅ leads-list.png
20. ✅ capture-lead-form.png
21. ✅ lead-detail.png
22. ✅ salespeople-directory.png
23. ✅ salesperson-profile.png
24. ✅ my-profile-page.png
25. ✅ profile-approvals.png
26. ✅ reports-page.png
27. ✅ insights-page.png
28. ✅ notification-dropdown.png
29. ✅ notifications-list.png
30. ✅ login-page.png
31. ✅ audit-log.png
32. ✅ tablet-view.png
33. ✅ orders-desktop.png

### Mobile Screenshots (`docs/screenshots/mobile/`):
34. ✅ dashboard-mobile.png
35. ✅ bottom-tabs.png
36. ✅ order-detail-mobile.png
37. ✅ orders-mobile.png

## Expected Output

During execution, you'll see output like:

```
Starting automated screenshot capture...

📊 Section 1: Dashboards & Overview
  Logging in as admin...
  ✓ Captured: admin-dashboard.png
  Logging in as sales1...
  ✓ Captured: salesperson-dashboard.png
  ...

📦 Section 2: Order Management
  Logging in as sales1...
  ✓ Captured: new-order-form-top.png
  ✓ Captured: new-order-form-bottom.png
  ...

✅ Screenshot capture complete!

Screenshots saved to: c:\E Drive\Shifa-Software\docs\screenshots
Mobile screenshots saved to: c:\E Drive\Shifa-Software\docs\screenshots\mobile
```

## Troubleshooting

### Problem: "Cannot find module 'playwright'"
**Solution:** Run `npm install` in the docs folder

### Problem: "Chromium not installed"
**Solution:** Run `npx playwright install chromium`

### Problem: "Login failed" or "Page not found"
**Solution:** 
- Check server is running at http://13.207.62.222
- Verify login credentials are correct
- Check network/firewall settings

### Problem: Some screenshots are blank or missing
**Solution:**
- Increase wait timeouts in script (lines with `waitForPageLoad`)
- Run script again (it will overwrite existing screenshots)
- Some pages may not exist yet (that's okay)

### Problem: Script hangs or freezes
**Solution:**
- Press Ctrl+C to stop
- Check if a modal/popup is blocking navigation
- Restart and run again

## Customizing the Script

### Change viewport sizes:
Edit lines 17-21 in `capture-screenshots.js`:
```javascript
viewport: {
  desktop: { width: 1400, height: 900 },  // Adjust these
  tablet: { width: 768, height: 1024 },
  mobile: { width: 360, height: 800 }
}
```

### Add more screenshots:
Follow the pattern in the script:
```javascript
await page.goto(`${CONFIG.baseUrl}/your-page`);
await waitForPageLoad(page);
await captureScreenshot(page, 'your-screenshot.png');
```

### Change wait times:
If pages load slowly, increase timeout:
```javascript
await waitForPageLoad(page, 3000); // 3 seconds instead of 2
```

## After Capture

Once screenshots are captured, I (the AI) can:
1. Read all the screenshot files from the `docs/screenshots/` folder
2. Update `Shifa-Features-Guide.html` to replace all placeholders with real screenshots
3. Verify all images are correctly placed

Just run the script and let me know when it's complete!

## Manual Capture (Fallback)

If automation doesn't work, you can still manually capture screenshots following `Screenshot-Guide.md`. The automation script just saves time.

## Script Maintenance

- **Update URLs:** If routes change, edit the `goto()` calls
- **Update Credentials:** If passwords change, edit the `CONFIG.credentials` section
- **Add New Sections:** Follow the existing pattern for new features

## Performance

- **Headless mode:** ~3-5 minutes
- **Visible browser:** ~5-10 minutes  
- **Network speed:** Affects load times
- **Server performance:** Affects page render times

## Security Note

The script contains login credentials for test accounts only. Never commit production credentials to version control.

---

**Quick Start:**
```cmd
cd "c:\E Drive\Shifa-Software\docs"
npm install
npm run install-browser
node capture-screenshots.js
```

**Expected Time:** 5-10 minutes
**Expected Output:** 37+ screenshots in `docs/screenshots/` folder
