import Link from "next/link";
import { ArrowRight, FolderGit2, MessageSquareCode, Sparkles } from "lucide-react";

import { DevPilotIcon } from "@/components/icons/devpilot-icon";
import { BrandMark } from "@/components/layout/app-shell";
import { ModeToggle } from "@/components/ui/mode-toggle";
import { buttonVariants } from "@/components/ui/button";

import { cn } from "@/lib/utils";
import { getGithubLoginUrl, getGoogleLoginUrl } from "@/lib/api";

export default function HomePage() {
  return (
    <div className="relative min-h-svh overflow-hidden">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_top,oklch(from_var(--primary)_l_c_h/0.12),transparent_55%)]" />
      <header className="relative z-10 mx-auto flex h-14 w-full max-w-5xl items-center justify-between px-4">
        <BrandMark />
        <div className="flex items-center gap-2">
          <ModeToggle />
          <Link
            href="/login"
            className={cn(buttonVariants({ variant: "ghost", size: "sm" }))}
          >
            Sign in
          </Link>
        </div>
      </header>

      <main className="relative z-10 mx-auto flex w-full max-w-5xl flex-col gap-16 px-4 py-16 md:py-24">
        <section className="mx-auto max-w-2xl space-y-6 text-center">
          <div className="mx-auto flex size-14 items-center justify-center rounded-2xl shadow-sm">
            <DevPilotIcon className="size-14 rounded-2xl" />
          </div>
          <div className="space-y-3">
            <h1 className="font-heading text-4xl font-semibold tracking-tight sm:text-5xl">
              DevPilot
            </h1>
            <p className="text-lg text-muted-foreground text-balance">
              Connect GitHub, index any repository, and chat with your codebase
              using retrieval-augmented answers and citations.
            </p>
          </div>
          <div className="flex flex-wrap items-center justify-center gap-3">
            <a
              href={getGithubLoginUrl()}
              className={cn(
                buttonVariants({ size: "lg" }),
                "inline-flex items-center gap-1.5"
              )}
            >
              <FolderGit2 className="size-4" />
              Continue with GitHub
              <ArrowRight className="size-4" />
            </a>
            <a
              href={getGoogleLoginUrl()}
              className={cn(
                buttonVariants({ size: "lg", variant: "outline" }),
                "inline-flex items-center gap-1.5"
              )}
            >
              <svg className="size-4" viewBox="0 0 24 24" aria-hidden>
                <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
                <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
                <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/>
                <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
              </svg>
              Continue with Google
            </a>
            <Link
              href="/login"
              className={cn(buttonVariants({ variant: "outline", size: "lg" }))}
            >
              See how it works
            </Link>
          </div>
        </section>

        <section className="grid gap-4 md:grid-cols-3">
          {[
            {
              title: "Connect GitHub",
              body: "OAuth with repo scope for public and private repositories.",
              icon: FolderGit2,
            },
            {
              title: "Index with RAG",
              body: "Chunk and embed your code into Postgres + pgvector.",
              icon: Sparkles,
            },
            {
              title: "Ask anything",
              body: "Get grounded answers with clickable source citations.",
              icon: MessageSquareCode,
            },
          ].map((item) => (
            <div
              key={item.title}
              className="rounded-2xl border bg-card/80 p-5 shadow-xs backdrop-blur"
            >
              <div className="mb-4 flex size-10 items-center justify-center rounded-xl bg-muted">
                <item.icon className="size-5 text-foreground" />
              </div>
              <h2 className="font-medium">{item.title}</h2>
              <p className="mt-1.5 text-sm text-muted-foreground">{item.body}</p>
            </div>
          ))}
        </section>
      </main>
    </div>
  );
}
