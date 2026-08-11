# Deploy RenewGuard on Cloudflare

RenewGuard is a **Ktor JVM + SQLite** app. Cloudflare Pages cannot run it — Pages serves static files only, and there is no JVM runtime.

**Production now:** Cloudflare Containers at **https://renewguard.antsolutions.uk** (Workers Paid).

| | Cloudflare Tunnel | Cloudflare Containers (live) |
|---|---|---|
| Cost | Free | Workers Paid, $5/mo minimum |
| Where the app runs | Your PC or a VPS | Cloudflare's network |
| SQLite + uploads | Persistent on real disk | **Ephemeral** — resets when the container moves |
| Uptime | Only while your machine is on | Always |

## SMTP (Cloudflare Email Sending)

RenewGuard sends temporary passwords over SMTP. Cloudflare Email Service provides SMTP at `smtp.mx.cloudflare.net:465`.

### 1. Onboard the domain

1. Cloudflare dashboard → **Compute** → **Email Service** → **Email Sending**
2. **Onboard Domain** → choose **antsolutions.uk** → Done (Cloudflare adds SPF/DKIM/bounce DNS)

### 2. Create an API token (this is the SMTP password)

1. [API Tokens](https://dash.cloudflare.com/profile/api-tokens) → **Create Token**
2. Custom token with permission **Email Sending — Edit**
3. Scope to your account → Create → **copy the token once** (do not paste it into chat)

### 3. Save in RenewGuard Admin

1. Open https://renewguard.antsolutions.uk/admin → **Settings**
2. Click **Fill Cloudflare Email defaults**
3. Paste the API token into **Password**
4. Confirm From email is e.g. `noreply@antsolutions.uk`
5. **Save SMTP settings** → **Send test email**

| Field | Value |
|-------|--------|
| Host | `smtp.mx.cloudflare.net` |
| Port | `465` |
| Username | `api_token` (literal string) |
| Password | your Email Sending API token |
| From | `noreply@antsolutions.uk` (must be on onboarded domain) |
| TLS | on |

**Note:** SMTP settings are stored in the container SQLite DB. Download Admin backups regularly; if the container relocates and data resets, re-enter SMTP settings (or restore a backup).

## Tunnel (local fallback)

Use the standalone binary at `deploy/cloudflare/bin/cloudflared.exe` (no admin install required), then:

```powershell
.\deploy\cloudflare\start-renewguard.ps1
```

| Path | |
|------|--|
| `/` | Marketing |
| `/app` | Agent portal |
| `/admin` | Admin |
| `/health` | Health |

### Named Tunnel + your domain

1. `cloudflared tunnel login`, then `cloudflared tunnel create renewguard`
2. Copy `config.example.yml` → `config.yml` and fill in the tunnel id and hostname
3. `.\deploy\cloudflare\start-named-tunnel.ps1`

## Containers deploy notes

1. Workers Paid plan
2. API token with Edit Cloudflare Workers (+ Containers)
3. GitHub secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`
4. Workflow `.github/workflows/deploy-cloudflare.yml`

Public URL: **https://renewguard.antsolutions.uk**

| Layer | Role |
|-------|------|
| `Dockerfile` (repo root) | Multi-stage: Vite `web` build + Gradle `:backend:installDist` + JRE runtime |
| `wrangler.jsonc` + `package.json` (repo root) | Wrangler config beside the Dockerfile |
| `deploy/cloudflare/worker/src/index.ts` | Worker proxy to sticky container |
| `github-actions-deploy-cloudflare.yml` | Template workflow |

**Container disk is ephemeral.** Download Admin backups regularly.
