import type { IndexStatus } from "@/lib/api";
import { cn } from "@/lib/utils";

export function indexStatusLabel(status: IndexStatus) {
  switch (status) {
    case "READY":
      return "Ready";
    case "INDEXING":
      return "Indexing";
    case "PAUSED":
      return "Paused";
    case "FAILED":
      return "Failed";
    default:
      return "Not indexed";
  }
}

const TONE: Record<IndexStatus, string> = {
  READY: "text-primary bg-accent",
  INDEXING: "text-foreground bg-secondary",
  PAUSED: "text-amber-700 bg-amber-500/10 dark:text-amber-300",
  FAILED: "text-destructive bg-destructive/10",
  PENDING: "text-muted-foreground bg-secondary",
};

export function IndexStatusBadge({ status, className }: { status: IndexStatus; className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex shrink-0 items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-medium",
        TONE[status],
        className
      )}
    >
      <span className={cn("size-1.5 rounded-full bg-current", status === "INDEXING" && "animate-pulse")} />
      {indexStatusLabel(status)}
    </span>
  );
}
