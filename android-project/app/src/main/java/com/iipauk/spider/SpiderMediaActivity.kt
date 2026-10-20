package com.iipauk.spider

import androidx.activity.ComponentActivity
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java.net.URI
import java.util.UUID

/**
 * Native Media3 audio/video player.
 * Supports user-picked local documents, direct HTTPS media URLs, HLS and DASH streams.
 * URL query strings (which may contain signed access tokens) are never shown in status text.
 */
@OptIn(UnstableApi::class)
class SpiderMediaActivity : ComponentActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private lateinit var queueView: TextView
    private lateinit var sourceAddress: EditText
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var pendingInitialUrl: String? = null
    private var pendingLocalUris: List<Uri> = emptyList()
    private val pendingQueuedUrls = mutableListOf<String>()
    private var playerListener: Player.Listener? = null

    private val openDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val controller = mediaController
        if (controller != null) enqueueLocalUris(controller, uris)
        else {
            pendingLocalUris = uris
            statusView.text = "Файлы выбраны. Подключаю медиадвижок…"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 14, 23)
        window.navigationBarColor = Color.rgb(16, 14, 23)
        pendingInitialUrl = intent.getStringExtra(EXTRA_MEDIA_URL)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
            setBackgroundColor(Color.rgb(16, 14, 23))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "🕷  Spider Media"
            textSize = 21f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Button(this).apply {
            text = "Назад"
            setOnClickListener { finish() }
        })
        page.addView(top)

        page.addView(TextView(this).apply {
            text = "Аудио и видео • телефон • HTTPS-сервер • интернет"
            textSize = 13f
            setTextColor(Color.rgb(199, 180, 224))
            setPadding(0, dp(6), 0, dp(12))
        })

        playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
            contentDescription = "Управление воспроизведением Spider Media"
        }
        page.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(235)))

        page.addView(TextView(this).apply {
            text = "Интернет или сервер (прямая HTTPS-ссылка)"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(6))
        })
        sourceAddress = EditText(this).apply {
            hint = "https://server.example/video.mp4"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(151, 141, 167))
            textSize = 14f
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            backgroundTintList = ColorStateList.valueOf(Color.rgb(137, 103, 190))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        page.addView(sourceAddress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val urlButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urlButtons.addView(Button(this).apply {
            text = "Воспроизвести"
            setOnClickListener { playHttpsSource(sourceAddress.text?.toString().orEmpty()) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
        urlButtons.addView(Button(this).apply {
            text = "В очередь"
            setOnClickListener { enqueueHttpsSource(sourceAddress.text?.toString().orEmpty()) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        page.addView(urlButtons)

        page.addView(Button(this).apply {
            text = "Открыть аудио/видео на телефоне или в файловом провайдере"
            setOnClickListener { openDocuments.launch(arrayOf("audio/*", "video/*")) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        val queueHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        queueHeader.addView(TextView(this).apply {
            text = "Очередь воспроизведения"
            textSize = 15f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        queueHeader.addView(Button(this).apply {
            text = "Очистить"
            setOnClickListener {
                mediaController?.let { controller ->
                    controller.stop()
                    controller.clearMediaItems()
                    statusView.text = "Очередь очищена."
                    renderQueue(controller)
                }
            }
        })
        page.addView(queueHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        queueView = TextView(this).apply {
            text = "Очередь пока пуста. Можно выбрать несколько файлов или добавить HTTPS-ссылки."
            textSize = 12f
            setTextColor(Color.rgb(187, 177, 201))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(29, 25, 39))
        }
        page.addView(queueView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navigation.addView(Button(this).apply {
            text = "⏮ Назад"
            setOnClickListener { mediaController?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() } }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        navigation.addView(Button(this).apply {
            text = "Вперёд ⏭"
            setOnClickListener { mediaController?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        page.addView(navigation)

        statusView = TextView(this).apply {
            text = "Готово. Выбери локальный файл или введи прямую HTTPS-ссылку на медиа."
            textSize = 12f
            setTextColor(Color.rgb(187, 177, 201))
            setPadding(2, dp(12), 2, 0)
        }
        page.addView(statusView)
        page.addView(TextView(this).apply {
            text = "Поддерживаются обычные медиафайлы, HLS (.m3u8) и MPEG-DASH (.mpd). HTTPS обязателен для сетевых адресов. Форматы кодеков зависят от Android-устройства."
            textSize = 11f
            setTextColor(Color.rgb(151, 141, 167))
            setPadding(2, dp(10), 2, 0)
        })

        setContentView(scroll)
        sourceAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                playHttpsSource(sourceAddress.text?.toString().orEmpty())
                true
            } else false
        }
    }

    override fun onStart() {
        super.onStart()
        val future = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, SpiderPlaybackService::class.java))
        ).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future || isFinishing || isDestroyed) {
                MediaController.releaseFuture(future)
                return@addListener
            }
            runCatching { future.get() }
                .onSuccess { controller ->
                    mediaController = controller
                    playerView.player = controller
                    val listener = object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            statusView.text = "Не удалось воспроизвести источник (${error.errorCodeName}). Проверь ссылку, доступ сервера и формат."
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_BUFFERING) statusView.text = "Буферизация медиа…"
                            if (playbackState == Player.STATE_READY && controller.isPlaying) statusView.text = "Воспроизведение идёт."
                            if (playbackState == Player.STATE_ENDED) statusView.text = "Воспроизведение завершено."
                            renderQueue(controller)
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying) statusView.text = "Воспроизведение идёт."
                            renderQueue(controller)
                        }
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            statusView.text = mediaItem?.mediaMetadata?.title?.toString()?.let { "Сейчас: $it" } ?: "Источник изменён."
                            renderQueue(controller)
                        }
                    }
                    playerListener = listener
                    controller.addListener(listener)
                    renderQueue(controller)
                    // Process the requested first source before queued selections to preserve user order.
                    processInitialUrl(controller)
                    if (pendingLocalUris.isNotEmpty()) {
                        val localUris = pendingLocalUris
                        pendingLocalUris = emptyList()
                        enqueueLocalUris(controller, localUris)
                    }
                    if (pendingQueuedUrls.isNotEmpty()) {
                        val queued = pendingQueuedUrls.toList()
                        pendingQueuedUrls.clear()
                        queued.forEach(::enqueueHttpsSource)
                    }
                }
                .onFailure {
                    statusView.text = "Не удалось подключить медиадвижок. Проверь Android-сборку и зависимости Media3."
                }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        playerListener?.let { listener -> mediaController?.removeListener(listener) }
        playerListener = null
        playerView.player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
        super.onStop()
    }

    private fun processInitialUrl(controller: MediaController) {
        val raw = pendingInitialUrl ?: return
        pendingInitialUrl = null
        val parsed = SpiderMediaUrlPolicy.parseHttpsUrl(raw)
        if (parsed == null) {
            statusView.text = "Ссылка отклонена: используй корректный HTTPS-адрес без логина и пароля в URL."
            return
        }
        val uri = Uri.parse(parsed.toASCIIString())
        if (controller.currentMediaItem?.localConfiguration?.uri == uri) return
        playUri(controller, uri, parsed)
        sourceAddress.setText(parsed.toASCIIString())
    }

    private fun playHttpsSource(raw: String) {
        val parsed = SpiderMediaUrlPolicy.parseHttpsUrl(raw)
        if (parsed == null) {
            val message = if (raw.trim().startsWith("http://", ignoreCase = true)) {
                "Незащищённый HTTP не поддерживается. Используй HTTPS-ссылку на медиа."
            } else {
                "Адрес не распознан. Введи прямую HTTPS-ссылку на аудио, видео, HLS или DASH."
            }
            statusView.text = message
            return
        }
        val controller = mediaController
        if (controller == null) {
            pendingInitialUrl = parsed.toASCIIString()
            statusView.text = "Адрес проверен. Подключаю медиадвижок…"
        } else {
            playUri(controller, Uri.parse(parsed.toASCIIString()), parsed)
        }
    }

    private fun enqueueHttpsSource(raw: String) {
        val parsed = SpiderMediaUrlPolicy.parseHttpsUrl(raw)
        if (parsed == null) {
            statusView.text = if (raw.trim().startsWith("http://", ignoreCase = true)) {
                "HTTP заблокирован. Для сетевого воспроизведения используй HTTPS."
            } else "Нужен корректный прямой HTTPS-адрес на медиа."
            return
        }
        val controller = mediaController
        if (controller == null) {
            pendingQueuedUrls += parsed.toASCIIString()
            statusView.text = "Адрес поставлен в очередь. Подключаю медиадвижок…"
            return
        }
        val item = buildNetworkMediaItem(parsed)
        if (controller.mediaItemCount == 0) {
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
        } else {
            controller.addMediaItem(item)
        }
        statusView.text = "Источник добавлен в очередь: ${item.mediaMetadata.title ?: "медиа"}."
        renderQueue(controller)
    }

    private fun playUri(controller: MediaController, uri: Uri, parsed: URI? = null) {
        runCatching {
            val item = if (parsed != null) buildNetworkMediaItem(parsed) else buildLocalMediaItem(uri)
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
            statusView.text = if (parsed == null) "Локальный файл передан медиадвижку." else "HTTPS-источник передан медиадвижку. Ожидаю ответ сервера…"
            renderQueue(controller)
        }.onFailure {
            statusView.text = "Не удалось открыть источник. Проверь доступность и формат медиа."
        }
    }

    private fun enqueueLocalUris(controller: MediaController, uris: List<Uri>) {
        val items = uris.map(::buildLocalMediaItem)
        runCatching {
            if (controller.mediaItemCount == 0) {
                controller.setMediaItems(items, 0, C.TIME_UNSET)
                controller.prepare()
                controller.play()
                statusView.text = "Добавлено файлов: ${items.size}. Воспроизведение началось."
            } else {
                controller.addMediaItems(items)
                statusView.text = "В очередь добавлено файлов: ${items.size}."
            }
            renderQueue(controller)
        }.onFailure {
            statusView.text = "Не удалось добавить выбранные файлы. Проверь разрешение на чтение."
        }
    }

    private fun buildLocalMediaItem(uri: Uri): MediaItem {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment ?: "Локальный медиафайл")
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId("local-${UUID.randomUUID()}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name.take(120)).build())
            .build()
    }

    private fun buildNetworkMediaItem(uri: URI): MediaItem {
        val mime = SpiderMediaUrlPolicy.inferMimeType(uri)
        val title = SpiderMediaUrlPolicy.displayTitle(uri)
        val builder = MediaItem.Builder()
            .setUri(Uri.parse(uri.toASCIIString()))
            // Avoid putting signed URLs or query-string tokens in the media id or visible metadata.
            .setMediaId("network-${UUID.randomUUID()}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        if (mime != null) builder.setMimeType(mime)
        return builder.build()
    }

    private fun renderQueue(controller: MediaController) {
        if (!::queueView.isInitialized) return
        val count = controller.mediaItemCount
        if (count == 0) {
            queueView.text = "Очередь пока пуста. Можно выбрать несколько файлов или добавить HTTPS-ссылки."
            return
        }
        val current = controller.currentMediaItemIndex
        val visible = minOf(count, MAX_VISIBLE_QUEUE_ITEMS)
        queueView.text = buildString {
            append("Всего: ").append(count).append("\n")
            for (index in 0 until visible) {
                val item = controller.getMediaItemAt(index)
                val title = item.mediaMetadata.title?.toString()?.take(72) ?: "Медиа ${index + 1}"
                append(if (index == current) "▶ " else "  ")
                append(index + 1).append(". ").append(title).append('\n')
            }
            if (count > visible) append("… ещё ").append(count - visible).append(" элементов")
        }.trimEnd()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MEDIA_URL = "com.iipauk.spider.EXTRA_MEDIA_URL"
        private const val MAX_VISIBLE_QUEUE_ITEMS = 6
    }
}

