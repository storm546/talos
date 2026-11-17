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
