# Detector validation, 2026-11-16

All Kotlin runs use `/work/jumpscan-detector` on the 4700S build kit. Data is read from
`/work/jumpscan-data`; private fixtures are uncommitted local resources. No subject-specific
constants or fitted angle calibration are used. Raw outputs and experiment scripts are retained
outside the repository in `/root/jumpscan/logs/detector/`.

Twente has 40 jumps per view at 33 fps. A result matches the labelled jump only if both estimated
edges are within 300 ms of the labelled edges, following the existing diagnostic script. Timing
errors are calculated only for matched jumps. “Missed” means no result, including analyzer errors;
spurious results are counted separately. Within one frame means absolute flight error <= 1000/33 ms.
The force reference uses 20 N at 100 Hz, with video alignment uncertain by roughly one frame.

| Change | View | Right jump | Spurious | Missed | MAE (ms) | Within one frame | Errors |
|---|---|---:|---:|---:|---:|---:|---:|
| Baseline | Front | 21/40 | 5 | 14 | 20.6 | 17/21 | 0 |
| Baseline | Side | 4/40 | 0 | 36 | 64.5 | 0/4 | 14 |
| 1: observed CMJ take-off | Front | 21/40 | 0 | 19 | 20.6 | 17/21 | 0 |
| 1: observed CMJ take-off | Side | 4/40 | 0 | 36 | 64.5 | 0/4 | 14 |
| 2: planted-foot reference | Front | 28/40 | 0 | 12 | 26.0 | 19/28 | 0 |
| 2: planted-foot reference | Side | 6/40 | 1 | 33 | 59.3 | 0/6 | 16 |
| 3: landing edge retained | Front | 28/40 | 0 | 12 | 26.0 | 19/28 | 0 |
| 3: landing edge retained | Side | 6/40 | 1 | 33 | 59.3 | 0/6 | 16 |
| 4: profile and single leg | Front | 28/40 | 0 | 12 | 26.0 | 19/28 | 0 |
| 4: profile and single leg | Side | 20/40 | 0 | 20 | 97.0 | 4/20 | 2 |
| 5: regression coverage | Front | 28/40 | 0 | 12 | 26.0 | 19/28 | 0 |
| 5: regression coverage | Side | 20/40 | 0 | 20 | 97.0 | 4/20 | 2 |

Webcam classes contain 200 clips each at 30 fps. Each cell lists flights / no flight / errors;
errors count against positive recall and are not treated as successful negative analyses.
The classes supply clip labels, not reviewed contact times. A jump-shaped pose signal exists in
at least one looking-down clip (104), so remaining false alarms need visual review before attributing
all of them to the detector. Class labels are nevertheless scored as supplied.

| Change | Jump in place | Jump left | Jump right | Looking down (negative) | Still pose (negative) |
|---|---|---|---|---|---|
| Baseline | 82/97/21 | 93/90/17 | 72/119/9 | 36/154/10 | 42/157/1 |
| 1: observed CMJ take-off | 74/105/21 | 61/122/17 | 68/123/9 | 10/180/10 | 6/193/1 |
| 2: planted-foot reference | 121/58/21 | 89/94/17 | 107/84/9 | 13/177/10 | 6/193/1 |
| 3: landing edge retained | 121/58/21 | 89/94/17 | 107/84/9 | 13/177/10 | 6/193/1 |
| 4: profile and single leg | 121/58/21 | 89/94/17 | 107/84/9 | 13/177/10 | 6/193/1 |
| 5: regression coverage | 121/58/21 | 89/94/17 | 107/84/9 | 13/177/10 | 6/193/1 |

Step 1 removes only the CMJ first-frame fallback. The explicitly selected drop landing may still
start above the future floor, with no inferred jump height. Sampled still-pose false alarms (1, 103,
113) came from this fallback: slow foot/hip drift was interpreted as landing, without take-off.
All 30 core tests pass on the kit, including all three private clips and the planted-foot squat.
The batch CSV now appends `contact_ms` so timing can be matched to the correct jump.

Step 2 uses stationary feet without requiring still or upright hips during preparation. The
250 ms reference duration and 2.5% displacement tolerance are unchanged. The existing hip-rise
and airborne-pose checks remain. All 31 core tests pass on the kit. Front matches by subject
change as follows: Subj04 9 to 10 of 10, Subj05 stays 9 of 9, Subj06 stays 0 of 10,
Subj07 3 to 9 of 11. Two additional Subj07 flights also lose tracking. On the three previously
matched Subj07 flights timing is unchanged; the newly recovered short flights increase pooled
MAE. Side matches rise from 4 to 6, but one spurious side result and two additional angle errors
appear before per-leg support. Looking-down false alarms rise from 10 to 13; still-pose stays 6.
This is the measured recall/false-alarm tradeoff, not a uniform improvement.

