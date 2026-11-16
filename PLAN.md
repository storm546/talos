# JumpScan: plan and checklist

Entry idea for the "John Atanasov" IT tournament, Distributed Applications (grades 11–12).
A phone films a jump landing, pose estimation runs on the phone, only numbers go to a club server.
Athlete, coach and admin roles. No video ever leaves the phone. Not a medical tool.

Scope of this round: core functions only (see SPEC.md). No public deployment, no Bulgarian UI, no documentation pack.

## Roles

- Orchestrator: writes the spec, dispatches workers, reviews diffs, verifies, commits.
- Worker S (Codex): `server/`.
- Worker C (Sonnet): `core/` and the Gradle scaffold.
- Worker A (Codex): `app/`, built and run in the emulator on the 4700S kit.
- Reviewer (Sonnet): one read-only review pass at the end.

No model training: MediaPipe Pose Landmarker is pretrained, and the kit's GPU is held by the local LLM. Training would only matter later (for example a custom landing classifier once real athlete data exists).

## Checklist

### Phase 0: setup
- [x] Read the session notes, check the kit (JDK 17, Android SDK 35, emulator image, KVM, 245 GB free)
- [x] Repo `/root/jumpscan`, PLAN.md, SPEC.md

### Phase 1: parallel
- [x] S: REST API with auth, roles, jumps, delete-my-data, input validation, seed data, pytest green
- [x] S: coach dashboard page served by the API
- [x] C: Gradle scaffold builds on the kit
- [x] C: metrics core with synthetic-landmark unit tests green on the kit
- [x] Orchestrator: review both diffs, rerun both test commands, commit

### Phase 2: app
- [x] A: Android app: login, pick or record video, on-device pose, metrics screen, upload, history
- [x] A: debug APK builds on the kit
- [x] A: emulator run on a real jump video gives finite knee numbers; screenshots saved
- [x] A: upload from the emulator reaches the API and shows up for the coach account
- [x] Orchestrator: review diff, look at the screenshots, check the stored row, commit

### Phase 3: close
- [x] Reviewer: security and correctness pass over the whole repo
- [x] Fix confirmed findings
- [x] Final report: what works, what was verified, what is left

## State on 2026-11-16

Verified: server 130 tests, core 19 tests (18 synthetic, 1 real-video fixture), debug APK builds on the kit,
emulator run from video to result screen, login, upload, history, coach read of the uploaded row.

Not verified:
- Flight time and jump height on a real jump. The stock clip used for testing turned out to contain squats
  with planted feet, so it only proves that squats are not reported as jumps. A clip with a visible jump is
  needed, ideally 60 to 120 fps, athlete facing the camera, whole body in frame.
- Accuracy of any angle against a reference measurement.
- Camera recording, the system picker, rotated video, a physical phone, a release build.

Left out on purpose: public deployment, Bulgarian UI, documentation pack, real frame timestamps for
slow-motion clips, stronger scrypt cost.

