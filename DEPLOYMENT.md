# Shifa OMS — Final Deployment Guide (AWS EC2)

**This is the single, canonical deployment guide. Follow this every time — ignore any other
deployment docs.** We host the whole dashboard-only app (Admin UI + API + MySQL) on **one AWS EC2
instance**. The public storefront was retired (the store is on Shopify), so we serve **only the Admin
app at `/`** plus the `/api/` backend.

---

## 0. What you're deploying to (the live setup)

| Thing | Value |
|---|---|
| Live URL | **http://13.234.22.207/** |
| SSH | `ubuntu@13.234.22.207` |
| SSH key | `C:\Users\Parul\Downloads\shifa-admin.pem` |
| Admin served at | **`/`** (root) — Angular built with base-href `/` |
| API | Nginx `/api/` → Spring Boot on `127.0.0.1:8080` |
| Database | **MySQL 8 on the SAME EC2 box** (`shifa_dashboard`, localhost:3306) |
| File storage | Amazon **S3** (`STORAGE_PROVIDER=S3`, bucket via `/etc/shifa/shifa.env`) |
| Backend service | systemd unit **`shifa-oms`**, JAR at `/opt/shifa/shifa-oms.jar` |
| Web root | `/var/www/shifa/admin` (served by Nginx) |
| Backend env/secrets | `/etc/shifa/shifa.env` (root-only, mode 600 — never in git) |

> ⚠️ The public IP is **ephemeral** — if the instance is stopped/started it can change. If the site
> stops responding, get the new IP from the EC2 console and pass it with `-Ip <new-ip>` (below).

---

## 1. Redeploy — the ONE command you run every time

From a **PowerShell window on your PC** (the repo is at `c:\E Drive\Shifa-Software`):

```powershell
powershell -ExecutionPolicy Bypass -File "c:\E Drive\Shifa-Software\deploy\push-to-aws.ps1" `
    -KeyPath "C:\Users\Parul\Downloads\shifa-admin.pem"
```

That's it. The script (`deploy/push-to-aws.ps1`) does everything:

1. **Builds** the backend JAR (`mvn -DskipTests clean package`) and the admin bundle (`npm run build:admin`, base-href `/`).
2. **Uploads** the JAR, the admin bundle, and the server apply script to the instance.
3. **Runs `deploy/aws-apply.sh` on the server**, which:
   - **Backs up MySQL first** → `~/shifa-backup-<timestamp>.sql` (because restarting the backend
     auto-applies any new Flyway migrations),
   - swaps the JAR into `/opt/shifa/shifa-oms.jar`,
   - publishes the admin bundle to `/var/www/shifa/admin`,
   - **restarts `shifa-oms`** (Flyway applies any new migrations) and **reloads Nginx**.

### Handy flags
- **`-SkipBuild`** — reuse the JAR/admin you already built (skips mvn + ng build; faster):
  ```powershell
  powershell -ExecutionPolicy Bypass -File "c:\E Drive\Shifa-Software\deploy\push-to-aws.ps1" -KeyPath "C:\Users\Parul\Downloads\shifa-admin.pem" -SkipBuild
  ```
- **`-Ip <new-ip>`** — if the instance's public IP changed.

---

## 2. Verify the deploy worked

```powershell
ssh -i "C:\Users\Parul\Downloads\shifa-admin.pem" ubuntu@13.234.22.207 "sudo journalctl -u shifa-oms -n 60 --no-pager"
```
Look for:
- `Successfully applied N migrations ... now at version vNN` (only when there ARE new migrations),
- `Tomcat started on port 8080 (http) with context path '/'`,
- `Started Application in ... seconds`.

Quick HTTP check (from anywhere):
```powershell
curl -s -o NUL -w "%{http_code}" http://13.234.22.207/      # expect 200 (admin loads)
```
Then hard-refresh the admin in your browser (**Ctrl-Shift-R**) so the new PWA service worker picks up
the fresh bundle.

> **Log note:** during a redeploy you'll see `ClassNotFoundException` / `s3Client` / Tomcat
> `Lifecycle$SingleUse` lines — that's the OLD JVM shutting down as it's replaced. Harmless. Only the
> lines from the NEW process (after "Starting service [Tomcat]") matter.

---

## 3. Database / schema changes

You do **not** run SQL on the server. Flyway does it on restart.

1. Add a **new** migration file: `backend/src/main/resources/db/migration/V44__your_change.sql`
   (then `V45__…`, etc.). **Never edit an already-applied migration.**
2. Add/adjust the matching JPA entity field (because `ddl-auto: validate` compares entities to the schema).
3. Redeploy (§1). On restart Flyway detects the new file, runs it once, and records it in
   `flyway_schema_history`.

**Keep migrations additive/nullable** (add columns/tables) so a code rollback stays safe and existing
rows are preserved. The pre-restart `mysqldump` backup (taken automatically by the script) is your
safety net; a destructive migration does exactly what you wrote, so back up matters most there.

---

## 4. Rollback

- **Bad code, no new migration:** copy the previous JAR back and restart. Data was never touched.
  (The script overwrites the JAR in place, so keep a copy before deploying if you want a fast revert,
  or rebuild the previous commit.)
- **A migration ran but code is bad:** roll the **code** back to the previous JAR — additive columns
  are ignored by old code, so the schema change is harmless.
- **Restore data (last resort):** the backup is on the server at `~/shifa-backup-<timestamp>.sql`:
  ```bash
  # DANGER: overwrites current data. Stop the app first.
  sudo systemctl stop shifa-oms
  sudo bash -c '. /etc/shifa/shifa.env; MYSQL_PWD="$DB_PASSWORD" mysql -u"$DB_USERNAME" "$DB_NAME" < ~/shifa-backup-<timestamp>.sql'
  sudo systemctl start shifa-oms
  ```

---

## 5. Day-to-day operations (on the server)

```bash
sudo systemctl status shifa-oms          # is it running?
sudo journalctl -u shifa-oms -f          # live logs
sudo systemctl restart shifa-oms         # restart backend (re-runs Flyway if new migrations)
sudo nginx -t && sudo systemctl reload nginx
sudo tail -f /var/log/nginx/error.log

