# Licence audit (draft, 2026-11-19)

Scope: every runtime dependency and bundled asset of JumpScan, checked against releasing the product
under the GNU GPL version 3 (`LICENSE`; `README.md` says "GPL-3.0-or-later"). This is an engineering
inventory, not legal advice. Where the answer depends on facts only the author knows, or on a legal
reading, it is marked **QUESTION** and left open.

Sources used: `server/requirements.txt` and the package metadata (`License-Expression` / `License`
fields) of the installed virtual environment `/root/jumpscan/server/.venv`; `build.gradle`,
`settings.gradle`, `app/build.gradle`, `core/build.gradle`; `app/src/main/assets/`; resource and
test-fixture READMEs; headers of `server/coaching.py` and `server/weight_trend/`; `docs/validacia.md`
and `core/VALIDATION.md` for datasets. There is no Gradle version catalogue (`gradle/libs.versions.toml`
does not exist); all versions are written inline in the build files.

Legend: **OK** = permissive or GPL-compatible licence as commonly understood (FSF list);
**CHECK** = probably fine but not verified from the primary source; **QUESTION** = the author must be
able to answer.

## 1. Project licence

| Item | Finding | Status |
|---|---|---|
| `LICENSE` | Full text of GNU GPL version 3 (29 June 2007) | OK |
| `README.md` "Licence" section | States GPL-3.0-or-later and lists third-party licences | OK |
| Source file headers | No per-file licence headers | CHECK: optional; GPL recommends a short notice per file, not required |
| Appendix 4 of the tournament rules | Releases the work under GPL | QUESTION: "or later" vs "version 3 only" should match what the author signs |

## 2. Server runtime (Python, shipped in the Docker image and on the live server)

Direct dependencies from `server/requirements.txt`, then their installed transitive dependencies.

| Package | Version | Licence | Runtime or test | Status |
|---|---|---|---|---|
| fastapi | 0.163.1 | MIT | runtime | OK |
| uvicorn | 0.54.0 | BSD-3-Clause | runtime | OK |
| starlette | 1.10.0 | BSD-3-Clause | runtime (via fastapi) | OK |
| pydantic | 2.14.3 | MIT | runtime | OK |
| pydantic-core | 2.50.4 | MIT | runtime | OK |
| anyio | 4.16.0 | MIT | runtime | OK |
| typing-extensions | 4.16.0 | PSF-2.0 | runtime | OK |
| annotated-types | 0.8.0 | MIT | runtime | OK |
| annotated-doc | 0.0.6 | MIT | runtime | OK |
| typing-inspection | 0.4.5 | MIT | runtime | OK |
| h11 | 0.16.0 | MIT | runtime (via uvicorn) | OK |
| click | 8.5.1 | BSD-3-Clause | runtime (via uvicorn) | OK |
| idna | 3.20 | BSD-3-Clause | runtime/test | OK |
| opentelemetry-api | 1.45.2 | Apache-2.0 | installed in the venv; which package requires it was not checked | CHECK |
| httpx | 0.28.1 | BSD-3-Clause | test client | OK |
| httpcore | 1.0.9 | BSD-3-Clause | test | OK |
| certifi | 2026.10.23 | MPL-2.0 | test (via httpx) | OK (MPL-2.0 is GPL-compatible by its secondary-licence clause) |
| pytest | 9.2.0 | MIT | test | OK |
| pluggy | 1.7.0 | MIT | test | OK |
| iniconfig | 2.3.1 | MIT | test | OK |
| packaging | 26.3 | Apache-2.0 OR BSD-2-Clause | test | OK |
| pygments | 2.21.0 | BSD-2-Clause | test | OK |
| Python standard library (sqlite3, hashlib.scrypt, secrets) | 3.13 | PSF-2.0 | runtime | OK |

Other server-side items:

| Item | Finding | Status |
|---|---|---|
| Docker base image `python:3.13-slim` | Debian packages under many licences | CHECK: only matters if the image itself is distributed; the repository ships only the Dockerfile |
| Swagger UI at `/docs`, ReDoc at `/redoc` | Loaded at runtime from jsDelivr (swagger-ui-dist, Apache-2.0; ReDoc, MIT) and Google Fonts; not bundled | OK |
| Dashboard fonts (`server/static/dashboard.css`) | `system-ui, -apple-system, "Segoe UI", sans-serif`; no font files bundled | OK |
| Dashboard icons | Favicon is an inline SVG drawn for the project; overview-card icons are Unicode characters (✓ ◷ ↗ ≋) rendered by the viewer's system font; no icon library | OK |
| Open Food Facts product data (fetched per barcode, not bundled) | Saved as club foods with `source` "Open Food Facts" and the product URL | ODbL 1.0 (database), DbCL 1.0 (contents) | OK: attribution is stored with each scanned food and shown in the app; the club database is not redistributed. CHECK: if foods are ever exported or shared beyond the club, the ODbL share-alike terms apply to that extract |
| `server/data/foods.csv` | Subset of about 330 foods from USDA FoodData Central, Foundation Foods (2025-12-18) and SR Legacy (2018-04); header states CC0 1.0. Bulgarian names are the project's own | OK (CC0; USDA asks for, but does not require, citation; the file header already cites it) |

