/**
 * Automated Screenshot Capture for Shifa OMS
 * 
 * This script logs into the application as different users and captures
 * all required screenshots for the Features Guide documentation.
 * 
 * Prerequisites:
 * 1. Node.js installed
 * 2. Run: npm install playwright
 * 3. Run: npx playwright install chromium
 * 
 * Usage:
 * node capture-screenshots.js
 */

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

// Configuration
const CONFIG = {
  baseUrl: 'http://13.207.62.222',
  screenshotsDir: path.join(__dirname, 'screenshots'),
  mobileScreenshotsDir: path.join(__dirname, 'screenshots', 'mobile'),
  viewport: {
    desktop: { width: 1400, height: 900 },
    tablet: { width: 768, height: 1024 },
    mobile: { width: 360, height: 800 }
  },
  credentials: {
    admin: { username: 'admin', password: 'admin123' },
    salesperson: { username: 'sales1', password: 'admin123' },
    packer: { username: 'packer', password: 'packer123' },
    accountant: { username: 'accountant', password: 'admin123' }
  }
};

// Create directories if they don't exist
if (!fs.existsSync(CONFIG.screenshotsDir)) {
  fs.mkdirSync(CONFIG.screenshotsDir, { recursive: true });
}
if (!fs.existsSync(CONFIG.mobileScreenshotsDir)) {
  fs.mkdirSync(CONFIG.mobileScreenshotsDir, { recursive: true });
}

// Helper function to wait and ensure page is loaded
async function waitForPageLoad(page, timeout = 2000) {
  await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
  await page.waitForTimeout(timeout);
}

// Login function
async function login(page, credentials) {
  console.log(`  Logging in as ${credentials.username}...`);
  await page.goto(CONFIG.baseUrl);
  await page.fill('input[name="username"]', credentials.username);
  await page.fill('input[type="password"]', credentials.password);
  await page.click('button[type="submit"]');
  await waitForPageLoad(page, 3000);
}

// Screenshot capture function with error handling
async function captureScreenshot(page, filename, fullPage = false) {
  try {
    const filepath = path.join(CONFIG.screenshotsDir, filename);
    await page.screenshot({ 
      path: filepath, 
      fullPage: fullPage,
      animations: 'disabled'
    });
    console.log(`  ✓ Captured: ${filename}`);
    return true;
  } catch (error) {
    console.error(`  ✗ Failed: ${filename} - ${error.message}`);
    return false;
  }
}

