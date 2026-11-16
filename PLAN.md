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
- [ ] Pose coverage table: lite vs full vs heavy model, lower confidence thresholds, larger frames
      (models were downloaded to /work/jumpscan-run/private/models on the kit; no result recorded)
- [ ] Private-clip acceptance for the two failing clips, or a documented limit
- [ ] Rebuild, emulator rerun of the three clips, screenshots; remove the private videos from the emulator storage
- [ ] Dataset job: write INDEX.csv and logs/data-inventory.md with licences for /work/jumpscan-data (21 GB on the kit),
      resume or delete kaggle-gesture-jumps/dataset.zip.part, prune rejected candidates
- [ ] Re-film with the phone at knee height, whole body in frame, 60 or 120 fps
