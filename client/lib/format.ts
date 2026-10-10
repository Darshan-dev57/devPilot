import type { Repository } from "@/lib/api";

export function shortSha(sha: string | null | undefined) {
  return sha ? sha.slice(0, 7) : null;
}

export function formatDuration(ms: number | null | undefined) {
  if (ms == null) return null;
  if (ms < 1000) return `${ms} ms`;
  const seconds = ms / 1000;
  if (seconds < 60) return `${seconds.toFixed(seconds < 10 ? 1 : 0)} s`;
  return `${Math.floor(seconds / 60)} min ${Math.round(seconds % 60)} s`;
}

export function lastRunLabel(repo: Pick<Repository, "lastIndexMode" | "lastIndexDurationMs">) {
  const took = formatDuration(repo.lastIndexDurationMs);
  switch (repo.lastIndexMode) {
    case "FULL":
      return took ? `Full index in ${took}` : "Full index";
    case "INCREMENTAL":
      return took ? `Changed files re-indexed in ${took}` : "Changed files re-indexed";
    case "UP_TO_DATE":
      return "Already up to date";
    default:
      return null;
  }
}

export function providerLabel(provider: string | null | undefined) {
  if (!provider) return null;
  const p = provider.toUpperCase();
  return p === "GEMINI" ? "Gemini" : p === "OLLAMA" ? "Ollama" : "OpenAI";
}
