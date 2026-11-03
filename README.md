# Compose Workbench

Inspect → Diagnose → Repair → Verify for Jetpack Compose touch-target and accessibility issues.

> **Provenance.** This is a **new** project created from `Compose_Workbench_Implementation_Prompt.md`. No pre-existing
> Compose Workbench repository was available, so nothing was "preserved" from an earlier UI: the Swing UI here is new
> and uses the default Look & Feel. If you have an existing project, port `workbench-core` (UI-independent) into it and
> bind your existing screens to `WorkflowStore`.

## What is real, what is not

| Area | Status |
|---|---|
| Static analysis of Compose source (4 rules) | **Real**, heuristic only. Compiled and tested. |
| Finding-specific proposals, unified diff, safe patch application | **Real**. Compiled and tested (temp files). |
| Workflow/state model (approval, patch, verification independent) | **Real**. Compiled and tested. |
| 28-case matrix (7 probes × 4 font scales), summaries, history | **Real**. Compiled and tested. |
| Swing UI (4 stages) | **Real**, tested headless via component tree + rendered to PNG. Not exercised in a visible window. |
| Instrumented touch matrix (`sample-app/.../TouchTargetMatrixTest.kt`) | **Written, NOT compiled, NOT run** (no Android SDK/device in the authoring sandbox). |
| IntelliJ/Android Studio plugin shell (`workbench-plugin`) | **Written, NOT compiled** (IntelliJ SDK not downloadable). |
| Gradle build files | **Written, NOT run** (Maven/Gradle hosts unreachable). |
| Demo findings CW-001 / CW-002 and `DemoFixtureEngine` | **Illustrative demo data**, labelled in UI and tests. Never real evidence. |
| Runtime geometry (`boundsInRoot`, `touchBoundsInRoot`) | Only inside the unrun instrumented test; `touchBoundsInRoot` is **not** used. |
| Clipping / parent-constraint analysis, live UI preview | **Not implemented.** |

## Run the tests that were actually run

```sh
# JDK 17+ and Kotlin 2.x compiler required (JUnit is not used; a dependency-free harness is).
KOTLINC=/path/to/kotlinc/bin/kotlinc scripts/build-and-test.sh
```
Result when authored: `50 tests executed, 50 passed, 0 failed` (see `docs/TEST_RESULTS.txt`).

Preview UI (demo mode, needs a display): `java -cp out/cw-tests.jar dev.composeworkbench.ui.WorkbenchLauncherKt`
Real mode: pass a project directory as the first argument.

## Getting real verification results
1. Run `TouchTargetMatrixTest` on a device/emulator (`./gradlew :sample-app:connectedDebugAndroidTest` — unverified command).
2. Copy `files/cw-results.tsv` from the app's data dir to `<project>/.compose-workbench/results.tsv`.
3. In the workbench, approve a repair and press **Run verification**. Without that file the result is **Verification not run**; nothing is ever reported as passed without executed results.

See `docs/ARCHITECTURE.md`, `docs/PHASE_REPORTS.md`, `docs/KNOWN_LIMITATIONS.md`.
