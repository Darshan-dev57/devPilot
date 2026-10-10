import { cn } from "@/lib/utils";

/**
 * DevPilot mark: code brackets around a compass needle, for finding your way around a
 * codebase. Drawn by hand on a 32px grid so it stays readable at 16px. The tile follows
 * the theme (ink on paper, paper on ink); the needle tip is the one green accent.
 */
export function DevPilotIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden className={cn("size-7 shrink-0", className)}>
      <rect width="32" height="32" rx="7.5" className="fill-foreground" />
      <path
        d="M10.4 9.6 5.6 16l4.8 6.4M21.6 9.6 26.4 16l-4.8 6.4"
        fill="none"
        strokeWidth="2.7"
        strokeLinecap="round"
        strokeLinejoin="round"
        className="stroke-background"
      />
      <path d="M20.88 11.12 L17.66 17.66 L14.34 14.34 Z" className="fill-[#58c295]" />
      <path d="M11.12 20.88 L17.66 17.66 L14.34 14.34 Z" className="fill-background" />
    </svg>
  );
}

/** Mark plus wordmark, for the navbar and the landing page. */
export function DevPilotLogo({ className, markClassName }: { className?: string; markClassName?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-2", className)}>
      <DevPilotIcon className={markClassName} />
      <span className="text-[15px] font-semibold tracking-tight">
        Dev<span className="text-primary">Pilot</span>
      </span>
    </span>
  );
}
