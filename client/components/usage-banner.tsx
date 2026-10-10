"use client";

import { CircleAlert } from "lucide-react";

import { useUsage } from "@/hooks/use-usage";
import { cn } from "@/lib/utils";

function left(n: number, perDay: number, noun: string) {
  if (n < 0) return `unlimited ${noun}`;
  return `${n} of ${perDay} ${noun}`;
}

/**
 * One quiet line: which model answers and what is left of today's free allowance.
 * Visitors never paste a key; the server owner sets one.
 */
export function UsageBanner({ kind = "both", className }: { kind?: "both" | "questions"; className?: string }) {
  const { data } = useUsage();
  if (!data) return null;

  if (!data.configured) {
    return (
      <div className={cn("flex items-start gap-3 rounded-xl border border-amber-500/30 bg-amber-500/[0.07] p-4 text-sm", className)}>
        <CircleAlert className="mt-0.5 size-4 shrink-0 text-amber-700 dark:text-amber-400" />
        <p>
          This server has no AI key yet. The owner needs to set <code className="font-mono">GEMINI_API_KEY</code> (free
          from Google AI Studio) and restart it.
        </p>
      </div>
    );
  }

  const outOfQuestions = data.questionsLeft === 0;
  const outOfIndexes = data.indexesLeft === 0 && kind === "both";
  const parts = [left(data.questionsLeft, data.questionsPerDay, "questions")];
  if (kind === "both") parts.push(left(data.indexesLeft, data.indexesPerDay, "index runs"));

  return (
    <p
      className={cn(
        "text-xs text-muted-foreground",
        (outOfQuestions || outOfIndexes) && "text-amber-700 dark:text-amber-400",
        className
      )}
    >
      Free today: {parts.join(" · ")} left
      {outOfQuestions || outOfIndexes ? " · resets at midnight Pacific" : ""}
      <span> · answers by {data.model}, so don&apos;t paste secrets</span>
    </p>
  );
}
