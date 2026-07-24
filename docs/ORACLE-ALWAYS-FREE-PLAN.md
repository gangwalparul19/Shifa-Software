# Shifa OMS — Oracle Cloud Always Free Deployment Plan (100 users, free forever)

Goal: host the **dashboard-only** Shifa OMS (Admin Angular app + Spring Boot API + MySQL 8) on
**Oracle Cloud Infrastructure (OCI) Always Free** so it serves **~100 staff accounts (≈60–70 working
concurrently, 9 AM–6 PM IST)** and **never accrues a bill**.

> This plan supersedes the older `DEPLOYMENT.md` in two ways: (1) there is **no public storefront**
> anymore (the store is on Shopify) — we serve only the **admin app at `/`** plus the `/api/` backend;
> (2) it adds explicit **capacity sizing for 100 users** and an **"always free forever"** strategy.
> The mechanical VM/SSH/nginx steps in `DEPLOYMENT.md` still apply — this doc is the decision + sizing
> layer on top of it.

---

## 1. Sizing for the real workload: 60–70 concurrent users (9 AM–6 PM shift)

The client confirmed the actual load: **~60–70 staff working the same 9 AM–6 PM IST shift, all online
at once.** That is very different from a trickle of occasional logins — it's a **sustained, all-day
concurrent load**, and it moves the bottleneck from RAM to **CPU**.

| Resource | Always Free allowance | We use | Why |
|---|---|---|---|
| **Compute (Ampere A1 Arm)** | up to **4 OCPU / 24 GB RAM** total, free | **1 VM: 4 OCPU / 24 GB** | 4 cores absorb the 9 AM login stampede + simultaneous reports; RAM lets JVM + MySQL both run big |
| **Block storage** | **200 GB** total (≤2 volumes) | ~50 GB boot volume | DB + files fit for ~20 years (see §1b) |
| **Object Storage** | **20 GB** + 50k req/mo | optional, for archives | Monthly screenshot zips |
| **Egress** | **10 TB/month** outbound | tiny | LOB app won't get close |
| **Load balancer** | 1 flexible LB (10 Mbps) free | **not used** | Nginx on the VM terminates TLS |

**Recommendation (changed): provision the FULL free A1 shape — 4 OCPU / 24 GB.** It is the same
Always-Free tier (₹0), and for 60–70 concurrent users the extra 2 cores are exactly the resource under
pressure. Only fall back to 2 OCPU / 12 GB if regional capacity blocks A1 4/24 — and **resize up the
moment capacity frees** (§1a explains the impact of running on 2/12).

**Tuning for 4 OCPU / 24 GB** (`/etc/shifa/shifa.env` + MySQL):
```
# Spring Boot: 6 GB heap (room for 60–70 concurrent + a growing codebase)
JAVA_OPTS=-Xms2g -Xmx6g
DB_POOL_SIZE=30

# MySQL (/etc/mysql/mysql.conf.d/mysqld.cnf)
innodb_buffer_pool_size = 8G
max_connections = 200
```
Footprint ≈ JVM ~7 GB + MySQL ~9 GB + OS/Nginx ~1.5 GB ≈ **~17–18 GB of 24 GB**, leaving ~6 GB free.

---

## 1a. Impact of running on 2 OCPU / 12 GB with 60–70 concurrent users

If A1 capacity forces you onto **2 OCPU / 12 GB**, the app still runs the **entire feature set** and
handles 60–70 concurrent users for **ordinary CRUD and dashboard work** — but it is **tight, not
comfortable**, because the 2 cores are *shared* between the JVM and MySQL. Expect visible slowdowns at
peaks. Be honest with the client about this; 4/24 is the right size for this load.

**Load estimate (60–70 concurrent):** each active user fires ~1 request every 5–8 s of think time →
**sustained ~10–20 requests/second**, with peaks to **~40–60 req/s**. Two Ampere cores handle the
*sustained* rate (most requests are short and spend time waiting on DB I/O, not CPU). The problems are
the **peaks**:

- **9 AM login stampede** — bcrypt password hashing is deliberately CPU-heavy (~50–100 ms each). 60–70
  logins inside a few minutes is a real CPU spike on 2 cores.
- **Simultaneous heavy endpoints** — several people running reports/analytics, generating invoice/label
  PDFs, or refreshing the role dashboard at the same moment. On 2 cores these queue behind each other →
  latency for everyone.
- **JVM ↔ MySQL CPU contention** — under concurrent load both want CPU at once, and 2 cores leave no
  slack.

