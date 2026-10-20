package com.iipauk.spider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real PTY acceptance test; authored here, must be run on an Android emulator/device. */
@RunWith(AndroidJUnit4::class)
class TerminalPtyInstrumentedTest {
    @Test fun androidSystemShellExecutesAndReturnsStdoutWithoutRoot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = ProbeClient()
        var session: TerminalSession? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val home = context.filesDir.absolutePath
            val env = arrayOf(
                "HOME=$home",
                "PWD=$home",
                "TMPDIR=${context.cacheDir.absolutePath}",
                "PATH=/system/bin:/system/xbin",
                "TERM=xterm-256color",
                "LANG=C.UTF-8"
            )
            session = TerminalSession("/system/bin/sh", home, arrayOf("/system/bin/sh"), env, 100, client)
            session!!.initializeEmulator(80, 24)
        }
        val active = session
        assertNotNull("PTY session should be created", active)
        try {
            active!!.write("pwd; id; printf '__SPIDER_PTY_OK__\\n'\r")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            var screen = ""
            while (System.nanoTime() < deadline && !screen.contains("__SPIDER_PTY_OK__")) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val emulator = active.emulator
                    if (emulator != null) {
                        screen = emulator.screen.getSelectedText(0, 0, emulator.mColumns, emulator.mRows) ?: ""
                    }
                }
                if (!screen.contains("__SPIDER_PTY_OK__")) Thread.sleep(80L)
            }
            assertTrue("Expected marker in PTY stdout, got: $screen", screen.contains("__SPIDER_PTY_OK__"))
            assertTrue("The shell should run from app-private home", screen.contains(context.filesDir.absolutePath))
            assertFalse("The shell must not run as root", Regex("uid=0\\(root\\)").containsMatchIn(screen))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (active != null && active.getPid() > 0 && active.isRunning) active.finishIfRunning()
            }
        }
        assertTrue("PTY process should finish after being stopped", client.finished.await(5, TimeUnit.SECONDS))
    }

    private class ProbeClient : TerminalSessionClient {
        val finished = CountDownLatch(1)
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) { finished.countDown() }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) = Unit
        override fun logWarn(tag: String, message: String) = Unit
        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
        override fun logStackTrace(tag: String, e: Exception) = Unit
    }
}
