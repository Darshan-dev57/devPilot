"use client";

import { AiKeyCard } from "@/components/settings/ai-key-card";
import { ModeToggle } from "@/components/ui/mode-toggle";

export function SettingsDashboard() {
  return (
    <div className="mx-auto grid w-full max-w-2xl gap-4 px-4 py-8 sm:px-6 sm:py-10">
      <div className="mb-2">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Settings</h1>
        <p className="mt-1 text-sm text-muted-foreground">Your AI key and how DevPilot looks.</p>
      </div>
      <AiKeyCard />
      <section className="flex items-center justify-between rounded-xl border bg-card p-5">
        <div>
          <h2 className="font-semibold">Appearance</h2>
          <p className="mt-1 text-sm text-muted-foreground">Light, dark, or follow your device.</p>
        </div>
        <ModeToggle />
      </section>
      <p className="px-1 text-xs text-muted-foreground">
        There are no accounts. Repos you add are visible to everyone using this DevPilot server, and only public
        repos can be added.
      </p>
    </div>
  );
}
