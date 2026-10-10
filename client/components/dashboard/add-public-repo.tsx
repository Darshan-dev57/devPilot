"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowRight, Loader2 } from "lucide-react";

import { useAddPublicRepo } from "@/hooks/use-repos";
import { cn } from "@/lib/utils";

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
  } else if (cleaned.includes(".") && cleaned.split("/")[0].includes(".")) {
    return null; // some other host
  }
  const segments = cleaned.split(/[/?#]/).map((s) => s.trim()).filter(Boolean);
  if (segments.length < 2) return null;
  const [owner, name] = segments;
  if (!/^[\w.-]+$/.test(owner) || !/^[\w.-]+$/.test(name)) return null;
  return { owner, name: name.replace(/\.git$/, "") };
}

const EXAMPLES = ["spring-projects/spring-petclinic", "Darshan-dev57/devPilot"];

export function AddPublicRepo({ variant = "inline" }: { variant?: "inline" | "hero" }) {
  const router = useRouter();
  const addMutation = useAddPublicRepo();
  const [value, setValue] = useState("");
  const [error, setError] = useState<string | null>(null);
  const hero = variant === "hero";

  function submit(input = value) {
    const parsed = parseRepoInput(input);
    if (!parsed) {
      setError("Use owner/name or a github.com link, e.g. facebook/react");
      return;
    }
    setError(null);
    addMutation.mutate(parsed, {
      onSuccess: () => {
        setValue("");
        if (hero) router.push("/dashboard");
      },
      onError: (err) => setError(err.message),
    });
  }

  return (
    <div>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
        className={cn(
          "flex items-center gap-2 rounded-xl border bg-card p-1.5 shadow-[0_1px_0_0_var(--border)] transition-colors focus-within:border-ring",
          hero && "p-2 shadow-[0_8px_30px_-12px_oklch(0.3_0.02_70/0.25)]"
        )}
      >
        <span className="hidden pl-2 font-mono text-sm text-muted-foreground sm:inline">github.com/</span>
        <input
          value={value}
          onChange={(e) => {
            setValue(e.target.value);
            setError(null);
          }}
          aria-label="GitHub repository"
          placeholder="owner/repo or paste a link"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          className="min-w-0 flex-1 bg-transparent px-2 py-2 font-mono text-sm outline-none placeholder:font-sans placeholder:text-muted-foreground sm:px-0"
        />
        <button
          type="submit"
          disabled={addMutation.isPending || value.trim().length === 0}
          className="inline-flex h-9 shrink-0 items-center gap-1.5 rounded-lg bg-primary px-3.5 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          {addMutation.isPending ? <Loader2 className="size-4 animate-spin" /> : null}
          Add repo
          {!addMutation.isPending && <ArrowRight className="size-4" />}
        </button>
      </form>
      {error ? (
        <p role="alert" className="mt-2 px-1 text-sm text-destructive">
          {error}
        </p>
      ) : (
        <div className="mt-3 flex flex-wrap items-center gap-2 px-1 text-xs text-muted-foreground">
          <span>Try</span>
          {EXAMPLES.map((example) => (
            <button
              key={example}
              type="button"
              onClick={() => {
                setValue(example);
                submit(example);
              }}
              className="rounded-md border border-dashed px-2 py-1 font-mono text-[11px] text-foreground/80 hover:border-solid hover:text-foreground"
            >
              {example}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
