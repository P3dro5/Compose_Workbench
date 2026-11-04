package dev.composeworkbench.tests

import dev.composeworkbench.core.*
import java.io.File

private class Fixture {
    val dir = tempDir()
    val pair = DemoData.create(dir)
    val file: File = pair.first
    val findings = pair.second
    val original = file.readText()
    val store = WorkflowStore(findings)
    val id1 = findings[0].id; val id2 = findings[1].id
}

fun registerWorkflowTests(s: Suite) {
    s.test("workflow: demo findings are labelled demo and carry demo evidence note") {
        val x = Fixture(); expectEq(listOf("CW-001", "CW-002"), x.findings.map { it.id }); expect(x.findings.all { it.isDemo && it.title.startsWith("[DEMO]") })
    }
    s.test("workflow: suggest fix -> Repair, awaiting approval, file untouched") {
        val x = Fixture(); x.store.suggestFix(x.id2)
        expectEq(Stage.REPAIR, x.store.stage); expectEq(ApprovalState.AWAITING_APPROVAL, x.store.state(x.id2).approval)
        expectEq("Repair proposed \u00B7 Awaiting approval", x.store.state(x.id2).statusLabel()); expectEq(x.original, x.file.readText(), "file")
    }
    s.test("workflow: approve -> Verify, applies patch automatically, file modified, verification NOT_RUN, not resolved") {
        val x = Fixture(); x.store.suggestFix(x.id2); x.store.approve(x.id2)
        val st = x.store.state(x.id2)
        expectEq(Stage.VERIFY, x.store.stage); expectEq(ApprovalState.APPROVED, st.approval); expectEq(PatchState.APPLIED, st.patch)
        expectEq(VerificationState.NOT_RUN, st.verification); expect(!st.resolved); expectEq("Approved \u00B7 Awaiting verification", st.statusLabel())
        expect(x.file.readText().contains(".minimumInteractiveComponentSize()")) { "file should be patched" }
    }
    s.test("workflow: reject -> Diagnose, file untouched, not approved") {
        val x = Fixture(); x.store.suggestFix(x.id1); x.store.reject(x.id1)
        expectEq(Stage.DIAGNOSE, x.store.stage); expectEq(ApprovalState.REJECTED, x.store.state(x.id1).approval); expectEq("Proposal rejected", x.store.state(x.id1).statusLabel())
        expectEq(x.original, x.file.readText(), "file"); expect(!x.store.applyApprovedPatch(x.id1).success)
    }
    s.test("workflow: cannot approve without a proposal; cannot apply patch without approval") {
        val x = Fixture(); expect(!x.store.approve(x.id1)); expect(!x.store.applyApprovedPatch(x.id1).success)
        x.store.suggestFix(x.id1); expect(!x.store.applyApprovedPatch(x.id1).success) { "applied while only proposed" }; expectEq(x.original, x.file.readText(), "file")
    }
    s.test("workflow: approve auto-applies patch; applyApprovedPatch after approve reports already applied") {
        val x = Fixture(); x.store.suggestFix(x.id2); x.store.approve(x.id2)
        val st = x.store.state(x.id2); expectEq(PatchState.APPLIED, st.patch)
        expect(x.file.readText().contains(".minimumInteractiveComponentSize()"))
        val r = x.store.applyApprovedPatch(x.id2); expect(!r.success) { "expected already applied but: ${r.message}" }
        expectEq(PatchState.APPLIED, x.store.state(x.id2).patch)
    }
    s.test("workflow: failed patch application during approve when source changed, state FAILED, never resolved") {
        val x = Fixture(); x.store.suggestFix(x.id2)
        // Modify source before approve so automatic patch application fails
        x.file.writeText(x.original.replace("testTag(\"favorite\")", "testTag(\"renamed\")").replace("onFavorite() }\n                .size(24.dp)", "onFavorite() }\n                .size(25.dp)"))
        val before = x.file.readText(); val approved = x.store.approve(x.id2)
        expect(!approved) { "approve should fail when patch cannot be applied" }
        expectEq(PatchState.FAILED, x.store.state(x.id2).patch); expectEq(before, x.file.readText(), "file")
        x.store.runVerification(x.id2, DemoFixtureEngine(DemoFixtureEngine.Scenario.ALL_PASS)); expect(!x.store.state(x.id2).resolved) { "resolved despite failed patch" }
    }
    s.test("verify: unavailable engine -> 'Verification not run', never resolved") {
        val x = Fixture(); x.store.suggestFix(x.id2); x.store.approve(x.id2)
        val run = x.store.runVerification(x.id2, UnavailableEngine("no device"))!!
        expectEq(Verdict.NOT_RUN, run.verdict); expectEq("Verification not run", x.store.state(x.id2).statusLabel()); expect(!x.store.state(x.id2).resolved)
        expectEq(28, run.summary.notRun)
    }
    s.test("verify: demo ALL_PASS resolves a DEMO finding only, labelled as demo data") {
        val x = Fixture(); x.store.suggestFix(x.id2); x.store.approve(x.id2)
        x.store.runVerification(x.id2, DemoFixtureEngine(DemoFixtureEngine.Scenario.ALL_PASS))
        expect(x.store.state(x.id2).resolved); expectEq("Resolved (demo data)", x.store.state(x.id2).statusLabel())
    }
    s.test("verify: demo fixture can NOT verify a real (non-demo) finding") {
        val d = tempDir(); val src = File(d, "R.kt"); src.writeText("package x\nimport androidx.compose.ui.Modifier\nfun A(){ Box(Modifier.size(24.dp).clickable{}) }\n")
        val fs = SourceAnalyzer.analyzeDirectory(d); expect(fs.isNotEmpty() && fs.none { it.isDemo })
        val store = WorkflowStore(fs); val id = fs.first { it.kind == FindingKind.SMALL_TOUCH_TARGET }.id
        store.suggestFix(id); store.approve(id)
        val run = store.runVerification(id, DemoFixtureEngine(DemoFixtureEngine.Scenario.ALL_PASS))!!
        expectEq(Verdict.INCONCLUSIVE, run.verdict); expect(!store.state(id).resolved); expect(run.note!!.contains("Demo fixture"))
    }
    s.test("verify: illustrated failures -> CW-001 fails at 1.5x right edge, CW-002 fails at 2.0x corners, others pass") {
        val x = Fixture(); listOf(x.id1, x.id2).forEach { x.store.suggestFix(it); x.store.approve(it) }
        val r1 = x.store.runVerification(x.id1, DemoFixtureEngine(DemoFixtureEngine.Scenario.ILLUSTRATED_FAILURES))!!
        val r2 = x.store.runVerification(x.id2, DemoFixtureEngine(DemoFixtureEngine.Scenario.ILLUSTRATED_FAILURES))!!
        expectEq(1, r1.summary.failed); expectEq(27, r1.summary.passed); expectEq(1, r2.summary.failed)
        expect(r1.results.single { it.status == ResultStatus.FAIL }.let { it.case.probe == Probe.RIGHT_EDGE && it.case.fontScale == 1.5f })
        expect(r2.results.single { it.status == ResultStatus.FAIL }.let { it.case.probe == Probe.CORNERS && it.case.fontScale == 2.0f })
        expectEq(VerificationState.FAILED, x.store.state(x.id1).verification); expectEq("Verification failed", x.store.state(x.id1).statusLabel())
        expect(r1.results.all { it.source == ResultSource.DEMO_FIXTURE }); expect(r1.results.all { it.evidence.startsWith("[DEMO FIXTURE]") })
    }
    s.test("verify: partial run is inconclusive and not resolved; history preserved") {
        val x = Fixture(); x.store.suggestFix(x.id2); x.store.approve(x.id2)
        val r = x.store.runVerification(x.id2, DemoFixtureEngine(DemoFixtureEngine.Scenario.PARTIAL))!!
        expectEq(Verdict.INCONCLUSIVE, r.verdict); expect(r.summary.notRun > 0 && r.summary.inconclusive > 0); expectEq("Verification inconclusive", x.store.state(x.id2).statusLabel())
        x.store.runVerification(x.id2, DemoFixtureEngine(DemoFixtureEngine.Scenario.ALL_PASS)); expectEq(2, x.store.state(x.id2).runs.size)
    }
    s.test("verify: runs before approval are baseline evidence and do not change verification state") {
        val x = Fixture(); x.store.suggestFix(x.id2)
        x.store.runVerification(x.id2, DemoFixtureEngine(DemoFixtureEngine.Scenario.ALL_PASS))
        val st = x.store.state(x.id2); expectEq(VerificationState.NOT_RUN, st.verification); expectEq(1, st.baselineRuns.size); expect(!st.resolved)
    }
    s.test("workflow: findings are independent (approving CW-001 does not touch CW-002)") {
        val x = Fixture(); x.store.suggestFix(x.id1); x.store.approve(x.id1)
        expectEq(ApprovalState.NOT_REVIEWED, x.store.state(x.id2).approval); expectEq("Not reviewed", x.store.state(x.id2).statusLabel())
    }
    s.test("workflow: reset clears state but warns that patched files are not reverted") {
        val x = Fixture(); x.store.suggestFix(x.id2); x.store.approve(x.id2); x.store.applyApprovedPatch(x.id2); x.store.reset()
        expectEq(Stage.INSPECT, x.store.stage); expectEq(ApprovalState.NOT_REVIEWED, x.store.state(x.id2).approval); expect(x.store.message!!.contains("NOT reverted"))
    }
    s.test("workflow: listeners are notified on transitions") {
        val x = Fixture(); var n = 0; x.store.addListener { n++ }; x.store.suggestFix(x.id1); x.store.approve(x.id1); expect(n >= 2)
    }

    s.test("spec Scenario D: approve two findings, verify one -> other stays 'Approved · Awaiting verification'; separate proposals and histories") {
        val x = Fixture(); listOf(x.id1, x.id2).forEach { x.store.suggestFix(it); x.store.approve(it) }
        x.store.runVerification(x.id1, DemoFixtureEngine(DemoFixtureEngine.Scenario.ALL_PASS))
        expect(x.store.state(x.id1).resolved); expect(!x.store.state(x.id2).resolved)
        expectEq("Approved \u00B7 Awaiting verification", x.store.state(x.id2).statusLabel()); expectEq(0, x.store.state(x.id2).runs.size); expectEq(1, x.store.state(x.id1).runs.size)
        expect(x.store.state(x.id1).proposal!!.findingId == x.id1 && x.store.state(x.id2).proposal!!.findingId == x.id2)
        expect(x.store.state(x.id1).proposal!!.summary != x.store.state(x.id2).proposal!!.summary) { "proposals must be finding-specific" }
    }
    s.test("spec Scenario F: complete batch = 28 executed; partial batch reported incomplete with exact counts") {
        val x = Fixture(); x.store.suggestFix(x.id2); x.store.approve(x.id2)
        val full = x.store.runVerification(x.id2, DemoFixtureEngine(DemoFixtureEngine.Scenario.ALL_PASS))!!.summary
        expectEq(28, full.total); expectEq(28, full.executed); expect(full.complete)
        val part = x.store.runVerification(x.id2, DemoFixtureEngine(DemoFixtureEngine.Scenario.PARTIAL))!!.summary
        expect(!part.complete); expectEq(17, part.passed); expectEq(3, part.inconclusive); expectEq(8, part.notRun); expectEq(20, part.executed)
    }
    s.test("spec Scenario E: failing engine (exception) is recorded as 28 NOT_RUN, not resolved") {
        val x = Fixture(); x.store.suggestFix(x.id2); x.store.approve(x.id2)
        val boom = object : VerificationEngine { override val name = "boom"; override fun run(finding: Finding, cases: List<MatrixCase>): List<VerificationResult> = throw IllegalStateException("device offline") }
        val run = x.store.runVerification(x.id2, boom)!!
        expectEq(28, run.summary.notRun); expect(run.results.all { it.error!!.contains("device offline") }); expect(!x.store.state(x.id2).resolved)
    }
    s.test("workflow: failed check keeps issue unresolved even after a previous pass in history") {
        val x = Fixture(); x.store.suggestFix(x.id1); x.store.approve(x.id1)
        x.store.runVerification(x.id1, DemoFixtureEngine(DemoFixtureEngine.Scenario.ALL_PASS)); expect(x.store.state(x.id1).resolved)
        x.store.runVerification(x.id1, DemoFixtureEngine(DemoFixtureEngine.Scenario.ILLUSTRATED_FAILURES))
        expect(!x.store.state(x.id1).resolved); expectEq(2, x.store.state(x.id1).runs.size)
    }

    registerUiTests(s)
}