## 3. Android app and core (Kotlin, shipped in the APK)

| Dependency | Version | Licence | Shipped | Status |
|---|---|---|---|---|
| Kotlin standard library | 2.0.21 | Apache-2.0 | yes | OK |
| com.google.mediapipe:tasks-vision | 1.2.3 | Apache-2.0 | yes | OK |
| androidx.activity:activity | 1.9.3 | Apache-2.0 | yes | OK |
| androidx.camera:camera-core, camera-camera2, camera-lifecycle | 1.4.2 | Apache-2.0 | yes | OK |
| Transitive libraries of the above (AndroidX, protobuf, Guava and similar) | not enumerated | expected Apache-2.0 / BSD | yes | CHECK: run `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` on the build kit and confirm each |
| com.google.mlkit:barcode-scanning (bundled, offline model) | 17.3.0 | ML Kit Terms of Service (Google APIs Terms); not an open-source licence | yes | CHECK: proprietary terms, not GPL-compatible in the FSF sense; the model runs on the device and sends no images. Confirm with the organisers that a GPL entry may link it, or replace it (e.g. ZXing, Apache-2.0) |
| Android Gradle Plugin | 8.7.3 | Apache-2.0 | build only | OK |
| Gradle wrapper (`gradle/wrapper/gradle-wrapper.jar`, Gradle 8.10.2) | — | Apache-2.0 | committed to the repo, not in the APK | OK |
| Loop Habit Tracker, uhabits-core `Score.compute` (habit strength formula, ported to `core/.../Consistency.kt` and `server/week_api.py`, SPEC 10.3) | upstream source in `/root/research/competitors/src/uhabits` | GPL-3.0-or-later, Copyright (C) 2016-2025 Álinson Santos Xavier | yes (as ported code) | OK: same licence as the project; attribution in both file headers |
| JUnit Jupiter | 5.11.3 | EPL-2.0 | test only, never shipped | OK for this use (EPL-2.0 is not GPL-compatible for combined distribution, but test-only use does not combine it with the product) |

Note on Apache-2.0: the FSF considers Apache-2.0 compatible with GPL version 3 (not with GPL version 2).
The NOTICE-file obligation of Apache-2.0 applies when redistributing; CHECK whether the APK or an
"About" screen should carry the MediaPipe and AndroidX notices.

## 4. Bundled models and weights

| File | Size | Origin | Licence | Status |
|---|---|---|---|---|
| `app/src/main/assets/pose_landmarker_heavy.task` | 30 664 242 bytes | Google MediaPipe Pose Landmarker (heavy) | Apache-2.0 per `README.md` | CHECK: confirm against the official MediaPipe model card and keep a copy of it; also read the model card's intended-use and limitations section |
| `app/src/main/assets/pose_landmarker_lite.task` | 5 777 746 bytes | Google MediaPipe Pose Landmarker (lite), used by the live form tracker | Apache-2.0 per `README.md` | CHECK: as above |
| `core/src/main/resources/jumpscan/core/airborne-v1.txt` | 336 658 bytes | The project's own temporal CNN weights; header says trained on Twente (CC BY 4.0) and the Kaggle gesture dataset (CC BY 4.0) | project's own, GPL | OK, keep the attribution. QUESTION (minor): whether trained weights are "adapted material" under CC BY 4.0 is not settled; attribution is given in the file header and in `docs/validacia.md` either way |
| `core/src/main/resources/form_exercises.json` | 17 087 bytes | Project's own exercise catalogue | GPL | OK |

## 5. Datasets and clips (used for validation; what is in the repository)

