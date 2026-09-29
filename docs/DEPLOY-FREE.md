# Deploy DevPilot for ₹0 with no credit card

Oracle needs a card for verification, so this guide uses only providers whose
free tier needs **no card**: Neon (Postgres + pgvector), Render (backend),
Vercel (frontend). Total cost: $0.

## Architecture

No login required. Visitors paste a GitHub repo link → backend fetches files using a **server-side GitHub token** (you provide one free token) → index → chat. Each visitor brings their own AI key (OpenAI or Gemini), stored in their browser's localStorage and sent with each request via the `X-Api-Key` header.

## Path A (recommended): Neon + Render + Vercel

Architecture: browser → Vercel (Next.js) → Render (Spring Boot API) → Neon
(Postgres). Each user still brings their own OpenAI/Gemini key in Settings.

### 1. Database — Neon (5 min)

1. Sign up at neon.com (email/GitHub, no card) → New Project.
2. Open the SQL Editor and run:
   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   CREATE EXTENSION IF NOT EXISTS hstore;
   CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
   ```
3. Copy the connection string (pooled or direct) and convert it to JDBC form:
   `jdbc:postgresql://<host>/<dbname>?sslmode=require`
   plus the username/password shown. Keep them handy.

### 2. GitHub token (2 min)

1. Go to github.com → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token.
2. Select `repo` scope (for private repos) or just `public_repo` (for public repos only).
3. Copy the token — you'll paste it into Render.

### 3. Backend — Render (10 min + first build)

1. Sign up at render.com (no card) → New → **Blueprint** → select your
   `devPilot` repo. Render reads `render.yaml` and creates `devpilot-backend`.
2. Fill the `sync: false` variables in the dashboard:
   - `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` — from step 1.
   - `APP_GITHUB_TOKEN` — from step 2.
3. Deploy. First build takes ~8–10 min (Maven downloads). Health check:
   `https://<your-service>.onrender.com/actuator/health` → `{"status":"UP"}`.
4. Note the backend URL: `https://<your-service>.onrender.com`.

> Heap is capped at 320 MB for the 512 MB free instance (`JAVA_TOOL_OPTIONS`
> in `render.yaml`). Don't raise it without a paid instance.

### 4. Frontend — Vercel (5 min)

1. Sign up at vercel.com (no card) → Add New Project → import `devPilot`.
2. Set **Root Directory** to `client`, keep the Next.js preset.
3. Environment variable: `NEXT_PUBLIC_API_BASE_URL=https://<render-backend>.onrender.com`
4. Deploy. Note the URL: `https://<your-app>.vercel.app`.

### 5. Use it

Open the Vercel URL → paste a GitHub repo link → add your AI key in Settings → Index → Chat. No login required.

Free Render services sleep after 15 min idle; Spring Boot then takes ~60s to
wake. A free UptimeRobot monitor (no card) pinging
`https://<render-backend>.onrender.com/actuator/health` every 5 min keeps one
service warm — 720 hrs/month fits inside Render's 750 free hours.

### Honest limits of Path A

| Piece | Free behavior |
|---|---|
| Render backend | Sleeps after 15 min idle (~60s cold start); 750 hrs/mo = ~1 always-on service |
| Neon DB | Sleeps after 5 min idle (wakes in seconds); pooled connections recycled via the Hikari settings in `render.yaml` |
| Vercel frontend | Always on, no sleep |
| Render Postgres | NOT used (expires after 30 days) — that's why the DB is on Neon |

## Path B: this PC + Cloudflare Tunnel (simplest, always fast)

Run the full `docker-compose.prod.yml` stack on your own machine and expose it
— no cloud signup, no sleep, full speed. Roughly:

```bash
# on your machine
docker compose -f docker-compose.prod.yml up -d --build
# in another terminal (cloudflared is a single free binary, no card)
cloudflared tunnel --url http://localhost:80
```

Caveats: your PC must stay on and online; the free `trycloudflare.com` URL
changes on every restart, which breaks the GitHub OAuth callback. For a stable
URL, put any domain you control on Cloudflare's free plan (no card) and create
a named tunnel to `http://localhost:80` — then use that hostname as `DOMAIN`.

## Path C: cheap VPS with UPI (no credit card, not free)

If you want Oracle-style control without a card, Indian hosts like Hostinger
take UPI/netbanking for VPS plans (~₹150–400/mo). Any 2 GB+ RAM VM runs
`docker-compose.prod.yml` unchanged — follow `docs/DEPLOY-ORACLE.md` and skip
the Oracle-specific firewall steps.
