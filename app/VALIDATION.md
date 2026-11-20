# Android app validation — 16 November 2026

All implementation changes are confined to `app/`. The app uses the existing login, logout and jump API calls and sends the same 14 metric fields. No server changes are needed.

## Delivered behavior

- English resources and natural Bulgarian resources follow Android's locale selection. Numbers use the active locale. App errors and warnings are translated; raw server or platform error text is not shown.
- The local result shows the exact contact frame returned by the core detector, with hips, knees, ankles, heels and toes drawn in blue for the left leg and orange for the right leg. Hidden joints are omitted. If core finds no landing, the preview uses the frame with greatest combined knee flexion. The image exists only in memory. Contact frames can be blurred: a leg chain is drawn only when its hip, knee and ankle each have at least 0.8 visibility, and individual foot points use the same threshold. Uncertain chains are omitted and a translated framing warning is shown. The owner clips have cropped, blurred contact frames, so their leg chains can be hidden rather than drawn with misleading foot positions. The complete skeleton was visually verified on the clear stock squat fixture. This does not change any metric or uploaded warning field. Frame-index extraction is used on API 28 and later; older devices use the original presentation timestamp.
- The result groups the numbers under Jump, Knees at landing and Left / right, with short explanations and warnings in words.
- Record and Pick video both open a filming checklist and vector diagram. Continue opens the original camera or picker action.
- A failed network upload saves only the metrics JSON in app-private storage. Atomic writes protect the file. A directory identity binds it to the athlete ID and exact server URL. The app retries at startup and through Send pending; it deletes a file only after a successful POST. HTTP errors and unreadable files remain available for recovery. Tokens, video, images and landmarks are excluded from the queue.

`CoreLanding.java` is a small bridge to core's existing Kotlin-internal landing result. This avoids copying the detector into the app or editing core. A future change to that internal JVM signature will require updating this bridge.

## Build and emulator checks

Build directory: `/work/jumpscan-app` on `android-build-kit`, using `/opt/android-sdk`.
AVD: `jumpscan-worker-a` (Android API 34), headless, 2048 MB RAM and two cores. Only one emulator was used.

Each feature commit followed a successful build and an emulator check. The final checks use:

```sh
ssh android-build-kit 'cd /work/jumpscan-app && timeout 900 ./gradlew --no-daemon :core:test :app:assembleDebug :app:assembleDebugAndroidTest'
ssh android-build-kit '/opt/android-sdk/platform-tools/adb shell am instrument -w jumpscan.app.test/jumpscan.app.AppChecks'
python3 app/tools/check_resources.py
```

The platform-only instrumentation checks cover queue identity, persistence, network and HTTP failure retention, successful deletion, metric-only payloads, malformed-file retention, drawing of both leg colours, hidden-joint omission, the no-landing bridge, and omission of floating ankle markers when a leg is unclear. They need no new test library.

Login, result upload, history and queue delivery were checked against `app/tools/mock_api.py`, a temporary loopback-only fixture on port 8765. No production account or server database was used. With emulator Wi-Fi and mobile data disabled, Upload saved one JSON file with exactly 14 metric fields. Restoring connectivity and tapping Send pending produced one accepted POST and a pending count of zero. Restoring that saved test payload and relaunching the app verified startup retry and removal after acknowledgment. Both radios were restored.

Screenshots are under `/root/jumpscan/logs/app2/`, outside version control. English and Bulgarian captures cover login, home, analysis, result overlay, grouped numbers, filming guide, queued result, history and history detail. The captures were opened and inspected for wrapping and skeleton placement. Current captures use the `verified-` prefix; the owner contact images explicitly show the framing warning when leg chains are uncertain. The filming guide's Continue action was seen opening the system video picker. A replay of the committed stock squat fixture verified the no-landing peak-flexion preview and warning wording in both languages, with the skeleton over the body.

## Analysis speed experiment (historical; removed 17 November 2026)

Full analysis remains the production default. Alternative modes require an explicit debug intent extra; release builds ignore it. The lite model is bundled only in debug assets. No coarse landmarks are mixed into the final metrics.

The lite experiment uses every second frame at a 360-pixel long side. On clip 1, its 116-frame first pass took 120.363 s and found no landing. The fallback full pass preserved all metrics, but total time increased to 380.009 s. This ruled it out for that clip.

The sparse-heavy experiment uses every third frame at a 360-pixel long side, then the heavy model at 720 pixels on every source frame from approximately 1.5 seconds before the estimated take-off to 1 second after the estimated contact. Core receives only that second pass. It includes the standing reference before the jump.

The owner reference exports were scored with the core batch runner. All three full-frame results report 280, 360 and 360 ms. The full emulator results agree with those exports. The sparse experiment failed the accuracy gate, so it is not enabled for normal users.

| Clip | Full baseline (s) | Two-pass candidate (s) | Full flight (ms) | Candidate flight (ms) |
| --- | ---: | ---: | ---: | ---: |
| jump1 | 263.878 | 159.599 | 280 | 320 |
| jump2 | 304.049 | 197.945 | 360 | 320 |
| jump3 | 150.456 | 107.445 | 360 | 360 |

All three candidates also changed knee angles, separation, asymmetry, quality or reported sampling rate. For example, clip 3 retained its 360 ms flight but peak flexion changed from 62.16 / 67.79 degrees to 69.92 / 65.45 degrees. The shortened window starts a new VIDEO tracker and provides different standing context; preserving timestamps alone does not preserve the estimates. The individual contributions of tracking warm-up and standing-reference selection were not isolated.

The measured sparse passes spent 86–91% of their elapsed time decoding. On clip 1, the coarse pass used 77.306 s for decoding and 7.810 s for inference; its heavy window used 63.614 s for decoding and 8.166 s for inference. A smaller model alone cannot remove the dominant decoding cost. A streaming decoder and a way to preserve tracking/reference context would need separate validation before enabling a faster path.

The baseline, sparse-window exports and displayed JSON were compared through the core batch runner. The comparison helper intentionally returns status 1 because the candidate differs. Full analysis remains the default, with the original 280 / 360 / 360 ms reference flights. A repeat full run of clip 3 before the experiment took 144.754 s and produced identical metrics, illustrating shared-host timing variation. Timers cover decoding, model passes and fixture export; core scoring and preview rendering occur afterward. Times are individual emulator runs, not benchmark medians. The final ordinary VIEW intent, without an experiment extra, completed clip 3 in 144.868 s with phase `full` and all 14 metrics exactly matching the full reference.

Debug exports now use `files/landmarks.csv`, `files/coarse.csv` and `files/metrics.json` inside app-private storage. The previous external-files location could not be read through the shell on this emulator. These files are developer fixtures, not queue entries, and export code is guarded by `BuildConfig.DEBUG`.

Historical reproduction commands (the rejected modes and comparison helper have since been removed):

```sh
python3 app/tools/emulator.py 1 --tag full --mode full
python3 app/tools/emulator.py 1 --tag sparse --mode sparse
python3 app/tools/emulator.py 1 --tag lite --mode lite
python3 app/tools/compare_results.py --tag sparse
```

Run one clip at a time. The helper waits for fresh decode logs and the result screen, rather than accepting the previous result that briefly remains visible after a VIEW intent. `--resume` recovers a completed run if SSH polling was interrupted. The comparison exits with status 1 when any metric differs; this is the intentional accuracy gate.

## Limits

These are emulator measurements on a shared machine, not timings on a real phone. The API 26 compatibility path is compiled but was not run on an API 26 device. Front-facing filming is assumed. The server has no idempotency key, so delivery is at least once: an accepted POST whose acknowledgment is lost can be repeated. The app does not change the API to hide that limitation.

The temporary API fixture was stopped, the fixture login and private video copies were removed, app language selection was returned to the system default, and the emulator was shut down with `adb emu kill`. A final process/device check found no worker emulator or test API fixture running.

## Check after the detector merge — 17 November 2026

Checked the `stage` tree at `4b46301` on `android-build-kit` as `builder`. The tree was synced to `/work/jumpscan-app` with `rsync --exclude local.properties`; no private video was copied into the repository. There was no emulator or Gradle process at the start. One headless `jumpscan-worker-a` emulator was used, with API 34, 2048 MB RAM and two cores. Builds were sequential in the app build directory; unrelated kit workloads were left running.

The following build passed, and all 37 core tests passed with no failures or skips, including the three private landmark fixtures:

```sh
ssh android-build-kit 'cd /work/jumpscan-app && timeout 900 ./gradlew --no-daemon :core:test :app:assembleDebug :app:assembleDebugAndroidTest'
ssh android-build-kit '/opt/android-sdk/platform-tools/adb shell am instrument -w jumpscan.app.test/jumpscan.app.AppChecks'
python3 app/tools/check_resources.py
```

Both APKs were installed before instrumentation. The instrumentation reported that queue isolation, durability, failure retention, payload boundaries and preview checks passed. This includes executing the updated `CoreLanding` bridge. All 127 English/Bulgarian resource keys and format arguments matched.

Each owner video was analysed separately through an ordinary VIEW intent with no `analysis_mode` extra. The emulator helper was used with `--mode default`; temporary local copies of the helpers changed only the evidence directory to `logs/appcheck`. Each run waited for fresh decode, elapsed-time and metric logs, followed by the result screen. Every run reported `phase=full` and used the heavy model on every source frame.

| Clip | Frames | Earlier full time (s) | This full run (s) | Contact / preview (ms) |
| --- | ---: | ---: | ---: | ---: |
| jump1 | 232 | 263.878 | 252.407 | 4027 / 4027 |
| jump2 | 247 | 304.049 | 301.135 | 5847 / 5847 |
| jump3 | 164 | 150.456 | 143.940 | 2918 / 2918 |

