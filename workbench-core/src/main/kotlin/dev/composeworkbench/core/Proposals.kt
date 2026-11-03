package dev.composeworkbench.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One text edit. [before]/[after] are context used to re-locate the edit if the file shifted. */
data class Edit(val start: Int, val end: Int, val original: String, val replacement: String, val before: String, val after: String)

/** [guards]: texts that must each still occur exactly once in the file, proving the targeted code was not changed. */
data class Patch(val file: String, val edits: List<Edit>, val guards: List<String> = emptyList())

data class Proposal(
    val findingId: String,
    val summary: String,
    val explanation: String,
    val rationale: String,
    /** Null when no reliable automatic patch exists; the proposal is then manual-review only. */
    val patch: Patch?,
    val diff: String,
    val requiresHumanInput: Boolean,
    val notes: List<String>
) {
    val autoApplicable get() = patch != null
}

data class PatchResult(val success: Boolean, val message: String, val newText: String? = null)

object PatchApplier {
    private fun countOf(src: String, needle: String): Int { var n = 0; var i = src.indexOf(needle); while (i >= 0) { n++; i = src.indexOf(needle, i + 1) }; return n }

    /** Tier 0: exact offset with full context. Then unique relocation by before+original+after, before+original, original+after. */
    private fun locate(src: String, e: Edit): Int? {
        val atOffset = e.start >= 0 && e.start + e.original.length <= src.length &&
            src.regionMatches(e.start, e.original, 0, e.original.length) &&
            src.substring(maxOf(0, e.start - e.before.length), e.start) == e.before &&
            src.substring(e.start + e.original.length, minOf(src.length, e.start + e.original.length + e.after.length)) == e.after
        if (atOffset) return e.start
        val tiers = listOf(
            (e.before + e.original + e.after) to e.before.length,
            (e.before + e.original) to e.before.length,
            (e.original + e.after) to 0)
        for ((needle, off) in tiers) {
            if (needle.isEmpty() || (needle == e.original && e.original.isEmpty())) continue
            if (needle.length < 12) continue
            val n = countOf(src, needle)
            if (n == 1) return src.indexOf(needle) + off
            if (n > 1) return null
        }
        return null
    }

    /** Pure text application. Fails (success=false) if any edit cannot be located unambiguously. */
    fun applyToText(src: String, patch: Patch): PatchResult {
        for (g in patch.guards) if (countOf(src, g) != 1)
            return PatchResult(false, "The code this repair targets was modified (or is no longer unique) since the proposal was made. No file was modified.")
        val located = mutableListOf<Pair<Int, Edit>>()
        for (e in patch.edits) {
            val at = locate(src, e) ?: return PatchResult(false,
                "Could not locate the code to change (it was modified or is ambiguous): `${e.original.take(60).ifEmpty { e.before.takeLast(30) }}`. No file was modified.")
            located += at to e
        }
        val sorted = located.sortedBy { it.first }
        for (i in 1 until sorted.size) if (sorted[i].first < sorted[i - 1].first + sorted[i - 1].second.original.length)
            return PatchResult(false, "Patch edits overlap. No file was modified.")
        val sb = StringBuilder(src)
        for ((at, e) in sorted.asReversed()) sb.replace(at, at + e.original.length, e.replacement)
        return PatchResult(true, "Patch applied to text.", sb.toString())
    }

    /** Applies the patch to disk atomically-where-possible. On failure the file is left byte-for-byte unchanged. */
    fun applyToFile(patch: Patch): PatchResult {
        val file = File(patch.file)
        if (!file.isFile) return PatchResult(false, "Source file not found: ${patch.file}. No file was modified.")
        val text = try { file.readText(Charsets.UTF_8) } catch (e: Exception) {
            return PatchResult(false, "Could not read ${file.name}: ${e.message}. No file was modified.")
        }
        val r = applyToText(text, patch)
        if (!r.success) return r
        return try {
            val tmp = File.createTempFile("cw-patch-", ".tmp", file.absoluteFile.parentFile)
            tmp.writeText(r.newText!!, Charsets.UTF_8)
            try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (e: Exception) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            PatchResult(true, "Patch written to ${file.name}.", r.newText)
        } catch (e: Exception) {
            PatchResult(false, "Writing ${file.name} failed: ${e.message}. The file may be unchanged; re-check it before continuing.")
        }
    }
}

