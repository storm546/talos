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

## Analysis speed experiment

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

Reproduction helpers:

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
