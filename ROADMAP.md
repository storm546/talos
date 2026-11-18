# JumpScan: from core to a competition entry

Target: National Autumn IT Tournament "John Atanasov", Distributed Applications (grades 11–12).
Registration closes 29.11.2026, defence in Sofia on 5.12.2026. Scoring is out of 100:
General 20, Design 25, Implementation 35, Presentation and defence 20.

## The idea in one paragraph

A sports club gets landing-mechanics testing without a lab. An athlete films a jump with a phone; the
phone finds the body pose and computes the numbers on the device; only the numbers go to the club's
server. The coach sees every athlete's history and how each one changes against their own baseline.
The video never leaves the phone. The project is honest about accuracy: it publishes its own validation
against laboratory motion capture and force plates, and presents the numbers as a way to track change,
not as a medical measurement.

## Why this can score well

| What the jury scores | What JumpScan shows |
|---|---|
| Concrete problem, originality (General) | Clubs cannot afford lab screening; phone-only, privacy-first, with published validation |
| Client and server technology, security, confidentiality, interoperability (category focus) | Android client with on-device ML, REST API with roles, token auth, rate limits, audit log, consent records, data export, OpenAPI description |
| Architecture, UI separated from logic (Design) | Three parts with written contracts: `core` (pure maths, tested), `app`, `server`; the same analyzer runs on the phone and in the batch validator |
| Code quality, tests, version control, error handling, attack protection (Implementation) | 250+ server tests, 37 core tests including real-video fixtures, CI on every push, security review with fixes recorded |
| Evidence of testing (Presentation) | Accuracy table against the University of Twente lab dataset (91 jumps, 9 subjects), detection rates on 1000 public clips, own filmed jumps |
| Language norms, UI (Implementation) | Bulgarian and English interface in app and dashboard |

The strongest single asset is the validation: most school projects cannot say how wrong they are. Ours can.

## Honest state of accuracy (2026-11-17, nine lab subjects)

- Detection (rules plus learned cross-check, each subject held out): from the front 64 of 91 jumps get a
  number without a warning and none of them is wrong; the 7 wrong ones carry a warning; 20 get suggested
  frames. From the side 41 without a warning, 3 of them wrong.
- Flight time: front mean error 32 ms (about one frame at 33 fps); side flights are about 87 ms long
  because the pose lags at landing.
- Knee flexion: front RMSE 17.3 degrees with the range compressed (slope 0.65); side, image plane, 10.0.
- A server-side model (RTMPose-x) reaches about 5 degrees from the side; it does not help from the front.
- On 1000 public webcam clips: a flight is found in 45 to 60 percent of jump clips; false alarms 3 to 7 percent.

These numbers decide the wording of the product: "change against your own baseline", never "risk".

## Work packages

Status key: done, running, staged (built but not reviewed or not deployed), todo.

### A. Measurement core
- done: knee flexion, frontal-plane angle, knee/ankle ratio, asymmetry, flight time, jump height; heavy pose model
- done: validation against Twente; batch scorer
- done: side view with one measured leg and image-plane angle; detector fixes; nine-subject benchmark (182 clips)
- done: learned take-off/landing detector as a cross-check and frame suggester (16 289 weights, plain Kotlin)
- todo: final RTMPose report; YouTube slow-motion benchmark

### B. Club server
- done: auth, roles, jumps, delete-my-data, validation, rate limit, coach dashboard v1
- done: consent record per athlete; baseline and change summary; CSV and JSON export; audit log; club and user administration; OpenAPI page
- done: club platform — groups, sessions with calendar export and session report, daily wellbeing check-in, feedback threads, live events stream, idempotent uploads

### C. Coach and admin web dashboard
- done: team overview with change against baseline, athlete page with trend charts, admin page, Bulgarian and English, phone-friendly layout
- done: club platform views — groups, sessions with report and calendar download, wellbeing team table and athlete chart, feedback threads, live indicator

### D. Android app
- done: login, record or pick video, on-device analysis, result, upload, history
- done: Bulgarian and English texts, landing-frame skeleton overlay, filming guide, offline upload queue; checked on the emulator after the detector merge (280/360/360 ms on the own clips)
- rejected: faster two-pass analysis (failed its accuracy check)
- running: club screens (next sessions, daily check-in, feedback thread) — another worker; frame-confirm step, visual polish, signed release build (logs/run-confirm.sh)
- todo: timing on a real phone

### E. Operations
- done: CI workflow (both jobs checked from a fresh clone), Dockerfile and compose file (start command checked; image build not runnable here), systemd unit, deployment notes, README
- needs the owner: public address and go-ahead to deploy; the rules require the project online from registration to the end of the tournament, with test accounts for every role

### F. Documents (Bulgarian)
- done (drafts with placeholders for names): documentation (five A4 pages with figures), A4 flyer, eleven-slide deck, talk plan, user guide, formula explainer, test-account sheet, submission checklist; `node docs/build.cjs` prints them
- done: platform round — documentation rewritten around the club platform, coach/athlete sections in the user guide, demo-data and jury checks for the new modules, platform slide and live-demo script
- needs the owner: names, school, supervisor, screenshots from the final build

### G. Things only the owner can do
- Decide the entry (the earlier advice was Kant in autumn; that decision and the co-author question are open)
- Supervising teacher and registration by 29.11
- Consent declarations (school director; parents for filmed minors)
- Filming session with real athletes: phone at knee height, whole body in frame, 60 or 120 fps
- Gated datasets if wanted (OpenCap, Notre Dame, Bath)
- Be able to explain every formula: the explainer in `docs/` is written for that

## Rules for everyone working on this

- Each part has an owner directory; do not edit outside it. Contracts live in `SPEC.md`.
- Smallest correct solution. No medical claims in any text.
- Tests must pass before a commit. Staged work goes to the `stage` branch; `main` holds only reviewed work.
- No deployment, publishing or pushing without the owner's go-ahead.
