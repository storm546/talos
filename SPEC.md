# Talos (formerly JumpScan): spec and contracts

Three parts in one repo. Each worker touches only its own directory.
Design and development principles: `PHILOSOPHY.md` (read it first).

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
| `GET /api/jumps/baseline?test=cmj` | athlete | Median of the athlete's first three measured jumps of the requested kind. Omits metrics missing from any of the three. `before_id` limits it to earlier jumps; `exclude_client_id` excludes an upload still awaiting confirmation. |
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
object checks on every read and management operation; login limit of 5 failures per email and client address pair per 15 minutes;
constant-time credential checks; no stack traces or submitted secrets in errors; 16 KiB body limit
(64 KiB for form uploads, section 6.1).
Paginated lists accept limits 1–500 and nonnegative offsets. Exports and summaries include all jumps.
Complete downloads and the check-in list are never truncated: serialized output beyond 256 KiB
rolls to temporary storage before delivery. Use the available page or date filters for smaller histories.
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

Run: `uvicorn server.main:app --no-server-header`. Database path from `JUMPSCAN_DB`, default `server/jumpscan.db`.

## 4. app (Android, Kotlin, package `jumpscan.app`)

minSdk 26, compileSdk and targetSdk 35. Plain Views, no Compose. Dependencies limited to AndroidX
core/appcompat and `com.google.mediapipe:tasks-vision`. Networking with `HttpURLConnection` and
`org.json`. Uses `:core` for all maths.

Screens:
1. Login: server URL, email, password. Token kept in app-private storage.
2. Home: choose a test type, then record in the app or pick a video with the system photo picker.
   CameraX targets a fixed 60 fps and offers 120 or 240 fps only when supported by the rear camera's
   recording session. The live framing check looks for the whole body and headroom before a three-second
   audible countdown. Recording stops about two seconds after detected landing, at the recording limit, or
   when the athlete taps Stop. Imports remain available if fixed-rate capture is unavailable. Recordings
   are checked against their actual presentation timestamps and duration before analysis.
3. Analysis: decode the clip frame by frame on a background thread, run Pose Landmarker
   (`pose_landmarker_heavy.task` bundled in assets, VIDEO mode), pass frames to `JumpAnalyzer`,
   show progress.
4. Result: measured flight time and height show the view-specific typical error from `docs/validacia.md`
   §4.8 (mean absolute error at 33 fps, not a confidence interval). The flight-time error is propagated
   through the height formula at the measured flight time. The athlete's own same-test baseline and
   difference appear alongside the estimates when available. Contact flexion of 30° or more triggers a
   technique reminder, not a diagnostic threshold. "Upload" sends only the metrics JSON to `POST /api/jumps`.
5. History: the athlete's own jumps from `GET /api/jumps`.
6. Profile → Diagnostics (also available offline): the latest 200 local jump and form runs. The Android
   share sheet exports a CSV and a plain-text summary, with no video, landmarks, or account data.
   The guided test pairs selected clips with 20 jump trials and collects manual counts for three squat sets.
   A jump step advances when analysis completes and the clip is within 5 fps of the selected rate; failed
   attempts and analyses with no flight are visible in the report. The trial view is the instructed view.

Diagnostic wall times use a monotonic clock. Decode time includes frame preparation (for live camera,
copy and rotation of the delivered RGBA buffer); pose time is the synchronous video call or the live
submit-to-result interval. Frames analysed count completed pose calls used by the run, not necessarily
frames with a visible person. Jump preview extraction adds a decode but no pose call. Manual jump
corrections and landmark fixtures do not rerun pose; model and delegate are blank. Live FPS is the
number of analysed frames divided by the set duration. Live drops are completed camera captures during
the set minus frames analysed, including backpressure and frames still in flight at Stop; callback
delivery near the two boundaries can affect the count. Debug live replay uses 50 ms sample slots instead
of camera captures and is labelled separately. Battery percentage and temperature (°C) are snapshots;
unavailable readings or empty timing samples are blank, not zero.

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
| `POST /api/jumps` | athlete | section 3 behaviour plus optional `session_id` (must be visible to the athlete and not cancelled, else 422 with `code: "jump_session_unavailable"` in addition to `detail`) and optional `client_id` (section 5.6) |
| `PUT /api/me/checkins/{day}` | athlete | create or replace the caller's check-in for `day`, from 6 days before today (UTC) through today+1 (UTC), inclusive, else 422; the app sends the phone's local calendar date and displays it in the locale's date format without a UTC label; requires consent like jumps; returns the stored check-in; 201 on create, 200 on replace |
| `GET /api/checkins?athlete_id=&from=&to=` | scope as jumps | check-ins, newest day first |
| `GET /api/checkins/team?day=` | admin, coach | for each active athlete of the coach's club (admin: filter `club_id`): the check-in for `day` (default today) or null, `wellbeing` = sum of the five items (5–25), `load` = `rpe × training_minutes` (session RPE in arbitrary units), and `below_usual: true` when `wellbeing` is at least 3 below the median of the athlete's previous 14 days with at least 5 check-ins (null otherwise) |
| `POST /api/comments` `{athlete_id, body, jump_id?, session_id?}` | admin, coach of club; athlete for themselves | add to the athlete's thread; a referenced jump must be that athlete's, a session visible to them; audit `comment_created` (no body in the audit) |
| `GET /api/comments?athlete_id=&limit=&offset=` | scope as jumps | the thread, newest first, with author name and role |
| `GET /api/me/unread` | athlete | `{comments: n}` written by others and not yet read |
| `POST /api/me/read` | athlete | marks all comments by others as read; returns 204 |
| `GET /api/events` | all | Server-Sent Events stream (`text/event-stream`) of `jump`, `form`, `checkin`, `comment`, `session` events visible to the caller (same scope rules), each with `{type, id, athlete_id?, club_id, at}` and no metric values or text; a comment line every 25 s keeps it open; at most 3 open streams per user |

Athlete JSON export adds check-ins, comments and session ids of jumps. `DELETE /api/me/data` also deletes
the athlete's check-ins and their comment thread. CSV export adds a session column. Paginated lists keep
limits 1–500. Unknown fields are rejected as in section 3.

### 5.3 Security additions

- In addition to the email and client address pair limit (section 3), login is limited to 30 failures per address per
  15 minutes. Behind a reverse proxy the address comes from the proxy's `X-Forwarded-For` only when the
  server is started with `--proxy-headers --forwarded-allow-ips <proxy address>` (deploy notes).
- Expired tokens and login failures older than 15 minutes are deleted on each login.
- Responses add `Strict-Transport-Security: max-age=31536000` (browsers honour it only over HTTPS).
- The events stream authenticates with the same bearer header (the dashboard reads it with `fetch`, not
  `EventSource`, so the token never appears in a URL). Each keep-alive tick rechecks authentication;
  revocation, expiry or deactivation ends the stream. Stream slots are reserved before a response starts;
  a fourth stream returns 429. Proxy buffering is disabled for events. Each stream holds up to 64 pending
  events. On overflow it sends `{"type":"resync"}` and closes. There is no event replay: reconnect first,
  then reload the current view through authorized REST reads. The dashboard refreshes after reconnecting
  without replacing an active, unsent draft.

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
Methods are the published, general ones named below, apart from the owner's own weight-trend model in 6.3.
Borrowing follows `PHILOSOPHY.md`: ideas and behaviour from other apps are welcome; code only from open-source
projects with a GPL-3.0-or-later compatible licence, with attribution in `LICENSES-AUDIT.md`; no code, assets,
names or text from proprietary products.

### 6.1 Form tracker (on-device analysis; server stores numbers only)

