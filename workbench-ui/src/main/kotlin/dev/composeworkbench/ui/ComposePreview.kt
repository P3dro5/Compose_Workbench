package dev.composeworkbench.ui

import dev.composeworkbench.core.ComponentPreview
import dev.composeworkbench.core.PreviewIssue
import dev.composeworkbench.core.PreviewSeverity
import java.awt.*
import java.awt.geom.*
import javax.swing.*

/**
 * Renders a visual approximation of a Compose component for preview purposes.
 * Since we cannot run actual Android Compose UI in this environment, this draws
 * representative shapes based on the modifier chain analysis.
 */
class ComposePreview : JPanel() {

    private var previewData: ComponentPreview? = null
    private var fontScale: Float = 1.0f
    private var showIssues: Boolean = true

    init {
        preferredSize = Dimension(400, 300)
        background = Color(0xF5F5F5)
    }

    /** Sets preview data from a core ComponentPreview. */
    fun setPreview(data: ComponentPreview, fontScale: Float = 1.0f, showIssues: Boolean = true) {
        this.previewData = data
        this.fontScale = fontScale
        this.showIssues = showIssues
        repaint()
    }

    fun clear() {
        previewData = null
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val w = size.width
        val h = size.height

        // Background card
        g2.color = Color.WHITE
        g2.fillRoundRect(20, 20, w - 40, h - 100, 12, 12)
        g2.color = Color(0xDDDDDD)
        g2.drawRoundRect(20, 20, w - 40, h - 100, 12, 12)

        if (previewData == null) {
            g2.color = Color.GRAY
            g2.font = Font("SansSerif", Font.PLAIN, 14)
            g2.drawString("No component preview available", w / 2 - 80, h / 2)
            return
        }

        val data = previewData!!

        // Title
        g2.color = Color.BLACK
        g2.font = Font("SansSerif", Font.BOLD, 16)
        g2.drawString(data.componentName, 40, 50)

        // Component visual
        val componentX = 60
        val componentY = 90
        val componentSize = (48.0f * fontScale).toInt() // 48dp baseline
        val actualWidth = (data.widthDp * fontScale).toInt().coerceAtLeast(componentSize).coerceAtMost(200)
        val actualHeight = (data.heightDp * fontScale).toInt().coerceAtLeast(40)

        // Draw component bounds
        g2.color = if (data.isInteractive) Color(0xE3F2FD) else Color(0xFFFFFF)
        g2.fillRect(componentX, componentY, actualWidth, actualHeight)
        g2.color = if (data.isInteractive) Color(0x2196F3) else Color(0x9E9E9E)
        g2.stroke = BasicStroke(2f)
        g2.drawRect(componentX, componentY, actualWidth, actualHeight)
        g2.stroke = BasicStroke(1f)

        // Draw icon/content representation
        g2.color = data.colorHex?.let { parseColor(it) } ?: Color(0xFF5722)
        val iconSize = (24.0f * fontScale).toInt()
        g2.fillOval(componentX + (actualWidth - iconSize) / 2, componentY + (actualHeight - iconSize) / 2, iconSize, iconSize)

        // Draw content description label
        g2.color = Color.BLACK
        g2.font = Font("SansSerif", Font.PLAIN, 12)
        val label = if (data.hasContentDescription) "✓ ${data.componentName}" else "⚠ No label"
        g2.drawString(label, 40, h - 70)

        // Font scale indicator
        g2.color = Color.GRAY
        g2.font = Font("SansSerif", Font.PLAIN, 10)
        g2.drawString("Font scale: ${"%.1f".format(fontScale)}x", 40, h - 50)

        // Draw issues as colored badges below the component
        if (showIssues && data.issues.isNotEmpty()) {
            var issueX = componentX
            val issueY = componentY + actualHeight + 10
            val issueSize = 16
            for ((index, issue) in data.issues.withIndex()) {
                val color = when (issue.severity) {
                    PreviewSeverity.HIGH -> Color(0xD32F2F)
                    PreviewSeverity.MEDIUM -> Color(0xF57C00)
                    PreviewSeverity.LOW -> Color(0xFBC02D)
                }
                g2.color = color
                g2.fillRect(issueX, issueY, issueSize, issueSize)
                g2.color = Color.WHITE
                g2.font = Font("SansSerif", Font.BOLD, 8)
                g2.drawString(issue.type.name.first().toString(), issueX + 2, issueY + 11)
                issueX += issueSize + 4
            }
        }

        // Draw target size indicator (48dp guideline)
        g2.color = Color(0xFF9800)
        g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(6f, 6f), 0f)
        val guideSize = (48.0f * fontScale).toInt()
        g2.drawRect(componentX + (actualWidth - guideSize) / 2 - 2, componentY + (actualHeight - guideSize) / 2 - 2, guideSize + 4, guideSize + 4)
        g2.stroke = BasicStroke(1f)
        g2.color = Color(0xFF9800)
        g2.font = Font("SansSerif", Font.PLAIN, 9)
        g2.drawString("48dp target guideline", componentX + (actualWidth - guideSize) / 2, componentY - 5)
    }

    /** Parses a color string that may be "Color(0xFF0000)", "0xFF0000", "#FF0000", or a named color. */
    private fun parseColor(colorStr: String): Color {
        val cleaned = colorStr
            .removePrefix("Color(")
            .removeSuffix(")")
            .trim()
        return try {
            when {
                cleaned.startsWith("0x") || cleaned.startsWith("0X") -> Color.decode(cleaned)
                cleaned.startsWith("#") -> Color.decode(cleaned)
                else -> Color.decode("0x$cleaned")
            }
        } catch (e: Exception) {
            Color(0xFF5722) // fallback
        }
    }
}
