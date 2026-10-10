"use client";

import { formatDistanceToNow } from "date-fns";
import { Plus } from "lucide-react";

import { Skeleton } from "@/components/ui/skeleton";
import { useChatSessions, useCreateChatSession } from "@/hooks/use-chat";
import type { Repository } from "@/lib/api";
import { lastRunLabel, shortSha } from "@/lib/format";
import { cn } from "@/lib/utils";

/** Chat history for one repo. Rendered as a side column on desktop and inside a sheet on phones. */
export function ChatSidebar({
  repo,
  sessionId,
  onSelectSession,
}: {
  repo: Repository;
  sessionId: string | null;
  onSelectSession: (id: string) => void;
}) {
  const ready = repo.indexStatus === "READY";
  const sessionsQuery = useChatSessions(repo.id, ready);
  const createSession = useCreateChatSession(repo.id);
  const lastRun = lastRunLabel(repo);

  return (
    <div className="flex h-full flex-col">
      <div className="border-b p-4">
        <p className="font-mono text-xs text-muted-foreground">{repo.owner}/</p>
        <p className="truncate font-semibold tracking-tight">{repo.name}</p>
        <p className="mt-2 text-xs text-muted-foreground">
          <span className="font-mono text-foreground/80">@{shortSha(repo.indexedSha) ?? repo.defaultBranch}</span>
          {" · "}
          {repo.chunkCount.toLocaleString()} chunks
        </p>
        {lastRun && <p className="text-xs text-muted-foreground">{lastRun}</p>}
      </div>
      <div className="p-3">
        <button
          disabled={!ready || createSession.isPending}
          onClick={() => createSession.mutate("New chat", { onSuccess: (session) => onSelectSession(session.id) })}
          className="flex h-9 w-full items-center justify-center gap-1.5 rounded-md border bg-card text-sm font-medium hover:bg-secondary disabled:opacity-50"
        >
          <Plus className="size-4" /> New chat
        </button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-4">
        <p className="px-2 pb-1 font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">History</p>
        {sessionsQuery.isLoading && <Skeleton className="mx-2 h-8 rounded-md" />}
        <ul className="space-y-0.5">
          {sessionsQuery.data?.map((session) => (
            <li key={session.id}>
              <button
                onClick={() => onSelectSession(session.id)}
                className={cn(
                  "w-full rounded-md px-2 py-2 text-left text-sm transition-colors",
                  session.id === sessionId ? "bg-secondary font-medium" : "text-muted-foreground hover:bg-secondary/60 hover:text-foreground"
                )}
              >
                <span className="block truncate">{session.title}</span>
                <span className="block text-[11px] text-muted-foreground">
                  {formatDistanceToNow(new Date(session.createdAt), { addSuffix: true })}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
