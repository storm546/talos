# JumpScan: design and development philosophy

This document says how JumpScan is designed and built, and why. `SPEC.md` holds the contracts (what each
part must do); this file holds the principles that decide everything the contracts do not. Every person and
every AI worker that touches the repo reads it first. When a rule here and a habit disagree, the rule wins.
When a rule here and `SPEC.md` disagree, `SPEC.md` wins for that feature and this file gets fixed.

## 1. What JumpScan is

The one app an athlete and their coach run their whole training life from, the way the owner's Hermes
Companion became the one app he manages his homelab and day with. One phone, one club server, one coach
dashboard. Jump scanning is the flagship tool; around it sit form tracking, workouts, timers, calculators,
nutrition, readiness and load, records, field tests (range of motion, balance, sprint and reaction, GPS
cardio) and an assistant, with more to come. The goal is that nothing fitness-related makes the user open a
second app. (The product will get a new name that fits this wider scope; until then it is called JumpScan.)

Borrowing is encouraged: study the best trackers and health apps, take their ideas, behaviours, formulas
and UX patterns, and reuse open-source code whose licence is compatible with GPL-3.0-or-later, with
attribution in `LICENSES-AUDIT.md`. Proprietary apps are studied, not copied: their code, assets and text
never enter the repo, because the entry is released under the GPL as the owner's own work.

Three promises hold for every tool:

1. **The phone does the seeing.** Video, images, raw landmarks and GPS routes stay on the device. The
   server stores numbers and the athlete's own words, nothing else.
2. **Numbers are honest.** Every number either has published validation behind it or says that it does
   not. "Still learning", "uncertain" and "experimental" are features, not failures.
3. **The coach sees what matters.** Anything an athlete measures can reach the coach, with consent, without
   extra work from the athlete.

What JumpScan is not: a medical device, a diagnosis, an injury-risk screen, a social network or an
engagement machine. We never write "diagnosis", "injury risk" or "screening". We write "landing
mechanics", "change against your own baseline" and "numbers to discuss with a coach".

## 2. Product principles

- **One toolkit, one platform.** Every tool reuses the same accounts, roles, consent, offline queue,
  idempotent uploads, events, audit log and design system. A new tool that needs its own login, its own
  sync or its own look is designed wrong.
- **Each tool is useful alone.** A plate calculator works with no account and no network. A timer works
  with the screen off. Sync is a bonus, never a precondition for doing the thing.
- **Your own baseline beats a population norm.** The default comparison is the athlete against their own
  rolling history, with the smallest worthwhile change and typical error deciding what counts as different.
  Population tables appear only where a published source gives them, and are labelled as such.
- **Show the reason, not just the verdict.** Every score lists what shaped it. Every warning says what to do
  next ("film from the side", "add two more check-ins").
- **Offline first, server second.** The phone queues; the server deduplicates. Losing a network must never
  lose a result or double it.
- **Depth over breadth near the deadline.** A finished, verified tool beats two half tools. A feature that
  cannot be shown working on a real phone before the defence is cut, parked behind a clear label, or
  described as future work.

## 3. Design philosophy

### 3.1 Identity: an instrument, not an app template

JumpScan looks like a precise piece of sports equipment: a timing gate, a force plate readout, a lab
notebook. The identity comes from the subject itself, the skeleton replay, the flight arc, the height
ruler, the timing strip and the live joints, and never from decoration.

Banned looks: purple or violet gradients, glassmorphism, neon, green-and-white or lime-on-forest palettes,
stock Material widgets left at their defaults, rainbow icon tiles, generic card stacks that could belong to
any app. A theme swap is not a redesign.

### 3.2 The visual system ("Instrument")

The same tokens drive the app (`app/src/main/res/values*/colors.xml`, `Instrument.kt`) and the dashboard
(`server/static/dashboard.css`). Change them in both places or not at all.

- **Ground and surfaces.** Paper (`#F3EFE7`) in light, Graphite in dark, with surface tiers that get
  lighter as they rise. Depth comes from tiers and a top-lit gradient, not from grey strokes everywhere.
  Strokes are for controls and tables.
- **One accent, signal colours for meaning only.** Timing orange (`#FF965B` signal, `#9F4114` accent on
  paper) marks the primary action and the live measurement. Teal/green means ready or good, amber means
  take care, brick red means error. A colour that carries no meaning is grey.
- **Type.** Space Grotesk for words, JetBrains Mono with tabular numerals for every number, time, unit,
  count and id. Numbers are the product, so they get the best type on the screen.
