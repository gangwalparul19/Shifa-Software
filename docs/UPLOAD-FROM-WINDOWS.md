# Upload Documentation from Windows to Server

## Files to Upload
- From: `C:\E Drive\Shifa-Software\docs\`
- To: Server `13.207.62.222` at `/opt/shifa/public/docs/`

---

## Method 1: Using WinSCP (Easiest)

### Step 1: Download WinSCP
If not installed: https://winscp.net/eng/download.php

### Step 2: Connect to Server
1. Open WinSCP
2. Click "New Site"
3. Fill in:
   - File protocol: SFTP
   - Host name: `13.207.62.222`
   - Port: `22`
   - User name: `ec2-user`
4. Click "Advanced" → "SSH" → "Authentication"
5. Select your private key (.pem or .ppk file)
6. Click "OK" then "Login"

### Step 3: Upload Files
1. **Left panel** (local): Navigate to `C:\E Drive\Shifa-Software\docs\`
2. **Right panel** (server): Navigate to `/tmp/`
3. **Drag and drop** these files from left to right:
   - `Shifa-Features-Guide.html`
   - `Shifa-Pricing-Interactive.html`
   - `screenshots` folder (the whole folder)

4. Wait for upload to complete (should take ~1 minute)

### Step 4: Move Files to Correct Location
Open PuTTY or Windows Terminal and SSH to server:

```bash
ssh -i your-key.pem ec2-user@13.207.62.222
```

Then run these commands:

```bash
# Create directory
sudo mkdir -p /opt/shifa/public/docs/screenshots

# Copy HTML files
sudo cp /tmp/Shifa-Features-Guide.html /opt/shifa/public/docs/
sudo cp /tmp/Shifa-Pricing-Interactive.html /opt/shifa/public/docs/

