// Version matrix, pinned deliberately.
//
//   AGP 8.5.2   requires Gradle 8.7+ and JDK 17
//   Kotlin 2.0.20 ships the Compose compiler as a first-party Gradle plugin,
//              so there is no separate composeOptions.kotlinCompilerExtensionVersion
//              to keep in sync. That is the whole reason for this Kotlin version.
//
// Bumping any one of these three means re-verifying the Compose compiler
// artifact, which is the single most common way an Android build dies on a
// clean machine. Do not bump them independently.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}