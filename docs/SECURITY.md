# Shifa OMS — Security notes

## Where secrets live

| Environment | File | Committed? |
|-------------|------|------------|
| Local dev   | `backend/local-secrets.properties` | **No** (git-ignored). Template: `backend/local-secrets.properties.example`. |
| Production  | `/etc/shifa/shifa.env` on the EC2 box (mode `600`, root) | **No**. Template: `deploy/shifa.env.example`. |

Never put a real secret in any file that git tracks, in `application*.yml`, or in a
code default. All real values are injected via environment variables (prod) or the
git-ignored `local-secrets.properties` (dev).

### Code defaults that MUST be overridden in prod
- `app.security.jwt.secret` — defaults to a committed development string. The
  `prod` profile refuses to start on that default (`JwtSecretValidator`). Set
  `JWT_SECRET` to a long random value.
- `app.payment.sandbox.secret` — demo default; set `PAYMENT_SANDBOX_SECRET`.
- DB credentials, mail password, QuikShipX/Meta/Shopify secrets — all blank by
  default and supplied via env.

## Secret rotation

Rotate a secret whenever it may have been exposed (committed, logged, shared, or
on staff turnover). General procedure:

1. Generate/obtain a new value in the provider's console.
2. Update the live source of truth — `/etc/shifa/shifa.env` on the server (and
   your local `local-secrets.properties` if used in dev).
3. Restart the backend: `sudo systemctl restart shifa-oms`.
4. Invalidate the old value in the provider's console so it can no longer be used.

### JWT secret
- Generate: `openssl rand -base64 48`
- Set `JWT_SECRET` in `/etc/shifa/shifa.env`, restart.
- Note: rotating this invalidates all existing tokens — users must log in again.

### Gmail app password (`MAIL_PASSWORD`)
1. https://myaccount.google.com/apppasswords — delete the old app password and
   create a new one (16 chars).
2. Update `MAIL_PASSWORD` in `/etc/shifa/shifa.env` (and local file if used).
3. Restart the backend. The old password is already dead once deleted in Google.

### QuikShipX secret (`QUIKSHIPX_USER_SECRET` / `QUIKSHIPX_LIVE_SECRET`)
1. In the QuikShipX dashboard, regenerate the API user secret.
2. Update the value in `/etc/shifa/shifa.env` (and local file if used).
3. Restart the backend.

### Database password (`DB_PASSWORD`)
1. `ALTER USER 'shifa'@'localhost' IDENTIFIED BY '<new-strong-password>';` then
   `FLUSH PRIVILEGES;` in MySQL.
2. Update `DB_PASSWORD` in `/etc/shifa/shifa.env`, restart the backend.

## If a secret was committed to git history
Rotating (above) is the real fix — assume anything in history is compromised.
Scrubbing history (`git filter-repo` / BFG) is optional cleanup and does not
substitute for rotation, since clones/forks may retain the old value.