The form tracker offers live coaching and recorded-video analysis for every entry in
`core/src/main/resources/form_exercises.json`. Entries that have passed the repetition benchmark in
`core/VALIDATION.md` are listed normally. The bar is on held-out sets in the live 15 fps path: exact in at least 90%
of sets, none off by more than one, at most 10% unclear, and at least 8 test sets from at least 3 people. Every other
entry carries `experimental: true` with one-line `experimental_reason_en`/`experimental_reason_bg`. The app lists
these in a collapsed "Experimental" section, each marked "Not validated" with its reason; live and recorded
analysis work the same for them. The server accepts and lists every entry. The catalogue defines the English/Bulgarian names, mode,
anatomical side selection, recommended view, joint or position signal, thresholds, checks, and bilingual
cues. Live mode uses the MediaPipe lite model in `LIVE_STREAM` on the CPU; recorded video continues to use
the heavy model in `VIDEO`. Both use `FormStream`. Given identical timestamped landmarks, aspect ratio,
and view policy, final metrics agree; different models or frame cadences can produce different inputs and
results. The live screen overlays a skeleton, checks framing and view before starting, displays a rep or
hold counter and technique cues, and speaks with an offline Android voice when available. Rep counts
are provisional until the set ends. A technique cue follows a completed attempt only if a fault on that
attempt also occurred in one of the previous two. At most one cue is presented per attempt; athletes can
mute technique cues without muting counts. If no matching offline voice is installed, technique feedback
is visual and completed repetitions are marked with a beep. Live sets end at 60 seconds of tracked input
or when stopped. No video, audio or landmarks are
uploaded. Recorded results retain the existing pending-upload queue; live results use the same authenticated,
idempotent endpoint and can be retried from the result screen, but are not queued offline.

The stream tracks rest-to-extremum-to-rest cycles with a minimum excursion and separate start/finish bands.
It reports complete, partial, and unclear candidates separately. Repetition timing fields retain their
legacy names; for movements that start low, descent/ascent fields refer to the first/second phase rather
than physical vertical direction. Available checks are sampled at the signal extremum. Holds count observed
time in the exercise posture and separately time inside the technique band; an unobserved stretch longer
than 300 ms adds no time (shorter low-visibility or dropped-frame stretches are bridged). The two squat entries use the original whole-set calibration and measurement rules: live counts are
provisional until `finish`. Depth is peak knee flexion (side: image plane; front: world), FPPA is measured
at deepest flexion (front only), asymmetry uses both legs, and hips below knees is available from the side.

Validated status (benchmark of 21 November 2026, round 4; hand counts, Countix and MM-Fit; see `core/VALIDATION.md`):
- A repetition counter passes on at least 8 held-out live-equivalent sets from 3 people, with at least 90%
  exact counts, no set off by more than one, and at most 10% unclear repetitions.
- **Squat** is validated: held-out live 15 fps, 21 of 22 sets exact, 12 people, none off by more than one, no
  unclear repetitions.
- **Plank** is validated for hold time: 9 of 9 held-out sets within max(1 s, 10%), 3 people. In-band time is
  not validated (8 of 9 held-out sets live, below the bar).
- **Experimental**, with their held-out live exact rates (adaptive counting of 24 November 2026 included; no
  exercise met that round's promotion bar, see `core/VALIDATION.md`):
  - lateral raise 83% of 12;
  - overhead press 83% of 12;
  - bent-over row 73% of 15;
  - push-up 70% of 27 (74% recorded);
  - jumping jack 68% of 28;
  - biceps curl 62% of 13;
  - pull-up 59% of 17;
  - lunge 53% of 36;
  - glute bridge, which has 2 counted sets;
  - the other 9 entries, which have no counted or timed test videos.

Catalogue signals may set:
- `"limbs": "each"`: in a front view with no side chosen, each arm or leg is counted separately, and overlapping
  repetitions on both sides count once (with `"count": "adaptive"` in every view, so alternating arms seen in
  profile still count);
- `"limbs": "best"`: one side drives the count in every view, the better-seen one, chosen between repetitions;
- `"count": "adaptive"`: a repetition is a rise of at least half of `min_range` above the athlete's own rest (the
  lowest value since the previous repetition), ended by a return of 60% of its own excursion; it is complete when
  the excursion reaches `min_range`, otherwise partial. `up`/`down` then only give the direction. A set whose first
  movement is a return began mid-repetition and reports `incomplete_start`. Used by overhead press, lateral raise,
  biceps curl, bent-over row and jumping jack;
- `min_visibility` for the signal and its gate (checks keep 0.5);
- `signal_front`: an alternative driving signal for front or diagonal views. Push-up detects the view from
  plank posture when possible. Its `rise:wrist:nose` signal uses the height of the nose above the wrist,
  divided by the larger of shoulder width and torso length, and can use one visible wrist.

In the squat counter, unusable frames are skipped, and only an unobserved stretch longer than max(200 ms, 1.5 median
intervals) splits an observation. `FormStream` interrupts after 300 ms without a usable frame.
Burpees are not included: the fast transition between upright and floor phases often hides joints, and
push-up/jump variants cannot reliably be distinguished by the single cycle signal. No check measures load,
contact with equipment, or medical outcomes. The checks are technique feedback only.

Form JSON (produced by `core`, stored by `server` at `POST /api/form`, charted by app and dashboard;
field list from the core contract): top level `exercise` (any catalogue ID), `side` ("L"|"R" for a sided
exercise, null otherwise), `view` ("front"|"side"), `fps` (>0 and ≤480),
`quality` (0–1), `repetitions[]` (up to 100 candidate cycles), `summary`, and
`warnings[]` (any of `low_visibility`, `unclear_reps`, `single_leg_view`). Optional `mode` ("reps"|"hold")
must match the catalogue; optional `source` is "live" or "video". Older payloads may omit both. Each entry:
`index` (1-based), `status` ("complete"|"partial"|"unclear"), `reasons[]` (empty for complete, only
`below_min_depth` for partial, up to five of `incomplete_start`, `incomplete_end`, `tracking_gap`, `too_fast`,
`degenerate_geometry` for unclear), `start_ms`, `bottom_ms` (int|null), `end_ms`, `depth` (0–180°|null),
`knee_flexion_peak_l/_r` (0–180°|null), `fppa_l/_r` (−90–90°|null, front only),
`flexion_asymmetry_pct` (0–100|null, both legs only), `descent_time_ms`/`ascent_time_ms` (int|null),
`hip_below_knee` (bool|null, side only). Optional `checks` maps catalogue check keys to finite numbers or
null. Unclear candidates have null measurements and `bottom_ms`; they do not report checks.
`summary` holds `repetition_count`, `partial_count`, `unclear_count`, and `median_` fields for `depth`,
`knee_flexion_peak_l`/`knee_flexion_peak_r`, `fppa_l`/`fppa_r`, `flexion_asymmetry_pct`,
`descent_time_ms` and `ascent_time_ms`, calculated on complete repetitions only (null if unavailable).
It also has `hip_below_knee_count` (side view only) and `worst_repetition` (index of the shallowest
complete repetition; earliest on ties; null without complete repetitions). The legacy squat-specific
measurements remain null for other exercises. Holds have empty `repetitions`, zero rep counts, and summary
`hold_ms` and `in_band_ms` integers with `0 ≤ in_band_ms ≤ hold_ms`. This is not a composite score.

- `POST /api/form` is for athletes with recorded consent. It accepts optional `client_id` as in 5.6 and
  returns `{id, created_at}` with 201 on insert or 200 on retry. It audits `form_logged` and publishes a
  scoped `form` event after commit, only for a new set. Unknown fields are rejected at every level.
- `GET /api/form?athlete_id=&limit=100&offset=0` returns the form JSON plus `id`, `athlete_id`, `created_at`
  and `client_id`, newest first (ID descending on ties). Athletes see only their own data; coaches see
  their club's athletes (cross-club targets return 404); admins see all. Staff can omit `athlete_id` to
  list all sets in scope. The assistant's `list_form_sets` tool uses the same scope.
- `GET /api/form/exercises` is read-only for signed-in users. It returns the catalogue entries' `id`,
  `name_bg`, `name_en`, `mode`, `sided`, `view`, and `checks` (`key`, `label_bg`, `label_en`, `unit`). Server
  uploads and discovery use `core/src/main/resources/form_exercises.json` relative to the application root.
  Containers must package that file or mount it and set `JUMPSCAN_FORM_CATALOGUE` to its path. Without it,
  the server keeps legacy squat uploads working but returns 503 for discovery and extended uploads.
