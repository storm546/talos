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
