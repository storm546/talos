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

## Side-view learned timing policy — 18 November 2026

The side-view policy was selected only after the four candidates and shipping rule were frozen in
logs/ml/PROTOCOL_SIDE.md (SHA256 61d7a06c30e7dbb6ad0d682e039f177ea84740025b35cd646190908d44b62a41).
The retained negative-trained MediaPipe LOSO probabilities cover nine subjects and five seeds.
The scoring script checks all 45 folds' protocol and negative-manifest hashes, held-subject
exclusion, test membership and train/test separation. All 2,000 baseline clip/seed outcomes,
selected edges and analyzer errors reproduce REPORT3 before candidate comparisons.

The shipping rule requires more correct side results and no more unflagged wrong results,
unchanged or better front and force-negative results, and unchanged webcam automatic false alarms.
The primary comparison uses five-seed means; force negatives are checked separately per seed.
The frozen tie-break selects the passing candidate with the most correct side results, then fewer
unflagged wrong, fewer total wrong, and finally candidate order. No candidate or threshold was
revised after scoring. Values below are mean [minimum, maximum] over seeds, each pooled over all
nine held-out subjects and 91 side-view jumps. Wrong means spurious or duration error greater than
two 33 fps frames (60.606 ms). Flagged is the uncertain_timing subset of automatic reports.

| Policy | Automatic | Correct | Wrong | Unflagged wrong | Flagged | Suggestion | Nothing | Pass |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| Shipped baseline | 60.0 [60, 60] | 37.6 [35, 40] | 22.4 [20, 25] | 3.0 [1, 6] | 19.4 [18, 22] | 24.8 [24, 25] | 6.2 [6, 7] | Baseline |
| Bounded learned landing, 0–150 ms earlier | 60.0 [60, 60] | 51.4 [51, 52] | 8.6 [8, 9] | 0.2 [0, 1] | 8.4 [8, 9] | 24.8 [24, 25] | 6.2 [6, 7] | Yes |
| Both learned edges when both detect | 60.0 [60, 60] | 58.2 [58, 59] | 1.8 [1, 2] | 1.6 [1, 2] | 0.2 [0, 1] | 24.8 [24, 25] | 6.2 [6, 7] | Yes, selected |
| Average with landing agreement widened to 150 ms | 60.0 [60, 60] | 46.0 [42, 48] | 14.0 [12, 18] | 5.6 [3, 10] | 8.4 [8, 9] | 24.8 [24, 25] | 6.2 [6, 7] | No |
| Learned landing with original 100 ms agreement | 60.0 [60, 60] | 40.2 [38, 42] | 19.8 [18, 22] | 0.4 [0, 1] | 19.4 [18, 22] | 24.8 [24, 25] | 6.2 [6, 7] | Yes |

The selected policy also improves correct results and never increases unflagged wrong in any
individual seed. Front outputs are identical per clip/seed. Force-negative automatic results
remain zero front and one flagged side result per seed, with zero unflagged wrong. The selected
policy has one spurious side result in one seed (mean 0.2), already included in wrong; the baseline
has none. The prespecified rule compares correct and unflagged wrong rather than requiring zero
spurious results.

In explicit SIDE or AUTO-selected side view, when rules and learned inference both find a flight,
the analyzer uses both continuous learned edges directly and clears uncertain_timing. Rules still
authorize automatic flight; learned-only results remain suggestions. Front selection retains the
inclusive 100 ms agreement and arithmetic means. Unavailable learned input, learned negatives,
manual edges, model weights, preprocessing and duration gates are unchanged. Editor edges and the
landing window use the nearest observed frames; the flight metric uses continuous selected edges.

In the isolated /work/jumpscan-side copy on android-build-kit, all 55 core tests passed with zero
failures, errors or skips. Two added JUnit tests cover the side selector's agreement/disagreement,
missing-flight and unavailable-input cases, plus explicit SIDE and AUTO routing, continuous flight
time and snapped editor edges. Existing front-policy, rules, manual, probability and measured-data
checks retain their assertions. The private clips remain exactly 280/360/360 ms and the stock squat
has no flight. Private resources remain only on the kit.

