/**
 * A visitor's own AI keys, for going past the free daily limits. They stay in this
 * browser (localStorage) and ride along as headers on each request; the server uses
 * them for that request only and never stores or logs them.
 */
const GEMINI = "devpilot_own_gemini_key";
const OPENCODE = "devpilot_own_opencode_key";
const OPENCODE_MODEL = "devpilot_own_opencode_model";

export type OwnKeys = { gemini: string; opencode: string; opencodeModel: string };

export function getOwnKeys(): OwnKeys {
  if (typeof window === "undefined") return { gemini: "", opencode: "", opencodeModel: "" };
  return {
    gemini: localStorage.getItem(GEMINI) ?? "",
    opencode: localStorage.getItem(OPENCODE) ?? "",
    opencodeModel: localStorage.getItem(OPENCODE_MODEL) ?? "",
  };
}

export function saveOwnKeys(keys: OwnKeys) {
  const set = (name: string, value: string) =>
    value.trim() ? localStorage.setItem(name, value.trim()) : localStorage.removeItem(name);
  set(GEMINI, keys.gemini);
  set(OPENCODE, keys.opencode);
  set(OPENCODE_MODEL, keys.opencode ? keys.opencodeModel : "");
}

export function clearOwnKeys() {
  saveOwnKeys({ gemini: "", opencode: "", opencodeModel: "" });
}

export function ownKeyHeaders(): Record<string, string> {
  const keys = getOwnKeys();
  const headers: Record<string, string> = {};
  if (keys.gemini) headers["X-Gemini-Key"] = keys.gemini;
  if (keys.opencode) headers["X-OpenCode-Key"] = keys.opencode;
  if (keys.opencode && keys.opencodeModel) headers["X-OpenCode-Model"] = keys.opencodeModel;
  return headers;
}
