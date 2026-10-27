"use client";

import { useEffect, useRef, useState } from "react";

import { DevPilotIcon } from "@/components/icons/devpilot-icon";
import { ChatMarkdown } from "@/components/chat/chat-markdown";
import { CitationChips, lineHref } from "@/components/chat/citation-chips";
import { Skeleton } from "@/components/ui/skeleton";
import type { ChatMessage, Repository } from "@/lib/api";

const SUGGESTIONS = [
  "Give me a two-minute tour of this codebase",
  "Where does a request enter, and what happens next?",
  "How is the project built, tested and deployed?",
];

export function ChatMessages({
  repo,
  messages,
  streamText,
  isLoading,
  streaming,
  onSuggestion,
}: {
  repo: Repository;
  messages: ChatMessage[];
  streamText?: string;
  isLoading?: boolean;
  streaming?: boolean;
  onSuggestion?: (text: string) => void;
}) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: streaming ? "auto" : "smooth", block: "end" });
  }, [messages, streamText, streaming]);

  if (isLoading) {
    return (
      <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-4 px-4 py-8">
        <Skeleton className="ml-auto h-10 w-1/2 rounded-2xl" />
        <Skeleton className="h-28 w-3/4 rounded-2xl" />
      </div>
    );
  }

  return (
    <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-8 px-4 py-6 sm:py-10">
        {messages.length === 0 && !streamText && !streaming && (
          <div className="py-6 sm:py-12">
            <p className="text-3xl font-semibold leading-tight tracking-tight sm:text-4xl">
              What do you want to know about <span className="italic text-primary">{repo.name}</span>?
            </p>
            <p className="mt-3 text-sm text-muted-foreground">
              Answers come only from the indexed code, with links to the lines they used.
            </p>
            <div className="mt-6 grid gap-2">
              {SUGGESTIONS.map((s) => (
                <button
                  key={s}
                  onClick={() => onSuggestion?.(s)}
                  className="rounded-lg border bg-card px-3.5 py-3 text-left text-sm transition-colors hover:border-primary/40 hover:bg-accent/40"
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map((message) =>
          message.role === "USER" ? (
            <UserBubble key={message.id} text={message.content} />
          ) : (
            <AssistantBlock key={message.id}>
              <ChatMarkdown
                content={message.content}
                sourceFiles={(message.citations ?? []).map((c) => c.filePath)}
                lineHref={(path, start, end) => lineHref(repo, path, start, end)}
              />
              <CitationChips repo={repo} citations={message.citations ?? []} />
            </AssistantBlock>
          )
        )}

        {streaming && !streamText && <Thinking />}
        {streamText && (
          <AssistantBlock>
            <ChatMarkdown content={streamText} isStreaming />
          </AssistantBlock>
        )}
        <div ref={bottomRef} className="h-px" />
      </div>
    </div>
  );
}

function UserBubble({ text }: { text: string }) {
  return (
    <div className="flex justify-end">
      <p className="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-md bg-foreground px-4 py-2.5 text-sm text-background">
        {text}
      </p>
    </div>
  );
}

function AssistantBlock({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex gap-3">
      <DevPilotIcon className="mt-0.5 size-6" />
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  );
}

const STAGES = ["Searching the code", "Reading the matches", "Writing"];

function Thinking() {
  const [stage, setStage] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => setStage((s) => Math.min(s + 1, STAGES.length - 1)), 1500);
    return () => clearInterval(timer);
  }, []);
  return (
    <AssistantBlock>
      <p className="flex items-center gap-2 pt-0.5 text-sm text-muted-foreground">
        <span className="flex gap-1" aria-hidden>
          {[0, 1, 2].map((i) => (
            <span key={i} className="size-1 animate-bounce rounded-full bg-current" style={{ animationDelay: `${i * 120}ms` }} />
          ))}
        </span>
        {STAGES[stage]}…
      </p>
    </AssistantBlock>
  );
}
