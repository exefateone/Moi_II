# Android build compatibility — v0.36 baseline / v0.37 Media3 update

Project settings after the deep source audit: Android Gradle Plugin 8.10.2, Kotlin Android plugin 2.2.0, `compileSdk=35`, `targetSdk=35`, Java/Kotlin target 17. AGP 8.10 uses Gradle 8.11.1 and JDK 17. Android documentation lists AGP 8.10 as compatible with Kotlin 2.2 and its bundled R8 requirement; AGP 8.9.2 was therefore an avoidable version-risk and has been updated. A full Gradle sync and build still must confirm the dependency graph.

Official Android compatibility guidance for AGP 8.10 specifies Gradle 8.11.1 minimum/default and JDK 17. This archive does not fabricate a Gradle wrapper JAR: this environment has no Gradle installation and outbound DNS is unavailable, so a wrapper binary could not be fetched or verified. Use Android Studio to sync the project and configure/generate the wrapper in a network-enabled development environment.

Sources:
- https://developer.android.com/build/releases/agp-8-10-0-release-notes?hl=en
- https://developer.android.com/build/kotlin-support?hl=en
- https://developer.android.com/reference/android/webkit/WebChromeClient
- https://developer.android.com/reference/android/webkit/WebViewClient

No claim is made that `assembleDebug` succeeded.


## v0.37 Media3 dependency note

Media3 1.11.1 is the latest stable version listed by Android Developers as of 12 October 2026, with a release date of 10 September 2026. The Media3 1.11.0 release notes record the Kotlin toolchain upgrade to 2.2.0; the project Kotlin Gradle plugin is therefore raised to 2.2.0 to reduce metadata/toolchain incompatibility risk. This still needs a real Gradle Sync/compile test. Official release notes: https://developer.android.com/jetpack/androidx/releases/media3
