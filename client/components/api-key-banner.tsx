"use client";

import Link from "next/link";
import { KeyRound } from "lucide-react";

import { Button } from "@/components/ui/button";

export function ApiKeyBanner() {
  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex items-start gap-3">
        <KeyRound className="mt-0.5 size-5 shrink-0 text-amber-600 dark:text-amber-400" />
        <div>
          <p className="font-medium">Add your AI API key</p>
          <p className="text-sm text-muted-foreground">
            Indexing and chat need your own OpenAI or Gemini key. It is stored
            encrypted and only used for your requests.
          </p>
        </div>
      </div>
      <Button
        variant="outline"
        className="shrink-0"
        render={<Link href="/dashboard/settings" />}
      >
        Open Settings
      </Button>
    </div>
  );
}
