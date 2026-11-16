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
| Code quality, tests, version control, error handling, attack protection (Implementation) | 130+ server tests, 29 core tests including real-video fixtures, CI on every push, security review with fixes recorded |
| Evidence of testing (Presentation) | Accuracy table against the University of Twente lab dataset (40 jumps, 4 subjects), detection rates on 1000 public clips, own filmed jumps |
| Language norms, UI (Implementation) | Bulgarian and English interface in app and dashboard |

The strongest single asset is the validation: most school projects cannot say how wrong they are. Ours can.

## Honest state of accuracy (2026-11-16)

- Flight time: about one frame off the force plate when the jump is found. Found from the front in 28 of
  40 lab jumps with no spurious results, from the side in 20 of 40 (timing about three frames off).
- Knee flexion from the front: follows the reference curve (correlation about 0.9) but about 16 degrees
  RMSE and peaks 12 degrees low. From the side, the image-plane angle gives about 10 degrees RMSE.
- A server-side model (RTMPose-x) reaches about 5 degrees from the side; it does not help from the front.
- On 1000 public webcam clips: a flight is found in 45 to 60 percent of jump clips; false alarms 3 to 7 percent.

These numbers decide the wording of the product: "change against your own baseline", never "risk".

## Work packages

Status key: done, running, staged (built but not reviewed or not deployed), todo.

### A. Measurement core
- done: knee flexion, frontal-plane angle, knee/ankle ratio, asymmetry, flight time, jump height; heavy pose model
- done: validation against Twente; batch scorer
- running: 2D side-view angle, calibration, failure analysis (Twente follow-up); stronger models on GPU (decides server-side analysis)
- todo: detector fixes against the 40-jump force-plate benchmark; per-leg visibility for side view; session of three trials with the median reported

### B. Club server
- done: auth, roles, jumps, delete-my-data, validation, rate limit, coach dashboard v1
- todo (stage tonight): consent record per athlete; baseline and change summary; CSV and JSON export; audit log; club and user administration; OpenAPI page

### C. Coach and admin web dashboard
- todo (stage tonight): team overview with change against baseline, athlete page with trend charts, admin page, Bulgarian and English, phone-friendly layout

### D. Android app
- done: login, record or pick video, on-device analysis, result, upload, history
- todo (stage tonight): Bulgarian and English texts, landing-frame skeleton overlay on the result screen, filming guide, offline upload queue, faster analysis (heavy model only around the jump)
- todo: timing on a real phone, release build

### E. Operations
- todo (stage tonight): CI workflow (server tests, core tests), Dockerfile and compose file, deployment notes for the home server, README
- needs the owner: public address and go-ahead to deploy; the rules require the project online from registration to the end of the tournament, with test accounts for every role

### F. Documents (Bulgarian)
- todo (stage tonight, as drafts): documentation of at most five A4 pages in the Appendix 1 structure, leaflet text, presentation outline, user guide, formula explainer for the defence, test-account sheet
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
