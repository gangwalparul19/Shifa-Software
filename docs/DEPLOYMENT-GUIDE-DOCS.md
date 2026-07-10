# Documentation Deployment Guide

## Client-Facing URLs (After Deployment)

Once deployed, share these URLs with clients:

### 📋 Features Guide
```
http://13.207.62.222/docs/Shifa-Features-Guide.html
```
**Description:** Complete interactive features guide with 31 screenshots covering all modules, workflows, and capabilities.

---

### 💰 Pricing & Investment Proposal
```
http://13.207.62.222/docs/Shifa-Pricing-Interactive.html
```
**Description:** Interactive pricing breakdown with module costs, payment plans, AMC options, and ROI comparison.

---

## Deployment Steps

### Step 1: Connect to AWS Server
```bash
ssh -i your-key.pem ec2-user@13.207.62.222
# Or use your existing connection method
```

### Step 2: Create Documentation Directory
```bash
sudo mkdir -p /opt/shifa/public/docs
sudo mkdir -p /opt/shifa/public/docs/screenshots
sudo chown -R $USER:$USER /opt/shifa/public/docs
```

### Step 3: Upload Files
From your local machine (Windows), use one of these methods:

**Option A: Using SCP (if you have Git Bash or WSL)**
```bash
# Navigate to your project folder
cd "C:\E Drive\Shifa-Software\docs"

# Upload HTML files
scp -i your-key.pem Shifa-Features-Guide.html ec2-user@13.207.62.222:/opt/shifa/public/docs/
scp -i your-key.pem Shifa-Pricing-Interactive.html ec2-user@13.207.62.222:/opt/shifa/public/docs/

# Upload screenshots folder
scp -i your-key.pem -r screenshots/ ec2-user@13.207.62.222:/opt/shifa/public/docs/
```

**Option B: Using WinSCP (Windows GUI)**
1. Open WinSCP and connect to your server
2. Navigate to `/opt/shifa/public/docs/`
3. Upload:
   - `Shifa-Features-Guide.html`
   - `Shifa-Pricing-Interactive.html`
   - `screenshots/` folder (all 31 PNG files)

**Option C: Using Git on Server**
```bash
# On the server
cd /tmp
git clone https://github.com/gangwalparul19/Shifa-Software.git
cd Shifa-Software
git checkout dashboard-only

# Copy docs files
sudo cp -r docs/Shifa-Features-Guide.html /opt/shifa/public/docs/
sudo cp -r docs/Shifa-Pricing-Interactive.html /opt/shifa/public/docs/
sudo cp -r docs/screenshots/ /opt/shifa/public/docs/

# Set permissions
sudo chown -R shifa:shifa /opt/shifa/public/docs
sudo chmod -R 755 /opt/shifa/public/docs
```

### Step 4: Configure Nginx
```bash
# Edit nginx configuration
sudo nano /etc/nginx/sites-available/shifa
# Or wherever your nginx config is located
```

**Add this location block** (inside the `server` block):
```nginx
server {
    listen 80;
    server_name 13.207.62.222;

    # Existing root and other configurations...
    
    # Documentation files - ADD THIS BLOCK
    location /docs/ {
        alias /opt/shifa/public/docs/;
        autoindex off;
        
        # MIME types for HTML and images
        types {
            text/html html htm;
            image/png png;
            image/jpeg jpg jpeg;
        }
        
        # Cache control for screenshots
        location ~* \.(png|jpg|jpeg)$ {
            expires 7d;
            add_header Cache-Control "public, immutable";
        }
        
        # No cache for HTML files
        location ~* \.(html|htm)$ {
            expires -1;
            add_header Cache-Control "no-cache, no-store, must-revalidate";
        }
    }

    # Your existing API and frontend locations...
    location /api/ {
        proxy_pass http://localhost:8080;
        # ... rest of your proxy config
    }
    
    location / {
        root /opt/shifa/frontend;
        try_files $uri $uri/ /index.html;
    }
}
```

### Step 5: Test and Reload Nginx
```bash
# Test nginx configuration
sudo nginx -t

# If test passes, reload nginx
sudo systemctl reload nginx

# Or restart if needed
sudo systemctl restart nginx
```

### Step 6: Verify Deployment
```bash
# Check file permissions
ls -la /opt/shifa/public/docs/

# Test URLs from server
curl http://localhost/docs/Shifa-Features-Guide.html | head -20
curl http://localhost/docs/Shifa-Pricing-Interactive.html | head -20

# Check if screenshots are accessible
curl -I http://localhost/docs/screenshots/admin-dashboard.png
```

### Step 7: Test from Browser
Open these URLs in your browser:
1. `http://13.207.62.222/docs/Shifa-Features-Guide.html`
2. `http://13.207.62.222/docs/Shifa-Pricing-Interactive.html`

**Check:**
- ✅ Pages load correctly
- ✅ All screenshots display
- ✅ Navigation works
- ✅ Footer shows Weblithic branding
- ✅ Mobile responsive (test on phone)

---

## Files to Upload

```
/opt/shifa/public/docs/
├── Shifa-Features-Guide.html
├── Shifa-Pricing-Interactive.html
└── screenshots/
    ├── accountant-dashboard.png
    ├── admin-dashboard.png
    ├── approval-queue.png
    ├── audit-log.png
    ├── barcode-scanner.png
    ├── courier-records.png
    ├── expenses-page.png
    ├── insights-page.png
    ├── inventory-page.png
    ├── lead-detail.png
    ├── leads-list.png
    ├── login-page.png
    ├── my-profile-page.png
    ├── new-order-form-bottom.png
    ├── new-order-form-top.png
    ├── notification-dropdown.png
    ├── notifications-list.png
    ├── orders-desktop.png
    ├── orders-list.png
    ├── packer-dashboard.png
    ├── packing-queues.png
    ├── pnl-report.png
    ├── products-list.png
    ├── profile-approvals.png
    ├── purchase-orders.png
    ├── receivables-list.png
    ├── reports-page.png
    ├── salespeople-directory.png
    ├── salesperson-dashboard.png
    ├── salesperson-profile.png
    └── tablet-view.png
```

