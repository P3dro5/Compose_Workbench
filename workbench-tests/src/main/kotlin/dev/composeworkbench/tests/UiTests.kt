package dev.composeworkbench.tests

import dev.composeworkbench.core.*
import dev.composeworkbench.ui.*
import java.awt.Component
import java.awt.Container
import javax.swing.*

private fun all(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap { all(it) } ?: emptyList())
private fun <T : Component> find(root: Component, name: String, type: Class<T>): T = all(root).filterIsInstance(type).first { it.name == name }
private fun flush() = SwingUtilities.invokeAndWait { }
private fun onEdt(block: () -> Unit) { SwingUtilities.invokeAndWait(block) }

fun registerUiTests(s: Suite) {
    fun panel(scenario: DemoFixtureEngine.Scenario = DemoFixtureEngine.Scenario.ILLUSTRATED_FAILURES): WorkbenchPanel {
        var p: WorkbenchPanel? = null; onEdt { p = WorkbenchFactory.demo(tempDir(), scenario) }; return p!!
    }
    fun click(p: Component, name: String) { onEdt { val b = find(p, name, JButton::class.java); expect(b.isEnabled) { "$name disabled" }; b.doClick() } ; flush() }
    fun label(p: Component, name: String) = find(p, name, JLabel::class.java).text

    s.test("ui: demo banner is shown and stage indicator lists four stages in order") {
        val p = panel(); expect(label(p, "demo-banner").contains("DEMO DATA"))
        expectEq(listOf("Inspect", "Diagnose", "Repair", "Verify"), Stage.values().map { label(p, "stage-${it.name}") })
    }
    s.test("ui: Verify card has NO 'Back to Repair' control (any component, any text)") {
        val p = panel()
        val texts = all(p.verifyCard).mapNotNull { (it as? javax.swing.AbstractButton)?.text ?: (it as? JLabel)?.text }
        expect(texts.none { it.contains("Back to Repair", ignoreCase = true) }) { "found: $texts" }
        expect(all(p.verifyCard).filterIsInstance<JButton>().none { it.text.contains("Repair", ignoreCase = true) && it.text.contains("Back", ignoreCase = true) })
        expectEq(listOf("Review findings", "Reset workflow", "Apply approved patch", "Run verification").sorted(), all(p.verifyCard).filterIsInstance<JButton>().filter { it.name?.startsWith("btn-") == true }.map { it.text }.sorted())
    }
    s.test("ui: stage indicator is display-only (labels, not buttons)") {
        val p = panel(); Stage.values().forEach { expect(find(p, "stage-${it.name}", JLabel::class.java).mouseListeners.isEmpty()) }
    }
    s.test("ui: Diagnose -> Suggest fix opens Repair with proposal, diff and enabled Approve/Reject") {
        val p = panel(); click(p, "btn-to-diagnose"); click(p, "btn-suggest-fix")
        val diff = find(p, "repair-diff", JTextArea::class.java).text
        expect(diff.contains("@@") && diff.contains("+")) { diff }
        expect(find(p, "btn-approve", JButton::class.java).isEnabled && find(p, "btn-reject", JButton::class.java).isEnabled)
        expect(label(p, "repair-header").contains("Awaiting approval"))
    }
    s.test("ui: spec Scenario A - approve without verification returns to Verify with awaiting-verification status") {
        val p = panel(); click(p, "btn-to-diagnose"); click(p, "btn-suggest-fix"); click(p, "btn-approve")
        expect(label(p, "verify-status").endsWith("Approved \u00B7 Awaiting verification")) { label(p, "verify-status") }
        expect(label(p, "verify-patch-status").contains("Patch not applied")); expect(label(p, "verify-summary").contains("not run"))
        expect(find(p, "btn-apply-patch", JButton::class.java).isEnabled); expect(label(p, "stage-VERIFY") == "Verify")
    }
    s.test("ui: spec Scenario C (labelled fixture) - verification failure shown, not resolved, 28 result rows") {
        val p = panel(); click(p, "btn-to-diagnose"); click(p, "btn-suggest-fix"); click(p, "btn-approve"); click(p, "btn-apply-patch"); click(p, "btn-run-verification")
        expect(label(p, "verify-patch-status").contains("Patch applied"))
        expect(label(p, "verify-status").contains("Verification failed")) { label(p, "verify-status") }
        expect(label(p, "verify-summary").contains("27 passed") && label(p, "verify-summary").contains("1 failed")) { label(p, "verify-summary") }
        expect(label(p, "verify-engine").contains("DEMO FIXTURE"))
        expectEq(28, find(p, "verify-results", JTable::class.java).rowCount)
    }
    s.test("ui: Reject returns to Diagnose and Verify never shows approval") {
        val p = panel(); click(p, "btn-to-diagnose"); click(p, "btn-suggest-fix"); click(p, "btn-reject")
        expect(label(p, "stage-DIAGNOSE") == "Diagnose"); expect(label(p, "verify-status").contains("Proposal rejected"))
    }
    s.test("ui: Reset workflow returns to Inspect") {
        val p = panel(); click(p, "btn-to-diagnose"); click(p, "btn-suggest-fix"); click(p, "btn-approve"); click(p, "btn-reset")
        expect(find(p, "message", JLabel::class.java).text.contains("Workflow reset"))
        expect(find(p, "findings-table", JTable::class.java).getValueAt(0, 4) == "Not reviewed")
    }
    s.test("ui: spec Scenario E - real mode with no results reports 'Verification not run' (no fabricated pass)") {
        val d = tempDir(); java.io.File(d, "R.kt").writeText("package x\nimport androidx.compose.ui.Modifier\nfun A(){ Box(Modifier.size(24.dp).clickable{}) }\n")
        var p: WorkbenchPanel? = null; onEdt { p = WorkbenchFactory.forProject(d) }; val pp = p!!
        expect(all(pp).none { it.name == "demo-banner" })
        click(pp, "btn-to-diagnose"); click(pp, "btn-suggest-fix"); click(pp, "btn-approve"); click(pp, "btn-run-verification")
        expect(label(pp, "verify-status").contains("Verification not run")) { label(pp, "verify-status") }
        expect(label(pp, "verify-summary").contains("28 not run"))
    }

    s.test("ui: spec Scenario F - partial batch is shown as INCOMPLETE with accurate counts; verdict not passed") {
        val p = panel(DemoFixtureEngine.Scenario.PARTIAL); click(p, "btn-to-diagnose"); click(p, "btn-suggest-fix"); click(p, "btn-approve"); click(p, "btn-run-verification")
        val t = label(p, "verify-summary"); expect(t.contains("INCOMPLETE") && t.contains("not run") && t.contains("17 passed")) { t }
        expect(label(p, "verify-status").contains("inconclusive")); expect(label(p, "verify-history").contains("1 after approval"))
        val tbl = find(p, "verify-results", JTable::class.java); expectEq(28, tbl.rowCount); expectEq(8, (0 until 28).count { tbl.getValueAt(it, 5) == "NOT_RUN" }, "NOT_RUN rows")
    }
    s.test("ui: results table shows finding id, target and coordinates for every executed case") {
        val p = panel(); click(p, "btn-to-diagnose"); click(p, "btn-suggest-fix"); click(p, "btn-approve"); click(p, "btn-run-verification")
        val tbl = find(p, "verify-results", JTable::class.java)
        expect((0 until tbl.rowCount).all { tbl.getValueAt(it, 0) == "CW-001" && tbl.getValueAt(it, 1) == "favorite" && (tbl.getValueAt(it, 4) as String).startsWith("(") })
    }

    s.test("ui: Repair screen shows finding id, explanation, evidence (heuristic + not-run), location, proposal and rationale") {
        val p = panel(); click(p, "btn-to-diagnose"); click(p, "btn-suggest-fix")
        val t = find(p, "repair-text", JTextArea::class.java).text
        for (needle in listOf("CW-001", "Problem", "Evidence", "[Heuristic]", "[Not run]", "Location", "FavoriteCard.kt", "Proposed repair", "Why this helps")) expect(t.contains(needle)) { "missing '$needle' in:\n$t" }
        expect(!find(p, "repair-diff", JTextArea::class.java).text.contains("a//"))
    }
}
