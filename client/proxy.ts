import { NextResponse } from "next/server";

/**
 * No accounts means no route guards: every page is public. Kept as a pass-through so a
 * future auth check has an obvious home.
 */
export function proxy() {
  return NextResponse.next();
}
