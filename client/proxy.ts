import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * No-login architecture: there are no accounts, so there is nothing to guard.
 *
 * This previously redirected /dashboard and /chat to /login, which 404'd because
 * the login route was removed. The auth cookie it checked was never set by
 * anything, so the entire app sat behind an unreachable redirect.
 */
export function proxy(request: NextRequest) {
  return NextResponse.next();
}
