// Kept free of imports so `node --test` can load it directly (see visitor-id.test.mjs).

export const VISITOR_STORAGE_KEY = "devpilot_visitor_id";

/** The server accepts only the canonical 8-4-4-4-12 form, so the browser checks the same. */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type VisitorStore = Pick<Storage, "getItem" | "setItem">;

export function isValidVisitorId(id: string | null | undefined): id is string {
  return typeof id === "string" && UUID_PATTERN.test(id.trim());
}

export function newVisitorId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  // randomUUID needs a secure context (https or localhost); build a v4 UUID by hand otherwise.
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** The stored id, or a fresh one when it is missing or not a UUID (an old build or a hand edit). */
export function readVisitorId(store: VisitorStore): string {
  const stored = store.getItem(VISITOR_STORAGE_KEY);
  if (isValidVisitorId(stored)) return stored.trim();
  return resetVisitorId(store);
}

export function resetVisitorId(store: VisitorStore): string {
  const id = newVisitorId();
  store.setItem(VISITOR_STORAGE_KEY, id);
  return id;
}

/** The server's 400 for a bad X-Visitor-Id ("Missing or invalid visitor id. ..."). */
export function isVisitorIdRejection(status: number, message: string | null | undefined): boolean {
  return status === 400 && /visitor id/i.test(message ?? "");
}
