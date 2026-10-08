# Shifa OMS — EC2 Deployment, Step by Step (with your current data)

A precise, follow-along runbook to stand up a **new EC2 instance** running the whole app
(Angular Admin + Spring Boot API + **self-hosted MySQL 8**) and load it with your **current data**
(migrated from RDS). No RDS, no Lambda.

- **Time needed:** ~60–90 minutes (first time).
- **Region used throughout:** `ap-south-1` (Mumbai). Use the same region as your current RDS.
- **Instance:** `t2.micro` (Free Tier, $0). It handles your load (40 salespeople + 10 report users)
  with swap + tuning. If reports ever feel slow, Part 18 shows a 3-minute resize to `t3.small` (2 GB).
- **Convention:** every step ends with a **✔ Verify** line. Don't move on until it passes.
- Replace all `<PLACEHOLDERS>`. Commands prefixed `PC>` run on your Windows PC (PowerShell);
  everything else runs **on the EC2 instance** (the `ubuntu@...` SSH shell).

> **What you need before starting:** AWS console login · your current **RDS endpoint + username +
> password** · a place to build (Java 21 + Maven, Node 20 + npm on your PC) · ~1 hour.

---

## PART 1 — Build the app on your PC (10–15 min)

Do this first so the artifacts are ready to upload. **Never build on the t2.micro** (it will run out of
memory).

```powershell
PC> cd "c:\E Drive\Shifa-Software"

# 1. Backend JAR
PC> mvn -f "backend/pom.xml" -DskipTests package

# 2. Angular admin bundle
PC> npm --prefix frontend ci
PC> npm --prefix frontend run build:admin
```

**✔ Verify:** these two paths now exist:
- `backend\target\shifa-oms-0.0.1-SNAPSHOT.jar`
- `frontend\dist\admin\browser\index.html`

Zip the Angular bundle for upload:
```powershell
PC> Compress-Archive -Path "frontend\dist\admin\browser\*" -DestinationPath "admin.zip" -Force
```
**✔ Verify:** `admin.zip` exists in `c:\E Drive\Shifa-Software`.

---

## PART 2 — Launch the EC2 instance (10 min)

1. AWS Console → top-right region selector → choose **Asia Pacific (Mumbai) ap-south-1**.
2. Search **EC2** → open it → **Instances** → **Launch instances**.
3. **Name:** `shifa-oms`.
4. **Application and OS Images (AMI):** search **Ubuntu** → select **Ubuntu Server 22.04 LTS**
   (64-bit **x86**). Make sure it says **Free tier eligible**.
5. **Instance type:** select **t2.micro** (Free tier eligible).
6. **Key pair (login):**
   - Click **Create new key pair** → Name `shifa-key` → Type **RSA** → Format **.pem** → **Create**.
   - The browser downloads `shifa-key.pem`. **Move it to a safe folder**, e.g.
     `C:\E Drive\aws-keys\shifa-key.pem`. You cannot re-download it.
7. **Network settings** → click **Edit**:
   - **VPC:** leave default. **Subnet:** No preference. **Auto-assign public IP:** **Enable**.
   - **Firewall (security groups):** **Create security group**, name `shifa-sg`. Add these inbound rules:
     | Type | Protocol | Port | Source |
     |---|---|---|---|
     | SSH | TCP | 22 | **My IP** |
     | HTTP | TCP | 80 | Anywhere `0.0.0.0/0` |
     | HTTPS | TCP | 443 | Anywhere `0.0.0.0/0` |
8. **Configure storage:** change the root volume to **30 GiB**, type **gp3** (still Free-tier friendly;
   free tier allows up to 30 GB).
9. Leave everything else default → **Launch instance**.

**✔ Verify:** Instances list shows `shifa-oms` → **Instance state = Running** and **Status checks =
2/2 checks passed** (wait ~2 minutes).

---

## PART 3 — Give it a fixed public IP (Elastic IP) (3 min)

A stop/start would otherwise change the IP. Pin it:

1. EC2 left menu → **Network & Security → Elastic IPs** → **Allocate Elastic IP address** → **Allocate**.
2. Select the new IP → **Actions → Associate Elastic IP address** → Resource type **Instance** →
   choose `shifa-oms` → **Associate**.
3. Note this IP — call it `<EIP>` for the rest of this guide.

**✔ Verify:** the instance's **Public IPv4 address** now equals `<EIP>`.

