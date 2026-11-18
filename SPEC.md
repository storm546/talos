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
| `knee_flexion_contact_l`, `_r` | number or null | knee flexion at initial contact, degrees (0 = straight leg); null for the unmeasured leg | 0–180 |
| `knee_flexion_peak_l`, `_r` | number or null | maximum knee flexion within 500 ms after contact; null for the unmeasured leg | 0–180 |
| `fppa_peak_l`, `_r` | number or null | frontal plane projection angle at peak flexion, positive towards the midline; null in side view | -90–90 |
| `knee_ankle_ratio` | number or null | knee separation / ankle separation at peak flexion; null in side view | 0–5 |
| `flexion_asymmetry_pct` | number or null | `abs(l - r) / max(l, r) * 100` of peak flexion; null with one measured leg | 0–100 |
| `quality` | number | mean landmark visibility of hips, knees, ankles over the landing window | 0–1 |
| `warnings` | string[] | any of `low_fps`, `low_visibility`, `no_flight_detected`, `uncertain_timing` (missing edge tracking or rules/learned disagreement), `single_leg_view` (only the better-observed leg is measured), `manual_edges` (take-off and landing frames chosen by the user) | max 6 items |

## 2. core (Kotlin/JVM, package `jumpscan.core`)

Input is what MediaPipe Pose Landmarker returns: 33 landmarks per frame, normalised image
coordinates (x right, y down, 0–1), with visibility.

```kotlin
data class Landmark(val x: Float, val y: Float, val z: Float, val visibility: Float)
data class Frame(val tMs: Long, val landmarks: List<Landmark>,   // size 33, MediaPipe order, normalised image
                 val worldLandmarks: List<Landmark>)             // size 33, MediaPipe world landmarks, metres
data class JumpMetrics(...)                                      // fields of section 1, camelCase
fun JumpMetrics.toJson(): String                                 // snake_case keys of section 1, no library
object JumpAnalyzer {
    enum class View { AUTO, FRONT, SIDE }
    data class FlightSuggestion(val takeoffMs: Long, val landingMs: Long)
    fun analyze(frames: List<Frame>, test: String, aspect: Float = 9f / 16f,
                view: View = View.AUTO, takeoffMs: Long? = null, landingMs: Long? = null): JumpMetrics
    data class Analysis(val metrics: JumpMetrics, val contactMs: Long?, val flightCount: Int,
                        val flightTimeMinMs: Long? = null, val flightTimeMaxMs: Long? = null,
                        val takeoffMs: Long? = null, val learnedSuggestion: FlightSuggestion? = null)
    fun analyzeWithLanding(frames: List<Frame>, test: String, aspect: Float = 9f / 16f,
                           view: View = View.AUTO, takeoffMs: Long? = null, landingMs: Long? = null): Analysis
}
```

- `analyzeWithLanding` is the public entry point for metrics plus flight edges. Rules must find a
  flight before any automatic flight is reported. In side view (explicit `SIDE` or selected by
  `AUTO`), when both detectors find a flight, use both learned edges directly with no additional
  timing warning, even when the edges disagree. In front view, if both learned edges agree with
  the selected rules flight within an inclusive 100 ms, each reported edge estimate is their
  arithmetic mean, with no additional timing warning. Flight time is the selected continuous
  landing minus take-off. `contactMs` and `takeoffMs` are the real frame timestamps nearest those
  estimates (earlier frame on a tie), for the landing window and editor. Their difference can
  therefore differ from the flight-time metric.
  If learned inference runs but finds no plausible flight, or disagrees in front view, retain the
  rules flight with `uncertain_timing`. An unavailable learned path retains the original rules result
  and warnings.
- With no rules flight, automatic `contactMs`, `takeoffMs`, flight time and height remain null and
  `no_flight_detected` remains present. A learned flight lasting an inclusive 100–1000 ms supplies
  `learnedSuggestion`, with both edges snapped to nearest real frames, only in Kotlin `Analysis`.
  It never changes the metrics JSON contract. With neither flight, behavior is unchanged.
- Learned inference uses the bundled final seed-zero temporal network at threshold 0.5. It uses
  the clip's own mean frame rate from timestamps between 24 and 36 fps, inclusive; above 36 fps
  it uses a 30 fps grid. Each grid cell uses the nearest real observation within half a step.
  Missing positions are interpolated, with zero visibility and presence. Below 24 fps or with
  unusable learned input, use the rules fallback. Only learned durations of 100–1000 ms are plausible.
- On a no-flight result with a suggestion, the app's Check frames action opens with both suggested
  edges preset and a translated instruction to confirm them. Applying still requires the user's
  action. Other editor entry paths retain their existing defaults.
- Optional input edges must be supplied together, match input frame timestamps, have landing after
  take-off, and span at most 1000 ms; otherwise `IllegalArgumentException` is thrown. They override
  automatic detection, use landing as initial contact for the 500 ms metrics window, and calculate
  height as `g * t^2 / 8` for both tests (a marked drop-jump flight is a rebound). Manual results add
  `manual_edges` and omit `no_flight_detected` and `uncertain_timing`. Visibility, frame-rate and
  single-leg warnings still apply, and at least ten usable angle frames are required. Manual results
  have zero automatic flight candidates and null automatic timing bounds. Frame selection does not
  remove frame-rate quantisation or validate physical timing.
