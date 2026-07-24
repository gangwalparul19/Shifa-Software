# Shifa OMS — AWS Single-Instance Deployment (Java + MySQL + Angular on one box)

Goal: run the **dashboard-only** Shifa OMS (Angular Admin app + Spring Boot API + **self-hosted MySQL 8**)
on **one small AWS instance** — no RDS, no Lambda, no DynamoDB — for the **lowest sustainable cost**
(**$0 during the 12-month Free Tier**, then **~$7–8/month**).

> **Why this shape:** the real workload is light — ~40 salespeople doing ~100 light GETs + up to 10
> orders each per day, and only ~10 users running dashboards/reports. That's ~0.15 req/s average, a few
> req/s at peak. It does not need a managed database or serverless; one small instance co-hosting
> everything is the cheapest correct answer. Self-hosting MySQL removes the ~$7.35/mo **RDS** charge that
> currently dominates the bill.

---

## 1. Workload & sizing

| Metric | Value |
|---|---|
| Salespeople | ~40, mostly light **GET** calls (~100/user/day) + ≤10 orders/user/day |
| Order writes | ~400/day |
| Dashboard/report users | ~10 (the only heavy work) |
| Avg request rate | **~0.15 req/s** across a 9-hour shift |
| Peak request rate | **~1–3 req/s** |
| Bottleneck | **RAM** (fitting JVM + MySQL together), not CPU |

CPU is a non-issue at this volume. The only real question is fitting the JVM and MySQL into a small
RAM box — solved with tight tuning + a swap file.

### Instance options (cheapest first)

| Instance | vCPU / RAM | ~Monthly | Verdict |
|---|---|---|---|
| **EC2 t2.micro** | 1 / 1 GB | **$0** for 12 months (Free Tier), then ~$8.5 | Works with swap + tight tuning; tight when reports run |
| **Lightsail 2 GB** ⭐ | 2 / 2 GB | **~$7** (flat, includes bandwidth + static IP) | Recommended — comfortable, simplest pricing |
| t4g.small (ARM) | 2 / 2 GB | ~$12 on-demand (~$8 with 1-yr Savings Plan) | Graviton alternative to Lightsail |

**Recommendation:** start on **t2.micro** while the Free Tier lasts ($0), or go straight to
**Lightsail 2 GB (~$7/mo)** for headroom — especially for the 10 report users. Both run UI + backend +
MySQL in a single instance. The steps below work on either; tuning differs by RAM (see §6).

---

## 2. Target architecture (single instance)

```
        ┌──────────────── AWS instance (Ubuntu 22.04, t2.micro 1GB / Lightsail 2GB) ────────────────┐
 Staff ─┼─▶ Nginx :443/:80  (Let's Encrypt TLS)                                                       │
 https:// │      /        → /var/www/shifa/admin   (Angular Admin SPA, static files)                  │
        │      /api/    → reverse proxy → 127.0.0.1:8080  (Spring Boot JAR, systemd)                 │
        │                                  └──▶ MySQL 8 (localhost:3306, self-hosted)                │
        │      files → S3 (screenshots/labels)   ·   nightly mysqldump → gzip → S3                    │
        └──────────────────────────────────────────────────────────────────────────────────────────┘
```

Single origin → no CORS, only ports 80/443 public. MySQL runs on `localhost` (never exposed).
Binary files go to **S3** (a few cents/month) or local disk; the DB stays lean so `mysqldump` is fast.

---

## 3. Prerequisites (on your Windows PC)

Build artifacts **locally** and upload them — do **not** build on a 1–2 GB instance (Maven/Angular will
OOM it). You need:
- **Java 21 + Maven** (to build the backend JAR)
- **Node.js 20 + npm** (to build the Angular admin bundle)
- **OpenSSH** (`ssh`/`scp`, built into Windows 10/11)

Build commands (run on the PC):
```powershell
# Backend JAR
mvn -f "backend/pom.xml" -DskipTests package        # -> backend/target/shifa-oms-*.jar

# Angular admin (base-href '/' is the default)
npm --prefix frontend ci
npm --prefix frontend run build:admin                # -> frontend/dist/admin/browser
```

---

## 4. Step-by-step deployment

