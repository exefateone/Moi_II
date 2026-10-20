package android.provider

import android.content.Context

/** Test-only stand-in; actual overlay permission checks run on Android. */
object Settings {
    @JvmStatic fun canDrawOverlays(context: Context): Boolean = false
}