All 14 exported fields are recorded below. Every value equals the earlier full reference in `logs/app2/baseline-metrics.csv`; the comparison helper returned status 0 with no differences for any clip. Each fresh landmark CSV is also byte-identical to its earlier `verified-preview-jumpN.csv` export.

| Field | jump1 | jump2 | jump3 |
| --- | ---: | ---: | ---: |
| test | cmj | cmj | cmj |
| fps | 24.91 | 24.97 | 24.84 |
| flight_time_ms | 280.00 | 360.00 | 360.00 |
| jump_height_cm | 9.61 | 15.89 | 15.89 |
| knee_flexion_contact_l | 25.34 | 30.01 | 20.84 |
| knee_flexion_contact_r | 12.96 | 28.27 | 23.29 |
| knee_flexion_peak_l | 34.93 | 48.42 | 62.16 |
| knee_flexion_peak_r | 29.51 | 46.62 | 67.79 |
| fppa_peak_l | -17.62 | -29.32 | -30.40 |
| fppa_peak_r | 11.65 | 3.16 | -0.68 |
| knee_ankle_ratio | 0.61 | 0.82 | 0.83 |
| flexion_asymmetry_pct | 15.52 | 3.73 | 8.30 |
| quality | 0.87 | 0.89 | 0.84 |
| warnings | [] | [] | [] |

No detector metric regression was observed on these clips. The merged detector requires observed ground for CMJ (`1a52e7a`), permits hip movement during the planted-foot reference (`f14d220`), and adds profile detection and nullable single-leg measurements (`4f627fa`, `core/src/main/kotlin/jumpscan/core/JumpAnalyzer.kt`). These owner clips retain two-leg results under AUTO, with no `single_leg_view` warning. The shorter elapsed times are individual shared-host measurements, not evidence of a detector speed improvement: the timer ends before core scoring, and decoding still accounts for roughly 89–90% of the measured time.

The English and Bulgarian result screens, grouped metrics, locale-specific decimal separators and exact contact previews were opened and inspected. All three owner contact previews show the translated unclear-joints framing warning. Only the visible hip markers are drawn; uncertain leg chains remain hidden, as in the earlier validation. This presentation warning is separate from the empty uploaded `warnings` arrays. A debug replay of the committed stock squat landmarks against `one-person.mp4` showed the complete blue/orange skeleton aligned with the body in both languages. It retained null flight time and height, the peak-flexion preview, and the translated `no_flight_detected` warning. This supplemental replay was not counted as an ordinary full video run.

The loopback-only mock API on port 8765 received two direct app uploads: jump1 in English and jump2 in Bulgarian. For each language, disabling Wi-Fi and mobile data and uploading jump3 saved exactly one app-private JSON with the same 14 metric fields and no video, landmarks or token. No POST reached the mock while offline. Restoring both radios and tapping Send pending delivered one result and removed the queue file, with the displayed count returning to zero. The English queue check used a debug replay of jump3's newly captured full landmarks. The four received app payloads were read back from the mock and matched jump1, jump2, jump3 and jump3 exactly.

A fifth mock row was seeded directly as a synthetic single-leg history fixture, with an unavailable right leg and nullable frontal fields. Its detail screen displayed the translated `single_leg_view` warning and unavailable values in both languages. This checks the new display contract, not detection accuracy on a real side-view video. It exposed one small defect: history appended the degree symbol to unavailable values, producing `Not available°` and `Няма данни°`. Only the two `history_numbers` resources were changed, moving the unit into the label. The APKs were rebuilt and installed, instrumentation and resource checks passed again, and the corrected history rows were opened and inspected in both languages. No detector, analysis or upload logic was changed.

Evidence is retained outside version control under `/root/jumpscan/logs/appcheck/`: build and instrumentation logs, `merge-full-jump1..3` logs/CSV/JSON/XML/screenshots, the complete comparison, queue payloads, received mock rows, and 25 screenshots. All saved screenshots were opened and inspected, including the before/after nullable history rows. The original API 26 and real-phone validation limits still apply.

Cleanup completed: both radios were enabled, app locale was returned to the system default, and `pm clear` removed both app and test-app data, including the test login, pending storage, private video and debug landmark exports. The app data directory was empty afterward. Temporary video, replay and UI-dump files were removed from the device; `/data/local/tmp` retained only the existing Dalvik cache. The mock API was stopped using its recorded PID, and the emulator was stopped with `adb emu kill`. A subsequent check found no qemu process, no mock API process or listener on port 8765, and no adb device. No service was restarted or production endpoint used.

## Manual frame confirmation — 17 November 2026

The result now exposes take-off and landing timestamps and opens a bilingual frame editor. The editor
steps through the clip's real presentation timestamps by one or five frames, decodes one frame at a
time at a maximum 720-pixel long side, and reuses the existing visibility-filtered skeleton overlay.
Analysed landmarks, timestamps, source URI, preview and selected edges stay in the retained result
across rotation. Apply calls the public core analysis API and returns the selected landing preview and
`manual_edges` result. Existing uploaded or queued entries are retained; applying an edit creates a new,
unsent local result rather than modifying a previously stored jump.

No-flight results explicitly offer frame marking or filming again. The editor starts at the peak-flexion
frame when there is no automatic take-off. Low visibility suggests filming again with the whole body
in frame. The filming guide adds the requested 60 fps or slow-motion instruction in both languages.
The rejected lite/sparse paths, lite asset, Java bridge and experiment-only comparison helper are gone;
full heavy analysis and debug replay remain. The staged deletions were included by a concurrent README
commit, `b52a77e29d8e59450c7ed51822c198d3e4f5fc52`; that history was preserved.

### Build and automated checks

Work ran as `builder` on `android-build-kit`, in `/work/jumpscan-app`. Sources were synced with
`rsync --exclude local.properties`, also excluding build products, logs and local environments. Memory
was checked before builds and emulator launches; 4 GB or more was available at those starts. Builds
were sequential with one Gradle worker. No other workload or service was stopped, and no package was
installed on the kit.

```sh
ssh android-build-kit 'cd /work/jumpscan-app && timeout 900 ./gradlew --no-daemon --max-workers=1 :core:test :app:assembleDebug :app:assembleDebugAndroidTest'
ssh android-build-kit '/opt/android-sdk/platform-tools/adb shell am instrument -w jumpscan.app.test/jumpscan.app.AppChecks'
python3 app/tools/check_resources.py
```

The final build passed. All 43 core tests passed with no failures or skips, including six new manual-edge
tests and all 37 unchanged existing tests. Both APKs were installed and final instrumentation passed:
queue isolation, durability, failure retention, metric-only payload boundaries and preview checks,
including the public core call and the peak-flexion fallback with only one visible leg. All 146
English/Bulgarian resource keys and format arguments matched. The first build failed because rsync had
left the deleted Java bridge on the kit; removing that obsolete remote file resolved the compile error.

Server checks ran only on `openclaw`, using its existing virtual environment and the requested cap:

```sh
PATH=/root/jumpscan/server/.venv/bin:$PATH systemd-run --scope -q -p MemoryMax=800M -p MemorySwapMax=0 nice python3 -m pytest server -q -p no:cacheprovider
```

All 254 server tests passed, with two dependency warnings. New checks cover acceptance and preservation
of six warning entries, `manual_edges` in history and CSV, and both dashboard labels. The system Python
lacked pytest; the existing environment was used without installing dependencies.

### Observed emulator behavior and validation limit

Only one `jumpscan-worker-a` emulator ran at any time, headless, API 34, with two cores. The emulator
raised requested guest RAM values of 2048 and 1536 MB to its 2560 MB minimum. A later launch used
SwiftShader with Vulkan disabled; the same guest RAM minimum still applied.

An ordinary, fresh heavy-model run of `/work/jumpscan-samples/private/jump1.mp4` started on all 232
frames at 720 pixels. It failed partway through with `FrameDecoder: failed to get video frame (err -11)`
and `StagefrightMetadataRetriever: all codecs failed to extract a frame`. The app showed its translated
decode error and produced no result. The cause was not isolated. While the emulator was running, shared
host memory remained below 3 GB available, so a further heavy inference run was not started. Fresh
end-to-end video inference therefore remains unverified in this task; the following successful editor
checks used the retained debug replay path, not fresh model inference. No decoder fix or speed/accuracy
claim is made.

Owner replay used the reviewed private `jump1.csv` already installed on the kit, together with the
original owner video copied only to app-private device storage. Core proposed 3747 / 4027 ms and
280 ms / 9.61 cm. All four step controls were exercised, and the exact timestamp sequence was recorded.
The user marks were set to 3747 / 4067 ms. The editor showed 320 ms / 12.56 cm. Portrait-to-landscape-to-
portrait rotation preserved the selection; a language change recreated the activity and retained both
edges and the selected 4067 ms frame. Apply returned a manually marked result with the selected contact
preview. The owner contact preview retained its existing unclear-joints warning and omitted uncertain
leg chains.

The no-flight check used `/work/jumpscan-samples/one-person.mp4` and the committed
`squat_jumps_one_person.csv.gz` landmarks through replay. English and Bulgarian results showed the
no-flight explanation and both recovery choices. The editor started at the expected 166 ms peak frame.
Equal edges disabled Apply. Source timestamps stepped through 333, 500, 533 and 567 ms, including the
real 33/34 ms intervals. Arbitrary test marks at 166 / 567 ms produced 401 ms / 19.72 cm and
`manual_edges` after Apply. These were UI-test marks on a planted-foot squat fixture, not a measured jump
or evidence of timing accuracy.

