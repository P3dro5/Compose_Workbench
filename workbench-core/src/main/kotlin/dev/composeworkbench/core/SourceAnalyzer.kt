package dev.composeworkbench.core

import java.io.File

/**
 * Heuristic static analysis of Compose source. Produces HEURISTIC evidence only:
 * it is a diagnostic signal, never proof of effective runtime touch bounds.
 */
object SourceAnalyzer {
    val INTERACTIVE = setOf("clickable", "combinedClickable", "toggleable", "selectable", "triStateToggleable")
    private val SIZE_CALLS = setOf("size", "width", "height", "requiredSize", "requiredWidth", "requiredHeight")
    const val MIN_TARGET_DP = 48.0

    data class ModCall(val name: String, val args: String, val start: Int, val end: Int)
    data class Chain(val start: Int, val tokenEnd: Int, val end: Int, val calls: List<ModCall>)

    private val DP = Regex("""(\d+(?:\.\d+)?)\s*\.dp""")

    // ---- Visual preview generation ----

    private fun visualPreviewFor(kind: FindingKind, targetName: String, dp: Float?, colorHex: String?, hasContentDescription: Boolean): VisualPreview {
        val beforeIssues = mutableListOf<PreviewIssue>()
        val afterIssues = mutableListOf<PreviewIssue>()

        when (kind) {
            FindingKind.SMALL_TOUCH_TARGET -> {
                val sizeDp = dp ?: 24.0f
                beforeIssues.add(PreviewIssue(PreviewIssueType.SMALL_TARGET,
                    "Target is ${sizeDp.toInt()}dp, below 48dp guideline", PreviewSeverity.HIGH))
                beforeIssues.add(PreviewIssue(PreviewIssueType.MISSING_LABEL,
                    "No accessible label", PreviewSeverity.MEDIUM))
                afterIssues.add(PreviewIssue(PreviewIssueType.SMALL_TARGET,
                    "Target now meets 48dp guideline", PreviewSeverity.LOW))
            }
            FindingKind.CLICKABLE_BEFORE_PADDING -> {
                beforeIssues.add(PreviewIssue(PreviewIssueType.SMALL_TARGET,
                    "Padding outside clickable region", PreviewSeverity.MEDIUM))
                afterIssues.add(PreviewIssue(PreviewIssueType.SMALL_TARGET,
                    "Padding inside clickable region", PreviewSeverity.LOW))
            }
            FindingKind.ADJACENT_CLICKABLES -> {
                beforeIssues.add(PreviewIssue(PreviewIssueType.OVERFLOW,
                    "Touch regions may overlap", PreviewSeverity.MEDIUM))
                afterIssues.add(PreviewIssue(PreviewIssueType.OVERFLOW,
                    "Touch regions separated", PreviewSeverity.LOW))
            }
            FindingKind.MISSING_CONTENT_DESCRIPTION -> {
                beforeIssues.add(PreviewIssue(PreviewIssueType.MISSING_LABEL,
                    "No content description - screen readers cannot announce", PreviewSeverity.HIGH))
                afterIssues.add(PreviewIssue(PreviewIssueType.MISSING_LABEL,
                    "Content description added", PreviewSeverity.LOW))
            }
            FindingKind.TEXT_CLIPPING -> {
                beforeIssues.add(PreviewIssue(PreviewIssueType.CLIPPING,
                    "Text may clip at larger font scales", PreviewSeverity.MEDIUM))
                afterIssues.add(PreviewIssue(PreviewIssueType.CLIPPING,
                    "Text constrained with maxLines/overflow", PreviewSeverity.LOW))
            }
            FindingKind.LAYOUT_OVERFLOW -> {
                beforeIssues.add(PreviewIssue(PreviewIssueType.OVERFLOW,
                    "Fixed width may overflow narrow screens", PreviewSeverity.MEDIUM))
                afterIssues.add(PreviewIssue(PreviewIssueType.OVERFLOW,
                    "Adaptive width constraint", PreviewSeverity.LOW))
            }
            FindingKind.COLOR_STATE_VISIBILITY -> {
                beforeIssues.add(PreviewIssue(PreviewIssueType.POOR_CONTRAST,
                    "Hardcoded color may fail contrast in themes", PreviewSeverity.LOW))
                afterIssues.add(PreviewIssue(PreviewIssueType.POOR_CONTRAST,
                    "Theme token adapts to light/dark", PreviewSeverity.LOW))
            }
        }

        val before = ComponentPreview(
            componentName = targetName,
            widthDp = dp?.coerceAtLeast(24.0f) ?: 24.0f,
            heightDp = dp?.coerceAtLeast(24.0f) ?: 24.0f,
            isInteractive = true,
            hasContentDescription = hasContentDescription,
            colorHex = colorHex,
            issues = beforeIssues
        )

        val afterDp = when (kind) {
            FindingKind.SMALL_TOUCH_TARGET -> 48.0f
            else -> dp?.coerceAtLeast(24.0f) ?: 24.0f
        }

        val after = ComponentPreview(
            componentName = targetName,
            widthDp = afterDp,
            heightDp = afterDp,
            isInteractive = true,
            hasContentDescription = true,
            colorHex = when {
                kind == FindingKind.COLOR_STATE_VISIBILITY -> null
                else -> colorHex
            },
            issues = afterIssues
        )

        return VisualPreview(before = before, after = after)
    }

