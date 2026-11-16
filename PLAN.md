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
- [ ] S: REST API with auth, roles, jumps, delete-my-data, input validation, seed data, pytest green
- [ ] S: coach dashboard page served by the API
- [ ] C: Gradle scaffold builds on the kit
- [ ] C: metrics core with synthetic-landmark unit tests green on the kit
- [ ] Orchestrator: review both diffs, rerun both test commands, commit

### Phase 2: app
- [ ] A: Android app: login, pick or record video, on-device pose, metrics screen, upload, history
- [ ] A: debug APK builds on the kit
- [ ] A: emulator run on a real jump video gives finite knee numbers; screenshots saved
- [ ] A: upload from the emulator reaches the API and shows up for the coach account
- [ ] Orchestrator: review diff, look at the screenshots, check the stored row, commit

### Phase 3: close
- [ ] Reviewer: security and correctness pass over the whole repo
- [ ] Fix confirmed findings
- [ ] Final report: what works, what was verified, what is left
