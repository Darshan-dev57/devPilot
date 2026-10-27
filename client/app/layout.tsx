import type { Metadata, Viewport } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { cn } from "@/lib/utils";
import { ThemeProvider } from "@/components/providers/theme-provider";
import QueryProvider from "@/components/providers/query-provider";
import { Toaster } from "@/components/ui/toast";

const sans = Geist({ subsets: ["latin"], variable: "--font-geist-sans" });
const mono = Geist_Mono({ subsets: ["latin"], variable: "--font-geist-mono" });

export const metadata: Metadata = {
  title: "DevPilot: chat with any GitHub repo",
  description:
    "Paste a public GitHub repository, index it once, and ask questions. Answers stream in with links to the exact lines of code.",
  applicationName: "DevPilot",
  // app/favicon.ico, app/icon.svg and app/apple-icon.png are picked up by Next itself.
  manifest: "/manifest.webmanifest",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#fafaf9" },
    { media: "(prefers-color-scheme: dark)", color: "#1b1916" },
  ],
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={cn("h-full antialiased", sans.variable, mono.variable)}
      style={{ ["--font-heading" as string]: "var(--font-geist-sans)", ["--font-display" as string]: "var(--font-geist-sans)" }}
      suppressHydrationWarning
    >
      <body className="flex min-h-full flex-col">
        <QueryProvider>
          <ThemeProvider attribute="class" defaultTheme="light" enableSystem disableTransitionOnChange>
            {/* Toasts were never mounted before, so every error toast was silently dropped. */}
            <Toaster>{children}</Toaster>
          </ThemeProvider>
        </QueryProvider>
      </body>
    </html>
  );
}
