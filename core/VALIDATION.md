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

## Learned detector — 18 November 2026

The learned MediaPipe detector and rule cross-check are implemented, but this revision does **not**
meet the requested ship gate. The full core suite fails on the existing stock-squat and private-clip
regressions described below. The threshold, training recipe and evaluation boundary convention were
not changed to conceal those failures. No application was installed, deployed or published.

The final fit uses seed 0 of the frozen `MediaPipe + training negatives` recipe in
`logs/ml/PROTOCOL2.md`: 600 AdamW updates, batch 96, learning rate 0.002, weight decay 0.001,
masked binary cross-entropy, the original augmentation, three 32-channel kernel-five convolutions
with dilations 1/2/4, ReLU, training dropout 0.2 and a final pointwise convolution. Training includes
all nine Twente subjects (182 jump cuts and 218 force-negative windows) and 128 usable webcam
training-half negatives. The audited 70 training-half negatives whose landmark contents duplicate
webcam test files remain excluded. Webcam jump-class training halves are unused. The test halves
are unchanged; no threshold, architecture, seed or training update was selected from their results.
Final training loss is 0.00709602702409029. The training script imports the exact frozen `fit` function.

Training ran on openclaw with system `python3`, torch CPU and one torch thread:

```sh
systemd-run --scope -q -p MemoryMax=3G -p MemorySwapMax=512M nice -n 15 python3 -u logs/ml/train_final.py
python3 logs/ml/export_final.py
```

The uncommitted scripts and `final_model.json` remain in ignored `logs/ml/`. The text resource
`core/src/main/resources/jumpscan/core/airborne-v1.txt` is **336,658 bytes**. It names Twente
(Huawei Wang, CC BY 4.0) and the Kaggle gesture dataset (CC BY 4.0). It contains a format/channel/
threshold/fps header followed by each layer's shape, dilation, padding, ReLU flag, weights and biases.
No JSON library or other dependency was added. Source attribution: Twente RGB data by Huawei Wang,
https://zenodo.org/records/6644593, and the gesture dataset published by daffasesarabbani,
https://www.kaggle.com/datasets/daffasesarabbani/gesture-video-dataset-for-jump-classification-1.
Both declared data licences are CC BY 4.0, https://creativecommons.org/licenses/by/4.0/.
Only derived weights and the compact public landmark fixtures are included, not source videos.

- Final model JSON SHA256: `4d3ff0b7eecde0798634893c47928850104000bb5f4dc0a410facc6ec3ee16df`.
- Text weights SHA256: `8971650c55f04b947af7f8ac6c4e52f53c8dd39713aab5508274bbc3654b2f76`.

Kotlin uses Double accumulation, the same 37 features and sigmoid, and the longest strict-above-0.5
run of at least two samples. It uses a fixed 33 fps grid for every capture rate; nearest observations
within half a step supply positions and visibility, with ties assigned once to the even grid index.
Missing positions are interpolated and missing visibility/presence is zero. Predicted crossings snap
forward to real frame timestamps. The rule detector is retained unchanged as a cross-check: both
edges within an inclusive 100 ms suppress the extra warning; otherwise a learned proposal or a
rule-only fallback carries `uncertain_timing`. An unusable learned input falls back silently to rules.
Manual edges and the JSON contract retain their existing behavior.

All Kotlin validation ran as builder in the separate `/work/jumpscan-learned` copy on
`android-build-kit`, with one Gradle build at a time, `--max-workers=1` and nice level 15. `uptime`
was checked during work. Existing emulator, app-copy and governed RTMPose jobs were left running.

The committed public-data JUnit fixtures contain compact selected landmark rows and independent
`reference_forward.py` Double probabilities, with coordinates/aspect first rounded to Kotlin Float.
They contain no owner data. The webcam port fixture is a training-half jump clip, resampled to 33 fps.

| Port fixture | Grid samples | Maximum absolute probability error |
|---|---:|---:|
| Twente Subj04 front jump01 | 87 | 0 |
| Twente Subj04 side jump01 | 87 | 3.33e-16 |
| Webcam jump_in_place/137 | 70 | 0 |

The eight learned-detector tests cover these probabilities to 1e-6, 60/120 fps downsampling and real
frame snapping, missing-cell interpolation, invalid input, strict threshold/run/tie/crossing behavior,
manual-window agreement, warning cases at 100/101 ms, and silent invalid-leg fallback. The retained
synthetic rule-detector tests now explicitly call the internal rules-only entry point; they continue
to check the cross-check independently. The batch test checks that its timing fields agree with the
public analyzer. Manual-edge tests still exercise the public entry point.

**Nine-subject Twente in-training port/consistency check, not an accuracy estimate.** Every subject
in this table is now in the final training set. The held-out accuracy estimate remains REPORT2's
nine-subject leave-one-subject-out result: mean 90.8/91 front and 86.8/91 side right jumps for the
chosen variant, versus 71/91 and 60/91 for rules. Those means are over five seeds and are not final-fit
measurements. The following current-fit numbers must not replace that estimate.

| Detector, in-training consistency only | View | Right jump | Spurious | Missed, including errors | MAE ms | Within one frame | Errors |
|---|---|---:|---:|---:|---:|---:|---:|
| Learned proposal + rule cross-check | Front | 88/91 | 1 | 2 | 27.85 | 66 | 0 |
| Rules on the same files | Front | 71/91 | 0 | 20 | 32.08 | 45 | 0 |
| Learned proposal + rule cross-check | Side | 86/91 | 0 | 5 | 17.83 | 68 | 5 |
| Rules on the same files | Side | 60/91 | 0 | 31 | 87.85 | 10 | 2 |

