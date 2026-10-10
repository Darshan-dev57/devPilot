"use client";

import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";

export function useUsage() {
  return useQuery({
    queryKey: queryKeys.usage,
    queryFn: api.usage,
    staleTime: 15_000,
  });
}
