import {
  getApiBaseUrl,
  getVisitorId,
  renewVisitorId,
  ApiError,
  type ChatMessage,
} from "@/lib/api";
import { ownKeyHeaders } from "@/lib/own-keys";
import { isVisitorIdRejection } from "@/lib/visitor-id";

async function errorMessage(res: Response): Promise<string> {
  try {
    const data = await res.json();
    return data.message ?? data.error ?? res.statusText;
  } catch {
    return res.statusText;
  }
}

export type StreamChatHandlers = {
  onUserMessage?: (message: ChatMessage) => void;
  onToken?: (token: string) => void;
  onAssistantMessage?: (message: ChatMessage) => void;
  onDone?: () => void;
  onError?: (error: Error) => void;
  signal?: AbortSignal;
};

export async function streamChatMessage(
  sessionId: string,
  content: string,
  handlers: StreamChatHandlers = {}
): Promise<void> {
  // Streaming uses raw fetch rather than apiFetch because the response body is
  // consumed as a stream, not as JSON. The visitor's own keys ride along only if set.
  const send = () =>
    fetch(`${getApiBaseUrl()}/api/chat/sessions/${sessionId}/messages`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Visitor-Id": getVisitorId(),
        ...ownKeyHeaders(),
      },
      body: JSON.stringify({ content }),
      signal: handlers.signal,
    });

  let res = await send();
  let message = res.ok ? "" : await errorMessage(res);
  if (!res.ok && isVisitorIdRejection(res.status, message)) {
    // Rejected before anything ran; send once more with a fresh visitor id.
    renewVisitorId();
    res = await send();
    message = res.ok ? "" : await errorMessage(res);
  }
  if (!res.ok) {
    throw new ApiError(res.status, message);
  }

  if (!res.body) {
    throw new Error("No response body for SSE stream");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split("\n\n");
    buffer = parts.pop() ?? "";

    for (const part of parts) {
      if (!part.trim()) continue;

      const lines = part.split("\n");
      let event = "message";
      const dataLines: string[] = [];

      for (const line of lines) {
        if (line.startsWith("event:")) {
          event = line.slice(6).trim();
        } else if (line.startsWith("data:")) {
          dataLines.push(line.slice(5).trimStart());
        }
      }

      const data = dataLines.join("\n");
      if (!data) continue;

      if (event === "error") {
        let message = "AI request failed";
        try {
          const parsed = JSON.parse(data) as { message?: string };
          if (parsed.message) message = parsed.message;
        } catch {
          if (data) message = data;
        }
        throw new ApiError(502, message);
      }

      try {
        if (event === "token") {
          handlers.onToken?.(JSON.parse(data) as string);
        } else if (event === "user_message") {
          handlers.onUserMessage?.(JSON.parse(data) as ChatMessage);
        } else if (event === "assistant_message") {
          handlers.onAssistantMessage?.(JSON.parse(data) as ChatMessage);
        } else if (event === "done") {
          handlers.onDone?.();
        }
      } catch (err) {
        handlers.onError?.(
          err instanceof Error ? err : new Error("Failed to parse SSE event")
        );
      }
    }
  }

  handlers.onDone?.();
}
