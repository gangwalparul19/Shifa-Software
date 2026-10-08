# Quick Fix for 404 Error

## Problem
Getting 404 error on:
- http://13.207.62.222/docs/Shifa-Features-Guide.html
- http://13.207.62.222/docs/Shifa-Pricing-Interactive.html

## Root Cause
The documentation files haven't been uploaded to the server yet.

---

## Solution: Upload Files to Server

### Option 1: Using WinSCP (Easiest for Windows)

1. **Download WinSCP** (if not installed): https://winscp.net/

2. **Connect to Server:**
   - Protocol: SFTP or SCP
   - Host: 13.207.62.222
   - Port: 22
   - Username: ec2-user
   - Private key: Your .pem file

3. **Upload Files:**
   - Navigate on server to: `/tmp/`
   - Upload from local:
     - `Shifa-Features-Guide.html`
     - `Shifa-Pricing-Interactive.html`
     - `screenshots/` folder (drag the whole folder)

4. **SSH to Server and Run:**
   ```bash
   ssh -i your-key.pem ec2-user@13.207.62.222
   
   # Create directory
   sudo mkdir -p /opt/shifa/public/docs/screenshots
   
   # Copy files
   sudo cp /tmp/Shifa-Features-Guide.html /opt/shifa/public/docs/
   sudo cp /tmp/Shifa-Pricing-Interactive.html /opt/shifa/public/docs/
   sudo cp -r /tmp/screenshots/* /opt/shifa/public/docs/screenshots/
   
   # Set permissions
   sudo chmod -R 755 /opt/shifa/public/docs/
   sudo chown -R shifa:shifa /opt/shifa/public/docs/
   
   # Verify files
   ls -la /opt/shifa/public/docs/
   ls -la /opt/shifa/public/docs/screenshots/ | wc -l
   # Should show 33 total (. .. + 31 PNG files)
   ```

5. **Test URLs:**
   - http://13.207.62.222/docs/Shifa-Features-Guide.html
   - http://13.207.62.222/docs/Shifa-Pricing-Interactive.html

---

### Option 2: Using Git on Server (If Git is set up)

```bash
# SSH to server
ssh -i your-key.pem ec2-user@13.207.62.222

# Clone or pull latest code
cd /tmp
rm -rf Shifa-Software  # Remove old if exists
git clone https://github.com/gangwalparul19/Shifa-Software.git
cd Shifa-Software
git checkout dashboard-only

# Create docs directory
sudo mkdir -p /opt/shifa/public/docs/screenshots

# Copy documentation files
sudo cp docs/Shifa-Features-Guide.html /opt/shifa/public/docs/
sudo cp docs/Shifa-Pricing-Interactive.html /opt/shifa/public/docs/
sudo cp docs/screenshots/*.png /opt/shifa/public/docs/screenshots/

# Set permissions
sudo chmod -R 755 /opt/shifa/public/docs/
sudo chown -R shifa:shifa /opt/shifa/public/docs/

# Verify
ls -la /opt/shifa/public/docs/
ls /opt/shifa/public/docs/screenshots/ | wc -l
# Should show 31 PNG files

# Clean up
cd ~
rm -rf /tmp/Shifa-Software
```

---

### Option 3: Using SCP Command Line

If you have Git Bash or WSL on Windows:

```bash
# Open Git Bash or WSL
cd "/c/E Drive/Shifa-Software/docs"

# Upload HTML files
scp -i /path/to/your-key.pem Shifa-Features-Guide.html ec2-user@13.207.62.222:/tmp/
scp -i /path/to/your-key.pem Shifa-Pricing-Interactive.html ec2-user@13.207.62.222:/tmp/

# Upload screenshots folder
scp -i /path/to/your-key.pem -r screenshots/ ec2-user@13.207.62.222:/tmp/

# Then SSH and move files (same as Option 1 step 4)
```

---

## Verification Steps

After uploading, verify files exist on server:

```bash
# Check HTML files
ls -lh /opt/shifa/public/docs/*.html

# Expected output:
# -rwxr-xr-x 1 shifa shifa 245K Jan 10 10:00 Shifa-Features-Guide.html
# -rwxr-xr-x 1 shifa shifa  89K Jan 10 10:00 Shifa-Pricing-Interactive.html

# Check screenshots
ls /opt/shifa/public/docs/screenshots/*.png | wc -l

# Expected output: 31

# Test locally on server
curl -I http://localhost/docs/Shifa-Features-Guide.html

# Expected: HTTP/1.1 200 OK (not 404)
```

