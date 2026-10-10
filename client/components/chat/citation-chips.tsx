"use client";

import { FileCode2 } from "lucide-react";

import type { Citation, Repository } from "@/lib/api";

/** Link to lines of a file at the exact commit that was indexed, so line numbers match. */
export function lineHref(repo: Repository, path: string, start: number, end: number) {
  const ref = repo.indexedSha ?? repo.defaultBranch;
  return `https://github.com/${repo.fullName}/blob/${ref}/${path}#L${start}${end !== start ? `-L${end}` : ""}`;
}

/** Link to the cited lines at the exact commit that was indexed, so line numbers match. */
export function citationHref(repo: Repository, citation: Citation) {
  const ref = repo.indexedSha ?? repo.defaultBranch;
  const line =
    citation.startLine != null
      ? `#L${citation.startLine}${citation.endLine && citation.endLine !== citation.startLine ? `-L${citation.endLine}` : ""}`
      : "";
  return `https://github.com/${repo.fullName}/blob/${ref}/${citation.filePath}${line}`;
}

function label(citation: Citation) {
  const name = citation.filePath.split("/").pop() ?? citation.filePath;
  if (citation.startLine == null) return name;
  return citation.endLine && citation.endLine !== citation.startLine
    ? `${name}:${citation.startLine}-${citation.endLine}`
    : `${name}:${citation.startLine}`;
}

export function CitationChips({ repo, citations }: { repo: Repository; citations: Citation[] }) {
  if (!citations.length) return null;
  return (
    <div className="mt-3">
      <p className="mb-1.5 font-mono text-[10px] uppercase tracking-[0.14em] text-muted-foreground">Sources</p>
      <ul className="flex flex-wrap gap-1.5">
        {citations.map((citation, index) => (
          <li key={`${citation.filePath}-${index}`} className="max-w-full">
            <a
              href={citationHref(repo, citation)}
              target="_blank"
              rel="noreferrer"
              title={citation.filePath}
              className="inline-flex max-w-full items-center gap-1.5 rounded-md border bg-background px-2 py-1 font-mono text-[11.5px] text-foreground/85 transition-colors hover:border-primary/50 hover:text-primary"
            >
              <FileCode2 className="size-3 shrink-0 opacity-60" />
              <span className="truncate">{label(citation)}</span>
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
