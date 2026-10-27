import Link from "next/link";
import { ArrowRight } from "lucide-react";

import { AddPublicRepo } from "@/components/dashboard/add-public-repo";
import { BrandMark } from "@/components/layout/app-shell";
import { Reveal } from "@/components/landing/reveal";
import { ModeToggle } from "@/components/ui/mode-toggle";

/*
 * Landing page, redesigned with the Taste Skill rules (tasteskill.dev): one typeface for
 * headlines, body copy at 17px in full ink, no icon cards, no dashed boxes, and the product's
 * own interface as the pictures. Every fragment below mirrors what the app really renders
 * (the chunk header, the streamed answer, the file:line source chips).
 */

const PETCLINIC = "https://github.com/spring-projects/spring-petclinic/blob/main/";

function SourceChip({ path, lines }: { path: string; lines: string }) {
  const name = path.split("/").pop();
  return (
    <a
      href={`${PETCLINIC}${path}#L${lines.replace("-", "-L")}`}
      target="_blank"
      rel="noreferrer"
      className="chip"
      title={path}
    >
      <code>
        {name}:{lines}
      </code>
    </a>
  );
}

function AnswerFragment() {
  return (
    <figure data-ui className="ui-window" aria-label="A DevPilot answer about spring-petclinic">
      <div className="ui-bar">
        <code className="ui-path">spring-projects/spring-petclinic</code>
        <span className="ui-status">Indexed · 1f2e9c0</span>
      </div>
      <div className="ui-body">
        <p className="ui-q">Where is a new owner validated before it&apos;s saved?</p>
        <div className="ui-a">
          <p>
            In <code>OwnerController.processCreationForm</code>. The form is bound with{" "}
            <code>@Valid</code>, so Bean Validation runs first; if <code>BindingResult</code> has
            errors the controller returns the form view again instead of calling{" "}
            <code>owners.save(owner)</code>.
          </p>
          <p>
            The rules themselves live on the entity: <code>Owner</code> marks address, city and
            telephone <code>@NotBlank</code>, and the telephone must be exactly ten digits.
          </p>
        </div>
        <div className="ui-sources">
          <span>Sources</span>
          <SourceChip path="src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java" lines="77-86" />
          <SourceChip path="src/main/java/org/springframework/samples/petclinic/owner/Owner.java" lines="52-65" />
        </div>
      </div>
    </figure>
  );
}

function ZipFragment() {
  return (
    <figure data-ui className="ui-window ui-small" aria-label="Indexing progress">
      <div className="ui-bar">
        <code className="ui-path">spring-projects/spring-petclinic</code>
        <span className="ui-status">Reading files</span>
      </div>
      <div className="ui-body">
        <div className="ui-progress" aria-hidden>
          <span style={{ width: "64%" }} />
        </div>
        <ul className="ui-log">
          <li>
            <code>Downloaded main.zip at the latest commit</code>
          </li>
          <li>
            <code>Skipped binaries, images and lockfiles</code>
          </li>
          <li>
            <code>Splitting classes and methods into chunks</code>
          </li>
        </ul>
      </div>
    </figure>
  );
}

function ChunkFragment() {
  return (
    <figure data-ui className="ui-window ui-small" aria-label="A code chunk with its header">
      <div className="ui-bar">
        <code className="ui-path">VisitController.java · lines 97-108</code>
      </div>
      <pre className="ui-code">
        <code>
          <span className="ui-code-head">{"// In: class VisitController"}</span>
          {"\n"}
          {"@PostMapping(\"/owners/{ownerId}/pets/{petId}/visits/new\")\n"}
          {"public String processNewVisitForm(@ModelAttribute Owner owner,\n"}
          {"    @PathVariable int petId, @Valid Visit visit,\n"}
          {"    BindingResult result, RedirectAttributes redirectAttributes) {\n"}
          {"  if (visit.getDate() != null && !visit.getDate().isAfter(LocalDate.now())) {\n"}
          {"    result.rejectValue(\"date\", \"typeMismatch.visitDate\");\n"}
          {"  }\n"}
          {"  if (result.hasErrors()) {\n"}
          {"    return \"pets/createOrUpdateVisitForm\";\n"}
          {"  }\n"}
          {"  owner.addVisit(petId, visit);"}
        </code>
      </pre>
    </figure>
  );
}

function StreamFragment() {
  return (
    <figure data-ui className="ui-window ui-small" aria-label="An answer streaming in">
      <div className="ui-bar">
        <code className="ui-path">Ask</code>
        <span className="ui-status">Streaming</span>
      </div>
      <div className="ui-body">
        <p className="ui-q">What happens when a visit form has errors?</p>
        <p className="ui-a-line">
          The controller sends you back to <code>createOrUpdateVisitForm</code> and nothing is
          saved<span className="caret" aria-hidden />
        </p>
      </div>
    </figure>
  );
}