**RAM is fine** on 12 GB (JVM ~3.5–4 GB + MySQL ~3.5 GB + OS ~1 GB ≈ 8–8.5 GB). **CPU is the limit.**

**Realistic verdict:**
- **60–70 concurrent, normal work** → **works, but tight** — fine most of the day, sluggish at peaks.
- **60–70 concurrent + several heavy reports/PDFs at once** → noticeable latency; requests queue.
- The fix is CPU: **resize to 4 OCPU / 24 GB** (free) — it's a shape change, no migration, no data move.

**Mitigations if you must stay on 2/12 for now:**
```
# Spring Boot: 3 GB heap
JAVA_OPTS=-Xms1g -Xmx3g
DB_POOL_SIZE=25            # enough concurrency without thrashing 2 cores

# MySQL (/etc/mysql/mysql.conf.d/mysqld.cnf): 3 GB InnoDB buffer pool
innodb_buffer_pool_size = 3G
```
- **Cache the role dashboard summary** with a short TTL so 60–70 simultaneous dashboard loads don't each
  recompute the aggregates.
- **Shift heavy reports/analytics to off-peak** or run them async; the nightly insights + backup jobs
  already run at 02:00.
- Keep the existing admin-table indexes (migrations V14/V15) — they matter under concurrency.
- Add a 2 GB swap file as an OOM safety net:
```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```
- **Watch CPU during the 9 AM window** (`top`/`htop`). If it pins at ~100% and requests back up, that's
  the signal to move to 4/24.

