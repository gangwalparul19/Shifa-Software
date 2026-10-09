# White-label demo + per-client onboarding

This OMS is now **white-label**: the same build runs for a different client (or a
shared demo) by changing **configuration + a logo**, with no code fork. Each
client gets its **own separate deployment** (own server, own database, own
domain, own JWT secret) — nothing is shared between clients except the build.

This guide covers:
1. Running the **demo locally** on a new `shifa_demo` DB (prod untouched).
2. What the white-label config surface is (what to change per client).
3. **Onboarding a new client** on its own server.

---

## 1. Run the demo locally (prod/`shifa_dashboard` untouched)

The `local` Spring profile is the ONLY thing that uses `shifa_dashboard`. The
`demo` profile uses a **separate** `shifa_demo` database. They never cross.

**One-time:** create the empty demo DB (migrations use explicit IDs, so it must
start empty):

```sql
CREATE DATABASE IF NOT EXISTS shifa_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

**Run the backend on the demo profile:**

```
backend\run-demo-local.cmd
```

This sets `SPRING_PROFILES_ACTIVE=demo`, `DB_NAME=shifa_demo`, local MySQL creds
(`root`/`root@123`), storage in the DB, and the brand env vars. On first boot
Flyway applies `V1..latest` into the empty `shifa_demo`, including the seed
migrations (demo users + ~200 demo orders), so the portal looks alive
immediately.

**Run the admin UI** (dev server points at `localhost:8080`):

```
npm --prefix frontend run start:admin
```

**Demo logins** (seeded): `admin` / `admin123` (ADMIN), `accountant` / `admin123`,
`sales1` / `admin123`, `packer` / `packer123`, plus `sales01`..`sales20` /
`admin123` from the test seed. (See the in-app user manual.)

> The demo profile hard-disables QuikShipX and keeps mail/WhatsApp/courier in
> MOCK, so **no real parcel, email, or message is ever sent** from the demo.

---

## 2. White-label config surface (what to change per client)

### Backend — environment variables (no code change)
| Variable | Purpose | Default (Shifa) |
|---|---|---|
| `BRAND_NAME` | Fallback brand name on PDFs/labels/reports/emails | `Shifa Herbal Remedies` |
| `BRAND_ORDER_CODE_PREFIX` | Prefix on every order code | `SHR-` |
| `MAIL_BRAND_NAME` | Brand shown in emails | `Shifa Herbal Remedies` |
| `CORS_ORIGINS` | The client's admin domain (browser origin) | `https://shifa.weblithic.online` |

> The **seller legal name / address / GSTIN** that print on invoices and labels
> are **runtime data in the `app_settings` table** — set them in the app under
> **Settings** after first boot (they override the `BRAND_NAME` fallback). A
> freshly-created settings row is auto-stamped with `BRAND_NAME` until an admin
> fills in the full details.

### Frontend — one source file + static assets (per build)
The admin app is a static build with no runtime server config, so its chrome is
branded at build time:

- **`frontend/projects/admin/src/app/shared/brand.ts`** — `BRAND.name`. Single
  source for the login/change-password footers, the sidebar/drawer brand label,
  the public tracking page, the WhatsApp message templates, install/update
  prompts, and the dashboard hero copy.
- **`frontend/projects/admin/public/manifest.webmanifest`** — PWA `name` /
  `short_name` / `description`.
- **`frontend/projects/admin/src/index.html`** — `apple-mobile-web-app-title`
  and `theme-color`.
- **Logo** — replace `frontend/projects/admin/public/logo.png` (served at
  `/logo.png`) **and** `backend/src/main/resources/brand/LOGO.png` (bundled PDF
  fallback) with the client's logo. An admin can also upload a logo in-app under
  Settings, which takes priority over the bundled one.

> Optional: the brand green `#1F5D3F` is defined in
> `frontend/projects/admin/src/styles.css` (`--shifa-green-*` / `--tblr-primary`).
> Change it there to re-skin; some component files still inline the hex, so a
> full re-color is a follow-up if a client wants a different primary color.

---

## 3. Onboard a new client (separate deployment)

Each client runs on its own box, exactly like the existing Shifa prod/demo split.

1. **Provision** a VM with MySQL 8, Java, and Nginx (same shape as the existing
   servers; see `DEPLOYMENT.md`).
2. **Create an empty database** for the client (explicit-ID seeds need it empty):
   ```sql
   CREATE DATABASE <client_db> CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```
3. **Env file** — copy `deploy/client.env.template` to `/etc/shifa/shifa.env`
   (mode 600, root) and fill in the `CHANGE_ME` values: `BRAND_NAME`,
   `BRAND_ORDER_CODE_PREFIX`, `MAIL_BRAND_NAME`, `CORS_ORIGINS`, `DB_NAME`,
   `DB_PASSWORD`, a unique `JWT_SECRET`, and the seed admin/packer passwords.
4. **Brand the frontend build** — set `BRAND.name` in `shared/brand.ts`, update
   the manifest/index.html, and drop in the client logo (both frontend and
   backend copies), then build the admin bundle.
5. **Deploy** the JAR + admin bundle to the client box and start the service
   (Nginx `server_name` = the client's domain; certbot for HTTPS).
6. **First boot** — Flyway applies all migrations + seeds; the app comes up with
   the seeded `admin` login and the ~200 demo orders.
7. **In-app setup** — log in as admin → **Settings** → set the real seller legal
   name, address, GSTIN, GST slabs, bank details; upload the client logo.
8. **(Optional, for a live client)** switch `SPRING_PROFILES_ACTIVE=prod`, set
   `STORAGE_PROVIDER=S3` (+ bucket), enable QuikShipX/mail/WhatsApp with real
   credentials. A demo/sandbox client stays on the `demo` profile.

### Shared demo portal
For a single shared demo everyone logs into: deploy ONE instance on the `demo`
profile at a demo domain, leave the seeded data in place, and share the demo
logins. (Prospects will see each other's test edits — that's expected for a
shared sandbox. Use a per-prospect deployment via the steps above if you need
isolation.)

---

## Notes / follow-ups
- `V51` (GST demo reset) wipes orders and reseeds ~200 demo orders; it also
  stamped a placeholder seller identity. On a client box set the real seller
  identity in **Settings** after first boot. Migrations are never edited
  retroactively (Flyway rule).
- Remaining hard-coded "Shifa" strings are only in config **defaults**
  (overridden by the env vars above) and the static `user-manual.html` help doc
  — update the manual separately if a client needs it rebranded.
