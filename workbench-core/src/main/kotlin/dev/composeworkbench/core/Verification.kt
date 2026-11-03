package dev.composeworkbench.core

import java.io.File

enum class Probe(val label: String, val negative: Boolean = false) {
    CENTER("Center"), TOP_EDGE("Top edge"), BOTTOM_EDGE("Bottom edge"),
    LEFT_EDGE("Left edge"), RIGHT_EDGE("Right edge"), CORNERS("Corners"),
    OUTSIDE("Outside boundary (negative check)", true)
}

object FontScales { val ALL = listOf(1.0f, 1.3f, 1.5f, 2.0f) }

enum class ResultStatus { PASS, FAIL, INCONCLUSIVE, NOT_RUN }

enum class ResultSource(val label: String) {
    INSTRUMENTED("Instrumented test results"),
    DEMO_FIXTURE("DEMO FIXTURE - not real test output"),
    NONE("No execution")
}

data class MatrixCase(val findingId: String, val targetName: String, val fontScale: Float, val probe: Probe) {
    val key: String get() = key(targetName, fontScale, probe)
    companion object { fun key(target: String, scale: Float, probe: Probe) = "$target|${"%.1f".format(java.util.Locale.ROOT, scale)}|${probe.name}" }
}

data class Point(val x: Float, val y: Float)
data class RectPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
}

data class VerificationResult(
    val case: MatrixCase,
    val points: List<Point>,
    val expected: String,
    val observed: String,
    val status: ResultStatus,
    val evidence: String,
    val error: String?,
    val source: ResultSource
)

/** Tap coordinates (root pixels) for each probe. Inside probes are [inset]px inside the bounds; OUTSIDE is [margin]px left of them. */
object ProbeGeometry {
    fun points(r: RectPx, probe: Probe, inset: Float = 1f, margin: Float = 8f): List<Point> = when (probe) {
        Probe.CENTER -> listOf(Point(r.cx, r.cy))
        Probe.TOP_EDGE -> listOf(Point(r.cx, r.top + inset))
        Probe.BOTTOM_EDGE -> listOf(Point(r.cx, r.bottom - inset))
        Probe.LEFT_EDGE -> listOf(Point(r.left + inset, r.cy))
        Probe.RIGHT_EDGE -> listOf(Point(r.right - inset, r.cy))
        Probe.CORNERS -> listOf(Point(r.left + inset, r.top + inset), Point(r.right - inset, r.top + inset),
            Point(r.left + inset, r.bottom - inset), Point(r.right - inset, r.bottom - inset))
        Probe.OUTSIDE -> listOf(Point(r.left - margin, r.cy))
    }
}

object VerificationMatrix {
    fun cases(f: Finding): List<MatrixCase> =
        FontScales.ALL.flatMap { s -> Probe.values().map { p -> MatrixCase(f.id, f.targetName, s, p) } }

    fun expectedFor(c: MatrixCase) =
        if (c.probe.negative) "A tap outside '${c.targetName}' must NOT trigger its click action"
        else "A tap on '${c.targetName}' (${c.probe.label.lowercase()}) triggers its click action"
}

enum class Verdict { PASSED, FAILED, INCONCLUSIVE, NOT_RUN }

data class BatchSummary(val total: Int, val passed: Int, val failed: Int, val inconclusive: Int, val notRun: Int) {
    val executed get() = passed + failed + inconclusive
    val complete get() = notRun == 0
    val verdict: Verdict get() = when {
        failed > 0 -> Verdict.FAILED
        total > 0 && notRun == total -> Verdict.NOT_RUN
        inconclusive > 0 || notRun > 0 -> Verdict.INCONCLUSIVE
        total > 0 && passed == total -> Verdict.PASSED
        else -> Verdict.NOT_RUN
    }
    companion object {
        fun of(results: List<VerificationResult>, total: Int = results.size) = BatchSummary(
            total, results.count { it.status == ResultStatus.PASS }, results.count { it.status == ResultStatus.FAIL },
            results.count { it.status == ResultStatus.INCONCLUSIVE }, results.count { it.status == ResultStatus.NOT_RUN })
    }
}

data class VerificationRun(
    val findingId: String,
    val engineName: String,
    val results: List<VerificationResult>,
    val summary: BatchSummary,
    val verdict: Verdict,
    val afterApproval: Boolean,
    val note: String?,
    val timestamp: Long = System.currentTimeMillis()
) {
    val sources: Set<ResultSource> get() = results.map { it.source }.toSet()
}

