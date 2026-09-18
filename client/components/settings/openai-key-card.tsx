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
import { api, ApiError } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";

export function OpenAiKeyCard() {
  const queryClient = useQueryClient();
  const { data: user } = useCurrentUser();
  const [keyInput, setKeyInput] = useState("");
  const [error, setError] = useState<string | null>(null);

  const refreshUser = () =>
    queryClient.invalidateQueries({ queryKey: queryKeys.auth.all });

  const save = useMutation({
    mutationFn: (apiKey: string) => api.saveOpenAiKey(apiKey),
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
    mutationFn: () => api.deleteOpenAiKey(),
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

  const configured = user?.openaiKeySet ?? false;

  return (
    <Card>
      <CardHeader>
        <CardTitle>OpenAI API key</CardTitle>
        <CardDescription>
          DevPilot uses your own key for indexing and chat. It is stored
          encrypted and never shared.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex items-center gap-2 text-sm">
          <KeyRound className="size-4 text-muted-foreground" />
          <span className="text-muted-foreground">Status</span>
          {configured ? (
            <Badge variant="secondary" className="gap-1">
              <CheckCircle2 className="size-3.5" />
              Configured
            </Badge>
          ) : (
            <Badge variant="outline">Not set</Badge>
          )}
        </div>

        <div className="space-y-2">
          <Label htmlFor="openai-key">
            {configured ? "Replace key" : "API key"}
          </Label>
          <Input
            id="openai-key"
            type="password"
            autoComplete="off"
            placeholder="sk-..."
            value={keyInput}
            onChange={(e) => setKeyInput(e.target.value)}
          />
          <p className="text-sm text-muted-foreground">
            Get one at{" "}
            <a
              className="underline underline-offset-4"
              href="https://platform.openai.com/api-keys"
              target="_blank"
              rel="noreferrer"
            >
              platform.openai.com/api-keys
            </a>
            . Billed by OpenAI to you, based on your usage.
          </p>
        </div>

        {error && <p className="text-sm text-destructive">{error}</p>}

        <div className="flex flex-col gap-3 sm:flex-row">
          <Button
            onClick={() => save.mutate(keyInput.trim())}
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
      </CardContent>
    </Card>
  );
}
