#!/usr/bin/env bash
# =============================================================================
# Build the Shifa backend JAR + both Angular apps and deploy them locally on
# the VM. Run from the repo root on the server:  sudo bash deploy/build-and-deploy.sh
# Re-run this any time you pull new code to redeploy.
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
echo ">> Repo: $REPO"

# --- 1. Backend: build the fat JAR -------------------------------------------
echo ">> Building backend (mvn package -DskipTests)..."
cd "$REPO/backend"
mvn -q -DskipTests clean package
JAR="$(ls target/shifa-oms-*.jar | head -n1)"
echo ">> Built: $JAR"
sudo mkdir -p /opt/shifa
sudo cp "$JAR" /opt/shifa/shifa-oms.jar

# --- 2. Frontend: build both apps --------------------------------------------
echo ">> Building frontends (this needs Node + npm)..."
cd "$REPO/frontend"
npm ci
npx ng build storefront --configuration production
npx ng build admin --configuration production --base-href /admin/

# --- 3. Publish static bundles to Nginx web roots ----------------------------
echo ">> Publishing static bundles..."
sudo mkdir -p /var/www/shifa/store /var/www/shifa/admin
# Angular 17+ application builder outputs to dist/<project>/browser
sudo rm -rf /var/www/shifa/store/* /var/www/shifa/admin/*
sudo cp -r dist/storefront/browser/* /var/www/shifa/store/
sudo cp -r dist/admin/browser/*     /var/www/shifa/admin/
sudo chown -R www-data:www-data /var/www/shifa

# --- 4. Restart backend + reload Nginx ---------------------------------------
echo ">> Restarting services..."
sudo systemctl restart shifa-oms
sudo nginx -t && sudo systemctl reload nginx

echo ">> Done. Store: http://<host>/   Admin: http://<host>/admin/"