The fresh public-data measurement reproduces all 200 front rows exactly (91 jumps and 109 negative
windows), including errors and warnings. Force-negative automatic counts remain 0/109 front and
1/109 side, with zero unflagged wrong in both views. The webcam test halves preserve the automatic
false-alarm identity of every negative clip, including errors: looking_down is 7/100 with nine
errors; still_pose is 1/100 with zero errors. Positive automatic counts also remain 64/100, 34/100
and 49/100 for jump_in_place, jump_left and jump_right, respectively; errors remain 9, 12 and 7.

The held-out estimate uses saved 33 fps fold predictions and does not rerun angle validity after
each chosen contact, as in REPORT3. The fresh Kotlin measurement includes analyzer errors and uses
the final model; its Twente inputs are in training and are not a second held-out accuracy estimate.
Webcam class labels do not establish edge timing accuracy. Duplicate-content caveats from REPORT2
remain. Full tables, protocol, script, provenance, test XML and kit checks are retained in
logs/ml/REPORT_SIDE.md and logs/ml/side-*.

The same isolated copy passed `:app:assembleDebug` in 44 seconds after core tests completed in
1 minute 7 seconds. Both invocations used nice level 15, --no-daemon, --max-workers=1, a 768 MB
Gradle heap and one active CPU. Memory and uptime were checked before each invocation; builds
were serial. The other worker's emulator and /work/jumpscan-app copy were not used. Commands:

```sh
cd /work/jumpscan-side
JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=1 nice -n 15 ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx768m -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8" :core:test
JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=1 nice -n 15 ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx768m -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8" :app:assembleDebug
```

The APK remains in /work/jumpscan-side/app/build/outputs/apk/debug/app-debug.apk. Build logs,
the APK SHA256 and machine-readable checks remain under logs/ml/side-*; research artifacts and
private landmarks are not committed.

## Form tracker — 18 November 2026

The core now has `FormAnalyzer.analyze(frames, exercise, side, aspect, view)` for `squat` and
`single_leg_squat`, plus `FormMetrics.toJson()` and the `:core:formBatch` JSON-lines task. This is
core-only work: no screens, upload path, new dependency, angle calibration or learned model was added.
`side` is null for two-leg squats and the anatomical `L` or `R` for single-leg squats. `view` reuses
`JumpAnalyzer.View`; output records the resolved front/side view. The exact proposed JSON field list
is in `/root/jumpscan/logs/form-fields.md` for the orchestrator; SPEC.md was not edited.

### What is measured

The tracker reuses the existing median-of-three coordinate smoothing, aspect correction, geometric
view detection, angle, FPPA sign and visibility constants from JumpAnalyzer. Those helpers are now
internal rather than private; jump behavior is unchanged. A side squat measures the leg with the
most visible hip/knee/ankle triples (left on a tie); it does not need heels/toes. A single-leg squat
always measures the requested side. The opposite knee and asymmetry stay null. Front single-leg
FPPA needs both hips to establish the midline. Every one-leg measurement adds `single_leg_view`.

A candidate is a local down-and-up cycle of smoothed image hip height, with a downward excursion
of at least 5% of median projected thigh-plus-shank length. Local tops are tracked independently,
rather than requiring every repetition to reach the tallest posture of the whole set. Return within
2.5% of the local top bounds the ascent. A visible rise of at least 5% followed by a new descent of
at least 5% can also bound the preceding ascent at its local top. Small hip motion below this gate
is treated as sway. This assumes a fixed camera; camera motion is not independently estimated.

A complete cycle requires **45 degrees minimum depth**. Depth is the mean of the independently
measured peak flexions of the available legs, or the measured leg's peak when only one is observed.
This threshold separates deliberate bends from small movements and the approximately 10–17 degree
standing bias in prior jump validation. It is not a prescribed adequate squat depth or a clinical
threshold. Fully bounded shallower candidates are `partial`, with `below_min_depth`. Front flexion
uses world geometry; side flexion uses the aspect-corrected image plane. These remain estimates from
a single-camera pose model, with the angle limitations documented above and in docs/validacia.md.

