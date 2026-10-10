"use client";

import { AlertCircle, Pause, Play, RotateCcw, Sparkles } from "lucide-react";

import { RepoProgress } from "@/components/dashboard/repo-progress";
import { usePauseIndexing, useResumeIndexing, useStartIndexing } from "@/hooks/use-repos";
import type { IndexStatusResponse, Repository } from "@/lib/api";

const button =
  "inline-flex h-9 items-center gap-1.5 rounded-md bg-foreground px-3.5 text-sm font-medium text-background hover:opacity-90 disabled:opacity-50";

export function IndexingState({ repo, status }: { repo: Repository; status?: IndexStatusResponse }) {
  const indexMutation = useStartIndexing();
  const pauseMutation = usePauseIndexing();
  const resumeMutation = useResumeIndexing();
  const live = {
    filesProcessed: status?.filesProcessed ?? repo.filesProcessed,
    filesTotal: status?.filesTotal ?? repo.filesTotal,
    indexPhase: status?.indexPhase ?? repo.indexPhase,
  };
  const indexStatus = status?.indexStatus ?? repo.indexStatus;
  const errorMessage = status?.errorMessage ?? repo.errorMessage;

  return (
    <div className="flex flex-1 items-center justify-center px-4 py-16">
      <div className="w-full max-w-md">
        {indexStatus === "FAILED" ? (
          <>
            <AlertCircle className="size-5 text-destructive" />
            <h2 className="mt-3 text-lg font-semibold">Indexing failed</h2>
            <p className="mt-1 text-sm text-muted-foreground">{errorMessage ?? "Something went wrong."}</p>
            <button className={`${button} mt-5`} onClick={() => indexMutation.mutate(repo.id)} disabled={indexMutation.isPending}>
              <RotateCcw className="size-4" /> Try again
            </button>
          </>
        ) : indexStatus === "PAUSED" ? (
          <>
            <Pause className="size-5 text-muted-foreground" />
            <h2 className="mt-3 text-lg font-semibold">Indexing paused</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              Stopped at {live.filesProcessed} of {live.filesTotal} files. Resume continues from there.
            </p>
            <button className={`${button} mt-5`} onClick={() => resumeMutation.mutate(repo.id)} disabled={resumeMutation.isPending}>
              <Play className="size-4" /> Resume
            </button>
          </>
        ) : indexStatus === "PENDING" ? (
          <>
            <Sparkles className="size-5 text-primary" />
            <h2 className="mt-3 text-lg font-semibold">Index {repo.name} to start chatting</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              DevPilot downloads the repo once, splits it by class and method, and embeds it once. Anyone who adds the same repo later reuses that index.
            </p>
            <button className={`${button} mt-5`} onClick={() => indexMutation.mutate(repo.id)} disabled={indexMutation.isPending}>
              <Sparkles className="size-4" /> Index now
            </button>
          </>
        ) : (
          <>
            <h2 className="text-lg font-semibold">Reading {repo.fullName}</h2>
            <p className="mt-1 mb-5 text-sm text-muted-foreground">Chat opens by itself when this finishes.</p>
            <RepoProgress repo={live} />
            <button
              className="mt-4 inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground"
              onClick={() => pauseMutation.mutate(repo.id)}
              disabled={pauseMutation.isPending}
            >
              <Pause className="size-3" /> Pause
            </button>
          </>
        )}
      </div>
    </div>
  );
}
