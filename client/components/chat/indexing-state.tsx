"use client";

import { AlertCircle, Loader2, Pause, Play, RotateCcw } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { Progress } from "@/components/ui/progress";
import { getRepoProgress, usePauseIndexing, useResumeIndexing, useStartIndexing } from "@/hooks/use-repos";
import type { IndexStatusResponse, Repository } from "@/lib/api";

export function IndexingState({
  repo,
  status,
}: {
  repo: Repository;
  status?: IndexStatusResponse;
}) {
  const indexMutation = useStartIndexing();
  const pauseMutation = usePauseIndexing();
  const resumeMutation = useResumeIndexing();
  const filesProcessed = status?.filesProcessed ?? repo.filesProcessed;
  const filesTotal = status?.filesTotal ?? repo.filesTotal;
  const chunkCount = status?.chunkCount ?? repo.chunkCount;
  const progress = getRepoProgress({ filesProcessed, filesTotal });
  const indexStatus = status?.indexStatus ?? repo.indexStatus;
  const errorMessage = status?.errorMessage ?? repo.errorMessage;

  if (indexStatus === "FAILED") {
    return (
      <Empty className="h-full border-0">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <AlertCircle className="text-destructive" />
          </EmptyMedia>
          <EmptyTitle>Indexing failed</EmptyTitle>
          <EmptyDescription>
            {errorMessage || "Something went wrong while indexing this repository."}
          </EmptyDescription>
        </EmptyHeader>
        <Button
          onClick={() => indexMutation.mutate(repo.id)}
          disabled={indexMutation.isPending}
        >
          <RotateCcw data-icon="inline-start" />
          Retry indexing
        </Button>
      </Empty>
    );
  }

  if (indexStatus === "PAUSED") {
    return (
      <Empty className="h-full border-0">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <Pause />
          </EmptyMedia>
          <EmptyTitle>Indexing paused</EmptyTitle>
          <EmptyDescription>
            {filesTotal > 0
              ? `Paused at ${filesProcessed} of ${filesTotal} files · ${chunkCount} chunks kept — resume continues where it stopped.`
              : "Indexing is paused — resume to continue."}
          </EmptyDescription>
        </EmptyHeader>
        <Button
          onClick={() => resumeMutation.mutate(repo.id)}
          disabled={resumeMutation.isPending}
        >
          <Play data-icon="inline-start" />
          Resume indexing
        </Button>
      </Empty>
    );
  }

  return (
    <Empty className="h-full border-0">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <Loader2 className="animate-spin" />
        </EmptyMedia>
        <EmptyTitle>Indexing {repo.fullName}</EmptyTitle>
        <EmptyDescription>
          {filesTotal > 0
            ? `${filesProcessed} of ${filesTotal} files · ${chunkCount} chunks embedded`
            : "Fetching repository files and preparing embeddings…"}
        </EmptyDescription>
      </EmptyHeader>
      <div className="w-full max-w-sm space-y-2">
        <Progress value={Math.max(progress, filesTotal ? progress : 12)} />
        <p className="text-center text-xs text-muted-foreground">
          You can leave this page open — chat unlocks when indexing finishes.
        </p>
        <div className="flex justify-center">
          <Button
            variant="ghost"
            size="sm"
            className="h-7 text-xs text-muted-foreground hover:text-foreground"
            onClick={() => pauseMutation.mutate(repo.id)}
            disabled={pauseMutation.isPending}
          >
            <Pause data-icon="inline-start" className="size-3" />
            Pause indexing
          </Button>
        </div>
      </div>
    </Empty>
  );
}
