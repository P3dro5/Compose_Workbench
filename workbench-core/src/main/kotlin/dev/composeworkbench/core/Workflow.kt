package dev.composeworkbench.core

import java.io.File

enum class ProposalState { NONE, PROPOSED }
enum class ApprovalState { NOT_REVIEWED, AWAITING_APPROVAL, APPROVED, REJECTED }
enum class PatchState { NOT_APPLIED, APPLIED, FAILED }
enum class VerificationState { NOT_RUN, PASSED, FAILED, INCONCLUSIVE }

/**
 * Independent state axes per finding. Approval, patch application and verification never imply each other.
 */
data class IssueState(
    val proposalState: ProposalState = ProposalState.NONE,
    val approval: ApprovalState = ApprovalState.NOT_REVIEWED,
    val patch: PatchState = PatchState.NOT_APPLIED,
    val verification: VerificationState = VerificationState.NOT_RUN,
    val proposal: Proposal? = null,
    val patchMessage: String? = null,
    /** User's recorded response when approving a manual (no-automatic-patch) proposal. */
    val manualResolution: String? = null,
    /** Whether the user reported applying a manual fix. */
    val manualFixApplied: Boolean = false,
    /** Issue description if the manual fix caused a problem. */
    val manualFixIssue: String? = null,
    /** Runs executed while approved (count toward verification). */
    val runs: List<VerificationRun> = emptyList(),
    /** Runs executed before approval (baseline evidence, never counted toward verification). */
    val baselineRuns: List<VerificationRun> = emptyList(),
    val lastRun: VerificationRun? = null,
    /** Whether the issue was formally closed/approved after verification. */
    val closed: Boolean = false
) {
    val resolved: Boolean get() = approval == ApprovalState.APPROVED && verification == VerificationState.PASSED && patch != PatchState.FAILED
    val resolvedOnDemoEvidence: Boolean get() = resolved && lastRun?.sources?.contains(ResultSource.DEMO_FIXTURE) == true

    fun statusLabel(): String = when {
        closed && resolved -> if (resolvedOnDemoEvidence) "Closed \u00B7 Resolved (demo data)" else "Closed \u00B7 Resolved"
        closed -> "Closed \u00B7 Unresolved"
        resolved -> if (resolvedOnDemoEvidence) "Resolved (demo data)" else "Resolved"
        approval == ApprovalState.REJECTED -> "Proposal rejected"
        approval == ApprovalState.AWAITING_APPROVAL -> "Repair proposed \u00B7 Awaiting approval"
        approval == ApprovalState.APPROVED -> when {
            // A downgraded batch verdict must not hide the fact that every case passed.
            lastRun?.allCasesPassed == true ->
                if (lastRun.hasDemoEvidence) "Verification passed (demo data)" else "Verification passed"
            verification == VerificationState.FAILED -> "Verification failed"
            verification == VerificationState.INCONCLUSIVE -> "Verification inconclusive"
            verification == VerificationState.PASSED -> "Verification passed"
            lastRun != null && lastRun.verdict == Verdict.NOT_RUN -> "Verification not run"
            else -> "Approved \u00B7 Awaiting verification"
        }
        else -> "Not reviewed"
    }

    fun patchLabel(): String = when (patch) {
        PatchState.NOT_APPLIED -> "Patch not applied"
        PatchState.APPLIED -> "Patch applied"
        PatchState.FAILED -> "Patch failed"
    }
}

fun interface SourceProvider { fun read(path: String): String? }
fun interface PatchWriter { fun apply(patch: Patch): PatchResult }

/**
 * UI-independent workflow state. All transitions are explicit methods. [approve] and [reject] never touch files;
 * [applyApprovedPatch] is the only method that writes source; [runVerification] is the only one that records results.
 */