/** Minimal line-based unified diff (LCS) with 2 lines of context. */
object DiffRenderer {
    fun unified(path: String, old: String, new: String, context: Int = 2): String {
        val a = old.split("\n"); val b = new.split("\n")
        if (a == b) return "(no changes)"
        val ops = diffOps(a, b)
        val rel = path.replace('\\', '/').trimStart('/')
        val sb = StringBuilder("--- a/$rel\n+++ b/$rel\n")
        val changed = ops.indices.filter { ops[it].first != ' ' }
        var i = 0
        while (i < changed.size) {
            var lo = maxOf(0, changed[i] - context); var hi = minOf(ops.size - 1, changed[i] + context)
            var j = i
            while (j + 1 < changed.size && changed[j + 1] - context <= hi + 1) { j++; hi = minOf(ops.size - 1, changed[j] + context) }
            var aStart = 1; var bStart = 1
            for (k in 0 until lo) { if (ops[k].first != '+') aStart++; if (ops[k].first != '-') bStart++ }
            val slice = ops.subList(lo, hi + 1)
            val aLen = slice.count { it.first != '+' }; val bLen = slice.count { it.first != '-' }
            sb.append("@@ -$aStart,$aLen +$bStart,$bLen @@\n")
            for ((c, line) in slice) sb.append(c).append(line).append('\n')
            i = j + 1
        }
        return sb.toString().trimEnd('\n')
    }

    private fun diffOps(a: List<String>, b: List<String>): List<Pair<Char, String>> {
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val am = a.subList(p, a.size - s); val bm = b.subList(p, b.size - s)
        val ops = mutableListOf<Pair<Char, String>>()
        for (k in 0 until p) ops += ' ' to a[k]
        if (am.size.toLong() * bm.size <= 4_000_000L) {
            val l = Array(am.size + 1) { IntArray(bm.size + 1) }
            for (i in am.size - 1 downTo 0) for (j in bm.size - 1 downTo 0)
                l[i][j] = if (am[i] == bm[j]) l[i + 1][j + 1] + 1 else maxOf(l[i + 1][j], l[i][j + 1])
            var i = 0; var j = 0
            while (i < am.size && j < bm.size) {
                when {
                    am[i] == bm[j] -> { ops += ' ' to am[i]; i++; j++ }
                    l[i + 1][j] >= l[i][j + 1] -> { ops += '-' to am[i]; i++ }
                    else -> { ops += '+' to bm[j]; j++ }
                }
            }
            while (i < am.size) ops += '-' to am[i++]
            while (j < bm.size) ops += '+' to bm[j++]
        } else { am.forEach { ops += '-' to it }; bm.forEach { ops += '+' to it } }
        for (k in a.size - s until a.size) ops += ' ' to a[k]
        return ops
    }
}

/** Produces a repair proposal specific to one finding. Never writes any file. */
object ProposalGenerator {
    private const val M3_IMPORT = "androidx.compose.material3.minimumInteractiveComponentSize"
    private const val PADDING_IMPORT = "androidx.compose.foundation.layout.padding"

    private fun edit(src: String, start: Int, end: Int, replacement: String) = Edit(
        start, end, src.substring(start, end), replacement,
        src.substring(maxOf(0, start - 40), start), src.substring(end, minOf(src.length, end + 40)))

    private fun importEdit(src: String, fq: String): Edit? {
        val pkg = fq.substringBeforeLast('.')
        if (Regex("""(?m)^import\s+${Regex.escape(fq)}\s*$""").containsMatchIn(src) ||
            Regex("""(?m)^import\s+${Regex.escape(pkg)}\.\*\s*$""").containsMatchIn(src)) return null
        val lastImport = Regex("""(?m)^import .*$""").findAll(src).lastOrNull()
        val pkgLine = Regex("""(?m)^package .*$""").find(src)
        return when {
            lastImport != null -> edit(src, lastImport.range.last + 1, lastImport.range.last + 1, "\nimport $fq")
            pkgLine != null -> edit(src, pkgLine.range.last + 1, pkgLine.range.last + 1, "\n\nimport $fq")
            else -> edit(src, 0, 0, "import $fq\n\n")
        }
    }

    private fun manual(f: Finding, summary: String, why: String, rationale: String, notes: List<String>) = Proposal(
        f.id, summary, f.explanation, rationale, null, "(no automatic patch — see notes)", false, notes + why)

