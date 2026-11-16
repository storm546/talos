# JumpScan: spec and contracts

Three parts in one repo. Each worker touches only its own directory.

```
server/   Python API + coach dashboard         (Worker S)
core/     pure Kotlin/JVM metrics library      (Worker C, also root Gradle files)
app/      Android app                          (Worker A)
```

Rules for every part: smallest correct solution, no speculative abstractions, few dependencies,
readable names, short comments only where the reason is not obvious, tests for the logic.
No video, image or raw landmark data is ever sent to or stored on the server.
UI wording must avoid medical claims: say "landing mechanics" and "numbers to discuss with a coach",
never "diagnosis", "injury risk" or "screening".

## 1. Jump metrics JSON (shared contract)

Produced by `core`, shown by `app`, stored by `server`.

| Field | Type | Meaning | Server-accepted range |
|---|---|---|---|
| `test` | string | `"drop_jump"` or `"cmj"` | enum |
| `fps` | number | frames per second analysed | 10–480 |
| `flight_time_ms` | number or null | last airborne phase before the analysed landing | 0–1500 |
| `jump_height_cm` | number or null | `g * t^2 / 8` from flight time; null for a plain drop landing | 0–150 |
| `knee_flexion_contact_l`, `_r` | number | knee flexion at initial contact, degrees (0 = straight leg) | 0–180 |
| `knee_flexion_peak_l`, `_r` | number | maximum knee flexion within 500 ms after contact | 0–180 |
| `fppa_peak_l`, `_r` | number | frontal plane projection angle at peak flexion, degrees, positive = knee moved towards the midline (valgus direction) | -90–90 |
| `knee_ankle_ratio` | number | knee separation / ankle separation at peak flexion (below ~0.8 means knees collapsing inward) | 0–5 |
| `flexion_asymmetry_pct` | number | `abs(l - r) / max(l, r) * 100` of peak flexion | 0–100 |
| `quality` | number | mean landmark visibility of hips, knees, ankles over the landing window | 0–1 |
| `warnings` | string[] | any of `low_fps`, `low_visibility`, `no_flight_detected`, `uncertain_timing` (tracking missing at a flight edge; timing uses nearest observed frames) | max 5 items |

## 2. core (Kotlin/JVM, package `jumpscan.core`)

Input is what MediaPipe Pose Landmarker returns: 33 landmarks per frame, normalised image
coordinates (x right, y down, 0–1), with visibility.

```kotlin
data class Landmark(val x: Float, val y: Float, val z: Float, val visibility: Float)
data class Frame(val tMs: Long, val landmarks: List<Landmark>,   // size 33, MediaPipe order, normalised image
                 val worldLandmarks: List<Landmark>)             // size 33, MediaPipe world landmarks, metres
data class JumpMetrics(...)                                      // fields of section 1, camelCase
fun JumpMetrics.toJson(): String                                 // snake_case keys of section 1, no library
object JumpAnalyzer { fun analyze(frames: List<Frame>, test: String): JumpMetrics }
```

- Landmark indices used: hips 23/24, knees 25/26, ankles 27/28, heels 29/30, foot index 31/32.
- Ground level: each foot supplies its own median floor height from 250 ms of planted feet
  (range <= 2.5% of local leg length), allowing one frame of endpoint quantisation. The hips may
  move throughout this reference, including a deep countermovement; still standing is not required.
  Ground references expire after 1.5 s (maximum flight plus landing window), excluding the approach.
  During a flight the floor is frozen. Hips must rise by 2.5% of leg length above both the tallest
  recent reference at this floor and the preceding ground frame, so settled sway cannot supply ascent.
- Airborne: both feet above ground level by a threshold scaled to body size (hip-to-ankle length).
  Smooth the signal first; pose data is jittery.
- Analysed landing: the last airborne-to-ground transition. Initial contact is the first observed
  frame where either measured foot crosses back through its floor clearance threshold. No fixed
  frame correction is applied: delayed pose estimates do not identify an earlier physical contact.
- A CMJ requires an observed take-off after an observed ground reference. A clip starting above a
  future floor reference cannot supply a CMJ. An explicitly selected `drop_jump` may start airborne;
  its initial drop landing has `jump_height_cm: null` because its take-off was not observed.
- Knee flexion: 180 minus the 3D hip-knee-ankle angle from the world landmarks. Seen from the front
  the knee bends towards the camera, so the image plane alone cannot show it. Single-camera depth is
  an estimate; the number is for comparing an athlete with themselves over time.