The loopback-only mock API received both app uploads, one owner result and one squat UI-test result.
Their payloads contained exactly the 14 metric fields, with flight/height 320 / 12.56 and 401 / 19.72,
and `warnings: ["manual_edges"]`. No video, landmarks or edge timestamps entered the upload. Both
filming-guide hints were opened and inspected in English and Bulgarian.

A synthetic visibility fixture retained the squat geometry and timestamps, set visible leg points to
0.6, and hid them from 560 through 1120 ms. It verified the whole-body re-filming hint in both languages.
Applying a 567 ms landing with no usable angles in its landing window exercised the recovery path.
A fresh UI review found that this error could appear below the viewport; it was moved above the image
and given an accessibility announcement. The APKs were rebuilt, instrumentation passed again, and the
actual failure screen was opened and inspected. The reviewer scored that specific recovery fix resolved.

Evidence is outside version control in `/root/jumpscan/logs/confirm/`: build/test logs, received mock
payloads, timestamp steps, UI XML, decoder failure log and 18 screenshots. Every screenshot was opened
and inspected, including the failed decode, loading state, editor, applied result, no-flight recovery,
visibility hints, guide text and final Apply error. The native interface retains the existing palette,
typography and controls. No new design-system files were introduced. API 26 execution and real-phone
performance remain untested; the existing compatibility path is compiled.

### Cleanup

App language selection was reset to the system default, portrait/automatic rotation was restored,
and `pm clear` removed both app and test-app data. The app data directory was empty afterward, removing
test login, private video, replay landmarks and pending storage. The test video, replay CSV and UI dump
were removed from the device; `/data/local/tmp` retained only its pre-existing Dalvik cache. Temporary
stock/visibility CSVs were removed from the kit. The mock API was stopped using its recorded, verified
PID, and the emulator was stopped with `adb emu kill`. Final checks found no adb device or worker qemu
process, and connection to mock port 8765 was refused. The original private video on the kit was not
modified or copied into the repository. No deployment, publication, push or service restart occurred.


## Visual polish — 17 November 2026

The Android Views now use the coach dashboard's paper (`#F2F4EF`), ink (`#20382F`),
muted text (`#53645B`), accent (`#176148`), line (`#CBD4CB`) and hover (`#E6EEE7`)
colors. The shared button helper supplies primary and secondary styles, including the
frame-step controls. Buttons have a minimum 48 dp height, 5 dp corners, native ripple/focus
feedback and readable disabled states. Record, Upload, Sign in and Apply are primary actions.
Fields use white surfaces and a visible accent border when focused. Section spacing and
heading weights separate the result groups without changing the frame-confirm workflow.
The default Android launcher icon is replaced by an adaptive vector icon using the dashboard
favicon's white upward chevron on the accent background. No library or dependency was added.

The shared metric formatter uses the active app locale: flight time and fps have zero decimal
places, knee/ankle separation and visibility have two, and all other metrics have one. Rounded
negative zero is normalized to zero. Result, queued result, history summary and history detail
reuse this formatter; the editor's height summary also uses one decimal place. The precision
matches the dashboard's DIGITS map. Fixed trailing decimal places follow this task's request.
Metric values, queue JSON and upload payloads are unchanged.

### Text contrast

Ratios below were calculated from the actual opaque sRGB colors using WCAG relative luminance.
All exceed the 4.5:1 AA requirement for normal text. The white and hover columns also check
field, button and interaction backgrounds; some combinations are conservative checks beyond
where a text color currently appears. Disabled buttons use muted text on hover, without opacity
that would reduce contrast. Primary pressed feedback uses the dashboard's `#104C37` green.

| Text color | Paper | White | Hover |
| --- | ---: | ---: | ---: |
| Ink `#20382F` | 11.37:1 | 12.59:1 | 10.64:1 |
| Muted `#53645B` | 5.67:1 | 6.28:1 | 5.31:1 |
| Accent `#176148` | 6.68:1 | 7.40:1 | 6.25:1 |
| Warning `#85421D` | 6.79:1 | 7.52:1 | 6.35:1 |
| Error `#8A3428` | 7.29:1 | 8.07:1 | 6.82:1 |
| Left legend `#004EA2` | 7.26:1 | 8.04:1 | 6.80:1 |
| Right legend `#8B4C00` | 6.06:1 | 6.71:1 | 5.67:1 |

White text is 7.40:1 on the primary accent and 9.95:1 on pressed green. The existing blue/orange
skeleton colors retain their leg-identification behavior; their darker text legend colors are
listed above. Evidence calculations are in `logs/polish/contrast.json`, outside version control.

### Build and instrumentation

Sources were synced to `/work/jumpscan-app` on `android-build-kit` as `builder`, using
`rsync --exclude local.properties` and excluding build products, logs, Git data and local
environments. The following commands passed:

```sh
ssh android-build-kit 'cd /work/jumpscan-app && timeout 900 ./gradlew --no-daemon --max-workers=1 :core:test :app:assembleDebug :app:assembleDebugAndroidTest'
ssh android-build-kit '/opt/android-sdk/platform-tools/adb install -r /work/jumpscan-app/app/build/outputs/apk/debug/app-debug.apk'
ssh android-build-kit '/opt/android-sdk/platform-tools/adb install -r /work/jumpscan-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
ssh android-build-kit '/opt/android-sdk/platform-tools/adb shell am instrument -w jumpscan.app.test/jumpscan.app.AppChecks'
python3 app/tools/check_resources.py
```

The build reported success in 51 seconds. `:core:test` was up to date; the existing reports contain
43 tests with zero failures, errors or skips. Both APK installs succeeded. Instrumentation executed
and passed queue isolation, persistence, failure retention, metric-only payload boundaries, preview
checks and the new number-format checks. Those checks exercise every metric's precision in English
and Bulgarian, including whole-number rounding, two-decimal rounding, small negative values,
negative zero and preserved nonzero negative values. All 146 bilingual resource keys and format
arguments matched. The mechanical design scan reported no findings; rendered screenshots were
reviewed separately.

Memory was checked with `free -g` before the build and each emulator launch; starts had at least
3 GB available, and starts below that threshold were deferred. Builds were sequential with one
Gradle worker. Only one headless `jumpscan-worker-a` emulator ran at a time. Early GPU-off and
small-memory configurations did not render reliably. The final API 34 configuration used two cores,
SwiftShader with Vulkan disabled, a 540 × 1140 display at 220 dpi, and 1280 MB guest RAM passed through
QEMU. This represents a phone approximately 393 dp wide. The initial install attempted before Android
had finished booting failed; installation after boot succeeded. A startup System UI ANR was dismissed
with Wait before the successful captures. Shared host memory fell below 3 GB while the emulator ran;
no new Gradle build or full model inference was started in that state. No unrelated workload or service
was stopped, and no host package was installed.

### Visual evidence and scope

Screenshots, UI XML, test logs, replay metadata and contrast measurements are under
`/root/jumpscan/logs/polish/`, outside version control. Screenshot helpers were copied into that
directory only to change their evidence path and adapt swipe coordinates to this emulator.

The result uses `/work/jumpscan-samples/private/jump1.mp4`, copied only to temporary and app-private
device storage, with the existing reviewed `jump1.csv` through the retained debug replay path.
Replay produced 232 frames, take-off at 3747 ms, contact at 4027 ms, flight 280 ms and height 9.61 cm.
This task does not claim a fresh heavy-model inference run. The result displays 280 ms, 25 fps,
9.6 cm, ratio 0.61 and visibility 0.87 in English, with Bulgarian commas for fractional values.
The exact contact preview and the existing unclear-joints warning remain visible.

History and its detail view show a mock-server record as 240 ms, 7.1 cm, ratio 1.89 and visibility
0.87 in English, and 240 ms, 7,1 cm, 1,89 and 0,87 in Bulgarian. The pending queue was empty after
delivery. Login, home, filming guide, result, frame editor, history, history detail and the launcher
icon were captured in both languages and reviewed visually; no clipped text, overlaps or unreadable
colors remained. The launcher icon shows the accent fill and white chevron in the app drawer; on the
home screen the Pixel launcher adds its own light suggestion ring around the hotseat icon, which is
launcher styling, not part of the app icon. Additional checks: with system night mode on, the app kept its light paper
background; at font scale 1.3 the Bulgarian history detail kept its formatted values and stayed
scrollable; in landscape (1140 × 540) the result and frame editor rendered without clipping and
Apply and Cancel remained reachable by scrolling.

### Cleanup

Font scale, rotation and night mode were restored to their recorded values. `pm clear` removed app
and test-app data, including the test login, pending storage and the private video copy; the app
data directory was empty afterward. The temporary video, replay CSV and UI dump were removed from
`/data/local/tmp` and `/sdcard`. Older sample videos and screenshots under `/sdcard` predate this
task and were left untouched. The mock API was stopped by its recorded PID and the emulator with
`adb emu kill`. A final check found no adb device, no qemu or mock process, and port 8765 refused
connections. No deployment, publication, push or service restart occurred.

## Release build — 18 November 2026

### Initial attempt (blocked)

Release preparation adds optional signing through `-PjumpscanKeystore`, keeps versionCode 1,
sets versionName to 1.0.0, disables minification, and restricts release native libraries to
`arm64-v8a` and `armeabi-v7a`. Without the property, release signing remains unset. Debug signing
and its network configuration remain unchanged. There are no debug-only model assets in this tree.