- Landmark indices used: hips 23/24, knees 25/26, ankles 27/28, heels 29/30, foot index 31/32.
- Ground level: each foot supplies its own median floor height from 250 ms of planted feet
  (range <= 2.5% of local leg length), allowing one frame of endpoint quantisation. The hips may
  move throughout this reference, including a deep countermovement; still standing is not required.
  Ground references expire after 1.5 s (maximum flight plus landing window), excluding the approach.
  During a flight the floor is frozen. Hips must rise by 2.5% of leg length above both the tallest
  recent reference at this floor and the preceding ground frame, so settled sway cannot supply ascent.
- Airborne: both feet above ground level by a threshold scaled to body size (hip-to-ankle length).
  Smooth the signal first; pose data is jittery.
- Rules landing: the last airborne-to-ground transition. Rules contact is the first observed
  frame where either measured foot crosses back through its floor clearance threshold. No fixed
  frame correction is applied. The reported contact then follows the learned-edge policy above;
  delayed pose estimates do not themselves identify an earlier physical contact.
- A CMJ requires an observed take-off after an observed ground reference. A clip starting above a
  future floor reference cannot supply a CMJ. An explicitly selected `drop_jump` may start airborne;
  its initial drop landing has `jump_height_cm: null` because its take-off was not observed.
- Knee flexion: 180 minus the 3D hip-knee-ankle angle from the world landmarks. Seen from the front
  the knee bends towards the camera, so the image plane alone cannot show it. Single-camera depth is
  an estimate; the number is for comparing an athlete with themselves over time.
- In side view, knee flexion uses the image hip-knee-ankle angle, with x multiplied by image aspect.
  `AUTO` selects side view only when median shoulder width is below 0.5 times torso length and median
  hip width is below 0.35 times torso length, from at least ten frames with all four joints visible.
  Torso length is shoulder-midpoint to hip-midpoint distance in the image plane. Both width tests
  require substantial foreshortening; borderline/oblique clips default to front. Callers may explicitly
  choose `FRONT` or `SIDE`. These thresholds express near-profile geometry, not subject calibration.
  Side views measure the leg with the most complete joint-and-foot observations (left on a tie),
  because the profile overlaps the legs. Ground, flight, pose checks, angles and quality use only
  that leg. The warning `single_leg_view` is always present. The other knee, FPPA, knee/ankle ratio
  and asymmetry are null, never copied from the measured leg. Front-view formulas are unchanged.
- FPPA: angle between thigh (hip to knee) and shank (knee to ankle) projected on the image plane,
  signed so that medial knee displacement is positive for both legs.
- Frames with missing or low-visibility leg landmarks are skipped, not guessed.
- Throws `IllegalArgumentException` on fewer than 10 usable frames.

Tests (JUnit, synthetic landmark series built in code): known flight time gives the right height,
known joint geometry gives the right flexion and FPPA within 1 degree, symmetric input gives 0 % asymmetry,
noisy input stays within tolerance, a clip with no jump gives `no_flight_detected`, 15 fps gives `low_fps`.
Optional measured-data tests use `JUMPSCAN_TWENTE_DIR` and `JUMPSCAN_KAGGLE_DIR`, defaulting to
`/work/jumpscan-data/twente-jumps` and `/work/jumpscan-data/kaggle-landmarks`. Missing directories
skip the corresponding test cleanly; an installed but incomplete dataset fails validation. These
tests assert measured detection, false-alarm, timing and error rates with small regression margins.
The full before/after measurements and limits are recorded in `core/VALIDATION.md`.

Gradle: root `settings.gradle` includes `:core` (Kotlin JVM, JDK 17). Gradle 8.10.2 wrapper.
Leave room for Worker A to add `:app`.

## 3. server (Python, FastAPI, SQLite)

Dependencies: `fastapi`, `uvicorn`, `pytest`, `httpx` (for the test client). Nothing else:
`sqlite3`, `hashlib.scrypt` and `secrets` from the standard library cover storage, password hashing and tokens.

Roles: `admin` (all clubs), `coach` (own club), `athlete` (own data). Every user belongs to one club.
The Android login, current-user, upload, history and delete contracts remain unchanged except for the
required consent gate on uploads. Login and `/api/me` still expose only `id`, `name`, `role`, `club_id`.