FPPA for both measured legs is sampled at the same frame of greatest mean knee flexion; positive
means medial, matching jumps. Each leg's peak flexion may occur at a different frame. Asymmetry is
`abs(peak_l - peak_r) / max(peak_l, peak_r) * 100`, and zero for two straight legs. The timing bottom
is the first maximum image hip y; it need not be the deepest knee-flexion frame. Descent and ascent
span the observed top-zone boundary, hip bottom and observed return/local top. A flat bottom counts
once; its hold belongs to ascent from the first maximum. With noise, the selected maximum can lie
anywhere in the hold. These times do not establish precise physical movement onset or cessation.
The side-only hip-below-knee flag means image hip y exceeded measured knee y at any cycle frame;
it is not a calibrated assessment of pelvic anatomy or competition squat depth.

Set medians include complete repetitions only; even-sized medians average the middle pair. Empty
medians are null. `worst_repetition` explicitly means the shallowest complete cycle, earliest on a
tie; it is a depth ordering, not a composite technique or injury score. Partial and unclear counts
are separate. `hip_below_knee_count` is null in front view and counts complete cycles in side view.

### Refusal and uncertainty

Each measured joint must have finite required coordinates and visibility >=0.5. Front flexion also
requires visible, finite world joints. The analyzer throws with fewer than ten usable frames,
invalid exercise/side/aspect, malformed landmark shape or non-increasing timestamps. Required-joint
visibility is averaged across all input frames, rejected frames included; front measured joints use
the lower of image/world visibility. Mean quality <0.7 or any rejected frame adds `low_visibility`.

Rejected frames and timestamp gaps above min(1.5 median intervals, 150 ms) split observations;
smoothing never crosses them. Open boundaries (`incomplete_start`, `incomplete_end`), interruptions
(`tracking_gap`), either phase shorter than 150 ms (`too_fast`), or zero-length segments
(`degenerate_geometry`) produce `unclear` observations and `unclear_reps`. An initial mean flexion
above 30 degrees conservatively marks an open beginning. No per-repetition measurements or bottom
time are reported for unclear observations. The unclear count is the number of unresolved
observations, not an inferred number of squats hidden by a gap: one interrupted squat can create
two unclear halves, while an unobserved interval can hide several squats. An otherwise unrepresented
gap gets an explicit unclear observation covering the clip. A batch refusal has status `error` and
metrics null; it is never scored as a successful zero count.

### Synthetic checks and development history

The new synthetic tests prescribe image FPPA independently of world knee flexion and generate
planted-ankle squat series. They check repetition count, independently known peak angles and
asymmetry, a 44/35.2 degree partial cycle, a 400 ms bottom pause, small Gaussian landmark noise,
isolated landmark spikes, both single-leg sides, positive/negative FPPA, side image/aspect behavior,
automatic profile detection, true/false hip-below-knee flags, null fields, medians, incomplete
boundaries, visibility/timestamp gaps, slow standing-height drift, malformed input, missing world
observations, degenerate geometry, insufficient visible frames, still-pose behavior, and equivalent
plain/compressed batch tracks with explicit malformed-file refusal.

The initial global-top design passed synthetic checks but returned **0 complete cycles on both real
inputs**, versus visual counts 4 and 9. Those failed predictions are retained in
`logs/form/initial-results.jsonl`. After seeing that failure, both clips were designated development
checks. The correction tracks each local top using the same numerical thresholds, with a new
synthetic drift guard. Therefore the final results on those two clips are **in-sample development
checks**, not held-out accuracy. No numerical threshold was selected by searching their counts.
The protocol, initial/final source hashes, annotation notes, rendered sheets, scripts and full JSON
outputs remain in uncommitted `logs/form/`; no private landmarks were copied into the repository.

### Visual repetition reference and available data

The stock fixture `squat_jumps_one_person.csv.gz` has 300 frames, 0–9976 ms, at 30000/1001 fps,
400x720 crop of the man in Pexels video 5034571. Before looking at analyzer outputs, Visual inspection
counted chronological landmark skeletons rendered every third frame (about 100 ms). Four fully
bounded cycles were labelled, approximately frames 43–86, 100–145, 159–199 and 211–250, with bottom
frames approximately 63, 120, 174 and 228. The file starts crouched and its last return is clipped;
those two boundary motions were labelled incomplete. This is a visual annotation, **not an
independent human rater** and not a reference for exact angles, depth threshold or phase times.