`finish()` now decodes the result preview with the analysis's existing
`MediaMetadataRetriever`. The editor's `videoFrame()` still opens and releases its own retriever;
both paths use the same frame extraction helper. This removes the second simultaneous retriever
at analysis completion. It does not establish the cause of the earlier mid-analysis decoder error.

The kit initially reported only 2 GiB available with `free -g`. Key generation and builds waited
for a reading of at least 3 GiB. When that threshold was reached, `keytool` generated the external
JKS with RSA 4096, validity 10000 days, alias `jumpscan` and subject `CN=JumpScan`. Its random
password was generated on the kit and was not printed, logged or committed. The signing files are:

- Kit: `/work/jumpscan-keys/jumpscan-release.jks` and `/work/jumpscan-keys/keystore.properties`.
- Openclaw backup: `/root/jumpscan-keys/jumpscan-release.jks` and `/root/jumpscan-keys/keystore.properties`.

Both directories were verified as mode 700 and both pairs of files as mode 600.

The task started on `stage` at `50039449da1c349cbce72f0ad6832c93092acfe3`. Concurrent detector work
appeared in the shared workspace during the initial sync. That mixed-source build ran 51 core tests
and failed two `RealVideoTest` cases. The other task subsequently committed its work as
`5926fbdb670629fc35f02e1b4b1bd2ca51bc371e`; its own `core/VALIDATION.md` records those shipping
blockers. No detector file was changed by this release task.

To obtain an unambiguous check of the release changes, a source snapshot of the task-start commit
was overlaid with this task's build configuration, decoder change and README. It was synced with
`rsync --exclude local.properties` to `/work/jumpscan-app`, retaining the kit's private test fixtures.
Concurrent local changes were left untouched. JDK 17 and `/opt/android-sdk` were used, with one
Gradle worker and no overlapping Gradle builds. The following isolated build passed in 1m 24s:

```sh
cd /work/jumpscan-app
./gradlew --no-daemon --max-workers=1 \
  --init-script /work/jumpscan-release-check-signing.gradle \
  :core:test :app:assembleDebug :app:assembleDebugAndroidTest
```

All 43 core tests passed, with zero failures, errors or skips. The temporary init script also
asserted unsigned release configuration without the signing property, unchanged debug signing,
both release ARM filters, disabled minification and the version fields. These assertions passed.
`python3 app/tools/check_resources.py` passed for all 146 English/Bulgarian resource keys and
format arguments. The instrumentation APK was built but was not installed or executed.

The requested signed build was queued behind a fresh 3 GiB memory gate:

```sh
./gradlew --no-daemon --max-workers=1 \
  --init-script /work/jumpscan-release-check-signing.gradle \
  :app:assembleRelease -PjumpscanKeystore=/work/jumpscan-keys/keystore.properties
```

The gate waited eight minutes, repeatedly reporting 2 GiB available, and was stopped before
Gradle started. No signed APK, certificate verification, zip alignment result, APK or model package
size, packaged ABI report, or `SHA256SUMS` was produced. No emulator was launched or app installed.
The AVD configuration is x86_64; its runtime ARM support was not checked. The unfiltered release
variant, HTTPS login refusal screenshot, filming guide and release clip analysis remain untested.

Neither required fresh ordinary debug analysis ran: jump1 is untested and jump2 is untested after
this decoder change. The kit never provided a window for a normally sized emulator and at least
4 GB free for inference. No replay was substituted. Earlier validation records jump1 at 280 ms,
which conflicts with this task's requested 360 ms; a fresh run is still needed to resolve that
expectation. The earlier `FrameDecoder` failure remains unisolated.

Evidence and the isolated source snapshot are in ignored `/root/jumpscan/logs/release/`.
`debug-build.log` contains the mixed-source failure, `debug-build-isolated.log` contains the passing
build, `release-build.log` contains only the memory wait, and `final-kit-state.txt` records 2 GiB
available, no adb device, no Gradle or emulator process, and the signing file permissions. Only this
task's waiting shell was stopped. No unrelated workload, service or package was changed. The
signed jury APK and all pending emulator checks must be completed before calling this release
validated; the newer committed detector also has its separately documented test failures.

### Completion check — 18 November 2026

The continuation built the current `stage` app and core sources, starting at
`9193520560fd047c6359f3488b6e4a5569506034`, rather than the earlier isolated snapshot.
Sources were synced to `/work/jumpscan-app` on `android-build-kit` with
`rsync --exclude local.properties`, excluding Git metadata, build products and logs.
The existing external keystore was reused. No signing secret or private video was copied
into the repository. Signing directories and files still have modes 700 and 600 respectively.

The kit initially had 5 GiB available and low load. Builds ran sequentially with JDK 17,
`/opt/android-sdk` and one Gradle worker:

```sh
cd /work/jumpscan-app
timeout 1200 nice -n 15 ./gradlew --no-daemon --max-workers=1 \
  :core:test :app:assembleDebug :app:assembleRelease \
  -PjumpscanKeystore=/work/jumpscan-keys/keystore.properties
```

The build passed in 2m 3s. All 53 core tests passed, with zero failures, errors or skips.
Both debug and signed release APKs assembled successfully. No additional app code fix was
needed in this continuation. The build log and per-suite test counts are retained in
`logs/release/current-build.log` and `logs/release/core-test-results.txt`.

The ARM release APK was preserved before building the emulator variant. Verification passed:

```sh
/opt/android-sdk/build-tools/35.0.0/apksigner verify --print-certs /work/jumpscan-release-arm.apk
/opt/android-sdk/build-tools/35.0.0/zipalign -c 4 /work/jumpscan-release-arm.apk
```

Only the certificate SHA-256 digest was printed from `apksigner` output:
`a1e6b96890cf936dab8c9d98456e94be84c0930058bd38749b09c35525782cfc`.
Signature verification returned exit 0 and zip alignment passed. The APK is 60,730,924 bytes
(57.92 MiB) and contains exactly `arm64-v8a` and `armeabi-v7a` native libraries. Its only bundled
model is `assets/pose_landmarker_heavy.task`, 30,664,242 bytes (29.24 MiB), stored uncompressed.

The signed jury artifact is `/root/jumpscan/logs/release/jumpscan-1.0.apk`. Its SHA-256 is
`ed62bb9e8991e8b7bfab179ca86af333a2337a40c2b2c1ae6db246b3554072cf`.
The copy matched the preserved kit APK, and `sha256sum -c SHA256SUMS` passed in
`logs/release/`. The APK was not published.

#### Fresh ordinary analyses

Both private source clips were analysed afresh through ordinary VIEW intents on the debug
build, with no `analysis_mode` extra or replay input. The helper copied each clip directly
from `/work/jumpscan-samples/private/` to the emulator, never through this repository:

```sh
python3 logs/release/run-fresh.py 1
python3 logs/release/run-fresh.py 2
```

The wrapper captures full logcat and runs `emulator_check.py --tag fresh --mode default`.
Each run waited for new decode, full-pass and metric logs and the result UI tree. The heavy
model processed every source frame. The API 34 `jumpscan-worker-a` guest had 2048 MB RAM,
two cores and a 720 × 1280 display. Emulator 37 otherwise raises the RAM request to 2560 MB,
so `-qemu -m 2048` kept the actual guest at the intended normal size. No 1024 MB guest was
used for either analysis.

Memory was checked with `free -g` and `uptime`. The continuation waited whenever the start
gate was below 3 GiB. Both analysis starts recorded 3 GiB available; the host sometimes fell
to 2 GiB during jump1. Only this task's emulator instances and waiting shells were stopped;
no unrelated job, service or resource limit was changed. Earlier renderer and boot attempts
waited below the gate and did not run analyses.

| Check | jump1 | jump2 |
| --- | ---: | ---: |
| Frames | 232 | 247 |
| Analysis timer (s) | 268.979 | 311.905 |
| Decode timer (s) | 242.166 | 283.619 |
| Inference timer (s) | 24.325 | 26.675 |
| Contact / preview (ms) | 4027 / 4027 | 5847 / 5847 |

The analysis timer ends before core scoring and preview rendering. These are individual
shared-host runs, not benchmark medians. All 14 exported metric fields follow:

| Field | jump1 | jump2 |
| --- | ---: | ---: |
| test | cmj | cmj |
| fps | 24.91 | 24.97 |
| flight_time_ms | 280.00 | 360.00 |
| jump_height_cm | 9.61 | 15.89 |
| knee_flexion_contact_l | 25.34 | 30.01 |
| knee_flexion_contact_r | 12.96 | 28.27 |
| knee_flexion_peak_l | 34.93 | 48.42 |
| knee_flexion_peak_r | 29.51 | 46.62 |
| fppa_peak_l | -17.62 | -29.32 |
| fppa_peak_r | 11.65 | 3.16 |
| knee_ankle_ratio | 0.61 | 0.82 |
| flexion_asymmetry_pct | 15.52 | 3.73 |
| quality | 0.87 | 0.89 |
| warnings | [uncertain_timing] | [uncertain_timing] |

Both flights exactly match the reviewed 280 / 360 ms references, within one source frame
(about 40 ms). Numeric fields match the earlier full reference; the current detector reports
`uncertain_timing` on both clips, so the warning arrays are not identical to the older empty
arrays. Neither result was manually edited.

