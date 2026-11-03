package dev.composeworkbench.tests

import dev.composeworkbench.core.*
import dev.composeworkbench.ui.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*

/** Developer tool: renders each stage headlessly to PNG (demo data). Usage: ScreenshotsKt <outDir> */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = File(args.getOrElse(0) { "screenshots" }).also { it.mkdirs() }
    SwingUtilities.invokeAndWait {
        val panel = WorkbenchFactory.demo(tempDir())
        fun shot(name: String) {
            panel.setSize(1000, 640); panel.doLayout(); panel.doRefresh()
            fun lay(c: java.awt.Component) { c.doLayout(); (c as? java.awt.Container)?.components?.forEach { lay(it) } }; lay(panel)
            val img = BufferedImage(1000, 640, BufferedImage.TYPE_INT_RGB); val g = img.createGraphics(); panel.paint(g); g.dispose()
            ImageIO.write(img, "png", File(out, "$name.png"))
        }
        val store = (panel.javaClass.getDeclaredField("store").also { it.isAccessible = true }.get(panel)) as WorkbenchStoreHandle
        shot("1-inspect"); store.goTo(Stage.DIAGNOSE); shot("2-diagnose")
        store.suggestFix("CW-001"); shot("3-repair")
        store.approve("CW-001"); shot("4-verify-awaiting")
        store.applyApprovedPatch("CW-001"); store.runVerification("CW-001", DemoFixtureEngine(DemoFixtureEngine.Scenario.ILLUSTRATED_FAILURES)); shot("5-verify-failed-demo")
    }
    println("wrote screenshots to ${out.absolutePath}")
}