    fun generate(f: Finding, source: String): Proposal {
        val code = SourceAnalyzer.blankComments(source)
        val chain = if (f.chainStart >= 0) SourceAnalyzer.parseChain(code, f.chainStart) else null
        val iCall = chain?.calls?.firstOrNull { it.name in SourceAnalyzer.INTERACTIVE }

        fun finish(summary: String, rationale: String, edits: List<Edit>, human: Boolean, notes: List<String>, guards: List<String> = emptyList()): Proposal {
            val patch = Patch(f.location.file, edits, guards)
            val r = PatchApplier.applyToText(source, patch)
            return if (!r.success) manual(f, summary, "The patch could not be generated reliably against the current source: ${r.message}", rationale, notes)
            else Proposal(f.id, summary, f.explanation, rationale, patch, DiffRenderer.unified(f.location.file, source, r.newText!!), human, notes)
        }

        return when (f.kind) {
            FindingKind.SMALL_TOUCH_TARGET -> {
                if (chain == null || iCall == null) return manual(f, "Enlarge the touch target of '${f.targetName}'",
                    "The modifier chain at line ${f.location.line} no longer matches the finding.",
                    "Use minimumInteractiveComponentSize so the interactive region is at least 48x48dp.", emptyList())
                val edits = listOfNotNull(edit(source, chain.tokenEnd, chain.tokenEnd, ".minimumInteractiveComponentSize()"), importEdit(source, M3_IMPORT))
                finish("Enlarge the touch target of '${f.targetName}' to at least 48x48dp",
                    "`minimumInteractiveComponentSize()` placed first in the chain makes the layout and hit-test area at least 48dp, " +
                        "even though the visible content stays ${f.snippet.trim()}. This matches the 48x48dp guideline without changing the icon artwork.",
                    edits, false,
                    listOf("Assumes Material 3. With Material 2 use androidx.compose.material.minimumInteractiveComponentSize (newer versions only).",
                        "The element's layout bounds can grow to 48dp; review spacing next to it.",
                        "Adding the modifier does not prove the target is correct: run verification after applying."),
                    listOf(source.substring(chain.start, chain.end)))
            }
            FindingKind.CLICKABLE_BEFORE_PADDING -> {
                val idx = chain?.calls?.indexOfFirst { it.name in SourceAnalyzer.INTERACTIVE } ?: -1
                val pad = if (idx > 0) chain!!.calls[idx - 1] else null
                if (chain == null || pad == null || pad.name != "padding") return manual(f, "Reorder padding and clickable on '${f.targetName}'",
                    "`padding` is not directly before the interactive modifier, so reordering would also move other modifiers.",
                    "Place `.padding(...)` after the interactive modifier so the padded area is clickable.",
                    listOf("Move the `.padding(...)` call to just after `.${chain?.calls?.getOrNull(idx)?.name ?: "clickable"}` by hand and review the visual result."))
                val c = chain.calls[idx]
                val gap = source.substring(pad.end, c.start)
                val original = source.substring(pad.start, c.end)
                finish("Make the padding of '${f.targetName}' part of its clickable region",
                    "Swapping the order so the interactive modifier comes first means the padding is applied inside the clickable area, enlarging the tap region without changing layout size.",
                    listOf(edit(source, pad.start, c.end, source.substring(c.start, c.end) + gap + source.substring(pad.start, pad.end))), false,
                    listOf("Visual layout is unchanged, but the ripple/click area now includes the padding.",
                        "If the padding was a deliberate outer margin, reject this proposal."))
                    .also { check(original.isNotEmpty()) }
            }
            FindingKind.ADJACENT_CLICKABLES -> {
                if (chain == null) return manual(f, "Separate '${f.targetName}' from its neighbour",
                    "The modifier chain at line ${f.location.line} no longer matches the finding.",
                    "Add spacing so the touch regions do not meet.", emptyList())
                val edits = listOfNotNull(edit(source, chain.tokenEnd, chain.tokenEnd, ".padding(horizontal = 8.dp)"), importEdit(source, PADDING_IMPORT))
                finish("Add spacing between '${f.targetName}' and the adjacent interactive target",
                    "An outer 8dp horizontal margin keeps the two click regions from touching, so a tap on the shared edge is not ambiguous.",
                    edits, false,
                    listOf("This shifts '${f.targetName}' by 8dp on each side; alternatively use Arrangement.spacedBy(8.dp) on the parent Row.",
                        "Spacing alone does not guarantee each target is >=48dp: run verification and consider the touch-target finding as well."),
                    listOf(source.substring(chain.start, chain.end)))
            }
            FindingKind.MISSING_CONTENT_DESCRIPTION -> {
                if (source.substring(f.location.startOffset.coerceAtMost(source.length), f.location.endOffset.coerceAtMost(source.length)) != f.snippet)
                    return manual(f, "Add a content description to '${f.targetName}'", "The code at line ${f.location.line} no longer matches the finding.",
                        "Give the control an accessible name.", emptyList())
                finish("Add a content description to '${f.targetName}'",
                    "A clickable element needs an accessible name so TalkBack can announce its action.",
                    listOf(edit(source, f.location.startOffset, f.location.endOffset, "contentDescription = \"TODO: describe this action\"")), true,
                    listOf("The inserted text is a placeholder. Replace it with a localized string resource (stringResource(...)) before shipping.",
                        "Approving this proposal does not make the control accessible until the placeholder is replaced."))
            }
        }
    }
}
