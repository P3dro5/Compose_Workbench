package dev.composeworkbench.ui

import dev.composeworkbench.core.*
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities

object WorkbenchFactory {
    /** Real mode: analyze the project's .kt files. Verification uses results from the instrumented test if present. */
    fun forProject(projectDir: File): WorkbenchPanel {
        val findings = SourceAnalyzer.analyzeDirectory(projectDir)
        val results = File(projectDir, ".compose-workbench/results.tsv")
        val engine: () -> VerificationEngine = {
            if (results.isFile) ResultsFileEngine(results)
            else UnavailableEngine("No instrumented results found at ${results.path}. Run the instrumented matrix test on a device/emulator and copy its output there.")
        }
        return WorkbenchPanel(WorkbenchStoreHandle(WorkflowStore(findings)), engine)
    }

    /** Demo mode: clearly labelled illustrative data with a scripted engine. */
    fun demo(workDir: File, scenario: DemoFixtureEngine.Scenario = DemoFixtureEngine.Scenario.ILLUSTRATED_FAILURES): WorkbenchPanel {
        val (_, findings) = DemoData.create(workDir)
        return WorkbenchPanel(WorkbenchStoreHandle(WorkflowStore(findings)), { DemoFixtureEngine(scenario) }, DemoData.BANNER)
    }
}

/** Standalone preview: `java -jar compose-workbench.jar [projectDir]`. No argument = demo mode. */
fun main(args: Array<String>) {
    SwingUtilities.invokeLater {
        val panel = if (args.isNotEmpty()) WorkbenchFactory.forProject(File(args[0]))
        else WorkbenchFactory.demo(File(System.getProperty("java.io.tmpdir"), "compose-workbench-demo"))
        JFrame("Compose Workbench").apply { defaultCloseOperation = JFrame.EXIT_ON_CLOSE; contentPane.add(panel); setSize(1000, 700); setLocationRelativeTo(null); isVisible = true }
    }
}
