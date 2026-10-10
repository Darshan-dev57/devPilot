import { redirect } from "next/navigation";

/** The old overview page duplicated the repo list; send visitors to the list itself. */
export default function OverviewPage() {
  redirect("/dashboard");
}
