package dev.composeworkbench.ui

import dev.composeworkbench.core.*
import java.io.File

/** Thin facade over [WorkflowStore] so the UI also reads source text through the same provider. */
class WorkbenchStoreHandle(private val delegate: WorkflowStore, private val sources: SourceProvider = SourceProvider { p -> File(p).takeIf { it.isFile }?.readText() }) {
    val findings get() = delegate.findings
    val stage get() = delegate.stage
    val selectedId get() = delegate.selectedId
    val selected get() = delegate.selected
    val message get() = delegate.message
    fun state(id: String) = delegate.state(id)
    fun select(id: String) = delegate.select(id)
    fun goTo(s: Stage) = delegate.goTo(s)
    fun suggestFix(id: String) = delegate.suggestFix(id)
    fun approve(id: String) = delegate.approve(id)
    fun reject(id: String) = delegate.reject(id)
    fun applyApprovedPatch(id: String) = delegate.applyApprovedPatch(id)
    fun runVerification(id: String, e: VerificationEngine) = delegate.runVerification(id, e)
    fun reset() = delegate.reset()
    fun addListener(l: () -> Unit) = delegate.addListener(l)
    fun readSource(path: String): String? = sources.read(path)
}
