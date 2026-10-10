"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { DevPilotLogo } from "@/components/icons/devpilot-icon";
import { ModeToggle } from "@/components/ui/mode-toggle";
import { cn } from "@/lib/utils";

export function BrandMark({ className }: { className?: string }) {
  return (
    <Link href="/" aria-label="DevPilot home" className={cn("group inline-flex items-center", className)}>
      <DevPilotLogo />
    </Link>
  );
}

const NAV = [
  { href: "/dashboard", label: "Repos" },
];

export function SiteHeader({ className, wide = false }: { className?: string; wide?: boolean }) {
  const pathname = usePathname();
  return (
    <header
      className={cn(
        "sticky top-0 z-30 border-b bg-background/85 backdrop-blur supports-[backdrop-filter]:bg-background/70",
        className
      )}
    >
      <div className={cn("mx-auto flex h-14 w-full items-center justify-between gap-4 px-4 sm:px-6", !wide && "max-w-6xl")}>
        <BrandMark />
        <nav className="flex items-center gap-1">
          {NAV.map((item) => {
            const active =
              item.href === "/dashboard"
                ? pathname === "/dashboard" || pathname.startsWith("/chat")
                : pathname.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                className={cn(
                  "rounded-md px-2.5 py-1.5 text-sm transition-colors",
                  active ? "bg-secondary font-medium text-foreground" : "text-muted-foreground hover:text-foreground"
                )}
              >
                {item.label}
              </Link>
            );
          })}
          <span className="mx-1 h-5 w-px bg-border" aria-hidden />
          <ModeToggle />
        </nav>
      </div>
    </header>
  );
}

/**
 * Page frame: a slim top bar and a centred column. The optional title block keeps the
 * same props the pages already pass (title, description, actions).
 */
export function AppShell({
  children,
  title,
  description,
  actions,
  hideHeader = false,
  fullHeight = false,
}: {
  children: React.ReactNode;
  title?: string;
  description?: string;
  actions?: React.ReactNode;
  hideHeader?: boolean;
  fullHeight?: boolean;
}) {
  return (
    <div className={cn("flex flex-col", fullHeight ? "h-dvh" : "min-h-dvh")}>
      <SiteHeader wide={fullHeight} />
      <main className={cn("flex w-full flex-1 flex-col", fullHeight && "min-h-0")}>
        {!hideHeader && title && (
          <div className="mx-auto flex w-full max-w-6xl flex-wrap items-end justify-between gap-3 px-4 pt-8 sm:px-6">
            <div className="min-w-0">
              <h1 className="truncate text-2xl font-semibold tracking-tight">{title}</h1>
              {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
            </div>
            {actions}
          </div>
        )}
        {children}
      </main>
    </div>
  );
}