### Phase A — Create the instance
**Lightsail (recommended, simplest):**
1. Lightsail console → **Create instance** → Region nearest users (**Mumbai `ap-south-1`**).
2. Platform **Linux/Unix** → Blueprint **OS Only → Ubuntu 22.04 LTS**.
3. Plan: **2 GB RAM / 2 vCPU** (~$7/mo). (Or 1 GB / $5 if you want to run tight.)
4. Name `shifa-oms`, **Create**. Then attach a **Static IP** (Networking → Create static IP → attach).

**EC2 t2.micro (Free Tier path):**
1. EC2 → Launch instance → **Ubuntu 22.04**, type **t2.micro** (Free Tier eligible).
2. Create/download a key pair. Storage: 20–30 GB gp3.
3. Security group: allow **22, 80, 443** (see Phase B). Launch, then **allocate + associate an Elastic IP**.

### Phase B — Firewall
- **Lightsail:** instance → **Networking → IPv4 Firewall** → add **HTTP (80)** and **HTTPS (443)** (SSH 22 is default).
- **EC2:** Security Group inbound rules → TCP **22** (your IP), **80** and **443** (`0.0.0.0/0`).

### Phase C — Connect
```bash
# Lightsail: use the browser SSH, or download the key and:
ssh -i "LightsailKey.pem" ubuntu@<STATIC_IP>
# EC2:
ssh -i "your-key.pem" ubuntu@<ELASTIC_IP>
```

### Phase D — Add swap (do this first on a small box)
Essential on 1 GB, recommended on 2 GB — prevents OOM during report spikes:
```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
sudo sysctl vm.swappiness=10 && echo 'vm.swappiness=10' | sudo tee -a /etc/sysctl.conf
```

### Phase E — Install the runtime (NOT build tools)
```bash
sudo apt update && sudo apt -y upgrade
# Java 21 runtime + MySQL + Nginx + client tools. No Maven/Node here (build on your PC).
sudo apt -y install openjdk-21-jre-headless mysql-server nginx unzip
java -version && nginx -v && mysql --version
```

### Phase F — MySQL: create DB + user, then tune small
```bash
sudo mysql_secure_installation
sudo mysql <<'SQL'
CREATE DATABASE IF NOT EXISTS shifa_dashboard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'shifa'@'localhost' IDENTIFIED BY 'STRONG_DB_PASSWORD';
GRANT ALL PRIVILEGES ON shifa_dashboard.* TO 'shifa'@'localhost';
FLUSH PRIVILEGES;
SQL
```
Add a small-footprint config (see §6 for the exact numbers by RAM):
```bash
sudo tee /etc/mysql/mysql.conf.d/shifa.cnf >/dev/null <<'CNF'
[mysqld]
innodb_buffer_pool_size = 256M      # 128M on 1GB t2.micro; 512M on 2GB
performance_schema = OFF            # saves ~150-250MB
max_connections = 50
CNF
sudo systemctl restart mysql
```

### Phase G — Bring your data over from RDS (one-time)
You're currently on RDS — export it and import into the local MySQL, then you can retire RDS (Phase M):
```bash
# From the instance (or your PC), dump the RDS database:
mysqldump -h <RDS_ENDPOINT> -u <RDS_USER> -p --single-transaction \
  --routines --triggers shifa_dashboard > shifa_rds_dump.sql
# Import into the local MySQL:
mysql -u shifa -p shifa_dashboard < shifa_rds_dump.sql
```
> If starting fresh instead, skip this — Flyway (V1→V38) builds the schema automatically on first boot.

### Phase H — Upload the built artifacts
From your **PC** (after building per §3):
```powershell
scp -i "key.pem" backend/target/shifa-oms-*.jar ubuntu@<IP>:/home/ubuntu/shifa-oms.jar
# zip the Angular bundle then upload (or scp -r the folder)
Compress-Archive -Path frontend/dist/admin/browser/* -DestinationPath admin.zip -Force
scp -i "key.pem" admin.zip ubuntu@<IP>:/home/ubuntu/
```
On the **instance**:
```bash
sudo mkdir -p /opt/shifa /var/www/shifa/admin /opt/shifa/storage
sudo mv ~/shifa-oms.jar /opt/shifa/shifa-oms.jar
sudo unzip -o ~/admin.zip -d /var/www/shifa/admin
sudo useradd -r -s /usr/sbin/nologin shifa || true
sudo chown -R shifa:shifa /opt/shifa
```

