package dev.composeworkbench.tests

import java.io.File
import java.nio.file.Files

/** Minimal dependency-free test harness (JUnit could not be downloaded in the build sandbox). */
class Suite {
    data class Result(val name: String, val ok: Boolean, val detail: String?)
    val results = mutableListOf<Result>()
    fun test(name: String, block: () -> Unit) {
        try { block(); results += Result(name, true, null) }
        catch (t: Throwable) { results += Result(name, false, "${t.javaClass.simpleName}: ${t.message}\n" + t.stackTrace.take(4).joinToString("\n") { "      at $it" }) }
    }
}

fun expect(cond: Boolean, msg: () -> String = { "expectation failed" }) { if (!cond) throw AssertionError(msg()) }
fun <T> expectEq(expected: T, actual: T, what: String = "") { if (expected != actual) throw AssertionError("$what expected <$expected> but was <$actual>") }
fun tempDir(): File = Files.createTempDirectory("cw-test").toFile()