    fun lineOf(src: String, offset: Int): Int {
        var l = 1
        for (i in 0 until minOf(offset, src.length)) if (src[i] == '\n') l++
        return l
    }

    /** Replace comments with spaces (same length, newlines kept) so offsets stay valid. */
    fun blankComments(src: String): String {
        val sb = StringBuilder(src)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                src.startsWith("\"\"\"", i) -> { val e = src.indexOf("\"\"\"", i + 3); i = if (e < 0) src.length else e + 3 }
                c == '"' -> { i++; while (i < src.length && src[i] != '"' && src[i] != '\n') { if (src[i] == '\\') i++; i++ }; i++ }
                c == '\'' && i + 2 < src.length && src[i + 2] == '\'' -> i += 3
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') { sb.setCharAt(i, ' '); i++ }
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    val e = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                    for (k in i until e) if (src[k] != '\n') sb.setCharAt(k, ' ')
                    i = e
                }
                else -> i++
            }
        }
        return sb.toString()
    }

    /** Index of the bracket matching the one at [open], or -1. Skips string literals. */
    fun matchClose(src: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < src.length) {
            val c = src[i]
            when {
                src.startsWith("\"\"\"", i) -> { val e = src.indexOf("\"\"\"", i + 3); if (e < 0) return -1; i = e + 3; continue }
                c == '"' -> { i++; while (i < src.length && src[i] != '"') { if (src[i] == '\\') i++; i++ } }
                c == '\'' && i + 2 < src.length && src[i + 2] == '\'' -> { i += 3; continue }
                c == '(' || c == '{' || c == '[' -> depth++
                c == ')' || c == '}' || c == ']' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    /** Parse a modifier chain beginning at the `Modifier`/`modifier` token at [start] (use comment-blanked source). */
    fun parseChain(src: String, start: Int): Chain? {
        val m = Regex("""(Modifier|modifier)\b""").matchAt(src, start) ?: return null
        var pos = m.range.last + 1
        val tokenEnd = pos
        val calls = mutableListOf<ModCall>()
        var end = pos
        while (true) {
            var p = pos
            while (p < src.length && src[p].isWhitespace()) p++
            if (p >= src.length || src[p] != '.') break
            var q = p + 1
            while (q < src.length && src[q].isWhitespace()) q++
            val idStart = q
            while (q < src.length && (src[q].isLetterOrDigit() || src[q] == '_')) q++
            if (q == idStart) break
            val name = src.substring(idStart, q)
            var callEnd = q
            var args = ""
            var r = q
            if (r < src.length && src[r] == '(') {
                val c = matchClose(src, r); if (c < 0) break
                args = src.substring(r + 1, c); callEnd = c + 1; r = callEnd
            }
            var s = r
            while (s < src.length && (src[s] == ' ' || src[s] == '\t')) s++
            if (s < src.length && src[s] == '{') {
                val c = matchClose(src, s); if (c < 0) break
                callEnd = c + 1
            }
            calls += ModCall(name, args, p, callEnd)
            pos = callEnd; end = callEnd
        }
        return if (calls.isEmpty()) null else Chain(start, tokenEnd, end, calls)
    }

    /** True for every offset that lies inside a string literal (comments must already be blanked). */
    fun stringMask(src: String): BooleanArray {
        val mask = BooleanArray(src.length)
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("\"\"\"", i) -> { val e = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }; for (k in i until e) mask[k] = true; i = e }
                src[i] == '"' -> { val st = i; i++; while (i < src.length && src[i] != '"' && src[i] != '\n') { if (src[i] == '\\') i++; i++ }; i = minOf(i + 1, src.length); for (k in st until i) mask[k] = true }
                src[i] == '\'' && i + 2 < src.length && src[i + 2] == '\'' -> i += 3
                else -> i++
            }
        }
        return mask
    }

    private fun findChains(code: String): List<Chain> {
        val out = mutableListOf<Chain>()
        var lastEnd = -1
        val inString = stringMask(code)
        for (m in Regex("""\b(Modifier|modifier)\b""").findAll(code)) {
            val s = m.range.first
            if (s < lastEnd || inString[s]) continue
            if (s > 0 && code[s - 1] == '.') continue
            val c = parseChain(code, s) ?: continue
            out += c; lastEnd = c.end
        }
        return out
    }

    private fun targetName(code: String, chain: Chain): String {
        chain.calls.firstOrNull { it.name == "testTag" }?.let { return it.args.trim().trim('"') }
        val window = code.substring(maxOf(0, chain.start - 300), chain.start)
        return Regex("""([A-Z][A-Za-z0-9]*)\s*\(""").findAll(window).lastOrNull()?.groupValues?.get(1)
            ?: "line ${lineOf(code, chain.start)}"
    }

    private fun dpNumbers(args: String) = DP.findAll(args).map { it.groupValues[1].toDouble() }.toList()

    private val NOT_RUN = Evidence(EvidenceKind.NOT_RUN, "Runtime geometry and behavioral verification have not been run for this finding.")

    fun analyze(file: String, src: String): List<Finding> {
        val code = blankComments(src)
        val chains = findChains(code)
        val raw = mutableListOf<Finding>()

        fun loc(start: Int, end: Int) = SourceLocation(file, lineOf(src, start), start, end)

        val interactive = chains.filter { c -> c.calls.any { it.name in INTERACTIVE } }

        for (c in interactive) {
            val target = targetName(code, c)
            val hasMin = c.calls.any { it.name == "minimumInteractiveComponentSize" }
            val iIdx = c.calls.indexOfFirst { it.name in INTERACTIVE }

            // 1. Small explicit size on an interactive chain
            if (!hasMin) {
                val small = c.calls.filter { it.name in SIZE_CALLS }
                    .mapNotNull { call -> dpNumbers(call.args).minOrNull()?.let { call to it } }
                    .filter { it.second < MIN_TARGET_DP }
                    .minByOrNull { it.second }
                if (small != null) {
                    val (call, dp) = small
                    val line = lineOf(src, call.start)
                    raw += Finding(
                        id = "", kind = FindingKind.SMALL_TOUCH_TARGET,
                        title = "Interactive target '$target' is sized below 48dp",
                        severity = if (dp < 40) Severity.HIGH else Severity.MEDIUM,
                        targetName = target, location = loc(call.start, call.end),
                        snippet = src.substring(call.start, call.end),
                        explanation = "The modifier chain makes '$target' interactive (${c.calls[iIdx].name}) and also constrains it to " +
                            "${formatDp(dp)}dp, below the 48x48dp touch-target guideline. Taps near the edges or corners may miss the click action, " +
                            "especially at large font scales.",
                        evidence = listOf(
                            Evidence(EvidenceKind.HEURISTIC, "`${src.substring(call.start, call.end).trim()}` at line $line on the same chain as `.${c.calls[iIdx].name}`. " +
                                "Static analysis only: effective touch bounds were not measured."),
                            NOT_RUN),
                        chainStart = c.start,
                        visualPreview = visualPreviewFor(
                            FindingKind.SMALL_TOUCH_TARGET, target, dp.toFloat(), null, false)
                    )
                }
            }

            // 2. Padding applied before (outside) the interactive call
            if (!hasMin) {
                val pad = c.calls.withIndex().lastOrNull { (i, call) -> i < iIdx && call.name == "padding" }
                val largeSize = c.calls.any { it.name in SIZE_CALLS && (dpNumbers(it.args).minOrNull() ?: 0.0) >= MIN_TARGET_DP }
                if (pad != null && !largeSize) {
                    val call = pad.value
                    val padDp = dpNumbers(call.args).minOrNull()?.toFloat() ?: 0.0f
                    raw += Finding(
                        id = "", kind = FindingKind.CLICKABLE_BEFORE_PADDING,
                        title = "Padding on '$target' sits outside the clickable region",
                        severity = Severity.LOW, targetName = target, location = loc(call.start, call.end),
                        snippet = src.substring(call.start, call.end),
                        explanation = "`.padding(...)` is applied before `.${c.calls[iIdx].name}` in the modifier chain, so the padded area is " +
                            "not part of the clickable region. If the padding was meant to enlarge the tap area, the order is reversed. " +
                            "If it is a deliberate margin, this finding can be dismissed.",
                        evidence = listOf(
                            Evidence(EvidenceKind.HEURISTIC, "`.padding` at line ${lineOf(src, call.start)} precedes `.${c.calls[iIdx].name}` at line ${lineOf(src, c.calls[iIdx].start)}. Intent cannot be determined statically."),
                            NOT_RUN),
                        chainStart = c.start,
                        visualPreview = visualPreviewFor(
                            FindingKind.CLICKABLE_BEFORE_PADDING, target, padDp, null, false)
                    )
                }
            }
        }

        // 3. Adjacent interactive chains
        for (k in 1 until interactive.size) {
            val prev = interactive[k - 1]; val cur = interactive[k]
            val gap = lineOf(src, cur.start) - lineOf(src, prev.end)
            if (gap in 0..6) {
                val target = targetName(code, cur); val prevTarget = targetName(code, prev)
                val iCall = cur.calls.first { it.name in INTERACTIVE }
                raw += Finding(
                    id = "", kind = FindingKind.ADJACENT_CLICKABLES,
                    title = "'$target' sits directly after another interactive target ('$prevTarget')",
                    severity = Severity.MEDIUM, targetName = target, location = loc(iCall.start, iCall.end),
                    snippet = src.substring(iCall.start, iCall.end),
                    explanation = "'$target' and '$prevTarget' are declared within $gap line(s) of each other and are likely laid out side by side. " +
                        "If their touch regions touch or overlap, a tap near the shared edge can reach the wrong target.",
                    evidence = listOf(
                        Evidence(EvidenceKind.HEURISTIC, "Interactive chains end at line ${lineOf(src, prev.end)} and start at line ${lineOf(src, cur.start)}. Actual overlap depends on parent layout and was not measured."),
                        NOT_RUN),
                    chainStart = cur.start,
                    visualPreview = visualPreviewFor(
                        FindingKind.ADJACENT_CLICKABLES, target, null, null, false)
                )
            }
        }

        // 4. Icon/Image with clickable but null contentDescription
        for (m in Regex("""\b(Icon|Image)\s*\(""").findAll(code)) {
            val open = m.range.last
            val close = matchClose(code, open); if (close < 0) continue
            val call = code.substring(open, close + 1)
            val cd = Regex("""contentDescription\s*=\s*null""").find(call) ?: continue
            if (!Regex("""\b(clickable|combinedClickable|toggleable|selectable)\b""").containsMatchIn(call)) continue
            val s = open + cd.range.first; val e = open + cd.range.last + 1
            val ch = findChains(code).firstOrNull { it.start in open..close }
            val target = ch?.let { targetName(code, it) } ?: m.groupValues[1]
            raw += Finding(
                id = "", kind = FindingKind.MISSING_CONTENT_DESCRIPTION,
                title = "Clickable ${m.groupValues[1]} '$target' has no content description",
                severity = Severity.MEDIUM, targetName = target, location = loc(s, e),
                snippet = src.substring(s, e),
                explanation = "A clickable ${m.groupValues[1]} with `contentDescription = null` exposes a click action with no accessible name, " +
                    "so screen readers cannot announce what it does.",
                evidence = listOf(
                    Evidence(EvidenceKind.HEURISTIC, "`contentDescription = null` at line ${lineOf(src, s)} inside a call that also applies a clickable modifier."),
                    NOT_RUN),
                visualPreview = visualPreviewFor(
                    FindingKind.MISSING_CONTENT_DESCRIPTION, target, null, null, false)
            )
        }

        // 5. Text that may clip or overflow at larger font scales
        for (m in Regex("""\bText\s*\(""").findAll(code)) {
            val open = m.range.last
            val close = matchClose(code, open); if (close < 0) continue
            val call = code.substring(open, close + 1)
            val hasMaxLines = Regex("""maxLines\s*=""").containsMatchIn(call)
            val hasOverflow = Regex("""overflow\s*=""").containsMatchIn(call)
            if (!hasMaxLines && !hasOverflow) {
                val s = open; val e = close + 1
                raw += Finding(
                    id = "", kind = FindingKind.TEXT_CLIPPING,
                    title = "Text may clip at larger font scales",
                    severity = Severity.MEDIUM, targetName = "Text", location = loc(s, e),
                    snippet = src.substring(s, e),
                    explanation = "Text without maxLines or overflow handling may clip at larger font scales, " +
                        "making content unreadable or hiding important information.",
                    evidence = listOf(
                        Evidence(EvidenceKind.HEURISTIC, "Text call at line ${lineOf(src, s)} has no maxLines or overflow parameter."),
                        NOT_RUN),
                    visualPreview = visualPreviewFor(
                        FindingKind.TEXT_CLIPPING, "Text", null, null, false)
                )
            }
        }

        // 6. Layout with fixed width that may overflow at narrow widths
        for (m in Regex("""\b(Row|Column|Box)\s*\(""").findAll(code)) {
            val open = m.range.last
            val close = matchClose(code, open); if (close < 0) continue
            val call = code.substring(open, close + 1)
            val hasFixedWidth = Regex("""(width|requiredWidth)\s*\(""").containsMatchIn(call)
            if (hasFixedWidth) {
                val s = open; val e = close + 1
                raw += Finding(
                    id = "", kind = FindingKind.LAYOUT_OVERFLOW,
                    title = "${m.groupValues[1]} with fixed width may overflow at narrow widths",
                    severity = Severity.MEDIUM, targetName = m.groupValues[1], location = loc(s, e),
                    snippet = src.substring(s, e),
                    explanation = "${m.groupValues[1]} with a fixed width constraint may overflow on narrow screens, " +
                        "causing content to be cut off or clipped.",
                    evidence = listOf(
                        Evidence(EvidenceKind.HEURISTIC, "${m.groupValues[1]} at line ${lineOf(src, s)} has a fixed width or requiredWidth constraint."),
                        NOT_RUN),
                    visualPreview = visualPreviewFor(
                        FindingKind.LAYOUT_OVERFLOW, m.groupValues[1], null, null, false)
                )
            }
        }

        // 7. Hardcoded colors that may have visibility issues in different themes
        for (m in Regex("""Color\(0x[0-9a-fA-F]{6,8}\)""").findAll(code)) {
            val s = m.range.first; val e = m.range.last + 1
            raw += Finding(
                id = "", kind = FindingKind.COLOR_STATE_VISIBILITY,
                title = "Hardcoded color may have visibility issues",
                severity = Severity.LOW, targetName = "Color", location = loc(s, e),
                snippet = src.substring(s, e),
                explanation = "Hardcoded colors may not meet accessibility contrast guidelines in light or dark themes.",
                evidence = listOf(
                    Evidence(EvidenceKind.HEURISTIC, "Hardcoded color at line ${lineOf(src, s)} may have poor contrast in some themes."),
                    NOT_RUN),
                visualPreview = visualPreviewFor(
                    FindingKind.COLOR_STATE_VISIBILITY, "Color", null, src.substring(s, e), false)
            )
        }

        return raw.sortedBy { it.location.startOffset }
            .mapIndexed { i, f -> f.copy(id = "CW-%03d".format(i + 1)) }
    }

    /** Analyze all .kt files below [root] (skipping build output). Findings are renumbered globally. */
    fun analyzeDirectory(root: File): List<Finding> {
        val files = root.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".git", ".idea", "node_modules") }
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }.toList()
        val all = files.flatMap { f -> analyze(f.path, f.readText()) }
        return all.mapIndexed { i, f -> f.copy(id = "CW-%03d".format(i + 1)) }
    }

    private fun formatDp(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
