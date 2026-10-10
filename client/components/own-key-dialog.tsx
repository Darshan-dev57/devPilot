"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { KeyRound } from "lucide-react";

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useUsage } from "@/hooks/use-usage";
import { PROVIDERS, clearOwnKeys, getOwnKeys, providerInfo, saveOwnKeys, type ChatProvider } from "@/lib/own-keys";
import { cn } from "@/lib/utils";

const field =
  "h-10 w-full rounded-md border bg-background px-3 font-mono text-sm outline-none focus-visible:ring-2 focus-visible:ring-primary/40";

/**
 * Optional: a visitor picks Gemini, OpenAI, Claude or OpenCode Zen and pastes their own
 * key to go past the free daily limits. Keys stay in this browser and are sent only with
 * their requests.
 */
export function OwnKeyButton({ className, label = "Use your own key" }: { className?: string; label?: string }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [keys, setKeys] = useState(getOwnKeys);
  const saved = getOwnKeys();
  const hasSaved = Boolean(saved.key);
  const info = providerInfo(keys.provider);
  const { data: usage } = useUsage();
  const noSharedKey = usage ? !usage.configured : false;

  const close = () => {
    setOpen(false);
    void queryClient.invalidateQueries();
  };

  const openDialog = () => {
    const current = getOwnKeys();
    setKeys({ ...current, model: current.model || providerInfo(current.provider).model });
    setOpen(true);
  };

  const pick = (provider: ChatProvider) => {
    // Keep a model the visitor typed only while they stay on the same provider.
    setKeys({ ...keys, provider, model: providerInfo(provider).model, key: provider === keys.provider ? keys.key : "" });
  };

  return (
    <>
      <button
        type="button"
        onClick={openDialog}
        className={cn(
          "inline-flex h-8 shrink-0 items-center gap-1.5 rounded-md border bg-card px-2.5 text-xs font-medium hover:bg-secondary",
          className
        )}
      >
        <KeyRound className="size-3.5" />
        {hasSaved ? `Your ${providerInfo(saved.provider).label} key` : label}
      </button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Use your own key</DialogTitle>
            <DialogDescription>
              Optional. With your own key there is no daily question limit. It stays in this browser and is sent only
              with your requests; the server never saves or logs it.
            </DialogDescription>
          </DialogHeader>

          <form
            className="grid gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              saveOwnKeys(keys);
              close();
            }}
          >
            <label className="grid gap-1.5">
              <span className="text-sm font-medium">Answers from</span>
              <select
                value={keys.provider}
                onChange={(e) => pick(e.target.value as ChatProvider)}
                className={cn(field, "font-sans")}
              >
                {PROVIDERS.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.label}
                  </option>
                ))}
              </select>
            </label>

            <label className="grid gap-1.5">
              <span className="text-sm font-medium">{info.label} API key</span>
              <input
                type="password"
                autoComplete="off"
                spellCheck={false}
                value={keys.key}
                onChange={(e) => setKeys({ ...keys, key: e.target.value })}
                placeholder={info.placeholder}
                className={field}
              />
              <span className="text-xs text-muted-foreground">
                Get one at{" "}
                <a className="underline underline-offset-2" href={info.keyUrl} target="_blank" rel="noreferrer">
                  {new URL(info.keyUrl).hostname}
                </a>
                .{keys.provider === "gemini" && " Free tier available."}
                {keys.provider === "opencode" && " Use a paid model: Zen's free models only work inside the OpenCode app."}
              </span>
            </label>

            <label className="grid gap-1.5">
              <span className="text-sm font-medium">Model</span>
              <input
                value={keys.model}
                onChange={(e) => setKeys({ ...keys, model: e.target.value })}
                placeholder={info.model}
                spellCheck={false}
                autoCapitalize="off"
                autoCorrect="off"
                className={cn(field, "h-9 text-xs")}
              />
            </label>

            {keys.provider === "gemini" ? (
              <p className="rounded-md bg-secondary/60 px-3 py-2 text-xs text-muted-foreground">
                Your Gemini key does both: indexing and answers.
              </p>
            ) : (
              <label className="grid gap-1.5">
                <span className="text-sm font-medium">
                  Gemini key for indexing <span className="font-normal text-muted-foreground">(optional)</span>
                </span>
                <input
                  type="password"
                  autoComplete="off"
                  spellCheck={false}
                  value={keys.indexGemini}
                  onChange={(e) => setKeys({ ...keys, indexGemini: e.target.value })}
                  placeholder="AIza..."
                  className={field}
                />
                <span className="text-xs text-muted-foreground">
                  {keys.provider === "openai"
                    ? noSharedKey
                      ? "This site has no shared key right now, so your OpenAI key indexes too (OpenAI embeddings). Add a Gemini key here only if you want Gemini to index instead."
                      : "Indexing runs on Gemini: your key if you add one here, otherwise the free server key (counts toward the daily index limit)."
                    : `${info.label} key answers questions only. Indexing needs embeddings, which run on Gemini: your key if you add one here, otherwise the free server key (counts toward the daily index limit).`}
                </span>
                {noSharedKey && keys.provider !== "openai" && !keys.indexGemini.trim() && (
                  <span className="rounded-md border border-amber-500/30 bg-amber-500/[0.07] px-3 py-2 text-xs text-amber-800 dark:text-amber-300">
                    This site has no shared key right now, so indexing needs a Gemini key here. Get a free one at{" "}
                    <a className="underline underline-offset-2" href="https://aistudio.google.com/apikey" target="_blank" rel="noreferrer">
                      aistudio.google.com/apikey
                    </a>
                    .
                  </span>
                )}
              </label>
            )}

            <DialogFooter className="gap-2 sm:justify-between">
              {hasSaved ? (
                <button
                  type="button"
                  onClick={() => {
                    clearOwnKeys();
                    setKeys(getOwnKeys());
                    close();
                  }}
                  className="h-9 rounded-md px-3 text-sm text-destructive hover:bg-destructive/10"
                >
                  Remove my key
                </button>
              ) : (
                <span />
              )}
              <button type="submit" className="h-9 rounded-md bg-foreground px-4 text-sm font-medium text-background hover:opacity-90">
                Save in this browser
              </button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
