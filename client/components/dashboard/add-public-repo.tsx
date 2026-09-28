"use client";

import { useState } from "react";
import { Loader2, Plus } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useAddPublicRepo } from "@/hooks/use-repos";

/**
 * Parse `owner/name`, a github.com URL, or a deeper path (tree/blob/...)
 * into an owner/name pair. Returns null when unparseable.
 */
export function parseRepoInput(value: string): { owner: string; name: string } | null {
  let cleaned = value.trim();
  if (!cleaned) return null;
  cleaned = cleaned.replace(/^https?:\/\//i, "").replace(/^www\./i, "");
  if (cleaned.toLowerCase().startsWith("github.com/")) {
    cleaned = cleaned.slice("github.com/".length);
  }
  const segments = cleaned.split("/").map((s) => s.trim()).filter(Boolean);
  if (segments.length < 2) return null;
  const [owner, name] = segments;
  if (!/^[\w.-]+$/.test(owner) || !/^[\w.-]+$/.test(name)) return null;
  return { owner, name: name.replace(/\.git$/, "") };
}

export function AddPublicRepo() {
  const addMutation = useAddPublicRepo();
  const [value, setValue] = useState("");
  const [error, setError] = useState<string | null>(null);

  function submit() {
    const parsed = parseRepoInput(value);
    if (!parsed) {
      setError("Enter as owner/name — e.g. facebook/react");
      return;
    }
    setError(null);
    addMutation.mutate(parsed, {
      onSuccess: () => setValue(""),
    });
  }

  return (
    <div className="rounded-2xl border border-dashed bg-card/60 p-4">
      <p className="font-medium">Add any public repository</p>
      <p className="mt-0.5 text-sm text-muted-foreground">
        Paste <span className="font-mono">owner/name</span> or a GitHub URL — no
        fork needed. It stays in your list across syncs.
      </p>
      <div className="mt-3 flex flex-col gap-2 sm:flex-row">
        <Input
          value={value}
          onChange={(e) => {
            setValue(e.target.value);
            setError(null);
          }}
          placeholder="facebook/react or https://github.com/facebook/react"
          className="border-dashed bg-background shadow-sm"
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              submit();
            }
          }}
        />
        <Button
          onClick={submit}
          disabled={addMutation.isPending || value.trim().length === 0}
          className="shrink-0"
        >
          {addMutation.isPending ? (
            <Loader2 data-icon="inline-start" className="animate-spin" />
          ) : (
            <Plus data-icon="inline-start" />
          )}
          Add repo
        </Button>
      </div>
      {error && <p className="mt-2 text-sm text-destructive">{error}</p>}
    </div>
  );
}