# Copy screenshots
sudo cp /tmp/screenshots/*.png /opt/shifa/public/docs/screenshots/

# Fix permissions
sudo chmod -R 755 /opt/shifa/public/docs/
sudo chown -R shifa:shifa /opt/shifa/public/docs/

# Verify
ls -la /opt/shifa/public/docs/
ls /opt/shifa/public/docs/screenshots/*.png | wc -l
# Should show: 31

# Clean up temp files
rm -rf /tmp/Shifa-Features-Guide.html
rm -rf /tmp/Shifa-Pricing-Interactive.html
rm -rf /tmp/screenshots
```

---

## Method 2: Using PowerShell with SCP

If you have OpenSSH installed on Windows (Windows 10/11 includes it):

### Step 1: Open PowerShell
Press `Win + X` → "Windows PowerShell" or "Terminal"

### Step 2: Navigate to docs folder
```powershell
cd "C:\E Drive\Shifa-Software\docs"
```

### Step 3: Upload files via SCP
```powershell
# Upload HTML files (one at a time)
scp -i "C:\path\to\your-key.pem" Shifa-Features-Guide.html ec2-user@13.207.62.222:/tmp/

scp -i "C:\path\to\your-key.pem" Shifa-Pricing-Interactive.html ec2-user@13.207.62.222:/tmp/

# Upload screenshots folder
scp -i "C:\path\to\your-key.pem" -r screenshots ec2-user@13.207.62.222:/tmp/
```

### Step 4: SSH and move files (same as Method 1 Step 4)
```powershell
ssh -i "C:\path\to\your-key.pem" ec2-user@13.207.62.222
```

Then run the same commands from Method 1 Step 4.

---

## Method 3: Using Git Bash (If installed)

### Step 1: Open Git Bash
Right-click in `C:\E Drive\Shifa-Software\docs\` → "Git Bash Here"

### Step 2: Upload files
```bash
# Upload HTML files
scp -i /path/to/your-key.pem Shifa-Features-Guide.html ec2-user@13.207.62.222:/tmp/

scp -i /path/to/your-key.pem Shifa-Pricing-Interactive.html ec2-user@13.207.62.222:/tmp/

# Upload screenshots folder
scp -i /path/to/your-key.pem -r screenshots/ ec2-user@13.207.62.222:/tmp/
```

### Step 3: SSH and move files (same as Method 1 Step 4)

---

## Verification After Upload

After completing the upload and move, verify everything works:

```bash
# On the server, check files
ls -lh /opt/shifa/public/docs/

# Expected output:
# -rwxr-xr-x 1 shifa shifa 245K ... Shifa-Features-Guide.html
# -rwxr-xr-x 1 shifa shifa  89K ... Shifa-Pricing-Interactive.html
# drwxr-xr-x 2 shifa shifa 4.0K ... screenshots

# Check screenshot count
ls /opt/shifa/public/docs/screenshots/*.png | wc -l
# Should show: 31

# Test locally on server
curl -I http://localhost/docs/Shifa-Features-Guide.html
# Should return: HTTP/1.1 200 OK

# Test HTML content
curl http://localhost/docs/Shifa-Features-Guide.html | head -20
# Should show HTML code
```

**Then test in browser:**
- http://13.207.62.222/docs/Shifa-Features-Guide.html
- http://13.207.62.222/docs/Shifa-Pricing-Interactive.html

---

## File Checklist

**HTML Files (2):**
- [ ] Shifa-Features-Guide.html (245 KB)
- [ ] Shifa-Pricing-Interactive.html (89 KB)

**Screenshots Folder (31 PNG files):**
- [ ] accountant-dashboard.png
- [ ] admin-dashboard.png
- [ ] approval-queue.png
- [ ] audit-log.png
- [ ] barcode-scanner.png
- [ ] courier-records.png
- [ ] expenses-page.png
- [ ] insights-page.png
- [ ] inventory-page.png
- [ ] lead-detail.png
- [ ] leads-list.png
- [ ] login-page.png
- [ ] my-profile-page.png
- [ ] new-order-form-bottom.png
- [ ] new-order-form-top.png
- [ ] notification-dropdown.png
- [ ] notifications-list.png
- [ ] orders-desktop.png
- [ ] orders-list.png
- [ ] packer-dashboard.png
- [ ] packing-queues.png
- [ ] pnl-report.png
- [ ] products-list.png
- [ ] profile-approvals.png
- [ ] purchase-orders.png
- [ ] receivables-list.png
- [ ] reports-page.png
- [ ] salespeople-directory.png
- [ ] salesperson-dashboard.png
- [ ] salesperson-profile.png
- [ ] tablet-view.png

---

## Troubleshooting

### Issue: Permission denied when uploading
**Solution:** Upload to `/tmp/` first, then use `sudo cp` to move files

### Issue: SCP command not found
**Solution:** 
- Windows 10/11: Enable OpenSSH Client in Windows Features
- Or use WinSCP (GUI method)
- Or use Git Bash

### Issue: Connection timeout
**Solution:** Check your security group allows SSH (port 22) from your IP

### Issue: Key file permission error
**Solution:** 
- On PowerShell: `icacls "your-key.pem" /inheritance:r /grant:r "$($env:USERNAME):R"`
- Or use PuTTYgen to convert .pem to .ppk for WinSCP

---

## Quick Command Reference

```bash
# After uploading to /tmp/, run on server:

sudo mkdir -p /opt/shifa/public/docs/screenshots
sudo cp /tmp/Shifa-Features-Guide.html /opt/shifa/public/docs/
sudo cp /tmp/Shifa-Pricing-Interactive.html /opt/shifa/public/docs/
sudo cp /tmp/screenshots/*.png /opt/shifa/public/docs/screenshots/
sudo chmod -R 755 /opt/shifa/public/docs/
sudo chown -R shifa:shifa /opt/shifa/public/docs/
ls -la /opt/shifa/public/docs/
```

---

## Expected Result

After successful upload:
- ✅ Files at `/opt/shifa/public/docs/`
- ✅ 31 screenshots at `/opt/shifa/public/docs/screenshots/`
- ✅ http://13.207.62.222/docs/Shifa-Features-Guide.html loads
- ✅ http://13.207.62.222/docs/Shifa-Pricing-Interactive.html loads
- ✅ All screenshots display correctly
- ✅ No authentication required

---

**Recommended Method:** WinSCP (easiest for Windows users)  
**Time Required:** 5-10 minutes  
**Total Upload Size:** ~15 MB