The already installed `/work/jumpscan-samples/pexels-5034571-hd.mp4` supplies another athlete in the
same recording. The woman was extracted at crop x=690,y=0,width=400,height=720 using the existing
heavy MediaPipe model and app detection/presence/tracking confidence 0.5; all 483 frames yielded
poses. Before scoring, full-video contact sheets at 2 fps and landmark sheets every third frame
were inspected. Nine fully bounded cycles plus two clipped boundary motions were labelled; bottom
frames approximately 63, 120, 177, 231, 285, 312, 343, 387 and 421. The dense sequence resolves the
fast separate cycles near 10 and 11 seconds. This shares scene and camera with the stock fixture.

After the local-top correction, an unscored temporal tail of the man was extracted at
x=350,y=0,width=400,height=720, source frames 300–482 (10010–16082 ms). Every second landmark frame
(about 67 ms) was visually inspected before scoring. Three fully bounded down/up cycles plus a final
clipped descent were labelled. Approximate exported-frame intervals: 20–58, 80–106 and 120–154;
bottoms approximately 34, 90 and 138. The second cycle rises into a visible jump, so this is a
**temporal stress check with shared development athlete/source**, not an independent pure-squat
validation. The tracker was frozen before scoring this segment and was not tuned to its result.

The kit inventory under `/work/jumpscan-data/commons-*` and `kaggle-*` contains **no retained squat
or lunge video/track**. Commons' staggered-squat-jump instruction video has only a contact sheet,
ffprobe and rejection metadata; SOURCE.txt explicitly says the source video was not retained.
Kaggle has only jump_in_place, jump_left, jump_right, looking_down and still_pose classes. Those
were not relabelled as squat tests. Other installed sample videos show a burpee or box jumps with
cropped bodies and were not substituted for pure-squat validation. No clips were downloaded and
no packages were installed. No independent squat/lunge test source is available on this kit.

### Results and practical limits

| Clip / role | Visual complete count | Detected complete | Detected partial | Detected unclear | Visual incomplete boundary motions |
|---|---:|---:|---:|---:|---:|
| Stock man, first 10 s — development | 4 | 5 | 0 | 1 | 2 |
| Pexels 5034571 woman, full source — development | 9 | 8 | 0 | 2 | 2 |
| Pexels 5034571 man, frames 300–482 — unscored temporal stress check | 3 | 3 | 0 | 1 | 1 |

All three resolve to front view, have no analyzer refusal, and return `unclear_reps`. The final
stock return is accepted by the counter's top tolerance while the stricter visual annotation leaves
it incomplete. The woman's fast sequence contains a missed cycle. Neither mismatch was tuned away.
The warning records open boundary observations; it does **not** independently identify every missed
interior cycle. Matching the count on one shared-source tail segment is insufficient to establish
reliable squat counting across athletes, cameras, views, loads or exercise speeds.

No independent real single-leg squat, side squat, lunge, or squat angle/FPPA/asymmetry/tempo reference
is installed. Those measurements have synthetic geometry checks only. The previously measured jump
angle errors are not a validation of squat angles. Independent squat/lunge counting and physical
metric accuracy remain blocked by data availability; no clinical or source-independent accuracy
claim is made.

Final core validation on `android-build-kit` in the isolated `/work/jumpscan-form` copy: **65 tests
passed, zero failures, errors or skips**, including ten new form tests, existing public benchmark
checks and all existing private-fixture checks. Private fixtures remained on the kit. All Gradle
invocations in this copy were serial, nice level 15, one worker, one active CPU and a 768 MB heap;
free memory and uptime were checked. The emulator and `/work/jumpscan-app` were not used. The final
analyzer and batch source SHA256 values match the freeze made before the tail test.

Reproduce the core tests and both batches on the kit:

```sh
cd /work/jumpscan-form
JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=1 nice -n 15 ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx768m -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8" :core:test
JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=1 nice -n 15 ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx768m -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8" :core:formBatch -Pin=/work/jumpscan-form/form-validation/input -Pout=/work/jumpscan-form/form-validation/development-results.jsonl
JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=1 nice -n 15 ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx768m -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8" :core:formBatch -Pin=/work/jumpscan-form/form-validation/heldout -Pout=/work/jumpscan-form/form-validation/heldout-results.jsonl
```

