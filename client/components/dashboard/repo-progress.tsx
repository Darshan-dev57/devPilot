"use client";

import { getRepoProgress } from "@/hooks/use-repos";
import type { Repository } from "@/lib/api";

/** Live indexing progress: the current step, files done, and a thin bar. */
export function RepoProgress({
  repo,
}: {
  repo: Pick<Repository, "filesProcessed" | "filesTotal" | "indexPhase">;
}) {
  const progress = getRepoProgress(repo);
  const counting = repo.filesTotal > 0;
  return (
    <div className="space-y-2" aria-live="polite">
      <div className="flex items-baseline justify-between gap-3 text-xs">
        <span className="flex items-center gap-2 text-foreground">
          <span className="relative flex size-2">
            <span className="absolute inline-flex size-full animate-ping rounded-full bg-primary opacity-60" />
            <span className="relative inline-flex size-2 rounded-full bg-primary" />
          </span>
          {repo.indexPhase ?? "Starting"}
        </span>
        <span className="font-mono tabular-nums text-muted-foreground">
          {counting ? `${repo.filesProcessed}/${repo.filesTotal} files` : ""}
        </span>
      </div>
      <div className="h-1 overflow-hidden rounded-full bg-secondary">
        <div
          className={counting ? "h-full rounded-full bg-primary transition-[width] duration-500" : "h-full w-1/4 animate-pulse rounded-full bg-primary/60"}
          style={counting ? { width: `${Math.max(progress, 3)}%` } : undefined}
        />
      </div>
    </div>
  );
}
