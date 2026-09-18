export type IndexStatus = "PENDING" | "INDEXING" | "PAUSED" | "READY" | "FAILED";

export type AiProvider = "openai" | "gemini";

export type User = {
  id: string;
  githubId: number;
  githubUsername: string;
  displayName: string;
  avatarUrl: string | null;
  aiKeySet: boolean;
  aiProvider: AiProvider;
};

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
};

export type IndexStatusResponse = {
  repositoryId: string;
  indexStatus: IndexStatus;
  filesTotal: number;
  filesProcessed: number;
  chunkCount: number;
  indexedAt: string | null;
  errorMessage: string | null;
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


export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export function getApiBaseUrl() {
  // Empty string means same-origin (production behind reverse proxy).
  // Only fall back to localhost when the variable is not set at all (local dev).
  const configured = process.env.NEXT_PUBLIC_API_BASE_URL;
  return configured === undefined ? "http://localhost:8080" : configured;
}

export function getGithubLoginUrl() {
  return `${getApiBaseUrl()}/oauth2/authorization/github`;
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
  const res = await fetch(`${getApiBaseUrl()}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
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
  me: () => apiFetch<User>("/api/auth/me"),
  logout: () =>
    apiFetch<void>("/api/auth/logout", {
      method: "POST",
    }),

  listRepos: (refresh = true) =>
    apiFetch<Repository[]>(`/api/repos?refresh=${refresh}`),
  getRepo: (id: string) => apiFetch<Repository>(`/api/repos/${id}`),
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
  saveAiKey: (provider: AiProvider, apiKey: string) =>
    apiFetch<{ aiKeySet: boolean; aiProvider: AiProvider }>("/api/users/me/ai-key", {
      method: "PUT",
      body: JSON.stringify({ provider, apiKey }),
    }),
  deleteAiKey: () =>
    apiFetch<{ aiKeySet: boolean }>("/api/users/me/ai-key", {
      method: "DELETE",
    }),
};