| Dataset | Licence | In the repository? | Status |
|---|---|---|---|
| University of Twente jump dataset, Zenodo 6644593 (video) and 7422031 (processed), Huawei Wang | CC BY 4.0 | Derived landmark fixtures only (`core/src/test/resources/learned/twente-*.txt`), with attribution in the README there | OK |
| Gesture Video Dataset for Jump Classification, Kaggle, daffasesarabbani | CC BY 4.0 | One derived landmark fixture (`learned/webcam.txt`), attributed | OK |
| Pexels video 5034571 | Pexels License (not a Creative Commons licence; free use, restrictions on redistributing unaltered copies) | Derived landmarks only: `core/src/test/resources/squat_jumps_one_person.csv.gz`, attributed in `core/src/test/resources/README.md` | CHECK: derived numeric landmarks are very likely fine, but the Pexels License is not GPL; confirm the author is comfortable shipping this fixture in a GPL repository |
| Wikimedia Commons clips for the form tracker (push-ups by Taco Fleur, CC BY-SA 4.0; shoulder press and pull-ups by FitnessScape, CC BY 3.0; CDC biceps curls, US public domain) | as listed | No: clips, tracks and annotations stay on the build kit (`core/VALIDATION.md`) | OK. If derived landmarks of the CC BY-SA clip are ever committed, share-alike applies to that file |
| 25 public YouTube clips (`docs/validacia.md` 4.11) | standard YouTube licence, copyright of the uploaders | No ("Самите видеа не се разпространяват") | OK as long as neither the clips nor frames from them are published; CHECK that no screenshot from them appears in slides or the flyer |
| REHAB24-6 (Zenodo 13305826) | CC BY-NC 4.0 | Not used (`core/VALIDATION.md`) | OK |
| USDA FoodData Central | CC0 1.0 | Yes, `server/data/foods.csv` | OK |
| Owner's own jump clips (`docs/validacia.md` 5) | own | No | OK |

## 6. Documents and images

| Item | Finding | Status |
|---|---|---|
| `docs/img/*.png` | Screenshots of the project's own app and dashboard with synthetic demo data | OK |
| `docs/img/arhitektura.svg`, `docs/img/qr-jumpscan.svg` | Drawn/generated for the project | OK |
| Fonts in the PDFs | Liberation Serif/Sans from the build machine (SIL OFL 1.1), embedded as subsets by Chrome | OK (OFL allows embedding in documents) |
| `docs/build.cjs` tools: marked (MIT), Playwright (Apache-2.0), Chrome | Build-time only, not shipped | OK |
| Scientific papers cited in `docs/dokumentacia.md` | Cited by DOI only, no text or figures copied | OK |

## 7. Nutrition method: `server/coaching.py` and `server/weight_trend/`

**QUESTION — the author must be able to answer this himself. No legal conclusion is made here.**

What the repository says:

- `server/coaching.py` header: "Original Python reimplementation of a reverse-engineered numeric method;
  exponential weight trend + 14-day energy balance."
- `server/weight_trend/__init__.py` header: "Numeric weight-trend model reconstructed from measured
  behaviour and reimplemented in original Python."
- `SPEC.md` 6.3 repeats "reimplementation of a reverse-engineered numeric method".
- `server/test_training_nutrition.py` asserts that the header statement is present and that a list of
  product-related strings is absent from the code.
- The method is that of a commercial nutrition app. The owner's stated position (project notes, not
  the repository) is that this is a clean-room reimplementation: his knowledge plus AI tools used to
  rebuild the behaviour, with no original third-party code in the finished product.

Questions a jury or the organisers could ask, which only the author can answer:

1. What exactly was the reference: published descriptions, observed inputs and outputs of the app,
   or its program files? Was any decompiled code, constant table or text copied, even temporarily?
2. Do the original app's terms of use say anything about reverse engineering, and has the author read
   them?
3. Can the author explain every rule in `coaching.py` and the weight-trend filter (state, smoothing,
   horizon blending) at the whiteboard, as with the jump formulas?
4. How does this module fit the Appendix 4 statement that the code is the author's own work, and the
   release of the whole product under GPL?
5. If the answer to any of these is uncertain, which options does the owner prefer (for example: keep
   the module and explain it, describe it openly in the documentation, or leave it out of the
   competition build)? This is the owner's decision.

## 8. Summary

- No dependency found with a licence that blocks GPL-3.0 release. All shipped libraries are MIT, BSD,
  PSF, Apache-2.0 or MPL-2.0; EPL-2.0 (JUnit) is test-only.
- To verify: the MediaPipe model card licence, the Android transitive dependency list, Apache-2.0
  NOTICE handling in the APK, the Pexels-derived test fixture, and which package pulls in
  opentelemetry-api.
- Open for the author: the nutrition method's origin (section 7), and "GPL-3.0-or-later" versus the
  wording he signs in Appendix 4.
# Interface fonts

The Android app and dashboard bundle Space Grotesk (regular and bold) and JetBrains Mono (regular), licensed under the SIL Open Font License 1.1. The complete copyright notices and licenses accompany the Android assets in `app/src/main/assets/licenses/` and the self-hosted web fonts in `server/static/fonts/`.

