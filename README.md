# JumpScan

**Накратко (BG).** JumpScan дава на спортен клуб тестване на механиката на приземяване без лаборатория.
Спортистът заснема скок с телефона си; приложението намира позата на тялото и изчислява числата на самото
устройство, а към сървъра на клуба отиват само числата. Видеото никога не напуска телефона. Треньорът
вижда историята на всеки спортист и как тя се променя спрямо собствените му базови стойности. Числата
са за проследяване на промяна и за разговор с треньор, не са медицинско измерване.

JumpScan gives a sports club landing-mechanics testing without a lab. An athlete films a jump with a
phone; the phone finds the body pose and computes the numbers on the device; only the numbers go to the
club's server. The coach sees every athlete's history and how each one changes against their own
baseline. The video never leaves the phone. The project is honest about accuracy: it publishes its own
validation against laboratory motion capture and force plates, and presents the numbers as a way to track
change, not as a medical measurement.

## Architecture

```
   Android phone                                    Home server
+---------------------------+                 +---------------------------+
| app/  (Kotlin, Views)     |                 | server/  (Python, FastAPI)|
|   camera / video picker   |    HTTPS        |   REST API  /api/...      |
|   MediaPipe pose (local)  | ------------>   |   SQLite: numbers only    |
|   core/ maths (local)     |  metrics JSON   |   coach dashboard  GET /  |
|   video stays on device   |                 +---------------------------+
+---------------------------+
              |
              | uses the same code as
              v
+---------------------------+
| core/  (Kotlin/JVM)       |
|   pure maths, unit tested,|
|   no Android dependencies |
+---------------------------+
```

Three parts with written contracts (see `SPEC.md`):

- `app/` — Android client, minSdk 26, targetSdk 35. Records or picks a video, runs MediaPipe Pose
  Landmarker on the device, shows the metrics, uploads the JSON.
- `core/` — Kotlin/JVM library with all the maths: flight time, jump height, knee flexion, frontal-plane
  angle, knee/ankle ratio, asymmetry. The same code runs in the app and in the batch validator.
- `server/` — FastAPI + SQLite API with three roles (admin, coach, athlete), token auth, rate limiting,
  strict validation and a coach dashboard served at `/`.

## Repository layout

```
app/      Android app
core/     Kotlin/JVM metrics library and batch scorer
server/   Python API, dashboard, seed script, tests
deploy/   notes for running the server behind a reverse proxy
docs/     validation write-ups, formula explainer
```

## Running the server

Requires Python 3.13.

```sh
python3.13 -m venv server/.venv
server/.venv/bin/pip install -r server/requirements.txt
server/.venv/bin/uvicorn server.main:app --port 8000 --no-server-header
```

The dashboard is at `http://127.0.0.1:8000/`. The database path comes from `JUMPSCAN_DB` and defaults to
`server/jumpscan.db`.

Synthetic demo accounts (club "Демо клуб", password `demo1234` for all): `admin@demo.test`,
`coach@demo.test` and six athletes `athlete1@demo.test`, `athlete2@demo.test`, `athlete4@demo.test` to
`athlete7@demo.test`, plus a second club with `coach2@demo.test` and `athlete3@demo.test`. Ten generated
jumps per athlete, four days apart, plus two groups, five sessions, two weeks of check-ins and a
feedback thread.

```sh
server/.venv/bin/python -m server.seed
```

The seed is idempotent: it creates the accounts once and does nothing on later runs.

Docker (server only, published on loopback):

```sh
docker compose up -d --build      # http://127.0.0.1:8171/
```

`deploy/README.md` has the step-by-step for a home server behind an HTTPS reverse proxy.

## Tests

```sh
server/.venv/bin/python -m pytest server -q      # 453 server tests
./gradlew --no-daemon :core:test                 # 65 core tests, JDK 17
```

