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
    /** Runs executed while approved (count toward verification). */
    val runs: List<VerificationRun> = emptyList(),
    /** Runs executed before approval (baseline evidence, never counted toward verification). */
    val baselineRuns: List<VerificationRun> = emptyList(),
    val lastRun: VerificationRun? = null
) {
    val resolved: Boolean get() = approval == ApprovalState.APPROVED && verification == VerificationState.PASSED && patch != PatchState.FAILED
    val resolvedOnDemoEvidence: Boolean get() = resolved && lastRun?.sources?.contains(ResultSource.DEMO_FIXTURE) == true

    fun statusLabel(): String = when {
        resolved -> if (resolvedOnDemoEvidence) "Resolved (demo data)" else "Resolved"
        approval == ApprovalState.REJECTED -> "Proposal rejected"
        approval == ApprovalState.AWAITING_APPROVAL -> "Repair proposed \u00B7 Awaiting approval"
        approval == ApprovalState.APPROVED -> when {
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

    /** Repair -> Verify. Records the decision only; does not apply the patch and does not mark anything verified. */
    fun approve(id: String): Boolean {
        val st = state(id)
        if (st.approval != ApprovalState.AWAITING_APPROVAL || st.proposal == null) { message = "There is no proposal awaiting approval for $id."; changed(); return false }
        update(id) { it.copy(approval = ApprovalState.APPROVED, verification = VerificationState.NOT_RUN, lastRun = null) }
        selectedId = id; stage = Stage.VERIFY
        message = "Repair approved. No file was modified; apply the patch and run verification from this screen."
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

    /** Resets workflow state. Source files already modified by the workbench are NOT reverted. */
    fun reset() {
        val patched = issues.values.count { it.patch == PatchState.APPLIED }
        issues = findings.associate { it.id to IssueState() }
        stage = Stage.INSPECT; selectedId = findings.firstOrNull()?.id
        message = "Workflow reset." + if (patched > 0) " $patched applied patch(es) were NOT reverted in your source files." else ""
        changed()
    }
}
