package com.iipauk.spider

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Interactive Android system-shell PTY, isolated to the app UID and private app directories.
 * This is not a Linux distribution and does not bundle apt/bash/python/git or root privileges.
 */
class SpiderTerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {
    private lateinit var terminalView: TerminalView
    private lateinit var statusView: TextView
    private var session: TerminalSession? = null
    private var resumed = false
    private var controlHeld = false
    private var altHeld = false
    private var sessionStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 14, 23)
        window.navigationBarColor = Color.rgb(16, 14, 23)
        setContentView(buildLayout())
        terminalView.setTerminalViewClient(this)
        terminalView.setTextSize(14)
        terminalView.setTerminalCursorBlinkerRate(550)
        terminalView.isFocusableInTouchMode = true
        startShellSession()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.rgb(16, 14, 23))
        }

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = "Spider Terminal"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        labels.addView(TextView(this).apply {
            text = "Android system shell · песочница приложения · без root"
            textSize = 11f
            setTextColor(Color.rgb(196, 185, 214))
        })
        heading.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(Button(this).apply {
            text = "Закрыть"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        root.addView(heading, matchWrap())

        statusView = TextView(this).apply {
            text = "Запускаю /system/bin/sh…"
            textSize = 12f
            setTextColor(Color.rgb(193, 175, 230))
            setPadding(dp(2), dp(5), dp(2), dp(5))
        }
        root.addView(statusView, matchWrap())

        terminalView = TerminalView(this).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = "Интерактивная консоль Android system shell"
        }
        root.addView(terminalView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(4)
            bottomMargin = dp(6)
        })

        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(toolbar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, matchWrap())

        addTerminalButton(toolbar, "CTRL") {
            controlHeld = !controlHeld
            updateToggleButton(it, controlHeld)
        }
        addTerminalButton(toolbar, "ALT") {
            altHeld = !altHeld
            updateToggleButton(it, altHeld)
        }
        addTerminalButton(toolbar, "ESC") { send("\u001b") }
        addTerminalButton(toolbar, "TAB") { send("\t") }
        addTerminalButton(toolbar, "↑") { send("\u001b[A") }
        addTerminalButton(toolbar, "↓") { send("\u001b[B") }
        addTerminalButton(toolbar, "←") { send("\u001b[D") }
        addTerminalButton(toolbar, "→") { send("\u001b[C") }
        addTerminalButton(toolbar, "Ctrl+C") { send("\u0003") }
        addTerminalButton(toolbar, "Ctrl+D") { send("\u0004") }
        addTerminalButton(toolbar, "Enter") { send("\r") }
        addTerminalButton(toolbar, "Новая") { restartSession() }

        root.addView(TextView(this).apply {
            text = "Команды выполняются от имени ИИ Паука внутри Android-песочницы. Linux-дистрибутив и пакеты не установлены."
            textSize = 10f
            setTextColor(Color.rgb(156, 146, 174))
            setPadding(dp(2), dp(5), dp(2), 0)
        }, matchWrap())
        return root
    }

    private fun addTerminalButton(parent: LinearLayout, label: String, action: (Button) -> Unit) {
        val button = Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minWidth = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { action(this) }
        }
        parent.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
            setMargins(dp(2), dp(1), dp(2), dp(1))
        })
    }

    private fun updateToggleButton(button: Button, enabled: Boolean) {
        button.setBackgroundColor(if (enabled) Color.rgb(111, 78, 170) else Color.rgb(52, 44, 66))
        button.setTextColor(Color.WHITE)
        statusView.text = "CTRL ${if (controlHeld) "вкл" else "выкл"} · ALT ${if (altHeld) "вкл" else "выкл"} · /system/bin/sh"
        focusTerminal()
    }

    private fun startShellSession() {
        if (sessionStarted && session?.isRunning == true) return
        sessionStarted = true
        controlHeld = false
        altHeld = false
        val home = filesDir.absolutePath
        val environment = arrayOf(
            "HOME=$home",
            "PWD=$home",
            "TMPDIR=${cacheDir.absolutePath}",
            "PATH=/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8"
        )
        try {
            val newSession = TerminalSession(
                "/system/bin/sh",
                home,
                arrayOf("/system/bin/sh"),
                environment,
                2000,
                this
            )
            session = newSession
            terminalView.attachSession(newSession)
            statusView.text = "Сессия создана · жду размера окна и старта PTY…"
        } catch (error: Exception) {
            sessionStarted = false
            statusView.text = "Не удалось открыть PTY: ${error.message ?: error.javaClass.simpleName}"
            Toast.makeText(this, "Не удалось запустить Android shell", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Unable to start Android system shell", error)
        }
    }

    private fun restartSession() {
        val old = session
        if (old != null && old.getPid() > 0 && old.isRunning) runCatching { old.finishIfRunning() }
        session = null
        sessionStarted = false
        startShellSession()
        focusTerminal()
    }

    private fun send(text: String) {
        val active = session
        if (active == null || !active.isRunning || active.getPid() <= 0) {
            Toast.makeText(this, "Shell не запущен. Нажми «Новая».", Toast.LENGTH_SHORT).show()
            return
        }
        active.write(text)
        focusTerminal()
    }

    private fun focusTerminal() {
        terminalView.requestFocus()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        resumed = true
        if (::terminalView.isInitialized) terminalView.setTerminalCursorBlinkerState(true, true)
    }

    override fun onStop() {
        resumed = false
        if (::terminalView.isInitialized) terminalView.setTerminalCursorBlinkerState(false, false)
        super.onStop()
    }

    override fun onDestroy() {
        val active = session
        session = null
        if (active != null && active.getPid() > 0 && active.isRunning) {
            runCatching { active.finishIfRunning() }.onFailure { Log.w(TAG, "Could not stop PTY session cleanly", it) }
        }
        controlHeld = false
        altHeld = false
        super.onDestroy()
    }

    // TerminalSessionClient — callbacks are delivered on the UI thread by the library.
    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession === session && ::terminalView.isInitialized && !isFinishing && !isDestroyed) terminalView.invalidate()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        if (changedSession === session) {
            val title = changedSession.title?.take(80)
            if (::statusView.isInitialized && !isFinishing && !isDestroyed) {
                statusView.text = if (title.isNullOrBlank()) "Рабочая сессия · /system/bin/sh" else title
            }
        }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (finishedSession !== session || isFinishing || isDestroyed) return
        statusView.text = "Shell завершился (код ${finishedSession.exitStatus}). Нажми «Новая», чтобы перезапустить."
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Spider Terminal", text.take(MAX_CLIPBOARD_CHARS)))
        Toast.makeText(this, "Текст скопирован", Toast.LENGTH_SHORT).show()
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching {
            if (clipboard.hasPrimaryClip() && clipboard.primaryClip!!.itemCount > 0) {
                clipboard.primaryClip!!.getItemAt(0).coerceToText(this).toString().take(MAX_CLIPBOARD_CHARS)
            } else ""
        }.getOrDefault("")
        if (text.isNotEmpty() && session === this.session && session.isRunning) session.write(text)
    }

    override fun onBell(session: TerminalSession) {
        if (!isFinishing && !isDestroyed) runOnUiThread {
            if (::statusView.isInitialized) statusView.text = "Terminal bell · /system/bin/sh"
        }
    }

    override fun onColorsChanged(session: TerminalSession) {
        if (session === this.session && ::terminalView.isInitialized) terminalView.postInvalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        if (::terminalView.isInitialized) terminalView.setTerminalCursorBlinkerState(resumed, true)
    }

    override fun getTerminalCursorStyle(): Int? = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    // TerminalViewClient — soft keyboard, hardware keyboard and toolbar key helpers.
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent) {
        focusTerminal()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed && ::terminalView.isInitialized) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 100L)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = controlHeld
    override fun readAltKey(): Boolean = altHeld
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {
        if (resumed) terminalView.setTerminalCursorBlinkerState(true, true)
    }

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Terminal library error", e) }

    companion object {
        private const val TAG = "SpiderTerminal"
        private const val MAX_CLIPBOARD_CHARS = 65_536
    }
}
