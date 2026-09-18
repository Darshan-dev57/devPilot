# Deploy DevPilot on Oracle Cloud Ampere A1 (Always Free)

Target shape: **VM.Standard.A1.Flex, 2 OCPUs / 12 GB RAM, ARM64**, Ubuntu 24.04.
This fits comfortably inside the Always Free allowance (4 OCPUs / 24 GB total)
and every image used here is verified multi-arch (`amd64` + `arm64`):
`pgvector/pgvector:pg16`, `eclipse-temurin:21-jre`, `node:22-alpine`, `caddy:2-alpine`.
Expected steady-state RAM use is ~2–3 GB.

## 0. Know the Always Free gotchas

- **"Out of capacity"** for Ampere shapes is common in busy regions/ADs. Retry in a
  different Availability Domain or region (e.g. Hyderabad, Mumbai, Frankfurt, Ashburn),
  or try off-peak. Paid (PAYG) tenancies get the same free allowance with better capacity.
- **Idle instances can be reclaimed.** Keep the VM doing something, or accept the risk.
- The public IP is **ephemeral** (changes on stop/start) unless you reserve one
  (free while attached to a running instance).
- You need a hostname for automatic TLS. No domain? Use nip.io:
  `devpilot.<VM-PUBLIC-IP>.nip.io` (e.g. `devpilot.129.154.44.20.nip.io`).

## 1. Create the VM

1. Console → Compute → Instances → **Create instance**.
2. Image: **Ubuntu 24.04 Minimal aarch64** (or Canonical Ubuntu 24.04 aarch64).
3. Shape: **VM.Standard.A1.Flex**, 2 OCPUs, 12 GB RAM.
4. Boot volume: 50 GB is plenty.
5. Networking: keep the default VCN. **Check "Assign a public IPv4 address".**
6. Paste your **SSH public key**. Create.

## 2. Open firewall ports (two layers on Oracle)

**Layer 1 — VCN Security List** (Networking → VCN → Security Lists → Default):
add Ingress rules, stateless No, source `0.0.0.0/0`:
- TCP **80** (HTTP, Let's Encrypt + redirect)
- TCP **443** (HTTPS)
- TCP **22** — restrict source to your own IP, not `0.0.0.0/0`.

**Layer 2 — instance iptables** (Oracle Ubuntu images drop everything by default):
```bash
ssh ubuntu@<VM-PUBLIC-IP>
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 3. Install Docker + get the code

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker ubuntu
newgrp docker
docker --version   # 25+ expected

sudo apt-get install -y git
git clone https://github.com/Darshan-dev57/devPilot.git
cd devPilot
```

## 4. Configure (4 values)

```bash
cp .env.example .env
openssl rand -hex 32   # -> TOKEN_ENCRYPTOR_PASSWORD
openssl rand -hex 8    # -> TOKEN_ENCRYPTOR_SALT
```

Edit `.env`:
- `DOMAIN=` — your hostname (`devpilot.example.com` or `devpilot.<IP>.nip.io`).
  Point your domain's **A record** at the VM IP first if using a real domain.
- `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` — register a **production** GitHub
  OAuth App (github.com → Settings → Developer settings → OAuth Apps → New):
  - Homepage URL: `https://<DOMAIN>`
  - Authorization callback URL: `https://<DOMAIN>/login/oauth2/code/github`
  - (Keep a separate local OAuth App with `http://localhost:8080/...` for dev.)
- `POSTGRES_PASSWORD=` — any strong password.

No OpenAI key needed on the server: every user adds their own in
Settings → OpenAI API key (stored encrypted, billed to them).

### Using a free Gemini key instead of OpenAI

Confirmed working end-to-end. Set these server properties (or env equivalents)
so the per-user factory talks to Gemini's OpenAI-compatible endpoint:

```properties
app.ai.base-url=https://generativelanguage.googleapis.com/v1beta/openai/
app.ai.chat-model=gemini-3.8-flash
app.ai.embedding-model=gemini-embedding-001
app.ai.embedding-dimensions=768
spring.ai.vectorstore.pgvector.dimensions=768
```

Notes:
- Model names retire fast on Gemini's side — if chat fails with
  "model ... is no longer available", list current ones with:
  `curl https://generativelanguage.googleapis.com/v1beta/openai/models -H "Authorization: Bearer KEY"`
  and pick the newest `gemini-*-flash`.
- `gemini-embedding-001` needs the native API path (already built into
  `GeminiEmbeddingModel`) and 768 dimensions.
- Changing embedding model/dimensions later requires a fresh `vector_store`
  table (`DROP TABLE vector_store;` — it is recreated automatically).

## 5. Launch

```bash
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f caddy backend
```

Open `https://<DOMAIN>`. Caddy fetches the TLS certificate automatically on first
visit (port 80 must be reachable for the ACME challenge).

## 6. Day-2 operations

```bash
# Update to latest code
git pull
docker compose -f docker-compose.prod.yml up -d --build

# Logs / status
docker compose -f docker-compose.prod.yml logs -f backend
docker compose -f docker-compose.prod.yml ps

# Database backup (volume devpilot_pg_data)
docker run --rm -v devpilot_devpilot_pg_data:/data -v $PWD:/backup \
  ubuntu tar czf /backup/pg-backup-$(date +%F).tgz /data
```

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Browser can't reach the site | VCN security list or instance iptables missing 80/443 |
| TLS error / cert not issued | DNS A record wrong, or port 80 blocked during first visit |
| Login lands on `?error=oauth_failed` | Callback URL mismatch in the GitHub OAuth App, or wrong client id/secret |
| `401` right after GitHub approve | `TOKEN_ENCRYPTOR_*` changed after users were created — keep it stable |
| Index/chat says "Add your OpenAI API key" | Expected: that user hasn't saved a key in Settings yet |
| OOM / slowness | Check `docker stats`; 2 OCPU/12 GB is enough — look for runaway indexing first |
