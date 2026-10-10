<p align="center"><img src="docs/logo.svg" width="72" alt="DevPilot logo: code brackets around a compass needle"></p>

<h1 align="center">DevPilot</h1>

Ask any public GitHub repo how it works. Paste a link, DevPilot reads the code once, and you chat with it: answers stream in word by word, and every source is a link to the exact lines on GitHub.

No sign-up and no key to paste. The server owner sets one free Gemini key; daily limits keep that free quota from running out.

## What you can do

- **Paste a repo and index it.** DevPilot downloads it as one zip, splits the code at class and method boundaries and stores embeddings in Postgres (pgvector). Progress shows live as "x/y files".
- **Ask in plain English.** "Where is the owner validated?", "Which endpoint adds a pet?" Answers cite `File.java:41-88` chips that open GitHub at the indexed commit.
- **Re-index after new commits.** Only the changed files are embedded again. No change, no work.
- **Your list stays yours.** Each browser gets an anonymous id, so you only see the repos you added. A repo two people add is indexed once and shared under the hood.

## Run it locally

You need Docker, Java 21 and Node 20+.

```bash
# 1. Database (Postgres 16 + pgvector on port 5433)
docker compose up -d postgres

# 2. Backend on :8080
cd backend
export GEMINI_API_KEY=your-key        # free at https://aistudio.google.com/apikey
export GITHUB_TOKEN=optional-token    # no scopes needed; lifts GitHub's 60 calls/hour limit
./mvnw spring-boot:run

# 3. Frontend on :3000
cd ../client
npm install
echo "NEXT_PUBLIC_API_BASE_URL=http://localhost:8080" > .env.local
npm run dev
```

Open http://localhost:3000, paste `https://github.com/spring-projects/spring-petclinic`, press Index, then Chat.

Never commit a key. Everything secret comes from environment variables.

## Free tier and limits

DevPilot defaults to Google's Gemini API free tier: `gemini-3.5-flash-lite` for answers and `gemini-embedding-2` for embeddings. Google's pricing page lists both as "Free of charge" on the free tier (checked October 10, 2026). Google's own limits, from its [rate limits page](https://ai.google.dev/gemini-api/docs/rate-limits): limits are per Google Cloud project, not per key, and "Requests per day (RPD) quotas reset at midnight Pacific time". Your project's exact numbers are shown in AI Studio.

What I measured with a free-tier key: Google's 429 reply names the embedding limit as `embed_content_free_tier_requests, limit: 100` per minute, and every text in a batch counts as one request. So a repo with more than about 100 chunks hits the limit mid-index. DevPilot waits the time Google asks for ("Please retry in 32s"), shows "Waiting for the free per-minute limit" in the progress bar, and carries on.

To keep one free key alive on a public site, the server enforces (all configurable, `0` switches a limit off):

| Limit | Default | Env var |
|---|---|---|
| Questions per visitor per day | 30 | `LIMIT_QUESTIONS_PER_VISITOR` |
| Index runs per visitor per day | 5 | `LIMIT_INDEXES_PER_VISITOR` |
| Questions per day, whole site | 1000 | `LIMIT_QUESTIONS_GLOBAL` |
| Index runs per day, whole site | 100 | `LIMIT_INDEXES_GLOBAL` |
| Largest repo accepted | 60 MB | `MAX_REPO_MB` |
| Most code files per index | 800 | `MAX_INDEX_FILES` |

Counters live in Postgres, so a restart does not reset them, and the day rolls over at midnight Pacific, the same moment Google resets its daily quota. A visitor who runs out sees "You've used today's 30 free questions. Come back tomorrow." The visitor id is kept in the browser, so clearing site data resets a visitor's own count; the site-wide cap is the hard stop. If Gemini itself answers 429, chat retries twice with a back-off before saying "The free Gemini quota is busy right now. Wait a minute and try again."

### Google's free-tier data terms

Read these before you put a free key on a public site. From Google's [Gemini API Additional Terms](https://ai.google.dev/gemini-api/terms), under Unpaid Services:

> When you use Unpaid Services, including, for example, Google AI Studio and the unpaid quota on Gemini API, Google uses the content you submit to the Services and any generated responses to provide, improve, and develop Google products and services and machine learning technologies, including Google's enterprise features, products, and services, consistent with our Privacy Policy.

