package com.droidbubble.app.island

import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(
    val timeMs: Long,
    val text: String
)

object LyricsManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var fetchJob: Job? = null

    private val _currentTrackKey = MutableStateFlow("")
    private val _currentLyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val currentLyrics: StateFlow<List<LyricLine>> = _currentLyrics.asStateFlow()

    // Cache up to 50 tracks in memory (even empty lists for tracks without available lyrics to avoid re-fetching)
    private val lyricsCache = LruCache<String, List<LyricLine>>(50)

    private val lrcTimestampRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun onTrackChanged(title: String?, artist: String?, durationMs: Long = 0L, embeddedLyrics: String? = null) {
        val cleanTitle = cleanTrackTitle(title ?: "")
        val cleanArtist = cleanArtistName(artist ?: "")
        if (cleanTitle.isBlank() || cleanTitle.equals("No Media Playing", ignoreCase = true) || cleanTitle.equals("Playing Audio", ignoreCase = true)) {
            _currentTrackKey.value = ""
            _currentLyrics.value = emptyList()
            fetchJob?.cancel()
            return
        }

        val key = "${cleanTitle.lowercase()}::${cleanArtist.lowercase()}"
        if (_currentTrackKey.value == key && _currentLyrics.value.isNotEmpty()) {
            return
        }
        _currentTrackKey.value = key

        // 1. Check embedded lyrics from MediaMetadata first if synced
        if (!embeddedLyrics.isNullOrBlank()) {
            val parsedEmbedded = parseSyncedLrc(embeddedLyrics)
            if (parsedEmbedded.isNotEmpty()) {
                lyricsCache.put(key, parsedEmbedded)
                _currentLyrics.value = parsedEmbedded
                return
            }
        }

        // 2. Check memory cache
        val cached = lyricsCache.get(key)
        if (cached != null) {
            _currentLyrics.value = cached
            return
        }

        // Reset while fetching so stale lyrics from previous song are never shown
        _currentLyrics.value = emptyList()
        fetchJob?.cancel()
        fetchJob = scope.launch {
            val fetched = fetchSyncedLyricsFromLrclib(cleanTitle, cleanArtist, durationMs)
            lyricsCache.put(key, fetched)
            if (_currentTrackKey.value == key) {
                _currentLyrics.value = fetched
            }
        }
    }

    /**
     * Returns the active lyric line for the given playback position in milliseconds,
     * or null if no lyrics are available.
     */
    fun getCurrentLine(lines: List<LyricLine>, positionMs: Long): LyricLine? {
        if (lines.isEmpty()) return null
        // Lead by 120ms so the line transitions right on beat
        val adjustedPos = (positionMs + 120L).coerceAtLeast(0L)
        var low = 0
        var high = lines.lastIndex
        var bestIndex = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= adjustedPos) {
                bestIndex = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (bestIndex >= 0) {
            lines[bestIndex].takeIf { it.text.isNotBlank() }
        } else {
            // If playback is right at the start before the first timestamp (within 4 seconds of first line), show first line
            val first = lines.firstOrNull { it.text.isNotBlank() }
            if (first != null && first.timeMs - adjustedPos <= 3500L) first else null
        }
    }

    private fun cleanTrackTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*\((?:Official|Lyric|Audio|Music|Video|Visualizer|HD|HQ|4K|Remaster(?:ed)?|feat\.|ft\.)[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[(?:Official|Lyric|Audio|Music|Video|Visualizer|HD|HQ|4K|Remaster(?:ed)?|feat\.|ft\.)[^\]]*]""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanArtistName(raw: String): String {
        return raw
            .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            .split(",", ";", "&", " feat.", " ft.", " Featuring ")
            .firstOrNull()
            ?.trim() ?: raw.trim()
    }

    private fun fetchSyncedLyricsFromLrclib(title: String, artist: String, durationMs: Long): List<LyricLine> {
        // 1. Try exact/get endpoint if artist is available
        if (artist.isNotBlank() && !artist.equals("Unknown Artist", ignoreCase = true)) {
            runCatching {
                val encodedTitle = URLEncoder.encode(title, "UTF-8")
                val encodedArtist = URLEncoder.encode(artist, "UTF-8")
                val durationSec = if (durationMs > 2000L) "&duration=${durationMs / 1000L}" else ""
                val url = "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist$durationSec"
                val response = httpGet(url)
                if (!response.isNullOrBlank()) {
                    val obj = JSONObject(response)
                    val synced = obj.optString("syncedLyrics", "")
                    val parsed = parseSyncedLrc(synced)
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        }

        // 2. Fallback to /api/search?q=...
        runCatching {
            val query = if (artist.isNotBlank() && !artist.equals("Unknown Artist", ignoreCase = true)) {
                "$title $artist"
            } else {
                title
            }
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$encodedQuery"
            val response = httpGet(url)
            if (!response.isNullOrBlank()) {
                val arr = JSONArray(response)
                var bestMatch: List<LyricLine> = emptyList()
                var bestDurationDiff = Long.MAX_VALUE

                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val synced = item.optString("syncedLyrics", "")
                    if (synced.isBlank()) continue
                    val parsed = parseSyncedLrc(synced)
                    if (parsed.isEmpty()) continue

                    if (durationMs > 2000L) {
                        val itemDurMs = (item.optDouble("duration", 0.0) * 1000.0).toLong()
                        val diff = kotlin.math.abs(itemDurMs - durationMs)
                        if (diff < bestDurationDiff) {
                            bestDurationDiff = diff
                            bestMatch = parsed
                        }
                    } else {
                        return parsed
                    }
                }
                if (bestMatch.isNotEmpty()) return bestMatch
            }
        }

        return emptyList()
    }

    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Droidbubble/2.0 (https://github.com/droidbubble)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun parseSyncedLrc(lrcContent: String?): List<LyricLine> {
        if (lrcContent.isNullOrBlank()) return emptyList()
        val result = mutableListOf<LyricLine>()
        for (rawLine in lrcContent.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val matches = lrcTimestampRegex.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            val textStart = matches.last().range.last + 1
            // Strip any inline word-level timestamps like <00:12.34> so it stays strictly per-line karaoke
            val cleanText = if (textStart < trimmed.length) {
                trimmed.substring(textStart)
                    .replace(Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>"""), "")
                    .trim()
            } else {
                ""
            }

            for (match in matches) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = match.groupValues.getOrNull(3) ?: ""
                val millis = when (fracStr.length) {
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                    3 -> fracStr.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val totalMs = (min * 60_000L) + (sec * 1_000L) + millis
                result.add(LyricLine(timeMs = totalMs, text = cleanText))
            }
        }
        return result
            .sortedBy { it.timeMs }
            .filter { it.text.isNotBlank() }
    }
}

