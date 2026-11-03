// NOT BUILT IN THE AUTHORING SANDBOX (no Android SDK / Gradle / Maven access). Versions are plausible, unverified starting points.
plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.1.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10"
}
repositories {
    google()
    mavenCentral()
}
android {
    namespace = "dev.composeworkbench.sample"
    compileSdk = 35
    defaultConfig { applicationId = "dev.composeworkbench.sample"; minSdk = 24; targetSdk = 35; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["androidTest"].java.srcDir("src/androidTest/kotlin")
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
        targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
    }
}
kotlin {
    jvmToolchain(17)
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(bom); androidTestImplementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