> To help with quality and improve our products, human reviewers may read, annotate, and process your API input and output.

> Do not submit sensitive, confidential, or personal information to the Unpaid Services.

DevPilot only indexes public repositories, and private repos are refused even if the server's GitHub token could read them. Questions visitors type are sent to Google too, so the site should say so (the frontend shows which model answers).

### Other providers

The same code runs on OpenAI (`AI_PROVIDER=openai`, `OPENAI_API_KEY`) or on a local Ollama (`AI_PROVIDER=ollama`). Each provider keeps its own vector table because embedding sizes differ.

**OpenCode Zen for answers.** `AI_PROVIDER=opencode` with `OPENCODE_API_KEY` (sign in at opencode.ai) writes answers with an OpenCode Zen model over its OpenAI-compatible endpoint (`https://opencode.ai/zen/v1/chat/completions`, override with `OPENCODE_BASE_URL`). Zen has no embeddings endpoint, so indexing stays on Gemini (or on `AI_EMBEDDING_PROVIDER=ollama`). The default model is `longcat-2.5-preview-free`; set another with `OPENCODE_MODEL`. Zen's [docs](https://opencode.ai/docs/zen) say of it: "LongCat 2.5 Preview Free is free on OpenCode for a limited time. Its provider follows a zero-retention policy and does not use your data for model training." Because free models there are limited-time, DevPilot answers with Gemini instead if the OpenCode model fails before writing anything, and logs why. Other free models on Zen may use your data ("During its free period, collected data may be used to improve the model"), so read the Privacy section of that page before switching. This path is covered by tests against a local fake of the API; it has not been run against the live OpenCode service.

## Deploy for free

- [docs/DEPLOY-FREE.md](docs/DEPLOY-FREE.md): Neon (database) + Render (backend) + Vercel (frontend), no card.
- [docs/DEPLOY-ORACLE.md](docs/DEPLOY-ORACLE.md): one Oracle Ampere VM running `docker-compose.prod.yml`.

## Architecture

```mermaid
flowchart LR
  B[Browser<br/>Next.js] -- REST + SSE<br/>X-Visitor-Id --> API[Spring Boot API]
  API -- one zip per commit,<br/>compare for changes --> GH[GitHub API]
  API -- embeddings + chat<br/>server key --> AI[Gemini / OpenAI / Ollama]
  API -- chunks + vectors,<br/>repos, chats, usage --> PG[(Postgres + pgvector)]
```

- `services/indexing`: zip download, file filter, class/method chunker, incremental re-index.
- `services/ai`: provider factory, Gemini chat and embeddings, retries on 429, retriever, prompt, SSE stream.
- `services/limits`: the daily usage limiter.
- `services/github`: GitHub client with a rate limiter; refuses private repos.

## API

| Method | Endpoint | What it does |
|---|---|---|
| GET | `/api/usage` | Model in use and today's remaining free questions and index runs |
| GET | `/api/repos` | This visitor's repos |
| POST | `/api/repos/by-url` | Add a public repo `{owner, name}` |
| POST | `/api/repos/{id}/index` | Start (or re-run) indexing, 202 |
| GET | `/api/repos/{id}/status` | Live progress |
| DELETE | `/api/repos/{id}` | Remove from this visitor's list |
| POST | `/api/chat/sessions` | New chat for a ready repo |
| GET | `/api/chat/sessions/{id}` | Chat history |
| POST | `/api/chat/sessions/{id}/messages` | Ask; answer streams as SSE (`token`, `assistant_message`, `done`, `error`) |

Every call carries `X-Visitor-Id`, the anonymous browser id. It is not a login.

## Tests

```bash
cd backend && ./mvnw verify     # unit tests + Testcontainers end-to-end test (needs Docker)
cd client && npm run lint && npm run build
```

GitHub Actions runs both on every push.

## Credits

- Headline animation: [ObsidianUI FlipText](https://www.obsidianui.dev/docs/flip-text) (MIT).
- Built with Spring Boot, Spring AI, pgvector, Next.js, Tailwind CSS and TanStack Query.
