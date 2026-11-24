package com.rizky088.mochymodules.megalobiz

import com.mochyy.mochymusic.module.*
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToInt

class MegalobizLyricsModule : LyricsModule, ModuleFields {

    override val info = ModuleInfo(
        id = "megalobiz-lyrics",
        name = "Megalobiz",
        version = "1.0.0",
        description = "Synchronized LRC lyrics from Megalobiz Public API v1.",
        sourceUrl = "https://github.com/Rizky088/Mochy-Lyrics-Modules",
        author = "Rizky088",
    )

    override val fields = listOf(
        ModuleField(
            id = "api_key",
            label = "Megalobiz API Key (optional)",
            kind = FieldKind.Text,
            hint = "mbz_...",
            description = "Optional free key from megalobiz.com/developers (raises limit from 30 to 300 req/min).",
        ),
    )

    private lateinit var host: ModuleHost
    @Volatile private var apiKey: String = ""

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override fun onFields(values: Map<String, String>) {
        apiKey = values["api_key"]?.trim().orEmpty()
    }

    override suspend fun lyrics(track: TrackQuery): String? {
        val rawTitle = track.title.trim()
        if (rawTitle.isEmpty()) return null
        val artists = splitArtists(track.artists)
        val primaryArtist = artists.firstOrNull().orEmpty()
        val durationSec = track.durationMs?.takeIf { it > 0 }?.let { (it / 1000.0).roundToInt() }

        val normalizedQuery = track.copy(artists = artists.ifEmpty { track.artists })

        var results = search(title = rawTitle, artist = primaryArtist, query = null, durationSec = durationSec)
        if (results.isEmpty()) {
            val cleanedTitle = TrackMatcher.core(rawTitle).ifBlank { rawTitle }
            results = search(title = null, artist = null, query = "$cleanedTitle $primaryArtist".trim(), durationSec = durationSec)
        }
        if (results.isEmpty()) return null

        val best = pickBest(normalizedQuery, results) ?: return null
        val lrcUrl = best.lrcUrl.ifBlank { "$BASE_URL/lrc/${best.id}?format=lrc" }
        val res = host.http(HttpRequest(url = lrcUrl, headers = requestHeaders()))
        if (!res.ok) {
            host.log("Megalobiz LRC fetch HTTP ${res.code} for id=${best.id}")
            return null
        }
        val cleaned = cleanLrc(res.text)
        return cleaned.takeIf { TIMED_LINE_REGEX.containsMatchIn(it) }
    }

    private data class MegalobizHit(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long?,
        val durationDiff: Double?,
        val sync: String,
        val apiScore: Double,
        val lrcUrl: String,
    )

    private fun search(title: String?, artist: String?, query: String?, durationSec: Int?): List<MegalobizHit> {
        val params = buildList {
            if (!query.isNullOrBlank()) {
                add("q=${enc(query)}")
            } else {
                if (!title.isNullOrBlank()) add("title=${enc(title)}")
                if (!artist.isNullOrBlank()) add("artist=${enc(artist)}")
            }
            if (durationSec != null && durationSec > 0) add("duration=$durationSec")
            add("limit=10")
        }.joinToString("&")

        val res = host.http(HttpRequest(url = "$BASE_URL/search?$params", headers = requestHeaders()))
        if (res.code == 429) {
            host.log("Megalobiz rate limited (429)")
            return emptyList()
        }
        if (!res.ok) return emptyList()

        val root = runCatching { JSONObject(res.text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isEmpty()) continue
                val dur = if (o.has("duration") && !o.isNull("duration")) o.optDouble("duration", -1.0) else -1.0
                val diff = if (o.has("duration_diff") && !o.isNull("duration_diff")) o.optDouble("duration_diff", Double.NaN) else Double.NaN
                add(
                    MegalobizHit(
                        id = id,
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        durationMs = if (dur > 0) (dur * 1000.0).toLong() else null,
                        durationDiff = if (diff.isFinite()) abs(diff) else null,
                        sync = o.optString("sync"),
                        apiScore = o.optDouble("score", 0.0),
                        lrcUrl = o.optString("lrc_url"),
                    )
                )
            }
        }
    }

    private fun pickBest(query: TrackQuery, hits: List<MegalobizHit>): MegalobizHit? {
        val byId = hits.associateBy { it.id }
        val candidates = hits.map { CatalogTrack(id = it.id, title = it.title, artist = it.artist, durationMs = it.durationMs) }
        val ranked = TrackMatcher.ranked(query, candidates)
        if (ranked.isNotEmpty()) {
            val topScore = ranked.first().score
            val finalists = ranked.filter { topScore - it.score <= 0.08f }.mapNotNull { byId[it.track.id] }
            return finalists.sortedWith(
                compareByDescending<MegalobizHit> { it.sync.equals("manual", ignoreCase = true) }
                    .thenBy { it.durationDiff ?: 999.0 }
                    .thenByDescending { it.apiScore }
            ).firstOrNull()
        }
        // Fallback when titles use alternate scripts (e.g., romanized vs Japanese) and Megalobiz scored high with tight duration
        return hits
            .filter { it.apiScore >= 0.85 && (it.durationDiff == null || it.durationDiff <= 4.0) }
            .sortedWith(
                compareByDescending<MegalobizHit> { it.sync.equals("manual", ignoreCase = true) }
                    .thenByDescending { it.apiScore }
                    .thenBy { it.durationDiff ?: 999.0 }
            )
            .firstOrNull()
    }

    private fun cleanLrc(raw: String): String =
        raw.replace("\r\n", "\n")
            .lineSequence()
            .filterNot { line ->
                val low = line.trim().lowercase()
                low.startsWith("[by:") || low.contains("megalobiz.com")
            }
            .joinToString("\n")
            .trim()

    private fun requestHeaders(): Map<String, String> = buildMap {
        put("User-Agent", USER_AGENT)
        put("Accept", "application/json, text/plain")
        if (apiKey.isNotBlank()) put("X-API-Key", apiKey)
    }

    private fun splitArtists(artists: List<String>): List<String> =
        artists.flatMap { it.split(ARTIST_SPLIT_REGEX) }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val BASE_URL = "https://api.megalobiz.com/api/public/v1"
        const val USER_AGENT = "MochyExt-Megalobiz/1.0 (https://github.com/Rizky088/Mochy-Lyrics-Modules)"
        val TIMED_LINE_REGEX = Regex("""\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]""")
        val ARTIST_SPLIT_REGEX = Regex("""\s*(?:,|&|/|feat\.|ft\.|featuring|×)\s*""", RegexOption.IGNORE_CASE)
    }
}
