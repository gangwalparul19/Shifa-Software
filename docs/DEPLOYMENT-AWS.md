# Shifa Herbal Remedies — Deploy on AWS (Free Tier) for a Client Demo

> **👉 First-time AWS user?** Start with the beginner, step-by-step, **RDS-based** guide:
> **`docs/aws-free-tier-guide.html`** — it's the recommended production-on-free-tier plan (EC2 app +
> managed RDS database, tuned for 50 concurrent users, with spending-protection set up first and an
> optional Lambda section). This markdown file is the fuller technical reference (incl. the single-box
> MySQL demo and the zero-downtime upgrade).

A complete, copy-paste guide to host the whole **dashboard-only** app (Admin UI + API + MySQL) on **one
AWS EC2 instance**, staying inside the AWS Free Tier / your sign-up credits.

> **TL;DR — Can I stay free with $100 credit?** **Yes.** Run everything on a single **`t4g.small`
> EC2** instance, which is **free for 750 hours/month until 31 Dec 2026** (one instance 24×7 ≈ 744 h),
> with MySQL on the same box. The only small always-on line item is the **public IPv4 address
> (~$3.6/month)**, which is itself free for your first 12 months and otherwise easily covered by your
> **$100–$200 sign-up credits**. Realistic out-of-pocket for this demo: **$0 for ~a year+**. Full cost
> table in section 17.

**What you'll end up with**
- Admin panel (staff): `http://<your-host>/` (login `admin` / your chosen password)
- One EC2 instance running: **Nginx** (web + reverse proxy) + **Spring Boot** (API) + **MySQL 8** (DB)

The Oracle guide (`DEPLOYMENT.md`) and the helper files in `deploy/` (`shifa-oms.service`,
`shifa.env.example`, `apply-on-vm.sh`, `package-local.ps1`) are reused almost unchanged — only the
Nginx site and the cloud-console steps differ.

---

## 0. Architecture (single Free-Tier EC2)

```
                       ┌──────────────── AWS EC2  t4g.small (Ubuntu 24.04, ARM/Graviton) ─────────────┐
   Staff browser  ──▶  │  Nginx :80/:443                                                              │
   https://host/       │    /          →  /var/www/shifa/admin   (Angular admin SPA)                  │
   https://host/api/   │    /api/      →  reverse proxy → 127.0.0.1:8080  (Spring Boot fat JAR)       │
                       │                                   │                                           │
                       │                                   └──▶  MySQL 8  (localhost:3306)            │
                       └──────────────────────────────────────────────────────────────────────────────┘
```

The store was retired (moved to Shopify), so this is **admin + API + DB only** — the Angular **admin**
app is served at the site root `/`. Same origin ⇒ no CORS, and you only expose ports 80/443 publicly.

**Why one EC2 (not RDS/ELB/etc.)?** It's the cheapest and simplest, mirrors the existing OCI setup, and
keeps you inside a single free meter. An optional "EC2 + RDS MySQL" variant is noted in section 16.

---

## 1. Create an AWS account & understand the 2025 Free Tier

1. Go to **https://aws.amazon.com/free/** → **Create a Free Account**. Sign up with email, then choose a
   plan at the end:
   - **Free Plan (no charges possible):** no charges beyond your credits; the account runs on the
     **$100 sign-up credits (+ up to $100 more = $200)** for **up to 6 months**. When credits run out
     or 6 months pass, resources pause until you upgrade. Best if you want a hard "cannot be charged"
     guarantee for the demo.
   - **Paid Plan (recommended for this app):** you add a card, still **get the $100–$200 credits**, AND
     you unlock the classic **12-month free-tier allowances** (750 h RDS, 30 GB EBS, 100 GB/mo egress)
     plus the **Graviton `t4g` promo** below. You only ever pay if you exceed free limits *and* run out
     of credits. Set a **Budget alarm** (section 18) so there are no surprises.
2. Pick a **Region** near your users and keep using it: **Asia Pacific (Mumbai) `ap-south-1`** for India.
   (All prices below are `ap-south-1`.)