The development input directory contains exactly the repository stock gzip and the second-person
export; the temporal directory contains exactly the man-tail export. Scripts to regenerate those
public exports using the already installed model and environment are
`logs/form/extract_second_person.py` and `logs/form/extract_man_tail.py`; the model path is recorded
there. `logs/form/render_landmarks.py` regenerates the annotation sheets. Extraction produced
483/483 and 183/183 pose frames respectively, using source timestamps at 30000/1001 fps. Pexels
source/license provenance is retained in `/work/jumpscan-samples/SOURCE.txt` (Pexels video 5034571).
The batch accepts plain/gzipped app-format landmark CSVs and optional `-Pexercise=single_leg_squat`,
`-Pside=L` or `R`, and `-Pview=FRONT` or `SIDE`. It writes one JSON record per clip with full metrics
or an explicit refusal. Test XML, build logs, both final JSON outputs, initial failed outputs,
inventory and visual count notes are retained under `/root/jumpscan/logs/form/`.

## 2026-11-19: Catalogue and live form stream

The catalogue contains 20 exercises. Synthetic tracks exercise every new cycle with one complete,
one partial and one visibility-interrupted candidate, and every hold with 2,450 ms in position,
including 1,000 ms in band. The tests check cue and cycle events, per-repetition keys, gap handling,
batch/stream equality, and exact final JSON parity for both squat modes. The legacy squat calculation
still uses whole-set smoothing, visibility, frame cadence, and leg selection. Live previews re-evaluate
the prefix at 400 ms intervals and may revise their counts; the final summary is authoritative.

On `android-build-kit`, `cmp` found byte-identical JSON before and after for the three archived
public squat tracks and for all three private landmark tracks scored as squat, left single-leg squat,
and right single-leg squat. The private clips are jump fixtures, not single-leg squat accuracy references.
The stock and public squat hand-versus-engine complete counts remain 4/5, 9/8, and 3/3, as documented
above. The optional private jump tests also retain the reviewed 280/360/360 ms results. No private
landmarks are committed.

The following independent clips were hand-counted from ordered contact sheets before scoring.
MediaPipe heavy produced the landmark tracks on the kit; the numbers are counts of fully bounded
cycles, not a validation of the check angles or an accuracy estimate across athletes.

| Exercise | Public source and segment | Hand | Complete | Partial | Unclear | Limitation |
|---|---|---:|---:|---:|---:|---|
| `push_up` | Taco Fleur, Wikimedia Commons, [Interval Push-ups](https://commons.wikimedia.org/wiki/File:Interval_Push-ups.webm), CC BY-SA 4.0, 3–49 s | 6 | 5 | 0 | 3 | Profile; one cycle lost during intermittent tracking. |
| `overhead_press` | FitnessScape, Wikimedia Commons, [Shoulder press](https://commons.wikimedia.org/wiki/File:Shoulder_press_-_exercise_demonstration_video.webm), CC BY 3.0, full 6.9 s | 1 | 0 | 0 | 5 | Rear three-quarter view and legs cropped; starts and ends mid-cycle. |
| `pull_up` | FitnessScape, Wikimedia Commons, [Pull-ups](https://commons.wikimedia.org/wiki/File:Pull-ups_-_exercise_demonstration_video.webm), CC BY 3.0, full 6 s | 1 | 0 | 0 | 6 | Rear three-quarter view and feet cropped; another cycle ends at the cut. |
| `biceps_curl` | CDC, Wikimedia Commons, [Bicep Curls](https://commons.wikimedia.org/wiki/File:Muscle_Strengthening_at_Home_-_Bicep_Curls.webm), US public domain, 46.5–61 s | 2 | 0 | 0 | 5 | Frontal, cropped at the legs, with a third curl cut off. |

All 18 new exercises remain unvalidated for general real-world accuracy. The four results above do
not justify tightening thresholds to fit individual clips. Burpees are omitted because floor and upright
phases need separate tracking and occlusion remains unresolved. The kit directory
`/work/jumpscan-data/form/` contains the clips, tracks, annotations and JSON outputs. REHAB24-6
(Zenodo 13305826) has labelled repetitions, but the data is CC BY-NC 4.0 and several movements use
distinct protocols; it was not used to validate this product. Countix/RepCount include YouTube-sourced
clips without a verified reusable licence per clip. No usable Kaggle credentials were found in the
kit builder environment, and no token was printed or copied.
