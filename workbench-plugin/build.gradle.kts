// NOT BUILT IN THE AUTHORING SANDBOX. Plugin/SDK coordinates are unverified; adjust to your Android Studio version.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}
repositories { mavenCentral(); intellijPlatform { defaultRepositories(); intellijDependencies() } }
dependencies {
    implementation(project(":workbench-core")); implementation(project(":workbench-ui"))
    intellijPlatform { intellijIdeaCommunity("2024.2") }
}
kotlin { jvmToolchain(21) }