- Athlete JSON export includes `form_sets`; `DELETE /api/me/data` removes them. Scoped staff export
  `GET /api/athletes/{id}/form.csv` lists all sets oldest first, with summary columns and warnings and
  repetitions (including checks) as JSON cells, followed by `mode`, `source`, `hold_ms`, and `in_band_ms`.

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
- Read tools cover the caller's scoped jump and team summaries, jumps, form sets, sessions and reports, check-ins,
  exercises, plans, set logs and training summaries, food and weight logs, food search, nutrition estimates,
  targets, summaries, programs and expenditure, and comments.
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
  are capped at 8 KiB each, with up to six tool rounds and four calls per round. The system prompt tells
  the model to match Bulgarian or English and refer medical advice, diagnoses, and diet prescriptions to
  the coach. All assistant endpoints share a rate limit of 20 requests per ten minutes per user.
- Only user and final assistant text are stored as conversation turns, not the raw tool transcript. Turns
  expire after 30 days, as do conversations idle for 30 days; pruning runs at startup and on assistant
  requests. Expired proposal rows are pruned after a day. `DELETE /api/me/data` removes the athlete's
  conversations and proposals. The demo seed contains no AI data.
- Dashboard: an assistant panel on every page. App: an "AI помощник" screen with the same confirmation
  step.

## 7. Nutrition, version 2 (added 2026-11-21)

The owner asked for a full food tracker in place of the basic one, built on his own reconstruction in
`/opt/personal-nutrition` (read it as the reference for behaviour: `core.py`, `server.py`, `common_catalog.py`,
`nutrients.py`, `nutrient_totals.py`, `nutrition_days.py`, `goals.py`, `analytics.py`, `ai_food.py` and
`static/*.js`). Port behaviour into JumpScan's own storage, roles, consent, audit and guardrails from section 6;
no third-party product names or hashes in code, UI or docs (the existing product-string test covers new files).
Everything in 6.3 keeps working; existing endpoints and responses stay compatible.

### 7.1 Server
- **Food catalogue.** Ship the USDA FoodData Central catalogue (CC0; SR Legacy 2018-04, Foundation, FNDDS) as a
  read-only SQLite file with full-text search (`server/data/common_catalog.sqlite`, copied from the reference,
  with a header or metadata row naming the source and licence). `GET /api/foods?q=` searches the existing foods
  first, then the catalogue; catalogue hits carry `catalog_id`, per-100 g values, micronutrients and USDA
  portions. Logging a catalogue food materialises it once as a system food (idempotent by `catalog_id`).
- **Servings.** Foods gain `servings` (label, grams; at least "100 g"; USDA portions and label serving sizes),
  `brand`, `barcode` (8–14 digits, unique when set) and `source`/`source_url`. `food_logs` gains `serving_label`
  and `quantity`; `grams` stays the stored truth.
- **Barcode.** `GET /api/foods/barcode/{code}` (8–14 digits, else 422): local food with that barcode first; else
  Open Food Facts product API v2 (server-side, 5 s timeout, User-Agent `JumpScan/1.2 (school project)`, fields as
  in the reference `barcode_food`), normalised to per-100 g kcal/protein/carbs/fat/fibre plus sugar, salt/sodium,
  saturated fat when present, and saved as a club food with `source` "Open Food Facts" and the product URL
  (attribution: data under the ODbL). Not found: 404 with a "create it" hint. Network failure: 503, nothing saved.
  The athlete can correct a scanned food (`PATCH /api/foods/{id}`, own/club scope as for delete).
- **Micronutrients.** Port the reference nutrient definitions (`GET /api/nutrient-definitions`) and per-day
  totals; `GET /api/athletes/{id}/nutrition/summary` gains `nutrients` (amount, unit, reference value when
  defined). Missing values are reported as missing, never as zero.
- **Diary editing.** `PATCH /api/food-logs/{id}` (grams/serving/quantity/meal/day), `POST /api/food-logs/copy`
  (from day[, meal] to day[, meal]; idempotent by `client_id`), `POST /api/food-logs/batch` (up to 50 entries,
  all or nothing). Quick add: a log may carry kcal/protein/carbs/fat with no food (`food_id` null, a label).
- **Recipes.** `recipes(id, owner_id, club_id, name, servings_count)` + `recipe_items(food_id, grams)`; a recipe
  is loggable like a food (per serving or grams); totals computed from items. CRUD at `/api/recipes`.