- FPPA: angle between thigh (hip to knee) and shank (knee to ankle) projected on the image plane,
  signed so that medial knee displacement is positive for both legs.
- Frames with missing or low-visibility leg landmarks are skipped, not guessed.
- Throws `IllegalArgumentException` on fewer than 10 usable frames.

Tests (JUnit, synthetic landmark series built in code): known flight time gives the right height,
known joint geometry gives the right flexion and FPPA within 1 degree, symmetric input gives 0 % asymmetry,
noisy input stays within tolerance, a clip with no jump gives `no_flight_detected`, 15 fps gives `low_fps`.

Gradle: root `settings.gradle` includes `:core` (Kotlin JVM, JDK 17). Gradle 8.10.2 wrapper.
Leave room for Worker A to add `:app`.

## 3. server (Python, FastAPI, SQLite)

Dependencies: `fastapi`, `uvicorn`, `pytest`, `httpx` (for the test client). Nothing else:
`sqlite3`, `hashlib.scrypt` and `secrets` from the standard library cover storage, password hashing and tokens.

Roles: `admin` (everything), `coach` (own club), `athlete` (own data). Every user belongs to one club.

| Method and path | Who | Does |
|---|---|---|
| `POST /api/auth/login` `{email, password}` | anyone | returns `{token, user: {id, name, role, club_id}}`; opaque random token, stored hashed, expires after 30 days |
| `POST /api/auth/logout` | logged in | revokes the token |
| `GET /api/me` | logged in | current user |
| `POST /api/users` `{email, name, password, role}` | admin, coach | admin creates any role in any club (`club_id` in body); coach creates athletes in their own club only |
| `GET /api/athletes` | coach, admin | athletes of the coach's club (admin: all), each with jump count and last jump date |
| `POST /api/jumps` (section 1 JSON) | athlete | stores a jump for the caller; returns `{id, created_at}` |
| `GET /api/jumps?athlete_id=` | all | athlete: own only (parameter ignored or must match); coach: athletes of own club; admin: any. Newest first |
| `DELETE /api/me/data` | athlete | deletes all the caller's jumps |
| `GET /` | anyone | coach dashboard: one static HTML file with plain JavaScript: login, athlete list, per-athlete history table and a simple trend of `knee_ankle_ratio` and peak flexion |

Security requirements: bearer token in the `Authorization` header; parameterised SQL only; strict
request validation with the ranges of section 1 and unknown fields rejected; object-level access checks
on every read (a coach must get 403 or 404 for another club's athlete); login rate limit (5 failures per
email per 15 minutes); constant-time credential checks; no stack traces in responses; the dashboard must
build DOM with `textContent`, never `innerHTML` with server data.

`python -m server.seed` creates synthetic data only: club "Demo Club", `admin@demo.test`,
`coach@demo.test`, `athlete1@demo.test`, `athlete2@demo.test` (password `demo1234` each), plus a
second club with one coach and one athlete for access tests, and about ten plausible jumps per athlete.

Tests (pytest): login success and failure, rate limit, every role boundary above, validation rejects
out-of-range and unknown fields, delete-my-data removes only the caller's rows.

Run: `uvicorn server.main:app`. Database path from `JUMPSCAN_DB`, default `server/jumpscan.db`.

## 4. app (Android, Kotlin, package `jumpscan.app`)

minSdk 26, compileSdk and targetSdk 35. Plain Views, no Compose. Dependencies limited to AndroidX
core/appcompat and `com.google.mediapipe:tasks-vision`. Networking with `HttpURLConnection` and
`org.json`. Uses `:core` for all maths.

Screens:
1. Login: server URL, email, password. Token kept in app-private storage.
2. Home: choose test type, then "Record" (system camera via `ACTION_VIDEO_CAPTURE`) or
   "Pick video" (system photo picker). Shows a one-line filming guide: camera at knee height,
   facing the athlete, whole body in frame.
3. Analysis: decode the clip frame by frame on a background thread, run Pose Landmarker
   (`pose_landmarker_heavy.task` bundled in assets, VIDEO mode), pass frames to `JumpAnalyzer`,
   show progress.
4. Result: the numbers of section 1 with plain labels and the warnings; "Upload" sends the JSON to
   `POST /api/jumps`. The video is never uploaded.
5. History: the athlete's own jumps from `GET /api/jumps`.

Errors (no person found, too few frames, network failure, expired token) show a clear message and
never crash.

Build and run on the kit: `ssh android-build-kit` (user builder, bash, JDK 17, SDK `/opt/android-sdk`,
work dir `/work/jumpscan`, KVM available, x86_64 google_apis system image installed).
