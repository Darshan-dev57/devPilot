# DevPilot

DevPilot is a GitHub-connected AI code assistant. Connect your GitHub account, pick a repository, index its code, and chat with it — answers stream back with file citations.

**Bring your own key:** each user adds their own OpenAI API key once in
Settings → OpenAI API key (stored encrypted, billed to them). The server operator
needs no OpenAI key, so the site can serve any number of users at zero AI cost.

## Deploy as a website

Two guides, pick one:

- ➡️ **[docs/DEPLOY-FREE.md](docs/DEPLOY-FREE.md)** — ₹0, no credit card:
  Neon (DB) + Render (backend) + Vercel (frontend). Best starting point.
- ➡️ **[docs/DEPLOY-ORACLE.md](docs/DEPLOY-ORACLE.md)** — free Oracle Ampere ARM
  VM with everything in one `docker-compose.prod.yml` (needs a card for
  verification, serves unlimited users at full speed).

Short version: fill 4 values in `.env` (domain, GitHub OAuth id/secret, DB password,
encryptor secrets) and run `docker compose -f docker-compose.prod.yml up -d --build`.
Visitors then just open the site, log in with GitHub, paste their OpenAI key in
Settings, and start chatting.

## Features

- GitHub OAuth login (session based)
- Repository dashboard with sync, search, and status filtering (`PENDING` / `INDEXING` / `READY` / `FAILED`)
- Async code indexing into pgvector (filters, chunks, embeds)
- Per-repository chat sessions with streaming responses (SSE) and file citations
- Markdown answers with code blocks, chat history, workspace overview and settings pages

## Tech Stack

- **Backend:** Java 21, Spring Boot 4.1, Spring Security + OAuth2 Client, Spring Data JPA, Spring AI 2.0 (OpenAI + pgvector)
- **Database:** PostgreSQL 16 with pgvector (`docker-compose.yml`)
- **Frontend:** Next.js 16, React 19, TypeScript, Tailwind CSS 4, TanStack Query, Streamdown
- **AI / RAG:** OpenAI embeddings + chat model, pgvector similarity search (top 8 chunks), custom prompt builder with citations

## Project Structure

```
devPilot/
  backend/   # Spring Boot API (port 8080)
    src/main/java/devPilot/backend/
      controllers/  # Auth, Repo, Chat REST endpoints
      services/     # Chat, Repo, User logic
      services/ai/        # Prompt builder, retriever, stream handler
      services/indexing/  # File filter, chunker, indexing service
      services/github/    # GitHub API client, rate limiter
      entity/ repository/ dto/ security/ config/
  client/    # Next.js app (port 3000)
    app/            # /, /login, /auth/callback, /dashboard, /chat/[repoId]
    components/     # chat, dashboard, layout, ui
    hooks/ lib/     # use-auth, use-chat, use-repos, api client, SSE parser
  docker/           # Postgres init (vector, hstore, uuid-ossp)
  docker-compose.yml
```

## Prerequisites

- Docker
- Java 21
- Node.js 20+
- A GitHub OAuth App (Client ID + Secret)
- An OpenAI API key

## Quick Start

### 1. Start the database

```bash
docker compose up -d postgres
```

Starts `pgvector/pgvector:pg16` on host port `5433`, database `devpilot`.

### 2. Configure the backend

Create `backend/src/main/resources/application.properties` (this file is gitignored):

```properties
server.port=8080

spring.datasource.url=jdbc:postgresql://localhost:5433/devpilot
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.jpa.hibernate.ddl-auto=update

spring.security.oauth2.client.registration.github.client-id=YOUR_GITHUB_CLIENT_ID
spring.security.oauth2.client.registration.github.client-secret=YOUR_GITHUB_CLIENT_SECRET
spring.security.oauth2.client.registration.github.scope=read:user,repo

app.frontend-url=http://localhost:3000
app.cors.allowed-origins=http://localhost:3000
app.token-encryptor-password=change-this-to-a-long-random-secret
app.token-encryptor-salt=change-this-to-a-hex-salt

spring.ai.openai.api-key=sk-your-openai-key
```

> The server key is only a fallback placeholder. In normal use every user saves
> their own key in the app (Settings → OpenAI API key), which the backend
> encrypts and uses per-request for that user's indexing and chat.

### 3. Run the backend

```bash
cd backend
./mvnw spring-boot:run
```

API runs on `http://localhost:8080`.

### 4. Run the frontend

```bash
cd client
npm install
echo "NEXT_PUBLIC_API_BASE_URL=http://localhost:8080" > .env.local
npm run dev
```

App runs on `http://localhost:3000`.

### 5. Use it

1. Open `http://localhost:3000` and continue with GitHub.
2. On the dashboard, sync repos and click Index on one.
3. Wait until status is `READY`, then open Chat.

## API Overview

| Method | Endpoint | Description |
| ------ | -------- | ----------- |
| GET | `/api/auth/login-url` | Get GitHub OAuth URL |
| GET | `/api/auth/me` | Current user |
| POST | `/api/auth/logout` | Logout |
| GET | `/api/repos?refresh=true` | List / sync GitHub repos |
| GET | `/api/repos/{id}` | Repo details |
| POST | `/api/repos/{id}/index` | Start indexing (202) |
| GET | `/api/repos/{id}/status` | Indexing status |
| POST | `/api/chat/sessions` | Create chat session (repo must be READY) |
| GET | `/api/chat/sessions?repositoryId=` | List sessions |
| GET | `/api/chat/sessions/{id}` | Message history |
| POST | `/api/chat/sessions/{id}/messages` | Send message (SSE stream) |

## How Indexing and Chat Work

- **Indexing:** fetches the GitHub repo tree, skips binaries / lockfiles / `node_modules` / build output, chunks code files (~800 chars), and stores embeddings in pgvector with `repoId`, `filePath`, `language` metadata.
- **Chat:** retrieves the top 8 similar chunks for the repo, builds a context-only prompt, streams the answer over SSE (`token`, `user_message`, `assistant_message`, `done` events), and saves citations as JSON.

## Scripts

Backend (`backend/`):

```bash
./mvnw test       # run tests
./mvnw package    # build jar
```

Frontend (`client/`):

```bash
npm run dev     # dev server
npm run build   # production build
npm run start   # serve production build
npm run lint    # eslint
```
