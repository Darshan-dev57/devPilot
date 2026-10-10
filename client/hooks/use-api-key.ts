"use client";

import { useSyncExternalStore } from "react";

import { getStoredApiKey, getStoredProvider, type AiProvider } from "@/lib/api";

const EVENT = "devpilot:key-changed";

function subscribe(onChange: () => void) {
  window.addEventListener("storage", onChange);
  window.addEventListener(EVENT, onChange);
  return () => {
    window.removeEventListener("storage", onChange);
    window.removeEventListener(EVENT, onChange);
  };
}

/** Tell open components the saved key changed (localStorage fires no event in the same tab). */
export function notifyKeyChanged() {
  window.dispatchEvent(new Event(EVENT));
}

/**
 * The visitor's saved key, read without a hydration mismatch: the server render and the
 * first client render both see "no key yet", then the real value arrives.
 */
export function useStoredApiKey(): { hasKey: boolean; provider: AiProvider; ready: boolean } {
  const key = useSyncExternalStore(subscribe, getStoredApiKey, () => null);
  const provider = useSyncExternalStore(subscribe, getStoredProvider, () => "openai" as AiProvider);
  return { hasKey: Boolean(key), provider, ready: key !== null };
}
