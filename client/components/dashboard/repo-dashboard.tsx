"use client";

import { useMemo, useState } from "react";
import { Search } from "lucide-react";

import { AddPublicRepo } from "@/components/dashboard/add-public-repo";
import { RepoCard } from "@/components/dashboard/repo-card";
import { ApiKeyBanner } from "@/components/api-key-banner";
import { Skeleton } from "@/components/ui/skeleton";
import { useRepos } from "@/hooks/use-repos";

export function RepoDashboard() {
  const reposQuery = useRepos();
  const [search, setSearch] = useState("");
  const repos = useMemo(() => reposQuery.data ?? [], [reposQuery.data]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return repos;
    return repos.filter(
      (repo) =>
        repo.fullName.toLowerCase().includes(q) ||
        (repo.description ?? "").toLowerCase().includes(q) ||
        (repo.language ?? "").toLowerCase().includes(q)
    );
  }, [repos, search]);

  const ready = repos.filter((r) => r.indexStatus === "READY").length;
  const indexing = repos.filter((r) => r.indexStatus === "INDEXING").length;

  return (
    <div className="mx-auto w-full max-w-6xl px-4 py-8 sm:px-6 sm:py-10">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Repos</h1>
        <p className="text-sm text-muted-foreground">
          {reposQuery.isLoading
            ? "Loading…"
            : `${repos.length} added · ${ready} ready to chat${indexing ? ` · ${indexing} indexing now` : ""}`}
        </p>
      </div>

      <div className="mt-6 grid gap-4">
        <ApiKeyBanner />
        <AddPublicRepo />
      </div>

      {repos.length > 4 && (
        <label className="mt-8 flex items-center gap-2 border-b pb-2 text-sm">
          <Search className="size-4 text-muted-foreground" />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Filter by name, language or description"
            className="flex-1 bg-transparent outline-none placeholder:text-muted-foreground"
          />
        </label>
      )}

      <div className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {reposQuery.isLoading &&
          Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-44 rounded-xl" />)}
        {filtered.map((repo) => (
          <RepoCard key={repo.id} repo={repo} />
        ))}
      </div>

      {reposQuery.isError && (
        <p className="mt-6 rounded-xl border border-destructive/30 p-4 text-sm text-destructive">
          Could not reach the DevPilot server. {(reposQuery.error as Error).message}
        </p>
      )}
      {!reposQuery.isLoading && !reposQuery.isError && repos.length === 0 && (
        <div className="mt-6 rounded-xl border border-dashed p-10 text-center">
          <p className="font-medium">No repos yet</p>
          <p className="mt-1 text-sm text-muted-foreground">Paste one above. Small repos index in seconds.</p>
        </div>
      )}
      {repos.length > 0 && filtered.length === 0 && (
        <p className="mt-6 text-sm text-muted-foreground">Nothing matches “{search}”.</p>
      )}
    </div>
  );
}
