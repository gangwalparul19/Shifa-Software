#!/usr/bin/env bash
# Apply an uploaded shifa-oms.jar + ~/admin-dist on the AWS EC2 box.
# Backs up MySQL first (restart auto-applies new Flyway migrations), then swaps
# in the JAR, publishes the admin bundle, restarts the backend, reloads Nginx.
# Does NOT touch /etc/shifa/shifa.env.
set -e

TS=$(date +%F-%H%M%S)
BACKUP_FILE=~/shifa-backup-"$TS".sql
BACKUP_BUCKET="shifa-db-backup"
BACKUP_REGION="ap-south-1"
echo ">> Backing up MySQL (shifa_dashboard) before migrations..."
# shifa.env is root-only (mode 600), so read it + run the dump under sudo.
# --no-tablespaces avoids the MySQL 8 PROCESS-privilege requirement.
sudo bash -c 'set -a; . /etc/shifa/shifa.env; set +a; MYSQL_PWD="$DB_PASSWORD" mysqldump --no-tablespaces -u"$DB_USERNAME" "$DB_NAME"' > "$BACKUP_FILE"
echo ">> Backup written: $BACKUP_FILE ($(du -h "$BACKUP_FILE" | cut -f1))"

# Upload the backup to S3 (authoritative store). The EC2 instance role
# (shifa-ec2-role) grants s3:PutObject on this bucket; region is ap-south-1.
# Best-effort: a failed upload warns but never aborts the deploy (the local
# copy is still on the box as a fallback). Requires the AWS CLI on the box.
echo ">> Uploading backup to s3://$BACKUP_BUCKET/ ..."
if command -v aws >/dev/null 2>&1; then
  if aws s3 cp "$BACKUP_FILE" "s3://$BACKUP_BUCKET/shifa-backup-$TS.sql" --region "$BACKUP_REGION" --only-show-errors; then
    echo ">> Backup uploaded to s3://$BACKUP_BUCKET/shifa-backup-$TS.sql"
    # Keep only the latest local backup per calendar day (today's) on the box;
    # every older local copy already lives in S3, so prune them to save disk.
    TODAY=$(date +%F)
    for f in ~/shifa-backup-*.sql; do
      [ -e "$f" ] || continue
      case "$(basename "$f")" in
        shifa-backup-"$TODAY"-*.sql) : ;;                 # keep today's
        *) { aws s3api head-object --bucket "$BACKUP_BUCKET" --key "$(basename "$f")" --region "$BACKUP_REGION" >/dev/null 2>&1 && rm -f "$f"; } || true ;;
      esac
    done
  else
    echo ">> WARNING: S3 upload failed — backup kept locally at $BACKUP_FILE only."
  fi
else
  echo ">> WARNING: aws CLI not found — backup kept locally at $BACKUP_FILE only (install awscli to enable S3 upload)."
fi

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