> ⚠️ Cost note: an Elastic IP is free **while associated with a running instance**. If you ever stop the
> instance for long or leave the EIP unattached, AWS bills ~$3.6/mo. Keep exactly one, attached.

---

## PART 4 — Connect via SSH from Windows (5 min)

```powershell
# Lock down the key file permissions once (Windows), otherwise SSH refuses it:
PC> icacls "C:\E Drive\aws-keys\shifa-key.pem" /inheritance:r
PC> icacls "C:\E Drive\aws-keys\shifa-key.pem" /grant:r "%USERNAME%:R"

# Connect (default user for Ubuntu AMIs is 'ubuntu'):
PC> ssh -i "C:\E Drive\aws-keys\shifa-key.pem" ubuntu@<EIP>
```
Type **yes** at the fingerprint prompt the first time.

**✔ Verify:** your prompt changes to `ubuntu@ip-...:~$`. You are now **on the server**.

---

## PART 5 — Prepare the server: updates, swap, timezone (10 min)

Run these **on the instance**.

```bash
# 1. Update the OS
sudo apt update && sudo apt -y upgrade

# 2. Add 2 GB swap (critical on 1 GB RAM — prevents out-of-memory kills)
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
sudo sysctl vm.swappiness=10 && echo 'vm.swappiness=10' | sudo tee -a /etc/sysctl.conf

# 3. Set timezone to IST (so logs/backups line up with your day)
sudo timedatectl set-timezone Asia/Kolkata
```

**✔ Verify:**
```bash
free -h        # should show Swap: 2.0Gi
date           # should show IST
```

---

## PART 6 — Install Java 21, MySQL 8, Nginx (10 min)

```bash
# Java 21 runtime (headless), MySQL 8 server + client, Nginx, unzip.
# NOTE: no Maven/Node here — we build on the PC.
sudo apt -y install openjdk-21-jre-headless mysql-server mysql-client nginx unzip
```

**✔ Verify:**
```bash
java -version     # openjdk version "21..."
mysql --version   # Ver 8.0...
nginx -v          # nginx version ...
```

---

## PART 7 — Create the database + app user + tune MySQL (10 min)

```bash
# 1. Harden MySQL (set a root password; answer Y to the prompts)
sudo mysql_secure_installation
```
```bash
# 2. Create the database and the app user (pick a strong password)
sudo mysql <<'SQL'
CREATE DATABASE IF NOT EXISTS shifa_dashboard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'shifa'@'localhost' IDENTIFIED BY 'CHANGE_ME_DB_PASSWORD';
GRANT ALL PRIVILEGES ON shifa_dashboard.* TO 'shifa'@'localhost';
FLUSH PRIVILEGES;
SQL
```
```bash
# 3. Small-footprint MySQL config for a 1 GB box
sudo tee /etc/mysql/mysql.conf.d/shifa.cnf >/dev/null <<'CNF'
[mysqld]
innodb_buffer_pool_size = 128M
performance_schema = OFF
max_connections = 50
CNF
sudo systemctl restart mysql
```

**✔ Verify:**
```bash
mysql -u shifa -p -e "SHOW DATABASES;"    # enter the shifa password; you should see shifa_dashboard
```

---

## PART 8 — Migrate your CURRENT data from RDS (15 min)

This copies everything (schema + data + Flyway history) from RDS into the local MySQL. Because the dump
includes the `flyway_schema_history` table, the app will **not** re-run migrations — it just uses the
data as-is.

### 8.1 Let the new EC2 reach RDS (temporary)
In the AWS console → **RDS → Databases → your DB → Connectivity & security**. Note the **endpoint**.
Its **VPC security group** must allow the new EC2 in on port 3306:
1. Open that RDS security group → **Inbound rules → Edit** → **Add rule**: Type **MySQL/Aurora (3306)**,
   Source = **the `shifa-sg` security group** (start typing `shifa-sg` and pick it).
2. Save. (We remove this again in Part 15 after cutover.)

### 8.2 Dump from RDS and import locally (run on the EC2)
```bash
# Dump the RDS database to a file on the EC2:
mysqldump -h <RDS_ENDPOINT> -u <RDS_USERNAME> -p \
  --single-transaction --routines --triggers --set-gtid-purged=OFF \
  shifa_dashboard > ~/rds_dump.sql

# Import it into the local MySQL:
mysql -u shifa -p shifa_dashboard < ~/rds_dump.sql
```