| Method and path | Who | Does |
|---|---|---|
| `POST /api/auth/login` `{email, password}` | anyone | returns `{token, user: {id, name, role, club_id}}`; opaque random token, stored hashed, expires after 30 days |
| `POST /api/auth/logout` | logged in | revokes the current token |
| `GET /api/me` | logged in | current user |
| `POST /api/users` `{email, name, password, role, club_id?}` | admin, coach | admin creates any role in any club; coach creates athletes in their own club only; password length 8–1024 |
| `GET /api/users?limit=100&offset=0` | admin, coach | admin sees all; coach sees own club; public identity, email, active state and consent only |
| `PATCH /api/users/{id}` `{active: bool}` | admin, coach | deactivate or reactivate; coach manages only own athletes; nobody can deactivate themselves |
| `POST /api/users/{id}/password` `{password}` | admin, coach | reset using creation password rules; coach manages only own athletes; revokes all target tokens; returns 204 |
| `PATCH /api/users/{id}/consent` `{consent_recorded: bool, consent_by: string}` | admin, coach | record or withdraw an athlete's consent; coach manages only own athletes; records server UTC `consent_at`; returns the three consent fields |
| `GET /api/clubs`, `POST /api/clubs` `{name}` | admin | list clubs or create a uniquely named club |
| `GET /api/athletes?limit=100&offset=0` | coach, admin | own club (admin: all); identity, jump count, last date, active state, consent fields and `changes` for the three headline metrics |
| `POST /api/jumps` (section 1 JSON) | athlete | requires recorded consent, otherwise 403 with a clear message; stores a jump for the caller; returns `{id, created_at}` |
| `GET /api/jumps?athlete_id=&limit=100&offset=0` | all | athlete: own only; coach: athletes of own club; admin: any. Newest first, ID descending for tied dates |
| `GET /api/athletes/{id}/summary` | same scope as jumps | counts, first/last jump dates, baseline completeness and all numeric metric summaries |
| `GET /api/me/export` | athlete | JSON attachment with own public profile, email, active state, consent, all jumps and export time; no passwords or tokens |
| `GET /api/athletes/{id}/export.csv` | admin, coach of club | CSV attachment with all athlete jumps, metric fields and athlete name; oldest first; cells protected against spreadsheet formulas |
| `DELETE /api/me/data` | athlete | deletes all the caller's jumps, retaining account and consent; returns 204 |
| `GET /api/audit?limit=100&offset=0` | admin | audit entries newest first: `id`, `at`, `actor_id`, `action`, `target`, `detail` (JSON object) |
| `GET /` | anyone | Bulgarian/English role-aware dashboard; plain HTML, local CSS/JavaScript and SVG charts |
| `GET /docs`, `GET /openapi.json` | anyone | FastAPI Swagger UI and versioned API description |

Summary response: `{athlete_id, jump_count, first_jump_at, last_jump_at, baseline_complete, metrics}`.
`metrics` contains each numeric field from section 1 plus `knee_flexion_peak_mean` (the mean of left and
right peak flexion for each jump). Each contains `baseline`, `latest`, `last_three_median`,
`change_absolute`, `change_percent`, `baseline_count`, `recent_count`. Baseline is the median of the
first three chronological jumps; recent is the median of the last three, ordered by timestamp then ID.
Changes compare recent median to baseline: `recent - baseline` and `(recent - baseline) / baseline * 100`.
Fewer than three jumps use available jumps and `baseline_complete: false`; null measurements are omitted
within each three-jump window. Missing values and percentages against a zero baseline are null.
The latest value can be null. No jumps produce null values and dates and count zero. Summaries combine
test types; compare jumps under similar filming conditions. Roster `changes` holds these metric objects
for `knee_ankle_ratio`, `knee_flexion_peak_mean`, `flexion_asymmetry_pct`, so the team needs one request.

Startup performs an idempotent migration, retaining existing clubs, users, password hashes, tokens and
jumps. New columns: `active` defaults true; `consent_recorded` defaults false; `consent_by` and `consent_at`
default null. Existing athletes require a real consent record before new uploads. Deactivation denies
login and token access, revokes all target tokens and retains history. Reactivation requires a new login.
Password resets also revoke all target tokens. Consent withdrawal stops new uploads while retaining read,
export and delete access. `consent_by` is required, nonblank, at most 100 characters, including withdrawal.

Audit table: `audit(id, at, actor_id, action, target, detail)`. Actions: `login_success`, `login_failure`
(including rate-limited attempts), `user_created`, `consent_changed`, `user_deactivated`, `user_activated`,
`password_reset`, `json_export`, `csv_export`, `data_deleted`, `club_created`. Failed login actors are null;
the target is the submitted normalized email. Other targets are string IDs. Passwords and tokens are never
included. Mutations, exports and their audit entries commit before a success response.

Security: bearer token in `Authorization`; parameterized SQL; strict validation and unknown-field rejection;
object checks on every read and management operation; login limit of 5 failures per email per 15 minutes;
constant-time credential checks; no stack traces or submitted secrets in errors; 16 KiB body limit.
Paginated lists accept limits 1–500 and nonnegative offsets. Exports and summaries include all jumps.
CSV quotes text cells starting with formula characters (`=`, `+`, `-`, `@`), including hidden whitespace
prefixes, and leading tab/newline controls with an apostrophe; CSV quoting handles delimiters and newlines.
Responses include `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`
and CSP. Dashboard CSP allows only same-origin scripts/styles/connections, disallows framing, objects,
inline scripts and inline styles. Swagger `/docs` alone allows its standard jsDelivr assets and the exact
SHA-256 hash of its fixed inline bootstrap, without `unsafe-inline`. Swagger assets need internet access;
the dashboard makes no external requests. Existing `/redoc` remains available with its CDN script and
its required inline-style and Google Fonts exceptions; `/docs/oauth2-redirect` uses an exact script hash. Tokens remain in page memory and disappear on reload.