- **Suggestions.** `GET /api/foods/recent?meal=` (the athlete's recent and frequent foods, at most 20) for
  one-tap logging.
- **Goals and phases.** Port the reference goal model onto `coaching_programs`: a phase (`lose`/`maintain`/
  `gain`) with target weight and end date gives the rate; `GET /api/athletes/{id}/nutrition/goal` shows progress
  from the weight trend (current trend, target, projected date). All 6.3 guardrails still apply.
- **Analytics.** `GET /api/athletes/{id}/nutrition/analytics?days=7|30|90`: daily kcal and macros, averages over
  logged days, adherence to target (days within ±10 % of kcal), trend weight series and expenditure series.
- **AI meal entry.** `POST /api/me/nutrition/ai-parse` sends a free-text meal ("2 eggs and toast") to the
  configured local LLM (JUMPSCAN_LLM_*; 503 when not configured), matches the items against foods and the
  catalogue, and returns a proposal; nothing is logged until the client posts it through `batch`.
- Audit: `food_updated`, `food_scanned`, `food_logs_copied`, `food_logs_batch`, `recipe_created`,
  `recipe_updated`, `recipe_deleted`, `goal_set`; events as in 6.3, no values. The athlete export includes recipes.

### 7.2 App (Android)
- **Barcode scanner:** CameraX preview + ML Kit barcode scanning, bundled (offline) model, formats EAN-13,
  EAN-8, UPC-A, UPC-E; torch toggle; vibrate on hit; result goes to the server lookup; 404 opens "create food"
  pre-filled with the barcode; offline shows a clear message and lets the athlete enter the food manually.
- **Diary:** a day view with the kcal ring and protein/carbs/fat bars against the target, meals as a timeline,
  swipe to delete, tap to edit serving and quantity, copy yesterday or a meal, mark day complete.
- **Add food:** one search across recent, own, club and catalogue foods; serving picker with quantity; quick
  add of macros; recipes; scan button; AI text entry with a confirm step.
- **Insights:** weight log with the trend line, expenditure and program card (weekly check-in), goal progress,
  7/30/90-day analytics, micronutrient list for the day.
- Bulgarian and English strings, light and dark, the existing design system (graphite and timing orange, the
  `UiCards.kt`/`Plots.kt` kit). Offline queue as for other uploads.

### 7.3 Dashboard
Coach view of an athlete gains nutrition adherence, the analytics chart and goal progress.

### 7.4 Nutrition follow-ups (added 2026-11-22)
Defaults chosen by the orchestrator after the owner said "keep working"; the owner may reverse them.
- **Goal form:** the athlete sets or changes their goal (phase, target weight, end date) in the app's Insights;
  a coach sets it from the dashboard's athlete nutrition view. Same endpoint, guardrails and audit as 7.1.
- **Scanned-food corrections:** foods record `created_by`. The athlete who scanned or created a club food may
  correct it with `PATCH /api/foods/{id}` until another athlete has logged it; staff may always. Others: 403.
- **Language:** nutrient definitions carry `name_bg` and `name_en`; the app shows the one for its language.
  Recipe serving labels are built by the client from a count and unit, not as server text.
- **Copy from an empty day:** the server answers 422 with `code: "nothing_to_copy"`; the app shows "Nothing to
  copy from that day" (bg: "Няма какво да се копира от този ден").
- **Seed:** the coaching demo athlete has one consistent weight series that moves towards the goal, so the
  projected date shows.
- **Licences:** `LICENSES-AUDIT.md` lists ML Kit barcode scanning (Google APIs terms, bundled model) and Open
  Food Facts data (ODbL, attribution on scanned foods).

## 8. Personal records and club leaderboard (added 2026-11-22)

Records are computed from data the server already holds; nothing new is measured.
- **Personal records:** `GET /api/athletes/{id}/records` (athlete own, coach own club, admin all; cross-club
  404) returns, per measure, the best value, its date and source id, and the previous best: jump height
  (best of non-warning jumps; uncertain results excluded and counted), flight time, heaviest logged set per
  exercise (load and reps), estimated one-rep max per exercise (Epley, sets of at most 10 reps), and longest
  validated form hold. A new record publishes a `record` event (no values) and the athlete's app shows it.
- **Leaderboard:** `GET /api/clubs/me/leaderboard?measure=jump_height|flight_time&period=30|90|all&group_id=`
  (members of the caller's club). Each athlete appears only if they opted in
  (`PATCH /api/me/profile {leaderboard_opt_in: bool}`, default false); athletes marked under 18 appear only
  with their coach's confirmation as well (`PATCH /api/users/{id}/profile {leaderboard_allowed: bool}`).
  Display name: first name and last initial. Ranking uses each athlete's best valid result in the period;
  ties share a rank. Coaches and admins see everyone opted in plus a count of those not shown.
- **App:** a Records screen (personal bests with dates, "new record" highlight using the height ruler) and a
  Leaderboard screen (period and measure switch, own row pinned, opt-in toggle with a plain explanation).
- **Dashboard:** club leaderboard panel and per-athlete records.
- Audit: `leaderboard_opt_in_changed`, `leaderboard_allowed_changed`. Export includes records and opt-in.

## 9. Fitness toolkit (direction set by the owner 2026-11-22)

The owner's direction: JumpScan grows from a jump-scanning app into a fitness "Swiss army knife", the way his
Hermes Companion grew from a chat client into a homelab toolkit. Jump scanning stays the flagship tool; every
other tool shares the same accounts, roles, consent, offline queue, events, audit and design system, and each
tool is useful alone. Tools that run entirely on the phone still sync their results so the coach sees them.
Built in waves; each wave ships as a beta.

**Wave 1 — toolkit shell and the gym floor**
- **Tools hub (app):** a home "Tools" grid that groups every tool (Measure: jump, form, timers; Train: workout,
  plans, records; Fuel: nutrition; Body: weight, check-in; Club: sessions, feedback, leaderboard; Ask: assistant),
  with pinned favourites and recent tools. Existing screens move under it; no feature is lost.
- **Workout session:** start an empty workout or from a template or today's plan; exercise picker with search
  and recent; per-set rows (load, reps, RPE, done tick) pre-filled from the last session; automatic rest timer
  after each done set (per-exercise default, ±15 s, notification and vibration when it ends, works with the
  screen off); supersets; notes; finish summary (duration, sets, volume, records hit). Sets go through the
  existing `set_logs` + offline queue. Server adds `workouts(id, athlete_id, started_at, ended_at, title,
  template_id NULL, notes, client_id)` and `set_logs.workout_id NULL`, and `workout_templates(id, owner_id,
  club_id NULL, title, items JSON)` (athletes own, coaches share with the club). API `/api/workouts`,
  `/api/workout-templates`, scope and audit as 6.2.
- **Calculators (on the phone):** plate loading (bar 20/15/10 kg, available plates editable), one-rep max
  (Epley and Brzycki, with a percentage table), warm-up sets for a working weight.
- **Interval timer (on the phone):** intervals, Tabata, EMOM, AMRAP and plain stopwatch; saved presets;
  large countdown, audio and vibration cues, keeps running with the screen off (foreground service); a
  finished timer can be logged as a conditioning session (check-in training minutes).

**Wave 2 — readiness and load (revised 2026-11-22 from logs/research/bevel-report.md)**
Every score is JumpScan's own method from published sports science, shows "still learning" until it has
enough data, explains what shaped it, and is framed as information for athlete and coach, never medical advice.
- **Personal baselines:** per athlete, rolling 28-day mean and SD (minimum 7 values, else "still learning") of
  morning resting heart rate, HRV as ln(RMSSD) typed in the check-in (optional new fields), check-in wellbeing
  and jump height. A value is "below normal" only when it differs from the baseline by more than the smallest
  worthwhile change (0.5 x SD) and the athlete's typical error (Hopkins 2000; Plews 2013; Buchheit 2014).
- **Jump readiness check:** a quick morning test of 2-3 countermovement jumps through the normal jump flow
  (`test_type` "readiness"); the best valid jump is compared with the athlete's readiness baseline
  (Claudino 2017). Uncertain jumps are excluded and counted.
- **Readiness:** a daily status (good / normal / take care / still learning) from the baselines above plus
  sleep, with a list of the factors behind it. `GET /api/athletes/{id}/readiness?days=` (scope as 6.2).
- **Sleep score:** bedtime and wake time in the check-in (optional) give duration against an age-based need
  (Hirshkowitz 2015) and night-to-night regularity (Phillips 2017).
- **Load:** session-RPE load per day (several sessions per day allowed: practice, gym, match, each with type,
  minutes and RPE; workouts and timers can create one), weekly monotony and strain (Foster 1998, 2001), and the
  acute:chronic ratio with exponentially weighted averages (Williams 2017), shown with its limits.
- **Coach team board (dashboard):** one row per athlete with today's check-in, readiness, jump vs baseline,
  load ratio and a "not checked in" mark; sort and filter by group; a "nudge" button sends a reminder event
  to athletes who have not checked in (audited, at most once a day per athlete).
- **Coach morning digest:** the server collects the facts for the coach's club (who is below normal and why,
  missing check-ins, load spikes); the local LLM only phrases them; with no LLM a plain list is shown.
- **App:** readiness card on the hub, readiness jump test entry, check-in fields for RHR/HRV/sleep times,
  sessions list per day, load chart, reminder notifications at the athlete's chosen time.
- Kept from the first draft for later (done in 10.7 and 10.5): body measurements (Navy formula), progress photos on the phone only,
  Health Connect import.

**Wave 3 — more camera tools from the same pose engine**
- Range-of-motion check (knee, hip, shoulder angles held still, left vs right).
- Balance test (single-leg stance time and sway).
- Sprint and reaction timing with the camera (start and finish line crossing), and a 5-0-5 / T-test timer.
- Outdoor cardio with GPS (distance, pace, splits; route stays on the phone, summary synced).

### 9.3 Wave 3 contract (added 2026-11-23)

Four on-phone tools. The phone keeps every image, landmark, audio sample and GPS fix; only the numbers
below are uploaded. Same rules as 6.1/6.2: athlete writes need recorded consent, staff read their club
(cross-club 404), admins read all, unknown fields are rejected, every new row is audited and published.

**Core (`jumpscan.core`, pure functions, JUnit with hand-checked numbers)**
- `RangeOfMotion`: joints `knee_flexion` (180 minus image hip-knee-ankle angle), `hip_flexion` (180 minus image
  shoulder-hip-knee angle), `shoulder_flexion` and `shoulder_abduction` (image hip-shoulder-elbow angle); x is
  multiplied by the image aspect as in section 2. Knee, hip and shoulder flexion need a side view
  (`FormStream.cameraView`), shoulder abduction a front view; otherwise the tool abstains with `wrong_view`.
  A frame counts only when the three joints have visibility of at least 0.5. The held value of one side is the
  median of the still window with the largest median: a window of at least 1000 ms and 5 frames whose angles
  span at most 5 degrees. No still window gives null for that side. The reported error is the measured
  side-view knee image error from `core/VALIDATION.md`: 10 degrees (pooled RMSE 10.4, bias -4.8). Hip and
  shoulder angles have no reference measurement; they report the same 10 degrees and the warning `not_validated`.
  A left/right difference smaller than the error is shown as "within the measuring error".
- `Balance`: front view, standing on one leg. The lifted foot is the ankle higher than the other by more than
  8% of the stance leg length (hip to ankle, image plane, aspect-corrected) while the stance knee is bent
  less than 30 degrees (a squat shortens the leg and must not start a stance); the stance starts after 300 ms of
  that and ends at the first frame of a 200 ms run below 4% (`touch_down`), when the hip midpoint moves more than
  25% of leg length from its position over the first 500 ms (`sway`), after 300 ms without the hips and ankles
  visible (`lost_tracking`), at 60 s (`time_limit`) or when stopped (`stopped`). Stance time is end minus start
  from frame timestamps. Sway is the RMS distance of the hip midpoint from its mean over the stance, in percent
  of leg length. Pose jitter adds to sway, so compare an athlete with themselves on the same phone.
- `LineCrossing`: the camera is still and the timing line is the vertical centre band (8% of the frame width).
  Activity is the fraction of band pixels whose luminance changed by more than 24 levels since the previous
  frame. A crossing is the timestamp of the first frame with activity at least 0.12 after at least 300 ms
  below 0.04. Precision is one median frame interval, shown with every time. A start is a crossing or the
  audio cue; the cue time is when the tone is requested, so cue-started times also contain the phone's
  unmeasured audio output delay and the athlete's reaction. Reaction: a tone after a random 2–5 s delay; the
  reaction time is the first whole-frame activity of at least 0.03 after the cue minus the cue time; activity
  before the cue is a false start and is not saved; above 1500 ms is not saved.
- `GpsTrack`: fixes with horizontal accuracy above 25 m are dropped; a fix closer than 5 m to the last accepted
  fix is held back (standing still); a step implying more than 12 m/s (25 m/s when cycling) is dropped. Distance is the haversine sum
  (R = 6 371 000 m) of accepted steps. Splits are the times at which the distance passes each whole kilometre,
  linearly interpolated within the step; each split is that kilometre's time in seconds. Average pace is
  duration divided by kilometres (null below 10 m).

**Server**: `measurements(id, athlete_id, created_at, kind, data, client_id)`, unique per (athlete, client_id),
created by the idempotent startup migration.
- `POST /api/measurements` `{client_id, kind, ...}` (athlete, consent required): 201 `{id, created_at}`, a repeat
  `client_id` 200 with the original. Audit `measurement_logged` (detail `{kind}`), event `measurement`, record event
  as section 8. Fields per kind (numbers finite):
  - `rom`: `joint` (above), `view` (`front`|`side`), `left_deg`, `right_deg` (0–180 or null, not both null),
    `error_deg` (0–45), `quality` (0–1), `warnings` (subset of `low_visibility`, `not_validated`).
  - `balance`: `leg` (`L`|`R`), `stance_ms` (0–60000), `sway_pct` (0–100 or null), `ended_by` (above), `fps` (1–480),
    `quality` (0–1).
  - `sprint`: `distance_m` (1–400), `time_ms` (100–120000), `start` (`cue`|`crossing`), `precision_ms` (0–1000), `fps`.
  - `reaction`: `time_ms` (50–1500), `precision_ms`, `fps`.
  - `agility`: `test` (`505`|`t_test`), `time_ms` (500–60000), `precision_ms`, `fps`.
  - `cardio`: `activity` (`run`|`walk`|`cycle`), `distance_m` (0–500000), `duration_s` (1–86400),
    `avg_pace_s_per_km` (null or 60–3600 for run/walk, any positive for cycle), `splits_s` (up to 500 numbers > 0).
- `GET /api/measurements?athlete_id=&kind=&limit=100&offset=0`: newest first, `{...fields, id, athlete_id, kind,
  created_at, client_id}`; scope as `GET /api/form`. Athlete export includes `measurements`; delete removes them.
- Records (section 8) add `balance_stance` per `leg` (longest, ms), `sprint_time` per `distance_m` and `start` (a beep start includes reaction time) and `agility_time`
  per `test` (fastest, ms; the earlier result keeps a tie).

**Dashboard**: the athlete page gains a "Field tests" panel: latest range of motion per joint (left, right, error),
balance per leg (best and latest stance, sway), sprint/agility/reaction bests and latest, and cardio summaries.

**App**: Tools gains Measure → Range of motion, Balance, Sprint and reaction; Train → Outdoor cardio. Each camera
tool has a setup screen (choice, placement drawing, what is measured and its limits), a live camera screen with
the skeleton (pose tools) or the timing band (timing tools), and a result screen that queues the result in the
offline queue (`FeatureWrite.MEASUREMENT`). Cardio records with a location foreground service (framework
`FUSED_PROVIDER`, GPS provider below API 31); the route is kept only in the service's memory for the result
screen and is never written to the queue.

## 10. Talos: the personal all-in-one app (direction set by the owner 2026-11-23)

The product is renamed **Talos** (Bulgarian "Талос"), after the bronze automaton of Greek myth that ran three laps
around Crete every day. The owner's direction: one app that elevates every aspect of a person's training, diet
and everyday fitness, the way Hermes Companion became his one app for everything. Personal use comes first; the
club and coach side stays, but no tool may require a club or a coach to be useful. Ideas come from
`logs/research/competitors-report.md` (ranked list in its section 4) and follow `PHILOSOPHY.md`.

### 10.1 Rename (user-visible only)

App label, launcher name, every user-facing string in both languages, the dashboard title and wordmark, the
documents and the README say Talos / Талос. Internal identifiers stay: the application id and Kotlin package
`jumpscan.app`, the `jumpscan.core` package, `JUMPSCAN_*` environment variables, `/var/lib/jumpscan`, the
systemd unit, the repository name. "Jump test" stays as the name of the jump tool.

### 10.2 Wave 4: Today home and phone integration

**Server: `GET /api/me/today?day=YYYY-MM-DD`** (any signed-in user; `day` defaults to the caller's today and may
be at most one day from the server's UTC date). One scoped aggregate built from the existing functions, so its
scope and numbers always equal what the separate routes return. Each section is independent: a section that
fails or has no data is `null` and the rest still return. Sections:
- `readiness`: status, the factor list and "still learning" exactly as `GET /api/athletes/{id}/readiness` for
  that day (athletes only; staff get null);
- `checkin`: whether today's check-in exists and its wellbeing sum;
- `plan`: today's planned items (plan title, exercise, sets x reps or a rep range, load; plan items carry no minutes)
  and whether a workout or a set was already logged today;
- `training`: today's sessions with their sRPE load, the day's total, and the acute:chronic ratio with its
  "still learning" state from the load route;
- `nutrition`: today's kcal, protein, carbs and fat against the current targets, and the number of food logs;
- `weight`: the latest weigh-in and the trend value from the weight trend;
- `records`: records set in the last 7 days (measure, value, unit, date, reps for a set), i.e. the records route's
  `new` records: a first value is not a record until it is beaten;
- `next_session`: the next visible club session, or null;
- `unread`: unread comment count.
No new tables. Bounded: at most 20 plan items, 10 sessions, 10 records. Tests cover each section, the
independence of a failing section, and that an athlete never sees another athlete's data.

**App: Today screen** (replaces the current jump-centred home; the jump test moves to a card and stays one tap
away):
- A header with the date and a greeting, then cards in this default order: Readiness, Today's plan (Start
  workout), Check-in, Nutrition (ring of kcal plus protein bar, Scan food and Add food actions), Training load,
  Weight, New records, Next session, Messages, Jump test. Each card opens its tool in one tap.
- The user can hide cards and change their order (stored on the phone); hidden cards are listed at the bottom
  under "Hidden cards".
- Signed out or offline the screen still works: phone-only tools (timers, calculators, jump test, workout) are
  shown, and the last successful `today` response is cached with its time ("updated 10:42").
- Live: `checkin`, `jump`, `form`, `comment`, `record` and `measurement` events refresh the card concerned.
- Instrument design: hero card for readiness, digit roll on numbers that change, `arrive` on cards, no idle motion.

**App: phone integration**
- Static launcher shortcuts: Check in, Start workout, Scan food, Interval timer, Jump test.
- Deep links `talos://tool/<key>` for every tool key of the tools hub (the shortcuts use them); unknown keys
  open the hub. A `scan_food` tool (barcode into today's food log) joins the hub so Scan food has a key; tools that
  need an account open sign-in when signed out, as in the hub.
- A home-screen widget "Today": readiness status, check-in done or not, kcal eaten of target; tap opens Today,
  the check-in button opens the check-in. Updated after each sync and at most every 30 minutes (the system's
  periodic update also fetches `today` when signed in).
- Voice cues in the interval timer and the rest timer through the phone's TextToSpeech in the app language
  ("round 3 of 8", "10 seconds", "rest over"), on by default, with a switch; beeps and vibration stay.

**Dashboard:** the athlete's own page starts with the same Today cards (same endpoint), so the web works as a
personal dashboard too.

**Done when:** server tests pass under the memory cap; the app builds on the kit; AppChecks gain Today checks
(cards render from a fixture, hide and reorder persist, deep link opens the right tool, widget renders); Today
screenshots in dark/light x bg/en, signed in and signed out; the dashboard check passes.

### 10.3 Wave 5: the personal loop (added 2026-11-23)

Personal first; nothing here needs a club or a coach. Ideas from `logs/research/competitors-report.md` section 4
(items 8, 9, 10, 12, 24).

**Consistency score (core + server).** Loop Habit Tracker's habit strength (uhabits-core `Score.compute`,
GPL-3.0-or-later, ported with attribution in the file header and in `LICENSES-AUDIT.md`): each day
`score = previous * m + done * (1 - m)` with `m = 0.5 ^ (sqrt(frequency) / 13)`, starting at 0 on the athlete's
first day with any data. Two habits: daily check-in (frequency 1) and training days (frequency = planned days per
week / 7 when a plan applies, else 3/7; a day counts when any set, workout or training session is logged).
Shown as a percentage with the plain fact beside it ("12 of the last 14 days"). Pure function in `core`
(`Consistency.kt`) with tests against hand-computed values; the server uses the same formula in Python with the same
test values.

**Weekly report.** `GET /api/me/week?week=YYYY-Www` (athlete; default the current ISO week, at most 52 weeks back):
`{week, from, to, training: {days, sessions, minutes, load, load_previous_week, sets, volume_kg, top_exercises[≤5]},
records[≤10], checkins: {days, wellbeing_avg}, readiness: {good, normal, take_care, still_learning},
nutrition: {days_logged, kcal_avg, protein_avg, kcal_target, protein_target}, weight: {start_trend, end_trend},
consistency: {checkin, training}, text}`. Facts come from the existing functions (same scope and numbers); `text` is
two or three sentences phrased by the assistant model from those facts only (prompt forbids new numbers and medical
advice), cached per athlete and week until the facts change, and `null` when no model is configured. App: a "This
week" card on Today (opens a week screen with previous/next week); dashboard: a Week section on the athlete's own page.

**"Not feeling 100%".** The athlete flags today with a reason (`ill`, `aches`, `tired`, `busy`) and a choice of
"rest today" or "lighter today". `PUT /api/me/days/{day}/adjustment` `{reason, mode}` and `DELETE` (today and
yesterday only; athlete; audited without free text). When set, Today's plan shows the lighter version (one set fewer
per item, minimum 1, and planned load x 0.9 rounded to 2.5 kg) or "Rest day" instead, and readiness lists the flag as
a factor. Staff see a neutral "not 100%" mark on the team board. No medical wording.

