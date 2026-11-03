# Phase reports (actual outcomes)

Test command for all phases: `KOTLINC=… scripts/build-and-test.sh` → final **50 executed, 50 passed, 0 failed** (`docs/TEST_RESULTS.txt`).
Per-area counts: analyzer 6, proposals 4, patch 2, diff 1, matrix 1, geometry 1, summary 1, engine 2, workflow 11, verify 6, spec-scenario 3, ui 12.
During development the suite ran 43 tests with 3 failures (analyzer treated string contents as code; patch relocation too strict on import context; a test counted scrollbar buttons). All three were fixed; no tests were removed or weakened.

## Phase 1 — Discovery and baseline
- Done: toolchain probe (JDK 21 present; Kotlin 2.0.21 compiler fetched from GitHub and works; Maven/Google/Gradle hosts unreachable → no Gradle, IntelliJ SDK or Android SDK builds).
- Existing repository: **none provided** → no existing UI, architecture or baseline tests to inspect. Project created from scratch at the user's explicit request.
- Gap analysis: everything in the prompt was missing; plan = dependency-free core + Swing UI (testable here) + uncompiled plugin/Android parts, labelled.
- Criteria: existing structure understood ✗ (none existed); conventions identified ✗ (n/a); baseline limitations documented ✓; minimal-change plan ✓ (n/a: greenfield).
- Blockers: no existing repo; no Android/IntelliJ/Gradle toolchain.

## Phase 2 — Workflow and state
- Files: `Workflow.kt`, `WorkbenchPanel.kt`, `WorkbenchStoreHandle.kt`, tests `WorkflowTests.kt`, `UiTests.kt`.
- Tests (all executed, pass): transitions, independence, approve ≠ apply ≠ verify, reset, listeners, label text `Approved · Awaiting verification`, no Back-to-Repair, indicator display-only.
- Criteria: four stages ✓; styling preserved ✗ (n/a, new UI); approval/verification separate ✓; independent issues ✓; reset ✓ (does not revert files); no Back to Repair ✓; label ✓.

## Phase 3 — Proposals and approval
- Files: `Proposals.kt`, `SourceAnalyzer.kt` (finding locations), UI Repair card.
- Tests: proposals distinct per finding; generation never writes; patch applies preserving unrelated edits; stale code → failure with file byte-identical; failed patch → FAILED, never resolved; reject/approve flows; UI shows id, explanation, evidence, location, proposal, rationale, diff.
- Criteria: all ✓ within the implemented scope (4 finding kinds). Proposals for placeholder content need human input (flagged).

## Phase 4 — Analysis and runtime verification
- Implemented: 4 heuristic rules; evidence classes; matrix engine abstraction; `ResultsFileEngine` import; instrumented test **written only**.
- Not run: instrumented test, any real geometry/behavioural check. Not implemented: clipping/parent constraints, semantics beyond contentDescription.
- Criteria: locations/explanations ✓; static vs runtime evidence distinguished ✓; real behavioural tests used ✗ (not runnable here); unsupported capabilities documented ✓; unexecuted not reported as passed ✓ (tested); evidence explains outcomes ✓.

## Phase 5 — Batch verification
- Tests: 28 unique cases, 7 probes × 4 scales, probe geometry, verdict logic, results-file import with missing cases NOT_RUN, partial = incomplete with exact counts (17 passed / 3 inconclusive / 8 not run), demo guard, history preserved, one finding's result does not resolve another.
- Demo findings CW-001 (1.5×, right edge) and CW-002 (2.0×, corners) are labelled DEMO.
- Criteria: all ✓ at the logic/UI level. **The matrix has never been executed against a real device.**

## Phase 6 — Integration/regression
- Spec scenarios: A ✓ (UI), B ✓ only with a labelled demo fixture, C ✓ (fixture), D ✓, E ✓ (unavailable + throwing engine), F ✓ (28 cases; incomplete flagged). B/C/F results are fixture-based, not real.
- Project build via Gradle ✗ not possible; kotlinc build ✓. UI consistency vs an existing UI ✗ (none). Window-size checks ✗ (one 1000×640 render only).

## Phase 7 — Documentation
README, ARCHITECTURE, KNOWN_LIMITATIONS, this file, TEST_RESULTS, demo screenshots (`docs/screenshots`, demo data).
