# Shifa Herbal Remedies — Deploy on Oracle Cloud (Always Free) for a Client Demo

A complete, copy-paste, step-by-step guide to host the whole app (store + admin + API + database)
on **one Oracle Cloud Infrastructure (OCI) Always Free** VM and share a working link with your client.

> Everything here stays inside the **Always Free** tier — no charges — as long as you pick the
> resources called out below. Read the "Staying in Free Tier" section before you finish.

**What you'll end up with**
- Store (customers): `http://<your-host>/`
- Admin panel (staff): `http://<your-host>/admin/` (login `admin` / your chosen password)
- One VM running: **Nginx** (web + reverse proxy) + **Spring Boot** (API) + **MySQL 8** (database)

Supporting files used by this guide live in the `deploy/` folder of this repo:
`deploy/nginx-shifa.conf`, `deploy/shifa-oms.service`, `deploy/shifa.env.example`, `deploy/build-and-deploy.sh`.

---

## 0. Architecture (single Always-Free VM)

```
                         ┌───────────────── OCI Always-Free VM (Ubuntu, Ampere A1) ─────────────────┐
   Client browser  ──▶   │  Nginx :80/:443                                                          │
   http://host/          │    /          →  /var/www/shifa/store   (Angular storefront PWA)         │
   http://host/admin/    │    /admin/    →  /var/www/shifa/admin   (Angular admin)                  │
                         │    /api/      →  reverse proxy → 127.0.0.1:8080  (Spring Boot JAR)       │
                         │                                   │                                       │
                         │                                   └──▶  MySQL 8 (localhost:3306)         │
                         └──────────────────────────────────────────────────────────────────────────┘
```

Because the store, admin, and API are all served from the **same origin**, there are **no CORS
issues** and you only expose ports 80/443 publicly.

---

## 1. Create an Oracle Cloud account (Always Free)

1. Go to **https://www.oracle.com/cloud/free/** and click **Start for free**.
2. Sign up: email, country (**India**), mobile verification, and a **credit/debit card** for identity
   verification (you are **not** charged for Always Free resources; the card is only for verification;
   a small temporary hold may appear and is reversed).
3. Choose a **Home Region** close to your users (e.g. **India South (Hyderabad)** or **India West (Mumbai)**).
   ⚠️ The home region **cannot be changed later** — pick the one nearest your client.
