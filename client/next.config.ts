import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Standalone output only for Docker builds (client/Dockerfile sets DOCKER_BUILD).
  // Vercel and local dev use the default output.
  ...(process.env.DOCKER_BUILD ? { output: "standalone" as const } : {}),
};

export default nextConfig;
