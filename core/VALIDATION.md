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

## Manual flight edges — 17 November 2026

The public `JumpAnalyzer.analyzeWithLanding` result now includes the take-off frame timestamp alongside
initial contact. Both `analyze` and `analyzeWithLanding` accept optional take-off and landing timestamps.
Manual input reuses the existing angle and landing-window calculation, replaces automatic flight timing,
and records `manual_edges`. A marked drop-jump flight is a rebound and therefore receives a height.
Automatic detection and its timing bounds retain their existing behavior.

On `android-build-kit`, in `/work/jumpscan-app`, the following core task passed as part of the Android
build, with one Gradle worker:

```sh
./gradlew --no-daemon --max-workers=1 :core:test :app:assembleDebug :app:assembleDebugAndroidTest
```

All 43 core tests passed with zero failures and zero skips. The existing 37 tests were unchanged,
including the three private landmark fixtures and measured-data regression tests. Six new synthetic
tests cover exact manual flight and height for both test types, the 1000 ms boundary, the selected
landing's contact and peak angles, absent heel/toe observations, retained low-visibility warnings,
missing or non-frame edges, out-of-clip edges, equal/reversed edges, flights over 1000 ms, and the
reported automatic take-off timestamp. Default and explicit-null calls produce identical automatic
results. Manual results use the marked contact even when there is no automatically detected flight.

Manual frame marking does not establish sub-frame accuracy. Frame rate and visibility warnings remain,
and at least ten usable angle frames plus a usable angle in the selected landing window are required.
No detector accuracy or clinical claim follows from these synthetic checks.

## Learned detector, second integration — 18 November 2026

The second integration keeps the rules detector as the sole authority for whether an automatic
flight exists. It restores the network, weights and independent probability fixtures from
5926fbdb670629fc35f02e1b4b1bd2ca51bc371e without changing the threshold or training recipe.
The prior port's automatic learned-flight source is not retained.

When the selected rules flight and a plausible learned flight agree within an inclusive 100 ms on
both edges, the result uses the arithmetic mean of each pair of continuous edge estimates. Flight
time is the difference of those means. The landing-window contact is the nearest actual frame to
the mean landing; the editor take-off marker similarly uses the nearest actual frame. Ties choose
the earlier frame. The metric is not recomputed from these snapped editor markers. Averaged timing
bounds bracket the continuous means using actual observations. Agreement adds no timing warning,
including when the original rule edge had missing tracking.

If learned inference runs but has no plausible flight, or either edge disagrees, the rules flight
is retained with uncertain_timing. If the learned path cannot run, the original rules result and
warnings are retained. A learned-only flight never produces automatic flight time, height or
contact. Its edges, snapped to nearest actual frames, are exposed only as Analysis.learnedSuggestion;
no_flight_detected and the JSON contract remain unchanged. Neither detector finding a flight keeps
the existing behavior. Manual edges bypass learned inference and always win.

The learned grid uses the clip's mean capture rate from timestamps when it is between 24 and 36 fps,
inclusive. Above 36 fps it uses 30 fps, selecting the nearest observation within half a grid step.
Below 24 fps it falls back to rules. The longest strict-above-0.5 run still needs two samples;
interpolated learned durations must be between 100 and 1000 ms, inclusive. No threshold, agreement
limit, duration limit, seed or training parameter was tuned on the held-out results.

All builds ran as builder on android-build-kit, with one Gradle invocation per build directory,
--no-daemon --max-workers=1 and nice level 15. Memory and uptime were checked before starting work.
The unrelated RTMPose extraction jobs were not touched. Core builds used /work/jumpscan-learned;
app builds used /work/jumpscan-app. The final core command was:

```sh
cd /work/jumpscan-learned
nice -n 15 ./gradlew --no-daemon --max-workers=1 :core:test
```

Result: 53 tests passed, zero failures, zero errors, zero skipped. Both optional measured-data tests
and the additional public-data policy measurement ran with installed datasets. The stock-squat
and private-clip test source and assertions are unchanged: squat has no flight and exactly the
existing no_flight_detected warning; private durations remain exactly 280/360/360 ms and pass their
independently reviewed contact and duration tolerances. Owner fixtures remain only on the kit.