Dashboard: Bulgarian default, English switch, all translated text in one dictionary. Coaches see a sortable
team table with numeric changes and direction arrows, consent controls, athlete creation and own-club user
management. Athlete pages show four SVG charts (ratio, both peak flexions, asymmetry, height), dashed
baselines, exact summary values and the full history with warnings and CSV export for staff. Admins also
manage clubs and all users and browse the audit log. Athletes see their own charts/history, JSON export
and a two-step delete action. Redraws preserve sort focus, move focus into editors or new view headings,
and restore the initiating control after cancellation. Left/right chart series also differ by line pattern
and marker shape, with translated legend cues. A short explanation emphasizes phone-camera estimates and change against
personal baselines. DOM uses `textContent`; no data is rendered through HTML strings. Responsive layout
becomes one column below 700 px, focus is visible and tables scroll in their containers.

`python -m server.seed` creates synthetic data only, idempotently: "Demo Club" with `admin@demo.test`,
`coach@demo.test`, six athletes (`athlete1`, `athlete2`, `athlete4`–`athlete7` at `demo.test`); a second club
with `coach2@demo.test` and `athlete3@demo.test`. Password is `demo1234` for all demo accounts. Every demo
athlete is explicitly consented and has ten plausible jumps over six weeks. One athlete improves, another
becomes more asymmetric; occasional visibility warnings demonstrate the history UI. Existing accounts
and their real consent are not overwritten by a second seed run.

Tests: original endpoint behavior, roles, validation, transactions and rate limiting, plus migrations,
consent gate and withdrawal, baseline math/empty/null/zero cases, exports and CSV formula escaping,
club/user administration, deactivation, resets, audit contents and pagination, security headers and OpenAPI.
The optional host-browser check is `server/check_dashboard.py`, run with existing Playwright/Chrome
and `JUMPSCAN_DASH_URL` pointing to a temporary localhost server; it is not a server dependency.
All original 131 test cases are retained. Existing tests adjusted for the expanded seed are
`test_roster_club_scope_and_aggregates`, `test_duplicate_email_unknown_club_and_safe_sql`,
`test_unfiltered_history_obeys_scope`, `test_lists_apply_default_maximum_limit_and_offset_in_sql`,
`test_numeric_ranges_reject_outside_and_accept_bounds`, and
`test_seed_command_is_idempotent_and_environment_selects_database`.
`test_dashboard_is_public_static_and_uses_safe_dom` now reads the external JavaScript.
Other existing behavior assertions remain unchanged. Validation: `server/.venv/bin/python -m pytest server -q`.

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

## 5. Club platform (added 2026-11-18)

Jump analysis is the core module of a club platform. The modules below reuse the roles, consent, audit,
validation, security and pagination rules of section 3. Nothing here is medical: no diagnosis, risk score
or injury wording; texts speak of training, testing and how the athlete feels.

### 5.1 Data model (SQLite, idempotent startup migration as in section 3)

- `groups(id, club_id, name)`: training groups inside a club; name 1–80 characters, unique per club.
- `group_members(group_id, user_id)`: athletes of the same club; an athlete may be in several groups.
- `sessions(id, club_id, group_id NULL, title, starts_at, place, test, trials, notes, created_by, cancelled)`:
  a scheduled test session. `starts_at` requires an ISO 8601 timestamp with a timezone and a year from
  2000 through 2100, stored normalized to UTC; `test` is `cmj` or `drop_jump`;
  `trials` 1–5 (default 3); title 1–100, place 0–100, notes 0–1000 characters; `group_id` null means the
  whole club.
- `jumps` gains `session_id NULL` (the session the jump belongs to) and `client_id NULL` (section 5.6).
- `checkins(id, athlete_id, day, sleep, fatigue, soreness, stress, mood, training_minutes, rpe,
  created_at, updated_at)`, unique `(athlete_id, day)`. `day` is a calendar date (YYYY-MM-DD). The five
  wellbeing items are integers 1–5 where 5 is best (sleep quality, freshness, no soreness, calm, good mood).
  `training_minutes` 0–600 and `rpe` 0–10 (session rating of perceived exertion) are both null or both set.
- `comments(id, athlete_id, author_id, jump_id NULL, session_id NULL, body, created_at, read_at NULL)`:
  feedback threads about one athlete; body 1–1000 characters; `read_at` is set when the athlete reads a
  comment written by someone else.

### 5.2 API (additions)

