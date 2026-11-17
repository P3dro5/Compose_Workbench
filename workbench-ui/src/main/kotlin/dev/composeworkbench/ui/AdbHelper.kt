package dev.composeworkbench.ui

import java.io.*

/**
 * Helps locate the Android SDK, detect adb, and pull verification results
 * from a connected device/emulator.
 */
object AdbHelper {

    /** Path to the adb executable, or null if not found. */
    val adbPath: String?
        get() {
            val sdkDir = localProperties("sdk.dir")
                ?: System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
            if (sdkDir.isNullOrBlank()) return null
            val candidates = listOf(
                File(sdkDir, "platform-tools/adb"),
                File(sdkDir, "platform-tools/adb.exe"),
            )
            return candidates.firstOrNull { it.isFile }?.absolutePath
        }

    /** Parses sdk.dir from local.properties. */
    private fun localProperties(key: String): String? {
        val file = File(System.getProperty("user.dir"), "local.properties")
        if (!file.isFile) return null
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("$key=")) {
                return trimmed.substringAfter("=").trim()
            }
        }
        return null
    }

    /** Checks if any Android device/emulator is connected via adb. */
    fun hasConnectedDevice(): Boolean {
        val adb = adbPath ?: return false
        return try {
            val output = runCommand(adb, "devices")
            output.lines().drop(1).any { it.trim().contains("device") && !it.contains("unauthorized") }
        } catch (_: Exception) {
            false
        }
    }

    /** Pulls the results.tsv file from the device to the local .compose-workbench directory.
     * Returns the local file path on success, or null on failure. */
    fun pullResultsFromDevice(projectDir: File): String? {
        val adb = adbPath ?: return null
        val resultsDir = File(projectDir, ".compose-workbench")
        resultsDir.mkdirs()
        val targetFile = File(resultsDir, "results.tsv")

        // Common paths where the instrumented test writes results
        val remotePaths = listOf(
            "/data/data/dev.composeworkbench.sample/files/cw-results.tsv",
            "/sdcard/Android/data/dev.composeworkbench.sample/files/cw-results.tsv",
        )

        for (remotePath in remotePaths) {
            try {
                val output = runCommand(adb, "pull", remotePath, targetFile.absolutePath)
                if (targetFile.isFile && targetFile.length() > 0) {
                    return targetFile.absolutePath
                }
            } catch (_: Exception) {
                // try next path
            }
        }
        return null
    }

    /**
     * Gradle arguments for an instrumented test run. When [target] is supplied, the test only
     * exercises that finding's test tag (and, optionally, one font scale / probe), which makes
     * "run automatic tests for this specific case" a fast, focused operation. Without a filter
     * the full matrix runs for all targets.
     */
    fun targetedTestArgs(target: String? = null, fontScale: Float? = null, probe: String? = null): List<String> {
        val args = mutableListOf(":sample-app:connectedDebugAndroidTest")
        if (!target.isNullOrBlank()) args += "-PcwTarget=$target"
        if (fontScale != null) args += "-PcwFontScale=${"%.1f".format(java.util.Locale.ROOT, fontScale)}"
        if (!probe.isNullOrBlank()) args += "-PcwProbe=$probe"
        return args
    }

    /** Runs the instrumented test for [target] (or all targets when null) and returns Gradle output. */
    fun runInstrumentedTest(projectDir: File, target: String? = null, fontScale: Float? = null, probe: String? = null): String {
        val gradlew = File(projectDir, "gradlew")
        require(gradlew.isFile) { "gradlew not found in ${projectDir.absolutePath}" }
        return runCommand(gradlew.absolutePath, *targetedTestArgs(target, fontScale, probe).toTypedArray())
    }

    /** Runs an adb command and returns its stdout. */
    fun runCommand(vararg args: String): String {
        val pb = ProcessBuilder(*args).redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }
}
