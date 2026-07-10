/**
 * Simple login test script to debug credential issues
 * Run this first to verify login works before capturing all screenshots
 */

const { chromium } = require('playwright');

const CONFIG = {
  baseUrl: 'http://13.207.62.222',
  credentials: {
    admin: { username: 'admin', password: 'admin123' }
  }
};

async function testLogin() {
  console.log('Testing login...\n');
  console.log(`URL: ${CONFIG.baseUrl}`);
  console.log(`Username: ${CONFIG.credentials.admin.username}`);
  console.log(`Password: ${CONFIG.credentials.admin.password}\n`);
  
  const browser = await chromium.launch({ 
    headless: false,
    slowMo: 500
  });

  try {
    const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
    
    console.log('Step 1: Navigating to login page...');
    await page.goto(CONFIG.baseUrl);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);
    
    console.log('Step 2: Taking screenshot of login page...');
    await page.screenshot({ path: 'test-login-page.png' });
    console.log('  ✓ Saved: test-login-page.png');
    
    console.log('\nStep 3: Finding login form fields...');
    
    // Try to find username field
    const usernameSelectors = [
      'input[name="username"]',
      'input[type="text"]',
      'input[placeholder*="username" i]',
      'input[placeholder*="user" i]',
      '#username',
      '[id*="username" i]'
    ];
    
    let usernameSelector = null;
    for (const selector of usernameSelectors) {
      const count = await page.locator(selector).count();
      if (count > 0) {
        console.log(`  ✓ Found username field: ${selector}`);
        usernameSelector = selector;
        break;
      }
    }
    
    if (!usernameSelector) {
      console.log('  ✗ Could not find username field!');
      console.log('\nAll input fields on page:');
      const inputs = await page.$$eval('input', els => els.map(el => ({
        type: el.type,
        name: el.name,
        id: el.id,
        placeholder: el.placeholder,
        class: el.className
      })));
      console.log(JSON.stringify(inputs, null, 2));
      
      console.log('\nBrowser will stay open for 30 seconds so you can inspect...');
      await page.waitForTimeout(30000);
      return;
    }
    
    // Try to find password field
    const passwordSelectors = [
      'input[type="password"]',
      'input[name="password"]',
      'input[placeholder*="password" i]',
      '#password'
    ];
    
    let passwordSelector = null;
    for (const selector of passwordSelectors) {
      const count = await page.locator(selector).count();
      if (count > 0) {
        console.log(`  ✓ Found password field: ${selector}`);
        passwordSelector = selector;
        break;
      }
    }
    
    if (!passwordSelector) {
      console.log('  ✗ Could not find password field!');
      return;
    }
    
    console.log('\nStep 4: Filling login form...');
    await page.fill(usernameSelector, CONFIG.credentials.admin.username);
    console.log(`  ✓ Entered username: ${CONFIG.credentials.admin.username}`);
    
    await page.fill(passwordSelector, CONFIG.credentials.admin.password);
    console.log(`  ✓ Entered password: ${CONFIG.credentials.admin.password}`);
    
    await page.screenshot({ path: 'test-form-filled.png' });
    console.log('  ✓ Saved: test-form-filled.png');
    
    console.log('\nStep 5: Finding submit button...');
    const submitSelectors = [
      'button[type="submit"]',
      'button:has-text("Login")',
      'button:has-text("Sign in")',
      'input[type="submit"]',
      'button.btn-primary'
    ];
    
    let submitSelector = null;
    for (const selector of submitSelectors) {
      const count = await page.locator(selector).count();
      if (count > 0) {
        console.log(`  ✓ Found submit button: ${selector}`);
        submitSelector = selector;
        break;
      }
    }
    
    if (!submitSelector) {
      console.log('  ⚠ Could not find submit button, will try pressing Enter');
    }
    
    console.log('\nStep 6: Submitting login...');
    if (submitSelector) {
      await page.click(submitSelector);
    } else {
      await page.keyboard.press('Enter');
    }
    
    console.log('  Waiting for navigation...');
    await page.waitForTimeout(5000);
    
    const currentUrl = page.url();
    console.log(`  Current URL: ${currentUrl}`);
    
    if (currentUrl.includes('dashboard') || !currentUrl.includes('login')) {
      console.log('\n✅ LOGIN SUCCESSFUL!');
      await page.screenshot({ path: 'test-after-login.png' });
      console.log('  ✓ Saved: test-after-login.png');
    } else {
      console.log('\n✗ LOGIN FAILED - Still on login page');
      await page.screenshot({ path: 'test-login-failed.png' });
      console.log('  ✓ Saved: test-login-failed.png');
      
      // Check for error messages
      const pageText = await page.textContent('body');
      if (pageText.toLowerCase().includes('invalid') || pageText.toLowerCase().includes('error')) {
        console.log('\n⚠️  Error message detected on page');
      }
    }
    
    console.log('\nBrowser will stay open for 10 seconds so you can verify...');
    await page.waitForTimeout(10000);
    
  } catch (error) {
    console.error('\n❌ Error:', error.message);
    console.error(error.stack);
  } finally {
    await browser.close();
    console.log('\nTest complete!');
  }
}

testLogin().catch(console.error);