| Method and path | Who | Does |
|---|---|---|
| `GET /api/groups` | admin, coach, athlete | coach: own club's groups with `member_ids`; admin: all (filter `club_id`); athlete: own groups without member lists |
| `POST /api/groups` `{name, club_id?}` | admin, coach | create; coach in own club; audit `group_created` |
| `PATCH /api/groups/{id}` `{name}`, `DELETE /api/groups/{id}` | admin, coach of club | rename or delete (memberships go, sessions become club-wide); audit `group_changed` / `group_deleted` |
| `PUT /api/groups/{id}/members` `{athlete_ids: [...]}` | admin, coach of club | replace the member list; every id must be an athlete of the same club, else 422; audit `group_changed` |
| `GET /api/sessions?from=&to=&limit=&offset=` | all | sessions visible to the caller, ordered by `starts_at`: coach own club, admin all, athlete own club where `group_id` is null or one of their groups; each with `jump_count` for the caller's scope |
| `POST /api/sessions`, `PATCH /api/sessions/{id}` | admin, coach of club | create or change (also `cancelled: true`); `group_id` must belong to the same club; audit `session_created` / `session_changed` |
| `GET /api/sessions/{id}/report` | admin, coach of club; athlete (own row only) | per athlete in scope: `trials_done`, `trials` and the median of each section 1 numeric field over that athlete's jumps in the session, plus `warnings_count`; athletes of the group without jumps appear with `trials_done: 0` |
| `GET /api/me/calendar.ics` | all | iCalendar (RFC 5545) `text/calendar` file of the caller's visible sessions from 30 days ago on: one `VEVENT` per session with `UID`, `DTSTAMP`, `DTSTART` (UTC), 1 h `DURATION`, `SUMMARY`, `LOCATION`, `DESCRIPTION`, `STATUS:CANCELLED` when cancelled; CRLF line endings, lines folded at 75 octets, text escaped |
| `POST /api/jumps` | athlete | section 3 behaviour plus optional `session_id` (must be visible to the athlete and not cancelled, else 422) and optional `client_id` (section 5.6) |
| `PUT /api/me/checkins/{day}` | athlete | create or replace the caller's check-in for `day`, from 6 days before today (UTC) through today+1 (UTC), inclusive, else 422; the app sends the phone's local calendar date and displays it in the locale's date format without a UTC label; requires consent like jumps; returns the stored check-in; 201 on create, 200 on replace |
| `GET /api/checkins?athlete_id=&from=&to=` | scope as jumps | check-ins, newest day first |
| `GET /api/checkins/team?day=` | admin, coach | for each active athlete of the coach's club (admin: filter `club_id`): the check-in for `day` (default today) or null, `wellbeing` = sum of the five items (5–25), `load` = `rpe × training_minutes` (session RPE in arbitrary units), and `below_usual: true` when `wellbeing` is at least 3 below the median of the athlete's previous 14 days with at least 5 check-ins (null otherwise) |
| `POST /api/comments` `{athlete_id, body, jump_id?, session_id?}` | admin, coach of club; athlete for themselves | add to the athlete's thread; a referenced jump must be that athlete's, a session visible to them; audit `comment_created` (no body in the audit) |
| `GET /api/comments?athlete_id=&limit=&offset=` | scope as jumps | the thread, newest first, with author name and role |
| `GET /api/me/unread` | athlete | `{comments: n}` written by others and not yet read |
| `POST /api/me/read` | athlete | marks all comments by others as read; returns 204 |
| `GET /api/events` | all | Server-Sent Events stream (`text/event-stream`) of `jump`, `checkin`, `comment`, `session` events visible to the caller (same scope rules), each with `{type, id, athlete_id?, club_id, at}` and no metric values or text; a comment line every 25 s keeps it open; at most 3 open streams per user |

Athlete JSON export adds check-ins, comments and session ids of jumps. `DELETE /api/me/data` also deletes
the athlete's check-ins and their comment thread. CSV export adds a session column. Paginated lists keep
limits 1–500. Unknown fields are rejected as in section 3.

### 5.3 Security additions

- Login is limited both per email (section 3) and per client address: 30 failures per address per
  15 minutes. Behind a reverse proxy the address comes from the proxy's `X-Forwarded-For` only when the
  server is started with `--proxy-headers --forwarded-allow-ips <proxy address>` (deploy notes).
- Expired tokens and login failures older than 15 minutes are deleted on each login.
- Responses add `Strict-Transport-Security: max-age=31536000` (browsers honour it only over HTTPS).
- The events stream authenticates with the same bearer header (the dashboard reads it with `fetch`, not
  `EventSource`, so the token never appears in a URL). Each keep-alive tick rechecks authentication;
  revocation, expiry or deactivation ends the stream. Stream slots are reserved before a response starts;
  a fourth stream returns 429. Proxy buffering is disabled for events.

### 5.4 Dashboard (additions)

New sections for staff: Groups (create, rename, delete, choose members), Sessions (list by date with
past/upcoming, create and edit, cancel, calendar download, and a session report table with trials done and
medians), Wellbeing (team table for a chosen day with the five items, wellbeing sum, load and a neutral
"below usual" mark; athlete page gets a wellbeing and load chart), Feedback (thread on the athlete page,
write a comment, optionally about a jump or session). Athletes see their sessions, their check-ins and
their feedback thread with a reply box. A live indicator shows when the events stream is connected; new
jumps, check-ins and comments refresh the open view without a reload. Bulgarian and English as before;
same accessibility, `textContent` and CSP rules.

