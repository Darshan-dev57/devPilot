export type IndexStatus = "PENDING" | "INDEXING" | "PAUSED" | "READY" | "FAILED";

export type AiProvider = "openai" | "gemini";

export type Repository = {
  id: string;
  githubRepoId: number;
  owner: string;
  name: string;
  fullName: string;
  isPrivate: boolean;
  defaultBranch: string;
  language: string | null;
  htmlUrl: string | null;
  description: string | null;
  indexStatus: IndexStatus;
  indexedAt: string | null;
  chunkCount: number;
  filesTotal: number;
  filesProcessed: number;
  errorMessage: string | null;
  indexedSha: string | null;
  embeddingProvider: "OPENAI" | "GEMINI" | null;
  lastIndexMode: "FULL" | "INCREMENTAL" | "UP_TO_DATE" | null;
  lastIndexDurationMs: number | null;
  indexPhase: string | null;
};

export type IndexStatusResponse = {
  repositoryId: string;
  indexStatus: IndexStatus;
  filesTotal: number;
  filesProcessed: number;
  chunkCount: number;
  indexedAt: string | null;
  errorMessage: string | null;
  indexPhase: string | null;
  indexedSha: string | null;
  lastIndexMode: Repository["lastIndexMode"];
  lastIndexDurationMs: number | null;
};

export type ChatSession = {
  id: string;
  repositoryId: string;
  title: string;
  createdAt: string;
};

export type Citation = {
  filePath: string;
  startLine: number | null;
  endLine: number | null;
  language: string | null;
};

export type ChatMessage = {
  id: string;
  role: "USER" | "ASSISTANT";
  content: string;
  citations: Citation[];
  createdAt: string;
};

const AI_KEY_STORAGE_KEY = "devpilot_ai_key";
const AI_PROVIDER_STORAGE_KEY = "devpilot_ai_provider";

export function getStoredApiKey(): string {
  if (typeof window === "undefined") return "";
  return localStorage.getItem(AI_KEY_STORAGE_KEY) ?? "";
}

export function getStoredProvider(): AiProvider {
  if (typeof window === "undefined") return "openai";
  const stored = localStorage.getItem(AI_PROVIDER_STORAGE_KEY);
  return stored === "gemini" ? "gemini" : "openai";
}

export function saveApiKeyToStorage(provider: AiProvider, key: string) {
  if (typeof window === "undefined") return;
  localStorage.setItem(AI_KEY_STORAGE_KEY, key);
  localStorage.setItem(AI_PROVIDER_STORAGE_KEY, provider);
}

export function clearApiKeyFromStorage() {
  if (typeof window === "undefined") return;
  localStorage.removeItem(AI_KEY_STORAGE_KEY);
  localStorage.removeItem(AI_PROVIDER_STORAGE_KEY);
}

const VISITOR_STORAGE_KEY = "devpilot_visitor_id";

function randomUuid(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  // randomUUID needs a secure context (https or localhost); build a v4 UUID by hand otherwise.
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/**
 * Anonymous id for this browser, made once and kept in localStorage. It is not a login;
 * it only keeps each visitor's repo list and chats separate from everyone else's.
 */
export function getVisitorId(): string {
  if (typeof window === "undefined") return "";
  let id = localStorage.getItem(VISITOR_STORAGE_KEY);
  if (!id) {
    id = randomUuid();
    localStorage.setItem(VISITOR_STORAGE_KEY, id);
  }
  return id;
}

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export function getApiBaseUrl() {
  const configured = process.env.NEXT_PUBLIC_API_BASE_URL;
  return configured === undefined ? "http://localhost:8080" : configured;
}

async function parseError(res: Response): Promise<string> {
  try {
    const data = await res.json();
    return data.message ?? data.error ?? res.statusText;
  } catch {
    return res.statusText || "Request failed";
  }
}

export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const apiKey = getStoredApiKey();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    "X-Visitor-Id": getVisitorId(),
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (apiKey) {
    headers["X-Api-Key"] = apiKey;
    // The backend cannot reliably tell a Gemini key from an OpenAI one: Google
    // issues Gemini keys under several prefixes. The chosen provider is sent
    // explicitly so routing does not depend on guessing the key format.
    headers["X-AI-Provider"] = getStoredProvider();
  }

  const res = await fetch(`${getApiBaseUrl()}${path}`, {
    ...init,
    headers,
  });

  if (!res.ok) {
    throw new ApiError(res.status, await parseError(res));
  }

  if (res.status === 204) {
    return undefined as T;
  }

  return res.json() as Promise<T>;
}

export const api = {
  listRepos: () => apiFetch<Repository[]>("/api/repos"),
  addPublicRepo: (owner: string, name: string) =>
    apiFetch<Repository>("/api/repos/by-url", {
      method: "POST",
      body: JSON.stringify({ owner, name }),
    }),
  getRepo: (id: string) => apiFetch<Repository>(`/api/repos/${id}`),
  removeRepo: (id: string) => apiFetch<void>(`/api/repos/${id}`, { method: "DELETE" }),
  startIndex: (id: string) =>
    apiFetch<Repository>(`/api/repos/${id}/index`, { method: "POST" }),
  pauseIndex: (id: string) =>
    apiFetch<Repository>(`/api/repos/${id}/pause-index`, { method: "POST" }),
  resumeIndex: (id: string) =>
    apiFetch<Repository>(`/api/repos/${id}/resume-index`, { method: "POST" }),
  indexStatus: (id: string) =>
    apiFetch<IndexStatusResponse>(`/api/repos/${id}/status`),
  createSession: (repositoryId: string, title?: string) =>
    apiFetch<ChatSession>("/api/chat/sessions", {
      method: "POST",
      body: JSON.stringify({ repositoryId, title }),
    }),
  listSessions: (repositoryId: string) =>
    apiFetch<ChatSession[]>(
      `/api/chat/sessions?repositoryId=${encodeURIComponent(repositoryId)}`
    ),
  getMessages: (sessionId: string) =>
    apiFetch<ChatMessage[]>(`/api/chat/sessions/${sessionId}`),
};
