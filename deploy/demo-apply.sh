#!/usr/bin/env bash
# Apply an uploaded shifa-oms.jar + ~/admin-dist on the DEMO EC2 box
# (account 060451241643). Backs up the demo MySQL (shifa_demo) first, uploads the
# dump to the DEMO S3 bucket (shifa-oms-demo-files, NOT the prod backup bucket),
# then swaps in the JAR, publishes the admin bundle, restarts the backend, reloads
# Nginx. Does NOT touch /etc/shifa/shifa.env and NEVER touches the prod account.
set -e

TS=$(date +%F-%H%M%S)
BACKUP_FILE=~/shifa-demo-backup-"$TS".sql
BACKUP_BUCKET="shifa-oms-demo-files"
BACKUP_REGION="ap-south-1"
BACKUP_PREFIX="demo/db-backups"

echo ">> [demo] Backing up MySQL (shifa_demo) before migrations..."
# shifa.env is root-only (mode 600), so read it + run the dump under sudo.
# --no-tablespaces avoids the MySQL 8 PROCESS-privilege requirement.
sudo bash -c 'set -a; . /etc/shifa/shifa.env; set +a; MYSQL_PWD="$DB_PASSWORD" mysqldump --no-tablespaces -u"$DB_USERNAME" "$DB_NAME"' > "$BACKUP_FILE"
echo ">> [demo] Backup written: $BACKUP_FILE ($(du -h "$BACKUP_FILE" | cut -f1))"

echo ">> [demo] Uploading backup to s3://$BACKUP_BUCKET/$BACKUP_PREFIX/ ..."
if command -v aws >/dev/null 2>&1; then
  if aws s3 cp "$BACKUP_FILE" "s3://$BACKUP_BUCKET/$BACKUP_PREFIX/shifa-demo-backup-$TS.sql" --region "$BACKUP_REGION" --only-show-errors; then
    echo ">> [demo] Backup uploaded to s3://$BACKUP_BUCKET/$BACKUP_PREFIX/shifa-demo-backup-$TS.sql"
    # Keep only today's local backups on the box (the role has no DeleteObject,
    # so S3 copies are retained; just prune local disk of older-than-today dumps).
    TODAY=$(date +%F)
    for f in ~/shifa-demo-backup-*.sql; do
      [ -e "$f" ] || continue
      case "$(basename "$f")" in
        shifa-demo-backup-"$TODAY"-*.sql) : ;;   # keep today's
        *) rm -f "$f" || true ;;
      esac
    done
  else
    echo ">> [demo] WARNING: S3 upload failed — backup kept locally at $BACKUP_FILE only."
  fi
else
  echo ">> [demo] WARNING: aws CLI not found — backup kept locally at $BACKUP_FILE only."
fi

echo ">> [demo] Deploying backend JAR..."
sudo cp ~/shifa-oms.jar /opt/shifa/shifa-oms.jar
sudo chown shifa:shifa /opt/shifa/shifa-oms.jar

echo ">> [demo] Publishing admin bundle..."
sudo mkdir -p /var/www/shifa/admin
sudo rm -rf /var/www/shifa/admin/*
sudo cp -r ~/admin-dist/* /var/www/shifa/admin/
sudo chown -R www-data:www-data /var/www/shifa

echo ">> [demo] Restarting backend + reloading Nginx..."
sudo systemctl restart shifa-oms
sudo nginx -t && sudo systemctl reload nginx
sleep 6
echo ">> [demo] shifa-oms is now: $(sudo systemctl is-active shifa-oms)"