// Main screenshot capture function
async function captureAllScreenshots() {
  console.log('Starting automated screenshot capture...\n');
  
  const browser = await chromium.launch({ 
    headless: false, // Set to true for headless mode
    slowMo: 100 // Slow down for visibility
  });

  try {
    // ========== SECTION 1: DASHBOARDS ==========
    console.log('\n📊 Section 1: Dashboards & Overview');
    
    // Admin Dashboard
    let page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.admin);
    await page.goto(`${CONFIG.baseUrl}/dashboard`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'admin-dashboard.png');
    await page.close();

    // Salesperson Dashboard
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.salesperson);
    await page.goto(`${CONFIG.baseUrl}/dashboard`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'salesperson-dashboard.png');
    await page.close();

    // Packer Dashboard
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.packer);
    await page.goto(`${CONFIG.baseUrl}/dashboard`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'packer-dashboard.png');
    await page.close();

    // Accountant Dashboard
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.accountant);
    await page.goto(`${CONFIG.baseUrl}/dashboard`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'accountant-dashboard.png');
    await page.close();

    // ========== SECTION 2: ORDER MANAGEMENT ==========
    console.log('\n📦 Section 2: Order Management');
    
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.salesperson);
    
    // New Order Form - Top
    await page.goto(`${CONFIG.baseUrl}/orders/new`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'new-order-form-top.png');
    
    // Scroll to bottom for full form
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.waitForTimeout(1000);
    await captureScreenshot(page, 'new-order-form-bottom.png');
    
    // Orders List
    await page.goto(`${CONFIG.baseUrl}/orders`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'orders-list.png');
    
    // Order Detail (click first order if exists)
    try {
      const firstOrder = await page.locator('.order-card, .order-row, [class*="order"]').first();
      if (await firstOrder.count() > 0) {
        await firstOrder.click();
        await page.waitForTimeout(2000);
        await captureScreenshot(page, 'order-detail.png');
        // Close drawer if it's a modal
        await page.keyboard.press('Escape');
      }
    } catch (e) {
      console.log('  ⚠ Could not capture order detail');
    }
    
    await page.close();

    // ========== SECTION 3: APPROVAL WORKFLOW ==========
    console.log('\n✅ Section 3: Approval Workflow');
    
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.admin);
    
    // Approval Queue
    await page.goto(`${CONFIG.baseUrl}/approval-queue`).catch(() => 
      page.goto(`${CONFIG.baseUrl}/approvals`)
    );
    await waitForPageLoad(page);
    await captureScreenshot(page, 'approval-queue.png');
    
    await page.close();


    // ========== SECTION 4: PACKING & DISPATCH ==========
    console.log('\n📋 Section 4: Packing & Dispatch');
    
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.packer);
    
    // Packing Queues
    await page.goto(`${CONFIG.baseUrl}/packing`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'packing-queues.png');
    
    // Scroll to scanner if not visible
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.waitForTimeout(1000);
    await captureScreenshot(page, 'barcode-scanner.png');
    
    await page.close();

    // ========== SECTION 5: COURIER & TRACKING ==========
    console.log('\n🚚 Section 5: Courier & Tracking');
    
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.admin);
    
    // Courier Records (try multiple possible routes)
    const courierRoutes = ['/reconciliation/couriers', '/couriers', '/reconciliation'];
    for (const route of courierRoutes) {
      try {
        await page.goto(`${CONFIG.baseUrl}${route}`, { waitUntil: 'networkidle', timeout: 5000 });
        await waitForPageLoad(page, 1000);
        await captureScreenshot(page, 'courier-records.png');
        break;
      } catch (e) {
        continue;
      }
    }
    
    await page.close();

    // ========== SECTION 6: COD RECONCILIATION ==========
    console.log('\n💰 Section 6: COD Reconciliation');
    
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.accountant);
    
    // Receivables
    await page.goto(`${CONFIG.baseUrl}/reconciliation/receivables`).catch(() => 
      page.goto(`${CONFIG.baseUrl}/receivables`)
    );
    await waitForPageLoad(page);
    await captureScreenshot(page, 'receivables-list.png');
    
    await page.close();

    // ========== SECTION 7: FINANCE & INVOICING ==========
    console.log('\n📊 Section 7: Finance & Invoicing');
    
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.accountant);
    
    // Expenses
    await page.goto(`${CONFIG.baseUrl}/finance/expenses`).catch(() => 
      page.goto(`${CONFIG.baseUrl}/expenses`)
    );
    await waitForPageLoad(page);
    await captureScreenshot(page, 'expenses-page.png');
    
    // P&L
    await page.goto(`${CONFIG.baseUrl}/finance/pnl`).catch(() => 
      page.goto(`${CONFIG.baseUrl}/finance`)
    );
    await waitForPageLoad(page);
    await captureScreenshot(page, 'pnl-report.png');
    
    await page.close();

    // ========== SECTION 8: PRODUCTS & INVENTORY ==========
    console.log('\n📦 Section 8: Products & Inventory');
    
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.admin);
    
    // Products
    await page.goto(`${CONFIG.baseUrl}/products`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'products-list.png');
    
    // Inventory
    await page.goto(`${CONFIG.baseUrl}/inventory`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'inventory-page.png');
    
    // Purchase Orders
    await page.goto(`${CONFIG.baseUrl}/procurement/purchase-orders`).catch(() => 
      page.goto(`${CONFIG.baseUrl}/purchase-orders`)
    );
    await waitForPageLoad(page);
    await captureScreenshot(page, 'purchase-orders.png');
    
    await page.close();

    // ========== SECTION 9: LEADS & CRM ==========
    console.log('\n👥 Section 9: Leads & CRM');
    
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.salesperson);
    
    // Leads List
    await page.goto(`${CONFIG.baseUrl}/leads`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'leads-list.png');
    
    // New Lead Form
    const newLeadBtn = await page.locator('button:has-text("New Lead"), a:has-text("New Lead")').first();
    if (await newLeadBtn.count() > 0) {
      await newLeadBtn.click();
      await page.waitForTimeout(2000);
      await captureScreenshot(page, 'capture-lead-form.png');
      await page.keyboard.press('Escape');
    }
    
    // Click first lead for detail
    try {
      const firstLead = await page.locator('.lead-card, [class*="lead"]').first();
      if (await firstLead.count() > 0) {
        await firstLead.click();
        await page.waitForTimeout(2000);
        await captureScreenshot(page, 'lead-detail.png');
      }
    } catch (e) {
      console.log('  ⚠ Could not capture lead detail');
    }
    
    await page.close();

    // ========== SECTION 10: STAFF MANAGEMENT ==========
    console.log('\n👤 Section 10: Staff Management');
    
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.admin);
    
    // Salespeople Directory
    await page.goto(`${CONFIG.baseUrl}/settings`);
    await waitForPageLoad(page);
    try {
      await page.click('a:has-text("Salespeople"), button:has-text("Salespeople")');
      await page.waitForTimeout(2000);
    } catch (e) {
      await page.goto(`${CONFIG.baseUrl}/salespeople`).catch(() => {});
    }
    await waitForPageLoad(page);
    await captureScreenshot(page, 'salespeople-directory.png');
    
    // Click first salesperson for profile
    try {
      const firstSalesperson = await page.locator('.salesperson-card, [class*="staff"], [class*="salesperson"]').first();
      if (await firstSalesperson.count() > 0) {
        await firstSalesperson.click();
        await page.waitForTimeout(2000);
        await captureScreenshot(page, 'salesperson-profile.png');
      }
    } catch (e) {
      console.log('  ⚠ Could not capture salesperson profile');
    }
    
    await page.close();
    
    // My Profile (as salesperson)
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.salesperson);
    await page.goto(`${CONFIG.baseUrl}/my-profile`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'my-profile-page.png');
    await page.close();

    // Profile Approvals (as admin)
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.admin);
    await page.goto(`${CONFIG.baseUrl}/profile-approvals`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'profile-approvals.png');
    await page.close();

    // ========== SECTION 11: REPORTS & INSIGHTS ==========
    console.log('\n📈 Section 11: Reports & Insights');
    
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.admin);
    
    // Reports
    await page.goto(`${CONFIG.baseUrl}/reports`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'reports-page.png');
    
    // Insights
    await page.goto(`${CONFIG.baseUrl}/insights`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'insights-page.png');
    
    await page.close();

    // ========== SECTION 12: NOTIFICATIONS ==========
    console.log('\n🔔 Section 12: Notifications');
    
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.admin);
    await page.goto(`${CONFIG.baseUrl}/dashboard`);
    await waitForPageLoad(page);
    
    // Click notification bell
    try {
      await page.click('[class*="notification"], [class*="bell"], button:has([class*="bell"])');
      await page.waitForTimeout(1500);
      await captureScreenshot(page, 'notification-dropdown.png');
    } catch (e) {
      console.log('  ⚠ Could not capture notification dropdown');
    }
    
    // Notifications page
    await page.goto(`${CONFIG.baseUrl}/notifications`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'notifications-list.png');
    
    await page.close();

    // ========== SECTION 13: SECURITY & AUDIT ==========
    console.log('\n🔒 Section 13: Security & Audit');
    
    // Login page (not logged in)
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await page.goto(CONFIG.baseUrl);
    await waitForPageLoad(page, 1000);
    await captureScreenshot(page, 'login-page.png');
    await page.close();
    
    // Audit log
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.admin);
    await page.goto(`${CONFIG.baseUrl}/audit`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'audit-log.png');
    await page.close();


    // ========== SECTION 14: MOBILE EXPERIENCE ==========
    console.log('\n📱 Section 14: Mobile Experience');
    
    // Mobile Dashboard
    page = await browser.newPage({ viewport: CONFIG.viewport.mobile });
    await login(page, CONFIG.credentials.admin);
    await page.goto(`${CONFIG.baseUrl}/dashboard`);
    await waitForPageLoad(page);
    const mobileScreenshot = path.join(CONFIG.mobileScreenshotsDir, 'dashboard-mobile.png');
    await page.screenshot({ path: mobileScreenshot, fullPage: true });
    console.log(`  ✓ Captured: mobile/dashboard-mobile.png`);
    
    // Mobile bottom tabs
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.waitForTimeout(1000);
    const tabsScreenshot = path.join(CONFIG.mobileScreenshotsDir, 'bottom-tabs.png');
    await page.screenshot({ path: tabsScreenshot });
    console.log(`  ✓ Captured: mobile/bottom-tabs.png`);
    
    // Mobile order detail
    await page.goto(`${CONFIG.baseUrl}/orders`);
    await waitForPageLoad(page);
    try {
      const firstOrder = await page.locator('.order-card, [class*="order"]').first();
      if (await firstOrder.count() > 0) {
        await firstOrder.click();
        await page.waitForTimeout(2000);
        const orderDetailMobile = path.join(CONFIG.mobileScreenshotsDir, 'order-detail-mobile.png');
        await page.screenshot({ path: orderDetailMobile, fullPage: true });
        console.log(`  ✓ Captured: mobile/order-detail-mobile.png`);
      }
    } catch (e) {
      console.log('  ⚠ Could not capture mobile order detail');
    }
    await page.close();
    
    // Tablet View
    page = await browser.newPage({ viewport: CONFIG.viewport.tablet });
    await login(page, CONFIG.credentials.admin);
    await page.goto(`${CONFIG.baseUrl}/dashboard`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'tablet-view.png');
    await page.close();

    // ========== ADDITIONAL SCREENSHOTS ==========
    console.log('\n📸 Additional Screenshots');
    
    // Desktop orders (for comparison)
    page = await browser.newPage({ viewport: CONFIG.viewport.desktop });
    await login(page, CONFIG.credentials.admin);
    await page.goto(`${CONFIG.baseUrl}/orders`);
    await waitForPageLoad(page);
    await captureScreenshot(page, 'orders-desktop.png');
    await page.close();
    
    // Mobile orders (for comparison)
    page = await browser.newPage({ viewport: CONFIG.viewport.mobile });
    await login(page, CONFIG.credentials.admin);
    await page.goto(`${CONFIG.baseUrl}/orders`);
    await waitForPageLoad(page);
    const ordersMobile = path.join(CONFIG.mobileScreenshotsDir, 'orders-mobile.png');
    await page.screenshot({ path: ordersMobile, fullPage: true });
    console.log(`  ✓ Captured: mobile/orders-mobile.png`);
    await page.close();

    console.log('\n✅ Screenshot capture complete!');
    console.log(`\nScreenshots saved to: ${CONFIG.screenshotsDir}`);
    console.log(`Mobile screenshots saved to: ${CONFIG.mobileScreenshotsDir}`);
    
  } catch (error) {
    console.error('\n❌ Error during screenshot capture:', error);
  } finally {
    await browser.close();
  }
}

// Run the script
captureAllScreenshots().catch(console.error);