- **Shape and touch.** Rounded controls (about 11 px on the web, the app's own radius tokens on Android),
  touch targets at least 44 px on the web and 48 dp on the phone, no text smaller than 12 sp.
- **Charts.** Baseline as a dashed muted line, the athlete's series in the accent, left and right sides
  distinguished by more than colour (dash pattern or marker). Axis labels in tabular numerals.

### 3.3 Motion: nothing moves unless state moves

Motion explains a change; it never decorates idle screens. The kit lives in `InstrumentMotion.kt`
(`settle` curve 0.2/0/0/1, `pressFeedback`, `arrive`, `bump`) and the dashboard's transitions.

- Press: scale to 0.97 with a light haptic, release with a small overshoot.
- Arrival: short fade and rise (about 210 ms) when content appears, staggered in lists.
- Data changes: digits roll, gauges sweep, the flight arc draws, a new record bumps.
- Live state: the dashboard's live dot and the capture countdown show real connection and real timing.
- Every animation honours the system animator scale and `prefers-reduced-motion`. With motion off the
  screen must be complete and correct, just still.

### 3.4 Words

- **Human first, raw value second.** "Not checked in yet" before "checkin: null". Ids, versions and
  technical details go in a collapsed details row.
- **Bulgarian and English everywhere,** written natively in each language, not translated word by word.
  Every user-facing string lives in resources (`values/` and `values-bg/` in the app, the dashboard's
  string tables), never inline in code.
- **No medical claims, no shame.** "Below your usual" instead of "bad"; "take care" instead of "at risk".
  The assistant refers medical and diet-prescription questions to the coach.
- **Plain verbs on buttons.** "Save check-in", "Start timer", "Send to coach". No "Submit", no "OK" where a
  verb fits.
- **Empty states teach.** An empty screen says what the tool does and the one step that fills it.

### 3.5 Accessibility and comfort

Light and dark themes in app and dashboard; system setting by default, user override remembered. Contrast
meets WCAG AA. Every control has a label for screen readers. Focus is visible on the web. Information is
never carried by colour alone. Timers and cues work with sound, vibration and visuals, so a user who has
any one of them off still gets the signal.

### 3.6 The quality bar

The bar is the owner's Hermes Companion app: hand-made, alive, consistent. A UI change is accepted only with
screenshots of the real rendered screen, in light and dark, in Bulgarian and English, on the emulator or the
phone, and for motion a screen recording. A design critique score is recorded for larger passes. "The code
compiles" is not a UI review.

## 4. Engineering philosophy

### 4.1 Smallest correct solution

Write the least code that fully does the job. Prefer the standard library, then the platform, then one
small dependency, and only then our own abstraction. No speculative layers, no frameworks for one screen, no
configuration nobody sets. Readable names; comments only where the reason is not obvious. Delete dead code
instead of commenting it out.

"Smallest" never means skipping validation, security checks, tests or error handling. Those are part of
"correct".

### 4.2 Architecture

```
app/     Android client (Kotlin, Views, MediaPipe on device)  -> sees, measures, queues
core/    pure Kotlin/JVM maths, no Android                    -> the single source of every formula
server/  Python FastAPI + SQLite, coach dashboard             -> stores numbers, enforces scope, serves the web
```

- **Contracts first.** A feature starts as a section in `SPEC.md`: data model, API table, scope rules,
  limits, what the app and dashboard show. Code follows the contract; a change to behaviour changes the
  contract in the same commit.
- **Maths lives in `core`.** Every formula the phone uses is pure, tested Kotlin in `core`, so it runs the
  same on the phone, in the batch validator and in tests. The server recomputes or validates what it
  stores; it never trusts the client's arithmetic for anything shown to a coach.
- **UI is separate from logic.** Screens call `core` and `Api`; they do not compute scores or build JSON by
  hand.
- **SQLite, migrated idempotently at startup.** Additive columns and tables, safe to run twice, no data
  loss on upgrade. Back up the live database before every deploy.
- **Events carry ids, not data.** Server-Sent Events say "something changed"; clients reload through
  authorized REST reads.

### 4.3 Privacy and security are the default path

- Data minimisation is the architecture: what is not sent cannot leak.
- Every route has an explicit role and scope check (athlete: self; coach: own club; admin: all). A test
  proves the forbidden case, not just the allowed one.
- Every input has bounds (`input_bounds.py`); unknown fields are rejected; lists are paginated (1–500).
- Consent is recorded before any measurement is stored or shared; athletes under 18 need coach-confirmed
  consent for anything public such as the leaderboard.
- Every write that matters is audited without storing private content in the audit row.
- Athletes can export everything and delete everything (`DELETE /api/me/data`), and each new table joins
  both paths in the same change.
- Tokens in headers, never in URLs. Rate limits on login and on the assistant. Secrets live in files with
  mode 600 outside the repo, never in code, logs, commits or chat.
- The dashboard builds the DOM with `textContent`, under a strict Content Security Policy.

### 4.4 AI inside the product

- The model runs locally (the club's own server or the build kit), never a paid cloud API.
- The model phrases; the server decides. Facts, scores and digests are computed by tested code; the model
  only turns them into sentences. With no model, a plain list is shown and nothing else breaks.
- The assistant acts as the calling user with the same scoped operations as REST. It proposes writes; the
  user confirms; confirmation revalidates and applies all or nothing. User text and tool output are
  untrusted data.

### 4.5 Science and honesty

- Every score cites its method in `SPEC.md` (for example Hopkins, Foster, Williams) and implements it as
  published, with its limits shown to the user.
- **Validated vs experimental is a hard line.** Only results checked against a reference measurement are
  called validated (today: jump flight time from the front, squat counts, plank hold). Everything else is
  labelled experimental in the app, the dashboard and the documents, with its measured error where known.
- Design thresholds that have not been checked on real recordings are written down as such (in the SPEC
  section and the worker report), not presented as findings.
- Uncertain measurements are excluded and counted, never silently averaged in.
- Accuracy numbers in documents come from a reproducible run (`docs/validacia.md`, `core/VALIDATION.md`)
  with the date and the data set.

### 4.6 Testing and verification

- Tests cover the logic: every formula in `core`, every route's scope, bounds and error codes in
  `server`, and the app's key flows in the instrumentation checks (`AppChecks`).
- A bug fix starts with a test that fails for the original reason.
- **Verify the behaviour that matters in the client that matters.** A backend test does not prove a screen
  works. A feature is done when it was seen working: the emulator run with screenshots, the dashboard
  rendered, and for sensors, timers, sound, camera and GPS, a real phone.
- Never claim a test passed without running it. Report counts exactly ("627 passed, 1 skipped").
- Python tests on the shared server run under `systemd-run --scope -p MemoryMax=800M`. Android builds and
  the emulator run on the build kit only, serialised by its lock files.

### 4.7 Version control and workflow

- `stage` is the integration branch; `main` follows releases on the owner's word. Features are built on
  their own branch in a worktree under `/root/jumpscan-wt/` and merged only after verification.
- One commit, one idea, written in plain English prose that says what changed and why. No AI attribution
  lines in commits, PRs or releases.
- Never `reset --hard`, never force-push, never `git clean`. Rollback by checking out the previous commit.
- Generated output, virtual environments, keys and large data stay out of git.
- Licences are checked before a dependency or data set enters (`LICENSES-AUDIT.md`); the project is
  GPL-3.0-or-later and everything it ships must be compatible or flagged for the owner.

### 4.8 Working with AI workers

JumpScan is built by an orchestrator and bounded workers. The rules that keep that safe:

- A worker task names the objective, the allowed files or directory, the validation command, the commit
  instruction and the stop condition. Workers stay in their worktree and their files.
- Briefs carry this philosophy, the relevant `SPEC.md` section and the design bar. Briefs are honest about
  what the code is and where it came from.
- Workers report exactly what they verified and what they did not; the orchestrator reruns the tests,
  reads the diff and looks at the screenshots before merging.
- Heavy work respects host pressure: bounded concurrency, memory caps, the kit for builds.
- Only the owner adds authors. Commits by anyone else are flagged before the defence (the tournament allows
  at most two authors and one project per student).

### 4.9 Release and operations

- Every release is a signed APK with the known certificate (SHA-256 `a1e6b968…2cfc`), a version bump, the
  core and server tests green, the emulator app checks passed, then sent to the owner's phone.
- Deploy: back up the database, fast-forward the live checkout from `stage`, restart, check the site, log in
  as every demo role, read the journal. Undo is the previous commit and a restart.
- The live site stays up from registration to the end of the national round, with a working test account
  for each role. Anything that can break that (restarts, migrations) is done with a backup and a check.

## 5. Decision rules

When two good options compete, pick in this order:

1. Athlete privacy and safety.
2. Honest numbers.
3. Works offline, works on a real phone.
4. Fewer moving parts.
5. Looks and feels like an instrument.
6. More features.

And three quick tests for any change:

- **Would the jury understand it in one sentence?** If not, simplify or cut.
- **Could we show it working tomorrow on the owner's phone?** If not, it is not done.
- **Does it need its own exception to a rule above?** Then the design is probably wrong.

## 6. Definition of done

A change is done when all of these hold:

- [ ] `SPEC.md` describes it, and the code matches.
- [ ] Logic is in `core` or the server, covered by tests that ran and passed; counts are reported.
- [ ] Scope, bounds, consent, audit, export and delete-my-data cover any new data.
- [ ] Strings exist in Bulgarian and English; no medical wording.
- [ ] Light and dark, reduced motion, screen reader labels and touch targets are checked.
- [ ] The rendered screen was seen (screenshots; a real phone for sensors and timers), or the gap is named.
- [ ] Validated vs experimental labels are right.
- [ ] Licences of anything new are recorded.
- [ ] Committed on its branch with a plain-prose message, merged into `stage` after review.
- [ ] Documents (`docs/`, 5-page limit) mention it if the jury should know about it.