### 5.5 App (additions)

Home shows the next visible sessions; choosing one before recording or picking a video attaches the result
to it and shows "attempt n of m". A daily check-in screen (five 1–5 choices with plain labels, optional
training minutes and RPE 0–10) saves through `PUT /api/me/checkins/{today}` and works offline through the
existing queue. A feedback screen shows the thread with an unread badge on home and a reply box; opening it
marks comments read. Bulgarian and English.

### 5.6 Idempotent uploads

`client_id` is a random UUID the app creates once per result and sends with every attempt. Unique per
`(athlete_id, client_id)`: a repeat returns 200 with the original `{id, created_at}` and stores nothing;
a new one returns 201. The same applies to check-ins through their natural key `(athlete_id, day)`.

## 6. Training, nutrition, form tracking and the assistant (added 2026-11-18)

Same rules as sections 3 and 5: roles and club scope on every read and write, consent before athlete data
is stored, strict validation, audit of every change, Bulgarian and English, no medical or diet-prescription
wording (no diagnosis, treatment, "cure", "fat-loss plan"; numbers are for discussion with the coach).
Nothing in this section may use code, data, names or designs taken from another product; methods are the
published, general ones named below, apart from the owner's own weight-trend model in 6.3.

### 6.1 Form tracker (core + app; on the phone, numbers only)

Exercises: `squat` (bodyweight or loaded, two legs) and `single_leg_squat` (left or right). The athlete
films a set; the phone runs the same pose model as for jumps and the core computes, per repetition: depth
(peak knee flexion, side view in the image plane, front view from world landmarks as in section 2),
frontal-plane knee angle (FPPA) at the deepest point in front view, left/right asymmetry of peak flexion
(two-leg squat), descent and ascent time, and whether the hips went below the knees (side view). A
repetition is one down-and-up cycle of the hip height with a minimum depth; partial and unclear repetitions
are counted separately and never silently. Set summary: repetition count, medians, worst repetition, and
warnings (`low_visibility`, `unclear_reps`, `single_leg_view`). Upload: `POST /api/form` with exercise,
side, view, fps, per-repetition numbers and the summary (no video, no landmarks); history and charts like
jumps. Validation: synthetic geometry tests, repetition counting on real clips with hand counts, and the
same honesty rules as `docs/validacia.md`.

### 6.2 Training (server, dashboard, app)

- `exercises(id, club_id, name, kind, lower_body)`: club exercise list (kind `strength`, `plyometric`, `mobility`,
  `conditioning`; `lower_body` marks the exercises where a coach allows the +5 kg step), seeded with common
  names by `python -m server.seed`; coaches add their own.
- `plans(id, club_id, title, group_id NULL, athlete_id NULL, starts_on, weeks, notes, created_by)` and
  `plan_items(id, plan_id, weekday, exercise_id, sets, reps, reps_max, load_kg NULL, rpe_target NULL, position)`:
  a weekly template assigned by a coach to one group or one athlete (both null: the whole club, both set: 422).
  `weekday` 0 is Monday; `reps` is the bottom and `reps_max` the top of the rep range (default: equal).
  The column is `position`, not `order`, which is an SQL keyword. Deleting a group deletes its plans.
- `set_logs(id, athlete_id, day, exercise_id, set_no, reps, load_kg NULL, rpe NULL, plan_id NULL,
  client_id NULL, created_at)`: what was actually done; idempotent like jumps (section 5.6): a repeated
  `client_id` returns 200 with the stored row. `day` from 30 days before today through tomorrow (UTC), wider
  than check-ins because the offline queue may send late. Athletes delete only their own sets.
- Progression suggestion (shown, never applied automatically): double progression per exercise, from the
  plan item and the last two logged sessions (days with sets for that exercise). A session reached the top
  when at least the planned number of sets were done, each of the planned sets reached `reps_max` and, when
  the item has an RPE target, was logged at or below it. It missed the bottom when any set was below `reps`.
  Two sessions at the top: suggest +2.5 kg on the last load (+5 kg for `lower_body` exercises). Two sessions
  missing the bottom: suggest −5 %. Otherwise keep; fewer than two sessions: keep, `need_two_sessions`.
  Weekly volume (sets, sum of reps × load, sets without a load counted apart) and the existing session-RPE
  load (`rpe × training_minutes` of the check-ins) are summarised per athlete and calendar week (Monday).
- API: `GET/POST /api/exercises`, `DELETE /api/exercises/{id}` (409 when in use); `GET/POST /api/plans`,
  `GET/PUT/DELETE /api/plans/{id}`; `POST /api/set-logs`, `GET /api/set-logs?athlete_id=&from=&to=`,
  `DELETE /api/set-logs/{id}`; `GET /api/athletes/{id}/training?weeks=` (weekly volume and suggestions);
  `GET /api/athletes/{id}/training.csv` (staff). Roles and club scope as for jumps and sessions: coach own club,
  athlete own data (plans: those assigned to them, their groups or the whole club), admin all, cross-club 404.
  Athlete writes need recorded consent. Audit actions: `exercise_created`, `exercise_deleted`, `plan_created`,
  `plan_changed`, `plan_deleted`, `set_logged`, `set_deleted`. A new set publishes a `set` event.

