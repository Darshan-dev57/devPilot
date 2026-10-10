import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "DevPilot",
    short_name: "DevPilot",
    description: "Chat with any public GitHub repo. Answers link to the exact lines.",
    start_url: "/",
    display: "standalone",
    background_color: "#fbfaf7",
    theme_color: "#1f1c18",
    icons: [
      { src: "/icon-192.png", sizes: "192x192", type: "image/png" },
      { src: "/icon-512.png", sizes: "512x512", type: "image/png" },
    ],
  };
}