# Manual DB backup (the app also runs a nightly backup at 02:00)
sudo bash -c '. /etc/shifa/shifa.env; MYSQL_PWD="$DB_PASSWORD" mysqldump --no-tablespaces -u"$DB_USERNAME" "$DB_NAME"' > ~/shifa-backup-$(date +%F).sql
```

---

## 6. Troubleshooting

| Symptom | Fix |
|---|---|
| `push-to-aws.ps1` can't connect | Wrong `-KeyPath`, wrong `-Ip` (changed after stop/start), or the EC2 security group doesn't allow port 22 from your IP. |
| Site unreachable in browser | Security group must allow **80** (and 443 if HTTPS). Check `sudo systemctl status shifa-oms` and `nginx -t`. |
| 502 Bad Gateway | Backend down: `sudo journalctl -u shifa-oms -e` — usually a bad migration or wrong `shifa.env` DB creds. |
| Backend won't start, DB error | `/etc/shifa/shifa.env` `DB_*` values must match the local MySQL user; `sudo systemctl restart shifa-oms`. |
| Admin loads blank | The bundle must be built base-href `/` (the script does this via `npm run build:admin`). Don't pass `/admin/`. |
| App fails to start: S3 bucket blank | `STORAGE_PROVIDER=S3` requires `STORAGE_S3_BUCKET` in `shifa.env` (or set `STORAGE_PROVIDER=DB`). |
| `mysqldump ... PROCESS privilege` | Already handled — the backup uses `--no-tablespaces`. |
| systemd: "unit file changed on disk" | Someone hand-edited the unit; run `sudo systemctl daemon-reload` once. Restart still works without it. |

---

## Appendix A — First-time server provisioning (only once, on a fresh EC2)

You normally never do this again. Full detail lives in `docs/DEPLOYMENT-AWS.md`; the essentials:

1. **Launch** an Ubuntu 22.04/24.04 EC2 instance; security group opens **22, 80, 443**. Save the `.pem` key.
2. **Install prerequisites:**
   ```bash
   sudo apt update && sudo apt -y upgrade
   sudo apt -y install openjdk-21-jdk maven git unzip mysql-server mysql-client nginx
   ```
3. **MySQL:** `sudo mysql_secure_installation`, then create the DB + app user:
   ```bash
   sudo mysql <<'SQL'
   CREATE DATABASE IF NOT EXISTS shifa_dashboard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE USER IF NOT EXISTS 'shifa'@'localhost' IDENTIFIED BY 'STRONG_PASSWORD';
   GRANT ALL PRIVILEGES ON shifa_dashboard.* TO 'shifa'@'localhost';
   FLUSH PRIVILEGES;
   SQL
   ```
4. **Backend env:** copy the template and fill in real values (DB password, `JWT_SECRET`,
   `STORAGE_S3_BUCKET`/region, seed passwords):
   ```bash
   sudo mkdir -p /etc/shifa
   sudo cp ~/shifa/deploy/shifa.env.example /etc/shifa/shifa.env
   sudo nano /etc/shifa/shifa.env
   sudo chmod 600 /etc/shifa/shifa.env
   ```
5. **App user + dirs:** `sudo useradd -r -s /usr/sbin/nologin shifa`; `sudo mkdir -p /opt/shifa /var/www/shifa/admin`.
6. **systemd unit:** `sudo cp ~/shifa/deploy/shifa-oms.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable shifa-oms`.
7. **Nginx:** `sudo cp ~/shifa/deploy/nginx-shifa.conf /etc/nginx/sites-available/shifa`, set `server_name`,
   symlink into `sites-enabled`, remove `default`, `sudo nginx -t && sudo systemctl reload nginx`.
8. **S3:** create the bucket + an IAM instance role granting `s3:PutObject/GetObject`; attach it to the EC2 instance.
9. **First deploy:** run `deploy/push-to-aws.ps1` from your PC (§1). Flyway builds the whole schema on first start.
10. **(Recommended) HTTPS:** point a domain/`nip.io` name at the IP, then
    `sudo apt -y install certbot python3-certbot-nginx && sudo certbot --nginx -d <host> --redirect`.

---

## Appendix B — Files that power this guide (in `deploy/`)

| File | Role |
|---|---|
| `push-to-aws.ps1` | **The command you run** (build → upload → apply). |
| `aws-apply.sh` | Runs on the server: backup → swap JAR → publish admin → restart → reload. |
| `nginx-shifa.conf` | Reference Nginx site (admin at `/`, `/api/` proxy, SSE). |
| `shifa-oms.service` | systemd unit for the backend. |
| `shifa.env.example` | Template for `/etc/shifa/shifa.env` (never commit real secrets). |
