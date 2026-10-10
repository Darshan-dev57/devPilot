"use client";

import Link from "next/link";
import { KeyRound } from "lucide-react";

import { useStoredApiKey } from "@/hooks/use-api-key";

export function ApiKeyBanner() {
  const { hasKey, ready } = useStoredApiKey();
  if (!ready || hasKey) return null;

  return (
    <div className="flex flex-col gap-3 rounded-xl border border-amber-500/30 bg-amber-500/[0.07] p-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex items-start gap-3">
        <KeyRound className="mt-0.5 size-4 shrink-0 text-amber-700 dark:text-amber-400" />
        <div>
          <p className="text-sm font-medium">Add your OpenAI or Gemini key first</p>
          <p className="mt-0.5 text-sm text-muted-foreground">
            Indexing and chat run on your own key. It stays in this browser and is sent only with your requests.
          </p>
        </div>
      </div>
      <Link
        href="/dashboard/settings"
        className="inline-flex h-8 shrink-0 items-center justify-center rounded-md border bg-card px-3 text-sm font-medium hover:bg-secondary"
      >
        Add key
      </Link>
    </div>
  );
}
