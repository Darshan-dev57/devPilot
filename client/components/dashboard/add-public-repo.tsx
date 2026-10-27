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
          hero && "rounded-2xl border-2 border-transparent bg-[var(--field,var(--secondary))] p-2 shadow-none focus-within:border-ring"
        )}
      >
        <code className={cn("hidden pl-2 text-muted-foreground sm:inline", hero ? "text-[15px]" : "text-sm")}>github.com/</code>
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
          className={cn(
            "min-w-0 flex-1 bg-transparent px-2 py-2 font-mono outline-none placeholder:font-sans placeholder:text-muted-foreground sm:px-0",
            hero ? "text-base" : "text-sm"
          )}
        />
        <button
          type="submit"
          disabled={addMutation.isPending || value.trim().length === 0}
          className={cn(
            "inline-flex shrink-0 items-center gap-1.5 bg-primary font-medium text-primary-foreground transition-[opacity,transform] hover:opacity-90 active:scale-[0.97] disabled:opacity-50",
            hero ? "h-11 rounded-xl px-4 text-[15px]" : "h-9 rounded-lg px-3.5 text-sm"
          )}
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
        <div className={cn("mt-3 flex flex-wrap items-center gap-2 px-1 text-muted-foreground", hero ? "text-sm" : "text-xs")}>
          <span>Try</span>
          {EXAMPLES.map((example) => (
            <button
              key={example}
              type="button"
              onClick={() => {
                setValue(example);
                submit(example);
              }}
              className={cn(
                "rounded-md bg-secondary px-2 py-1 text-foreground/85 transition-colors hover:bg-accent hover:text-accent-foreground",
                hero ? "text-[13px]" : "text-[11px]"
              )}
            >
              <code>{example}</code>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
