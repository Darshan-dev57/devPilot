"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
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
import { useCurrentUser } from "@/hooks/use-auth";
import { api, ApiError, type AiProvider } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";
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
  const queryClient = useQueryClient();
  const { data: user } = useCurrentUser();
  const [provider, setProvider] = useState<AiProvider>(
    user?.aiProvider ?? "openai"
  );
  const [keyInput, setKeyInput] = useState("");
  const [error, setError] = useState<string | null>(null);

  const refreshUser = () =>
    queryClient.invalidateQueries({ queryKey: queryKeys.auth.all });

  const save = useMutation({
    mutationFn: ({ p, apiKey }: { p: AiProvider; apiKey: string }) =>
      api.saveAiKey(p, apiKey),
    onSuccess: async () => {
      setKeyInput("");
      setError(null);
      await refreshUser();
    },
    onError: (err) => {
      setError(
        err instanceof ApiError ? err.message : "Could not save the key"
      );
    },
  });

  const remove = useMutation({
    mutationFn: () => api.deleteAiKey(),
    onSuccess: async () => {
      setError(null);
      await refreshUser();
    },
    onError: (err) => {
      setError(
        err instanceof ApiError ? err.message : "Could not remove the key"
      );
    },
  });

  const configured = user?.aiKeySet ?? false;
  const activeProvider = user?.aiProvider ?? provider;
  const switchingProvider = configured && provider !== user?.aiProvider;

  return (
    <Card>
      <CardHeader>
        <CardTitle>AI provider key</CardTitle>
        <CardDescription>
          Pick OpenAI or Gemini and paste your own key. It is stored encrypted
          and only used for your indexing and chat. You pay your provider
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
                {configured && user?.aiProvider === p.id && (
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

        <div className="flex flex-col gap-3 sm:flex-row">
          <Button
            onClick={() => save.mutate({ p: provider, apiKey: keyInput.trim() })}
            disabled={save.isPending || keyInput.trim().length === 0}
          >
            {save.isPending && (
              <Loader2 data-icon="inline-start" className="animate-spin" />
            )}
            Save key
          </Button>
          {configured && (
            <Button
              variant="outline"
              onClick={() => remove.mutate()}
              disabled={remove.isPending}
            >
              {remove.isPending ? (
                <Loader2 data-icon="inline-start" className="animate-spin" />
              ) : (
                <Trash2 data-icon="inline-start" />
              )}
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
