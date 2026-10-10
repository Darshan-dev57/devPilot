import Link from "next/link";
import { ArrowRight, GitCommitHorizontal, Layers, MessageSquareText } from "lucide-react";

import { AddPublicRepo } from "@/components/dashboard/add-public-repo";
import { BrandMark } from "@/components/layout/app-shell";
import { FlipText } from "@/components/obsidian/flip-text";
import { ModeToggle } from "@/components/ui/mode-toggle";

const STEPS = [
  {
    icon: GitCommitHorizontal,
    title: "Paste a public repo",
    body: "DevPilot downloads it as one zip at the latest commit. No account, no OAuth, no per-file API calls.",
  },
  {
    icon: Layers,
    title: "It reads the code by structure",
    body: "Files are split at class and method boundaries, then embedded into Postgres with pgvector. New commits later? Only the changed files are re-indexed.",
  },
  {
    icon: MessageSquareText,
    title: "Ask in plain English",
    body: "Answers stream in word by word, and every source is a link to the exact lines on GitHub at the indexed commit.",
  },
];

export default function HomePage() {
  return (
    <div className="relative flex min-h-dvh flex-col">
      <header className="mx-auto flex h-14 w-full max-w-6xl items-center justify-between px-4 sm:px-6">
        <BrandMark />
        <div className="flex items-center gap-1">
          <ModeToggle />
          <Link href="/dashboard" className="rounded-md px-2.5 py-1.5 text-sm text-muted-foreground hover:text-foreground">
            Open app
          </Link>
        </div>
      </header>

      <main className="flex-1">
        <section className="relative overflow-hidden">
          <div className="paper-grid pointer-events-none absolute inset-0 opacity-70" aria-hidden />
          <div className="relative mx-auto max-w-3xl px-4 pt-14 pb-16 text-center sm:px-6 sm:pt-24 sm:pb-20">
            <p className="font-mono text-xs uppercase tracking-[0.18em] text-muted-foreground">
              Spring Boot · pgvector · RAG
            </p>
            <h1 className="mt-5 text-balance font-[family-name:var(--font-display)] text-[2.75rem] leading-[1.02] font-normal tracking-tight sm:text-7xl">
              Ask any GitHub repo{" "}
              <span className="italic text-primary">
                <FlipText loop={false} duration={1.6}>
                  how it works.
                </FlipText>
              </span>
            </h1>
            <p className="mx-auto mt-6 max-w-xl text-pretty text-base text-muted-foreground sm:text-lg">
              Paste a public repository. DevPilot reads it once, then answers your questions with links to
              the exact lines. Bring your own OpenAI or Gemini key.
            </p>
            <div className="mx-auto mt-8 max-w-xl text-left">
              <AddPublicRepo variant="hero" />
            </div>
          </div>
        </section>

        <section className="mx-auto w-full max-w-6xl px-4 pb-20 sm:px-6">
          <ol className="grid gap-px overflow-hidden rounded-xl border bg-border md:grid-cols-3">
            {STEPS.map((step, i) => (
              <li key={step.title} className="bg-card p-6">
                <div className="flex items-center gap-3">
                  <span className="font-mono text-xs text-muted-foreground">0{i + 1}</span>
                  <step.icon className="size-4 text-primary" />
                </div>
                <h2 className="mt-4 font-medium">{step.title}</h2>
                <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{step.body}</p>
              </li>
            ))}
          </ol>

          <div className="mt-10 flex flex-col items-start justify-between gap-4 rounded-xl border border-dashed p-6 sm:flex-row sm:items-center">
            <div>
              <p className="font-medium">Already added a repo?</p>
              <p className="text-sm text-muted-foreground">Every repo anyone indexed is listed, ready to chat.</p>
            </div>
            <Link
              href="/dashboard"
              className="inline-flex items-center gap-1.5 rounded-md bg-foreground px-3.5 py-2 text-sm font-medium text-background hover:opacity-90"
            >
              Browse repos <ArrowRight className="size-4" />
            </Link>
          </div>
        </section>
      </main>

      <footer className="border-t">
        <div className="mx-auto flex max-w-6xl flex-col gap-2 px-4 py-6 text-xs text-muted-foreground sm:flex-row sm:items-center sm:justify-between sm:px-6">
          <span>
            Built by Darshan R ·{" "}
            <a className="underline-offset-4 hover:underline" href="https://github.com/Darshan-dev57/devPilot">
              source on GitHub
            </a>
          </span>
          <span>
            Headline animation:{" "}
            <a className="underline-offset-4 hover:underline" href="https://www.obsidianui.dev/docs/flip-text">
              ObsidianUI FlipText
            </a>{" "}
            (MIT)
          </span>
        </div>
      </footer>
    </div>
  );
}
