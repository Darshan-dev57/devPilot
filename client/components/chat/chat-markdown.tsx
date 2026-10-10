"use client";

import { code } from "@streamdown/code";
import { Streamdown } from "streamdown";

import { cn } from "@/lib/utils";

import "./chat-markdown.css";
import "streamdown/styles.css";

const streamdownPlugins = { code };

const CITE = /`?(?<![\w/.:-])((?:[\w.-]+\/)*[\w.-]+\.[A-Za-z0-9]+):(\d+)(?:-(\d+))?`?/g;

/**
 * Turns the answer's inline citations ("src/A.java:36-48") into links to those exact lines,
 * but only for files that are among the answer's sources, and never inside code blocks.
 */
export function linkCitations(markdown: string, files: string[], href: (path: string, start: number, end: number) => string) {
  if (!files.length) return markdown;
  const resolve = (path: string) => files.find((f) => f === path || f.endsWith("/" + path));
  return markdown
    .split(/(```[\s\S]*?(?:```|$))/g)
    .map((part) =>
      part.startsWith("```")
        ? part
        : part.replace(CITE, (match, path: string, a: string, b?: string) => {
            const file = resolve(path);
            if (!file) return match;
            const start = Number(a);
            const end = b ? Number(b) : start;
            const name = file.split("/").pop();
            return `[${name}:${start}${end !== start ? `-${end}` : ""}](${href(file, start, end)})`;
          })
    )
    .join("");
}

export function ChatMarkdown({
  content,
  isStreaming = false,
  className,
  sourceFiles = [],
  lineHref,
}: {
  content: string;
  isStreaming?: boolean;
  className?: string;
  /** Paths of the answer's sources; inline citations of these become links. */
  sourceFiles?: string[];
  lineHref?: (path: string, start: number, end: number) => string;
}) {
  return (
    <Streamdown
      className={cn("chat-markdown max-w-none text-sm leading-relaxed", className)}
      mode={isStreaming ? "streaming" : "static"}
      plugins={streamdownPlugins}
      shikiTheme={["github-light", "github-dark"]}
      isAnimating={isStreaming}
      // Links to the repo's own lines on GitHub open straight away; anything else asks first.
      linkSafety={{ enabled: true, onLinkCheck: (url) => url.startsWith("https://github.com/") }}
    >
      {lineHref ? linkCitations(content, sourceFiles, lineHref) : content}
    </Streamdown>
  );
}
