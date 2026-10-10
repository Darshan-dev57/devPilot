"use client";

import { useState } from "react";
import { Check, Eye, EyeOff } from "lucide-react";

import { notifyKeyChanged, useStoredApiKey } from "@/hooks/use-api-key";
import { clearApiKeyFromStorage, saveApiKeyToStorage, type AiProvider } from "@/lib/api";
import { cn } from "@/lib/utils";

const PROVIDERS: { id: AiProvider; label: string; hint: string; href: string; placeholder: string }[] = [
  {
    id: "openai",
    label: "OpenAI",
    hint: "platform.openai.com/api-keys",
    href: "https://platform.openai.com/api-keys",
    placeholder: "sk-...",
  },
  {
    id: "gemini",
    label: "Gemini",
    hint: "aistudio.google.com/api-keys (free tier)",
    href: "https://aistudio.google.com/api-keys",
    placeholder: "AIza...",
  },
];

export function AiKeyCard() {
  const stored = useStoredApiKey();
  const [picked, setPicked] = useState<AiProvider | null>(null);
  const provider = picked ?? stored.provider;
  const [keyInput, setKeyInput] = useState("");
  const [show, setShow] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const meta = PROVIDERS.find((p) => p.id === provider)!;

  function save() {
    const key = keyInput.trim();
    if (!key) return setError("Paste a key first.");
    if (provider === "openai" && !key.startsWith("sk-")) return setError("OpenAI keys start with sk-");
    if (provider === "gemini" && key.length < 20) return setError("That looks too short for a Gemini key.");
    saveApiKeyToStorage(provider, key);
    notifyKeyChanged();
    setKeyInput("");
    setError(null);
    setSaved(true);
    setTimeout(() => setSaved(false), 2500);
  }

  return (
    <section className="rounded-xl border bg-card">
      <div className="border-b p-5">
        <h2 className="font-semibold">AI key</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          DevPilot runs on your own key, so you only pay your provider for what you use. The key is kept in this
          browser&apos;s local storage and sent with each request you make. The server uses it for that request and
          never stores it.
        </p>
      </div>

      <div className="space-y-5 p-5">
        <div role="radiogroup" aria-label="Provider" className="grid grid-cols-2 gap-2">
          {PROVIDERS.map((p) => {
            const active = stored.hasKey && stored.provider === p.id;
            return (
              <button
                key={p.id}
                role="radio"
                aria-checked={provider === p.id}
                onClick={() => {
                  setPicked(p.id);
                  setError(null);
                }}
                className={cn(
                  "rounded-lg border p-3 text-left transition-colors",
                  provider === p.id ? "border-primary bg-accent/50" : "hover:bg-secondary"
                )}
              >
                <span className="flex items-center justify-between text-sm font-medium">
                  {p.label}
                  {active && (
                    <span className="inline-flex items-center gap-1 text-[11px] font-medium text-primary">
                      <Check className="size-3" /> in use
                    </span>
                  )}
                </span>
                <span className="mt-0.5 block truncate text-[11px] text-muted-foreground">{p.hint}</span>
              </button>
            );
          })}
        </div>

        <form
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
          className="space-y-2"
        >
          <label htmlFor="ai-key" className="text-sm font-medium">
            {meta.label} API key
          </label>
          <div className="flex gap-2">
            <div className="flex min-w-0 flex-1 items-center rounded-md border bg-background focus-within:border-ring">
              <input
                id="ai-key"
                type={show ? "text" : "password"}
                autoComplete="off"
                spellCheck={false}
                placeholder={meta.placeholder}
                value={keyInput}
                onChange={(e) => {
                  setKeyInput(e.target.value);
                  setError(null);
                }}
                className="h-9 min-w-0 flex-1 bg-transparent px-3 font-mono text-sm outline-none"
              />
              <button type="button" onClick={() => setShow((v) => !v)} className="px-2 text-muted-foreground" aria-label={show ? "Hide key" : "Show key"}>
                {show ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
              </button>
            </div>
            <button type="submit" className="h-9 shrink-0 rounded-md bg-foreground px-3.5 text-sm font-medium text-background hover:opacity-90">
              {saved ? "Saved" : "Save"}
            </button>
          </div>
          {error && <p className="text-sm text-destructive">{error}</p>}
          {stored.hasKey && provider !== stored.provider && (
            <p className="text-xs text-amber-700 dark:text-amber-400">
              Repos are searched with the embeddings they were indexed with. After switching, re-index a repo
              before chatting with it.
            </p>
          )}
          <p className="text-xs text-muted-foreground">
            Get a key at{" "}
            <a href={meta.href} target="_blank" rel="noreferrer" className="underline underline-offset-4">
              {meta.hint.split(" ")[0]}
            </a>
          </p>
        </form>

        {stored.hasKey && (
          <div className="flex items-center justify-between border-t pt-4 text-sm">
            <span className="text-muted-foreground">
              {stored.provider === "gemini" ? "A Gemini" : "An OpenAI"} key is saved in this browser.
            </span>
            <button
              onClick={() => {
                clearApiKeyFromStorage();
                notifyKeyChanged();
              }}
              className="text-sm font-medium text-destructive hover:underline"
            >
              Remove
            </button>
          </div>
        )}
      </div>
    </section>
  );
}
