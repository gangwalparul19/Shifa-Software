# Fix: Documentation Pages Requiring Authentication

## Problem
Documentation URLs are requiring login when they should be publicly accessible:
- ❌ http://13.207.62.222/docs/Shifa-Features-Guide.html (requires auth)
- ❌ http://13.207.62.222/docs/Shifa-Pricing-Interactive.html (requires auth)

## Root Cause
Your nginx configuration likely has authentication (basic auth or proxy to backend) applied globally, which catches the `/docs/` path.

---

## Solution: Update Nginx Configuration

### Step 1: SSH to Server
```bash
ssh -i your-key.pem ec2-user@13.207.62.222
```

### Step 2: Edit Nginx Configuration
```bash
# Find your nginx config file (usually one of these):
sudo nano /etc/nginx/sites-available/shifa
# OR
sudo nano /etc/nginx/conf.d/shifa.conf
# OR
sudo nano /etc/nginx/nginx.conf
```

### Step 3: Add Public Docs Location Block

**IMPORTANT:** The `/docs/` location block must come **BEFORE** any authentication directives or catch-all locations.

Add this configuration:

```nginx
server {
    listen 80;
    server_name 13.207.62.222;

    # ============================================
    # PUBLIC DOCUMENTATION - NO AUTH REQUIRED
    # This MUST come BEFORE any auth directives
    # ============================================
    location /docs/ {
        alias /opt/shifa/public/docs/;
        autoindex off;
        
        # MIME types
        types {
            text/html html htm;
            image/png png;
            image/jpeg jpg jpeg;
        }
        
        # Cache images for 7 days
        location ~* \.(png|jpg|jpeg)$ {
            expires 7d;
            add_header Cache-Control "public, immutable";
        }
        
        # No cache for HTML
        location ~* \.(html|htm)$ {
            expires -1;
            add_header Cache-Control "no-cache, no-store, must-revalidate";
        }
        
        # Security headers
        add_header X-Content-Type-Options nosniff;
        add_header X-Frame-Options SAMEORIGIN;
    }

    # ============================================
    # REST OF YOUR CONFIGURATION BELOW
    # ============================================
    
    # Your existing API location
    location /api/ {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # Your existing frontend location
    location / {
        root /opt/shifa/frontend;
        try_files $uri $uri/ /index.html;
    }
}
```

### Step 4: Test Configuration
```bash
# Test nginx syntax
sudo nginx -t
```

**Expected output:**
```
nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
nginx: configuration file /etc/nginx/nginx.conf test is successful
```

### Step 5: Reload Nginx
```bash
sudo systemctl reload nginx
# OR
sudo systemctl restart nginx
```

### Step 6: Verify Access
Test the URLs without authentication:
```bash
# From the server
curl -I http://localhost/docs/Shifa-Features-Guide.html

# Expected: HTTP/1.1 200 OK (not 401 or 403)
```

Test from browser (no login required):
- http://13.207.62.222/docs/Shifa-Features-Guide.html
- http://13.207.62.222/docs/Shifa-Pricing-Interactive.html

---

## Common Issues & Solutions

### Issue 1: Still Requires Authentication

**Check 1:** Location block order matters
```nginx
# WRONG - auth will catch /docs/
location / {
    auth_basic "Restricted";
    auth_basic_user_file /etc/nginx/.htpasswd;
    root /opt/shifa/frontend;
}

location /docs/ {  # This comes too late!
    alias /opt/shifa/public/docs/;
}

# CORRECT - /docs/ comes BEFORE /
location /docs/ {  # Specific path first
    alias /opt/shifa/public/docs/;
    # No auth here
}

location / {  # General path last
    auth_basic "Restricted";
    auth_basic_user_file /etc/nginx/.htpasswd;
    root /opt/shifa/frontend;
}
```

**Check 2:** Remove auth from /docs/ if it's inherited
```nginx
location /docs/ {
    alias /opt/shifa/public/docs/;
    
    # Explicitly disable auth
    auth_basic off;
    
    # ... rest of config
}
```

### Issue 2: 404 Not Found

