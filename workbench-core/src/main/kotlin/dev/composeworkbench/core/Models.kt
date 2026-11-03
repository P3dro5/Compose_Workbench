package dev.composeworkbench.core

/** The four workflow stages. Order matters: it is the order shown in the stage indicator. */
enum class Stage(val label: String) {
    INSPECT("Inspect"), DIAGNOSE("Diagnose"), REPAIR("Repair"), VERIFY("Verify")
}

enum class Severity { HIGH, MEDIUM, LOW }

enum class FindingKind {
    SMALL_TOUCH_TARGET, CLICKABLE_BEFORE_PADDING, ADJACENT_CLICKABLES, MISSING_CONTENT_DESCRIPTION
}

/** How strong a piece of evidence is. Static analysis is only ever HEURISTIC. */
enum class EvidenceKind(val label: String) {
    HEURISTIC("Heuristic"),
    GEOMETRY("Geometry"),
    BEHAVIORAL_PASS("Behavioral pass"),
    BEHAVIORAL_FAIL("Behavioral fail"),
    INCONCLUSIVE("Inconclusive"),
    NOT_RUN("Not run")
}

data class Evidence(val kind: EvidenceKind, val detail: String)

data class SourceLocation(val file: String, val line: Int, val startOffset: Int, val endOffset: Int)

data class Finding(
    val id: String,
    val kind: FindingKind,
    val title: String,
    val severity: Severity,
    /** Name of the interactive target (testTag if present, otherwise enclosing composable). */
    val targetName: String,
    val location: SourceLocation,
    /** Exact source text the finding refers to. */
    val snippet: String,
    val explanation: String,
    val evidence: List<Evidence>,
    /** True for illustrative demo findings. Demo findings must never be presented as real defects. */
    val isDemo: Boolean = false,
    /** Offset of the `Modifier` token for chain-based findings, -1 otherwise. */
    val chainStart: Int = -1
)