> **The key free allowances this app relies on** (verify current terms on the linked AWS pages):
> - **EC2 `t4g.small` (2 vCPU / 2 GB, Graviton2): free up to 750 hours/month until 31 Dec 2026** —
>   [aws.amazon.com/ec2/instance-types/t4/](https://aws.amazon.com/ec2/instance-types/t4/). One instance
>   run 24×7 uses ~744 h, so it fits in the 750 h.
> - **Public IPv4**: 750 hours/month free for the first 12 months (then ~$0.005/h ≈ **$3.6/mo**).
> - **EBS**: 30 GB gp3 free for 12 months.
> - **Data transfer out**: 100 GB/month free.
> - **$100 sign-up credits (up to $200)** cover anything outside those windows —
>   [aws.amazon.com/free/offers](https://aws.amazon.com/free/offers/).
>
> *Content re-summarised from AWS pages for compliance; confirm the live terms before you rely on them.*

---

## 2. Launch the EC2 instance

1. Console → search **EC2** → **Instances → Launch instances**.
2. **Name**: `shifa-demo`.
3. **Application and OS Images (AMI)**: **Ubuntu Server 24.04 LTS**. ⚠️ **Architecture: select `64-bit
   (Arm)`** — this is required to use the free **`t4g`** Graviton instances.
4. **Instance type**: **`t4g.small`** (2 vCPU, 2 GB) — the free Graviton promo shape. (`t4g.micro` at 1 GB
   is too tight once MySQL + JVM are both up.)

   > **Sizing for ~50 users (important).** The workload is light and the data volume is tiny for MySQL,
   > but on a 2 GB box the JVM and MySQL *share* RAM, which is the only real constraint. Choose:
   > - **A (recommended for ~50 users, stays ~free):** `t4g.small` for the app **+ RDS `db.t4g.micro`**
   >   for MySQL (section 16). The DB gets its own instance; both are free for 12 months.
   > - **B (single bigger box):** **`t4g.medium`** (2 vCPU / **4 GB**) with local MySQL — ~$24/mo (not in
   >   the free promo), comfortable for 50 users and simplest ops.
   > - **C (demo / ≤~30 light users, free):** stay on `t4g.small` with local MySQL, but add a **2 GB swap
   >   file**, set MySQL `innodb_buffer_pool_size ≈ 320M` and JVM `-Xmx640m`.
   >
   > MySQL itself handles this data volume (~30k orders in 6 months) easily with the existing indexes —
   > the sizing question is about RAM headroom, not database capacity.
5. **Key pair**: **Create new key pair** → type **RSA**, format **.pem** → download and keep it safe
   (you'll SSH with it). Name it e.g. `shifa-key`.
6. **Network settings → Edit** → **Create security group** with these inbound rules:
   - **SSH** TCP **22** — Source **My IP** (lock SSH to your own IP; widen later only if needed).
   - **HTTP** TCP **80** — Source **Anywhere `0.0.0.0/0`**.
   - **HTTPS** TCP **443** — Source **Anywhere `0.0.0.0/0`**.
   - Leave **Auto-assign public IP = Enable**.
7. **Configure storage**: **30 GB**, **gp3** (within the 30 GB free allowance).
8. **Launch instance**. When it shows **Running**, note the **Public IPv4 address**.

> **Stable IP (recommended):** Console → EC2 → **Elastic IPs → Allocate** → then **Associate** it to
> `shifa-demo`. An Elastic IP is free **while associated with a running instance**; the public-IPv4
> hourly charge (section 17) applies whether it's elastic or auto-assigned. Using an Elastic IP means
> your URL doesn't change if you stop/start the instance.

---

## 3. Connect over SSH

From your Windows machine (PowerShell), using the `.pem` you downloaded:

```powershell
# First time only: restrict the key file's permissions so SSH accepts it
icacls "C:\path\to\shifa-key.pem" /inheritance:r /grant:r "$($env:USERNAME):(R)"

ssh -i "C:\path\to\shifa-key.pem" ubuntu@<PUBLIC_IP>
```
- Default user for the Ubuntu AMI is **`ubuntu`**. Accept the fingerprint the first time.

Everything from here runs **on the instance** (the `ubuntu@...` shell).

---

## 4. Install prerequisites

```bash
sudo apt update && sudo apt -y upgrade

# Java 21 (Spring Boot JAR) + build tools
sudo apt -y install openjdk-21-jdk git unzip

# MySQL 8 server + client (mysqldump for backups)
sudo apt -y install mysql-server mysql-client

# Nginx web server / reverse proxy
sudo apt -y install nginx

# sanity check
java -version && nginx -v && mysql --version
```

> **Do NOT build the app on this 2 GB instance** — the Angular build needs more RAM. You'll build on
> your PC and upload the artifacts (section 8). That's why Maven/Node aren't installed here.

---

## 5. Set up MySQL

```bash
sudo mysql_secure_installation   # set a root password; answer Y to the hardening prompts

sudo mysql <<'SQL'
CREATE DATABASE IF NOT EXISTS shifa_dashboard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'shifa'@'localhost' IDENTIFIED BY 'CHANGE_ME_STRONG_DB_PASSWORD';
GRANT ALL PRIVILEGES ON shifa_dashboard.* TO 'shifa'@'localhost';
FLUSH PRIVILEGES;
SQL
```
Use the **same** password you'll put in the env file (section 7). Flyway runs all migrations
(V1…V27, including the demo/test seed) automatically on first backend start — you don't create tables
by hand.

---

## 6. Prepare app directories + service user

```bash
sudo useradd -r -s /usr/sbin/nologin shifa || true
sudo mkdir -p /opt/shifa /var/www/shifa/admin /etc/shifa
```

---

## 7. Configure the backend environment

```bash
# copy the example from the repo once you've uploaded it (section 8), or paste it manually:
sudo nano /etc/shifa/shifa.env
```
Use `deploy/shifa.env.example` as the template and fill in:
- `SPRING_PROFILES_ACTIVE=prod`
- `DB_HOST=localhost`, `DB_NAME=shifa_dashboard`, `DB_USERNAME=shifa`, `DB_PASSWORD=` (from section 5)
- `JWT_SECRET=` → generate: `openssl rand -base64 48`
- `SEED_ADMIN_PASSWORD`, `SEED_PACKING_PASSWORD` → your demo passwords
- Keep integrations mocked for the demo: `COURIER_MODE=MOCK`, `WHATSAPP_MODE=MOCK`, `MAIL_MODE=MOCK`
- Small-VM heap: `JAVA_OPTS=-Xms256m -Xmx768m`

```bash
sudo chmod 600 /etc/shifa/shifa.env
```
The production API base URL is already same-origin (`/api`), so the built admin app calls the API
through Nginx — nothing to change there.

---

## 8. Build on your PC and upload (the instance is too small to build)

On your **Windows PC** (needs Java 21 + Maven + Node 20):

```powershell
# 1) Build the backend JAR
cd "c:\E Drive\Shifa-Software\backend"
mvn -DskipTests package        # produces target\shifa-oms-*.jar

# 2) Build the Angular ADMIN app for serving at the site ROOT
cd "c:\E Drive\Shifa-Software\frontend"
npm ci
npx ng build admin --configuration production --base-href /

# 3) Upload the JAR + admin bundle to the instance
scp -i "C:\path\to\shifa-key.pem" "..\backend\target\shifa-oms-0.0.1-SNAPSHOT.jar" ubuntu@<PUBLIC_IP>:/home/ubuntu/shifa-oms.jar
scp -i "C:\path\to\shifa-key.pem" -r ".\dist\admin\browser\*" ubuntu@<PUBLIC_IP>:/home/ubuntu/admin-dist
# also upload the deploy helper files once
scp -i "C:\path\to\shifa-key.pem" "..\deploy\shifa-oms.service" "..\deploy\shifa.env.example" ubuntu@<PUBLIC_IP>:/home/ubuntu/
```

Then **on the instance**, put the artifacts in place:
```bash
sudo cp ~/shifa-oms.jar /opt/shifa/shifa-oms.jar
sudo chown shifa:shifa /opt/shifa/shifa-oms.jar
sudo rm -rf /var/www/shifa/admin/*
sudo cp -r ~/admin-dist/* /var/www/shifa/admin/
sudo chown -R www-data:www-data /var/www/shifa
```

> The exact JAR filename/dist path may differ slightly by version — check with `ls backend/target/*.jar`
> and `ls frontend/dist/admin/browser`. Angular sometimes emits to `dist/admin/browser` (SSR-style
> layout) or `dist/admin`; upload whichever contains `index.html`.

---

## 9. Install the backend as a service (systemd)

```bash
sudo cp ~/shifa-oms.service /etc/systemd/system/shifa-oms.service
sudo systemctl daemon-reload
sudo systemctl enable --now shifa-oms

sudo journalctl -u shifa-oms -f   # watch for "Started Application" / "Tomcat started on port 8080"
```
It connects to MySQL, runs Flyway migrations, and listens on `127.0.0.1:8080`. (Ctrl-C to stop tailing.)

---

## 10. Configure Nginx (admin served at `/`)

Create the site (admin at root, API proxied, SSE unbuffered):

```bash
sudo tee /etc/nginx/sites-available/shifa >/dev/null <<'NGINX'
server {
    listen 80;
    listen [::]:80;
    server_name _;                 # replace with your IP / hostname
    client_max_body_size 15m;

    gzip on;
    gzip_types text/plain text/css application/javascript application/json image/svg+xml;
    gzip_min_length 1024;

    # API -> Spring Boot
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }

    # Server-Sent Events (admin live dashboard) — buffering OFF
    location = /api/admin/events {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host       $host;
        proxy_set_header Connection '';
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
    }

    # Admin SPA at the site root
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

> On the Ubuntu EC2 AMI there is **no host-level firewall blocking 80/443** (unlike OCI's iptables) —
> the **Security Group** from section 2 is the only firewall, so there's no extra `iptables` step.

---

## 11. First check — share the link

Open `http://<PUBLIC_IP>/` → the admin login. Log in as `admin` / your `SEED_ADMIN_PASSWORD`.
See `docs/test-data-guide.html` for the full seeded login list (all demo users share `admin123`).

---

## 12. (Recommended) Free HTTPS with a hostname

```bash
sudo apt -y install certbot python3-certbot-nginx
# nip.io maps an IP to a hostname with no DNS setup: 12.34.56.78 -> 12-34-56-78.nip.io
sudo sed -i 's/server_name _;/server_name 12-34-56-78.nip.io;/' /etc/nginx/sites-available/shifa
sudo systemctl reload nginx
sudo certbot --nginx -d 12-34-56-78.nip.io --redirect --agree-tos -m you@example.com --no-eff-email
```
Share **`https://12-34-56-78.nip.io/`**. Certbot installs auto-renewal. (If you own a domain, point an
**A record** at the public IP and run certbot with `-d yourdomain.com` instead.)

---

## 13. Day-to-day operations

```bash
sudo systemctl status shifa-oms
sudo journalctl -u shifa-oms -f
sudo systemctl restart shifa-oms
sudo systemctl reload nginx

# Manual DB backup (the app also runs a nightly backup job at 02:00)
mysqldump -u shifa -p shifa_dashboard > ~/shifa_backup_$(date +%F).sql
```

---

## 14. Redeploying updates

Same "build on PC, upload, restart" loop as go-live. Rebuild the JAR and/or admin bundle (section 8),
`scp` them up, then:
```bash
sudo cp ~/shifa-oms.jar /opt/shifa/shifa-oms.jar && sudo chown shifa:shifa /opt/shifa/shifa-oms.jar
sudo rm -rf /var/www/shifa/admin/* && sudo cp -r ~/admin-dist/* /var/www/shifa/admin/
sudo chown -R www-data:www-data /var/www/shifa
sudo systemctl restart shifa-oms && sudo systemctl reload nginx
```
**Schema/data changes:** never re-import a dump over live data. Add a **new Flyway migration**
(`V28__*.sql`, `V29__*.sql`, …) — Flyway applies it on the next backend restart. Never edit an applied
migration.

---

## 15. Database migrations & the seed on AWS

Flyway runs on startup, so V1…V27 apply automatically the first time the backend connects to the empty
`shifa_dashboard`. **V27 is the large NOW()-relative test seed** — great for a demo, but if you want a
*clean* production DB with no demo rows, either (a) delete `V22`/`V27` seed migrations before building
the prod JAR, or (b) start from an empty DB and add real data through the admin UI. For a client demo,
keeping the seed is usually what you want.

---

## 16. Optional variant — EC2 + RDS MySQL (managed database)

If you'd rather not self-host MySQL:
- Create **RDS for MySQL**, instance class **`db.t4g.micro`**, **20 GB gp3**, **Single-AZ**,
  **Publicly accessible = No**, in the **same VPC** as the EC2; put it in a security group that allows
  **3306 from the EC2's security group** only.
- In `/etc/shifa/shifa.env` set `DB_HOST=<rds-endpoint>` (and the RDS username/password); skip the local
  MySQL install.
- **Free tier:** RDS gives **750 h of `db.t2/t3/t4g.micro` + 20 GB storage + 20 GB backups free for 12
  months**. After that (or once credits run out) it's ~**$12–14/month** (see table). This adds a second
  always-on meter, so for a pure-demo the single-EC2 (local MySQL) path is cheaper.

---

## 17. Cost breakdown (Mumbai `ap-south-1`, pay-as-you-go, ≈ ₹96/USD)

**Recommended single-EC2 setup (local MySQL):**

| Service | What / size | Free-tier window | Cost *after* free (approx.) |
|---|---|---|---|
| EC2 `t4g.small` | 2 vCPU / 2 GB, 24×7 (~730 h) | **Free 750 h/mo until 31 Dec 2026** | ~**$8.2/mo** (₹787) |
| EBS gp3 root | 30 GB | 30 GB free for 12 months | ~**$2.7/mo** (₹259) |
| Public IPv4 | 1 address, in-use 24×7 | 750 h/mo free for first 12 months | ~**$3.6/mo** (₹346) |
| Data transfer out | demo traffic (< 100 GB) | 100 GB/mo free | ~**$0** |
| MySQL | on the same EC2 | — | **$0** (no RDS) |
| CloudWatch / Budgets | basic metrics + 1 budget | always free | **$0** |
| **Total** | | **≈ $0 during free windows / covered by credits** | **≈ $14.5/mo (~₹1,392) fully paid** |

**Recommended production sizing for ~50 users:** `t4g.medium` (4 GB), run **19 h/day** (down 00:30–05:30
IST, ~578 h/mo) → ~**$13/mo (~₹1,243)** after credits (24×7 ≈ ₹1,570); fully-free alternative is
`t4g.small` **+ RDS `db.t4g.micro`**. See section 21 (nightly power-down) and `docs/cost-estimate.html`.

**Notes**
- **Right now (2026) this is effectively $0:** EC2 compute is free via the Graviton promo through **31
  Dec 2026**, public IPv4 + EBS are free for your first 12 months, and your **$100–$200 credits** absorb
  anything else. Realistic out-of-pocket for the demo period: **$0**.
- The only line item that outlives the promos first is the **public IPv4 (~$3.6/mo)** after month 12.
- **After all free windows expire** and credits are gone, the steady-state bill for this footprint is
  **~$14–15/month**. Adding RDS instead of local MySQL adds **~$12–14/month**.
- Prices are approximate and region/time-dependent — always confirm in the **AWS Pricing Calculator**
  (https://calculator.aws) and your **Billing console**.

---

## 18. Stay free — set a Budget alarm (do this first!)

1. Console → **Billing and Cost Management → Budgets → Create budget**.
2. **Zero-spend budget** (alerts the moment any real charge appears) or a small **$1–$5 monthly cost
   budget**; add your email for alerts.
3. Also enable **Free Tier usage alerts**: Billing → **Preferences → Free Tier usage alerts**.
4. Watch **Billing → Cost Explorer / Bills** occasionally.
5. If you chose the **Free Plan** at signup, you literally cannot be charged — the account pauses when
   credits/6-months end. On the **Paid Plan**, the budget alarm + free-tier alerts are your safety net.

---

## 19. Free Tier only? — direct answer

- **Yes, you can run this demo for $0** for well over a year: `t4g.small` compute is free until **31 Dec
  2026**, EBS + public IPv4 are free for 12 months, egress is 100 GB/mo free, and MySQL runs on the same
  box (no RDS). Your **$100–$200 credits** cover any small gaps.
- **Truly "cannot be charged"?** Pick the **Free Plan** at signup (credit-capped, up to 6 months). For a
  longer-lived demo, use the **Paid Plan** + a **zero-spend Budget alarm** so you get the 12-month
  allowances and Graviton promo while staying practically free.
- **What eventually costs money:** after the promos/credits end, expect **~$14–15/month** for this
  single-instance setup — still cheap, and you can downsize (`t4g.micro` + tighter heap) or move to
  Oracle Cloud Always Free (`DEPLOYMENT.md`) if you want perpetual $0.

---

## 20. AWS vs Oracle Cloud (quick comparison)

| | **AWS (this guide)** | **Oracle Cloud (`DEPLOYMENT.md`)** |
|---|---|---|
| Perpetual free? | No — promo/credits (free ~1 yr, then ~$14/mo) | **Yes — Always Free** (A1: 4 OCPU/24 GB) |
| Free RAM | 2 GB (`t4g.small`) | up to 24 GB (Ampere A1) |
| Build on the box? | No (2 GB — build on PC) | Yes (A1 is roomy) |
| Capacity issues | Rare | A1 often "out of capacity" in Mumbai |
| Best for | You already have AWS credits / want AWS | Long-term $0 hosting |

If long-term **$0** matters most, Oracle Cloud Always Free is the cheaper home. If you want to use your
**AWS credits** and are fine with ~$14/mo eventually, this AWS guide gets you live quickly.

---

## 21. Nightly power-down (00:30–05:30 IST) & scheduled jobs — optional cost saver

Running a *paid* instance (e.g. `t4g.medium`) only ~19 h/day trims ~21% off compute. Notes:

- **No saving on the free `t4g.small`** (already free 24×7). Only worth it on a paid/bigger instance.
- **Use an Elastic IP** — the auto-assigned public IP changes on every stop/start; an Elastic IP keeps
  the URL stable (EC2 → Elastic IPs → Allocate → Associate).
- **Automate stop/start** with **EventBridge Scheduler + a small Lambda** (or the AWS *Instance
  Scheduler* solution): one schedule stops the instance at 00:30 IST, another starts it at 05:30 IST.
- **⚠️ Move the app's 02:00 jobs out of the downtime window.** The backend runs the **DB backup** and the
  **insights computation** at 02:00 by default — that's *inside* the power-down, so they'd be skipped.
  In `/etc/shifa/shifa.env` set them outside the window, e.g.:
  ```bash
  BACKUP_CRON=0 45 23 * * *          # 23:45, before shutdown
  # app.insights.nightly.cron -> e.g. 0 50 23 * * *  (23:50)
  REPORT_DIGEST_CRON=0 0 6 * * *     # 06:00, after startup
  ```
  Then `sudo systemctl restart shifa-oms`. MySQL data on the EBS disk persists across stop/start — no data loss.

### AWS Lambda for reports/insights (optional, later)
Lambda + EventBridge can run scheduled work **independently of the EC2**, so it keeps working during the
nightly power-down — good for daily/weekly/monthly report emails, the nightly insights, and DB backups.
**Dependency:** those night jobs need the DB while the EC2 is off, so the database must be **always-on →
use RDS** (the `t4g.small` + RDS option), not MySQL on the stopped EC2. Lambda's always-free tier (1M
requests + 400k GB-s/month) makes a few runs/day effectively **$0**. For now the simplest path is to keep
reports/insights inside the app and just reschedule the crons as above; adopt RDS + Lambda if/when you
want true night-time jobs or to offload heavy report generation. Details in `docs/cost-estimate.html` §6.

---

## 22. Full production estimate — EC2 + RDS + CloudWatch + CloudTrail + Elastic IP + S3

This is the **managed** architecture (not the single-box demo): app on EC2, database on **RDS MySQL**,
**CloudWatch** monitoring, **CloudTrail** auditing, a stable **Elastic IP**, and an **S3** bucket that
holds the RDS backup exports / `mysqldump` archives. Region **Mumbai `ap-south-1`**, On-Demand, Linux,
24×7 (~730 h/mo), **≈ ₹88/USD**. All figures are approximate — reproduce them in the AWS Pricing
Calculator (https://calculator.aws) using the exact config in the last column, then confirm against the
live Billing console.

### 22.1 Pricing Calculator recipe (enter these in calculator.aws)

| # | Service | Configure it as | Rate used (`ap-south-1`) | Monthly (paid) |
|---|---|---|---|---|
| 1 | **EC2** compute | `t4g.small`, Linux, On-Demand, 100% utilisation (730 h) | $0.0112/hr | **$8.18** |
| 2 | **EBS** (EC2 root) | 30 GB gp3, 3000 IOPS + 125 MB/s (baseline, free) | $0.0912/GB-mo | **$2.74** |
| 3 | **Elastic IP / public IPv4** | 1 address, in-use on running instance, 730 h | $0.005/hr | **$3.65** |
| 4 | **RDS MySQL** compute | `db.t4g.micro`, Single-AZ, On-Demand, 730 h | $0.018/hr | **$13.14** |
| 5 | **RDS storage** | 20 GB gp3 (3000 IOPS/125 MB/s baseline included) | $0.115/GB-mo | **$2.30** |
| 6 | **RDS backup storage** | Automated backups ≤ 100% of DB size (20 GB) → free; extra $0.095/GB-mo | first 20 GB free | **$0.00** |
| 7 | **S3** (backup archive) | S3 Standard, ~25 GB stored + light PUT/GET (nightly dumps, 30-day retention) | $0.025/GB-mo | **$0.70** |
| 8 | **CloudWatch** | ~10 alarms (first 10 free) + a few custom metrics + ~2 GB logs (5 GB free) | $0.30/metric, $0.10/alarm | **~$2.00** |
| 9 | **CloudTrail** (audit) | 1 trail, **management events only** (first copy free); logs delivered to the S3 bucket above | management events free | **$0.00** |
| 10 | **Data transfer out** | demo/admin traffic, ~10 GB/mo (100 GB/mo free for 12 mo) | $0.109/GB after free | **~$1.09** |
| | **TOTAL (steady-state, all free tiers expired)** | | | **≈ $33.8/mo (~₹2,975)** |

**Annualised:** ≈ **$405/yr (~₹35,700)** once every free window and the sign-up credits are gone.

### 22.2 What you actually pay in year 1 (free tiers + credits applied)

| Line item | Year-1 reality |
|---|---|
| EC2 `t4g.small` | **Free** (Graviton promo, 750 h/mo through 31 Dec 2026) |
| EBS 30 GB gp3 | **Free** (30 GB free, first 12 months) |
| Public IPv4 / Elastic IP | **Free** (750 h/mo, first 12 months) |
| RDS `db.t4g.micro` + 20 GB storage + 20 GB backups | **Free** (750 h/mo, first 12 months) |
| S3 (5 GB Standard) | **Free** within 5 GB free tier; ~$0.50/mo above it |
| CloudWatch (10 metrics/alarms, 5 GB logs) | **Free** within always-free limits |
| CloudTrail (management events) | **Free** (first copy always free) |
| Data transfer out (< 100 GB) | **Free** |
| **Effective out-of-pocket, year 1** | **≈ $0** — small overages covered by your **$100–$200 credits** |

So during the first 12 months this managed stack is **effectively $0**; the ~**$34/mo** figure is the
steady-state bill *after* all promos and credits are exhausted.

### 22.3 Cost levers
- **Biggest line is RDS compute (~$13/mo).** Self-hosting MySQL on the EC2 (section 0 single-box) removes
  items 4–6 entirely and drops the total to **~$14–15/mo**, at the cost of RAM headroom and manual DB ops.
- **CloudTrail stays free** as long as you keep to a single trail of **management events**. Turning on
  **data events** (S3 object-level, Lambda) adds **$0.10 per 100,000 events** — leave them off unless an
  audit requirement needs them.
- **S3 + RDS automated backups** are near-free here: RDS gives free backup storage up to your DB size
  (20 GB), and the S3 archive of `mysqldump` files is only ~$0.025/GB-mo. Add an S3 **lifecycle rule**
  (e.g. expire after 30–90 days, or transition to Glacier) to keep it that way.
- **CloudWatch:** the first 10 alarms and 5 GB of logs are free. Cost only grows if you add many custom
  metrics or high-volume log ingestion.
- **Nightly power-down** (section 21) saves on EC2 compute but **not** on RDS (keep the DB always-on if
  Lambda night jobs need it).

> All rates re-summarised from AWS and vendor pricing pages for compliance; region- and time-dependent —
> **confirm the live numbers in the AWS Pricing Calculator and your Billing console before quoting them.**

---

## 23. File storage — S3 bucket for labels & payment screenshots

The app stores two kinds of binary files: **internal label PDFs** and **payment
screenshots**. It never writes them into the app code — it hands them to a
`StorageService` and persists only the returned key. There are three backends,
chosen by `app.storage.provider` (env `STORAGE_PROVIDER`):

| provider | Where files go | Use |
|---|---|---|
| `LOCAL` (default) | server filesystem under `storage/` | local dev |
| `DB` | `stored_files` table | quick prod fallback (no bucket needed) |
| `S3` **(recommended for prod)** | an S3 bucket, `labels/` + `payments/` folders | durable, off the box, cheap |

> **Why S3 (not the filesystem):** the systemd service runs as user `shifa` with
> `WorkingDirectory=/opt/shifa`, which root owns and `ProtectSystem=full` guards —
> so filesystem writes fail (this is what broke screenshot upload *and* order
> approval, since approval writes a label PDF). S3 removes the filesystem
> dependency entirely and keeps files out of the database.

### 23.1 Create the bucket

1. Console → **S3 → Create bucket**. Name e.g. `shifa-oms-files` (globally unique),
   Region **`ap-south-1`**. **Block all public access = ON** (files are served
   through the authenticated API, never publicly).
2. Objects are namespaced automatically by the app — you don't pre-create folders.
   After the first upload you'll see `labels/internal/…` and `payments/…` prefixes.
3. (Optional) Add a **lifecycle rule** to transition old objects to a cheaper
   class or expire them, if you don't need them forever.

### 23.2 Let the EC2 reach the bucket (instance role — no keys in config)

Create an IAM role for the EC2 with a least-privilege policy scoped to this bucket,
then attach it (EC2 → the instance → Actions → Security → **Modify IAM role**):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::shifa-oms-files/*"
    }
  ]
}
```
The app resolves credentials via the default provider chain, so with the role
attached **no access keys go into any file**. (If you truly can't use a role, set
`STORAGE_S3_ACCESS_KEY`/`STORAGE_S3_SECRET_KEY` in `shifa.env` instead.)

### 23.3 Point the app at it

In `/etc/shifa/shifa.env`:
```bash
STORAGE_PROVIDER=S3
STORAGE_S3_BUCKET=shifa-oms-files
STORAGE_S3_REGION=ap-south-1
# optional: nest everything under a root prefix if the bucket is shared
# STORAGE_S3_PREFIX=prod
```
Then rebuild/upload the JAR (it now bundles the AWS SDK) and restart:
```bash
sudo systemctl restart shifa-oms
sudo journalctl -u shifa-oms -f    # confirm a clean start
```
Approve an order and upload a payment screenshot; in S3 you'll see the new objects
under `labels/internal/…` and `payments/…`. The approval-queue drawer fetches the
screenshot through the authenticated API (`GET /api/orders/{id}/payment-screenshot`),
so the bucket stays private.

> **Fail-fast:** if `STORAGE_PROVIDER=S3` but `STORAGE_S3_BUCKET` is unset, the app
> refuses to start with a clear message — set the bucket, or switch to
> `STORAGE_PROVIDER=DB` for a bucket-free fallback.

---

## Quick reference — what to give the client
- **Admin link:** `https://<host>/` — user `admin`, password = your `SEED_ADMIN_PASSWORD`
- Courier & WhatsApp are **mocked**, email is **MOCK** — perfect for a demo; no real messages/money.
- Seeded demo logins & a per-role tour: **`docs/test-data-guide.html`**.
