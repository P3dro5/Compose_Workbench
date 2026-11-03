rootProject.name = "compose-workbench"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":workbench-core", ":workbench-ui", ":workbench-tests", ":workbench-plugin", ":sample-app")