Full logcat again contains `FrameDecoder` errors `err -11`, preceded by
`Received error -11 (WOULD_BLOCK) instead of output` and followed by another decoder attempt.
Both ordinary runs nevertheless completed all frames, core metrics and preview extraction.
No fatal exception or failed analysis occurred. The log entries were recoverable platform
decoder attempts in these runs; their presence alone does not demonstrate an app failure.
This does not establish the cause of the earlier aborted run. No speculative decoder change
was made. Full logs, landmarks, metrics and result XML are in `logs/release/fresh-jump1*`
and `fresh-jump2*`. An experimental EGL renderer produced black debug screen captures;
those captures are not visual evidence. The release checks below used the standard renderer.

#### Release UI and cleanup

The x86_64 AVD used a temporary release build with its ABI filters cleared by an external
init script. The repository's ARM filters were never edited:

```sh
cd /work/jumpscan-app
nice -n 15 ./gradlew --no-daemon --max-workers=1 \
  --init-script /work/jumpscan-release-emulator.gradle \
  :app:assembleRelease -PjumpscanKeystore=/work/jumpscan-keys/keystore.properties
```

That one unfiltered build passed in 17s. It was installed after uninstalling debug. The
lightweight release UI checks used a 1024 MB guest, two cores, the standard SwiftShader
renderer, a 720 × 1280 display and at least 3 GiB available on the host. This smaller guest
was used only for UI checks, after both normal-size fresh analyses had completed. An initial
UI-driver attempt found the package absent after a guest restart; reinstalling the same
release variant in the current guest resolved that setup error.

The release app started at the login screen. Entering `http://example.invalid` and tapping
Sign in visibly produced `Enter an https:// server URL.` No credentials were entered.
The Toast is visible in `logs/release/http-refusal.png`; Android's later UI dump did not
include it. Continue offline opened home, and Pick video opened the filming guide, including
the body-framing diagram and 60 fps / slow-motion guidance. The startup, refusal and guide
screenshots were all opened and inspected. The UI driver, dumps, screenshots and logcat are
retained in `logs/release/`. No real server or account was used.

Debug app data and both emulator copies of the private clips were removed by clearing and
uninstalling debug and deleting `/data/local/tmp/jumpscan-ref.mp4`. The release app and old
instrumentation package were also cleared and uninstalled, remaining test files were removed,
and the guest filesystem was synced. The temporary unfiltered APK and ABI init script were
removed from the kit after validation. The emulator was stopped. Final checks found no
JumpScan package, no copied test video in `/data/local/tmp`, no emulator process and no adb
device. The kit's original private source clips and external signing files were retained.
No requested release check remains pending; validation on a physical ARM phone is outside
this emulator check.

### Club platform release 1.1.0 — 18 November 2026

Rebuilt the current `stage` sources at `4a2e13b674803e12b6b87b95bfd2650d36aad0a8`
with versionName `1.1.0` and versionCode `2`. This feature release includes sessions,
check-ins, coach feedback and idempotent uploads. Sources were synced with
`rsync --exclude local.properties`, excluding Git metadata, build products and logs,
to `/work/jumpscan-app` on `android-build-kit` as `builder`. The existing external
keystore was reused; its directory and files retained modes 700 and 600. No signing
secret was printed or copied into the repository.

The kit initially reported 5 GiB available. Memory and uptime were checked before
build and emulator work, with a 3 GiB start gate. Builds ran sequentially with JDK 17,
`ANDROID_HOME=/opt/android-sdk` and one Gradle worker:

```sh
cd /work/jumpscan-app
timeout 1200 nice -n 15 ./gradlew --no-daemon --max-workers=1 \
  :core:test :app:assembleRelease \
  -PjumpscanKeystore=/work/jumpscan-keys/keystore.properties
```

The build passed in 56 seconds. `:core:test` was UP-TO-DATE; its existing XML reports
contain 55 tests with zero failures, errors or skips. No fresh core test execution
is claimed for this release build. The signed ARM APK was preserved before the
emulator variant was built. `apksigner verify --print-certs` and `zipalign -c 4`
both passed. Only the certificate SHA-256 digest was printed from certificate output:
`a1e6b96890cf936dab8c9d98456e94be84c0930058bd38749b09c35525782cfc`.

The APK is 60,753,932 bytes (57.94 MiB), with exactly `arm64-v8a` and `armeabi-v7a`
native libraries. Its uncompressed `assets/pose_landmarker_heavy.task` remains
30,664,242 bytes. Packaged output metadata confirms versionName `1.1.0` and versionCode `2`.
The new APK replaces `logs/release/jumpscan-1.0.apk`; the filename is retained as requested.
The prior artifact is preserved as `logs/release/jumpscan-1.0-preplatform.apk`.
`logs/release/SHA256SUMS` now covers both, and `sha256sum -c SHA256SUMS` passed:

- New: `f2f7d4925726192451788cdc69275c7c74c417f7094488f3d78e82404e52375e`.
- Preplatform: `ed62bb9e8991e8b7bfab179ca86af333a2337a40c2b2c1ae6db246b3554072cf`.

An external temporary Gradle init script cleared only release ABI filters; the
repository's ARM filters were unchanged. That signed emulator build passed in
14 seconds and included x86_64. Only AVD `jumpscan-worker-a` ran, headless, with a
1024 MB guest, two cores, SwiftShader and a 720 × 1280 display at 320 dpi. Existing
test packages in the guest were cleared and removed before installing the release.
An initial Android System UI timeout dialog interrupted the first UI attempt;
selecting Wait allowed the smoke test to proceed. No JumpScan code change was needed.

The release app started at sign-in. Submitting `http://example.invalid` visibly
showed `Enter an https:// server URL.` No credentials or server were used. Continue
offline opened home with the Sessions block, disabled How I feel today check-in
entry and `Sign in to see sessions, check-ins and coach feedback.` explanation.
Pick video opened the filming guide with its diagram and frame-rate guidance.
Startup, HTTP refusal, offline home and filming-guide screenshots are retained in
`logs/release3/`; every saved screenshot was opened and inspected. The first refusal
capture missed the toast, so it is retained separately as `http-refusal-before-toast.png`;
the final `http-refusal.png` visibly contains the refusal. Final smoke logcat contains
no fatal exception or JumpScan ANR, and the app process remained alive.

Build, verification, UI-driver, logcat and cleanup evidence is in `logs/release3/`.
Cleanup cleared and uninstalled the release, removed the UI dump, reset locale and
display overrides, synced the guest and stopped the emulator. The temporary init
script and unfiltered APK were removed from the kit. Final checks found no JumpScan
package, adb device, emulator process or active Gradle build. No private clips were
used, no host packages were installed and no unrelated process was stopped. The APK
was not published. Physical ARM execution and live-server integration were outside
this release smoke test; no requested check remains blocked.

## Learned detector, second integration — 18 November 2026

The result screen still reports no flight when only the learned detector proposes one. Check frames
now presets both suggested edges only when no_flight_detected is present and the Kotlin Analysis
contains learnedSuggestion. The editor displays a short English or Bulgarian instruction to confirm
the suggestion before applying it. Normal automatic results and manual editing retain their existing
entry paths. Suggestions are not added to metrics JSON or uploaded data.

Builds ran as builder on android-build-kit in /work/jumpscan-app, with one Gradle build in that
directory at a time. The final build command was:

```sh
nice -n 15 ./gradlew --no-daemon --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest
```

Both tasks passed. The debug and instrumentation APKs were installed on the dedicated
jumpscan-worker-a AVD. The platform instrumentation command passed:

```sh
/opt/android-sdk/platform-tools/adb shell am instrument -w jumpscan.app.test/jumpscan.app.AppChecks
```

The instrumentation reported: "Queue isolation, durability, failure retention, payload boundaries,
preview and number-format checks passed." The associated core run in /work/jumpscan-learned passed
53 tests, with no failures, errors or skips, including the unchanged private-clip and stock-squat
assertions. Details and measured detector tables are in core/VALIDATION.md.

Only one emulator ran at a time. The final replay used the same AVD with a 720 × 1280 display,
GPU disabled and a 1024 MB QEMU guest to leave memory for the kit's existing workloads. Memory and
uptime were checked; the replay driver waits whenever available host memory is below 3 GB. Earlier
larger boots were closed before launching the final emulator. Existing services and the unrelated
RTMPose extraction jobs were not changed.

The emulator replay used only /work/jumpscan-samples/one-person.mp4 and the committed public
squat_jumps_one_person.csv.gz landmark fixture. No owner video or private landmarks were copied
into the repository. The existing debug-only analysis_mode=replay path was used; release behavior
was not expanded. The reproducible driver and output are retained at
logs/learnedv2/check_suggestion.py and logs/learnedv2/suggestion-check.txt. It ran on openclaw under
systemd-run with MemoryMax=2G, MemorySwapMax=256M and nice level 15.

In English and Bulgarian, the automatic stock-squat result retained null flight time and height,
no_flight_detected, and unavailable automatic edge markers. Check frames opened at 9876 ms with
both suggested edges already set to 9876/9976 ms. The translated confirmation instruction was
visible above the preview. Opening the editor and cancelling left the automatic metrics unchanged.
The debug metrics JSON retained exactly 14 fields and no suggestion field. Explicit Apply in the
English editor produced the manual result with the "Frames set by hand" label, 100 ms flight time
and 1.2 cm displayed height. This is an intentional UI confirmation check on a planted-foot clip,
not an assertion that the squat contains a jump. The existing debug metrics.json export describes
the initial replay; the applied manual result is verified through the visible result and UI dump.
A new Bulgarian replay then restored the automatic no-flight result and checked cancellation again.
No upload or network action was invoked. App locale selection was returned to the system default.

