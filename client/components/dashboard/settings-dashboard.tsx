"use client";

import { Moon, Sun } from "lucide-react";
import { useTheme } from "next-themes";

import { ModeToggle } from "@/components/ui/mode-toggle";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import { Switch } from "@/components/ui/switch";
import { AiKeyCard } from "@/components/settings/ai-key-card";
import {
  clearApiKeyFromStorage,
  getStoredApiKey,
  getStoredProvider,
} from "@/lib/api";

export function SettingsDashboard() {
  const { theme, setTheme, resolvedTheme } = useTheme();
  const isDark = resolvedTheme === "dark";
  const hasKey = getStoredApiKey().length > 0;
  const provider = getStoredProvider();

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 p-4 md:p-6">
      <Card>
        <CardHeader>
          <CardTitle>AI provider key</CardTitle>
          <CardDescription>
            Your key is stored in this browser only — never sent to our servers.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-3 text-sm">
            <div className="flex items-center justify-between gap-3">
              <span className="text-muted-foreground">Provider</span>
              <span className="font-medium capitalize">{provider}</span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <span className="text-muted-foreground">Key status</span>
              <span className="font-medium">
                {hasKey ? "Saved in browser" : "Not set"}
              </span>
            </div>
          </div>
          {hasKey && (
            <>
              <Separator />
              <Button
                variant="destructive"
                className="justify-start"
                onClick={() => {
                  clearApiKeyFromStorage();
                  window.location.reload();
                }}
              >
                Remove saved key
              </Button>
            </>
          )}
        </CardContent>
      </Card>

      <AiKeyCard />

      <Card>
        <CardHeader>
          <CardTitle>Appearance</CardTitle>
          <CardDescription>
            Customize how DevPilot looks on your device.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center justify-between gap-4">
            <div className="space-y-1">
              <Label htmlFor="dark-mode">Dark mode</Label>
              <p className="text-sm text-muted-foreground">
                Switch between light and dark themes.
              </p>
            </div>
            <div className="flex items-center gap-3">
              <Sun className="size-4 text-muted-foreground" />
              <Switch
                id="dark-mode"
                checked={isDark}
                onCheckedChange={(checked) =>
                  setTheme(checked ? "dark" : "light")
                }
              />
              <Moon className="size-4 text-muted-foreground" />
            </div>
          </div>

          <Separator />

          <div className="flex items-center justify-between gap-4">
            <div className="space-y-1">
              <Label>Theme selector</Label>
              <p className="text-sm text-muted-foreground">
                Current theme: {theme ?? "system"}
              </p>
            </div>
            <ModeToggle />
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