**Share into the assistant.** The app accepts Android `ACTION_SEND` with `text/plain` and opens the assistant with
the text as a draft message (never sent without the user tapping Send). Proposals still need confirmation.

**Record share card.** On a new record (Today records card and the Records screen) a Share action renders an
Instrument-style image on the phone (wordmark, record name, value with unit, previous best, date, flight arc for
jumps) and opens the Android share sheet. The image is never uploaded.

**Done when:** core and server tests pass (server under the memory cap); the app builds on the kit; AppChecks cover
the week screen, the adjustment flow, the share intent and the card renderer; screenshots dark/light x bg/en of
Today with the week card, the week screen, the adjustment sheet and a rendered share card; dashboard check passes.

**Contract details settled while building (2026-11-23).**
- Consistency per habit is `{score, percent, done, days}`: `done` of the last `days` days (14, fewer while the history
  is shorter), or `null` before the first day with a check-in, set, workout or training session (history capped at
  365 days). Training frequency is the number of distinct planned weekdays of the plans that apply that day / 7.
- `GET /api/me/week` takes `language=bg|en` (default `bg`) for `text`; Bulgarian text addresses the athlete formally.
  The facts come from the routes as in `today`; `training.days` counts days with a session, a set or a workout;
  `readiness` counts the days of the week up to today; `nutrition` is the nutrition summary's 7-day average ending
  on Sunday with the current targets; `weight` is the first and last trend value inside the week. Staff get 403.