All five final screenshots were opened and inspected. Text wraps without clipping, both suggested
edge values are readable, the skeleton remains aligned with the stock subject, and Apply and Cancel
are visible and usable in both languages:

- logs/learnedv2/result-en.png
- logs/learnedv2/suggestion-en.png
- logs/learnedv2/manual-en.png
- logs/learnedv2/result-bg.png
- logs/learnedv2/suggestion-bg.png

Matching XML dumps and initial replay metric JSON are retained beside the screenshots. Build and
instrumentation output are in logs/learnedv2/app-build.log and instrumentation.txt. This debug replay
checks the suggestion and manual-confirmation flow; it does not benchmark full video pose extraction.

## 2026-11-18 — Bulgarian app terminology

Aligned Bulgarian labels, warnings, history and frame-editor text with the reviewed terminology and
coach dashboard, retaining „спортист“. Corrected the suggested-edge articles and saved-result and
slow-motion guidance. English changes are limited to the two knee/ankle ratio labels. String names
and exact format placeholders are unchanged; all 147 English/Bulgarian resource checks passed.
There are no baseline strings in these resources. The camera-depth caveats remain because the
calculation uses estimated 3D depth.

As `builder` on `android-build-kit`, in `/work/jumpscan-app`,
`:app:assembleDebug :app:assembleDebugAndroidTest` passed with one Gradle worker. Both APKs were
installed and `jumpscan.app.test/jumpscan.app.AppChecks` passed. One `jumpscan-worker-a` emulator
was used; `free -g` was checked and UI work waited whenever available memory fell below 3 GB.
Bulgarian home, result, frame editor and history screenshots, including scrolled metrics and history
detail, are retained in `/root/jumpscan/logs/appterms/` and were opened and inspected at 720 × 1280,
320 dpi. Longer labels wrap without clipping or overflow; no layout change was needed. Results used
the reviewed owner landmarks through debug replay; history used the loopback-only mock API. No
owner clip was copied into the repository, and no production endpoint or service was changed.
Test app data and private device copies were cleared, the temporary mock was stopped, and the
emulator was shut down after validation.

## 2026-11-18 — Club platform

Implemented athlete sessions, daily check-ins and coach feedback in the existing plain-Views app.
Home loads up to five upcoming visible sessions, formats their timestamps in the device's local
zone, shows the next attempt when jumps already exist, and disables cancelled sessions. Selecting
one fixes the test type and carries the session into the result and upload. Club requests run in
the background; their failure leaves video controls available. Session parsing accepts both JSON
booleans and the server's SQLite 0/1 cancellation flags.

The check-in uses the UTC calendar day required by the API, five integer scores with 5 as best,
and optional paired training minutes/RPE. Check-ins share the existing account/server-bound atomic
queue and replace the pending item for the same day. Feedback is paginated, newest first, with
translated author roles, timestamps, jump/session references, a reply field and home unread count.
Opening feedback calls POST /api/me/read. New labels are available in Bulgarian and English.

A result receives one random UUID before its first upload or queue write. Every attempt carries
that client_id and the optional session_id. Older queued results acquire a UUID that is written
before a network attempt. Both HTTP 201 and repeat HTTP 200 acknowledge success; failures retain
the saved item. Queue files contain metrics or check-in numbers, never tokens, video or landmarks.

Validation used builder@android-build-kit, /work/jumpscan-app, JDK 17 and /opt/android-sdk. Sources
were synced with rsync --exclude local.properties, excluding build products, Git metadata and logs.
Only one Gradle build ran at a time. The final required command passed:

```sh
cd /work/jumpscan-app
timeout 1200 nice -n 15 ./gradlew --no-daemon --max-workers=1 \
  :core:test :app:assembleDebug :app:assembleDebugAndroidTest
/opt/android-sdk/platform-tools/adb -s emulator-5554 shell am instrument -w \
  jumpscan.app.test/jumpscan.app.AppChecks
```

All 55 core tests passed, with zero failures, errors or skips. Both APKs assembled. Instrumentation
on the final APKs passed club parsing, local time conversion, score/pair validation, day-key
replacement, legacy UUID persistence, retry identity, account/server isolation, failure retention,
payload boundaries, preview and existing locale/precision checks. The final Bulgarian unread label
was also checked in the packaged APK. Local checks passed:

```sh
python3 app/tools/check_resources.py
python3 app/tools/check_mock_api.py
```

All 192 resource keys and format arguments match. The mock contract check exercises authentication,
sessions and cancellation, a dropped response and repeat 200, check-in create/replace and paired
validation, feedback ordering/references and read counts. The fixture binds only to loopback and
uses a synthetic athlete; its /_fixture endpoint exposes only this temporary test state.

Emulator flows used only AVD jumpscan-worker-a, with no overlapping emulator. Memory and uptime were
checked before work; drivers waited whenever available memory fell below 3 GiB. The initial 2048 MB
guest was stopped when it left insufficient headroom. UI and replay checks then used a 1024 MB guest,
two cores, SwiftShader and a 720 × 1280 display at 320 dpi. This checks the club app flows, not inference
performance. The reviewed debug replay read the original clip and landmarks directly from the kit
into app-private device storage; no owner clip or private landmark fixture entered the repository.

Observed through the app against app/tools/mock_api.py:

- Selected Morning jumps; the cancelled session was visibly disabled. The result displayed the
  selected session in both languages. The first upload stored session_id=1 and a random client_id,
  then the fixture deliberately closed the connection without a response. The app retained one
  queued file. Send pending repeated the identical UUID, received 200, removed the file and left
  exactly one stored jump. Home then showed attempt 2 of 3.
- Selected all five scores as 5 and saved an online check-in with both optional fields empty.
  Disabled Wi-Fi/mobile data and removed adb reverse forwarding, then saved 45 minutes and RPE 6.
  One pending check-in remained for the day. Home showed the saved status and unavailable sessions;
  Record still opened the filming guide. Restoring connectivity and Send pending updated the same
  server record and emptied the queue.
- Opened the coach thread from the home badge, saw the author role and session reference, and sent
  “Thanks today” through the reply box. The reply appeared first. The fixture's read_at was set,
  its unread count became zero, and home displayed zero unread comments.

Screenshots, matching UI trees, build/instrumentation output, request payloads and mock state are
retained outside version control in /root/jumpscan/logs/appclub/. Home, check-in, feedback and result
with a session were captured in Bulgarian and English. Every saved screenshot was opened and
inspected; controls and longer Bulgarian labels wrap without clipping or overlap, and lower fields
and actions remain reachable by scrolling. Screenshot review also corrected the Bulgarian unread
count wording. These are mock API and debug replay checks; no production account or server was used.

Cleanup cleared and uninstalled the app and instrumentation package, removed this task's temporary
video and UI dump, removed adb forwarding, reset app locale and display overrides, and returned
animation scales and radios to their normal enabled settings. The app data directory was empty
before uninstall. The temporary mock was stopped by its recorded, command-checked PID and the
emulator was stopped with adb emu kill. Final checks found no adb device, emulator/mock process or
listener on port 8765. Original kit clips and fixtures were retained. No deployment, publication,
push, unrelated workload stop or service restart occurred.

## 2026-11-18 review fixes

An independent review of the club-platform code found defects; these were fixed and checked.

- Timestamps: every server timestamp (sessions, comments) is now parsed with OffsetDateTime, which
  accepts both `Z` and `+00:00`. The real server emits `+00:00`, and Instant.parse on Android 8–13
  libcore (older than JDK 12) rejects it. app/tools/mock_api.py now emits `+00:00` too. The
  instrumentation check parses both forms. The test emulator is API 34, which already accepts
  offsets in Instant.parse, so this run cannot prove the old failure; the fix follows from the
  documented libcore difference.
- Offline queue: a permanent rejection (400–499 except 401, 408, 429) or an unreadable file is moved
  to `*.rejected` and the rest of the queue continues; home shows a count with a short message.
  Transient errors still stop and retry later. A jump rejected with 422 that carries a session_id
  is retried once without it, same client_id. A missing file in sendJump counts as already sent.
  A check-in PUT is only accepted when the response body names the day.
- Main thread: check-in Save and Upload enqueue inside the background work helper, so the click
  handler never waits on the upload lock or the disk.
- Sessions: selected and result sessions are cleared on sign-out, when the signed-in user changes
  and when the selected session is missing from the refreshed list.
- A result that is uploaded or queued is read-only (Check frames is hidden) with a message, so
  editing frames can no longer create a second jump.
- Club fetches run on their own executor, so slow club requests no longer delay other screens.
- Comments are marked read only after the newest page is shown. An empty radio group no longer
  crashes the check-in. Sign-out works offline: the token and queue owner state are dropped locally
  even if the server call fails.

Checks: `:core:test` (55 tests, 0 failures), `:app:assembleDebug`, `:app:assembleDebugAndroidTest`,
the instrumentation checks on the emulator, app/tools/check_mock_api.py and check_resources.py all
passed.

Observed on the emulator (API 34, Europe/Sofia) against the real server running as a fresh seeded
throwaway database, reached through an SSH reverse tunnel:

- Home listed the real seeded session with the local time (17:00 UTC shown as 7:00 PM) and the
  feedback screen showed all comments with local times; unread count cleared.
- A debug-replay result was uploaded with the tunnel down and queued. The coach then cancelled the
  session. With the tunnel restored the app sent it: the server answered 422, the app retried
  without session_id and the server stored exactly one jump with the original client_id.
- A hand-made check-in dated 2026-10-01 (older than the server window) was refused with 422 and set
  aside as `.rejected`; the jump queued behind it was still sent and stored once. Home showed
  "1 item(s) could not be sent and were set aside."
