package com.iipauk.spider

import java.util.Locale
import kotlin.math.sqrt

/**
 * Privacy-preserving semantic feature search for the current on-device memory MVP.
 *
 * This is not a neural embedding model. It creates deterministic dense feature vectors from
 * safe topic tokens, multilingual concept aliases, light morphology and character n-grams.
 * That enables offline synonym-aware retrieval without sending user topics to a provider.
 * The interface is intentionally small so a future on-device neural embedding model can replace
 * this encoder without changing memory/skill retrieval contracts.
 */
object SpiderSemanticSearch {
    private const val VECTOR_SIZE = 384
    private const val DIRECT_WEIGHT = 1.0
    private const val CONCEPT_WEIGHT = 1.6
    private const val MORPH_WEIGHT = 0.65
    private const val NGRAM_WEIGHT = 0.12

    private val conceptGroups: List<Pair<String, Set<String>>> = listOf(
        "inspect" to setOf("inspect", "inspection", "list", "show", "view", "browse", "explore", "examine", "review", "look", "просмотр", "посмотреть", "покажи", "показать", "список", "перечислить", "изучить", "проверить", "обзор", "обследовать"),
        "file" to setOf("file", "files", "document", "documents", "folder", "directory", "directories", "catalog", "catalogue", "storage", "файл", "файлы", "документ", "документы", "папка", "папки", "каталог", "каталоги", "директория", "директории", "хранилище"),
        "workspace" to setOf("workspace", "project", "repo", "repository", "workarea", "рабочее", "пространство", "проект", "репозиторий", "рабочепространство"),
        "code" to setOf("code", "coding", "program", "programming", "source", "developer", "development", "код", "кодирование", "программа", "программирование", "исходник", "исходники", "разработка", "разработчик"),
        "build" to setOf("build", "compile", "compilation", "package", "assemble", "gradle", "apk", "собрать", "сборка", "компиляция", "скомпилировать", "пакет", "приложение", "апк"),
        "video" to setOf("video", "movie", "film", "clip", "footage", "player", "playback", "видео", "фильм", "кино", "ролик", "клип", "плеер", "воспроизведение"),
        "audio" to setOf("audio", "sound", "music", "song", "track", "mp3", "soundtrack", "аудио", "звук", "музыка", "песня", "трек", "саундтрек"),
        "memory" to setOf("memory", "remember", "recall", "history", "experience", "learn", "learning", "adapt", "память", "запомнить", "вспомнить", "история", "опыт", "обучение", "самообучение", "адаптация"),
        "skill" to setOf("skill", "skills", "workflow", "procedure", "routine", "playbook", "навык", "навыки", "процесс", "процедура", "сценарий", "регламент", "инструкция"),
        "error" to setOf("error", "bug", "failure", "fault", "issue", "debug", "fix", "ошибка", "баг", "сбой", "неудача", "проблема", "отладка", "исправить", "исправление"),
        "search" to setOf("search", "find", "lookup", "retrieve", "query", "поиск", "найти", "искать", "подобрать", "извлечь", "запрос"),
        "test" to setOf("test", "tests", "testing", "verify", "validation", "benchmark", "evaluate", "тест", "тесты", "тестирование", "проверка", "проверить", "валидация", "оценка", "сравнить"),
        "network" to setOf("network", "server", "remote", "internet", "online", "cloud", "сеть", "сервер", "удалённый", "удаленный", "интернет", "онлайн", "облако"),
        "create" to setOf("create", "make", "generate", "write", "produce", "build", "создать", "сделать", "генерировать", "написать", "подготовить", "сформировать")
    )
    private val wordToConcept: Map<String, String> = buildMap {
        conceptGroups.forEach { (concept, words) -> words.forEach { put(normalize(it), concept) } }
    }

    /** Exposes the safe lexical normalization, not raw-text storage. */
    fun normalizedToken(token: String): String = normalize(token)