class WorkflowStore(
    val findings: List<Finding>,
    private val sources: SourceProvider = SourceProvider { p -> File(p).takeIf { it.isFile }?.readText() },
    private val writer: PatchWriter = PatchWriter { PatchApplier.applyToFile(it) }
) {
    var stage: Stage = Stage.INSPECT; private set
    var selectedId: String? = findings.firstOrNull()?.id; private set
    var message: String? = null; private set
    private var issues: Map<String, IssueState> = findings.associate { it.id to IssueState() }
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    private fun changed() = listeners.toList().forEach { it() }

    fun state(id: String): IssueState = issues[id] ?: IssueState()
    fun finding(id: String): Finding? = findings.firstOrNull { it.id == id }
    val selected: Finding? get() = selectedId?.let { finding(it) }
    private fun update(id: String, f: (IssueState) -> IssueState) { issues = issues + (id to f(state(id))) }

    fun select(id: String) { if (finding(id) != null) { selectedId = id; changed() } }
    fun goTo(s: Stage) { stage = s; changed() }

    /** Diagnose -> Repair. Generates a proposal; does not approve or modify anything. */
    fun suggestFix(id: String): Boolean {
        val f = finding(id) ?: return false
        selectedId = id
        val st = state(id)
        if (st.approval == ApprovalState.APPROVED) { stage = Stage.REPAIR; message = "Showing the approved repair for ${f.id}."; changed(); return true }
        val src = sources.read(f.location.file)
        if (src == null) { message = "Cannot read ${f.location.file}; no proposal generated."; changed(); return false }
        val p = ProposalGenerator.generate(f, src)
        update(id) { it.copy(proposalState = ProposalState.PROPOSED, approval = ApprovalState.AWAITING_APPROVAL, proposal = p) }
        stage = Stage.REPAIR
        message = "Repair proposed for ${f.id}. Nothing has been changed in your files."
        changed(); return true
    }

    /** Repair -> Verify. Records the decision and applies the patch automatically when one exists. */
    fun approve(id: String, manualResolution: String? = null): Boolean {
        val st = state(id)
        if (st.approval != ApprovalState.AWAITING_APPROVAL || st.proposal == null) { message = "There is no proposal awaiting approval for $id."; changed(); return false }
        val patch = st.proposal?.patch
        val hasAutomaticPatch = patch != null
        update(id) { it.copy(approval = ApprovalState.APPROVED, verification = VerificationState.NOT_RUN, lastRun = null, manualResolution = manualResolution, closed = false) }

        // Apply the patch automatically when one exists
        if (hasAutomaticPatch) {
            val r = writer.apply(patch)
            update(id) {
                if (r.success) it.copy(patch = PatchState.APPLIED, patchMessage = r.message, verification = VerificationState.NOT_RUN, lastRun = null)
                else it.copy(patch = PatchState.FAILED, patchMessage = r.message)
            }
            selectedId = id; stage = Stage.VERIFY
            message = if (r.success) "Repair approved and patch applied to ${patch.file}. Run verification from this screen." else "Repair approved but patch failed: ${r.message}."
            changed(); return r.success
        }

        // No automatic patch - record decision only
        selectedId = id; stage = Stage.VERIFY
        message = "Repair approved. No automatic patch available; apply the suggested fix manually, then run verification from this screen."
        changed(); return true
    }

    /** Records that the user applied a manual fix for the finding. */
    fun recordManualFixApplied(id: String, applied: Boolean): Boolean {
        val st = state(id)
        if (st.approval != ApprovalState.APPROVED) { message = "The repair for $id has not been approved."; changed(); return false }
        update(id) { it.copy(manualFixApplied = applied) }
        message = if (applied) "Manual fix application recorded for $id." else "Manual fix application cleared for $id."
        changed(); return true
    }

    /** Records an issue with a manual fix attempt. Creates a new finding-level issue note. */
    fun recordManualFixIssue(id: String, issue: String): Boolean {
        val st = state(id)
        if (st.approval != ApprovalState.APPROVED) { message = "The repair for $id has not been approved."; changed(); return false }
        update(id) { it.copy(manualFixIssue = issue) }
        message = "Manual fix issue recorded for $id: $issue"
        changed(); return true
    }

    /** Repair -> Diagnose. Never modifies files. */
    fun reject(id: String): Boolean {
        val st = state(id)
        if (st.approval != ApprovalState.AWAITING_APPROVAL) { message = "There is no proposal awaiting approval for $id."; changed(); return false }
        update(id) { it.copy(approval = ApprovalState.REJECTED) }
        selectedId = id; stage = Stage.DIAGNOSE
        message = "Proposal rejected. No file was modified."
        changed(); return true
    }

    /** Writes the approved patch. Requires prior approval. Resets verification because the code under test changed. */
    fun applyApprovedPatch(id: String): PatchResult {
        val st = state(id)
        if (st.approval != ApprovalState.APPROVED) return PatchResult(false, "The repair for $id has not been approved.").also { message = it.message; changed() }
        if (st.patch == PatchState.APPLIED) return PatchResult(false, "The patch for $id is already applied.").also { message = it.message; changed() }
        val patch = st.proposal?.patch
        if (patch == null) {
            update(id) { it.copy(patch = PatchState.FAILED, patchMessage = "This proposal has no automatic patch; apply it manually.") }
            message = "This proposal has no automatic patch; apply it manually."; changed()
            return PatchResult(false, message!!)
        }
        val r = writer.apply(patch)
        update(id) {
            if (r.success) it.copy(patch = PatchState.APPLIED, patchMessage = r.message, verification = VerificationState.NOT_RUN, lastRun = null)
            else it.copy(patch = PatchState.FAILED, patchMessage = r.message)
        }
        message = r.message; changed(); return r
    }

    /** Runs the full 28-case matrix through [engine] and records the outcome. Missing cases are recorded as NOT_RUN. */
    fun runVerification(id: String, engine: VerificationEngine): VerificationRun? {
        val f = finding(id) ?: return null
        val cases = VerificationMatrix.cases(f)
        val raw = try { engine.run(f, cases) } catch (e: Exception) {
            cases.map { notRun(it, "Engine '${engine.name}' failed: ${e.message}") }
        }
        val byKey = raw.associateBy { it.case.key }
        val results = cases.map { c -> byKey[c.key]?.copy(case = c) ?: notRun(c, "The engine returned no result for this case.") }
        val summary = BatchSummary.of(results, cases.size)
        val st = state(id)
        val approved = st.approval == ApprovalState.APPROVED
        var verdict = summary.verdict
        var note: String? = null
        val demoSource = results.any { it.source == ResultSource.DEMO_FIXTURE }
        if (demoSource && !f.isDemo && verdict == Verdict.PASSED) {
            verdict = Verdict.INCONCLUSIVE
            note = "Demo fixture output cannot verify a real finding; result downgraded to inconclusive."
        }
        if (!approved) note = (note?.plus(" ") ?: "") + "Recorded as baseline evidence: verification only counts after the repair is approved."
        val run = VerificationRun(id, engine.name, results, summary, verdict, approved, note)
        update(id) {
            val base = if (approved) it.copy(
                verification = when (verdict) { Verdict.PASSED -> VerificationState.PASSED; Verdict.FAILED -> VerificationState.FAILED
                    Verdict.INCONCLUSIVE -> VerificationState.INCONCLUSIVE; Verdict.NOT_RUN -> VerificationState.NOT_RUN },
                runs = it.runs + run, lastRun = run)
            else it.copy(baselineRuns = it.baselineRuns + run, lastRun = null)
            base
        }
        selectedId = id; stage = Stage.VERIFY
        message = "Verification ${verdict.name.lowercase().replace('_', ' ')}: ${summary.passed} passed, ${summary.failed} failed, ${summary.inconclusive} inconclusive, ${summary.notRun} not run of ${summary.total}."
        changed(); return run
    }

    /** Actionable diagnoses derived from the most recent verification run. Empty if nothing failed. */
    fun unresolvedIssues(id: String): List<UnresolvedIssue> =
        state(id).lastRun?.let { VerificationDiagnosis.of(it) } ?: emptyList()

    /**
     * True if the most recent run passed every individual case (no fails, no inconclusive, no
     * not-run). This intentionally does not rely on the batch verdict: that verdict can be
     * downgraded for evidence-quality reasons (e.g. demo fixture data on a real finding) even
     * when every executed case passed. Keeping this check in the core prevents the UI and the
     * workflow state from disagreeing about whether an issue may be closed.
     */
    fun verificationPassed(id: String): Boolean = state(id).lastRun?.allCasesPassed == true

    /** Number of cases in the current finding's verification matrix. */
    fun matrixSize(id: String): Int = finding(id)?.let { VerificationMatrix.cases(it).size } ?: 0

    /**
     * Formally closes/approves an issue after verification. Requires the repair to be approved first.
     * Closing is allowed even when verification failed; the UI must warn the user before calling this.
     */
    fun closeIssue(id: String): Boolean {
        val st = state(id)
        if (st.approval != ApprovalState.APPROVED) {
            message = "The repair for $id must be approved before the issue can be closed."
            changed(); return false
        }
        // Reconcile the stored verification axis with the individual case results. A run can have a
        // downgraded batch verdict (e.g. demo evidence on a real finding) while every case passed;
        // closing such a run must finalize as resolved, not leave a stale "inconclusive" status.
        val allPassed = verificationPassed(id)
        val demoEvidence = st.lastRun?.hasDemoEvidence == true
        update(id) {
            it.copy(
                closed = true,
                verification = if (allPassed) VerificationState.PASSED else it.verification
            )
        }
        message = "Issue $id closed and approved" + when {
            allPassed && demoEvidence -> " (reported as resolved using demo data; rerun on a device for real verification)."
            allPassed -> "."
            else -> " despite unresolved verification issues."
        }
        changed(); return true
    }

    /** Reopens a closed issue so verification can be rerun after a further fix. */
    fun reopenIssue(id: String): Boolean {
        update(id) { it.copy(closed = false) }
        message = "Issue $id reopened."
        changed(); return true
    }

    /**
     * Generates a repair proposal informed by failing verification cases and navigates to Repair.
     * The base code fix comes from [ProposalGenerator]; verification details are added so the user
     * can see exactly which cases motivated the repair. Nothing is written until approval.
     */
    fun suggestFixFromVerification(id: String): Boolean {
        val f = finding(id) ?: return false
        val issues = unresolvedIssues(id)
        if (issues.isEmpty()) {
            message = "There are no unresolved verification issues for $id."
            changed(); return false
        }
        val src = sources.read(f.location.file)
        if (src == null) {
            message = "Cannot read ${f.location.file}; no verification-driven proposal generated."
            changed(); return false
        }
        val base = ProposalGenerator.generate(f, src)
        val enhanced = base.copy(
            summary = "Fix ${issues.size} failing verification case(s) for '${f.targetName}'",
            explanation = base.explanation + "\n\nVerification failures that motivated this repair:\n" +
                issues.joinToString("\n") { "  - ${it.diagnosis}" },
            rationale = base.rationale + " This repair specifically targets the failing verification cases listed above.",
            notes = base.notes + listOf(
                "After applying, run verification again to confirm the ${issues.size} failing case(s) now pass.",
                "Unresolved cases: " + issues.joinToString(", ") { it.case.key }
            ),
            verificationScenarioIds = (base.verificationScenarioIds + issues.map { it.case.key }).distinct()
        )
        update(id) {
            it.copy(
                proposalState = ProposalState.PROPOSED,
                approval = ApprovalState.AWAITING_APPROVAL,
                proposal = enhanced,
                closed = false
            )
        }
        selectedId = id; stage = Stage.REPAIR
        message = "Repair proposed for ${f.id} based on ${issues.size} verification failure(s). Review, approve, then rerun verification."
        changed(); return true
    }

    /** Resets workflow state. Source files already modified by the workbench are NOT reverted. */
    fun reset() {
        val patched = issues.values.count { it.patch == PatchState.APPLIED }
        issues = findings.associate { it.id to IssueState() }
        stage = Stage.INSPECT; selectedId = findings.firstOrNull()?.id
        message = "Workflow reset." + if (patched > 0) " $patched applied patch(es) were NOT reverted in your source files." else ""
        changed()
    }
}
