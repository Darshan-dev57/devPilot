"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowUpRight, Pause, Play, RefreshCw, RotateCcw, Sparkles } from "lucide-react";

import { IndexErrorAlert } from "@/components/dashboard/index-error-alert";
import { RepoProgress } from "@/components/dashboard/repo-progress";
import { IndexStatusBadge } from "@/components/dashboard/repo-status";
import { LanguageIcon } from "@/components/icons/language-icon";
import { usePauseIndexing, useResumeIndexing, useStartIndexing } from "@/hooks/use-repos";
import type { Repository } from "@/lib/api";
import { lastRunLabel, providerLabel, shortSha } from "@/lib/format";
import { cn } from "@/lib/utils";

const secondaryButton =
  "inline-flex h-8 items-center gap-1.5 rounded-md border bg-card px-2.5 text-[13px] font-medium text-foreground transition-colors hover:bg-secondary disabled:opacity-50";
const primaryButton =
  "inline-flex h-8 items-center gap-1.5 rounded-md bg-foreground px-3 text-[13px] font-medium text-background transition-opacity hover:opacity-90 disabled:opacity-50";

export function RepoCard({ repo }: { repo: Repository }) {
  const router = useRouter();
  const indexMutation = useStartIndexing();
  const pauseMutation = usePauseIndexing();
  const resumeMutation = useResumeIndexing();
  const status = repo.indexStatus;
  const isIndexing = status === "INDEXING" || indexMutation.isPending;
  const sha = shortSha(repo.indexedSha);
  const lastRun = lastRunLabel(repo);

  return (
    <article
      className={cn(
        "flex flex-col rounded-xl border bg-card p-4 transition-colors sm:p-5",
        status === "FAILED" ? "border-destructive/30" : "hover:border-foreground/20"
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-3">
          <div className="grid size-9 shrink-0 place-items-center rounded-lg border bg-background">
            <LanguageIcon language={repo.language} size="sm" />
          </div>
          <div className="min-w-0">
            <Link
              href={status === "READY" ? `/chat/${repo.id}` : repo.htmlUrl ?? "#"}
              className="block truncate font-mono text-[13px] text-muted-foreground"
            >
              {repo.owner}/
            </Link>
            <h3 className="truncate text-base font-semibold leading-tight tracking-tight">{repo.name}</h3>
          </div>
        </div>
        <IndexStatusBadge status={status} />
      </div>

      {repo.description && status !== "INDEXING" && (
        <p className="mt-3 line-clamp-2 text-sm text-muted-foreground">{repo.description}</p>
      )}

      <div className="mt-4 flex-1">
        {isIndexing ? (
          <RepoProgress repo={repo} />
        ) : status === "FAILED" && repo.errorMessage ? (
          <IndexErrorAlert message={repo.errorMessage} />
        ) : status === "READY" ? (
          <dl className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
            <div className="flex gap-1">
              <dt className="sr-only">Commit</dt>
              <dd className="font-mono text-foreground/80">@{sha ?? repo.defaultBranch}</dd>
            </div>
            <div>
              <dt className="sr-only">Chunks</dt>
              <dd className="tabular-nums">{repo.chunkCount.toLocaleString()} chunks</dd>
            </div>
            {providerLabel(repo.embeddingProvider) && <dd>{providerLabel(repo.embeddingProvider)} embeddings</dd>}
            {lastRun && <dd>{lastRun}</dd>}
          </dl>
        ) : status === "PAUSED" ? (
          <p className="text-xs text-muted-foreground">
            Paused at {repo.filesProcessed} of {repo.filesTotal} files. Resume picks up where it stopped.
          </p>
        ) : (
          <p className="text-xs text-muted-foreground">Not indexed yet. Indexing reads the code once with your key.</p>
        )}
      </div>

      <div className="mt-4 flex items-center justify-between gap-2 border-t pt-3">
        {repo.htmlUrl ? (
          <a
            href={repo.htmlUrl}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
          >
            GitHub <ArrowUpRight className="size-3" />
          </a>
        ) : (
          <span />
        )}
        <div className="flex gap-2">
          {isIndexing ? (
            <button className={secondaryButton} onClick={() => pauseMutation.mutate(repo.id)} disabled={pauseMutation.isPending}>
              <Pause className="size-3.5" /> Pause
            </button>
          ) : status === "READY" ? (
            <>
              <button
                className={secondaryButton}
                onClick={() => indexMutation.mutate(repo.id)}
                title="Pull new commits and re-index only the files that changed"
              >
                <RefreshCw className="size-3.5" /> Update
              </button>
              <button className={primaryButton} onClick={() => router.push(`/chat/${repo.id}`)}>
                Chat
              </button>
            </>
          ) : status === "PAUSED" ? (
            <button className={primaryButton} onClick={() => resumeMutation.mutate(repo.id)} disabled={resumeMutation.isPending}>
              <Play className="size-3.5" /> Resume
            </button>
          ) : (
            <button className={primaryButton} onClick={() => indexMutation.mutate(repo.id)}>
              {status === "FAILED" ? <RotateCcw className="size-3.5" /> : <Sparkles className="size-3.5" />}
              {status === "FAILED" ? "Try again" : "Index"}
            </button>
          )}
        </div>
      </div>
    </article>
  );
}