    fun encode(tokens: List<String>): FloatArray {
        val vector = FloatArray(VECTOR_SIZE)
        tokens.asSequence().map(::normalize).filter { it.length >= 3 }
            .distinct().take(FileSpiderLearningMemory.MAX_TOPIC_TOKENS).forEach { token ->
                addFeature(vector, "w:$token", DIRECT_WEIGHT)
                stem(token)?.takeIf { it != token && it.length >= 3 }?.let { addFeature(vector, "m:$it", MORPH_WEIGHT) }
                wordToConcept[token]?.let { addFeature(vector, "c:$it", CONCEPT_WEIGHT) }
                // Character trigrams are a low-weight fallback for inflections and small typos.
                if (token.length >= 4) {
                    for (i in 0..token.length - 3) addFeature(vector, "g:${token.substring(i, i + 3)}", NGRAM_WEIGHT)
                }
            }
        normalizeVector(vector)
        return vector
    }

    /**
     * Terms for the SQLite inverted index. They mirror the local vector features so retrieval
     * can shortlist candidates without loading the whole memory corpus into RAM.
     */
    fun searchTerms(tokens: List<String>): Set<String> {
        val result = linkedSetOf<String>()
        tokens.asSequence().map(::normalize).filter { it.length >= 3 }
            .distinct().take(FileSpiderLearningMemory.MAX_TOPIC_TOKENS).forEach { token ->
                result += "w:$token"
                stem(token)?.takeIf { it != token && it.length >= 3 }?.let { result += "m:$it" }
                wordToConcept[token]?.let { result += "c:$it" }
                if (token.length >= 4) {
                    for (i in 0..token.length - 3) result += "g:${token.substring(i, i + 3)}"
                }
            }
        return result
    }

    fun similarity(leftTokens: List<String>, rightTokens: List<String>): Double {
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        val left = encode(leftTokens)
        val right = encode(rightTokens)
        var dot = 0.0
        for (index in left.indices) dot += left[index].toDouble() * right[index].toDouble()
        val lexical = jaccard(canonicalSet(leftTokens), canonicalSet(rightTokens))
        // The vector captures synonym/concept overlap; lexical overlap remains a transparent tie-break.
        return (0.82 * dot.coerceIn(0.0, 1.0) + 0.18 * lexical).coerceIn(0.0, 1.0)
    }

    private fun canonicalSet(tokens: List<String>): Set<String> = tokens.map { token ->
        val cleaned = normalize(token)
        wordToConcept[cleaned]?.let { "concept:$it" } ?: cleaned
    }.toSet()

    private fun jaccard(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / (left union right).size.toDouble()
    }

    private fun addFeature(vector: FloatArray, feature: String, weight: Double) {
        val bucket = stableHash(feature).mod(VECTOR_SIZE)
        vector[bucket] += weight.toFloat()
    }

    private fun stableHash(value: String): Int {
        var hash = 0x811c9dc5.toInt()
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = hash xor (byte.toInt() and 0xff)
            hash *= 0x01000193
        }
        return hash and Int.MAX_VALUE
    }

    private fun normalizeVector(vector: FloatArray) {
        val norm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
        if (norm <= 0.0) return
        for (index in vector.indices) vector[index] = (vector[index] / norm).toFloat()
    }

    private fun normalize(token: String): String = token.lowercase(Locale.ROOT)
        .trim('_', '-', '.', ' ')
        .replace(Regex("[^a-zа-яё0-9_-]"), "")

    private fun stem(token: String): String? {
        val englishSuffixes = listOf("ization", "ational", "fulness", "iveness", "ments", "ment", "ingly", "edly", "ing", "ers", "ies", "ed", "es", "s")
        val russianSuffixes = listOf("иями", "ями", "ами", "ого", "ему", "ыми", "ими", "ость", "ение", "ения", "овать", "ировать", "ыми", "ий", "ый", "ая", "ое", "ые", "ов", "ев", "ам", "ям", "ах", "ях", "ом", "ем", "ы", "и", "а", "я")
        val suffix = (englishSuffixes + russianSuffixes).firstOrNull { token.length - it.length >= 3 && token.endsWith(it) }
        return suffix?.let { token.dropLast(it.length) }
    }
}
