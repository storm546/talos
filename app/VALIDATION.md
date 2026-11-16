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