### Phase I — Backend environment `/etc/shifa/shifa.env`
```bash
sudo mkdir -p /etc/shifa
sudo tee /etc/shifa/shifa.env >/dev/null <<'ENV'
SPRING_PROFILES_ACTIVE=prod
DB_HOST=localhost
DB_PORT=3306
DB_NAME=shifa_dashboard
DB_USERNAME=shifa
DB_PASSWORD=STRONG_DB_PASSWORD
JWT_SECRET=REPLACE_WITH_openssl_rand_base64_48
SEED_ADMIN_PASSWORD=your-admin-pw
SEED_PACKING_PASSWORD=your-packer-pw

# Files: S3 (durable, ~cents/mo) OR LOCAL (block disk). For S3 set the bucket + region.
STORAGE_PROVIDER=S3
STORAGE_S3_BUCKET=shifa-files-<unique>
STORAGE_S3_REGION=ap-south-1
# Prefer an EC2 instance role (leave keys blank). Lightsail has no instance role,
# so on Lightsail set STORAGE_PROVIDER=LOCAL, or provide S3 keys here.

COURIER_MODE=MOCK
WHATSAPP_MODE=MOCK
MAIL_MODE=MOCK

# JVM heap — see §6 (512m on 1GB, 1g on 2GB)
JAVA_OPTS=-Xms256m -Xmx512m
ENV
sudo chmod 600 /etc/shifa/shifa.env
```
> **Lightsail note:** Lightsail instances don't have IAM instance roles. Either use
> `STORAGE_PROVIDER=LOCAL` (files on `/opt/shifa/storage`, archived monthly), or create an IAM user with
> `s3:PutObject/GetObject` and put its keys in `STORAGE_S3_ACCESS_KEY/SECRET_KEY`. On **EC2**, attach an
> instance role instead and leave the keys blank.

