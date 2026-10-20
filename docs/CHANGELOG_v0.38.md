# Changelog — ИИ Паук v0.38 Widget + Code Studio

## Added to source
- `SpiderOverlayService.kt`: user-enabled system overlay bubble, drag positioning, quick panel and navigation to core modules.
- Manifest permission/foreground-service declarations and native bridge flow to request Android's overlay permission.
- Widget routes for Chat, Browser, Media, Terminal, Spider AI and Spider Code & Build.
- Spider Code & Build UI: editor, sandboxed HTML preview and `.html` save request.
- Android Storage Access Framework save flow with UTF-8 output and callback to the HTML editor.
- Explicit APK build state. The UI does not pretend to compile; the isolated `Spider Build Runner` remains future work.
- Readiness percentages, current truth table and ordered implementation checklist.

## Verification limitation
This runtime has Java 21 but no Gradle executable, Android SDK or adb. Therefore Kotlin/Gradle compilation, APK output, overlay permission behavior and Storage Access Framework are not verified in this environment. Treat all new Android source as uncompiled until Android Studio/CI returns build logs.
