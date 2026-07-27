#!/usr/bin/env bash
# Apply an uploaded shifa-oms.jar + ~/admin-dist on the AWS EC2 box.
# Backs up MySQL first (restart auto-applies new Flyway migrations), then swaps
# in the JAR, publishes the admin bundle, restarts the backend, reloads Nginx.
# Does NOT touch /etc/shifa/shifa.env.
set -e

TS=$(date +%F-%H%M%S)
echo ">> Backing up MySQL (shifa_dashboard) before migrations..."
# shifa.env is root-only (mode 600), so read it + run the dump under sudo.
# --no-tablespaces avoids the MySQL 8 PROCESS-privilege requirement.
sudo bash -c 'set -a; . /etc/shifa/shifa.env; set +a; MYSQL_PWD="$DB_PASSWORD" mysqldump --no-tablespaces -u"$DB_USERNAME" "$DB_NAME"' > ~/shifa-backup-"$TS".sql
echo ">> Backup written: ~/shifa-backup-$TS.sql ($(du -h ~/shifa-backup-"$TS".sql | cut -f1))"

echo ">> Deploying backend JAR..."
sudo cp ~/shifa-oms.jar /opt/shifa/shifa-oms.jar
sudo chown shifa:shifa /opt/shifa/shifa-oms.jar

echo ">> Publishing admin bundle..."
sudo mkdir -p /var/www/shifa/admin
sudo rm -rf /var/www/shifa/admin/*
sudo cp -r ~/admin-dist/* /var/www/shifa/admin/
sudo chown -R www-data:www-data /var/www/shifa

echo ">> Restarting backend + reloading Nginx..."
sudo systemctl restart shifa-oms
sudo nginx -t && sudo systemctl reload nginx
sleep 5
echo ">> shifa-oms is now: $(sudo systemctl is-active shifa-oms)"
