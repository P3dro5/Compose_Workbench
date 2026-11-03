plugins { id("org.jetbrains.kotlin.jvm") version "2.1.10"; application }
repositories { mavenCentral() }
dependencies { implementation(project(":workbench-core")); implementation(project(":workbench-ui")) }
kotlin { jvmToolchain(17) }
application { mainClass.set("dev.composeworkbench.tests.MainKt") }
// `./gradlew :workbench-tests:run` executes the same harness as scripts/build-and-test.sh (Gradle path itself NOT run in the sandbox).
tasks.named<JavaExec>("run") { systemProperty("java.awt.headless", "true") }
