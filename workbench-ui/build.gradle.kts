plugins { id("org.jetbrains.kotlin.jvm") version "2.1.10" }
repositories { mavenCentral() }
dependencies { implementation(project(":workbench-core")) }
kotlin { jvmToolchain(17) }