- `GET /api/me/today` gains `adjustment` (`{reason, mode}` or null) and `week` (`{week, consistency}`); `plan` gains
  `adjusted` (`rest`, `lighter` or null; `rest` empties `items`); `records` entries carry `previous`.
- The adjustment day must be within one day of the server's UTC date (as `today`), which covers the phone's today
  and yesterday; consent must be recorded; the audit row holds the day only. The readiness factor is
  `{key: "adjustment", value: reason, mode, state: "flagged"}`, listed first and never scored; staff reading
  readiness get `value: null`, and team board rows carry `not_100`. An `adjustment` event refreshes Today and the
  board. The table `day_adjustments` joins export and delete-my-data.

### 10.4 Wave 6: accuracy and robustness (added 2026-11-23)

Source: `logs/research/algorithms-review.md` (sections 2c-7c, 9, 10). Every change below either fixes a defect or
passes the experiment and pass bar written in that report before it ships; an experiment that fails is reported and
its change is not shipped. Validated results (squat counts, plank hold, front flight time) must not get worse.

**6A server.** Records: a new best must beat the previous best by more than the measurement's smallest detectable
change (from the repeatability numbers when available, else the documented typical error), and jumps are compared only
within the same view and frame-rate class; side-view jumps without other warnings count again. Readiness: HRV
(ln RMSSD) and resting heart rate use a rolling 7-day mean against the 28-day baseline; acute (7 d) and chronic
(28 d) load are shown separately and the acute:chronic ratio is secondary information with its known limits.
Assistant: temperature 0.2, a fallback chain of models from `JUMPSCAN_LLM_FALLBACK` (comma-separated
`url|model|keyfile` entries, tried in order on timeout, 429 or 5xx), tool subsets chosen by a simple intent router for
food, training, check-in and reading questions (all tools when unsure), and SPEC 6.4 aligned with six tool rounds.
Food search: Bulgarian names for the most common catalogue foods and a typo-tolerant fallback (trigram similarity).
Expenditure: an uncertainty band from the weight trend's own error. Documents: the assistant is described as an
OpenAI-compatible model (local or cloud), and outdated figures in `docs/validacia.md`/ROADMAP are corrected.

6A clarifications (2026-11-24, as built): the records table lives in `server/records_api.py` (`SMALLEST_CHANGE`,
MDC95 from the 6B repeatability analysis in `core/VALIDATION.md`: front 11.3 cm and 96.6 ms, side 5.1 cm and 58.2 ms;
the same for every frame-rate class until faster clips are measured). Frame-rate classes are the nearest of 30, 60, 120,
240 and 480 fps; view is side when `single_leg_view` is present. Record entries for jumps add `view`, `fps_class`
and `margin`; the leaderboard takes `view` (default `front`), ranks each frame-rate class separately and adds
`fps_class` per row and `smallest_change`. HRV and resting heart rate need today's value and at least three values in the
7 days ending today (else "learning"), with threshold max(0.5 x SD, TE / sqrt n); factor `value` is the 7-day mean
and `today` the day's own value. The load response adds `acute_load` (7-day total) and `chronic_load` (mean weekly
total over 28 days, null before 28 days of history); the team board adds `load_acute` and `load_chronic`. The
fallback chain also moves on after a connection failure, and stays on the model that answered for the rest of the
turn; a 4xx other than 429 or a malformed reply does not fall back. The router reads the message and the previous
user turn. Food search matches every query word against word starts in either name; Bulgarian catalogue names are
in `server/data/catalog_bg.csv`; the typo fallback runs only when nothing matches (one edit, two from eight letters,
trigram similarity breaking ties). The expenditure band is `uncertainty_kcal` = 7700 x `sigma_kg` / 14 on every
expenditure row, kept through holding days. Balance measurements take the optional 6B fields `sway_velocity_pct` and
`noise_floor_pct` (0-1000 % of leg length per second, null allowed).