> **Bottom line:** for 60–70 concurrent all-day users, go **4 OCPU / 24 GB** from the start (it's free).
> 2/12 is a temporary fallback that works but will feel slow at peaks.

---

## 1b. Data & storage growth (5,000 orders/month → 60,000/year)

At the expected volume, split growth into two very different buckets: the **relational database**
(tiny, keep in MySQL) and **binary files** (screenshots + label PDFs — the only thing that grows fast).

### Relational data (MySQL) — stays small
Per year at 60,000 orders: orders (~60 MB) + line items (~20 MB) + status history (~500–600k rows,
~120 MB) + payments/courier/receivables/notifications/audit/stock/leads (~100–150 MB), plus ~40–60%
for indexes → **≈ 0.5–1 GB/year**. Over 5 years that's ~5 GB — trivial against the 200 GB block
volume, and the **3 GB InnoDB buffer pool caches all hot/recent orders** for years (old orders just
hit disk occasionally). Nightly `mysqldump` stays fast **as long as binaries are NOT stored in the DB**
(see below).

### Binary files — the part that actually grows
| Asset | Volume | Retention need | Plan |
|---|---|---|---|
| **Payment screenshots** (`payments/{orderId}/…`) | ~0.3–0.75 GB/month (compressed JPEG ~150–400 KB each; depends on % prepaid) → **~4–9 GB/year** | **Durable — irreplaceable** | Archive monthly; keep a rolling window live |
| **Label PDFs** (`labels/internal/<code>.pdf`) | ~0.2 GB/month (~30–50 KB each) → ~2.4 GB/year | **Disposable** | Purge freely — the print endpoint **re-renders them deterministically from order state** (`LabelService.internalLabelPdf`), so reprinting an old label still works after deletion |

**Key insight:** only **payment screenshots** must be retained. Label PDFs regenerate on demand, so
they can be purged on a short cycle and don't count toward long-term growth.

### Recommended storage strategy (free forever)
Use **`STORAGE_PROVIDER=LOCAL`** — files on the 200 GB block volume under `/opt/shifa/storage`, **not**
in MySQL (keeps the DB lean and `mysqldump` fast). Then run one **monthly maintenance job** (cron):

1. **Purge label PDFs** older than ~1 month (`labels/internal/`) — they regenerate on demand.
2. **Zip last month's screenshots** → `shifa-screenshots-YYYY-MM.zip`.
3. **Hand off the zip** to the client (and/or upload to OCI Object Storage — see below).
4. **Delete the archived originals** from disk once handoff is confirmed (optional — see note).

```bash
# /opt/shifa/monthly-archive.sh  (run 1st of month via cron)
MON=$(date -d "last month" +%Y-%m)
SRC=/opt/shifa/storage/payments
OUT=/opt/shifa/archives/shifa-screenshots-$MON.zip
mkdir -p /opt/shifa/archives
# zip files modified last month, then (optionally) delete them
find "$SRC" -type f -newermt "$MON-01" ! -newermt "$(date +%Y-%m-01)" -print | zip -q "$OUT" -@
# purge disposable label PDFs older than 30 days
find /opt/shifa/storage/labels/internal -type f -mtime +30 -delete
# optional: push the zip to OCI Object Storage, then remove local originals
# oci os object put --bucket-name shifa-archives --file "$OUT"
```

> **Trade-off to accept:** once a screenshot is deleted from disk, the order-detail "view screenshot"
> link for that old order returns 404 (the order's amount/status stay in the DB — only the image is
> offloaded). Reconciliation happens within the month, so keep a **rolling 2–3 month window live** and
> archive older. If disk space is not a concern (it isn't — see below), you can **skip deletion
> entirely** and just take the monthly zip as an off-VM backup.

### Do we even need to delete? (block volume math)
The 200 GB block volume is enormous for this app: at ~9 GB/year of screenshots (worst case, keeping
labels too), local disk alone lasts **~20 years**. So deletion is a **business/handoff choice**, not a
capacity necessity. Two clean options:

- **Option 1 (recommended): keep local + monthly zip to client.** Simplest, guaranteed free, DB stays
  lean. The monthly zip doubles as an off-site backup the client holds. Delete originals only if you
  want a minimal footprint.
- **Option 2: OCI Object Storage as the live store.** Point `STORAGE_PROVIDER=S3` at OCI Object
  Storage's **S3-compatible endpoint** (`STORAGE_S3_ENDPOINT`, bucket + Customer Secret Key) — files
  live off the VM, durable. Free tier is **20 GB** (≈ 2–4 years of screenshots) + 50k requests/month
  (well under 5k uploads + views). Add an **Object Storage lifecycle rule** to move objects older than
  ~2 months to the **Archive tier** and/or delete after the monthly client handoff, keeping you inside
  20 GB forever. (Note: the bundled `OciStorageService` is a stub — use the S3-compat path.)

**Recommendation:** Option 1 (local + monthly zip handover, label purge). It's the least moving parts,
provably free forever, and matches "archive monthly and send the client a zip." Use Option 2 only if
the client prefers Oracle to hold the archives instead of receiving zips.

---

## 2. Target architecture (single Always-Free A1 VM)

```
                 ┌──────────── OCI Always-Free A1 VM (Ubuntu 22.04, 4 OCPU / 24 GB) ───────────┐
  Staff browser ─┼─▶ Nginx :443/:80 (Let's Encrypt TLS)                                          │
  https://host/  │      /        → /var/www/shifa/admin   (Angular admin SPA)                    │
  https://host/  │      /api/    → reverse proxy → 127.0.0.1:8080  (Spring Boot JAR, systemd)    │
                 │                                    └──▶ MySQL 8 (localhost:3306)              │
                 │      nightly: mysqldump → gzip → /opt/shifa/backups (+ optional Object Storage)│
                 └──────────────────────────────────────────────────────────────────────────────┘
```

Everything is one origin → **no CORS**, only ports **80/443** exposed publicly. Binary files (payment
screenshots, label PDFs) are stored on the **block volume** (`STORAGE_PROVIDER=LOCAL`, under
`/opt/shifa/storage`) — **not** in MySQL — so the nightly `mysqldump` stays small and fast, and files
are archived monthly (see §1b). MySQL holds only relational data.

---

## 3. Step-by-step plan

### Phase A — Create the Oracle account (once, ~15 min)
1. Go to **oracle.com/cloud/free** → **Start for free**.
2. Sign up: email, **Country = India**, mobile OTP, and a **credit/debit card for identity only**
   (Always Free is not charged; a small refundable hold may appear).
3. **Home Region**: pick the one nearest your staff — **India West (Mumbai)** or **India South
   (Hyderabad)**. ⚠️ **Cannot be changed later.**
4. Log in to the OCI Console (cloud.oracle.com). Look for the green **"Always Free Eligible"** tags.
5. **Immediately do the "free forever" hardening in §4** (budget alert + PAYG decision) before you
   build anything.

### Phase B — Provision services
Only these OCI services are needed (all free):
1. **Networking → VCN**: run **Start VCN Wizard → "VCN with Internet Connectivity"** (name `shifa-vcn`).
   This creates a public subnet + Internet Gateway + route — avoids the "public IP toggle disabled" trap.
2. **Compute → Instance** `shifa-oms`:
   - Image: **Ubuntu 22.04**.
   - Shape: **VM.Standard.A1.Flex** → **4 OCPU / 24 GB** (or 2/12 if capacity is tight).
   - Network: select existing `shifa-vcn` + its **public subnet**, **enable public IPv4**.
   - SSH: **generate a key pair** and download both keys.
   - Create → wait for **Running** → note the **public IP**.
3. **Object Storage** (optional, for off-VM backups): create a **bucket** `shifa-backups` in the same
   region. Skip if you only want on-VM backups.

### Phase C — Network + firewall
1. VCN → Default Security List → **Add Ingress**: TCP **80** and TCP **443** from `0.0.0.0/0`
   (22 is already open).
2. On the VM, open Ubuntu's iptables too:
   ```bash
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80  -j ACCEPT
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
   sudo netfilter-persistent save
   ```

### Phase D — Install the stack (on the VM)
```bash
sudo apt update && sudo apt -y upgrade
sudo apt -y install openjdk-21-jdk maven git unzip nginx mysql-server mysql-client
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt -y install nodejs
```

### Phase E — Database
```bash
sudo mysql_secure_installation
sudo mysql <<'SQL'
CREATE DATABASE IF NOT EXISTS shifa_dashboard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'shifa'@'localhost' IDENTIFIED BY 'STRONG_DB_PASSWORD';
GRANT ALL PRIVILEGES ON shifa_dashboard.* TO 'shifa'@'localhost';
FLUSH PRIVILEGES;
SQL
```
Flyway runs migrations V1→V38 automatically on first backend start — do **not** create tables by hand.
Start against an **empty** `shifa_dashboard` (V22 seed uses explicit IDs).

### Phase F — Backend config (`/etc/shifa/shifa.env`)
Copy `deploy/shifa.env.example` → `/etc/shifa/shifa.env` (`chmod 600`) and set, at minimum:
```
SPRING_PROFILES_ACTIVE=prod
DB_USERNAME=shifa
DB_PASSWORD=STRONG_DB_PASSWORD
JWT_SECRET=<openssl rand -base64 48>
SEED_ADMIN_PASSWORD=<your admin pw>
SEED_PACKING_PASSWORD=<your packer pw>

# Free-forever: store files on the block volume (NOT in MySQL, so mysqldump stays
# small/fast); archived monthly per §1b. /opt/shifa/storage must be writable by
# the shifa service user.
STORAGE_PROVIDER=LOCAL

# Integrations stay mock unless/until you wire real providers
COURIER_MODE=MOCK
WHATSAPP_MODE=MOCK
MAIL_MODE=MOCK          # switch to SMTP + Gmail app password to send real email

# JVM sizing for the 24 GB box
JAVA_OPTS=-Xms512m -Xmx2g
```
> **Important:** the prod default is `STORAGE_PROVIDER=S3`, which **fails to start** with no bucket.
> For Oracle-only/free, set `STORAGE_PROVIDER=DB` (or `LOCAL`) as above.

### Phase G — Build + deploy (dashboard-only)
The old `deploy/build-and-deploy.sh` and `deploy/nginx-shifa.conf` still build/serve a storefront at
`/`. Since the storefront is gone, serve the **admin app at `/`**. Two options:

- **Simple:** build the admin app with base-href `/` and point Nginx `root` at it:
  ```bash
  npm --prefix ~/shifa/frontend ci
  npm --prefix ~/shifa/frontend run build:admin        # produces dist/admin/browser
  sudo mkdir -p /var/www/shifa/admin
  sudo cp -r ~/shifa/frontend/dist/admin/browser/* /var/www/shifa/admin/
  cd ~/shifa/backend && mvn -DskipTests package
  sudo mkdir -p /opt/shifa && sudo cp target/shifa-oms-*.jar /opt/shifa/shifa-oms.jar
  ```
- Nginx site (replaces the storefront/admin split): serve the SPA at `/`, proxy `/api/`, keep the SSE
  block. See the ready-to-use config in the **Appendix**.

Install the systemd unit and start:
```bash
sudo useradd -r -s /usr/sbin/nologin shifa || true
sudo cp ~/shifa/deploy/shifa-oms.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now shifa-oms
sudo journalctl -u shifa-oms -f     # look for "Started Application" / "Tomcat started on port 8080"
```

### Phase H — HTTPS (free)
Use a **nip.io** hostname (no domain purchase) or your own domain's A record → public IP, then:
```bash
sudo apt -y install certbot python3-certbot-nginx
sudo certbot --nginx -d <PUBLIC-IP-with-dashes>.nip.io --redirect --agree-tos -m you@example.com --no-eff-email
```
Certbot installs auto-renewal. Share `https://<host>/` with staff.

### Phase I — Backups (protect the data)
The app runs a nightly `mysqldump` (`BACKUP_CRON=0 0 2 * * *`) to `/opt/shifa/backups`. For off-VM
durability, sync those to the **Object Storage bucket** from §B.3 via a cron using the OCI CLI or the
S3-compat endpoint. Test a restore at least once.

---

## 4. Staying "Always Free — forever" (the important part)

Free tier can quietly bite you two ways: **idle reclamation** and **accidental paid resources**.
Do all of these:

1. **Upgrade to Pay-As-You-Go (PAYG) but stay within Always Free limits.**
   Oracle may **reclaim idle compute on accounts that are still "Always Free" (never upgraded)**.
   Upgrading to PAYG **stops the idle-reclaim risk** and, as long as you only use Always-Free-eligible
   resources (the A1 shape, ≤200 GB block, etc.), **your bill stays ₹0**. This is the single best move
   for "free forever." (A1 instances are less aggressively reclaimed than x86 micro, but PAYG removes
   the risk entirely.)
2. **Create a Budget alert:** Billing → **Budgets → Create budget** at a tiny threshold (e.g. ₹1 / $1)
   so you're emailed the instant *anything* accrues cost.
3. **Only ever pick "Always Free Eligible" resources.** Stick to **VM.Standard.A1.Flex** within
   **4 OCPU / 24 GB total**, boot/block volume within **200 GB total**, no Load Balancer, no extra DBs.
4. **Don't exceed the A1 total across all instances** — if you make a second VM, the OCPU/RAM is shared
   from the same 4/24 pool.
5. **Watch Object Storage requests** (50k/month free) if you push frequent backups — nightly is fine.
6. **Keep egress in mind** (10 TB/mo free) — irrelevant for a dashboard, listed for completeness.
7. **Check Cost Analysis** (Billing → Cost Analysis) monthly for the first few months.

Net effect: one A1 VM (4 OCPU/24 GB) + ≤200 GB block + optional 20 GB Object Storage, on a **PAYG
account with a ₹1 budget alert**, runs Shifa OMS for 100 users **at zero cost, indefinitely.**

---

## 5. Day-2 operations
- **Redeploy:** rebuild JAR + admin bundle, copy into `/opt/shifa` and `/var/www/shifa/admin`,
  `sudo systemctl restart shifa-oms && sudo systemctl reload nginx`. `apply-on-vm.sh` never touches the
  DB or `/etc/shifa/shifa.env`.
- **Schema changes:** add a new Flyway migration `V39__...sql` (never edit an applied one); Flyway
  applies it on the next restart against live data.
- **Logs:** `sudo journalctl -u shifa-oms -f`, `sudo tail -f /var/log/nginx/error.log`.
- **Restore drill:** `gunzip < backup.sql.gz | mysql -u shifa -p shifa_dashboard` into a scratch DB.

---

## 6. Cost summary

| Item | Tier | Monthly cost |
|---|---|---|
| A1 VM (4 OCPU / 24 GB) | Always Free | ₹0 |
| 50 GB boot volume (of 200 GB free) | Always Free | ₹0 |
| Object Storage backups (< 20 GB) | Always Free | ₹0 |
| 10 TB egress | Always Free | ₹0 |
| TLS cert (Let's Encrypt) | Free | ₹0 |
| nip.io hostname (or your own domain) | Free (domain optional) | ₹0 |
| **Total** | | **₹0 / month** |

Only real optional spend later: a custom domain name (~₹700–1000/yr) and, if you switch email/courier/
WhatsApp from mock to live, those providers' own fees — none required to run the app.

---

## Appendix — Dashboard-only Nginx site

Install to `/etc/nginx/sites-available/shifa`, symlink into `sites-enabled`, remove `default`,
then `sudo nginx -t && sudo systemctl reload nginx`. Certbot (Phase H) adds the 443 block + redirect.

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name _;                 # replace with your IP / nip.io host

    client_max_body_size 15m;      # payment screenshots
    gzip on;
    gzip_types text/plain text/css application/javascript application/json image/svg+xml;
    gzip_min_length 1024;

    # ---- API -> Spring Boot ----
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }

    # ---- Live dashboard (SSE): buffering must be off ----
    location = /api/admin/events {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host       $host;
        proxy_set_header Connection '';
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
    }

    # ---- Admin SPA served at root ----
    location / {
        root /var/www/shifa/admin;
        try_files $uri $uri/ /index.html;
    }
}
```

> `npm --prefix frontend run build:admin` (= `ng build admin`) already builds with the default
> base-href `/`, which matches serving the SPA at root — no `--base-href` change needed. Output lands
> in `frontend/dist/admin/browser`; copy that into `/var/www/shifa/admin`.
