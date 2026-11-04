package dev.composeworkbench.ui

import dev.composeworkbench.core.*
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableModel

/**
 * Four-stage workbench UI. Each stage is a card. The stage indicator is display-only.
 * The Verify card deliberately has no "Back to Repair" control: approval is decided on the Repair screen,
 * and Verify only applies the approved patch and records verification results.
 */
class WorkbenchPanel(
    private val store: WorkbenchStoreHandle,
    private val engineFactory: () -> VerificationEngine,
    private val bannerText: String? = null
) : JPanel(BorderLayout()) {

    private val cards = CardLayout()
    private val center = JPanel(cards)
    private val stageLabels = Stage.values().associateWith { JLabel(it.label, SwingConstants.CENTER).apply { name = "stage-${it.name}"; border = BorderFactory.createEmptyBorder(6, 14, 6, 14); isOpaque = true } }
    private val messageLabel = JLabel(" ").apply { name = "message"; border = BorderFactory.createEmptyBorder(6, 10, 6, 10) }

    private val inspect = InspectCard(); private val diagnose = DiagnoseCard()
    private val repair = RepairCard(); private val verify = VerifyCard()

    val verifyCard: JComponent get() = verify
    val repairCard: JComponent get() = repair

    init {
        name = "workbench"
        val top = JPanel(BorderLayout())
        val steps = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply { name = "stage-indicator" }
        stageLabels.values.forEach { steps.add(it) }
        top.add(steps, BorderLayout.WEST)
        if (bannerText != null) top.add(JLabel(bannerText).apply { name = "demo-banner"; foreground = Color(0x8A5A00); border = BorderFactory.createEmptyBorder(0, 8, 0, 8) }, BorderLayout.CENTER)
        add(top, BorderLayout.NORTH)
        Stage.values().forEach { s -> center.add(when (s) { Stage.INSPECT -> inspect; Stage.DIAGNOSE -> diagnose; Stage.REPAIR -> repair; Stage.VERIFY -> verify }, s.name) }
        add(center, BorderLayout.CENTER)
        add(messageLabel, BorderLayout.SOUTH)
        store.addListener { refresh() }
        refresh()
    }

    fun refresh() {
        if (SwingUtilities.isEventDispatchThread()) doRefresh() else SwingUtilities.invokeLater { doRefresh() }
    }

    /** Synchronous refresh (used by tests and on the EDT). */
    fun doRefresh() {
        val cur = store.stage
        Stage.values().forEach { s ->
            val l = stageLabels.getValue(s)
            l.font = l.font.deriveFont(if (s == cur) Font.BOLD else Font.PLAIN)
            l.background = if (s == cur) UIManager.getColor("List.selectionBackground") ?: Color.LIGHT_GRAY else UIManager.getColor("Panel.background")
            l.foreground = if (s == cur) UIManager.getColor("List.selectionForeground") ?: Color.BLACK else UIManager.getColor("Label.foreground")
        }
        inspect.update(); diagnose.update(); repair.update(); verify.update()
        cards.show(center, cur.name)
        messageLabel.text = store.message ?: " "
    }

    private fun button(text: String, name: String, onClick: () -> Unit) = JButton(text).also { it.name = name; it.addActionListener { onClick() } }
    private fun mono(area: JTextArea) = area.apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12); lineWrap = false }
    private fun wrapped(area: JTextArea) = area.apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false }

    // ---------- Inspect ----------
    private inner class InspectCard : JPanel(BorderLayout(8, 8)) {
        val source = mono(JTextArea()).apply { name = "inspect-source" }
        val info = wrapped(JTextArea()).apply { name = "inspect-info" }
        init {
            name = "card-INSPECT"; border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(info, BorderLayout.NORTH); add(JScrollPane(source), BorderLayout.CENTER)
            add(button("Continue to Diagnose", "btn-to-diagnose") { store.goTo(Stage.DIAGNOSE) }.let { b -> JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(b) } }, BorderLayout.SOUTH)
        }
        fun update() {
            val f = store.selected
            info.text = "${store.findings.size} finding(s) from static analysis. Static analysis is a heuristic signal only; runtime behavior is checked in Verify. " +
                "Live UI preview is not available in this panel."
            source.text = f?.let { store.readSource(it.location.file) } ?: "(no source available)"
            source.caretPosition = 0
        }
    }

    // ---------- Diagnose ----------
    private inner class DiagnoseCard : JPanel(BorderLayout(8, 8)) {
        val model = object : DefaultTableModel(arrayOf("ID", "Severity", "Title", "Location", "Status"), 0) { override fun isCellEditable(r: Int, c: Int) = false }
        val table = JTable(model).apply { name = "findings-table"; selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION }
        val details = wrapped(JTextArea()).apply { name = "diagnose-details" }
        val suggest = button("Suggest fix", "btn-suggest-fix") { store.selectedId?.let { store.suggestFix(it) } }
        private var updating = false
        init {
            name = "card-DIAGNOSE"; border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            table.selectionModel.addListSelectionListener { e -> if (!e.valueIsAdjusting && !updating) { val r = table.selectedRow; if (r >= 0) store.select(model.getValueAt(r, 0) as String) } }
            val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), JScrollPane(details)).apply { resizeWeight = 0.45 }
            add(split, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(suggest) }, BorderLayout.SOUTH)
        }
        fun update() {
            updating = true
            model.rowCount = 0
            store.findings.forEach { f -> model.addRow(arrayOf(f.id, f.severity.name, f.title, "${File(f.location.file).name}:${f.location.line}", store.state(f.id).statusLabel())) }
            val idx = store.findings.indexOfFirst { it.id == store.selectedId }
            if (idx >= 0) table.setRowSelectionInterval(idx, idx)
            updating = false
            val f = store.selected
            details.text = if (f == null) "No findings. Static analysis found nothing to report; this does not prove the UI is accessible." else buildString {
                append("${f.id}  ${f.title}\n${f.location.file}:${f.location.line}\n\n${f.explanation}\n\nEvidence:\n")
                f.evidence.forEach { append("  [${it.kind.label}] ${it.detail}\n") }
            }
            details.caretPosition = 0
            suggest.isEnabled = f != null
        }
    }

    // ---------- Repair ----------
    private inner class RepairCard : JPanel(BorderLayout(8, 8)) {
        val header = JLabel(" ").apply { name = "repair-header"; font = font.deriveFont(Font.BOLD) }
        val text = wrapped(JTextArea()).apply { name = "repair-text" }
        val diff = mono(JTextArea()).apply { name = "repair-diff" }
        val approve = button("Approve repair", "btn-approve") {
            val id = store.selectedId ?: return@button
            val st = store.state(id)
            val p = st.proposal
            if (p == null || p.autoApplicable) {
                store.approve(id)
            } else {
                val response = askManualResolution(p)
                if (response != null) store.approve(id, response)
            }
        }
        val reject = button("Reject", "btn-reject") { store.selectedId?.let { store.reject(it) } }
        init {
            name = "card-REPAIR"; border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(header, BorderLayout.NORTH)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(text), JScrollPane(diff)).apply { resizeWeight = 0.5 }, BorderLayout.CENTER)
            val south = JPanel(BorderLayout())
            south.add(JLabel("Approving will apply the suggested fix to your source file automatically.").apply { border = BorderFactory.createEmptyBorder(0, 0, 0, 8) }, BorderLayout.CENTER)
            south.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(reject); add(approve) }, BorderLayout.EAST)
            add(south, BorderLayout.SOUTH)
        }
        fun update() {
            val f = store.selected; val st = f?.let { store.state(it.id) }; val p = st?.proposal
            header.text = if (f == null) "No finding selected" else "${f.id} \u00B7 ${st!!.statusLabel()}"
            text.text = if (f == null || p == null) "No repair has been proposed. Choose a finding in Diagnose and select \"Suggest fix\"." else buildString {
                append("Finding\n${f.id} \u2014 ${f.title}\n\nProblem\n${f.explanation}\n\nEvidence\n")
                f.evidence.forEach { append("  [${it.kind.label}] ${it.detail}\n") }
                append("\nLocation\n${f.location.file}:${f.location.line}\n\n")
                append("Proposed repair\n${p.summary}\n\nWhy this helps\n${p.rationale}\n")
                if (p.notes.isNotEmpty()) { append("\nNotes\n"); p.notes.forEach { append("  - $it\n") } }
                if (!p.autoApplicable) append("\nNo automatic patch is available; review the suggested fixes below and apply the change manually if you agree.\n")
                if (p.requiresHumanInput) append("\nThis repair needs your input after it is applied.\n")
            }
            text.caretPosition = 0

            // Show "What should be done?" with suggested fixes when no automatic patch
            if (p == null) {
                diff.text = ""
            } else if (!p.autoApplicable && p.suggestedFixes.isNotEmpty()) {
                val sb = StringBuilder("What should be done?\n\n")
                sb.append("Suggested fixes:\n\n")
                p.suggestedFixes.forEachIndexed { i, fix ->
                    sb.append("${i + 1}. ${fix.description}\n")
                    sb.append("   Before:\n     ${fix.beforeCode}\n")
                    sb.append("   After:\n     ${fix.afterCode}\n\n")
                }
                sb.append("Open the source file to edit manually.")
                diff.text = sb.toString()
            } else {
                diff.text = p.diff
            }
            diff.caretPosition = 0
            val awaiting = st?.approval == ApprovalState.AWAITING_APPROVAL
            approve.isEnabled = awaiting; reject.isEnabled = awaiting
        }
    }

    /** Asks how the user wants to proceed with a manual (no-automatic-patch) proposal.
     * Returns the user's chosen resolution, or null if cancelled. */
    private fun askManualResolution(p: Proposal): String? {
        val options = arrayOf("I'll apply the change manually", "Skip this repair for now", "Open source file to edit", "Other…")
        val choice = JOptionPane.showOptionDialog(
            this,
            "No automatic patch is available for this repair.\n\n${p.summary}\n\nHow would you like to proceed?",
            "Manual repair decision",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]
        )
        return when (choice) {
            0 -> "Applied manually by user"
            1 -> "Skipped by user"
            2 -> {
                // Open source file at the finding location
                val f = store.selected
                if (f != null) {
                    openSourceFileAt(f.location.file, f.location.line)
                }
                "Opened source file at ${f?.location?.file}:${f?.location?.line}"
            }
            3 -> {
                val input = JOptionPane.showInputDialog(
                    this,
                    "Describe how you will address this repair:",
                    "Other resolution",
                    JOptionPane.PLAIN_MESSAGE
                )
                if (input == null || input.isBlank()) null else "Other: $input"
            }
            else -> null
        }
    }

    /** Opens the source file at the given line (delegates to the IDE if available). */
    private fun openSourceFileAt(filePath: String, line: Int) {
        val file = java.io.File(filePath)
        if (file.isFile) {
            try {
                java.awt.Desktop.getDesktop().open(file)
            } catch (e: Exception) {
                // Fallback: show file path in a dialog
                JOptionPane.showMessageDialog(
                    this,
                    "Source file: $filePath\nLine: $line\n\nOpen this file in your IDE and make the manual fix.",
                    "Open Source File",
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        } else {
            JOptionPane.showMessageDialog(
                this,
                "Source file not found: $filePath\n\nPlease locate the file manually in your project.",
                "File Not Found",
                JOptionPane.WARNING_MESSAGE
            )
        }
    }

    // ---------- Verify ----------
    private inner class VerifyCard : JPanel(BorderLayout(8, 8)) {
        val header = JLabel(" ").apply { name = "verify-header"; font = font.deriveFont(Font.BOLD) }
        val status = JLabel(" ").apply { name = "verify-status" }
        val patchStatus = JLabel(" ").apply { name = "verify-patch-status" }
        val summary = JLabel(" ").apply { name = "verify-summary" }
        val engineLabel = JLabel(" ").apply { name = "verify-engine" }
        val historyLabel = JLabel(" ").apply { name = "verify-history" }
        val overview = object : DefaultTableModel(arrayOf("ID", "Status", "Patch"), 0) { override fun isCellEditable(r: Int, c: Int) = false }
        val overviewTable = JTable(overview).apply { name = "verify-overview" }
        val resultsModel = object : DefaultTableModel(arrayOf("Finding", "Target", "Font scale", "Probe", "Points (root px)", "Status", "Expected", "Observed", "Evidence / error"), 0) { override fun isCellEditable(r: Int, c: Int) = false }
        val resultsTable = JTable(resultsModel).apply {
            name = "verify-results"; autoResizeMode = JTable.AUTO_RESIZE_OFF
            intArrayOf(70, 80, 70, 150, 190, 90, 340, 300, 420).forEachIndexed { i, w -> columnModel.getColumn(i).preferredWidth = w }
            columnModel.getColumn(5).cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, foc: Boolean, r: Int, c: Int): Component {
                    val comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c)
                    if (!sel) comp.foreground = when (v) { "FAIL" -> Color(0xB00020); "INCONCLUSIVE" -> Color(0x8A5A00); "NOT_RUN" -> Color.GRAY; else -> Color.BLACK }
                    comp.font = comp.font.deriveFont(if (v == "FAIL") Font.BOLD else Font.PLAIN)
                    return comp
                }
            }
        }
        val apply = button("Apply approved patch", "btn-apply-patch") { store.selectedId?.let { store.applyApprovedPatch(it) } }
        val run = button("Run verification", "btn-run-verification") { store.selectedId?.let { store.runVerification(it, engineFactory()) } }
        val review = button("Review findings", "btn-review-findings") { store.goTo(Stage.DIAGNOSE) }
        val reset = button("Reset workflow", "btn-reset") { store.reset() }
        val reportFixApplied = button("Report fix applied", "btn-report-fix-applied") {
            store.selectedId?.let { id ->
                store.recordManualFixApplied(id, true)
            }
        }
        val reportFixIssue = button("Report fix issue", "btn-report-fix-issue") {
            store.selectedId?.let { id ->
                val input = JOptionPane.showInputDialog(
                    this,
                    "Describe the issue with the manual fix:",
                    "Report Fix Issue",
                    JOptionPane.QUESTION_MESSAGE
                )
                if (input != null && input.isNotBlank()) {
                    store.recordManualFixIssue(id, input)
                }
            }
        }
        init {
            name = "card-VERIFY"; border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            val north = JPanel(GridLayout(0, 1)).apply { add(header); add(status); add(patchStatus); add(summary); add(engineLabel); add(historyLabel) }
            add(north, BorderLayout.NORTH)
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(overviewTable), JScrollPane(resultsTable)).apply { resizeWeight = 0.25 }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(review); add(reset); add(apply); add(run); add(reportFixApplied); add(reportFixIssue) }, BorderLayout.SOUTH)
        }
        fun update() {
            val f = store.selected; val st = f?.let { store.state(it.id) }
            header.text = if (f == null) "No finding selected" else "${f.id} \u00B7 ${f.title}"
            status.text = "Status: " + (st?.statusLabel() ?: "-")
            patchStatus.text = "Patch: " + (st?.patchLabel() ?: "-") + (st?.patchMessage?.let { " \u2014 $it" } ?: "")
            val run0 = st?.lastRun
            summary.text = if (run0 == null) "Verification matrix: not run (${VerificationMatrix.cases(f ?: return).size} cases = 7 probes x 4 font scales)"
            else run0.summary.let { "Last run: ${it.executed}/${it.total} executed \u00B7 ${it.passed} passed \u00B7 ${it.failed} failed \u00B7 ${it.inconclusive} inconclusive \u00B7 ${it.notRun} not run" + (if (it.complete) "" else " \u00B7 INCOMPLETE") } +
                (run0.note?.let { " \u2014 $it" } ?: "")
            engineLabel.text = "Evidence source: " + (run0?.sources?.joinToString { it.label } ?: "none yet")
            historyLabel.text = st?.let { "Run history for this finding: ${it.runs.size} after approval, ${it.baselineRuns.size} baseline (before approval)" } ?: " "
            overview.rowCount = 0
            store.findings.forEach { x -> val s = store.state(x.id); overview.addRow(arrayOf(x.id, s.statusLabel(), s.patchLabel())) }
            resultsModel.rowCount = 0
            run0?.results?.forEach { r -> resultsModel.addRow(arrayOf(r.case.findingId, r.case.targetName, "%.1fx".format(java.util.Locale.ROOT, r.case.fontScale), r.case.probe.label, r.points.joinToString("; ") { "(%.0f, %.0f)".format(java.util.Locale.ROOT, it.x, it.y) }.ifEmpty { "-" }, r.status.name, r.expected, r.observed, listOfNotNull(r.evidence.ifBlank { null }, r.error).joinToString(" | "))) }
            val approved = st?.approval == ApprovalState.APPROVED
            apply.isEnabled = approved && st?.patch != PatchState.APPLIED
            run.isEnabled = f != null
            reportFixApplied.isEnabled = approved && st?.manualFixApplied != true
            reportFixIssue.isEnabled = approved && st?.manualFixApplied == true
        }
    }
}

private typealias File = java.io.File