### Phase J — Run it as a service (systemd)
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
sudo systemctl daemon-reload && sudo systemctl enable --now shifa-oms
sudo journalctl -u shifa-oms -f     # wait for "Started Application" / "Tomcat started on port 8080"
```

### Phase K — Nginx (Admin SPA at `/`, proxy `/api/`, SSE)
```bash
sudo tee /etc/nginx/sites-available/shifa >/dev/null <<'NGINX'
server {
    listen 80;
    server_name _;                 # replace with your host / sslip.io name
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
    location = /api/admin/events {          # SSE live dashboard — no buffering
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
Open `http://<IP>/` → Admin login (`admin` / your `SEED_ADMIN_PASSWORD`).

### Phase L — Free HTTPS
No domain needed — use an IP-based hostname via **sslip.io** (works like nip.io on AWS):
```bash
sudo apt -y install certbot python3-certbot-nginx
# e.g. IP 13.234.56.78 -> 13-234-56-78.sslip.io
sudo sed -i 's/server_name _;/server_name <IP-with-dashes>.sslip.io;/' /etc/nginx/sites-available/shifa
sudo systemctl reload nginx
sudo certbot --nginx -d <IP-with-dashes>.sslip.io --redirect --agree-tos -m you@example.com --no-eff-email
```
Own a domain? Point an **A record** to the static/Elastic IP and run certbot with `-d yourdomain.com`.

### Phase M — Stop the RDS bill
Once the app runs against local MySQL and you've verified data + logins:
1. Take a **final RDS snapshot** (safety) — RDS → your DB → **Take snapshot**.
2. **Delete the RDS instance** (RDS → Actions → Delete). This stops the ~$7.35/mo charge.
3. **Release idle Elastic IPs** you no longer use (each idle public IPv4 bills ~$3.6/mo).

### Phase N — Backups (you now own this)
```bash
sudo tee /opt/shifa/backup.sh >/dev/null <<'BK'
#!/usr/bin/env bash
set -e
STAMP=$(date +%F)
mkdir -p /opt/shifa/backups
mysqldump -u shifa -p"$DB_PASSWORD" --single-transaction shifa_dashboard | gzip > /opt/shifa/backups/db-$STAMP.sql.gz
# optional off-box copy: aws s3 cp /opt/shifa/backups/db-$STAMP.sql.gz s3://shifa-files-<unique>/backups/
find /opt/shifa/backups -name 'db-*.sql.gz' -mtime +14 -delete
BK
sudo chmod +x /opt/shifa/backup.sh
# nightly at 02:00
( sudo crontab -l 2>/dev/null; echo "0 2 * * * DB_PASSWORD=STRONG_DB_PASSWORD /opt/shifa/backup.sh" ) | sudo crontab -
```
**Test a restore** into a scratch DB at least once — self-hosting means there's no managed safety net.

---

## 5. Redeploying updates
Rebuild on your PC (§3), upload, swap in, restart:
```powershell
scp -i "key.pem" backend/target/shifa-oms-*.jar ubuntu@<IP>:/home/ubuntu/shifa-oms.jar
Compress-Archive -Path frontend/dist/admin/browser/* -DestinationPath admin.zip -Force
scp -i "key.pem" admin.zip ubuntu@<IP>:/home/ubuntu/
ssh -i "key.pem" ubuntu@<IP> "sudo mv ~/shifa-oms.jar /opt/shifa/shifa-oms.jar && sudo rm -rf /var/www/shifa/admin/* && sudo unzip -o ~/admin.zip -d /var/www/shifa/admin && sudo chown -R shifa:shifa /opt/shifa && sudo systemctl restart shifa-oms && sudo systemctl reload nginx"
```
Schema changes: add a new Flyway migration `V39__...sql` (never edit an applied one) — it applies on restart.

---

## 6. Tuning by RAM

| Setting | t2.micro (1 GB) | Lightsail/2 GB |
|---|---|---|
| `JAVA_OPTS` heap | `-Xms256m -Xmx512m` | `-Xms512m -Xmx1g` |
| `DB_POOL_SIZE` | 8 | 12 |
| MySQL `innodb_buffer_pool_size` | `128M` | `512M` |
| MySQL `performance_schema` | `OFF` | `OFF` (or ON if RAM allows) |
| Swap | **2 GB (required)** | 2 GB (recommended) |

Rough footprint: 1 GB box ≈ JVM ~600 MB + MySQL ~250 MB + OS/Nginx ~200 MB (swap covers spikes);
2 GB box ≈ JVM ~1.2 GB + MySQL ~650 MB + OS/Nginx ~250 MB with comfortable headroom.

---

## 7. Optional: in-process caching (Caffeine)
For the ~10 dashboard/report users, cache the expensive aggregates **in-process** — do NOT add Redis/
ElastiCache (extra paid service, unnecessary for one instance):
- Add `spring-boot-starter-cache` + `com.github.ben-manes.caffeine:caffeine`, `@EnableCaching`, and
  `@Cacheable` on the dashboard-summary / report methods with a short TTL (30–60 s).
- Keep invalidation simple: let entries expire by TTL; optionally evict a salesperson's own list on their
  order write. Avoid hand-managing per-field cache mutations (a common source of stale-data bugs). At
  ~400 orders/day the salespeople's GETs are trivial for MySQL even uncached — caching is really about the
  10 report users.

---

## 8. Cost summary

| Item | During Free Tier (12 mo) | After Free Tier |
|---|---|---|
| Compute (t2.micro / Lightsail 2 GB) | **$0** / ~$7 | ~$8.5 / ~$7 |
| MySQL (self-hosted, no RDS) | $0 | $0 |
| S3 files + backups | ~$0.05–0.20 | ~$0.05–0.20 |
| Static IP (attached) | $0 | $0 |
| **Total** | **~$0–7/mo** | **~$7–9/mo** |

Removing RDS (~$7.35) + idle Elastic IPs (~$3.6 each) is where the real saving comes from.

---

## 9. Cost-efficiency checklist
- ✅ One instance runs UI + API + MySQL — no RDS, no Lambda, no DynamoDB, no ElastiCache.
- ✅ Self-host MySQL on `localhost`; keep binaries in S3/local so `mysqldump` stays small.
- ✅ Build artifacts on your PC — never on a 1–2 GB instance.
- ✅ Add swap; tune JVM + MySQL to the box (§6).
- ✅ Release idle Elastic IPs; keep exactly one attached static IP.
- ✅ Set an **AWS Budget alert** (Billing → Budgets, e.g. $5) so surprises page you early.
- ✅ Nightly `mysqldump` → S3; test one restore.
- ✅ Delete the old RDS instance after cutover (keep one final snapshot).