- A queued result showed the saved message and no Check frames button.
- Signing out with the network down returned to the sign-in screen and removed the token, name and
  user id from the app preferences.
- A quick read-only check against the public server (demo athlete) showed the sessions list and the
  feedback screen with correct local times. Nothing was uploaded there; opening feedback marked that
  demo athlete's two comments as read.

Screenshots (each opened and inspected) are in /root/jumpscan/logs/appfix/. The app, test package,
emulator, tunnel, throwaway server and database were removed afterwards. The emulator already held
about 4 GB when the run started, so the 3 GB free-memory gate was bypassed for adb commands only;
Wi-Fi had to stay disabled in the emulator because its second network blocked 10.0.2.2.

## 2026-11-18 completion and independent verification

The uncommitted review fixes above were reviewed against every finding in
`logs/review-app-club.md`. Completion also fixes the history's `created_at` display through the
same offset-aware local-time formatter, discards the retained analysis and upload identity on
sign-out or an authenticated account/server change, and validates that a successful check-in
response identifies the requested day. Logout also clears local state after a 401 response.
Save and Upload capture the queue identity before background work and check cancellation after
acquiring the lock. A missing jump file leaves the result read-only even without a returned ID.
Malformed check-in filenames and backup-only corrupt files cannot block the queue.

The extended instrumentation covers `Z`, `+00:00` and nonzero offsets, comment local times,
400/403/404/422 quarantine, continued delivery, 401/408/429/500 retention, a failed session
fallback, unchanged retry client IDs, missing files and corrupt backups. No dependency was added.

Verification used `builder@android-build-kit`, `/work/jumpscan-app`, and an rsync excluding
`local.properties`, `server/`, `core/`, and `SPEC.md`. A checksum dry run confirmed that the core
source already matched. Only one Gradle build ran, with at most one `jumpscan-worker-a` emulator
running at a time.
The build command was:

```sh
./gradlew --no-daemon :core:test :app:assembleDebug :app:assembleDebugAndroidTest
```

It succeeded. The core test reports contain 55 tests, no failures, no errors and no skips.
Both APKs installed successfully, and `adb shell am instrument -w
jumpscan.app.test/jumpscan.app.AppChecks` reported all checks passed. The resource checker passed
all 194 English/Bulgarian strings, and `python3 app/tools/check_mock_api.py` passed.

The API 34 emulator initially forced its RAM to 2560 MB, leaving the host below the 3 GiB
available-memory gate. Validation paused and that emulator was stopped. The same AVD was restarted
with `-lowram -memory 1024 -skin 720x1280 -feature -Vulkan`; this kept host available memory above
the gate. UI inspection used a 128 MB heap after the stock UI inspection command was killed by
guest memory pressure. No unrelated workload was stopped.

Against `https://jumpscan.stormlabs.cloud`, the app signed in as the demo athlete and loaded
sessions, the complete coach-note thread, and history with `Europe/Sofia` as the device timezone.
The public API returned session and comment timestamps ending in `+00:00`. The session at
17:00 UTC rendered at 7:00 PM, coach notes at 06:21 UTC rendered at 8:21 AM, and history also used
local time. No jumps, check-ins, comments, sessions or media were created on the public server.
Only authentication, reads and the normal read marker were used; both app and inspection logins
were signed out. No owner clip was used in this run.

Additional UI checks used a temporary loopback-only threaded mock and the public stock squat
fixture (`one-person.mp4` and the existing landmark CSV), not private clips. Upload displayed its
cancellable busy screen while a different queued POST held the upload lock for 30 seconds.
The uploaded result had no Check frames button and showed its read-only message. A 401 followed
by login as another owner discarded the old result and selected session. Removing the selected
session from the mock's list cleared the selection. Check-in opened during a 60-second sessions
request; the server trace shows its check-in GET finishing while the sessions GET was still
pending. The full click, UI inspection and screenshot sequence took 9.8 seconds. Check-in Save
stored one check-in. With the loopback forwarding removed, sign-out returned to login and removed
the token and owner ID. The UI driver was corrected to use the initial "How I feel today" label
rather than waiting for the club request to replace it with the status label.

Both APKs were uninstalled, the device's temporary stock clip and UI dump were removed, and the
emulator and temporary mock were stopped. Final checks found no adb device, emulator, Gradle
daemon, mock process or port 8765 listener. The kit's original samples were retained.

All 16 screenshots were opened and inspected. Screenshots and command logs are in
`/root/jumpscan/logs/appfinish/`. This run uses API 34;
Android API 26–33 runtime verification remains a limitation, although the timestamp parser no
longer depends on their `Instant.parse` behavior.

## 2026-11-18 — Form, nutrition, assistant and training screens

The four home entries use the existing programmatic view styles. Form analysis uses the shared
on-device pose pipeline and sends form metrics only. Food, weight, diary completion and training
sets use the account-scoped pending queue. The adaptive program is read-only in the app.
Assistant proposals require a separate online confirmation and are never queued for replay.

### Build and instrumentation

The repository was synced to `/work/jumpscan-app` on `builder@android-build-kit` with
`rsync --exclude local.properties`. Host checks used `free -g`, `uptime` and `MemAvailable`;
only one Gradle build and at most one `jumpscan-worker-a` emulator ran at a time. The final build
ran with the emulator stopped and succeeded:

```sh
./gradlew --no-daemon :core:test :app:assembleDebug :app:assembleDebugAndroidTest
adb shell am instrument -w jumpscan.app.test/jumpscan.app.AppChecks
python3 app/tools/check_resources.py
python3 app/tools/check_mock_api.py
```

All 65 core tests passed. Both APKs installed; the instrumentation passed routing and ordering,
stable retry IDs, durability, queue isolation, form payload boundaries and localized numbers.
The resource checker passed all 323 English/Bulgarian strings. The existing mock API check passed.

### End-to-end and visual checks

The API 34 emulator signed in as `athlete2@demo.test` against a loopback instance of the stage
server with a disposable seeded database. On-device analysis of the public Pexels 5034571 stock
clip (`one-person.mp4`, 300 frames) showed 4 complete, 0 partial and 2 unclear repetitions. With
the API tunnel disconnected, upload saved the form metrics to the pending queue. Reconnecting and
sending pending items returned HTTP 201. GET history showed the set and its per-repetition
measurements. Replaying the same form `client_id` returned HTTP 200 without adding a second row.
No owner clip, video payload or landmarks were sent.

The API confirmed 123.5 g of oats for lunch, body weight of 81.6 kg, and a scheduled set of 6 reps
at 42.5 kg and RPE 7.5 with its assigned plan ID. The weight trend and daily totals were visible.
A locally seeded program showed the goal, rate, applied targets and expenditure. The local
assistant model fixture returned an answer, proposed 80.4 kg for the previous day and left that row
unchanged before confirmation. Confirm applied one change. A model-side HTTP 503 produced the
localized unavailable message and retained the draft.

English and Bulgarian emulator screenshots were captured and inspected under
`/root/jumpscan/logs/appui/`. The offline form receipt and retry control were moved above the
measurements and the result refreshes after delivery. Unclear repetitions show the reasons
without repeating unavailable measurements. The final views showed no text truncation or overlap
apart from normal scroll boundaries. Evidence includes `build-final.log`,
`instrumentation-final.txt`, `public-api.txt`, `local-api.log`, and `local-verification.txt`.

### Public deployment limitation

Public login succeeded, but `https://jumpscan.stormlabs.cloud/openapi.json` did not advertise any
of these feature routes, and authenticated reads for form, foods, weights, plans, set logs and
assistant history returned HTTP 404. Public feature writes and a public model test could not be
verified. The public inspection sessions were signed out, and no public feature records were
created or deleted.

The app signed out of the loopback server. Both APKs, the copied stock clip and UI dump were
removed from the emulator. Display and locale overrides were reset. The emulator, tunnel,
loopback server and model fixture were stopped; the disposable database was removed. The kit's
original media was retained. `final-cleanup.txt` confirms no device or test listener remained.

## Release 1.1.2 — 18 November 2026

On `builder@android-build-kit`, the build ran in `/work/jumpscan-app` after an rsync excluding
`local.properties`. Host memory and uptime were checked before the build; Gradle ran alone with
one worker. `:core:test :app:assembleRelease` ran with the external signing configuration and
`--rerun-tasks`; all 65 core tests passed with no failures, errors or skips.

The APK is version 1.1.2 (code 4), 60,840,072 bytes (58.02 MiB), and includes only `arm64-v8a`
and `armeabi-v7a`. The uncompressed `assets/pose_landmarker_heavy.task` is 30,664,242 bytes
(29.24 MiB). `apksigner verify` and `zipalign -c -P 16 4` passed. The signer certificate SHA-256 is
`a1e6b96890cf936dab8c9d98456e94be84c0930058bd38749b09c35525782cfc`.

On the API 34 `jumpscan-worker-a` emulator, a temporary signed build with the ABI filters cleared
prefilled `https://jumpscan.stormlabs.cloud` and signed in as `athlete2@demo.test` over HTTPS.
All four Home entries opened, and Training loaded the live demo plan. The Nutrition screen saved
82.37 kg and 134.7 g of cooked buckwheat groats for lunch on November 14, 2026. That date had no
existing weight. API reads confirmed both writes; targeted deletes and follow-up reads confirmed
that neither test record remained and the original food logs were unchanged. The assistant
handled HTTP 503 with the model-unavailable message, retained the draft, and created no conversation.