### 6.3 Nutrition (server, dashboard, app)

- Food list: a subset of about 330 USDA FoodData Central "Foundation" (2025-12-18) and "SR Legacy" (2018-04)
  foods (public domain, CC0; source and release recorded in the header of `server/data/foods.csv`), per
  100 g: energy, protein, carbohydrate, fat, fibre (missing fibre stored as 0); Bulgarian display name, USDA
  description and FDC id. The file is loaded idempotently at startup (`INSERT OR IGNORE` on the FDC id).
  Club foods (staff) and personal foods (athletes, consent needed) are added with the same five values.
- `food_logs(id, athlete_id, day, meal, food_id, grams, client_id, created_at)` (meal `breakfast`, `lunch`,
  `dinner`, `snack`; idempotent by `client_id`, same day window as sets) and `weights(id, athlete_id, day, kg)`
  (unique per day, like check-ins: `PUT /api/me/weights/{day}` 201 on create, 200 on replace; 20–400 kg).
- Weight trend: the owner's own numeric trend model, reconstructed from measured behaviour and reimplemented
  in original Python (`server/weight_trend/`, exponential trend smoothing over daily bodyweight). It replaces
  the plain moving average first considered here and is the only code in this section not written from a
  published general method. Dated kilograms (the last 180 days up to the latest weigh-in, UTC midnight) go in;
  `trend_kg`, `transient_kg` and `sigma_kg` come out for every calendar day between the first and the last
  weigh-in, shown beside the daily values. Fewer than two weigh-ins: no trend. The numbers are those of the
  original model; a test compares both implementations on a fixed series.
- Expenditure estimate (energy balance): over the last 28 days (shortened to start at the first logged day,
  never below 14) with at least 10 logged food days and 8 weigh-ins inside the window, estimate = mean
  logged intake (mean of the daily kcal of days with food logged) − (trend change in kg × 7700 kcal) / days,
  where the trend change runs from the first to the last weigh-in of the window and days is the span between
  them. The response shows the window, the food-day and weigh-in counts and the words "estimate from your own
  logs". Below the data threshold: no estimate (`available: false`, `not_enough_data`).
- Targets: `nutrition_targets(athlete_id, kcal, protein_g, carbs_g, fat_g, set_by, updated_at)`, set by the coach
  or the athlete (`PUT /api/athletes/{id}/nutrition/target`, consent needed). `users.under_18` (athlete profile,
  default false) is set by a coach or admin with `PATCH /api/users/{id}/profile` and shown in user lists and the
  export. Guardrails: nobody can set less than 1500 kcal (422); an athlete marked under 18 cannot set a target
  below the current estimate, or any target while there is no estimate (a coach or admin can, above 1500);
  protein suggestion 1.4–2.0 g per kg of trend weight as a range, not a prescription. The app says these are
  numbers to discuss with the coach.
