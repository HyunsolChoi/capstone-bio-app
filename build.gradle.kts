// ===============================
// build.gradle.kts  (Project-level)
// ===============================
plugins {
    id("com.android.application")           version "8.13.0" apply false
    id("org.jetbrains.kotlin.android")      version "2.1.20"       apply false
    id("com.google.devtools.ksp")           version "2.1.20-2.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"     apply false
    id("com.google.dagger.hilt.android")    version "2.56.2"       apply false   // Hilt 최신
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.google.gms:google-services:4.4.1")                // Google-services
        classpath("androidx.navigation:navigation-safe-args-gradle-plugin:2.7.7")
    }
}