4. Finish sign-up and log in to the **OCI Console** (https://cloud.oracle.com).

> Tip: In the console, a green **"Always Free Eligible"** label marks resources that are free.

---

## 2. Create the VM (Compute Instance)

> **Note on the console UI:** Oracle now uses a **stepped "Create instance" wizard** with a
> "Tasks Completed X of 4" progress bar and a **Previous / Next** button at the bottom (instead of
> the older single scrolling page). The settings below are the same — they're just spread across a
> few wizard steps now. Use **Next** to move forward and **Previous** to go back and change anything.

1. Console → hamburger menu → **Compute → Instances → Create instance**.
2. **Name**: `shifa-demo`.
3. **Image and shape** (first wizard step) → click **Edit** / **Change image** and **Change shape**:
   - **Image**: Canonical **Ubuntu 22.04** (or 24.04).
   - **Shape**: **Change shape → Ampere (Arm)**. Under Ampere there is **only one shape**,
     **VM.Standard.A1.Flex** (marked *Always Free-eligible*) — that's the correct one; select its radio button.
     Then **expand its row (click the ▸ arrow)** to reveal the **Number of OCPUs** and **Amount of memory (GB)**
     fields and set **OCPUs = 2** and **Memory = 12 GB** (the default shows 1 OCPU / 6 GB). The free Ampere
     allowance is up to **4 OCPU / 24 GB total**, so 2/12 is comfortably free and enough to build + run everything.
     Click **Select shape**.
   - If you see **"Out of host capacity"** for A1: try a different **Availability Domain**, try again later, or as a fallback pick the always-free **VM.Standard.E2.1.Micro** (x86, 1 OCPU/1 GB — too small to build Angular on the VM, so use the "build locally & upload" path in Step 8B).
   - Click **Next** to reach the **Networking** step.
4. **Networking** step — this is where most people get stuck on the new wizard, so follow closely:
   - **Primary network**: select **"Create new virtual cloud network"** (default). A VCN name is auto-filled — leave it.
   - **Subnet**: select **"Create new public subnet"** (default). Leave the CIDR block `10.0.0.0/24`.
   - **Private IPv4 address**: leave **"Automatically assign private IPv4 address"**.
   - **Public IPv4 address assignment**: ⚠️ **turn this toggle ON** — it is **OFF by default** on the new
     wizard. Without it your VM has **no public IP** and you can't reach the site from the internet.
     - **If the toggle is greyed out / disabled** with the warning *"You must select a public subnet to
       assign a public IPv4 address"*: the wizard set your subnet to **private**. Scroll back up to the
       **Subnet** section and make sure **"Create new public subnet"** is selected — then the toggle enables.
     - If it still won't enable, don't worry: just finish creating the VM and **assign a public IP
       afterward** (see the recovery note at the end of this step), or use the **VCN Wizard** path below.

     > **Most reliable network setup (avoids the disabled-toggle problem entirely):** *before* creating the
     > instance, go to **Networking → Virtual Cloud Networks → Start VCN Wizard → "Create VCN with Internet
     > Connectivity"**, name it `shifa-vcn`, and create it. This builds a proper **public subnet** with an
     > Internet Gateway + route rule. Then in the instance wizard's Networking step pick **"Select existing
     > virtual cloud network"** → `shifa-vcn` → its public subnet, and the public-IP toggle will be enabled.
   - Leave **IPv6** OFF, **DNS record** as **"Assign a private DNS record"**, and **Launch options** on
     **"Let Oracle Cloud Infrastructure choose the best networking type."**
   - Click **Next**.
5. **SSH keys** step: choose **Generate a key pair for me** and **download both** the private and public keys
   (keep the private key safe — you'll SSH with it). Or paste your own public key. Click **Next**.
6. **Boot volume** step: leave the defaults (~47 GB is fine and free). Then click **Create**.
7. Wait ~1 minute until state = **Running**. Note the **Public IP address** (e.g. `140.238.x.x`) shown
   on the instance details page — that's the address you'll SSH to and share with the client.

> If you forgot to enable the public IP and the instance is already created, you can add one later:
> instance details → **Attached VNICs** → the VNIC → **IPv4 Addresses** → edit the primary private IP →
> **Assign public IPv4 address** → **Ephemeral**.

---

## 3. Open the firewall (ingress ports 80 & 443)

OCI blocks inbound traffic by default. Open HTTP/HTTPS in the VCN security list:

1. Console → **Networking → Virtual Cloud Networks →** your VCN → **Security Lists →** the **Default Security List**.
2. **Add Ingress Rules** (Stateless: No), add two rules:
   - Source `0.0.0.0/0`, IP Protocol **TCP**, Destination Port **80**.
   - Source `0.0.0.0/0`, IP Protocol **TCP**, Destination Port **443**.
   (Port 22 for SSH is already open by default.)
3. Save.

> There's a second firewall **inside** Ubuntu — we open it in Step 12.

---

## 4. Connect to the VM (SSH)

From your Windows machine (PowerShell), using the private key you downloaded:

```powershell
# Fix key permissions once (Windows): right-click the .key → Properties → Security,
# or just use it directly. Then:
ssh -i "C:\path\to\your-private-key.key" ubuntu@<PUBLIC_IP>
```
- Default user for Ubuntu images is **`ubuntu`**.
- Accept the fingerprint prompt the first time.

Everything from here runs **on the VM** (the `ubuntu@...` shell).

---

## 5. Install prerequisites

```bash
sudo apt update && sudo apt -y upgrade

# Java 21 (for the Spring Boot JAR) + build tools + Maven
sudo apt -y install openjdk-21-jdk maven git unzip

# MySQL 8 server + client (mysqldump for backups)
sudo apt -y install mysql-server mysql-client

# Nginx web server / reverse proxy
sudo apt -y install nginx

# Node.js 20 LTS + npm (to build the Angular apps on the VM)
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt -y install nodejs

# sanity check
java -version && mvn -v && node -v && nginx -v && mysql --version
```

---

## 6. Set up MySQL

```bash
# Secure the install (set a root password, answer Y to the hardening prompts)
sudo mysql_secure_installation

# Create the database + a dedicated app user
sudo mysql <<'SQL'
CREATE DATABASE IF NOT EXISTS shifa_dashboard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'shifa'@'localhost' IDENTIFIED BY 'CHANGE_ME_STRONG_DB_PASSWORD';
GRANT ALL PRIVILEGES ON shifa_dashboard.* TO 'shifa'@'localhost';
FLUSH PRIVILEGES;
SQL
```
Use the **same** password you'll put in the env file (Step 8). The app runs Flyway migrations
(V1–V8) automatically on first start — you don't create tables manually.

---

## 7. Get the code onto the VM

**Option A — Git (recommended if the repo is in GitHub/GitLab):**
```bash
cd ~
git clone <YOUR_REPO_URL> shifa
cd shifa
```

**Option B — Upload from your PC** (if no remote repo). From Windows PowerShell:
```powershell
# Zip the project locally (exclude node_modules/target to keep it small), then:
scp -i "C:\path\to\key.key" shifa.zip ubuntu@<PUBLIC_IP>:/home/ubuntu/
# On the VM:
unzip shifa.zip -d ~/shifa && cd ~/shifa
```

The repo root should contain `backend/`, `frontend/`, and `deploy/`.

---

## 8. Configure the backend environment

```bash
sudo mkdir -p /etc/shifa
sudo cp ~/shifa/deploy/shifa.env.example /etc/shifa/shifa.env
sudo nano /etc/shifa/shifa.env
```
Fill in real values (see comments in the file):
- `DB_PASSWORD` = the MySQL password from Step 6.
- `JWT_SECRET` = generate one: `openssl rand -base64 48`
- `SEED_ADMIN_PASSWORD`, `SEED_PACKING_PASSWORD` = your chosen demo passwords.
- `PAYMENT_SANDBOX_SECRET` = any random string.
- Keep `PAYMENT_MODE=SANDBOX`, `COURIER_MODE=MOCK`, `WHATSAPP_MODE=MOCK` for the demo.

Lock it down:
```bash
sudo chmod 600 /etc/shifa/shifa.env
```

> The app's production API base URL is already set to same-origin (`/api`) in the repo, so the
> built frontends automatically call the API through Nginx — nothing to change.

---

## 9. Create the app user and build + deploy

```bash
# Dedicated non-login user to run the backend
sudo useradd -r -s /usr/sbin/nologin shifa || true

# Build everything and publish it (backend JAR + both Angular apps)
cd ~/shifa
sudo bash deploy/build-and-deploy.sh
```
`build-and-deploy.sh` (in `deploy/`) will:
1. `mvn package` → copy the JAR to `/opt/shifa/shifa-oms.jar`
2. `ng build` the **storefront** and the **admin** (with `--base-href /admin/`)
3. copy the static bundles to `/var/www/shifa/store` and `/var/www/shifa/admin`
4. restart the backend service and reload Nginx.

> **First build takes a few minutes** (Maven + npm download dependencies). The A1 VM handles it fine.
>
> **Option 8B (tiny/x86 VM):** if you're on the micro shape and Angular build runs out of memory,
> build on your Windows PC instead — `cd frontend && npm ci && npx ng build storefront --configuration production && npx ng build admin --configuration production --base-href /admin/` and `cd backend && mvn -DskipTests package` — then `scp` the `dist/storefront/browser`, `dist/admin/browser`, and `target/shifa-oms-*.jar` up, and copy them into `/var/www/shifa/...` and `/opt/shifa/shifa-oms.jar` manually.

---

## 10. Install the backend as a service (systemd)

```bash
sudo cp ~/shifa/deploy/shifa-oms.service /etc/systemd/system/shifa-oms.service
sudo systemctl daemon-reload
sudo systemctl enable --now shifa-oms

# watch it start (looks for "Started Application")
sudo journalctl -u shifa-oms -f
```
(Ctrl-C to stop watching.) It will connect to MySQL, run migrations, and listen on `127.0.0.1:8080`.

---

## 11. Configure Nginx

```bash
sudo cp ~/shifa/deploy/nginx-shifa.conf /etc/nginx/sites-available/shifa
# set your host (public IP or a name); optional but nicer for the client:
sudo nano /etc/nginx/sites-available/shifa   # replace: server_name _;  → your IP or hostname

sudo ln -sf /etc/nginx/sites-available/shifa /etc/nginx/sites-enabled/shifa
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

---

## 12. Open Ubuntu's own firewall

OCI Ubuntu images ship with iptables rules that block 80/443 even after Step 3. Open them:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80  -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save        # persist across reboots
```
(If `netfilter-persistent` is missing: `sudo apt -y install iptables-persistent` then save.)

---

## 13. First check — share the HTTP link

Open in a browser:
- **Store:** `http://<PUBLIC_IP>/`
- **Admin:** `http://<PUBLIC_IP>/admin/` → log in as `admin` / your `SEED_ADMIN_PASSWORD`.

If both load, you can already share `http://<PUBLIC_IP>/` with the client. 🎉

For a nicer URL and the padlock, do Step 14.

---

## 14. (Recommended) Free HTTPS with a hostname

Browsers (and PWAs) prefer HTTPS. Easiest free path without buying a domain — use a **nip.io**
hostname that maps to your IP, then get a free Let's Encrypt certificate:

1. Your hostname is simply: `PUBLIC-IP-with-dashes.nip.io`
   e.g. IP `140.238.1.2` → `140-238-1-2.nip.io` (resolves automatically, no DNS setup).
2. Put it in Nginx `server_name`:
   ```bash
   sudo sed -i 's/server_name _;/server_name 140-238-1-2.nip.io;/' /etc/nginx/sites-available/shifa
   sudo systemctl reload nginx
   ```
3. Install certbot and issue the cert (it edits Nginx for you and adds the 443 block + redirect):
   ```bash
   sudo apt -y install certbot python3-certbot-nginx
   sudo certbot --nginx -d 140-238-1-2.nip.io --redirect --agree-tos -m you@example.com --no-eff-email
   ```
4. Done — share **`https://140-238-1-2.nip.io/`** (store) and `.../admin/` (admin).
   Auto-renewal is installed by certbot (`systemctl status certbot.timer`).

> If you own a real domain, point an **A record** to the public IP instead of using nip.io, and run
> certbot with `-d yourdomain.com` — much nicer for a client demo.

---

## 15. Seed some demo data (optional)

On first run the catalog seeder adds sample herbal products automatically (local/prod seeders).
Log into the admin → **Products** to add/adjust, **Settings** to set company/GST, then place a test
order from the store to demo the full flow (checkout → approval → packing → courier → invoice).

---

## 16. Day-to-day operations

```bash
# Status / logs
sudo systemctl status shifa-oms
sudo journalctl -u shifa-oms -f
sudo tail -f /var/log/nginx/error.log

# Restart backend / reload web
sudo systemctl restart shifa-oms
sudo systemctl reload nginx

# Redeploy after pulling new code
cd ~/shifa && git pull && sudo bash deploy/build-and-deploy.sh

# Manual DB backup (the app also runs a nightly backup job at 02:00)
mysqldump -u shifa -p shifa_dashboard > ~/shifa_backup_$(date +%F).sql
```

---

## 17. Staying in the Always Free tier (avoid charges)

- **Compute**: only use **VM.Standard.A1.Flex** (≤4 OCPU / ≤24 GB total across all your instances)
  or **VM.Standard.E2.1.Micro**. Do **not** create paid shapes. One `shifa-demo` VM at 2 OCPU/12 GB
  is well within limits.
- **Boot volume**: default ~47 GB is fine (Always Free gives up to 200 GB block storage total).
- **Egress**: 10 TB/month outbound is free — a demo won't get close.
- **No Load Balancer / no extra paid services** — this guide uses only the VM + its public IP.
- **Set a Budget alert**: Console → **Billing → Budgets → Create budget** (e.g. ₹1) so you're emailed
  if anything ever accrues cost. Also check **Billing → Cost Analysis** occasionally.
- **Idle reclaim**: Oracle may reclaim *idle* Always-Free **x86 micro** instances; **A1 instances are not**
  subject to that. A demo VM you use is fine.

---

## 18. Troubleshooting

| Symptom | Fix |
|---|---|
| Browser can't reach the site | Check **both** firewalls: OCI security list (Step 3) **and** Ubuntu iptables (Step 12). |
| 502 Bad Gateway | Backend not up: `sudo systemctl status shifa-oms` + `journalctl -u shifa-oms -e`. |
| Backend won't start / DB error | Verify `/etc/shifa/shifa.env` DB creds match the MySQL user; `sudo systemctl restart shifa-oms`. |
| Admin loads blank at /admin | Rebuild admin with `--base-href /admin/` (the deploy script already does this). |
| API calls 404/blocked | Confirm Nginx `/api/` proxy block is present and `nginx -t` passes. |
| Angular build killed (OOM) | You're on the micro VM — use the **build-locally-and-upload** path (Step 9, Option 8B). |
| A1 "out of host capacity" | See the dedicated section below — this is common in Mumbai. |
| SSE/live dashboard not updating | Ensure the `location = /api/admin/events` block (buffering off) is in the Nginx config. |

---

## 19. "Out of capacity for shape VM.Standard.A1.Flex" (very common in Mumbai)

Oracle's free **Ampere A1** capacity is heavily used, so you may hit
*"Out of capacity for shape VM.Standard.A1.Flex in availability domain AD-1"* on **Create**.
Note that **`ap-mumbai-1` has only one Availability Domain**, so "try another AD" doesn't apply there.
Work through these in order:

1. **Don't pin a fault domain.** In the **Placement** step, leave it on *"Let Oracle Cloud choose the
   best fault domain"* — pinning one can trigger this error.
2. **Ask for a smaller shape.** Set the A1 shape to **1 OCPU / 6 GB** instead of 2/12 and retry — a
   smaller request is far more likely to be scheduled when capacity is tight. 1 OCPU/6 GB still builds
   and runs the whole app (just a little slower).
3. **Retry at off-peak times.** A1 capacity frees up intermittently; **early morning IST (~4–8 AM)** is
   usually best. Click **Create** repeatedly over a few minutes, or come back later. Most people
   eventually get an A1 this way.
4. **Fall back to the free x86 micro (to get the demo live now).** Shape → **VM.Standard.E2.1.Micro**
   (Always Free, 1 OCPU / 1 GB) almost always has capacity. Because RAM is small:
   - Add a **2 GB swap file** on the VM so services don't OOM:
     ```bash
     sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
     sudo mkswap /swapfile && sudo swapon /swapfile
     echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
     ```
   - **Build on your Windows PC and upload** the artifacts (see Step 9, Option 8B) — don't build Angular
     on the micro VM (it will run out of memory).
   You can always create an A1 later and move to it once capacity is available.

> Prefer A1 (steps 1–3) for the demo box; use the micro (step 4) only if A1 stays unavailable and you're
> in a hurry.

---

## 20. Redeploying updates (day-to-day dev workflow)

The VM runs a *snapshot* of what you built and uploaded — local changes don't appear online
until you rebuild, upload, and restart. Helper scripts reduce this to three commands.

**My deployment details (fill-in reference):**

| Thing | Value |
|---|---|
| VM public IP | `130.210.52.12` |
| SSH user | `ubuntu` |
| SSH private key | `C:\E Drive\Oracle Cloud - parulgangwal1904\ssh-key-2026-07-05.key` |
| Project root (PC) | `c:\E Drive\Shifa-Software` |
| Build+package script (PC) | `c:\E Drive\Shifa-Software\deploy\package-local.ps1` |
| Deploy zip produced (PC) | `c:\E Drive\Shifa-Software\shifa-deploy.zip` |
| Apply script (run on VM) | `c:\E Drive\Shifa-Software\deploy\apply-on-vm.sh` |
| Store URL | http://130.210.52.12/ |
| Admin URL | http://130.210.52.12/admin/ |

> If you ever stop/start the VM, an **ephemeral** public IP can change. If the site stops responding
> after a restart, check the new IP in the OCI console (Compute → Instances → `shifa`) and update it
> in the commands below.

### Code changes (backend and/or frontend)

Run these three commands from a **PowerShell window on your PC** (not the SSH session):

**1. Build + package** (rebuilds the JAR + both Angular apps into `shifa-deploy.zip`):
```powershell
powershell -ExecutionPolicy Bypass -File "c:\E Drive\Shifa-Software\deploy\package-local.ps1"
```

**2. Upload the zip to the VM:**
```powershell
scp -i "C:\E Drive\Oracle Cloud - parulgangwal1904\ssh-key-2026-07-05.key" "c:\E Drive\Shifa-Software\shifa-deploy.zip" ubuntu@130.210.52.12:/home/ubuntu/
```

**3. Apply it on the VM** (unzips, swaps in new JAR + web files, restarts backend, reloads Nginx).
⚠️ **PowerShell does NOT support the `<` stdin-redirection operator**, so run the apply script
*on the VM* from the uploaded zip (this works in both PowerShell and cmd):
```powershell
ssh -i "C:\E Drive\Oracle Cloud - parulgangwal1904\ssh-key-2026-07-05.key" ubuntu@130.210.52.12 "cd ~ && unzip -o shifa-deploy.zip -d shifa-deploy && sed -i 's/\r$//' shifa-deploy/config/apply-on-vm.sh && bash shifa-deploy/config/apply-on-vm.sh"
```
(The `sed` strips any Windows CR line-endings so bash runs the script cleanly.)

Then refresh http://130.210.52.12/ — your changes are live. `apply-on-vm.sh` **does not touch the
database or `/etc/shifa/shifa.env`**, so live data and secrets are preserved.

> **Alternative — SSH in first, then run it interactively:**
> ```bash
> ssh -i "C:\E Drive\Oracle Cloud - parulgangwal1904\ssh-key-2026-07-05.key" ubuntu@130.210.52.12
> # then on the VM:
> cd ~ && unzip -o shifa-deploy.zip -d shifa-deploy && bash shifa-deploy/config/apply-on-vm.sh
> ```
>
> **Note:** the older `ssh ... "bash -s" < deploy\apply-on-vm.sh` form only works in **cmd**, not
> PowerShell. If you're in cmd you can still use it.

### Verify a redeploy worked
On the VM (or via the one-shot ssh above), the apply script prints the backend status. To double-check:
```bash
sudo systemctl status shifa-oms --no-pager
sudo journalctl -u shifa-oms -n 30 --no-pager
```
Look for `Started Application` / `Tomcat started on port 8080`.

### Database changes — never re-import the dump after go-live
Re-importing your local dump would **erase real data** created on the live site (orders, status
changes, etc.). Instead:
- **Schema changes:** add a NEW Flyway migration file at
  `c:\E Drive\Shifa-Software\backend\src\main\resources\db\migration\V9__your_change.sql`
  (then `V10__...`, `V11__...`). **Never edit an existing migration.** On the next redeploy + backend
  restart, Flyway applies new migrations to the live DB automatically — no manual SQL on the server.
- **Reference/seed data** (e.g. new products): add via the admin UI on the live site, or put the
  `INSERT`s in a Flyway migration so it's version-controlled.
- Local MySQL (dev) and the VM MySQL (prod) are intentionally separate databases; Flyway keeps their
  *schema* in sync, while their *data* stays independent.

### First-time setup on a fresh PC (if the scripts are new to you)
The two helper scripts already live in `c:\E Drive\Shifa-Software\deploy\`
(`package-local.ps1` and `apply-on-vm.sh`) — no setup needed. Requirements on the PC:
Java 21 + Maven (for the JAR), Node.js + npm (for Angular), and the OpenSSH client (`scp`/`ssh`,
built into Windows 10/11). That's it.

---

## Quick reference — what to give the client

- **Store link:** `https://<host>/`
- (Internal) **Admin link:** `https://<host>/admin/` — user `admin`, password = your `SEED_ADMIN_PASSWORD`
- Payments are in **sandbox** (test) mode; courier & WhatsApp are **mocked** — perfect for a demo,
  no real money or messages are sent. Switch them to live providers later (see `ROADMAP.md`).