**✔ Verify — compare row counts (should match RDS):**
```bash
mysql -u shifa -p shifa_dashboard -e \
  "SELECT (SELECT COUNT(*) FROM orders) AS orders, \
          (SELECT COUNT(*) FROM users) AS users, \
          (SELECT COUNT(*) FROM flyway_schema_history) AS migrations;"
```
You should see your real order/user counts and the migration count (38+). If these look right, the data
is in.

> If the EC2 can't reach RDS, alternative: run the `mysqldump` from a machine that can (or your PC),
> then `PC> scp -i "...\shifa-key.pem" rds_dump.sql ubuntu@<EIP>:/home/ubuntu/` and import as above.

---

## PART 9 — Upload the app artifacts (10 min)

From your **PC** (new PowerShell window; keep the SSH one open):
```powershell
PC> scp -i "C:\E Drive\aws-keys\shifa-key.pem" "c:\E Drive\Shifa-Software\backend\target\shifa-oms-0.0.1-SNAPSHOT.jar" ubuntu@<EIP>:/home/ubuntu/shifa-oms.jar
PC> scp -i "C:\E Drive\aws-keys\shifa-key.pem" "c:\E Drive\Shifa-Software\admin.zip" ubuntu@<EIP>:/home/ubuntu/
```

Back **on the EC2**, place the files:
```bash
sudo mkdir -p /opt/shifa /opt/shifa/storage /var/www/shifa/admin
sudo mv ~/shifa-oms.jar /opt/shifa/shifa-oms.jar
sudo unzip -o ~/admin.zip -d /var/www/shifa/admin
sudo useradd -r -s /usr/sbin/nologin shifa || true
sudo chown -R shifa:shifa /opt/shifa
```

**✔ Verify:**
```bash
ls -lh /opt/shifa/shifa-oms.jar          # ~60-90 MB file exists
ls /var/www/shifa/admin/index.html       # exists
```

---

## PART 10 — Backend configuration file (5 min)

```bash
sudo mkdir -p /etc/shifa
sudo tee /etc/shifa/shifa.env >/dev/null <<'ENV'
SPRING_PROFILES_ACTIVE=prod
DB_HOST=localhost
DB_PORT=3306
DB_NAME=shifa_dashboard
DB_USERNAME=shifa
DB_PASSWORD=CHANGE_ME_DB_PASSWORD
DB_POOL_SIZE=8

# Long random string. Generate with: openssl rand -base64 48
JWT_SECRET=CHANGE_ME_LONG_RANDOM_SECRET

# Only used if these accounts don't already exist in your migrated data
SEED_ADMIN_PASSWORD=CHANGE_ME_ADMIN_PW
SEED_PACKING_PASSWORD=CHANGE_ME_PACKER_PW

# Files on local disk (simplest, $0). /opt/shifa/storage must be writable by 'shifa'.
STORAGE_PROVIDER=LOCAL

COURIER_MODE=MOCK
WHATSAPP_MODE=MOCK
MAIL_MODE=MOCK

# JVM heap for a 1 GB box
JAVA_OPTS=-Xms256m -Xmx512m
ENV
sudo chmod 600 /etc/shifa/shifa.env
```
Now edit the two real secrets:
```bash
# generate a JWT secret and copy the output
openssl rand -base64 48
sudo nano /etc/shifa/shifa.env    # paste JWT_SECRET, set DB_PASSWORD to match Part 7
```
**✔ Verify:** `sudo cat /etc/shifa/shifa.env` shows your real `DB_PASSWORD` and a long `JWT_SECRET`.

> Note: `STORAGE_PROVIDER=LOCAL` keeps files on the instance disk. Your migrated screenshots that lived
> in S3 (if you used S3 before) stay in S3 — switch to S3 later via `STORAGE_PROVIDER=S3` + an EC2
> instance role if you prefer. LOCAL is the simplest way to go live now.

---

## PART 11 — Run the backend as a service (10 min)

```bash
sudo tee /etc/systemd/system/shifa-oms.service >/dev/null <<'UNIT'
[Unit]
Description=Shifa OMS (Spring Boot)
After=network.target mysql.service
Wants=mysql.service

[Service]
User=shifa
EnvironmentFile=/etc/shifa/shifa.env
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/shifa/shifa-oms.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable --now shifa-oms
sudo journalctl -u shifa-oms -f
```
Watch the logs. Because your data already has `flyway_schema_history`, Flyway should report the schema
is up to date and start the web server.

