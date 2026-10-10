"use client";

import { AppShell } from "@/components/layout/app-shell";
import { SettingsDashboard } from "@/components/dashboard/settings-dashboard";

export default function SettingsPage() {
  return (
    <AppShell hideHeader>
      <SettingsDashboard />
    </AppShell>
  );
}