const STEPS = [
  {
    n: "1",
    title: "Paste a public repo",
    body: "DevPilot downloads the whole repository as one zip at the latest commit. No account, no OAuth, no per-file API calls, so even big repos come down in seconds.",
    picture: <ZipFragment />,
  },
  {
    n: "2",
    title: "It reads the code by structure",
    body: "Files are split at class and method boundaries, and every chunk keeps a header saying where it lives. Then it is embedded into Postgres with pgvector. New commits later? Only the changed files are read again.",
    picture: <ChunkFragment />,
  },
  {
    n: "3",
    title: "Ask in plain English",
    body: "Answers stream in word by word, grounded only in the chunks that matched your question. When the code doesn't say, DevPilot says so instead of guessing.",
    picture: <StreamFragment />,
  },
];

export default function HomePage() {
  return (
    <div className="landing relative flex min-h-dvh flex-col">
      <header className="frame flex h-16 w-full items-center justify-between">
        <BrandMark />
        <nav className="flex items-center gap-1" aria-label="Main">
          <ModeToggle />
          <Link href="/dashboard" className="nav-link">
            Your repos
          </Link>
        </nav>
      </header>

      <main className="flex-1">
        {/* 1. Hero: what it is, the one action, and the product itself. */}
        <section className="frame hero">
          <div className="hero-copy">
            <h1 className="hero-title rise" style={{ ["--d" as string]: "0ms" }}>
              Ask any GitHub repo how it works.
            </h1>
            <p className="hero-sub rise" style={{ ["--d" as string]: "80ms" }}>
              Paste a public repository. DevPilot reads it once, then answers your questions with
              links to the exact lines. Free, no sign-up, no key to paste.
            </p>
            <div className="hero-form rise" style={{ ["--d" as string]: "160ms" }}>
              <AddPublicRepo variant="hero" />
            </div>
          </div>
          <div className="hero-picture rise" style={{ ["--d" as string]: "240ms" }}>
            <AnswerFragment />
          </div>
        </section>

        {/* 2. How it works: a numbered story in large type, each step with its own screen. */}
        <section className="frame section" aria-labelledby="how">
          <Reveal>
            <h2 id="how" className="section-title">
              From a link to an answer in three steps
            </h2>
          </Reveal>
          <ol className="steps">
            {STEPS.map((step) => (
              <li key={step.n} className="step">
                <Reveal className="step-copy">
                  <span className="step-n" aria-hidden>
                    {step.n}
                  </span>
                  <h3 className="step-title">{step.title}</h3>
                  <p className="step-body">{step.body}</p>
                </Reveal>
                <Reveal className="step-picture" delay={90}>
                  {step.picture}
                </Reveal>
              </li>
            ))}
          </ol>
        </section>

        {/* 3. The proof: a field of ink where the citations are the subject. */}
        <section className="ink" aria-labelledby="cite">
          <div className="frame ink-inner">
            <Reveal className="ink-copy">
              <h2 id="cite" className="section-title">
                Every answer points at the lines
              </h2>
              <p className="ink-body">
                Each source is a link to the file on GitHub at the exact commit that was indexed, so
                the line numbers still match next week. Tap one and you are reading the code the
                answer came from.
              </p>
              <ul className="facts">
                <li>
                  <strong>Spring Boot</strong> backend with Server-Sent Events for streaming
                </li>
                <li>
                  <strong>pgvector</strong> for search across class and method chunks
                </li>
                <li>
                  <strong>Commit-pinned</strong> links, so a citation never drifts
                </li>
              </ul>
            </Reveal>
            <Reveal className="ink-picture" delay={90}>
              <figure data-ui className="ui-window ui-dark" aria-label="Source links on an answer">
                <div className="ui-bar">
                  <code className="ui-path">Sources</code>
                </div>
                <div className="ui-body ui-cite-list">
                  <SourceChip path="src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java" lines="77-86" />
                  <SourceChip path="src/main/java/org/springframework/samples/petclinic/owner/Owner.java" lines="52-65" />
                  <SourceChip path="src/main/java/org/springframework/samples/petclinic/owner/VisitController.java" lines="97-109" />
                  <p className="ui-cite-url">
                    <code>github.com/…/blob/1f2e9c0/…/OwnerController.java#L77-L86</code>
                  </p>
                </div>
              </figure>
            </Reveal>
          </div>
        </section>

        {/* 4. Come back: the repos you added are kept in this browser. */}
        <section className="frame section closing" aria-labelledby="back">
          <Reveal className="closing-copy">
            <h2 id="back" className="section-title">
              Your repos stay in this browser
            </h2>
            <p className="closing-body">
              Anything you add is kept on this device, ready to chat the next time you open DevPilot.
              Try it on a repo you know well and check the answers yourself.
            </p>
          </Reveal>
          <Reveal className="closing-actions" delay={90}>
            <Link href="/dashboard" className="btn-primary">
              Open your repos <ArrowRight className="size-4" aria-hidden />
            </Link>
            <a className="btn-quiet" href="https://github.com/Darshan-dev57/devPilot">
              Read the source
            </a>
          </Reveal>
        </section>
      </main>

      <footer className="footer">
        <div className="frame flex flex-col gap-2 py-8 sm:flex-row sm:items-center sm:justify-between">
          <span>Built by Darshan R in Bengaluru</span>
          <a className="underline-offset-4 hover:underline" href="https://github.com/Darshan-dev57/devPilot">
            Source on GitHub
          </a>
        </div>
      </footer>
    </div>
  );
}
