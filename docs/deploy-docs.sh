#!/bin/bash
# Shifa Documentation Deployment Script
# Run this on your AWS server after uploading files

set -e

echo "🚀 Shifa Documentation Deployment"
echo "=================================="

# Create directory structure
echo "📁 Creating directory structure..."
sudo mkdir -p /opt/shifa/public/docs/screenshots
sudo chown -R $USER:$USER /opt/shifa/public/docs

# Check if we're in the repo
if [ ! -f "Shifa-Features-Guide.html" ]; then
    echo "❌ Error: HTML files not found in current directory"
    echo "   Please run this script from the docs/ folder or upload files first"
    exit 1
fi

# Copy files
echo "📋 Copying documentation files..."
sudo cp Shifa-Features-Guide.html /opt/shifa/public/docs/
sudo cp Shifa-Pricing-Interactive.html /opt/shifa/public/docs/
sudo cp -r screenshots/* /opt/shifa/public/docs/screenshots/

# Set permissions
echo "🔒 Setting permissions..."
sudo chown -R shifa:shifa /opt/shifa/public/docs || sudo chown -R $USER:$USER /opt/shifa/public/docs
sudo chmod -R 755 /opt/shifa/public/docs

# Count files
HTML_COUNT=$(ls /opt/shifa/public/docs/*.html 2>/dev/null | wc -l)
IMG_COUNT=$(ls /opt/shifa/public/docs/screenshots/*.png 2>/dev/null | wc -l)

echo "✅ Files copied:"
echo "   - HTML files: $HTML_COUNT"
echo "   - Screenshots: $IMG_COUNT"

# Check nginx config
echo ""
echo "⚙️  Next steps:"
echo "1. Add this to your nginx config (/etc/nginx/sites-available/shifa):"
echo ""
cat << 'EOF'
    location /docs/ {
        alias /opt/shifa/public/docs/;
        autoindex off;
        
        types {
            text/html html htm;
            image/png png;
            image/jpeg jpg jpeg;
        }
        
        location ~* \.(png|jpg|jpeg)$ {
            expires 7d;
            add_header Cache-Control "public, immutable";
        }
        
        location ~* \.(html|htm)$ {
            expires -1;
            add_header Cache-Control "no-cache, no-store, must-revalidate";
        }
    }
EOF
echo ""
echo "2. Test nginx config:"
echo "   sudo nginx -t"
echo ""
echo "3. Reload nginx:"
echo "   sudo systemctl reload nginx"
echo ""
echo "4. Test URLs:"
echo "   http://13.207.62.222/docs/Shifa-Features-Guide.html"
echo "   http://13.207.62.222/docs/Shifa-Pricing-Interactive.html"
echo ""
echo "✨ Documentation deployment complete!"
