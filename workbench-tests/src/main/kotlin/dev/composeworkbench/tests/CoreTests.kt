package dev.composeworkbench.tests

import dev.composeworkbench.core.*
import java.io.File

private fun write(dir: File, name: String, text: String) = File(dir, name).also { it.writeText(text) }
private const val HDR = "package x\nimport androidx.compose.foundation.clickable\nimport androidx.compose.ui.Modifier\n"

fun registerCoreTests(s: Suite) {
    // ---------- Analyzer ----------
    s.test("analyzer: demo source yields small-target and adjacent findings for 'favorite'") {
        val f = SourceAnalyzer.analyze("F.kt", DemoData.SAMPLE_SOURCE)
        expect(f.any { it.kind == FindingKind.SMALL_TOUCH_TARGET && it.targetName == "favorite" }) { "no small target: ${f.map { it.kind to it.targetName }}" }
        expect(f.any { it.kind == FindingKind.ADJACENT_CLICKABLES && it.targetName == "favorite" })
        expect(f.any { it.kind == FindingKind.SMALL_TOUCH_TARGET && it.targetName == "share" })
        expect(f.all { it.evidence.any { e -> e.kind == EvidenceKind.HEURISTIC } && it.evidence.any { e -> e.kind == EvidenceKind.NOT_RUN } }) { "static findings must be heuristic and not-run" }
    }
    s.test("analyzer: 48dp+ and minimumInteractiveComponentSize are not flagged") {
        val ok1 = SourceAnalyzer.analyze("F.kt", HDR + "fun A(){ Box(Modifier.size(48.dp).clickable{}) }")
        expect(ok1.none { it.kind == FindingKind.SMALL_TOUCH_TARGET }) { "48dp flagged: $ok1" }
        val ok2 = SourceAnalyzer.analyze("F.kt", HDR + "fun A(){ Box(Modifier.minimumInteractiveComponentSize().clickable{}.size(24.dp)) }")
        expect(ok2.none { it.kind == FindingKind.SMALL_TOUCH_TARGET }) { "min-interactive flagged" }
    }
    s.test("analyzer: comments and strings are ignored") {
        val f = SourceAnalyzer.analyze("F.kt", HDR + "// Modifier.size(10.dp).clickable{}\n/* Modifier.size(10.dp).clickable{} */\nfun A(){ val s = \"Modifier.size(10.dp).clickable{}\" }")
        expectEq(0, f.size, "findings")
    }
    s.test("analyzer: padding before clickable flagged; after is not") {
        val bad = SourceAnalyzer.analyze("F.kt", HDR + "fun A(){ Box(Modifier.padding(8.dp).clickable{}) }")
        expect(bad.any { it.kind == FindingKind.CLICKABLE_BEFORE_PADDING })
        val good = SourceAnalyzer.analyze("F.kt", HDR + "fun A(){ Box(Modifier.clickable{}.padding(8.dp)) }")
        expect(good.none { it.kind == FindingKind.CLICKABLE_BEFORE_PADDING })
    }
    s.test("analyzer: clickable Icon with null contentDescription flagged") {
        val f = SourceAnalyzer.analyze("F.kt", HDR + "fun A(){ Icon(imageVector = X, contentDescription = null, modifier = Modifier.size(48.dp).clickable{}) }")
        expect(f.any { it.kind == FindingKind.MISSING_CONTENT_DESCRIPTION })
    }
    s.test("analyzer: finding ids are stable and sequential") {
        val a = SourceAnalyzer.analyze("F.kt", DemoData.SAMPLE_SOURCE); val b = SourceAnalyzer.analyze("F.kt", DemoData.SAMPLE_SOURCE)
        expectEq(a.map { it.id }, b.map { it.id }); expectEq("CW-001", a.first().id)
    }

    // ---------- Proposals, diff, patch ----------
    s.test("proposals: every kind yields a distinct, finding-specific proposal with a diff") {
        val src = HDR + """
fun A() {
    Icon(imageVector = X, contentDescription = null, modifier = Modifier.testTag("one").size(24.dp).clickable { })
    Box(Modifier.testTag("two").padding(8.dp).clickable { })
}
"""
        val fs = SourceAnalyzer.analyze("F.kt", src)
        val props = fs.map { ProposalGenerator.generate(it, src) }
        expect(props.map { it.summary }.toSet().size >= 3) { "proposals not distinct: ${props.map { it.summary }}" }
        props.forEach { expect(it.diff.contains("@@") || !it.autoApplicable) { "missing diff" }; expect(it.notes.isNotEmpty()) }
        expect(props.any { it.requiresHumanInput }) { "placeholder proposal must require human input" }
    }
    s.test("proposals: generating a proposal never writes the file") {
        val d = tempDir(); val (file, findings) = DemoData.create(d)
        val before = file.readText()
        ProposalGenerator.generate(findings[0], before); ProposalGenerator.generate(findings[1], before)
        expectEq(before, file.readText(), "file")
    }
    s.test("proposals: small-target patch inserts minimumInteractiveComponentSize and import") {
        val d = tempDir(); val (file, findings) = DemoData.create(d)
        val p = ProposalGenerator.generate(findings[1], file.readText())
        expect(p.autoApplicable); val r = PatchApplier.applyToText(file.readText(), p.patch!!)
        expect(r.success); expect(r.newText!!.contains("Modifier.minimumInteractiveComponentSize()\n"))
        expect(r.newText!!.contains("import androidx.compose.material3.minimumInteractiveComponentSize"))
        expect(p.diff.contains("+import androidx.compose.material3.minimumInteractiveComponentSize")) { p.diff }
    }
    s.test("proposals: padding/clickable reorder swaps exactly those calls") {
        val src = HDR + "fun A(){ Box(Modifier.padding(8.dp).clickable{ }) }\n"
        val f = SourceAnalyzer.analyze("F.kt", src).first { it.kind == FindingKind.CLICKABLE_BEFORE_PADDING }
        val r = PatchApplier.applyToText(src, ProposalGenerator.generate(f, src).patch!!)
        expect(r.newText!!.contains("Modifier.clickable{ }.padding(8.dp)")) { r.newText!! }
    }
    s.test("patch: applies to file, preserving unrelated edits made above the target") {
        val d = tempDir(); val (file, findings) = DemoData.create(d)
        val p = ProposalGenerator.generate(findings[1], file.readText())
        file.writeText(file.readText().replace("@Composable\nfun FavoriteCard", "// my unrelated edit\n@Composable\nfun FavoriteCard"))
        val r = PatchApplier.applyToFile(p.patch!!)
        expect(r.success) { r.message }
        val after = file.readText(); expect(after.contains("// my unrelated edit")); expect(after.contains(".minimumInteractiveComponentSize()"))
    }
    s.test("patch: stale source fails and leaves the file byte-identical") {
        val d = tempDir(); val (file, findings) = DemoData.create(d)
        val p = ProposalGenerator.generate(findings[1], file.readText())
        file.writeText(file.readText().replace(".size(24.dp)", ".size(30.dp)").replace("Modifier\n                .testTag(\"favorite\")", "Modifier\n                .testTag(\"fav2\")"))
        val before = file.readText(); val r = PatchApplier.applyToFile(p.patch!!)
        expect(!r.success) { "expected failure but: ${r.message}" }; expectEq(before, file.readText(), "file after failed patch")
    }
    s.test("diff: unified diff marks removed and added lines") {
        val d = DiffRenderer.unified("a.kt", "a\nb\nc", "a\nB\nc")
        expect(d.contains("-b") && d.contains("+B") && d.contains("@@")) { d }
        expectEq("(no changes)", DiffRenderer.unified("a.kt", "x", "x"))
    }

    // ---------- Matrix ----------
    s.test("matrix: 7 probes x 4 font scales = 28 cases per finding, unique keys") {
        val d = tempDir(); val (_, fs) = DemoData.create(d)
        val c = VerificationMatrix.cases(fs[0]); expectEq(28, c.size); expectEq(28, c.map { it.key }.toSet().size)
        expectEq(setOf(1.0f, 1.3f, 1.5f, 2.0f), c.map { it.fontScale }.toSet()); expectEq(7, c.map { it.probe }.toSet().size)
    }
    s.test("geometry: probe coordinates are inside bounds; outside probe is outside") {
        val r = RectPx(100f, 200f, 172f, 272f)
        Probe.values().filter { !it.negative }.forEach { p -> ProbeGeometry.points(r, p).forEach { pt ->
            expect(pt.x >= r.left && pt.x < r.right && pt.y >= r.top && pt.y < r.bottom) { "$p $pt outside" } } }
        expectEq(4, ProbeGeometry.points(r, Probe.CORNERS).size)
        val o = ProbeGeometry.points(r, Probe.OUTSIDE).single(); expect(o.x < r.left)
    }
    s.test("summary: verdict logic") {
        expectEq(Verdict.PASSED, BatchSummary(28, 28, 0, 0, 0).verdict)
        expectEq(Verdict.FAILED, BatchSummary(28, 20, 1, 0, 7).verdict)
        expectEq(Verdict.INCONCLUSIVE, BatchSummary(28, 27, 0, 1, 0).verdict)
        expectEq(Verdict.INCONCLUSIVE, BatchSummary(28, 20, 0, 0, 8).verdict)
        expectEq(Verdict.NOT_RUN, BatchSummary(28, 0, 0, 0, 28).verdict)
    }
    s.test("engine: unavailable engine reports 28 NOT_RUN, never PASS") {
        val d = tempDir(); val (_, fs) = DemoData.create(d)
        val r = UnavailableEngine("no device").run(fs[0], VerificationMatrix.cases(fs[0]))
        expectEq(28, r.size); expect(r.all { it.status == ResultStatus.NOT_RUN && it.source == ResultSource.NONE })
    }
    s.test("engine: results file import maps entries and marks missing cases NOT_RUN") {
        val d = tempDir(); val (_, fs) = DemoData.create(d); val f = fs[1]
        val tsv = File(d, "r.tsv")
        tsv.writeText("# header\nfavorite\t1.0\tCENTER\t10.0,10.0\texp\tobs\tPASS\tev\t\nfavorite\t2.0\tCORNERS\t1.0,1.0;2.0,2.0\texp\tobs\tFAIL\tev\tno click\n")
        val res = ResultsFileEngine(tsv).run(f, VerificationMatrix.cases(f))
        expectEq(28, res.size); expectEq(1, res.count { it.status == ResultStatus.PASS }); expectEq(1, res.count { it.status == ResultStatus.FAIL })
        expectEq(26, res.count { it.status == ResultStatus.NOT_RUN }); expect(res.filter { it.status != ResultStatus.NOT_RUN }.all { it.source == ResultSource.INSTRUMENTED })
        val missing = ResultsFileEngine(File(d, "nope.tsv")).run(f, VerificationMatrix.cases(f)); expect(missing.all { it.status == ResultStatus.NOT_RUN })
    }

    registerWorkflowTests(s)
}