Step 3 leaves the landing algorithm unchanged after inspecting the foot signals. It already ends
flight at the first measured foot's threshold crossing, rather than waiting for both feet. A new
synthetic test locks this behavior when the second foot lands 100 ms later. Front Subj07 jumps
04, 05 and 07 have force contact at 11630, 14340 and 20150 ms, but foot clearances remain
approximately 7–15% of leg length around those frames. Detected contacts are 11727, 14454 and
20242 ms. Side Subj05 jump04 retains 18% apparent foot clearance at 12666 ms, against force
contact at 12660 ms, and crosses the floor threshold only at 12727 ms. The signal does not
supply a consistent earlier edge: Subj07 jump05's feet keep descending smoothly through the
true contact. These 60–114 ms delays exceed force sampling uncertainty; they are consistent
with pose lag/occlusion rather than evidence for a universal frame correction. Subtracting two
frames or increasing the clearance threshold would shift correctly timed flights as well. No such
correction is shipped. The synthetic first-foot test is the regression guard for edge semantics.

Step 3 validation: all 32 core tests pass; both complete batches reproduce step 2 exactly.

Step 4 adds conservative geometric view detection, an explicit view override, and measurement of
the best-observed profile leg. Side fields that cannot be measured are nullable in Kotlin and JSON.
This contract change requires app/server consumers to handle nulls; those directories are outside
this worker's scope. Front outputs and all five webcam confusion counts are unchanged by step 4.
Auto identifies 38 of the 40 labelled side clips as profile; two oblique Subj07 clips default to front.
Side matches by subject become Subj04 6/10, Subj05 6/9, Subj06 2/10 and Subj07 6/11.
One previously matched side trial (Subj05 jump08) has worse flight error, 515 ms versus 424 ms;
recovering a nearer-leg take-off and the late pose contact can lengthen the measured interval.
Side MAE increases from 59.3 to 97.0 ms on different matched populations, so improved recall must
not be described as improved flight timing. No long tracking gap is filled to recover Subj06.
Private flight times remain exactly 280, 360 and 360 ms; the squat has no flight.

The existing Python reference loaders and knee formula were rerun on the full continuous side
trials, gated on the visible left leg, with the benchmark's per-view alignment offsets. This checks
the angle formula independently of selected landing timing (it is not the error of the batch peak).

| Subject | World RMSE (degrees) | Image RMSE (degrees) |
|---|---:|---:|
| Subj04 | 15.44 | 12.73 |
| Subj05 | 10.55 | 9.07 |
| Subj06 | 12.99 | 8.75 |
| Subj07 | 17.27 | 9.97 |
| Pooled, 3738 frames | 14.40 | 10.41 |

Pooled bias changes from -6.46 to -4.83 degrees. Force-anchored 500 ms peak error over 36
landings changes from mean -10.41 (SD 6.33) to -7.58 (SD 9.13) degrees. The original report's
10.3 degree image RMSE uses its original alignment; this rerun uses the labels' offsets (including
0.49 s on Subj04 side) and obtains 10.4. Every subject improves on frame-level RMSE; peak error
variability increases. No calibration is applied.

Step 4 validation: all 35 core tests pass on the kit. The side mode loses the previously matched
Subj04 side jump08 while gaining six other Subj04 trials (net 1 to 6 matches); the other lost
result, Subj07 side jump02, was spurious. This is not a per-trial monotonic recall improvement.

Step 5 adds the optional Twente and webcam regression tests and pins the private clips to their
reviewed 280/360/360 ms outputs. The Twente assertions require at least 27 front and 18 side
matches, zero spurious flights, at least 18/3 within-frame results, MAE <= 30/110 ms and no more
than 0/3 errors. The webcam assertions bound false alarms, positive recall and analyzer errors,
so making every result empty cannot pass. Missing dataset directories skip only the relevant
test; installed but incomplete datasets fail. Batch contact timestamps are also checked.

Reproduce on the kit from the isolated directory (with data installed at the default locations):

```sh
ssh android-build-kit 'cd /work/jumpscan-detector && timeout 900 ./gradlew --no-daemon :core:test'
ssh android-build-kit 'cd /work/jumpscan-detector && timeout 900 ./gradlew --no-daemon :core:batch -Pin=/work/jumpscan-data/twente-jumps -Pout=/work/jumpscan-detector/step5-twente.csv'
ssh android-build-kit 'cd /work/jumpscan-detector && timeout 900 ./gradlew --no-daemon :core:batch -Pin=/work/jumpscan-data/kaggle-landmarks -Pout=/work/jumpscan-detector/step5-kaggle.csv'
```

The batch walker also reports root-level metadata CSVs as malformed input. All tables and
regression tests above restrict scoring to the 80 labelled Twente files or the five named webcam
class folders; metadata errors are not pose-track errors. The Python comparison script is
`/root/jumpscan/logs/detector/summarize.py`; the angle rerun is `knee_check.py` in the same folder.
The local copies of reference data remain outside version control. No dependencies were added.

Final validation includes 37 passing tests (35 synthetic/fixture tests plus both measured-data
tests), no skipped tests with the datasets installed. Running only `BenchmarkTest` with both
dataset environment variables set to absent paths also succeeds, with exactly two skipped tests.
Both final batches reproduce step 4 counts. `git diff --check` passes. Side output nullability is
a documented consumer integration requirement; no app/server changes are included here.