GitHub Actions runs three jobs on every push and pull request (`.github/workflows/ci.yml`): server
tests, core tests and a debug build of the Android app (GitHub's Ubuntu runners include the Android SDK).
The signed release build stays on the build machine, because its key is kept outside the repository.

## Building the Android app

Requires JDK 17 and the Android SDK with platform 35. Point Gradle at the SDK with `ANDROID_HOME` or a
`sdk.dir=` line in `local.properties` (that file is git-ignored).

```sh
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk

./gradlew --no-daemon :app:assembleRelease -PjumpscanKeystore=/work/jumpscan-keys/keystore.properties
# output: app/build/outputs/apk/release/app-release.apk
```

The release command runs in `/work/jumpscan-app` on `android-build-kit`. Signing files live outside
the repository at `/work/jumpscan-keys/jumpscan-release.jks` and
`/work/jumpscan-keys/keystore.properties`, with a backup in `/root/jumpscan-keys/` on openclaw.
The properties file contains `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
Without `-PjumpscanKeystore`, release builds remain unsigned. Debug signing is unaffected.
Release APKs include only `arm64-v8a` and `armeabi-v7a`; minification is disabled.

The pose model `pose_landmarker_heavy.task` ships in `app/src/main/assets/`. Debug builds accept plain
`http://` server URLs for local testing; release builds require `https://`.

## Validation

Accuracy is measured against laboratory reference data, not asserted. The full write-up is
`docs/validacia.md` (Bulgarian); this table is the summary.

| What | Reference | Result | Details |
|---|---|---|---|
| Detection | Force plate, Twente lab dataset (91 jumps, 9 subjects, filmed front and side), each subject scored by a model that never saw them | front: 64 numbers without a warning, none wrong; the 7 wrong ones carry `uncertain_timing`; 20 more get suggested frames. Side: 58 correct and 2 wrong numbers without a warning (side rule chosen among four prespecified candidates on the same data) | `docs/validacia.md` 4.9, `logs/ml/REPORT3.md` |
| Flight time, jump height | Same force plate | front: mean error 32 ms, about one frame at 33 fps (about 3 cm of height at a 15 cm jump); side: flight about 87 ms too long | `docs/validacia.md` 4.8 |
| Knee flexion curve | Lab motion capture (OpenSim inverse kinematics), same dataset | RMSE front 17.3° (range compressed, slope 0.65), side in the image plane 10.0° | `docs/validacia.md` 3, 4.8 |
| False alarms | 400 public webcam clips without a jump | 3–7 % | `docs/validacia.md` |
| Own filmed jumps | Manual frame labelling | all three found; landing within one frame | `docs/validacia.md` |

Datasets: University of Twente, Zenodo 10.5281/zenodo.6644593 and 10.5281/zenodo.7422031 (Huawei Wang,
CC BY 4.0); Gesture Video Dataset for Jump Classification on Kaggle (daffasesarabbani, CC BY 4.0).

The honest reading: the numbers are good for comparing an athlete with themselves over time, not for
absolute angles; in side view one leg is measured and flight timing is only indicative.

## Platform

Jump analysis is the core module of a club platform (specified in `SPEC.md` sections 5 and 6). Around
it, the same roles, consent rules, audit log and pagination carry the club's working day:

| Module | What it does for the club | Endpoints |
|---|---|---|
| Groups | Training groups inside a club; an athlete may be in several | `GET/POST /api/groups`, `PATCH/DELETE /api/groups/{id}`, `PUT /api/groups/{id}/members` |
| Sessions | Scheduled test sessions (group or club-wide, 1–5 trials), with past/upcoming list | `GET/POST /api/sessions`, `PATCH /api/sessions/{id}` |
| Session report | Trials done vs planned and medians of each metric per athlete | `GET /api/sessions/{id}/report` |
| Calendar export | iCalendar (.ics) of the caller's visible sessions | `GET /api/me/calendar.ics` |
| Wellbeing check-in | Daily five-item 1–5 check-in plus optional training minutes and RPE; team table with a wellbeing sum (5–25), load and a neutral "below usual" mark | `PUT /api/me/checkins/{day}`, `GET /api/checkins`, `GET /api/checkins/team` |
| Feedback | Coach comments per athlete, optionally about a jump or session; unread badge for athletes | `POST /api/comments`, `GET /api/comments`, `GET /api/me/unread`, `POST /api/me/read` |
| Live events | Server-Sent Events of new jumps, check-ins, comments and sessions in scope, via `fetch` so the token never appears in a URL | `GET /api/events` |
| Form check | On-device squat form analysis (depth, frontal-plane angle, per-rep asymmetry); server stores numbers only | `POST /api/form`, `GET /api/form`, `GET /api/athletes/{id}/form.csv` |
| Training | Coach plans per group or athlete, set logs, weekly volume and a double-progression suggestion (shown, never auto-applied) | `GET/POST /api/exercises`, `GET/POST/PUT/DELETE /api/plans`, `POST/GET/DELETE /api/set-logs`, `GET /api/athletes/{id}/training[.csv]` |
| Nutrition | Food and weight logs, own weight-trend model, adaptive expenditure estimate and a weekly target program; numbers for discussion, not prescriptions | `GET/POST/DELETE /api/foods`, `POST/GET/DELETE /api/food-logs`, `PUT/DELETE /api/me/weights/{day}`, `GET/PUT /api/athletes/{id}/nutrition/target`, `PUT /api/athletes/{id}/nutrition/program`, `GET /api/athletes/{id}/nutrition/{estimate,summary,expenditure,program}` |
| Assistant | On-prem LLM helper that proposes actions as the logged-in user and runs on the club's own model; no write happens until an explicit confirm | `POST /api/assistant`, `POST /api/assistant/confirm`, `GET /api/assistant/conversations[/{id}]` |

Jumps optionally attach to a session, and idempotent uploads use a client `client_id`. The Android app
shows the next sessions, a daily check-in and the feedback thread; the coach dashboard (Bulgarian and
English) adds Groups, Sessions with a report and calendar download, Wellbeing and Feedback, with a live
indicator that refreshes the open view without a reload. Both the app and the dashboard also carry
Training, Nutrition and Form tabs and an AI assistant panel; the assistant proposes actions as the
logged-in user, runs on the club's own model and applies nothing until the user confirms. Nutrition and
training numbers are for discussion with the coach, not prescriptions.

## Privacy

The video and the raw landmarks never leave the phone. The app uploads only the metrics JSON (a few
hundred bytes per jump) to the club server, which stores numbers against an athlete account. Athletes
can delete their own data (`DELETE /api/me/data`). Debug builds additionally write the landmark series of
the analysed clip to `landmarks.csv` in the app's private files folder, so it can be pulled for testing
with `adb`; that export does not exist in release builds.

## Licence

JumpScan is licensed under the GNU General Public License, version 3 or later (GPL-3.0-or-later).
The full text is in `LICENSE`; it is also available at <https://www.gnu.org/licenses/gpl-3.0.txt>.

Third-party components keep their own licences: MediaPipe Tasks Vision and the bundled
`pose_landmarker_heavy.task` model (Apache-2.0), AndroidX (Apache-2.0), FastAPI and Uvicorn (MIT,
BSD-3-Clause), Kotlin (Apache-2.0). All are compatible with GPL-3.0.

## Documents

The Bulgarian competition documents are Markdown files in `docs/`. `node docs/build.cjs` renders them to
A4 PDFs in `docs/out/` (Times New Roman 12 pt, line spacing 1.5, as the tournament rules require). It
needs the npm packages `marked` and `playwright`; set `CHROME` to a browser binary if Playwright has not
downloaded its own.