Matching and conditional timing use the inclusive 300 ms edge rule at the start of this document.
The learned-proposal batch has 19 front and 35 side `uncertain_timing` results. Four side errors have
no usable angle in the selected landing window; one has fewer than ten angle frames. Those errors
remain missed results. Metadata CSVs are excluded by joining the batch to all 182 label rows.

**Webcam TEST halves, held out from final fitting.** Each class has 100 test files selected by the
unchanged SHA256-basename split. Counts use the same files for learned proposals and rules. Raw
flights are what the core actually reports. The additional 100–1000 ms columns apply REPORT2's
class-duration convention only for comparison; that filter is not applied to the core's output.

| Class | Learned raw flights /100 | Rules raw flights /100 | Learned 100–1000 ms /100 | Rules 100–1000 ms /100 | Analyzer errors, each detector |
|---|---:|---:|---:|---:|---:|
| looking_down, false alarms | 32 | 7 | 11 | 7 | 9 |
| still_pose, false alarms | 25 | 1 | 7 | 1 | 0 |
| jump_in_place, class recall | 88 | 64 | 88 | 64 | 9 |
| jump_left, class recall | 81 | 34 | 81 | 34 | 12 |
| jump_right, class recall | 92 | 49 | 91 | 49 | 7 |

Unusable tracks count against positive recall and are exposed separately from negative successes.
The learned-proposal warning counts are 30/25/28/50/50 in table order. These are supplied class
labels, not reviewed airborne intervals. The duplicate-content caveat in REPORT2 still applies:
clip separation does not establish person/session independence, and test files include duplicates.
REPORT2 used 30 fps webcam preprocessing and its class-duration filter; these final-core raw counts
also include fixed-33-fps resampling, real-frame snapping and rule fallback. They are not the same
measurement as a REPORT2 threshold-curve cell.

Private fixtures remain only on the kit. The rule outputs reproduce 280/360/360 ms; the learned
outputs below are also independently reproduced by the standard-library Python reference on the kit.

| Private clip | Prior rule flight ms | Learned flight ms | Change ms | Within one nominal frame of prior output |
|---|---:|---:|---:|---|
| jump1 | 280 | 280 | 0 | Yes |
| jump2 | 360 | 320 | -40 | Yes, about 40.1 ms per frame |
| jump3 | 360 | 320 | -40 | Yes, about 40.3 ms per frame |

All three learned results have no timing warning because their edges agree with rules within 100 ms.
The existing exact-output private test fails on jump2. Jump3 also fails its independent reviewed
last-ground-to-contact duration tolerance: the labelled interval is 440.611 ms and the new output
is 320 ms, beyond the existing two-frame allowance. These private assertions were not relaxed.

The stock planted-foot squat reports a **100 ms false flight**, snapped to 9876–9976 ms, with
`uncertain_timing`; rules report no flight. Python reproduces interpolated edges
9869.792501–9969.696970 ms and maximum probability 0.946819. The run ends at the clip boundary,
without an observed down-crossing. The evaluation substitutes the final grid timestamp at an
unbracketed edge, and the Kotlin port preserves that convention. Requiring an observed landing or
adding a duration/rule veto would change the specified detector behavior; no such gate was selected
using these results. The existing no-flight squat assertion remains failing. This blocks shipping.

`BenchmarkTest` now uses all nine Twente subjects as an in-training consistency check and only the
webcam test halves. Margins are two clips for detection/false alarms, about 2–3 ms for learned MAE,
and one additional error (zero front errors); positive recall and within-frame lower bounds prevent
an empty-result implementation from passing. The unfavorable negative rates are bounded honestly,
not described as improvements. The rule baseline is checked on the same inputs.

Reproduction on the kit (run the Gradle invocations sequentially):

```sh
cd /work/jumpscan-learned
nice -n 15 ./gradlew --no-daemon --max-workers=1 :core:test
nice -n 15 ./gradlew --no-daemon --max-workers=1 :core:batch :app:assembleDebug \
  -Pin=/work/jumpscan-data/twente-jumps9 -Pout=/work/jumpscan-learned/final-twente.csv
nice -n 15 python3 score_final.py
nice -n 15 python3 reference_kit.py
```

The scoring and independent-reference scripts are retained in ignored `logs/ml/` and copied to the
kit root alongside their required model/reference files. Kit logs are `learned-final-tests.log`,
`learned-batch-app.log`, `final-twente.csv`, `final-twente-score.json` and `reference-kit.txt`.
The Android debug assembly and batch complete successfully (`BUILD SUCCESSFUL in 54s`). The final
`:core:test` run executes 51 tests: 49 pass, 2 fail, 0 skip. Both failures are in `RealVideoTest`;
all 8 learned-port tests, 32 rule tests, 6 manual-edge tests, the batch test and both measured-data
regression tests pass. The private rule-baseline assertions reproduce exactly 280/360/360 ms.
The existing private learned-output and squat assertions remain failing, so the requested all-core
passing gate and no-flight squat gate are not met. No passing subset is substituted for the full suite.

The optional missing-dataset check also passes: running only `BenchmarkTest` with
`JUMPSCAN_TWENTE_DIR=/work/jumpscan-learned/absent-twente` and
`JUMPSCAN_KAGGLE_DIR=/work/jumpscan-learned/absent-webcam` produces exactly two skipped tests,
zero failures. The complete prior run's XML, batch results and logs are retained in ignored
`logs/ml/kit-final/`; the optional skip run is stored separately. `git diff --check` passes.
