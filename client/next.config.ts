import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  // Pin the tracing root to this app so stray lockfiles higher up
  // (e.g. a package-lock.json in a home directory) don't change the
  // standalone output layout.
  outputFileTracingRoot: __dirname,
};

export default nextConfig;