Next step: film two or three real jumps, export the landmarks with the debug build
(CSV in the app's external files folder), add them as fixtures with hand-labelled take-off and landing
frames, and tune the detector against them.

## State on 2026-11-16, evening (Codex limit reached mid-task)

Real jump clips from the owner arrived (three countermovement jumps, 25 fps, filmed from the floor).
Hand labels exist; the clips and their landmark fixtures are private and not in git.

Done and committed: real frame timestamps, portrait video, local standing reference, per-foot ground level,
bridging of short tracking dropouts inside a flight, an `uncertain_timing` warning, a filter for implausible poses.
Tests: server 131 pass; core 26 synthetic and the squat fixture pass; the private-clip test passes on one clip
and fails on two ("expected 1 flight"). The app with these last changes has not been rebuilt or run in the emulator.

Unfinished, in order:
- [x] Pose coverage table: lite vs full vs heavy model, lower confidence thresholds, larger frames
      (models were downloaded to /work/jumpscan-run/private/models on the kit; no result recorded)
- [x] Private-clip acceptance for the two failing clips, or a documented limit
- [x] Rebuild, emulator rerun of the three clips, screenshots; remove the private videos from the emulator storage
- [x] Dataset job: write INDEX.csv and logs/data-inventory.md with licences for /work/jumpscan-data (21 GB on the kit),
      resume or delete kaggle-gesture-jumps/dataset.zip.part, prune rejected candidates
- [ ] Re-film with the phone at knee height, whole body in frame, 60 or 120 fps

## State on 2026-11-16, late evening

The heavy pose model replaced the lite one: it is the only variant that tracks the legs through the whole flight
on all three owner clips (lite lost 2 to 7 airborne frames per clip, full lost one clip). All three real jumps are
now detected, landing contact within one frame of the hand label, flight time within two frame intervals.
Tests: server 131, core 26 synthetic plus squat fixture plus the private-clip test, all pass on the kit.

Open:
- [ ] Flight time is quantised to 40 ms at 25 fps (about 5 cm of jump height); film at 60 or 120 fps
- [ ] Analysis speed with the heavy model on a real phone is unmeasured (2.5 to 5 minutes per clip in the software emulator)
- [ ] Knee-angle accuracy against reference data (Twente dataset on the kit has knee flexion and forces for 4 subjects)
- [ ] Negative and positive detection rate on the Kaggle clips (600 jump, 400 non-jump) on the kit
- [ ] Decide on the 17.8 GB Twente source archive: extract the rest, then delete

## State on 2026-11-16, 23:00 (end of the staging night)

`main`, `stage` and `detector` all sit at or below commit `4a5bec2`; `main` and `stage` are identical. Nothing is pushed or deployed.

Verified by the orchestrator:
- Server: 251 tests pass. Consent, baseline and change summary, CSV export, administration, audit log, Bulgarian and English dashboard with trend charts (screenshots in `logs/dash/`).
- Core after the detector merge: 37 tests pass on the kit with no skips. Webcam benchmark rerun: flights found in 121, 89 and 107 of 200 jump clips (in place, left, right); false alarms 13 of 200 (looking down) and 6 of 200 (still pose), down from 36 and 42.
- App: debug APK builds on the kit against the merged core (one bridge call in `CoreLanding.java` had to be updated).

Reported by workers, not rerun by the orchestrator:
- Twente force-plate benchmark: front 28 of 40 right jump with no spurious results (was 21 with 5 spurious), side 20 of 40 (was 4). Side timing error is about 97 ms (three frames), and 12 front jumps are still missed. Details in `core/VALIDATION.md`.
- App v2 on the emulator: Bulgarian and English, landing-frame skeleton, filming guide, offline upload queue (`app/VALIDATION.md`, screenshots in `logs/app2/`). The app was not rerun on the emulator after the detector merge.
- Two-pass faster analysis failed its accuracy check and stays off; decoding takes 86 to 91 percent of the time.
- GPU benchmark (`logs/gpu-bench/REPORT.md`): from the side, RTMPose-x on a server gives 4.8 degrees RMSE against 10.3 for the phone; from the front nothing beats the phone (16.5).

Open:
- `docs/validacia.md`, `docs/dokumentacia.md` and the README still quote the detector numbers from before the merge (21 of 40 front, 4 of 40 side) and do not mention the GPU benchmark.
- The Docker image and the CI workflow were written but not confirmed to build or run.
- The demo seed data is perfectly linear and looks artificial.
- `app/src/debug/assets/pose_landmarker_lite.task` (5.8 MB) serves only the rejected speed experiment and can be removed with it.
- Decision for the owner: an opt-in server-side "precise mode" for side-view video. It contradicts "the video never leaves the phone" unless it is explicit.
- The owner's list in `ROADMAP.md` section G is unchanged, as is the question about the 17.8 GB Twente archive on the kit.
- Codex is out of quota until 2026-11-17 01:34.

## Update on 2026-11-16, 23:20

- Fixed a contract gap left by the detector merge: side-view results (one measured leg, warning `single_leg_view`, null metrics for the other leg) were rejected by the server. Server, dashboard and app now accept and label them; 252 server tests pass and the app builds.
- Documents and README now quote the post-merge numbers, the webcam benchmark and the GPU experiment.
- Container image: the build cannot be run on openclaw (Docker inside this container fails on its AppArmor profile, before any project step) and the kit has no container runtime. The Dockerfile is still untested. The published port was changed from 8160 (taken by another service on this host) to 8171.
- Still open: emulator run of the merged app, less regular demo data, removing the lite model asset, the owner's decisions.

## Update on 2026-11-16, 23:10 (after the limit reset)

- Twente check: the video archive holds jump videos for nine subjects (Subj04 to Subj12) and Zenodo 7422031 has reference data for all nine; only four were used so far. Started on the kit: extraction of the remaining jump videos (done), download of the reference files for Subj08 to Subj12 (`/work/jumpscan-data/twente-more.sh`, log `twente-more.log`, ends with ALL-DONE), pose extraction for the five missing videos (`twente-pose-more.sh`).
- Scheduled for 01:37 on 2026-11-17, when the Codex quota returns (`logs/run-night2.sh`): `logs/task-twente9.txt` (nine-subject validation, report to `logs/twente9/REPORT.md`, no commits) and `logs/task-appcheck.txt` (emulator check of the merged app, may commit under `app/` on `stage`). Reports land in `logs/out-twente9.txt` and `logs/out-appcheck.txt`. Their results are unreviewed until someone checks them; `main` is not touched.
- Once the nine jump videos are confirmed extracted, the 17.8 GB `Videos_anonymized.rar` holds nothing further for jumps and can be deleted on the owner's word.

## Reliability plan, 2026-11-16 night

Goal: the app should either give a number that can be trusted or say clearly that the clip must be re-filmed. Measured starting point: front view finds 28 of 40 lab jumps (side 20 of 40), side landing is about three frames late, knee flexion is 10 to 16 degrees RMSE.

Where the errors come from (measured, `core/VALIDATION.md` and `logs/twente/REPORT.md`):
1. The pose is lost during the flight (all of Subj06, 10 of the 12 front misses): body partly out of frame, motion blur.
2. No still stance before take-off (Subj07): the floor reference is never established.
3. Pose lag at landing in the side view (60 to 114 ms late).
4. 25 to 33 fps video: one frame is 30 to 40 ms, about 5 cm of jump height.
5. MediaPipe compresses knee angles (slope 0.64 to 0.74 on world landmarks).

Tried tonight and rejected: taking the flight edges from a gravity (parabola) fit of the hip trajectory alone. At 33 fps the hip signal is too noisy (about 1 cm) and the fit degenerates; errors were 140 to 190 ms. The parabola stays useful only as a plausibility check (the leg length it implies was 0.72 to 0.74 m on clean jumps).

Work packages, in order of expected gain per effort:

- R1. Keep the pose through the flight. Re-run pose extraction on the failing Twente videos with (a) a padded frame, (b) lower detection and tracking thresholds, (c) re-detection on every frame instead of tracking. Then benchmark RTMPose-m and RTMPose-l (CPU, ONNX) on the same videos, both with their own detector and cropped from the MediaPipe box. RTMPose-x already gave 4.8 degrees against 10.3 from the side. Success: Subj06 front flights tracked; decision whether an on-device RTMPose (ONNX Runtime Mobile) replaces or assists MediaPipe.
- R2. Floor reference from either side of the jump. Accept a still stance after the landing when none exists before take-off. Guarded by the existing Twente and webcam regression tests (no rise in false alarms).
- R3. Capture guard in the app before filming: whole body in frame with a margin, phone level (accelerometer), distance, light, and 60 or 120 fps recording when the phone offers it. After filming: a quality gate that refuses to report instead of reporting a doubtful number, with the reason and a re-film hint.
- R4. Confirm step. The app proposes the take-off and landing frames and the user can step frame by frame and correct them (the published My Jump method; its error at 240 fps is 1.8 ms). The stored result records whether the edges were automatic or confirmed.
- R5. Three-jump protocol. One session is three jumps; the app reports the median and the spread and flags an outlier jump. Report repeatability the way sport science does: within-athlete coefficient of variation, ICC, standard error of measurement and minimal detectable change, computed on the nine Twente subjects (9 to 11 repeated jumps each) for our numbers and for the reference.
- R6. Sub-frame edges: interpolate the threshold crossing between frames instead of taking the first frame beyond it; report flight time with its uncertainty (plus or minus one frame interval at the clip's frame rate).
- R7. Side view: measure the knee angle in the image plane (10.4 against 14.4 degrees), already supported by the data; landing lag re-examined after R1.
- R8. More data. (a) Nine Twente subjects (running). (b) Slow-motion jump videos from YouTube (120 or 240 fps, front or side, whole body): label on the full frame rate, then decimate to 30 and 60 fps to measure what frame rate costs; videos with a force-plate or My Jump readout on screen give a second weak label. Links and labels are kept, the videos are not redistributed. (c) The owner's own session filmed at 240 fps is its own reference in the same way.
- R9. Documents: replace single accuracy figures with a table "detected / refused / wrong" per view, and state the repeatability numbers from R5.

Not planned: any calibration fitted on the lab subjects, medical wording, sending video to a server without an explicit opt-in.

Order: R1 experiments and R8(b) collection run on the kit tonight; R2, R6 and R7 go to a core worker once R1 is known; R3 and R4 go to an app worker; R5 analysis follows the nine-subject report; R9 last.

First R1 result (details in `logs/r1/REPORT.md`, one video only): on Twente Subj06 front, where the app's MediaPipe setup keeps the legs in 13 of 132 flight frames and no MediaPipe setting got beyond 48, RTMPose-m with a person detector keeps 130 and tracks all ten flights. RTMPose gives 2D points only, so the likely design is a hybrid: RTMPose for tracking, feet, flight timing and the side-view angle; MediaPipe world landmarks for the front-view knee flexion. Next: the same comparison on all nine subjects through the project analyzer, single-thread speed, and model size for ONNX Runtime on Android (`logs/task-rtm.txt`). The YouTube benchmark brief is `logs/task-ytdata.txt`. Both are queued behind the two night workers (`logs/run-night3.sh`).

Prior work checked on 2026-11-16/17 (sources in `logs/ml/PRIOR.md`):
- OpenCap Monocular (Utah, March 2026, Apache-2.0 code): one phone video to 3D joint angles, 4.8 degrees mean error, but on a GPU server (under two minutes per 10 s clip), validated on walking, squat and sit-to-stand only; the authors state it performs poorly on jumping because the contact probabilities fail in flight, and that a 45 degree front-side camera position worked best. So nobody in that line has solved jumps on the phone; the 45 degree view is worth a test of our own.
- Chiu et al., Journal of Biomechanics 2025: countermovement jump from one uncalibrated camera, segmentation plus pose, 77 elite athletes against a 1000 Hz force platform, correlations above 0.7 and variation under 10 percent for most variables. The closest published work to ours; the full text could not be fetched automatically.
- My Jump: manual frame marking, flight-time error 3.4 ms at 120 Hz and 1.8 ms at 240 Hz (Pueo et al.). Supports R4 and high frame rate.
- Open-source jump tools (sportkin, boing, JumpLab): MediaPipe plus flight-time rules with manual correction; none publishes its own validation and none handles drop-jump contact time.
- Learned contact detection exists for gait and animation (Zou 2020, Mundt LSTM with F1 0.76 to 0.93, UnderPressure, the event-timing encoding of Einfalt 2020) and reports that rules lack temporal precision.

R10 (new, from our own experiment, `logs/ml/REPORT.md`): a small learned airborne classifier on the same MediaPipe landmarks, leave one subject out: front 36 of 40 (rules 28), side 33 of 40 (rules 20) with side timing error 34 ms (rules 97); webcam false alarms 7 and 2 of 200 once no-jump clips are in the training set (rules 13 and 6) with higher recall. Four subjects only. Next: nine subjects, RTMPose points as input, more seeds, then a plain-Kotlin forward pass (no new dependency). Brief: `logs/task-learned.txt`, queued after the RTMPose and YouTube tasks (`logs/run-night4.sh`).

R11 (new, `logs/ml/REPORT.md`, second section): front-view knee flexion from thigh and shank foreshortening blended with the world-landmark angle: pooled 16.5 to 14.7 degrees, three of four subjects better by 2 to 5 degrees, the out-of-frame subject worse. A modest gain that needs nine subjects before it is believed; added to `logs/task-learned.txt`.
