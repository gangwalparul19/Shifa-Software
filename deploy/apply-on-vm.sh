#!/usr/bin/env bash
# =============================================================================
# Apply an uploaded shifa-deploy.zip on the VM: place the JAR + web bundles and
# restart the services. Does NOT touch the database or /etc/shifa/shifa.env.
# Usage (on the VM):        bash ~/shifa-deploy/config/apply-on-vm.sh
# Or from your PC in one shot:
#   ssh -i "<KEY>" ubuntu@<IP> "bash -s" < deploy/apply-on-vm.sh
# (expects ~/shifa-deploy.zip to already be uploaded)
# =============================================================================
set -euo pipefail

cd ~
echo ">> Unzipping shifa-deploy.zip..."
# NOTE: zips created by Windows PowerShell Compress-Archive use backslash path
# separators, which makes `unzip` emit a warning and exit with code 1 even though
# extraction succeeds. Under `set -e` that would abort the script, so we tolerate
# a warning-level (exit 1) result and only fail on a real error (exit >= 2).
unzip -o shifa-deploy.zip -d shifa-deploy >/dev/null || [ "$?" -le 1 ]

echo ">> Deploying backend JAR..."
sudo cp shifa-deploy/shifa-oms.jar /opt/shifa/shifa-oms.jar
sudo chown shifa:shifa /opt/shifa/shifa-oms.jar

echo ">> Deploying web bundles..."
sudo mkdir -p /var/www/shifa/store /var/www/shifa/admin
sudo rm -rf /var/www/shifa/store/* /var/www/shifa/admin/*
sudo cp -r shifa-deploy/store/* /var/www/shifa/store/
sudo cp -r shifa-deploy/admin/* /var/www/shifa/admin/
sudo chown -R www-data:www-data /var/www/shifa

echo ">> Restarting backend + reloading Nginx..."
sudo systemctl restart shifa-oms
sudo nginx -t && sudo systemctl reload nginx

echo ">> Redeploy complete. Backend status:"
sudo systemctl is-active shifa-oms || true