interface VerificationEngine {
    val name: String
    fun run(finding: Finding, cases: List<MatrixCase>): List<VerificationResult>
}

internal fun notRun(c: MatrixCase, reason: String, src: ResultSource = ResultSource.NONE) =
    VerificationResult(c, emptyList(), VerificationMatrix.expectedFor(c), "Not executed", ResultStatus.NOT_RUN, "", reason, src)

/** Honest default: nothing was executed, so nothing is reported as passed. */
class UnavailableEngine(private val reason: String) : VerificationEngine {
    override val name = "Unavailable"
    override fun run(finding: Finding, cases: List<MatrixCase>) = cases.map { notRun(it, reason) }
}

/**
 * Imports results written by the instrumented test (sample-app/.../TouchTargetMatrixTest).
 * Format: tab-separated, '#' comment lines ignored. Columns:
 * target, fontScale, probe, points("x,y;x,y"), expected, observed, status, evidence, error
 */
class ResultsFileEngine(private val file: File) : VerificationEngine {
    override val name = "Instrumented results file (${file.name})"

    override fun run(finding: Finding, cases: List<MatrixCase>): List<VerificationResult> {
        if (!file.isFile) return cases.map { notRun(it, "Results file not found: ${file.path}. Run the instrumented test on a device/emulator and copy its output here.") }
        val byKey = HashMap<String, VerificationResult>()
        file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val p = line.split('\t')
            if (p.size < 7) return@forEach
            val probe = Probe.values().firstOrNull { it.name == p[2] } ?: return@forEach
            val scale = p[1].toFloatOrNull() ?: return@forEach
            val status = ResultStatus.values().firstOrNull { it.name == p[6] } ?: return@forEach
            val pts = p[3].split(';').mapNotNull { s -> s.split(',').let { v -> if (v.size == 2) v[0].toFloatOrNull()?.let { x -> v[1].toFloatOrNull()?.let { y -> Point(x, y) } } else null } }
            val c = MatrixCase("", p[0], scale, probe)
            byKey[c.key] = VerificationResult(c, pts, p[4], p[5], status, p.getOrElse(7) { "" }, p.getOrNull(8)?.ifBlank { null }, ResultSource.INSTRUMENTED)
        }
        return cases.map { c -> byKey[c.key]?.copy(case = c) ?: notRun(c, "The results file contains no entry for this case.") }
    }
}

/** Scripted results for demos and tests. Every result is tagged DEMO_FIXTURE and is never real test output. */
class DemoFixtureEngine(private val scenario: Scenario) : VerificationEngine {
    enum class Scenario { ILLUSTRATED_FAILURES, ALL_PASS, PARTIAL }
    override val name = "Demo fixture (${scenario.name}) - ILLUSTRATIVE"

    override fun run(finding: Finding, cases: List<MatrixCase>): List<VerificationResult> {
        val limit = if (scenario == Scenario.PARTIAL) 20 else cases.size
        return cases.take(limit).mapIndexed { i, c ->
            val bounds = RectPx(800f, 400f, 800f + 72f * c.fontScale, 400f + 72f * c.fontScale)
            val pts = ProbeGeometry.points(bounds, c.probe)
            val fail = scenario == Scenario.ILLUSTRATED_FAILURES &&
                ((finding.id == "CW-001" && c.fontScale == 1.5f && c.probe == Probe.RIGHT_EDGE) ||
                    (finding.id == "CW-002" && c.fontScale == 2.0f && c.probe == Probe.CORNERS))
            val inconclusive = scenario == Scenario.PARTIAL && i >= 17
            val (st, obs) = when {
                fail && finding.id == "CW-001" -> ResultStatus.FAIL to "[DEMO] tap reached the adjacent target instead"
                fail -> ResultStatus.FAIL to "[DEMO] no click action observed at the corner points"
                inconclusive -> ResultStatus.INCONCLUSIVE to "[DEMO] injection result could not be classified"
                else -> ResultStatus.PASS to "[DEMO] click action observed as expected"
            }
            VerificationResult(c, pts, VerificationMatrix.expectedFor(c), obs, st,
                "[DEMO FIXTURE] fixture bounds ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom} px - not measured", if (st == ResultStatus.FAIL) obs else null, ResultSource.DEMO_FIXTURE)
        }
    }
}
