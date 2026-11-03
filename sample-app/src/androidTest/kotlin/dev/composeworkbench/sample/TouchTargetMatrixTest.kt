package dev.composeworkbench.sample

// STATUS: WRITTEN, NOT COMPILED AND NOT RUN. The build sandbox had no Android SDK, no Gradle and no device/emulator.
// Compose test API names below (createComposeRule, onRoot, performTouchInput, boundsInRoot) are standard, but verify
// them against the Compose BOM you use. See docs/KNOWN_LIMITATIONS.md.

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Runs the 7 probe groups x 4 font scales matrix for the targets "favorite" and "share" and writes real observations to
 * <app files dir>/cw-results.tsv (format documented in ResultsFileEngine). Copy that file to
 * <project>/.compose-workbench/results.tsv so the workbench can import it.
 *
 * Coordinate conventions (root pixels): inside probes are 1px inside the node's layout bounds (boundsInRoot);
 * corners use the four inner corners; the outside probe is 8px left of the left edge, at vertical centre.
 * Assumption: boundsInRoot is the layout bounds, not necessarily the hit-test region; the matrix therefore tests
 * behaviour (which click handler fires), not geometry alone.
 */
class TouchTargetMatrixTest {
    @get:Rule val rule = createComposeRule()

    private val scales = listOf(1.0f, 1.3f, 1.5f, 2.0f)
    private val probes = listOf("CENTER", "TOP_EDGE", "BOTTOM_EDGE", "LEFT_EDGE", "RIGHT_EDGE", "CORNERS", "OUTSIDE")
    private var favoriteClicks = 0
    private var shareClicks = 0

    private data class Pt(val x: Float, val y: Float)

    private fun points(l: Float, t: Float, r: Float, b: Float, probe: String, inset: Float = 1f, margin: Float = 8f): List<Pt> {
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        return when (probe) {
            "CENTER" -> listOf(Pt(cx, cy)); "TOP_EDGE" -> listOf(Pt(cx, t + inset)); "BOTTOM_EDGE" -> listOf(Pt(cx, b - inset))
            "LEFT_EDGE" -> listOf(Pt(l + inset, cy)); "RIGHT_EDGE" -> listOf(Pt(r - inset, cy))
            "CORNERS" -> listOf(Pt(l + inset, t + inset), Pt(r - inset, t + inset), Pt(l + inset, b - inset), Pt(r - inset, b - inset))
            else -> listOf(Pt(l - margin, cy))
        }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    @Test
    fun runMatrix() {
        val scale = mutableStateOf(1.0f)
        rule.setContent {
            val base = LocalDensity.current
            val s by scale
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = s)) {
                FavoriteCard(onFavorite = { favoriteClicks++ }, onShare = { shareClicks++ })
            }
        }
        val rows = StringBuilder("# target\tfontScale\tprobe\tpoints\texpected\tobserved\tstatus\tevidence\terror\n")
        for (target in listOf("favorite", "share")) for (s in scales) {
            scale.value = s; rule.waitForIdle()
            val b = try { rule.onNodeWithTag(target).fetchSemanticsNode().boundsInRoot } catch (e: Throwable) { null }
            for (probe in probes) {
                val negative = probe == "OUTSIDE"
                val expected = if (negative) "tap outside '$target' must NOT trigger its click action" else "tap on '$target' ($probe) triggers its click action"
                val key = String.format(Locale.ROOT, "%.1f", s)
                if (b == null) { rows.append("$target\t$key\t$probe\t\t$expected\tnode not found\tINCONCLUSIVE\t\tnode with testTag '$target' not found\n"); continue }
                val pts = points(b.left, b.top, b.right, b.bottom, probe)
                favoriteClicks = 0; shareClicks = 0
                val own = { if (target == "favorite") favoriteClicks else shareClicks }
                val other = { if (target == "favorite") shareClicks else favoriteClicks }
                var error: String? = null
                try { for (p in pts) { rule.onRoot().performTouchInput { click(Offset(p.x, p.y)) }; rule.waitForIdle() } }
                catch (e: Throwable) { error = clean(e.toString()) }
                val ptsText = pts.joinToString(";") { "${it.x},${it.y}" }
                val observed = "target clicks=${own()}, other target clicks=${other()}"
                val ev = "boundsInRoot=${b.left},${b.top},${b.right},${b.bottom} px; density=${rule.activity.resources.displayMetrics.density}"
                val status = when {
                    error != null -> "INCONCLUSIVE"
                    negative -> if (own() == 0) "PASS" else "FAIL"
                    own() == pts.size && other() == 0 -> "PASS"
                    else -> "FAIL"
                }
                rows.append("$target\t$key\t$probe\t$ptsText\t${clean(expected)}\t${clean(observed)}\t$status\t${clean(ev)}\t${error ?: ""}\n")
            }
        }
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.filesDir, "cw-results.tsv").writeText(rows.toString())
        // This test records observations; it does not assert pass/fail so that failing cases are reported, not hidden.
    }
}