After sign-out, Form Tracker analyzed the public Pexels 5034571 squat clip on-device: 4 complete,
0 partial and 2 unclear reps. Inference started with 3 GiB available on the kit. The headless EGL
renderer produced a black screenshot; the Android UI hierarchy recorded the result and metrics.
No form record, video, or landmarks were uploaded, and no private clip was used. No JumpScan
crash or ANR was recorded. The app, copied clip, and temporary unfiltered APK were removed;
the read-only emulator was stopped. Evidence is in `logs/release5/`.

`logs/release/jumpscan-1.0.apk` now holds the signed ARM release, and `sha256sum -c SHA256SUMS`
passed for all three archived APKs. The previous file was verified to be 1.1.0 and preserved as
`jumpscan-1.1.0.apk`; no 1.1.1 artifact was found to preserve. No APK was published.

The starting `stage` checkout had an empty release server default. Both APKs include a local
`MainActivity.kt` change restoring the editable HTTPS default without changing the debug default.
That change is intentionally outside the two-file commit; apply it when rebuilding from the
commit alone. A copy of the source diff is in `logs/release5/release-default.patch`.

## 2026-11-19 — Competition interface redesign

The Android build ran on `android-build-kit` with `ANDROID_HOME=/opt/android-sdk` and the
shared Gradle lock. `:core:test :app:assembleDebug :app:assembleDebugAndroidTest` succeeded;
the core report contains 65 passing tests. The installed `jumpscan.app.AppChecks` passed, as
did the local mock API and localization checks. The 104,920,422-byte debug APK is 159,417 bytes
larger than the preceding 104,761,005-byte debug build. No dependencies or model assets were added.

The API 34 emulator used a single `jumpscan-worker-a` instance under `/work/jumpscan-emu.lock`.
The app connected over an SSH/ADB reverse tunnel to a disposable seeded server on port 8196.
Bulgarian and English app screenshots show the daily workspace, profile and connection controls,
jump and technique history, club and check-in, training, nutrition, and the assistant’s
model-unavailable state. The technique history used a synthetic squat set through the real
API and the catalogue's 404 fallback. Result and frame-editor screenshots use the public squat
clip and committed landmarks through the existing debug replay path; they are UI evidence,
not a new full-inference accuracy run.

The server suite returned 453 passed and one skipped. The Chrome smoke check passed with
coach, athlete and administrator accounts at 1280 and 390 pixels, in both languages and both
themes. It also checked axis labels and rendered a catalogue-backed hold fixture. See
`/root/jumpscan/logs/uiv2/app/` and `/root/jumpscan/logs/uiv2/dashboard/` for the captures
and logs. No production service, account, database or deployment was used.

## 2026-11-19: Live form coaching

### Build and tests

On `android-build-kit`, after the repository sync and with the kit's private fixtures copied into
`core/src/test/resources/private/`, `flock /work/jumpscan-gradle.lock ./gradlew --no-daemon :core:test
:app:assembleDebug` succeeded: 68 core tests ran with no failures or errors, and the debug APK built.
No private data is in the APK. On the host, `systemd-run --scope -q -p MemoryMax=800M nice
server/.venv/bin/python -m pytest server -q` reported 471 passed and one skipped. No production endpoint was used.

### Live pose model choice

Live mode bundles Google's float16 Pose Landmarker lite model, revision 1, and runs it on the CPU in
`LIVE_STREAM` mode. SHA-256: `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`
(5.8 MB). Recorded video keeps the heavy model. An earlier jump measurement found that lite loses the
legs during jumps; slow form exercises were therefore re-checked with all three models.

All three models processed the same clips on the kit (AMD 4700S desktop CPU, MediaPipe Python 1.2.3,
`VIDEO` mode, longest side 720 px, one frame in flight). Every frame produced a pose with every model.
Counts are complete / partial / unclear from `:core:formBatch`.

| Clip | Hand count | Lite | Full | Heavy |
|---|---:|---:|---:|---:|
| Pexels 5034571 woman, squat, 483 frames | 9 | 8 / 0 / 2 | 8 / 0 / 2 | 8 / 0 / 2 |
| Stock man, squat, first 10 s (the replay clip), 300 frames | 4 | 5 / 0 / 1 | not run | 5 / 0 / 1 |
| Interval Push-ups (Wikimedia Commons), 3–49 s, every second frame, 691 frames | 6 | 6 / 0 / 0 | 6 / 0 / 0 | 5 / 0 / 3 |

| Model | Size | Mean ms per frame | p95 ms |
|---|---:|---:|---:|
| Lite | 5.8 MB | 31.8–35.9 | 51–56 |
| Full | 9.4 MB | 46.2–47.5 | 65–66 |
| Heavy | 30.7 MB | 113–117 | 135–136 |

For these slow movements, lite counted as well as full and heavy and did better than heavy on the
push-up clip, at about a third of heavy's cost and two thirds of full's. Full adds 3.6 MB without a
measured gain. Lite therefore stays live; it leaves the most headroom on a mid-range phone. The GPU
delegate was not added because it could not be measured on any device available here. Jumping jacks
remain the one live entry where the earlier leg-loss finding may matter; they are unvalidated.

These are desktop CPU figures. They are not Galaxy A54 performance evidence.

### Emulator check of the live streaming path

On the AVD `jumpscan-worker-a` (API 34, x86_64, SwiftShader, emulated back camera), held under
`flock /work/jumpscan-emu.lock`, the debug build was installed and run in English and Bulgarian.
The debug-only "Live replay" button feeds `/work/jumpscan-data/form/replay.mp4` (the stock squat clip
above, 360x648, 10 s) through `CameraFormFeed.submit`, the same `LIVE_STREAM` landmarker, the framing
check and `FormStream` used for the camera, at wall-clock pace with timestamps from
`SystemClock.elapsedRealtime()`. Frames that arrive while the landmarker is busy are dropped, as with
the camera.

The first run failed with "Camera or pose tracking unavailable": `MediaMetadataRetriever` returned
RGB_565 frames, which MediaPipe rejects. Replay frames are now converted to ARGB_8888, and replay and
landmarker errors are logged under the `JumpScanForm` tag.

| Run | Callbacks in 10 s | Callbacks/s | Mean / p95 submit-to-result ms | Complete / partial / unclear |
|---|---:|---:|---:|---:|
| English | 137 | 13.7 | 18.4 / 30 | 4 / 0 / 3 |
| Bulgarian | 138 | 13.7 | 18.6 / 30 | 4 / 0 / 3 |

The emulator pace is limited by seeking and decoding each video frame, not by inference (about 18 ms).
At about 14 tracked frames per second the live count matched the hand count of 4. The recorded 30 fps
analysis of the same clip gave 5 / 0 / 1. The difference comes from the lower live frame rate, which
leaves more boundary and gap repetitions unclear. It is not a difference between engines; identical
frames give identical results (core tests).

Screenshots in `logs/formv2/` (outside git): the exercise picker over the whole catalogue, the live screen
with the virtual camera (no person, so the framing prompt asks the user to step back and Start stays
disabled), the replay with the skeleton overlay, rep counter and cue banner, and the set summary, in both
languages. The emulator has an English offline voice and no Bulgarian voice. The English screen reports
spoken counts and cues; the Bulgarian screen says that only counts are spoken and cues stay on screen.
Audio was not captured. Upload was not exercised in this run.

### Still needs a physical Galaxy A54

Real-camera tracking for each exercise and recommended view; the front camera's orientation and
mirroring; daylight and low-light tracking; skeleton alignment over the image; frame rate, end-to-end
latency, frame drops and heat across repeated sets; Bulgarian speech with and without an installed
offline Bulgarian voice; and the live upload against a real account. Count differences between live
and recorded input must be reported separately from the engine's identical-frame parity.

## 2026-11-20 — Phone test diagnostics

The Android build kit passed `:core:test :app:assembleDebug` (68 tests passed; the optional private
fixture test was skipped). `AppChecks` on `jumpscan-worker-a` also passed, including the 200-run cap,
p95, CSV escaping, frame-rate matching, manual count persistence and clearing the diagnostic log.

The API 34 emulator ran under `/work/jumpscan-emu.lock`. The public `one-person.mp4` stock clip was
picked through the guided jump test and processed by the heavy CPU model. It advanced the guide to
trial 2 and reported no flight, as expected for this clip. The same clip ran through recorded squat
analysis. The separate stock live replay exercised the lite CPU path using 50 ms replay slots.

| Run | Decoded / analysed | Wall time | Result |
| --- | ---: | ---: | --- |
| Guided jump | 301 / 300 | 136.45 s | 29.97 input fps, no flight |
| Recorded squat | 300 / 300 | 124.71 s | 4 app-counted repetitions |
| Live replay | 137 / 136 | 10.08 s | 13.59 achieved fps, 65 missed slots, 4 app-counted repetitions |

An independent test app received and opened both attachments through the Android share sheet in
English and Bulgarian. The CSV bytes matched across locales. Screenshots and the actual exported
files are in `/root/jumpscan/logs/phonekit/`. A separate seeded fixture exercised all three manual
count entries (app counts 4/4/4, entered counts 5/4/3, differences −1/0/+1) and the completion
screen. The fixture rows are not in the actual emulator report. Clear was confirmed in the Bulgarian
UI and reset the stored rows and guide. The emulator was stopped afterward.

These are emulator measurements, not Galaxy A54 results. The live camera opened on the emulator,
but there was no person in frame; camera drop counts and battery behavior still need a physical
test. The share sheet was tested with a local receiver, not with Taildrop, mail, or a messenger account.
