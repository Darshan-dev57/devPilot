"use client";

import { useEffect, useRef, useState } from "react";
import { ArrowUp, Square } from "lucide-react";

export function ChatComposer({
  disabled,
  streaming,
  onSend,
  onStop,
}: {
  disabled?: boolean;
  streaming?: boolean;
  onSend: (content: string) => void | Promise<void>;
  onStop?: () => void;
}) {
  const [value, setValue] = useState("");
  const ref = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 180)}px`;
  }, [value]);

  async function submit() {
    const content = value.trim();
    if (!content || disabled || streaming) return;
    setValue("");
    await onSend(content);
  }

  return (
    <div className="border-t bg-background/90 px-3 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] backdrop-blur sm:px-4">
      <form
        className="mx-auto flex max-w-3xl items-end gap-2 rounded-2xl border bg-card p-1.5 pl-3 transition-colors focus-within:border-ring"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <textarea
          ref={ref}
          rows={1}
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder="Ask about files, flows, design decisions…"
          disabled={disabled}
          aria-label="Your question"
          className="max-h-44 min-h-9 flex-1 resize-none bg-transparent py-2 text-[15px] leading-6 outline-none placeholder:text-muted-foreground sm:text-sm"
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              void submit();
            }
          }}
        />
        {streaming ? (
          <button
            type="button"
            onClick={onStop}
            aria-label="Stop"
            className="grid size-9 shrink-0 place-items-center rounded-xl bg-secondary text-foreground hover:bg-secondary/70"
          >
            <Square className="size-3.5 fill-current" />
          </button>
        ) : (
          <button
            type="submit"
            disabled={disabled || !value.trim()}
            aria-label="Send"
            className="grid size-9 shrink-0 place-items-center rounded-xl bg-primary text-primary-foreground transition-opacity disabled:opacity-40"
          >
            <ArrowUp className="size-4" />
          </button>
        )}
      </form>
      <p className="mx-auto mt-1.5 hidden max-w-3xl px-1 text-[11px] text-muted-foreground sm:block">
        Enter to send · Shift+Enter for a new line
      </p>
    </div>
  );
}