---

## Check Nginx Configuration

Make sure nginx has the `/docs/` location block:

```bash
# View nginx config
sudo cat /etc/nginx/sites-available/shifa | grep -A 10 "location /docs"

# Should show:
# location /docs/ {
#     alias /opt/shifa/public/docs/;
#     ...
# }
```

If `/docs/` location block is missing, add it:

```bash
sudo nano /etc/nginx/sites-available/shifa
```

Add this block:
```nginx
location /docs/ {
    alias /opt/shifa/public/docs/;
    autoindex off;
    auth_basic off;
    
    types {
        text/html html htm;
        image/png png;
    }
}
```

Then reload:
```bash
sudo nginx -t
sudo systemctl reload nginx
```

---

## Complete File List to Upload

Total: 33 files

**HTML Files (2):**
- Shifa-Features-Guide.html (~245 KB)
- Shifa-Pricing-Interactive.html (~89 KB)

**Screenshots (31 PNG files):**
- accountant-dashboard.png
- admin-dashboard.png
- approval-queue.png
- audit-log.png
- barcode-scanner.png
- courier-records.png
- expenses-page.png
- insights-page.png
- inventory-page.png
- lead-detail.png
- leads-list.png
- login-page.png
- my-profile-page.png
- new-order-form-bottom.png
- new-order-form-top.png
- notification-dropdown.png
- notifications-list.png
- orders-desktop.png
- orders-list.png
- packer-dashboard.png
- packing-queues.png
- pnl-report.png
- products-list.png
- profile-approvals.png
- purchase-orders.png
- receivables-list.png
- reports-page.png
- salespeople-directory.png
- salesperson-dashboard.png
- salesperson-profile.png
- tablet-view.png

---

## Troubleshooting

### Still getting 404?

1. **Check files exist:**
   ```bash
   ls -la /opt/shifa/public/docs/
   ```

2. **Check permissions:**
   ```bash
   # Should be 755 for directories, 644 for files
   sudo chmod 755 /opt/shifa/public/docs/
   sudo chmod 644 /opt/shifa/public/docs/*.html
   sudo chmod 755 /opt/shifa/public/docs/screenshots/
   sudo chmod 644 /opt/shifa/public/docs/screenshots/*.png
   ```

3. **Check nginx error log:**
   ```bash
   sudo tail -f /var/log/nginx/error.log
   # Then refresh the URL and see what error appears
   ```

4. **Check nginx access log:**
   ```bash
   sudo tail -f /var/log/nginx/access.log
   # Refresh URL and see the request
   ```

5. **Test alias path:**
   ```bash
   # Check if alias is correct
   sudo nginx -T | grep -A 5 "location /docs"
   ```

---

## Quick Command Summary

```bash
# 1. SSH to server
ssh -i your-key.pem ec2-user@13.207.62.222

# 2. Create directory
sudo mkdir -p /opt/shifa/public/docs/screenshots

# 3. Upload files (use WinSCP or git clone method above)

# 4. Copy to correct location
sudo cp /tmp/Shifa-*.html /opt/shifa/public/docs/
sudo cp -r /tmp/screenshots/* /opt/shifa/public/docs/screenshots/

# 5. Fix permissions
sudo chmod -R 755 /opt/shifa/public/docs/
sudo chown -R shifa:shifa /opt/shifa/public/docs/

# 6. Verify
ls -la /opt/shifa/public/docs/
curl -I http://localhost/docs/Shifa-Features-Guide.html

# 7. If working, test from browser
# http://13.207.62.222/docs/Shifa-Features-Guide.html
```

---

## Expected Result

After successful upload:
- ✅ http://13.207.62.222/docs/Shifa-Features-Guide.html → Loads without login
- ✅ http://13.207.62.222/docs/Shifa-Pricing-Interactive.html → Loads without login
- ✅ All 31 screenshots display correctly
- ✅ Works on mobile and desktop
- ✅ No authentication required

---

**Action Required:** Upload the 33 files to server  
**Time Required:** 10 minutes  
**Tools Needed:** WinSCP (or SCP/Git)
