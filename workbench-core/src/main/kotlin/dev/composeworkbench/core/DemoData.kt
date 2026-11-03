package dev.composeworkbench.core

import java.io.File

/**
 * Illustrative demo findings CW-001 and CW-002. They are derived from the real analyzer run over the bundled sample
 * source but are labelled as DEMO DATA: the described runtime symptoms were not observed by any test.
 */
object DemoData {
    const val BANNER = "DEMO DATA - illustrative findings and results, not output of a real device test."

    val SAMPLE_SOURCE = """package dev.composeworkbench.sample

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
fun FavoriteCard(onFavorite: () -> Unit, onShare: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = Icons.Filled.Share,
            contentDescription = "Share",
            modifier = Modifier
                .testTag("share")
                .clickable { onShare() }
                .size(32.dp)
        )
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = "Favorite",
            modifier = Modifier
                .testTag("favorite")
                .clickable { onFavorite() }
                .size(24.dp)
        )
    }
}
"""

    /** Writes the sample source into [dir] (if absent) and returns its demo findings. */
    fun create(dir: File): Pair<File, List<Finding>> {
        dir.mkdirs()
        val file = File(dir, "FavoriteCard.kt")
        if (!file.exists()) file.writeText(SAMPLE_SOURCE)
        val found = SourceAnalyzer.analyze(file.path, file.readText())
        val adjacent = found.firstOrNull { it.kind == FindingKind.ADJACENT_CLICKABLES && it.targetName == "favorite" }
        val small = found.firstOrNull { it.kind == FindingKind.SMALL_TOUCH_TARGET && it.targetName == "favorite" }
        check(adjacent != null && small != null) { "Demo source did not produce the expected findings" }
        val demoNote = Evidence(EvidenceKind.INCONCLUSIVE, "DEMO DATA: the symptom described by this finding is illustrative and was not observed on a device.")
        return file to listOf(
            adjacent.copy(id = "CW-001", isDemo = true, title = "[DEMO] Right-edge taps on 'favorite' may reach the adjacent 'share' target",
                evidence = listOf(demoNote) + adjacent.evidence),
            small.copy(id = "CW-002", isDemo = true, title = "[DEMO] Corner taps on 'favorite' may miss its 24dp target",
                evidence = listOf(demoNote) + small.evidence))
    }
}
