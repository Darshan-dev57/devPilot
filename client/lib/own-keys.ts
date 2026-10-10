/**
 * A visitor's own AI keys, for going past the free daily limits. They stay in this
 * browser (localStorage) and ride along as headers on each request; the server uses
 * them for that request only and never stores or logs them.
 */
export type ChatProvider = "gemini" | "openai" | "claude" | "opencode";

export const PROVIDERS: { id: ChatProvider; label: string; model: string; placeholder: string; keyUrl: string }[] = [
  { id: "gemini", label: "Gemini", model: "gemini-3.5-flash-lite", placeholder: "AIza...", keyUrl: "https://aistudio.google.com/apikey" },
  { id: "openai", label: "OpenAI", model: "gpt-6-luna", placeholder: "sk-...", keyUrl: "https://platform.openai.com/api-keys" },
  { id: "claude", label: "Claude", model: "claude-sonnet-5-5", placeholder: "sk-ant-...", keyUrl: "https://console.anthropic.com/settings/keys" },
  { id: "opencode", label: "OpenCode Zen", model: "deepseek-v4.1-flash", placeholder: "sk-...", keyUrl: "https://opencode.ai/zen" },
];

export function providerInfo(id: string) {
  return PROVIDERS.find((p) => p.id === id) ?? PROVIDERS[0];
}

const PROVIDER = "devpilot_own_chat_provider";
const CHAT_KEY = "devpilot_own_chat_key";
const CHAT_MODEL = "devpilot_own_chat_model";
/** Gemini key for indexing when answers come from another provider. */
const INDEX_KEY = "devpilot_own_index_gemini_key";
// Older builds kept separate Gemini / OpenCode entries; read them once so nobody loses a key.
const OLD_GEMINI = "devpilot_own_gemini_key";
const OLD_OPENCODE = "devpilot_own_opencode_key";
const OLD_OPENCODE_MODEL = "devpilot_own_opencode_model";

export type OwnKeys = { provider: ChatProvider; key: string; model: string; indexGemini: string };

const EMPTY: OwnKeys = { provider: "gemini", key: "", model: "", indexGemini: "" };

export function getOwnKeys(): OwnKeys {
  if (typeof window === "undefined") return EMPTY;
  const get = (name: string) => localStorage.getItem(name) ?? "";
  if (!get(CHAT_KEY) && (get(OLD_GEMINI) || get(OLD_OPENCODE))) {
    const migrated: OwnKeys = get(OLD_OPENCODE)
      ? { provider: "opencode", key: get(OLD_OPENCODE), model: get(OLD_OPENCODE_MODEL), indexGemini: get(OLD_GEMINI) }
      : { provider: "gemini", key: get(OLD_GEMINI), model: "", indexGemini: "" };
    [OLD_GEMINI, OLD_OPENCODE, OLD_OPENCODE_MODEL].forEach((n) => localStorage.removeItem(n));
    saveOwnKeys(migrated);
  }
  const provider = providerInfo(get(PROVIDER)).id;
  return { provider, key: get(CHAT_KEY), model: get(CHAT_MODEL), indexGemini: get(INDEX_KEY) };
}

export function saveOwnKeys(keys: OwnKeys) {
  const set = (name: string, value: string) =>
    value.trim() ? localStorage.setItem(name, value.trim()) : localStorage.removeItem(name);
  const hasKey = Boolean(keys.key.trim());
  set(PROVIDER, hasKey ? keys.provider : "");
  set(CHAT_KEY, keys.key);
  set(CHAT_MODEL, hasKey ? keys.model : "");
  set(INDEX_KEY, hasKey && keys.provider !== "gemini" ? keys.indexGemini : "");
}

export function clearOwnKeys() {
  saveOwnKeys(EMPTY);
}

export function hasOwnKey() {
  return Boolean(getOwnKeys().key);
}

export function ownKeyHeaders(): Record<string, string> {
  const keys = getOwnKeys();
  const headers: Record<string, string> = {};
  if (!keys.key) return headers;
  headers["X-Chat-Provider"] = keys.provider;
  if (keys.model) headers["X-Chat-Model"] = keys.model;
  if (keys.provider === "gemini") {
    // A Gemini key does both: indexing and answers.
    headers["X-Gemini-Key"] = keys.key;
  } else {
    headers["X-Chat-Key"] = keys.key;
    if (keys.indexGemini) headers["X-Gemini-Key"] = keys.indexGemini;
  }
  return headers;
}
