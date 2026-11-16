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
