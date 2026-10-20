package com.iipauk.spider

/** User-selected routing; local-only is the conservative default. */
enum class SpiderLlmMode(val title: String) {
    LOCAL_ONLY("Локально — без интернета"),
    YANDEX_CLOUD("Яндекс AI Studio через backend API"),
    OPENCODE_ZEN("OpenCode Zen API — без своего сервера"),
    LOCAL_THEN_YANDEX("Сначала локально, Яндекс по разрешённому fallback");

    companion object {
        fun fromStored(value: String?): SpiderLlmMode = entries.firstOrNull { it.name == value } ?: LOCAL_ONLY
    }
}
