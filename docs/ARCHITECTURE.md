# Architecture

```
workbench-core   pure Kotlin/JVM, no dependencies   (analyzer, proposals, patching, matrix, engines, WorkflowStore)
workbench-ui     Swing, depends on core             (WorkbenchPanel, factory, standalone launcher)
workbench-tests  dependency-free harness (50 tests) (core + headless UI tests, screenshot tool)
workbench-plugin IntelliJ tool-window shell         (NOT compiled)
sample-app       Android sample + instrumented test (NOT compiled / run)
```

## Workflow & state model (`Workflow.kt`)
Per finding, four **independent** axes plus history:

| Axis | Values |
|---|---|
| proposalState | NONE, PROPOSED |
| approval | NOT_REVIEWED, AWAITING_APPROVAL, APPROVED, REJECTED |
| patch | NOT_APPLIED, APPLIED, FAILED |
| verification | NOT_RUN, PASSED, FAILED, INCONCLUSIVE |

`resolved = approval == APPROVED && verification == PASSED && patch != FAILED`. Nothing else resolves a finding.

Transitions (all explicit methods on `WorkflowStore`):
- `suggestFix` → Repair; creates proposal; **writes nothing**.
- `approve` → Verify; records decision only; verification reset to NOT_RUN; **writes nothing**. Label: `Approved · Awaiting verification`.
- `reject` → Diagnose; **writes nothing**.
- `applyApprovedPatch` – only method that writes source; requires APPROVED; failure → patch FAILED, file untouched; success resets verification (code changed) but keeps run history.
- `runVerification` – runs the full 28-case matrix via a `VerificationEngine`; missing cases become NOT_RUN. Runs **before** approval are baseline evidence and do not change verification state. Demo-fixture output cannot verify a non-demo finding (downgraded to inconclusive).
- `reset` – restores workflow state; it does **not** revert source files already patched (it says so).

Status labels: Not reviewed · Repair proposed · Awaiting approval · Proposal rejected · Approved · Awaiting verification · Verification not run · Verification passed/failed/inconclusive · Resolved (demo data when evidence is a fixture). Patch state is shown as a separate line.

## Verify screen
Buttons: Review findings (→ Diagnose), Reset workflow, Apply approved patch, Run verification. **There is no "Back to Repair"**
(asserted by test). The stage indicator is display-only labels, not buttons.

## Static analysis rules (heuristic evidence only)
SMALL_TOUCH_TARGET (interactive chain with `.size/.width/.height` < 48dp and no `minimumInteractiveComponentSize`),
CLICKABLE_BEFORE_PADDING, ADJACENT_CLICKABLES (≤ 6 lines apart), MISSING_CONTENT_DESCRIPTION (clickable Icon/Image with `contentDescription = null`).
Comments and string literals are ignored. Every static finding carries a HEURISTIC and a NOT_RUN evidence item.

## Patch safety
Edits carry context and are located by exact offset, else by unique text match; "guards" (the targeted modifier chain) must still occur exactly once or the patch fails with no change. Writes go through a temp file + move. Proposals that cannot be patched reliably are manual-review (no Apply).

## Verification matrix & coordinates
7 probes (center, top, bottom, left, right edge, corners, outside) × 1.0/1.3/1.5/2.0 = 28 per finding. Inside probes are 1 px inside the bounds, corners = four inner corners, outside = 8 px left of the left edge at vertical centre. Negative probe passes only if the target's click did **not** fire. Results file: tab-separated (`ResultsFileEngine` docs).

## Compose-version-specific choices
`minimumInteractiveComponentSize` assumes Material 3 (`androidx.compose.material3`); the proposal notes the Material 2 alternative. The instrumented test uses `boundsInRoot` (layout bounds); it observes *which handler fires*, so it does not depend on `touchBoundsInRoot`.
