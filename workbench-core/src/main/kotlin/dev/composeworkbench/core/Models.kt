package dev.composeworkbench.core

/** The four workflow stages. Order matters: it is the order shown in the stage indicator. */
enum class Stage(val label: String) {
    INSPECT("Inspect"), DIAGNOSE("Diagnose"), REPAIR("Repair"), VERIFY("Verify")
}

enum class Severity { HIGH, MEDIUM, LOW }

enum class FindingKind {
    SMALL_TOUCH_TARGET, CLICKABLE_BEFORE_PADDING, ADJACENT_CLICKABLES, MISSING_CONTENT_DESCRIPTION,
    TEXT_CLIPPING, LAYOUT_OVERFLOW, COLOR_STATE_VISIBILITY
}

/** How strong a piece of evidence is. Static analysis is only ever HEURISTIC. */
enum class EvidenceKind(val label: String) {
    HEURISTIC("Heuristic"),
    GEOMETRY("Geometry"),
    BEHAVIORAL_PASS("Behavioral pass"),
    BEHAVIORAL_FAIL("Behavioral fail"),
    INCONCLUSIVE("Inconclusive"),
    NOT_RUN("Not run"),
    SCREENSHOT("Screenshot"),
    TEST_RESULT("Test result")
}

enum class Confidence { LOW, MEDIUM, HIGH }

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** A single piece of evidence tied to a scenario where applicable. */
data class Evidence(
    val kind: EvidenceKind,
    val detail: String,
    val id: String = "",
    val scenarioId: String? = null,
    val data: Map<String, String> = emptyMap(),
    val screenshotPath: String? = null
)

data class SourceLocation(
    val file: String,
    val line: Int,
    val startOffset: Int,
    val endOffset: Int,
    val startColumn: Int? = null,
    val endColumn: Int? = null
)

/** A runnable scenario with environment dimensions. */
data class InspectionScenario(
    val id: String,
    val name: String,
    val fontScale: Float = 1.0f,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val windowWidthDp: Int? = null,
    val windowHeightDp: Int? = null,
    val localeTag: String? = null,
    val stateDescription: String? = null
)

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
    /** Rule identifier that produced this finding. */
    val ruleId: String? = null,
    /** Confidence level of this finding. */
    val confidence: Confidence = Confidence.MEDIUM,
    /** Human-readable message summarizing the finding. */
    val message: String? = null,
    /** Suggested fix identifiers related to this finding. */
    val suggestedFixIds: List<String> = emptyList(),
    /** True for illustrative demo findings. Demo findings must never be presented as real defects. */
    val isDemo: Boolean = false,
    /** Offset of the `Modifier` token for chain-based findings, -1 otherwise. */
    val chainStart: Int = -1,
    /** Visual preview data for before/after comparison. */
    val visualPreview: VisualPreview? = null
)

/** Visual before/after preview data for a finding. */
data class VisualPreview(
    /** Before state: current problematic component. */
    val before: ComponentPreview,
    /** After state: how the component looks after the fix. */
    val after: ComponentPreview,
    /** Font scales to test (1.0x, 1.3x, 1.5x, 2.0x). */
    val fontScales: List<Float> = listOf(1.0f, 1.3f, 1.5f, 2.0f)
)

/** Preview of a single component state. */
data class ComponentPreview(
    val componentName: String,
    val widthDp: Float,
    val heightDp: Float,
    val isInteractive: Boolean,
    val hasContentDescription: Boolean,
    val colorHex: String?,
    val issues: List<PreviewIssue>
)

data class PreviewIssue(
    val type: PreviewIssueType,
    val description: String,
    val severity: PreviewSeverity
)

enum class PreviewIssueType { SMALL_TARGET, MISSING_LABEL, POOR_CONTRAST, OVERFLOW, CLIPPING }
enum class PreviewSeverity { HIGH, MEDIUM, LOW }
