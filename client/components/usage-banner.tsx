"use client";

import { CircleAlert } from "lucide-react";

import { OwnKeyButton } from "@/components/own-key-dialog";
import { useUsage } from "@/hooks/use-usage";
import { cn } from "@/lib/utils";

function left(n: number, perDay: number, noun: string) {
  if (n < 0) return `unlimited ${noun}`;
  return `${n} of ${perDay} ${noun}`;
}

/**
 * One quiet line: which model answers and what is left of today's free allowance, with
 * the optional "Use your own key" button beside it.
 */
export function UsageBanner({ kind = "both", className }: { kind?: "both" | "questions"; className?: string }) {
  const { data } = useUsage();
  if (!data) return null;

  const own = data.ownGeminiKey || data.ownOpencodeKey;

  if (!data.configured && !own) {
    return (
      <div className={cn("flex flex-col gap-3 rounded-xl border border-amber-500/30 bg-amber-500/[0.07] p-4 text-sm sm:flex-row sm:items-center sm:justify-between", className)}>
        <div className="flex items-start gap-3">
          <CircleAlert className="mt-0.5 size-4 shrink-0 text-amber-700 dark:text-amber-400" />
          <p>
            This server has no AI key yet. The owner needs to set <code className="font-mono">GEMINI_API_KEY</code>, or
            you can use your own key.
          </p>
        </div>
        <OwnKeyButton />
      </div>
    );
  }

  const outOfQuestions = data.questionsLeft === 0 && !own;
  const outOfIndexes = data.indexesLeft === 0 && kind === "both" && !data.ownGeminiKey;
  const out = outOfQuestions || outOfIndexes;

  let text: string;
  if (own) {
    text = `Using your own ${data.ownOpencodeKey ? "OpenCode Zen" : "Gemini"} key, no daily limit`;
  } else {
    const parts = [left(data.questionsLeft, data.questionsPerDay, "questions")];
    if (kind === "both") parts.push(left(data.indexesLeft, data.indexesPerDay, "index runs"));
    text = `Free today: ${parts.join(" · ")} left`;
  }

  return (
    <div
      className={cn(
        "flex items-center justify-between gap-3",
        out && "rounded-xl border border-amber-500/30 bg-amber-500/[0.07] p-3",
        className
      )}
    >
      <p className={cn("text-xs text-muted-foreground", out && "text-amber-800 dark:text-amber-300")}>
        {out ? "Today's free limit is used up. It resets at midnight Pacific, or use your own key now." : text}
        {!out && <span> · answers by {data.model}, so don&apos;t paste secrets</span>}
      </p>
      <OwnKeyButton className={cn(!out && "h-7")} />
    </div>
  );
}
