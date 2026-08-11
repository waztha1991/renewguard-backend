# RenewGuard Backend

> **Note:** currently configured to deploy as `renewguard-backend-dev` on the free `*.workers.dev` subdomain (no custom domain attached), separate from any existing production deployment.

Kotlin/Ktor API + admin panel, split out from the main RenewGuard monorepo
for independent deployment. Deploys as a Cloudflare Worker fronting a
Docker container (see `deploy/cloudflare/`).

No longer serves the marketing site or the agent web portal — those now
live in `renewguard-landing` and `renewguard-web` respectively, and this
API allows any origin via CORS (`anyHost()` in `Application.kt`).

## Run locally

```bash
gradlew.bat :backend:run
```

- Admin UI: http://localhost:8080/admin
- API/health: http://localhost:8080/health

## Deploy to Cloudflare

Push to GitHub with Actions secrets `CLOUDFLARE_API_TOKEN` and
`CLOUDFLARE_ACCOUNT_ID` set — `.github/workflows/deploy-cloudflare.yml`
builds the Docker image and deploys via `wrangler.jsonc`.

---

