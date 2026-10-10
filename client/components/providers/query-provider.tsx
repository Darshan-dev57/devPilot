"use client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import React from "react";

import { ApiError } from "@/lib/api";

const MAX_RETRIES = 3;

/** A 4xx will not change on retry, so show it at once instead of spinning for several seconds. */
function shouldRetry(failureCount: number, error: Error) {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
  return failureCount < MAX_RETRIES;
}

const QueryProvider = ({ children }: { children: React.ReactNode }) => {
  const [query] = React.useState(
    () => new QueryClient({ defaultOptions: { queries: { retry: shouldRetry } } })
  );
  return <QueryClientProvider client={query}>{children}</QueryClientProvider>;
};

export default QueryProvider;
