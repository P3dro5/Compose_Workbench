# Known limitations (nothing below is implemented or verified)

1. **No existing project was provided**; this is a new implementation. "Preserve existing UI" could not be satisfied or checked.
2. **Instrumented tests were never compiled or run.** No Android SDK, Gradle or device/emulator was available. Every verification number in this repository's tests comes from *fixtures* or unit-level engines, not real taps.
3. **Plugin module and all Gradle files were never built.** Maven Central, Google Maven and Gradle hosts were unreachable; versions are unverified starting points.
4. **JUnit not used** (could not be downloaded); a minimal custom harness is used. Migration is mechanical.
5. Not implemented: clipping/parent-constraint analysis, semantics beyond `contentDescription`, pixel↔dp reporting outside the instrumented test, live UI preview in Inspect, `touchBoundsInRoot`.
6. Static findings are heuristics (e.g. adjacency = "within 6 lines") and can be false positives/negatives.
7. Patches are written with `java.io`; inside an IDE they should go through `WriteCommandAction`, and the VFS must be refreshed.
8. `reset` does not revert patched files. There is no undo for an applied patch.
9. Demo findings CW-001/CW-002 and `DemoFixtureEngine` results are illustrative and labelled so.
10. The UI was rendered headless to PNG and exercised via component-tree tests; it was not driven in a real window or at multiple window sizes.
