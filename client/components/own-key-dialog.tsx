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
import { clearOwnKeys, getOwnKeys, saveOwnKeys } from "@/lib/own-keys";
import { cn } from "@/lib/utils";

const field =
  "h-10 w-full rounded-md border bg-background px-3 font-mono text-sm outline-none focus-visible:ring-2 focus-visible:ring-primary/40";

/**
 * Optional: a visitor pastes their own Gemini and/or OpenCode Zen key to go past the
 * free daily limits. Keys stay in this browser and are sent only with their requests.
 */
export function OwnKeyButton({ className, label = "Use your own key" }: { className?: string; label?: string }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [keys, setKeys] = useState(getOwnKeys);
  const saved = getOwnKeys();
  const hasSaved = Boolean(saved.gemini || saved.opencode);

  const close = () => {
    setOpen(false);
    void queryClient.invalidateQueries();
  };

  return (
    <>
      <button
        type="button"
        onClick={() => {
          setKeys(getOwnKeys());
          setOpen(true);
        }}
        className={cn(
          "inline-flex h-8 shrink-0 items-center gap-1.5 rounded-md border bg-card px-2.5 text-xs font-medium hover:bg-secondary",
          className
        )}
      >
        <KeyRound className="size-3.5" />
        {hasSaved ? "Your key" : label}
      </button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Use your own key</DialogTitle>
            <DialogDescription>
              Optional. With your own key there is no daily limit. It stays in this browser and is sent only with your
              requests; the server never saves or logs it.
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
              <span className="text-sm font-medium">Gemini API key</span>
              <input
                type="password"
                autoComplete="off"
                spellCheck={false}
                value={keys.gemini}
                onChange={(e) => setKeys({ ...keys, gemini: e.target.value })}
                placeholder="AIza..."
                className={field}
              />
              <span className="text-xs text-muted-foreground">
                Indexing and answers. Free from{" "}
                <a className="underline underline-offset-2" href="https://aistudio.google.com/apikey" target="_blank" rel="noreferrer">
                  Google AI Studio
                </a>
                .
              </span>
            </label>

            <label className="grid gap-1.5">
              <span className="text-sm font-medium">OpenCode Zen API key</span>
              <input
                type="password"
                autoComplete="off"
                spellCheck={false}
                value={keys.opencode}
                onChange={(e) => setKeys({ ...keys, opencode: e.target.value })}
                placeholder="sk-..."
                className={field}
              />
              <input
                value={keys.opencodeModel}
                onChange={(e) => setKeys({ ...keys, opencodeModel: e.target.value })}
                placeholder="Model, default deepseek-v4.1-flash"
                spellCheck={false}
                className={cn(field, "h-9 text-xs")}
              />
              <span className="text-xs text-muted-foreground">
                Answers only (Zen has no embeddings). Needs a paid model on the chat endpoint: Zen&apos;s free models
                only work inside the OpenCode app.
              </span>
            </label>

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
                  Remove my keys
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