**6B core, app and kit.** Slow-motion guard: the app reads the clip's capture frame rate and refuses a clip whose
capture rate differs from its timestamp rate by more than 3%, with a clear message (no rescaling until an A54 test
proves the rule). Field tests: the reaction/sprint cue time is the moment the tone actually plays (AudioTrack
timestamp), line crossings are interpolated between frames, and balance reports sway velocity above a measured noise
floor. Kit experiments 1-7 of the report run with their pass bars; results go to `logs/wave6/`.

6B clarifications (added by the 6B worker, 2026-11-24):
- Slow-motion guard: every analysed video (jump and form) reads `METADATA_KEY_CAPTURE_FRAMERATE`; when it is present,
  finite and positive and differs from the sample-timestamp rate by more than 3% of the timestamp rate, the clip is
  refused with `slow_motion_error` before any pose work. A clip without the key passes. Nothing is rescaled.
- Cue time (supersedes 9.3's "when the tone is requested"): the start tone is a 1 kHz tone on its own `AudioTrack`; the
  cue time is the presentation time of its first sample from `AudioTrack.getTimestamp`, moved onto the camera frame
  clock. Until that time is known, motion is not judged; motion before it is a false start. If no timestamp arrives
  within 1 s, the request time is used (the old behaviour) and the result screen says so. The remaining unmeasured parts
  are the speaker and the sound's travel to the athlete. How the cue was timed stays on the phone.
- Line crossing: the crossing time is interpolated linearly in band activity between the frame before and the first
  frame with activity at least 0.12. Precision is still reported as one median frame interval, because the
  interpolation has no phone measurement yet.
- Balance sway velocity: the noise floor is the hip midpoint's path length per second over the first window before the
  lift of at least 1000 ms and 5 frames with both feet down (lift below 4%) and the hips within 3% of leg length of the
  window's first position. Sway velocity is the hip path length per second over the stance; both are in % of leg length
  per second, and the result shows velocity minus floor (never below zero), or says the floor was not measured. Both
  stay on the phone: the server's balance fields are unchanged (uploading them needs a server field first).
- Kit experiments: results and verdicts are in `core/VALIDATION.md` (Wave 6B) and the raw outputs in `logs/wave6/kit/`.

### 10.5 Health Connect and Samsung Health (added 2026-11-24)

Owner: "We need to get Samsung health and health connect working." Samsung Health on the owner's Galaxy A54 writes to
Android Health Connect; Talos reads Health Connect (Samsung's own SDK needs partner approval, so it is not used). Read
only: Talos never writes to Health Connect. Data stays on the phone until the athlete saves a check-in, a weigh-in or a
training session, which then go to the server through the existing routes and offline queue (no server change).

- **Library and manifest:** `androidx.health.connect:connect-client` (Apache-2.0; record it in LICENSES-AUDIT.md), the
  read permissions for resting heart rate, heart rate, heart rate variability (RMSSD), sleep sessions, steps, weight and
  exercise sessions, the permissions-rationale activity Health Connect requires (it shows the privacy text: what is read, why,
  that it stays on the phone until you save) and the Android 14 `VIEW_PERMISSION_USAGE` alias.
- **Connect:** Profile gets a "Health Connect" row: availability state (not installed / needs update / available),
  "Свържете / Connect" that requests the permissions, which permissions are granted, and a short Samsung Health note
  ("In Samsung Health: Settings > Health Connect, allow it to share"). If Health Connect is missing or outdated on
  Android 9-13, the row opens its Play Store page.
- **What Samsung Health shares (Samsung's Health Connect FAQ and Data SDK type list, checked by the orchestrator
  2026-11-24):** steps, exercise, heart rate and sleep. It does not write resting heart rate or HRV (RMSSD) to Health
  Connect; weight is not in Samsung's list (read if present, never relied on). The Samsung Health Data SDK is not used:
  its licence forbids building it into another product (a clash with the GPL entry) and it needs Samsung approval.
- **Check-in prefill:** the check-in screen gets "Попълнете от Health Connect / Fill from Health Connect": resting heart
  rate (the latest `RestingHeartRateRecord` from the last 24 h; when there is none, an estimate from the heart rate
  samples inside the sleep session below, see `jumpscan.core.NightHeartRate`), HRV RMSSD in ms (the latest
  `HeartRateVariabilityRmssdRecord` of the last 24 h taken from 20:00 to 11:59 local time; when there is none the field
  stays for manual entry with a one-line note that Samsung Health does not share HRV), bedtime and wake time from the
  longest sleep session ending today (local time, HH:MM). Each prefilled field
  is marked "от Health Connect" and stays editable; nothing is saved until the athlete taps Save. Values outside the
  server's bounds are skipped with a note.
- **Weigh-in prefill:** the latest weight from the last 7 days, same rules.
- **Workouts:** exercise sessions from Health Connect for today and yesterday that Talos does not already know (matched
  by start time ±5 min) are offered as training sessions on Today ("Samsung Health: Бягане 42 мин — добавете?"); the
  athlete picks the type and RPE before it is saved through the existing training-session route.
- **Steps:** today's steps shown on the Today readiness card as information (not uploaded).
- **Honesty:** the source of every prefilled number is visible; readiness treats them like typed values.

**Resting heart rate estimate (added 2026-11-24, orchestrator correction):** every 5-minute window that starts at a
heart-rate sample during that sleep session (samples from the start up to, not including, start + 5 min) with at
least 3 samples gives a mean; the lowest mean, rounded to a whole beat, is the estimate, and no qualifying window
means no estimate. The field is then labelled "Пулс в покой, оценка от нощния пулс / Resting heart rate, estimated
from night heart rate" plus "от Health Connect", and stays editable. The window and the 3-sample minimum are design
choices with no reference measurement behind them; a phone without a watch records little or no night heart rate.

**10.5 clarifications (implementation, 2026-11-24):**
- Library: the only stable `connect-client` (1.1.0) needs compileSdk 36 and AGP 8.9.1; the app pins `1.1.0-beta01`,
  the newest release that builds with compileSdk 35 and AGP 8.7.3. Moving to 1.1.0 needs both bumps.
- The bounds are the server's: resting heart rate 25–150, HRV above 0 and at most 300 ms (rounded to 0.1), weight
  20–400 kg (rounded to 0.1). A value outside them is skipped with a note naming the field and the value; it is never
  clamped. A prefilled field's "от Health Connect" mark goes away as soon as the athlete changes the value.
- "Talos already knows" a Health Connect exercise session when its start is within 5 minutes of a Talos workout's
  `started_at` (read from `/api/workouts`, offline skipped) or of a session the athlete already added or turned down
  on this phone (kept in app preferences, last 50). Training sessions on the server carry no start time, so a session
  typed by hand is not matched. Sessions of 1–600 minutes are offered, newest three, above the Today cards; "Add" opens
  the training-session form with the minutes filled and the athlete choosing type and RPE.
- The source is "Samsung Health" when the record came from `com.sec.android.app.shealth`, otherwise "Health Connect".
- Debug builds also declare the Health Connect write permissions, only so the instrumentation checks can insert fake
  records; the release manifest declares read permissions only.
- The rationale activity (`HealthRationaleActivity`) shows the privacy text and is also the target of the Android 14
  `ViewPermissionUsageActivity` alias.

Done when: the app builds; AppChecks cover availability handling, permission denial, and prefill mapping with fake
Health Connect data (the API 34 emulator has Health Connect built in; insert test records from the test APK); the
emulator screenshots show the Profile row, the prefilled check-in and a workout suggestion in light-bg; the real A54 with
Samsung Health data is a documented owner check.

### 10.6 Food photos, pantry recipes and nearby stores (added 2026-11-24)

Owner: "Implement food analysis by image. Implement recipe and food suggestions based on stores near user and for
example a pic of the fridge or pantry will get you recipes including those ingredients."

**Privacy rule (amends PHILOSOPHY 1 and 4.3):** video, body images and landmarks still never leave the phone. A food
or pantry photo leaves the phone only when the user starts the analysis, after a one-time notice saying it goes to the
club's language model (currently an NVIDIA cloud model) and is not stored. The server forwards the downscaled image
(max 1024 px JPEG, EXIF stripped on the phone) to the vision model and discards it; no image is written to disk, the
database, logs or the audit (the audit records only "food_photo_analysed"). Athletes under 18 cannot use photo
analysis while the model is external (same rule as the assistant). Location for stores is rounded to about 1 km on
the phone and is not stored.

**Server**
- `JUMPSCAN_VISION_MODEL` (default `nvidia/nemotron-3.5-super-vl-120b-a12b`), same URL/key/fallback settings as the
  assistant; timeout 90 s; image body max 2 MB; rate limit shared with the assistant.
- `POST /api/me/nutrition/photo` `{image_jpeg_base64, meal?}`: the model lists foods with estimated grams; the server
  matches each to the catalogue/club foods (same search as text, Bulgarian and English) and returns
  `{items:[{label, grams, food|catalog_id|null, kcal, protein_g, carbs_g, fat_g, confidence}], totals}`. Nothing is
  saved; the app shows the items for review and saves through the existing food-log batch route (confirm step).
  Numbers come from the catalogue, never from the model; unmatched items are shown as "not found, add manually".
- `POST /api/me/nutrition/pantry` `{image_jpeg_base64}`: the model lists visible ingredients; returns
  `{ingredients:[{label, food|catalog_id|null}]}` for the user to edit.
- `POST /api/me/nutrition/recipe-ideas` `{ingredients:[labels], goal?: "high_protein"|"light"|"any", servings?}`:
  the model proposes up to 3 recipes using mainly those ingredients (steps in the user's language); the server matches
  every ingredient to the catalogue and computes kcal and macros per serving itself. Returns recipes with
  `uses_from_pantry`, `missing` ingredients and per-serving nutrition. "Save as recipe" uses the existing recipes route.
- `GET /api/stores/nearby?lat=&lon=` (lat/lon rounded to 2 decimals): supermarkets and greengrocers within 2 km from
  OpenStreetMap (Overpass API, ODbL attribution shown), name, brand, distance; cached 1 day per rounded cell; 10 s
  timeout; no stock or price claims.
- Recipe ideas can include `stores` context: when the app passes nearby store brands, missing ingredients get a
  "можете да ги намерите в: Lidl (400 м), Kaufland (1,2 км)" line built from the store list (plain text, no claim
  that a store has the item in stock).
- Tests: all model and Overpass calls mocked; privacy tests (no image persisted or logged, minors refused with an
  external model, audit without content); matching and nutrition computed server-side.

**App**
- Nutrition screen: "Снимка на храната / Photo of a meal" (camera or gallery) -> review list -> save to the chosen meal.
- "Какво да сготвя? / What can I cook?": photo of fridge or pantry (or type ingredients) -> editable ingredient chips
  -> goal -> 3 recipe cards (time, kcal and protein per serving, uses / missing, steps) -> save as recipe or log.
- "Магазини наблизо / Stores nearby" on the recipe screen using coarse location permission (asked only when tapped),
  with OSM attribution; works without location by hiding the store line.
- One-time privacy notice before the first photo; EXIF stripped and image downscaled on the phone.
- Bulgarian formal (Вие), brand "Talos", both themes.

Clarifications from the implementation (2026-11-24):
- Module `server/food_vision_api.py`. The vision model is called at `JUMPSCAN_LLM_URL` with `JUMPSCAN_LLM_KEY_FILE`, then
  the `JUMPSCAN_LLM_FALLBACK` models; `JUMPSCAN_VISION_TIMEOUT` (default 90 s), `max_tokens` 4000, only
  `message.content` is read. Recipe ideas use the same vision model with a text-only message.
- The 2 MB limit is on the decoded JPEG (checked for the JPEG signature); the request body limit is raised to fit it
  only on the photo and pantry routes. Routes return 503 when the model fails, 403 for a minor with an external model,
  429 from the assistant's shared limit (20 requests in 10 minutes). The audit row has no detail.
- Photo items also carry `label_bg` (the model's Bulgarian words), `food_id`, `catalog_id`, `name_bg`, `name_en`;
  nutrition fields are null for an unmatched item. Pantry ingredients have the same reference fields.
- Recipe ideas: `servings` 1-8 (default 2), `language` "bg"|"en" (default "bg"), optional `stores` as
  `[{name, brand, distance_m}]` from the stores route. Each recipe has `name, minutes, servings, ingredients[{label,
  name, grams, from_pantry, food_id|catalog_id}], steps, uses_from_pantry, missing, per_serving{kcal, protein_g,
  carbs_g, fat_g}, nutrition_complete, stores_line`; unmatched ingredients are left out of the numbers and
  `nutrition_complete` is false.
- Stores is `POST /api/stores/nearby {lat, lon}` instead of a GET, so the position stays out of access logs. The cache is
  in memory only. `JUMPSCAN_OVERPASS_URL` overrides the public Overpass instance.
- `JUMPSCAN_FOOD_FIXTURE=1` (tests and emulator screenshots only, off by default) answers with canned model and Overpass
  replies.
- App: the photo is decoded with sampling, turned upright from its EXIF orientation, scaled to at most 1024 px and
  re-encoded as JPEG 85, which drops all EXIF. A camera photo goes through a cache file that is deleted after reading.

Done when: server tests pass under the memory cap; the app builds; AppChecks cover the photo review flow, pantry ->
recipe flow and stores list with a fixture server (model and Overpass mocked server-side via a test switch); one
real end-to-end check against the live vision model from openclaw with a public-domain food photo is recorded.

### 10.7 Body measurements and progress photos (added 2026-11-24)

- **Measurements** are one more kind of the field-test measurement (`kind: "body"`, same route, consent, scope,
  idempotent `client_id`, offline queue, export and delete). Fields, all optional but at least one girth:
  `waist_cm`, `neck_cm`, `hip_cm`, `chest_cm`, `arm_cm`, `thigh_cm` (10–250 cm), `height_cm` (100–250) and
  `sex` (`male`/`female`). Height and sex are remembered on the phone only, to prefill the next entry.
- **Body fat** is computed by the server, never accepted from the client: the US Navy circumference formula
  (Hodgdon and Beckett 1984, metric form; men use waist minus neck, women waist plus hips minus neck). It is
  stored with `body_fat_error_pct` (3.5 men, 3.7 women: the published standard error against underwater
  weighing) and `body_fat_method`, or null when a needed value is missing or the result is outside 2–60 %.
  The app shows it as "16.4 % ± 3.5", explains that one person's error can be larger, and shows the change
  since the first estimate in percentage points.
- **Progress photos** stay on the phone: shrunk to 1600 px, EXIF removed, saved in app-private storage per
  signed-in athlete (app backup is off). Never uploaded, never sent to a model. The screen shows the first
  and the latest side by side; the latest can be deleted.
- Reached from Nutrition › Insights under the weight log ("Body measurements and photos").

### 10.8 Exercise library and ready-made programs (added 2026-11-24)

- **Library:** 36 common gym and home exercises (`programs_api.LIBRARY`, Bulgarian names like the club
  catalogue) are added to every club once: existing clubs at startup, new clubs when created. A table
  `club_library(club_id)` records it, so an exercise a coach deletes does not come back; names the club already
  has are kept.
- **Programs:** four built-in programs (full body A/B for beginners, upper/lower, push/pull/legs, home without
  equipment), each a few days of exercise, sets, reps and rest. `GET /api/programs` lists them;
  `POST /api/programs/{key}/adopt` creates one ordinary workout template per day, private for an athlete
  (consent required) and shared with the club for staff. A day already adopted by the same owner is not created
  again; exercises a coach removed from the club are left out and listed in `missing_exercises`. Loads are not
  prescribed: the workout screen pre-fills from the last session and progression works as before.
- The list also carries English exercise and day names (`exercise_en`, `name_en`) for the English app, and
  `hold` for timed holds (plank: sets of 30–45 s). Adopted templates use the club's names.
- The assistant can read the programs (`list_programs`); adopting happens in the app (Workout › Ready-made programs).
