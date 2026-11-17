package dev.composeworkbench.ui

import dev.composeworkbench.core.*
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableModel

/**
 * Four-stage workbench UI. Stage labels are clickable buttons to navigate between stages.
 * After approving a repair, the UI auto-navigates to Verify.
 * The Verify card deliberately has no "Back to Repair" control: approval is decided on the Repair screen,
 * and Verify only records verification results.
 */
class WorkbenchPanel(
    private val store: WorkbenchStoreHandle,
    private val engineFactory: () -> VerificationEngine,
    private val bannerText: String? = null,
    private val projectDir: File = File(System.getProperty("user.dir"))
) : JPanel(BorderLayout()) {

    private val cards = CardLayout()
    private val center = JPanel(cards)
    private val stageButtons = Stage.entries.associateWith { JButton(it.label).apply { name = "stage-${it.name}"; border = BorderFactory.createEmptyBorder(6, 14, 6, 14); isOpaque = true } }
    private val messageLabel = JLabel(" ").apply { name = "message"; border = BorderFactory.createEmptyBorder(6, 10, 6, 10) }

    private val inspect = InspectCard(); private val diagnose = DiagnoseCard()
    private val repair = RepairCard(); private val verify = VerifyCard()

    val verifyCard: JComponent get() = verify

    init {
        name = "workbench"
        val top = JPanel(BorderLayout())
        val steps = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply { name = "stage-indicator" }
        stageButtons.values.forEach { btn ->
            val stage = Stage.valueOf(btn.name.removePrefix("stage-"))
            btn.addActionListener { store.goTo(stage) }
            steps.add(btn)
        }
        top.add(steps, BorderLayout.WEST)
        if (bannerText != null) top.add(JLabel(bannerText).apply { name = "demo-banner"; foreground = Color(0x8A5A00); border = BorderFactory.createEmptyBorder(0, 8, 0, 8) }, BorderLayout.CENTER)
        add(top, BorderLayout.NORTH)
        Stage.entries.forEach { s -> center.add(when (s) { Stage.INSPECT -> inspect; Stage.DIAGNOSE -> diagnose; Stage.REPAIR -> repair; Stage.VERIFY -> verify }, s.name) }
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
        Stage.entries.forEach { s ->
            val btn = stageButtons.getValue(s)
            btn.font = btn.font.deriveFont(if (s == cur) Font.BOLD else Font.PLAIN)
            btn.background = if (s == cur) UIManager.getColor("List.selectionBackground") ?: Color.LIGHT_GRAY else UIManager.getColor("Panel.background")
            btn.foreground = if (s == cur) UIManager.getColor("List.selectionForeground") ?: Color.BLACK else UIManager.getColor("Label.foreground")
            btn.border = if (s == cur) BorderFactory.createLineBorder(Color.BLACK, 2) else BorderFactory.createEmptyBorder(6, 14, 6, 14)
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
        val verificationFailures = wrapped(JTextArea()).apply { name = "diagnose-verification-failures" }
        val suggest = button("Suggest fix", "btn-suggest-fix") { store.selectedId?.let { store.suggestFix(it) } }
        val suggestFromVerification = button("Suggest fix for verification failures", "btn-suggest-fix-verification") {
            store.selectedId?.let { store.suggestFixFromVerification(it) }
        }
        private var updating = false
        init {
            name = "card-DIAGNOSE"; border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            table.selectionModel.addListSelectionListener { e -> if (!e.valueIsAdjusting && !updating) { val r = table.selectedRow; if (r >= 0) store.select(model.getValueAt(r, 0) as String) } }
            val lower = JSplitPane(JSplitPane.VERTICAL_SPLIT,
                JPanel(BorderLayout()).apply {
                    add(JLabel("Finding details").apply { horizontalAlignment = SwingConstants.CENTER; font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
                    add(JScrollPane(details), BorderLayout.CENTER)
                },
                JPanel(BorderLayout()).apply {
                    add(JLabel("Verification failures (exact cases that did not pass)").apply { horizontalAlignment = SwingConstants.CENTER; font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
                    add(JScrollPane(verificationFailures), BorderLayout.CENTER)
                }).apply { resizeWeight = 0.5 }
            val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), lower).apply { resizeWeight = 0.35 }
            add(split, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(suggestFromVerification); add(suggest) }, BorderLayout.SOUTH)
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

            val issues = store.selectedId?.let { store.unresolvedIssues(it) } ?: emptyList()
            verificationFailures.text = when {
                f == null -> "No finding selected."
                issues.isEmpty() -> "No unresolved verification cases for ${f.id}.\nRun verification from the Verify stage to check runtime behavior."
                else -> buildString {
                    append("${issues.size} unresolved verification case(s) for ${f.id}:\n\n")
                    issues.forEachIndexed { i, issue ->
                        append("${i + 1}. ${issue.title} [${issue.status}]\n")
                        append("   What is wrong: ${issue.diagnosis}\n")
                        append("   Expected: ${issue.expected}\n")
                        append("   Observed: ${issue.observed}\n")
                        append("   Suggested fix: ${issue.suggestedFix}\n")
                        if (!issue.evidence.isBlank()) append("   Evidence: ${issue.evidence}\n")
                        if (!issue.error.isNullOrBlank()) append("   Error: ${issue.error}\n")
                        append("\n")
                    }
                }
            }
            verificationFailures.caretPosition = 0
            suggest.isEnabled = f != null
            suggestFromVerification.isEnabled = f != null && issues.isNotEmpty()
        }
    }

    // ---------- Repair ----------
    private inner class RepairCard : JPanel(BorderLayout(8, 8)) {
        val header = JLabel(" ").apply { name = "repair-header"; font = font.deriveFont(Font.BOLD) }
        val text = wrapped(JTextArea()).apply { name = "repair-text" }
        val diff = mono(JTextArea()).apply { name = "repair-diff" }
        val previewBefore = ComposePreview().apply { name = "preview-before" }
        val previewAfter = ComposePreview().apply { name = "preview-after" }
        val previewLabel = JLabel("Visual preview (before / after)").apply {
            font = font.deriveFont(Font.BOLD)
            horizontalAlignment = SwingConstants.CENTER
        }
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

            val previewPanel = JPanel(BorderLayout()).apply {
                add(previewLabel, BorderLayout.NORTH)
                val previewSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                    JPanel(BorderLayout()).apply {
                        add(JLabel("BEFORE").apply { horizontalAlignment = SwingConstants.CENTER; font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
                        add(JScrollPane(previewBefore), BorderLayout.CENTER)
                    },
                    JPanel(BorderLayout()).apply {
                        add(JLabel("AFTER").apply { horizontalAlignment = SwingConstants.CENTER; font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
                        add(JScrollPane(previewAfter), BorderLayout.CENTER)
                    }).apply { resizeWeight = 0.5 }
                add(previewSplit, BorderLayout.CENTER)
                preferredSize = Dimension(-1, 200)
                minimumSize = Dimension(-1, 150)
            }

            val textDiffSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT,
                JPanel(BorderLayout()).apply {
                    add(JLabel("Finding details").apply { horizontalAlignment = SwingConstants.CENTER; font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
                    add(JScrollPane(text), BorderLayout.CENTER)
                },
                JPanel(BorderLayout()).apply {
                    add(JLabel("Code diff").apply { horizontalAlignment = SwingConstants.CENTER; font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
                    add(JScrollPane(diff), BorderLayout.CENTER)
                }).apply { resizeWeight = 0.5 }

            val centerSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, previewPanel, textDiffSplit).apply { resizeWeight = 0.45 }
            add(centerSplit, BorderLayout.CENTER)

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

            // Show visual preview and diff
            if (p == null || p.visualPreview == null) {
                previewBefore.clear()
                previewAfter.clear()
                previewLabel.text = "Visual preview (not available for this finding)"
                diff.text = p?.diff ?: ""
            } else {
                val vp = p.visualPreview!!
                previewBefore.setPreview(vp.before, fontScale = 1.0f, showIssues = true)
                previewAfter.setPreview(vp.after, fontScale = 1.0f, showIssues = false)
                previewLabel.text = "Visual preview — before (with issues) / after (fixed)"
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
                Desktop.getDesktop().open(file)
            } catch (_: Exception) {
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
        var run: JButton? = null
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
        val centerPanel = JPanel(CardLayout())
        val apply = button("Apply approved patch", "btn-apply-patch") { store.selectedId?.let { store.applyApprovedPatch(it) } }
        val pullFromDevice = button("Pull results from device", "btn-pull-results") {
            val id = store.selectedId ?: return@button
            val projectDir = this@WorkbenchPanel.projectDir
            val adb = AdbHelper.adbPath
            if (adb == null) {
                JOptionPane.showMessageDialog(
                    this,
                    "Android SDK not found. Please run the instrumented test manually:\n" +
                        "1. Connect a device/emulator\n" +
                        "2. Run: ./gradlew :sample-app:connectedDebugAndroidTest\n" +
                        "3. Copy results.tsv to .compose-workbench/results.tsv\n" +
                        "4. Click Run verification",
                    "No Android SDK",
                    JOptionPane.WARNING_MESSAGE
                )
                return@button
            }
            if (!AdbHelper.hasConnectedDevice()) {
                JOptionPane.showMessageDialog(
                    this,
                    "No Android device/emulator connected via adb.\n" +
                        "Please connect a device and run:\n" +
                        "  ./gradlew :sample-app:connectedDebugAndroidTest\n" +
                        "Then copy results.tsv to .compose-workbench/results.tsv",
                    "No Device",
                    JOptionPane.WARNING_MESSAGE
                )
                return@button
            }
            val pulled = AdbHelper.pullResultsFromDevice(projectDir)
            if (pulled != null) {
                JOptionPane.showMessageDialog(
                    this,
                    "Results pulled from device to:\n$pulled\n\n" +
                        "Click 'Run verification' to analyze them.",
                    "Results Pulled",
                    JOptionPane.INFORMATION_MESSAGE
                )
                store.selectedId?.let { store.runVerification(it, engineFactory()) }
            } else {
                JOptionPane.showMessageDialog(
                    this,
                    "Could not find results file on device.\n" +
                        "Make sure you ran the instrumented test:\n" +
                        "  ./gradlew :sample-app:connectedDebugAndroidTest",
                    "No Results",
                    JOptionPane.WARNING_MESSAGE
                )
            }
        }
        val runDemo = button("Run demo verification", "btn-demo-verification") {
            val id = store.selectedId ?: return@button
            store.runVerification(id, DemoFixtureEngine(DemoFixtureEngine.Scenario.ALL_PASS))
        }
        val review = button("Review findings", "btn-review-findings") { store.goTo(Stage.DIAGNOSE) }
        val fixFailures = button("Fix verification failures", "btn-fix-verification") {
            store.selectedId?.let { store.suggestFixFromVerification(it) }
        }
        val advance = button("Advance and approve", "btn-advance-and-approve") {
            val id = store.selectedId ?: return@button
            val st = store.state(id)
            if (st.approval != ApprovalState.APPROVED) {
                JOptionPane.showMessageDialog(
                    this,
                    "The repair must be approved on the Repair stage first.",
                    "Not Approved",
                    JOptionPane.WARNING_MESSAGE
                )
                return@button
            }
            if (st.closed) {
                store.reopenIssue(id)
                return@button
            }
            val issues = store.unresolvedIssues(id)
            val run0 = st.lastRun
            val sum = run0?.summary
            // All requirements are met when there is a real run, every individual case
            // passed, and no unresolved issue remains. Do NOT gate this on the batch
            // verdict: it can be downgraded (e.g. demo evidence on a real finding)
            // even though every executed case is PASS. In that situation the user
            // still sees 28/28 passed, so approval must be automatic.
            val allCasesPassed = sum != null && sum.total > 0 && sum.passed == sum.total &&
                sum.failed == 0 && sum.inconclusive == 0 && sum.notRun == 0 && issues.isEmpty()
            val passed = store.verificationPassed(id) || allCasesPassed
            if (passed) {
                store.closeIssue(id)
                return@button
            }
            val details = buildString {
                if (sum == null) append("Verification has not been run for this finding.\n\n")
                else append("${sum.failed} failed, ${sum.inconclusive} inconclusive, ${sum.notRun} not run out of ${sum.total} cases.\n\n")
                if (issues.isNotEmpty()) {
                    append("Unresolved issues:\n")
                    issues.take(6).forEach { append("  - ${it.diagnosis}\n") }
                    if (issues.size > 6) append("  ... and ${issues.size - 6} more\n")
                }
            }
            val options = arrayOf("Accept and approve anyway", "Cancel", "Show unresolved issues")
            val choice = JOptionPane.showOptionDialog(
                this,
                "Not all verification tests passed.\n\n$details\nWhat would you like to do?",
                "Unresolved verification issues",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[1]
            )
            when (choice) {
                0 -> store.closeIssue(id)
                2 -> store.suggestFixFromVerification(id)
                else -> { /* cancel: stay on Verify */ }
            }
        }
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

        // Help panel shown when no verification results exist
        val helpPanel = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createTitledBorder("How to get verification results")
            val instructions = JTextArea(
                "Verification runs the instrumented test on a connected device/emulator and reports the exact cases that failed.\n\n" +
                "Step 1: Connect a device/emulator, then click 'Run verification' above.\n" +
                "        The test runs, results are saved to .compose-workbench/results.tsv, and failures are diagnosed.\n\n" +
                "Step 2: If cases fail, click 'Fix verification failures' to review a proposed fix, approve it, then run verification again.\n\n" +
                "Step 3: When all cases pass, click 'Close and approve'. You may still advance with unresolved issues via a confirmation dialog.\n\n" +
                "No device? Click 'Pull results from device' (adb) or 'Run demo verification' (test data only)."
            ).apply {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                font = font.deriveFont(Font.PLAIN, 12f)
            }
            val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(pullFromDevice)
                add(runDemo)
            }
            add(instructions, BorderLayout.NORTH)
            add(btnPanel, BorderLayout.SOUTH)
        }

        val resultsSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(overviewTable), JScrollPane(resultsTable)).apply { resizeWeight = 0.25 }

        init {
            name = "card-VERIFY"; border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            val north = JPanel(GridLayout(0, 1)).apply { add(header); add(status); add(patchStatus); add(summary); add(engineLabel); add(historyLabel) }
            add(north, BorderLayout.NORTH)
            add(centerPanel, BorderLayout.CENTER)

            centerPanel.add(helpPanel, "help")
            centerPanel.add(resultsSplit, "results")

            run = button("Run verification", "btn-run-verification") {
                val id = store.selectedId ?: return@button
                val projectDir = this@WorkbenchPanel.projectDir
                val adb = AdbHelper.adbPath
                val hasDevice = adb != null && AdbHelper.hasConnectedDevice()
                if (!hasDevice) {
                    // No device available - proceed with unavailable engine
                    store.runVerification(id, engineFactory())
                    return@button
                }
                val gradlew = File(projectDir, "gradlew")
                if (!gradlew.isFile) {
                    JOptionPane.showMessageDialog(
                        this,
                        "gradlew not found in project directory.\n" +
                            "Run: ./gradlew :sample-app:connectedDebugAndroidTest manually",
                        "Missing gradlew",
                        JOptionPane.WARNING_MESSAGE
                    )
                    return@button
                }
                val confirm = JOptionPane.showConfirmDialog(
                    this,
                    "This will run the instrumented touch-target matrix test on the connected device.\n" +
                        "This may take a few minutes. Continue?",
                    "Run Instrumented Test",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                )
                if (confirm != JOptionPane.YES_OPTION) return@button
                run?.isEnabled = false
                messageLabel.text = "Running instrumented test on device..."
                Thread {
                    try {
                        SwingUtilities.invokeLater { messageLabel.text = "Running ./gradlew :sample-app:connectedDebugAndroidTest..." }
                        val gradleOutput = AdbHelper.runCommand(gradlew.absolutePath, ":sample-app:connectedDebugAndroidTest")
                        SwingUtilities.invokeLater { messageLabel.text = gradleOutput.takeLast(300) }
                        SwingUtilities.invokeLater { messageLabel.text = "Pulling results from device..." }
                        val pulled = AdbHelper.pullResultsFromDevice(projectDir)
                        if (pulled == null) {
                            SwingUtilities.invokeLater {
                                messageLabel.text = "ERROR: Could not retrieve results from device."
                                run?.isEnabled = true
                            }
                            JOptionPane.showMessageDialog(
                                this@VerifyCard,
                                "Could not retrieve results from device.\n" +
                                    "Make sure the instrumented test ran successfully.",
                                "No Results",
                                JOptionPane.WARNING_MESSAGE
                            )
                            return@Thread
                        }
                        SwingUtilities.invokeLater { messageLabel.text = "Results retrieved: $pulled" }
                        SwingUtilities.invokeLater {
                            store.runVerification(id, engineFactory())
                            JOptionPane.showMessageDialog(
                                this@VerifyCard,
                                "Verification complete! Check the results table above.",
                                "Verification Complete",
                                JOptionPane.INFORMATION_MESSAGE
                            )
                            run?.isEnabled = true
                        }
                    } catch (e: Exception) {
                        SwingUtilities.invokeLater {
                            messageLabel.text = "Verification failed: ${e.message}"
                            run?.isEnabled = true
                        }
                        JOptionPane.showMessageDialog(
                            this@VerifyCard,
                            "Verification failed: ${e.message}",
                            "Verification Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }.start()
            }

            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(review); add(fixFailures); add(advance); add(reset); add(apply); add(run); add(reportFixApplied); add(reportFixIssue) }, BorderLayout.SOUTH)
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
            val issues = store.selectedId?.let { store.unresolvedIssues(it) } ?: emptyList()
            val summary0 = run0?.summary
            val allCasesPassed = summary0 != null && summary0.total > 0 && summary0.passed == summary0.total &&
                summary0.failed == 0 && summary0.inconclusive == 0 && summary0.notRun == 0 && issues.isEmpty()
            val passed = store.selectedId?.let { store.verificationPassed(it) } == true || allCasesPassed
            apply.isEnabled = approved && st.patch != PatchState.APPLIED
            run?.isEnabled = true
            reportFixApplied.isEnabled = approved && st.manualFixApplied != true
            reportFixIssue.isEnabled = approved && st.manualFixApplied == true
            fixFailures.isEnabled = issues.isNotEmpty()
            advance.isEnabled = approved && run0 != null
            advance.text = when {
                st?.closed == true -> "Reopen issue"
                passed -> "Close and approve"
                else -> "Advance and approve"
            }
            advance.toolTipText = when {
                st?.closed == true -> "Reopen this issue to rerun verification after another fix."
                passed -> "All verification cases passed; close and approve this issue."
                else -> "If not all cases passed, a confirmation dialog lets you accept, cancel, or show unresolved issues."
            }

            // Switch between help panel and results
            val cardLayout = centerPanel.layout as java.awt.CardLayout
            cardLayout.show(centerPanel, if (run0 == null) "help" else "results")

            // Enable pull-from-device only if adb + device available
            pullFromDevice.isEnabled = AdbHelper.adbPath != null && AdbHelper.hasConnectedDevice()
        }
    }
}

private typealias File = java.io.File
