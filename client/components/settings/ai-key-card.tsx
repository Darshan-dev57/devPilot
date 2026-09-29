"use client";

import { useState } from "react";
import { CheckCircle2, KeyRound, Loader2, Trash2 } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  clearApiKeyFromStorage,
  getStoredApiKey,
  getStoredProvider,
  saveApiKeyToStorage,
  type AiProvider,
} from "@/lib/api";
import { cn } from "@/lib/utils";

const PROVIDERS: { id: AiProvider; label: string; hint: string }[] = [
  {
    id: "openai",
    label: "OpenAI",
    hint: "Get one at platform.openai.com/api-keys",
  },
  {
    id: "gemini",
    label: "Gemini",
    hint: "Get one free at aistudio.google.com/api-keys",
  },
];

export function AiKeyCard() {
  const [provider, setProvider] = useState<AiProvider>(getStoredProvider());
  const [keyInput, setKeyInput] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const configured = getStoredApiKey().length > 0;
  const activeProvider = configured ? getStoredProvider() : provider;
  const switchingProvider = configured && provider !== getStoredProvider();

  function save() {
    const key = keyInput.trim();
    if (!key) {
      setError("Enter an API key first");
      return;
    }
    if (provider === "openai" && !key.startsWith("sk-")) {
      setError("OpenAI API key must start with sk-");
      return;
    }
    if (provider === "gemini" && key.length < 10) {
      setError("That Gemini API key looks too short");
      return;
    }
    saveApiKeyToStorage(provider, key);
    setKeyInput("");
    setError(null);
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  }

  function remove() {
    clearApiKeyFromStorage();
    setError(null);
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>AI provider key</CardTitle>
        <CardDescription>
          Pick OpenAI or Gemini and paste your own key. It is stored in your
          browser only — never sent to our servers. You pay your provider
          directly based on your usage.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 gap-2">
          {PROVIDERS.map((p) => (
            <button
              key={p.id}
              type="button"
              onClick={() => {
                setProvider(p.id);
                setError(null);
              }}
              className={cn(
                "rounded-xl border p-3 text-left transition-colors",
                provider === p.id
                  ? "border-primary bg-primary/5"
                  : "border-border hover:bg-muted/50"
              )}
            >
              <span className="flex items-center gap-2 font-medium">
                {p.label}
                {configured && getStoredProvider() === p.id && (
                  <Badge variant="secondary" className="gap-1">
                    <CheckCircle2 className="size-3" />
                    Active
                  </Badge>
                )}
              </span>
              <span className="mt-1 block text-xs text-muted-foreground">
                {p.hint}
              </span>
            </button>
          ))}
        </div>

        <div className="space-y-2">
          <Label htmlFor="ai-key">
            {activeProvider === "gemini" ? "Gemini" : "OpenAI"} API key
          </Label>
          <Input
            id="ai-key"
            type="password"
            autoComplete="off"
            placeholder={provider === "gemini" ? "AIza..." : "sk-..."}
            value={keyInput}
            onChange={(e) => setKeyInput(e.target.value)}
          />
          {switchingProvider && (
            <p className="text-sm text-amber-600 dark:text-amber-400">
              Switching provider resets your repositories to unindexed — each
              provider keeps its own index and you will need to re-index.
            </p>
          )}
        </div>

        {error && <p className="text-sm text-destructive">{error}</p>}
        {saved && (
          <p className="text-sm text-green-600 dark:text-green-400">
            Key saved in this browser.
          </p>
        )}

        <div className="flex flex-col gap-3 sm:flex-row">
          <Button
            onClick={save}
            disabled={keyInput.trim().length === 0}
          >
            {saved && (
              <Loader2 data-icon="inline-start" className="animate-spin" />
            )}
            Save key
          </Button>
          {configured && (
            <Button
              variant="outline"
              onClick={remove}
            >
              <Trash2 data-icon="inline-start" />
              Remove key
            </Button>
          )}
        </div>

        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <KeyRound className="size-4" />
          {configured
            ? `Using your ${activeProvider === "gemini" ? "Gemini" : "OpenAI"} key.`
            : "No key saved yet — indexing and chat are disabled until you add one."}
        </div>
      </CardContent>
    </Card>
  );
}
