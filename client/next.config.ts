import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Standalone output only for Docker builds (client/Dockerfile sets DOCKER_BUILD).
  // Vercel and local dev use the default output.
  ...(process.env.DOCKER_BUILD ? { output: "standalone" as const } : {}),
  // Pin the tracing root to this app so stray lockfiles higher up
  // (e.g. a package-lock.json in a home directory) don't change the
  // standalone output layout.
  outputFileTracingRoot: __dirname,
};

export default nextConfig;
