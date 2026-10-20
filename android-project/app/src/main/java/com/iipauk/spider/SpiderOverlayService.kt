package com.iipauk.spider

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * User-enabled, draggable, always-on-top Spider launcher.
 * The overlay offers app shortcuts and status; it does not inspect or control other apps by itself.
 * Accessibility-based cross-app automation is a separate, explicit future permission surface.
 */
class SpiderOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var moved = false
    private var wasTuckedOnDown = false
    private var tucked = false
    private var dockedLeft = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retractRunnable: Runnable? = null
    private var bubbleAnimator: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
        startVisibleForeground()
        if (bubble == null) addBubble()
        return START_STICKY
    }

    private fun startVisibleForeground() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 7101, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            this, 7102, Intent(this, SpiderOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("ИИ Паук активен")
                .setContentText("Виджет поверх окон включён. Нажмите на паучка или откройте меню.")
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Скрыть Паука", stopPendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("ИИ Паук активен")
                .setContentText("Плавающий виджет включён.")
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Скрыть Паука", stopPendingIntent)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Плавающий виджет Паука", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Показывает, что пользователь включил плавающий виджет ИИ Паука."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun overlayParams(width: Int, height: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    private fun addBubble() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val metrics = resources.displayMetrics
        val widthPx = metrics.widthPixels
        val savedX = prefs.getInt(KEY_X, max(dp(4), widthPx - dp(76)))
            .coerceIn(dp(4), max(dp(4), widthPx - dp(64)))
        val savedY = prefs.getInt(KEY_Y, dp(220))
            .coerceIn(dp(12), max(dp(12), metrics.heightPixels - dp(88)))
        dockedLeft = savedX + dp(30) < widthPx / 2
        val icon = TextView(this).apply {
            text = "🕷"
            textSize = 25f
            gravity = Gravity.CENTER
            contentDescription = "ИИ Паук — нажми, чтобы выползти и открыть меню; перетащи к краю, чтобы спрятать"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(39, 27, 57))
                setStroke(dp(2), Color.rgb(180, 131, 255))
            }
            elevation = dp(8).toFloat()
        }
        bubble = icon
        val params = overlayParams(dp(60), dp(60), savedX, savedY)
        bubbleParams = params
        icon.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelRetract()
                    cancelBubbleAnimation()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    wasTuckedOnDown = tucked
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4)) moved = true
                    if (moved) {
                        params.x = (downX + dx).coerceIn(dp(4), max(dp(4), metrics.widthPixels - dp(64)))
                        params.y = (downY + dy).coerceIn(dp(12), max(dp(12), metrics.heightPixels - dp(72)))
                        tucked = false
                        icon.alpha = 1f
                        runCatching { windowManager.updateViewLayout(icon, params) }
                        repositionPanel()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        dockBubbleAndScheduleRetract()
                    } else if (wasTuckedOnDown || tucked) {
                        expandBubble {
                            togglePanel()
                        }
                    } else {
                        togglePanel()
                        if (panel == null) scheduleRetract()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) dockBubbleAndScheduleRetract() else if (panel == null) scheduleRetract()
                    true
                }
                else -> false
            }
        }
        runCatching { windowManager.addView(icon, params) }.onFailure {
            bubble = null
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            stopSelf()
        }.onSuccess {
            scheduleRetract()
        }
    }

    /** Snap the spider to an edge, then let it tuck partly off-screen while remaining tappable. */
    private fun dockBubbleAndScheduleRetract() {
        val params = bubbleParams ?: return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val width = resources.displayMetrics.widthPixels
        dockedLeft = params.x + dp(30) < width / 2
        val targetX = if (dockedLeft) dp(4) else max(dp(4), width - dp(64))
        animateBubbleTo(targetX, params.y, 220) {
            tucked = false
            bubble?.alpha = 1f
            prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
            scheduleRetract()
        }
    }

    private fun expandBubble(onExpanded: (() -> Unit)? = null) {
        val params = bubbleParams ?: return
        cancelRetract()
        val width = resources.displayMetrics.widthPixels
        dockedLeft = params.x + dp(30) < width / 2 || (tucked && params.x < 0)
        val targetX = if (dockedLeft) dp(4) else max(dp(4), width - dp(64))
        tucked = false
        bubble?.alpha = 1f
        animateBubbleTo(targetX, params.y.coerceAtLeast(dp(12)), 260, onExpanded)
    }

    private fun scheduleRetract() {
        cancelRetract()
        if (panel != null || bubble == null) return
        val task = Runnable {
            if (panel == null && bubble != null) retractBubble()
        }
        retractRunnable = task
        mainHandler.postDelayed(task, RETRACT_DELAY_MS)
    }

    private fun cancelRetract() {
        retractRunnable?.let(mainHandler::removeCallbacks)
        retractRunnable = null
    }

    private fun retractBubble() {
        val params = bubbleParams ?: return
        if (panel != null) return
        val width = resources.displayMetrics.widthPixels
        dockedLeft = params.x + dp(30) < width / 2
        val targetX = if (dockedLeft) -dp(BUBBLE_SIZE_DP - TUCK_VISIBLE_DP) else width - dp(TUCK_VISIBLE_DP)
        animateBubbleTo(targetX, params.y, 360) {
            tucked = true
            bubble?.alpha = 0.82f
        }
    }

    private fun animateBubbleTo(targetX: Int, targetY: Int, duration: Long, onEnd: (() -> Unit)? = null) {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        cancelBubbleAnimation()
        val startX = params.x
        val startY = params.y
        val height = resources.displayMetrics.heightPixels
        val safeY = targetY.coerceIn(dp(8), max(dp(8), height - dp(72)))
        bubbleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                params.x = (startX + ((targetX - startX) * fraction)).toInt()
                params.y = (startY + ((safeY - startY) * fraction)).toInt()
                runCatching { windowManager.updateViewLayout(view, params) }
                repositionPanel()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (bubbleAnimator === animation) bubbleAnimator = null
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun cancelBubbleAnimation() {
        val old = bubbleAnimator ?: return
        bubbleAnimator = null
        old.removeAllUpdateListeners()
        old.removeAllListeners()
        old.cancel()
    }

    private fun togglePanel() {
        if (panel != null) {
            removePanel()
            scheduleRetract()
            return
        }
        cancelRetract()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.rgb(20, 16, 29))
                setStroke(dp(1), Color.rgb(128, 88, 185))
            }
            elevation = dp(12).toFloat()
        }
        val heading = TextView(this).apply {
            text = "🕷  ИИ Паук"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(2), dp(0), dp(2), dp(6))
        }
        box.addView(heading)
        statusView = TextView(this).apply {
            text = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATUS, "Готов к работе · нажмите действие")
            textSize = 11f
            setTextColor(Color.rgb(211, 194, 233))
            maxLines = 2
            setPadding(dp(2), dp(0), dp(2), dp(8))
        }
        box.addView(statusView)
        addActionRow(box, listOf("💬 Чат" to "newchat", "🌐 Браузер" to "browser"))
        addActionRow(box, listOf("♫ Медиа" to "media", "⌘ Терминал" to "terminal"))
        addActionRow(box, listOf("🧠 Агент" to "spiderai", "⌨ Code/Build" to "code"))
        addActionRow(box, listOf("🏠 Паук" to "home", "↘ К краю" to "__tuck"))
        addActionRow(box, listOf("⏻ Выключить виджет" to "__hide"))
        panel = box
        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, 0, 0)
        p.width = dp(248)
        panelParams = p
        repositionPanel()
        runCatching { windowManager.addView(box, p) }.onFailure {
            panel = null
            panelParams = null
            scheduleRetract()
        }
    }

    private fun addActionRow(parent: LinearLayout, actions: List<Pair<String, String>>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.forEach { (label, section) ->
            val button = Button(this).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(61, 43, 82))
                setPadding(dp(2), dp(0), dp(2), dp(0))
                setOnClickListener {
                    removePanel()
                    when (section) {
                        "__hide" -> disable(this@SpiderOverlayService)
                        "__tuck" -> {
                            removePanel()
                            dockBubbleAndScheduleRetract()
                        }
                        else -> {
                            openSection(section)
                            scheduleRetract()
                        }
                    }
                }
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        parent.addView(row)
    }

    private fun repositionPanel() {
        val view = panel ?: return
        val p = panelParams ?: return
        val bubbleP = bubbleParams ?: return
        val metrics = resources.displayMetrics
        val measuredWidth = if (p.width > 0) p.width else dp(248)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(measuredWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST)
        )
        val h = max(dp(230), view.measuredHeight)
        p.x = (bubbleP.x + dp(60) - measuredWidth).coerceIn(0, max(0, metrics.widthPixels - measuredWidth))
        val above = bubbleP.y - h - dp(8)
        p.y = if (above >= dp(8)) above else (bubbleP.y + dp(68)).coerceIn(0, max(0, metrics.heightPixels - h))
        if (view.isAttachedToWindow) runCatching { windowManager.updateViewLayout(view, p) }
    }

    private fun openSection(section: String) {
        if (section == "terminal") {
            runCatching {
                startActivity(Intent(this, SpiderTerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                updateStatus(this, "Не удалось открыть Spider Terminal")
            }
            updateStatus(this, "Открываю Android system shell в песочнице приложения")
            return
        }
        val target = if (section == "home") "" else section
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (target.isNotBlank()) putExtra(MainActivity.EXTRA_OVERLAY_SECTION, target)
        }
        runCatching { startActivity(launch) }
        updateStatus(this, when (section) {
            "media" -> "Открываю Spider Media"
            "browser" -> "Открываю Spider Browser"
            "terminal" -> "Открываю Spider Terminal"
            "code" -> "Открываю Spider Code & Build"
            "spiderai" -> "Открываю Spider AI"
            "newchat" -> "Открываю чат"
            else -> "Паук готов к работе"
        })
    }

    private fun removePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        panelParams = null
        statusView = null
    }

    private fun refreshStatus() {
        statusView?.text = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATUS, "Готов к работе")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelRetract()
        cancelBubbleAnimation()
        removePanel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        bubbleParams = null
        if (runningInstance === this) runningInstance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.iipauk.spider.action.STOP_OVERLAY"
        const val EXTRA_UNUSED = "com.iipauk.spider.EXTRA_UNUSED"
        private const val CHANNEL_ID = "spider_overlay"
        private const val NOTIFICATION_ID = 7101
        private const val PREFS = "spider_overlay"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STATUS = "status"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val RETRACT_DELAY_MS = 5_500L
        private const val BUBBLE_SIZE_DP = 60
        private const val TUCK_VISIBLE_DP = 24
        @Volatile private var runningInstance: SpiderOverlayService? = null

        fun updateStatus(context: Context, status: String) {
            val safe = status.trim().take(120).ifBlank { "Готов к работе" }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATUS, safe).apply()
            runningInstance?.refreshStatus()
        }

        fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun disable(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            context.stopService(Intent(context, SpiderOverlayService::class.java))
        }
    }
}