**Check files exist:**
```bash
ls -la /opt/shifa/public/docs/
# Should show:
# - Shifa-Features-Guide.html
# - Shifa-Pricing-Interactive.html
# - screenshots/ directory
```

**Check permissions:**
```bash
sudo chmod -R 755 /opt/shifa/public/docs/
sudo chown -R nginx:nginx /opt/shifa/public/docs/
# OR
sudo chown -R www-data:www-data /opt/shifa/public/docs/
```

### Issue 3: Images Not Loading (403 Forbidden)

**Check screenshot permissions:**
```bash
sudo chmod -R 755 /opt/shifa/public/docs/screenshots/
ls -la /opt/shifa/public/docs/screenshots/
# All should be readable (r--)
```

### Issue 4: HTML Downloads Instead of Displaying

**Check MIME types:**
```nginx
location /docs/ {
    alias /opt/shifa/public/docs/;
    
    # Add MIME types
    types {
        text/html html htm;
        image/png png;
        image/jpeg jpg jpeg;
    }
    default_type text/html;
}
```

---

## Verification Checklist

After making changes, verify:

- [ ] Nginx config syntax is valid (`sudo nginx -t`)
- [ ] Nginx reloaded successfully
- [ ] Features Guide loads without login
- [ ] Pricing Guide loads without login
- [ ] All screenshots display correctly
- [ ] Can access from mobile browser
- [ ] Can access from desktop browser
- [ ] No authentication popup appears
- [ ] No redirect to login page

---

## Example: Complete Working Configuration

```nginx
server {
    listen 80;
    server_name 13.207.62.222;

    # Public documentation - FIRST (no auth)
    location /docs/ {
        alias /opt/shifa/public/docs/;
        autoindex off;
        auth_basic off;  # Explicitly disable auth
        
        types {
            text/html html htm;
            image/png png;
        }
        
        location ~* \.(png|jpg|jpeg)$ {
            expires 7d;
        }
    }

    # API endpoints - proxied to backend (auth handled by Spring)
    location /api/ {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # Frontend application - requires login (handled by Angular)
    location / {
        root /opt/shifa/frontend;
        try_files $uri $uri/ /index.html;
    }
}
```

---

## Quick Test Commands

```bash
# Test from server (should return 200, not 401/403)
curl -I http://localhost/docs/Shifa-Features-Guide.html

# Test features guide
curl http://localhost/docs/Shifa-Features-Guide.html | head -20

# Test pricing guide  
curl http://localhost/docs/Shifa-Pricing-Interactive.html | head -20

# Test screenshot
curl -I http://localhost/docs/screenshots/admin-dashboard.png

# Check nginx logs
sudo tail -f /var/log/nginx/access.log
sudo tail -f /var/log/nginx/error.log
```

---

## If You're Using Spring Security

If your Spring Boot application is handling authentication for static resources, you need to exclude `/docs/` in your Security Configuration.

**File:** `backend/src/main/java/com/shifa/oms/platform/security/SecurityConfig.java`

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            // Public endpoints - no authentication
            .requestMatchers("/docs/**").permitAll()
            .requestMatchers("/api/public/**").permitAll()
            .requestMatchers("/actuator/health").permitAll()
            
            // Everything else requires authentication
            .anyRequest().authenticated()
        )
        // ... rest of config
    ;
    return http.build();
}
```

However, since `/docs/` is served by nginx (not proxied to backend), this shouldn't be necessary.

---

## Summary

The key is ensuring the `/docs/` location block:
1. ✅ Comes **BEFORE** any authentication directives
2. ✅ Has `auth_basic off;` if needed
3. ✅ Points to correct alias `/opt/shifa/public/docs/`
4. ✅ Has proper MIME types configured
5. ✅ Files have correct permissions (755/644)

After fixing, the documentation pages will be publicly accessible without requiring login.

---

## Need Help?

If issues persist:
1. Check nginx error logs: `sudo tail -f /var/log/nginx/error.log`
2. Test with curl first before browser
3. Clear browser cache
4. Try in incognito mode
5. Check firewall rules (port 80 should be open)

---

**Status:** Configuration Ready  
**Action Required:** Update nginx config on server  
**Expected Result:** Public access to documentation URLs  
**Time Required:** 5 minutes
