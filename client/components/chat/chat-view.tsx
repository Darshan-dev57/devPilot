"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { ArrowLeft, History } from "lucide-react";

import { ChatComposer } from "@/components/chat/chat-composer";
import { ChatMessages } from "@/components/chat/chat-messages";
import { ChatSidebar } from "@/components/chat/chat-sidebar";
import { IndexingState } from "@/components/chat/indexing-state";
import { UsageBanner } from "@/components/usage-banner";
import { AppShell } from "@/components/layout/app-shell";
import { Sheet, SheetContent, SheetTitle, SheetTrigger } from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { useChatMessages, useChatSessions, useCreateChatSession, useStreamChat } from "@/hooks/use-chat";
import { useIndexStatus, useRepository } from "@/hooks/use-repos";
import { shortSha } from "@/lib/format";

export function ChatView({ repoId }: { repoId: string }) {
  const repoQuery = useRepository(repoId);
  const baseStatus = repoQuery.data?.indexStatus;
  const statusQuery = useIndexStatus(repoId, baseStatus === "INDEXING");
  const indexStatus = statusQuery.data?.indexStatus ?? baseStatus;
  const ready = indexStatus === "READY";

  // When a running index finishes, reload the repo so the header shows the new commit.
  const refetchRepo = repoQuery.refetch;
  const statusNow = statusQuery.data?.indexStatus;
  useEffect(() => {
    if (statusNow && statusNow !== "INDEXING") void refetchRepo();
  }, [statusNow, refetchRepo]);

  const sessionsQuery = useChatSessions(repoId, ready);
  const createSession = useCreateChatSession(repoId);
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const autoCreateRef = useRef(false);

  const sessionId = selectedSessionId ?? sessionsQuery.data?.[0]?.id ?? null;
  const messagesQuery = useChatMessages(sessionId);
  const { send, stop, streaming, streamText } = useStreamChat(sessionId);

  useEffect(() => {
    if (!ready || !sessionsQuery.isSuccess || (sessionsQuery.data?.length ?? 0) > 0 || autoCreateRef.current) return;
    autoCreateRef.current = true;
    createSession.mutate(undefined, {
      onSuccess: (session) => setSelectedSessionId(session.id),
      onError: () => {
        autoCreateRef.current = false;
      },
    });
  }, [ready, sessionsQuery.isSuccess, sessionsQuery.data, createSession]);

  if (repoQuery.isLoading) {
    return (
      <AppShell fullHeight>
        <div className="mx-auto w-full max-w-3xl space-y-4 p-6">
          <Skeleton className="h-8 w-1/3" />
          <Skeleton className="h-40 rounded-xl" />
        </div>
      </AppShell>
    );
  }

  if (repoQuery.isError || !repoQuery.data) {
    return (
      <AppShell>
        <div className="mx-auto flex max-w-md flex-1 flex-col items-start justify-center gap-3 p-8">
          <p className="font-medium">This repo is not available</p>
          <p className="text-sm text-muted-foreground">{(repoQuery.error as Error)?.message ?? "Repository not found"}</p>
          <Link href="/dashboard" className="text-sm font-medium text-primary underline-offset-4 hover:underline">
            Back to repos
          </Link>
        </div>
      </AppShell>
    );
  }

  const repo = {
    ...repoQuery.data,
    indexStatus: indexStatus ?? repoQuery.data.indexStatus,
  };

  const sidebar = (
    <ChatSidebar
      repo={repo}
      sessionId={sessionId}
      onSelectSession={(id) => {
        setSelectedSessionId(id);
        setHistoryOpen(false);
      }}
    />
  );

  return (
    <AppShell fullHeight>
      <div className="flex min-h-0 flex-1">
        <aside className="hidden w-72 shrink-0 border-r bg-sidebar md:block">{sidebar}</aside>

        <section className="flex min-h-0 min-w-0 flex-1 flex-col">
          <div className="flex items-center gap-2 border-b px-3 py-2 md:hidden">
            <Link href="/dashboard" aria-label="Back to repos" className="grid size-8 place-items-center rounded-md hover:bg-secondary">
              <ArrowLeft className="size-4" />
            </Link>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold leading-tight">{repo.name}</p>
              <p className="truncate font-mono text-[11px] text-muted-foreground">
                {repo.owner} @{shortSha(repo.indexedSha) ?? repo.defaultBranch}
              </p>
            </div>
            <Sheet open={historyOpen} onOpenChange={setHistoryOpen}>
              <SheetTrigger
                render={
                  <button className="inline-flex h-8 items-center gap-1.5 rounded-md border px-2.5 text-xs font-medium" />
                }
              >
                <History className="size-3.5" /> Chats
              </SheetTrigger>
              <SheetContent side="left" className="w-[85vw] max-w-xs p-0">
                <SheetTitle className="sr-only">Chat history</SheetTitle>
                {sidebar}
              </SheetContent>
            </Sheet>
          </div>

          <div className="mx-auto w-full max-w-3xl px-4 pt-4 empty:hidden">
            <UsageBanner kind="questions" />
          </div>

          {!ready ? (
            <IndexingState repo={repo} status={statusQuery.data} />
          ) : (
            <>
              <ChatMessages
                repo={repo}
                messages={messagesQuery.data ?? []}
                streamText={streamText}
                streaming={streaming}
                isLoading={messagesQuery.isLoading}
                onSuggestion={(text) => void send(text)}
              />
              <ChatComposer disabled={!sessionId} streaming={streaming} onSend={send} onStop={stop} />
            </>
          )}
        </section>
      </div>
    </AppShell>
  );
}