**✔ Verify:** the log shows `Tomcat started on port 8080` and `Started ...Application`. Press
**Ctrl-C** to stop tailing (the service keeps running). Then:
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/api/auth/login -X POST
# any HTTP code (e.g. 400/401/405) proves the API is up and reachable locally
```

---

## PART 12 — Configure Nginx (5 min)

```bash
sudo tee /etc/nginx/sites-available/shifa >/dev/null <<'NGINX'
server {
    listen 80;
    listen [::]:80;
    server_name _;              # replaced with sslip.io host in Part 13
    client_max_body_size 15m;
    gzip on;
    gzip_types text/plain text/css application/javascript application/json image/svg+xml;
    gzip_min_length 1024;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }
    location = /api/admin/events {          # SSE live dashboard — buffering off
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Connection '';
        proxy_buffering off; proxy_cache off; proxy_read_timeout 3600s;
    }
    location / {
        root /var/www/shifa/admin;
        try_files $uri $uri/ /index.html;
    }
}
NGINX

sudo ln -sf /etc/nginx/sites-available/shifa /etc/nginx/sites-enabled/shifa
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

**✔ Verify:** open `http://<EIP>/` in your browser → the Shifa Admin login page loads. Log in with a
real user from your migrated data (e.g. `admin`). You should see live data.

---

## PART 13 — Free HTTPS with a hostname (10 min)

No domain purchase needed — `sslip.io` turns your IP into a hostname automatically.

```bash
sudo apt -y install certbot python3-certbot-nginx

# Example: EIP 13.234.56.78  ->  13-234-56-78.sslip.io
sudo sed -i 's/server_name _;/server_name <EIP-with-dashes>.sslip.io;/' /etc/nginx/sites-available/shifa
sudo systemctl reload nginx

sudo certbot --nginx -d <EIP-with-dashes>.sslip.io --redirect --agree-tos -m you@example.com --no-eff-email
```

**✔ Verify:** open `https://<EIP-with-dashes>.sslip.io/` → padlock shows, login works. Certbot set up
auto-renewal (`systemctl status certbot.timer`).

> Have a real domain? Point an **A record** to `<EIP>` and run certbot with `-d yourdomain.com` instead.

---

## PART 14 — Smoke test the full flow (10 min)

Log in as admin and confirm, end to end:
- [ ] Dashboard loads with your real numbers.
- [ ] Orders list shows your migrated orders.
- [ ] Create a **test order** as a salesperson → it appears and can be approved.
- [ ] Approve it → a label is generated (Print Label works).
- [ ] Open a report / analytics page → renders.
- [ ] Log out / log back in.

**✔ Verify:** all boxes pass. If the app behaves like your old environment, cutover is successful.

---

## PART 15 — Lock things back down (5 min)

1. **Remove the temporary RDS access** you added in 8.1: RDS security group → inbound rules → delete the
   `3306 from shifa-sg` rule. (The app no longer needs RDS.)
2. Delete the dump file: `rm ~/rds_dump.sql`.

**✔ Verify:** app still works (it uses local MySQL now, not RDS).

---

## PART 16 — Nightly backups (5 min)

```bash
sudo tee /opt/shifa/backup.sh >/dev/null <<'BK'
#!/usr/bin/env bash
set -e
STAMP=$(date +%F)
mkdir -p /opt/shifa/backups
mysqldump -u shifa -p"$DB_PASSWORD" --single-transaction shifa_dashboard | gzip > /opt/shifa/backups/db-$STAMP.sql.gz
find /opt/shifa/backups -name 'db-*.sql.gz' -mtime +14 -delete
BK
sudo chmod +x /opt/shifa/backup.sh

# schedule nightly at 02:00 IST (pass the DB password from the env file)
( sudo crontab -l 2>/dev/null; echo "0 2 * * * DB_PASSWORD=CHANGE_ME_DB_PASSWORD /opt/shifa/backup.sh" ) | sudo crontab -
```
Run it once now to confirm it works:
```bash
sudo DB_PASSWORD=CHANGE_ME_DB_PASSWORD /opt/shifa/backup.sh
ls -lh /opt/shifa/backups/
```
**✔ Verify:** a `db-<date>.sql.gz` file exists. (Optional: also copy backups off-box to S3 for safety.)

---

## PART 17 — Retire RDS to stop the bill (after 2–3 confident days)