The 32 synthetic rules checks retain their assertions and explicitly exercise the rules entry
point. Public wrapper checks now compare batch output to the public analyzer and verify that
continuous mean timing and snapped editor markers are consistent with the policy. Nine learned
unit checks cover independent probabilities, native and high-rate grids, interpolation, threshold
and crossing conventions, all policy branches, unusable-input fallback and manual precedence.
The three restored 33 fps reference fixtures still have maximum probability errors 0, 3.33e-16 and
0 for front, side and webcam respectively. These arithmetic checks explicitly request their
reference grid; normal inference uses the capture-rate policy above. The weights SHA256 remains
8971650c55f04b947af7f8ac6c4e52f53c8dd39713aab5508274bbc3654b2f76.

### Webcam TEST halves

The same 100 files per class were selected using the frozen SHA256-basename split. Rules and the
new public analyzer ran on identical inputs. Analyzer errors count against class recall; these
are supplied jump-class labels rather than independently reviewed airborne intervals. A suggestion
is counted only when no automatic flight exists. Duplicate-content caveats from REPORT2 apply.

| Class | Automatic flights /100 | Rules flights /100 | Suggestion only | Analyzer errors |
|---|---:|---:|---:|---:|
| Looking down, false alarms | 7 | 7 | 7 | 9 |
| Still pose, false alarms | 1 | 1 | 11 | 0 |
| Jump in place, recall | 64 | 64 | 24 | 9 |
| Jump left, recall | 34 | 34 | 45 | 12 |
| Jump right, recall | 49 | 49 | 42 | 7 |

Automatic false alarms are not higher than rules in either negative class. Positive automatic
recall is retained; learned-only recovery is presented for user confirmation rather than counted
as an automatic detection.

### Held-out policy estimate and final-model consistency

logs/ml/policy3.py computes the policy from the saved MediaPipe plus training negatives LOSO
predictions at threshold 0.5, with all nine held-out subjects and five prescribed seeds. It checks
fold train/test separation, held-subject exclusion, protocol hashes and the negative-window manifest.
It does not fit a model. The script, REPORT3.md and per-seed/per-clip tables are retained under logs/ml.

Reported automatically includes the rules flights with uncertain_timing; flagged is a subset of
reported. Correct plus wrong equals reported. Wrong means a spurious match or duration error above
two 33 fps frames, using REPORT2's inclusive 300 ms edge-matching convention. Values below are means
[min, max] over five seeds, pooled over the nine held-out subjects for each seed.

| Population | View | Clips per seed | Reported automatically | Correct | Wrong | Flagged uncertain_timing | Suggestion only | Nothing |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Labelled jumps | Front | 91 | 71.0 [71, 71] | 64.4 [63, 67] | 6.6 [4, 8] | 6.6 [4, 8] | 19.6 [19, 20] | 0.4 [0, 1] |
| Labelled jumps | Side | 91 | 60.0 [60, 60] | 37.6 [35, 40] | 22.4 [20, 25] | 19.4 [18, 22] | 24.8 [24, 25] | 6.2 [6, 7] |
| Force-negative windows | Front | 109 | 0.0 [0, 0] | 0.0 [0, 0] | 0.0 [0, 0] | 0.0 [0, 0] | 1.2 [0, 4] | 107.8 [105, 109] |
| Force-negative windows | Side | 109 | 1.0 [1, 1] | 0.0 [0, 0] | 1.0 [1, 1] | 1.0 [1, 1] | 1.6 [0, 2] | 106.4 [106, 108] |

Rules for jumps use REPORT2's saved snapshot. Rules for all 218 audited force-negative windows
were computed in Kotlin from the original public landmark rows. Every learned prediction in the
held-out table comes from its saved fold, never the final fit. Saved rules analysis errors remain
nothing, with error text retained in the per-clip table. This is an estimate of edge selection
on the saved 33 fps grid; it does not re-run landing-angle validity for each fold's averaged contact.
The final-model Kotlin measurements below include analyzer errors explicitly.

All nine Twente subjects are in the final model's training set. These numbers are only an in-sample
consistency check, not a held-out accuracy estimate. Definitions match the table above.

| View | Clips | Reported automatically | Correct | Wrong | Flagged uncertain_timing | Suggestion only | Nothing | Analyzer errors |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Front | 91 | 71 | 68 | 3 | 3 | 20 | 0 | 0 |
| Side | 91 | 60 | 40 | 20 | 17 | 29 | 2 | 2 |

Nothing includes analyzer errors. Earlier rule-only errors were also retained by REPORT2 rather
than relabelled as successful negative detections. The final model changes timing only on agreement;
it does not improve automatic recall by promoting a learned-only flight.

Evidence is retained in logs/learnedv2/core-test.log, core-results/, clips.csv and app-build.log.
The app's build, instrumentation and inspected screenshot evidence is documented in app/VALIDATION.md.
