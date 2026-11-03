package dev.composeworkbench.tests

fun main() {
    System.setProperty("java.awt.headless", "true")
    val s = Suite(); registerCoreTests(s)
    s.results.forEach { println((if (it.ok) "PASS  " else "FAIL  ") + it.name + (it.detail?.let { d -> "\n      $d" } ?: "")) }
    val failed = s.results.count { !it.ok }
    println("\n${s.results.size} tests executed, ${s.results.size - failed} passed, $failed failed")
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