Once you're happy the EC2 is serving everything correctly for a couple of days:
1. RDS → your DB → **Actions → Take snapshot** (name it `shifa-final-pre-delete`) — a safety copy.
2. RDS → your DB → **Actions → Delete** → uncheck "create final snapshot" only if you took one above →
   confirm. This stops the **~$7.35/mo** charge.
3. EC2 → Elastic IPs → release any **unattached** IPs (keep the one on `shifa-oms`).

**✔ Verify:** RDS console shows no running instance; AWS **Billing → Cost Explorer** trends down next day.

---

## PART 18 — Operations cheat-sheet

**Service control / logs**
```bash
sudo systemctl status shifa-oms
sudo journalctl -u shifa-oms -f
sudo systemctl restart shifa-oms
sudo tail -f /var/log/nginx/error.log
```

**Redeploy after code changes** — build on the PC (Part 1), then:
```powershell
PC> scp -i "C:\E Drive\aws-keys\shifa-key.pem" "c:\E Drive\Shifa-Software\backend\target\shifa-oms-0.0.1-SNAPSHOT.jar" ubuntu@<EIP>:/home/ubuntu/shifa-oms.jar
PC> Compress-Archive -Path "frontend\dist\admin\browser\*" -DestinationPath "admin.zip" -Force
PC> scp -i "C:\E Drive\aws-keys\shifa-key.pem" "c:\E Drive\Shifa-Software\admin.zip" ubuntu@<EIP>:/home/ubuntu/
```
```bash
# on the EC2:
sudo mv ~/shifa-oms.jar /opt/shifa/shifa-oms.jar
sudo rm -rf /var/www/shifa/admin/* && sudo unzip -o ~/admin.zip -d /var/www/shifa/admin
sudo chown -R shifa:shifa /opt/shifa
sudo systemctl restart shifa-oms && sudo systemctl reload nginx
```
Schema changes: add a new Flyway file `V39__...sql` on the PC before building — it applies on restart.
Data is preserved; the env file and DB are never touched by a redeploy.

**If reports feel slow → resize to t3.small (2 GB), 3 minutes, ~$15/mo:**
1. EC2 → select `shifa-oms` → **Instance state → Stop instance** (wait for Stopped).
2. **Actions → Instance settings → Change instance type** → **t3.small** → Apply.
3. **Instance state → Start instance.** (Elastic IP stays attached — same URL.)
4. Bump limits: in `/etc/shifa/shifa.env` set `JAVA_OPTS=-Xms512m -Xmx1g` and `DB_POOL_SIZE=12`; in
   `/etc/mysql/mysql.conf.d/shifa.cnf` set `innodb_buffer_pool_size = 512M`; then
   `sudo systemctl restart mysql && sudo systemctl restart shifa-oms`.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Browser can't reach `http://<EIP>/` | Security group must allow 80/443 (Part 2.7); instance Running; `sudo systemctl status nginx`. |
| **502 Bad Gateway** | Backend down: `sudo journalctl -u shifa-oms -e`. Usually a DB password mismatch in `/etc/shifa/shifa.env`. |
| Backend won't start, DB error | Check `DB_PASSWORD` matches Part 7; `mysql -u shifa -p` should connect. |
| App starts but shows no/blank data | The RDS import (Part 8) didn't run or row counts were 0 — re-import and re-check counts. |
| Flyway "validate" error on boot | Your migrated schema differs from the JAR's migrations — you likely imported an older DB; deploy the matching JAR, or restore the correct dump. |
| Out of memory / killed | Confirm 2 GB swap (`free -h`); keep `-Xmx512m`; if persistent, resize to t3.small (Part 18). |
| SSH refused | Fix key perms (Part 4 `icacls`); ensure SSH-22 source = your IP; your ISP IP may have changed → update the SG rule. |
| Login works but SSE dashboard not live | Ensure the `location = /api/admin/events` block is present and `nginx -t` passes. |

---

### Quick reference (fill in your values)
| Thing | Value |
|---|---|
| Region | `ap-south-1` |
| Instance | `shifa-oms` (t2.micro) |
| Elastic IP | `<EIP>` |
| SSH key | `C:\E Drive\aws-keys\shifa-key.pem` |
| SSH command | `ssh -i "C:\E Drive\aws-keys\shifa-key.pem" ubuntu@<EIP>` |
| App URL | `https://<EIP-with-dashes>.sslip.io/` |
| DB | local MySQL `shifa_dashboard`, user `shifa` |
| Backups | `/opt/shifa/backups/` nightly 02:00 IST |