**Total size:** ~15-20 MB (31 screenshots + 2 HTML files)

---

## Client Sharing Templates

### For WhatsApp Message
```
Hi [Client Name],

Here are the complete Shifa OMS documentation links:

📋 *Features Guide with Screenshots*
http://13.207.62.222/docs/Shifa-Features-Guide.html

💰 *Pricing & Investment Details*
http://13.207.62.222/docs/Shifa-Pricing-Interactive.html

Both pages are mobile-friendly. Please review and let me know if you have any questions.

Best regards,
Weblithic Team
```

### For Email
```
Subject: Shifa OMS - Complete Documentation

Dear [Client Name],

Please find the comprehensive Shifa Order Management System documentation:

1. Features Guide: http://13.207.62.222/docs/Shifa-Features-Guide.html
   - Interactive showcase with 31 screenshots
   - Complete workflow demonstrations
   - All modules and capabilities covered

2. Pricing Details: http://13.207.62.222/docs/Shifa-Pricing-Interactive.html
   - One-time cost: ₹39,999
   - Flexible payment plans
   - AMC options
   - Module breakdown

Both documents are mobile-responsive and can be viewed on any device.

Please review at your convenience and let us know if you need any clarification.

Best regards,
Weblithic Team
http://www.weblithic.com
```

### For Formal Proposal
```
Documentation Links:

Technical Features Guide:
http://13.207.62.222/docs/Shifa-Features-Guide.html

Commercial Proposal:
http://13.207.62.222/docs/Shifa-Pricing-Interactive.html
```

---

## Troubleshooting

### Issue: 404 Not Found
```bash
# Check if files exist
ls -la /opt/shifa/public/docs/

# Check nginx error log
sudo tail -f /var/log/nginx/error.log

# Verify nginx config
sudo nginx -t
```

### Issue: Screenshots Not Loading
```bash
# Check permissions
ls -la /opt/shifa/public/docs/screenshots/

# Fix permissions if needed
sudo chmod -R 755 /opt/shifa/public/docs/
```

### Issue: Nginx Won't Reload
```bash
# Check configuration syntax
sudo nginx -t

# View recent nginx logs
sudo journalctl -u nginx -n 50
```

### Issue: Mobile Images Still Not Showing
- Clear browser cache on mobile
- Try in incognito/private mode
- Check browser console for errors (Chrome mobile DevTools)

---

## Security Considerations

### Public Access
These documentation pages are **publicly accessible** without authentication. This is intentional for easy client sharing.

**Protected:**
- ✅ Main application (`/`) - requires login
- ✅ API endpoints (`/api/`) - JWT protected

**Public:**
- 📄 Documentation pages (`/docs/`) - no authentication

### If You Need Protection
To add password protection later:
```nginx
location /docs/ {
    alias /opt/shifa/public/docs/;
    auth_basic "Shifa Documentation";
    auth_basic_user_file /etc/nginx/.htpasswd;
}
```

---

## Updating Documentation

When you make changes to HTML or screenshots:

```bash
# Method 1: Direct upload (WinSCP/SCP)
# Just overwrite the existing files

# Method 2: Git pull on server
cd /tmp/Shifa-Software
git pull origin dashboard-only
sudo cp docs/*.html /opt/shifa/public/docs/
sudo cp -r docs/screenshots/* /opt/shifa/public/docs/screenshots/

# No nginx reload needed (static files)
```

---

## Maintenance

### Backup Documentation
```bash
# Create backup
sudo tar -czf /tmp/shifa-docs-backup-$(date +%Y%m%d).tar.gz /opt/shifa/public/docs/

# Download backup
scp -i your-key.pem ec2-user@13.207.62.222:/tmp/shifa-docs-backup-*.tar.gz ./
```

### Monitor Access
```bash
# View documentation access logs
sudo grep "/docs/" /var/log/nginx/access.log | tail -20
```

---

## Quick Reference

| Item | Value |
|------|-------|
| **Server IP** | 13.207.62.222 |
| **Features URL** | http://13.207.62.222/docs/Shifa-Features-Guide.html |
| **Pricing URL** | http://13.207.62.222/docs/Shifa-Pricing-Interactive.html |
| **Server Path** | /opt/shifa/public/docs/ |
| **Total Files** | 33 (2 HTML + 31 PNG) |
| **Total Size** | ~15-20 MB |
| **Nginx Config** | /etc/nginx/sites-available/shifa |

---

## Status Checklist

Before sharing with clients, verify:
- [ ] Files uploaded to `/opt/shifa/public/docs/`
- [ ] Nginx configuration updated
- [ ] Nginx reloaded successfully
- [ ] Features Guide URL accessible
- [ ] Pricing Guide URL accessible
- [ ] All screenshots loading
- [ ] Mobile view working correctly
- [ ] Footer Weblithic link works
- [ ] Tested on desktop browser
- [ ] Tested on mobile browser

---

**Deployment Guide**  
**Version:** 1.0  
**Last Updated:** January 2025  
**Prepared by:** Weblithic Development Team