- Adaptive expenditure and the weekly program: the owner's own adaptive method, an original Python
  reimplementation of a reverse-engineered numeric method (`server/coaching.py`, exponential weight trend +
  14-day energy balance), test-only compared to the owner's reference implementation on a fixed synthetic
  history to 1e-9 (integers exactly). A day counts only when the athlete has explicitly confirmed it complete
  (`PUT /api/me/nutrition/days/{day}/complete`, consent needed; stored in `nutrition_day_complete` with a
  fingerprint of that day's diary, so a later edit stops the day counting); missing intake is never treated as
  zero, and a confirmed day with no food is a true zero. From a saved seed the expenditure steps forward one
  day at a time towards the trailing 14-day energy balance (mean logged intake over the 14 complete days minus
  the trend's weight change over the window × 7700 kcal/kg, divided by 14), with the daily move capped at
  150/7 kcal; it holds when a day is incomplete, a weigh-in is missing, or the trend cannot cover the window.
  `coaching_programs(athlete_id, effective_date, seed_kcal, mode, goal, rate_kg_week, protein_g, fat_g, applied,
  set_by, updated_at)` holds one program per athlete. `PUT /api/athletes/{id}/nutrition/program` (goal `maintain`
  /`lose`/`gain`, rate 0–2 kg/week, protein and fat grams; consent needed, same scope as 6.2) seeds the program
  from the one-shot estimate on first use (422 when there is not enough logged data yet) and recomputes the
  recommended daily targets: calories = expenditure ± 7700 × rate / 7 (maintain holds), protein and fat as given,
  carbs filling the remainder. The recommendation still passes through the guardrails before it is applied: the
  1500 kcal floor and the under-18 "no deficit without a coach" rule still hold, so a recommended deficit for an
  under-18 set by the athlete is shown (`applied: false`, with the guardrail reason) but not written to the
  target; otherwise it updates `nutrition_targets` so the daily summary shows targets-vs-actual as before.
  `GET /api/athletes/{id}/nutrition/expenditure` returns the daily expenditure series (current value and recent
  history); `GET /api/athletes/{id}/nutrition/program` returns the current recommendation and whether it is
  applied. Not enough confirmed days, or no program, yields no program rather than a guess. The server never
  imports the reference implementation at runtime.
- Daily summary: totals and kcal per meal against the target, remaining amounts, and averages over the 7 days
  ending at the chosen day (logged days only).
- API: `GET /api/foods?q=` (system, club and own foods), `POST /api/foods`, `DELETE /api/foods/{id}` (own or club
  custom foods, 409 in use); `POST /api/food-logs`, `GET /api/food-logs?athlete_id=&from=&to=`,
  `DELETE /api/food-logs/{id}`; `PUT/DELETE /api/me/weights/{day}`, `GET /api/athletes/{id}/weights`;
  `GET /api/athletes/{id}/nutrition/estimate`, `GET /api/athletes/{id}/nutrition/target`,
  `PUT /api/athletes/{id}/nutrition/target`, `GET /api/athletes/{id}/nutrition/summary?day=`;
  `GET /api/athletes/{id}/nutrition.csv` and `weights.csv` (staff). Scope as in 6.2. Audit actions:
  `food_created`, `food_deleted`, `food_logged`, `food_deleted_log`, `weight_logged`, `weight_deleted`,
  `target_set`, `profile_changed`, `coaching_program_set`, `nutrition_day_completed`,
  `nutrition_day_uncompleted`; CSV exports are audited as `csv_export` with the kind. New meals and
  weights publish `food` and `weight` events (no values); a program or a confirmed day publish `coaching` and
  `nutrition_day` events (no values).
- The athlete JSON export adds `set_logs`, `custom_foods`, `food_logs`, `weights`, `nutrition_target`,
  `coaching_program`, `complete_diary_days` and `under_18`; `DELETE /api/me/data` also deletes them, retaining
  the account, consent and plans.

### 6.4 Assistant ("AI помощник")

- `POST /api/assistant` accepts `{message, conversation_id?}` and returns `{conversation_id, reply, proposal}`.
  Any logged-in user can chat without recording consent. `GET /api/assistant/conversations` lists the
  caller's conversations; `GET /api/assistant/conversations/{conversation_id}` returns its turns, newest
  first. Both reads accept `limit` and `offset`; conversations cannot be opened by other users.
- The server calls an OpenAI-compatible chat-completions endpoint using `JUMPSCAN_LLM_URL` (the API base,
  for example a local `/v1` endpoint), `JUMPSCAN_LLM_MODEL`, and optional bearer-key file
  `JUMPSCAN_LLM_KEY_FILE`. There is no default host or key. An unconfigured or unavailable model returns
  503 from chat; history, confirmation, and the rest of the site do not depend on the model.
- Read tools cover the caller's scoped jump and team summaries, jumps, sessions and reports, check-ins,
  exercises, plans, set logs and training summaries, food and weight logs, food search, nutrition estimates,
  targets, summaries, programs and expenditure, and comments. Form sets are not exposed until there is a
  scoped REST history endpoint for them (6.1).
- Write tools can propose logging a set, food, or weight, saving a check-in, creating or editing a session,
  assigning or replacing a plan, setting a nutrition target or goal and rate, or adding a staff note.
  Tools use the same scoped operations and input validation as REST, running as the caller. Comments and
  plans assigned to individual athletes require those athletes' recorded consent in both REST and assistant
  operations. The assistant rejects a program proposal if its recommended target would not pass the guardrails.
- A proposal is `{proposal_id, expires_at, actions: [{tool, args, human_summary}]}` (otherwise `null`).
  No domain writes occur before `POST /api/assistant/confirm {proposal_id}`. Confirmation does not call
  the model. It revalidates against current permissions and data, then applies all actions in order in
  one transaction, or none if any action fails. A committed proposal is single-use and expires after ten
  minutes; it is bound to the proposing user. Unknown and other users' proposals return 404; used and
  expired proposals return 409 while retained. Each applied action adds an `assistant_action` audit entry
  with the proposal ID and tool name, not the action arguments or body.
- User text and tool output are untrusted data; prompt instructions do not grant permissions. Tool results
  are capped at 8 KiB each, with up to four tool rounds and four calls per round. The system prompt tells
  the model to match Bulgarian or English and refer medical advice, diagnoses, and diet prescriptions to
  the coach. All assistant endpoints share a rate limit of 20 requests per ten minutes per user.
- Only user and final assistant text are stored as conversation turns, not the raw tool transcript. Turns
  expire after 30 days, as do conversations idle for 30 days; pruning runs at startup and on assistant
  requests. Expired proposal rows are pruned after a day. `DELETE /api/me/data` removes the athlete's
  conversations and proposals. The demo seed contains no AI data.
- Dashboard: an assistant panel on every page. App: an "AI помощник" screen with the same confirmation
  step.
