package com.rizky088.mochymodules.petitlyrics

import android.util.Base64
import android.util.Xml
import com.mochyy.mochymusic.module.*
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.util.Locale

class PetitLyricsModule : LyricsModule, ModuleFields {

    override val info = ModuleInfo(
        id = "petitlyrics",
        name = "PetitLyrics",
        version = "1.0.0",
        description = "Word-synced (WSY) and plain lyrics from PetitLyrics — great for Japanese, J-Pop & anime.",
        sourceUrl = "https://github.com/Rizky088/Mochy-Lyrics-Modules",
        author = "Rizky088",
    )

    override val fields = listOf(
        ModuleField(
            id = "word_sync",
            label = "Word-by-word sync",
            kind = FieldKind.Toggle,
            default = "true",
            description = "Use PetitLyrics WSY word/character timing when available.",
        ),
    )

    private lateinit var host: ModuleHost
    @Volatile private var wordSync: Boolean = true

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override fun onFields(values: Map<String, String>) {
        wordSync = values["word_sync"]?.lowercase() != "false"
    }

    override suspend fun lyrics(track: TrackQuery): String? {
        val rawTitle = track.title.trim()
        if (rawTitle.isEmpty()) return null
        val artists = splitArtists(track.artists)
        val primaryArtist = artists.firstOrNull().orEmpty()
        val normalized = track.copy(artists = artists.ifEmpty { track.artists })

        // 1. Try word-synced (lyricsType = 3 -> unencrypted <wsy> XML)
        val wsySong = findSong(normalized, rawTitle, primaryArtist, lyricsType = 3)
        if (wsySong != null) {
            val decoded = decodeBase64Utf8(wsySong.lyricsData)
            if (decoded != null && decoded.contains("<wsy>")) {
                val lrc = parseWsyToLrc(decoded, includeWords = wordSync)
                if (!lrc.isNullOrBlank()) return lrc
            }
        }

        // 2. Fallback to plain text (lyricsType = 1)
        val plainSong = findSong(normalized, rawTitle, primaryArtist, lyricsType = 1)
        if (plainSong != null) {
            val plain = decodeBase64Utf8(plainSong.lyricsData)?.replace("\r\n", "\n")?.trim()
            if (!plain.isNullOrBlank() && !plain.startsWith("MHDROBJT")) return plain
        }
        return null
    }

    private data class PetitSong(
        val id: String,
        val title: String,
        val artist: String,
        val isrc: String,
        val lyricsData: String,
    )

    private fun findSong(query: TrackQuery, rawTitle: String, primaryArtist: String, lyricsType: Int): PetitSong? {
        val isrc = query.isrc?.trim()
        if (!isrc.isNullOrEmpty()) {
            val byIsrc = fetchSongs(mapOf("key_isrc" to isrc), lyricsType)
            byIsrc.firstOrNull { it.lyricsData.isNotBlank() }?.let { return it }
        }

        val attempts = buildList {
            if (primaryArtist.isNotBlank()) add(mapOf("key_title" to rawTitle, "key_artist" to primaryArtist))
            val cleaned = TrackMatcher.core(rawTitle)
            if (cleaned.isNotBlank() && !cleaned.equals(rawTitle, ignoreCase = true) && primaryArtist.isNotBlank()) {
                add(mapOf("key_title" to cleaned, "key_artist" to primaryArtist))
            }
            add(mapOf("key_title" to rawTitle))
        }

        for (params in attempts) {
            val songs = fetchSongs(params, lyricsType).filter { it.lyricsData.isNotBlank() }
            if (songs.isEmpty()) continue
            val matched = pickSong(query, songs)
            if (matched != null) return matched
        }
        return null
    }

    private fun pickSong(query: TrackQuery, songs: List<PetitSong>): PetitSong? {
        val byId = songs.associateBy { it.id }
        val candidates = songs.map { CatalogTrack(id = it.id, title = it.title, artist = it.artist, isrc = it.isrc.ifBlank { null }) }
        val best = TrackMatcher.best(query, candidates)
        if (best != null) return byId[best.track.id]

        // PetitLyrics often matches JP/kanji titles directly; accept if exact title matches ignoring case/whitespace
        val qTitle = TrackMatcher.core(query.title)
        return songs.firstOrNull { s ->
            val sTitle = TrackMatcher.core(s.title)
            sTitle.isNotEmpty() && (sTitle == qTitle || s.title.equals(query.title, ignoreCase = true))
        }
    }

    private fun fetchSongs(extra: Map<String, String>, lyricsType: Int): List<PetitSong> {
        val body = buildMap {
            putAll(extra)
            put("lyricsType", lyricsType.toString())
            put("terminalType", "10")
            put("clientAppId", CLIENT_APP_ID)
            put("maxCount", "5")
        }.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

        val res = host.http(
            HttpRequest(
                url = API_URL,
                method = "POST",
                headers = mapOf("User-Agent" to USER_AGENT),
                body = body.toByteArray(Charsets.UTF_8),
                contentType = "application/x-www-form-urlencoded; charset=UTF-8",
            )
        )
        if (!res.ok) return emptyList()
        return parseResponseXml(res.text)
    }

    private fun parseResponseXml(xml: String): List<PetitSong> = runCatching {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val out = mutableListOf<PetitSong>()
        var inSong = false
        var tag: String? = null
        var id = ""
        var title = ""
        var artist = ""
        var isrc = ""
        var data = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "song") {
                        inSong = true
                        id = ""; title = ""; artist = ""; isrc = ""; data = ""
                    }
                }
                XmlPullParser.TEXT -> if (inSong) {
                    when (tag) {
                        "lyricsId" -> id += parser.text
                        "title" -> title += parser.text
                        "artist" -> artist += parser.text
                        "isrc" -> isrc += parser.text
                        "lyricsData" -> data += parser.text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "song" && inSong) {
                        inSong = false
                        out += PetitSong(
                            id = id.trim().ifEmpty { out.size.toString() },
                            title = title.trim(),
                            artist = artist.trim(),
                            isrc = isrc.trim(),
                            lyricsData = data.trim(),
                        )
                    }
                    tag = null
                }
            }
            event = parser.next()
        }
        out
    }.getOrDefault(emptyList())

    private data class WsyWord(val text: String, val startMs: Long, val endMs: Long)

    private fun parseWsyToLrc(wsyXml: String, includeWords: Boolean): String? = runCatching {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(wsyXml))
        val out = StringBuilder()

        var inLine = false
        var inWord = false
        var tag: String? = null
        var lineText = StringBuilder()
        val words = mutableListOf<WsyWord>()

        var wStart = 0L
        var wEnd = 0L
        var wText = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    when (tag) {
                        "line" -> {
                            inLine = true
                            lineText.clear()
                            words.clear()
                        }
                        "word" -> if (inLine) {
                            inWord = true
                            wStart = 0L
                            wEnd = 0L
                            wText.clear()
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val txt = parser.text ?: ""
                    if (inWord) {
                        when (tag) {
                            "starttime" -> wStart = txt.trim().toLongOrNull() ?: wStart
                            "endtime" -> wEnd = txt.trim().toLongOrNull() ?: wEnd
                            "wordstring" -> wText.append(txt)
                        }
                    } else if (inLine && tag == "linestring") {
                        lineText.append(txt)
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "word" -> if (inWord) {
                            inWord = false
                            val wt = wText.toString()
                            if (wt.isNotEmpty()) {
                                val safeEnd = if (wEnd > wStart) wEnd else wStart + 60L
                                words += WsyWord(wt, wStart, safeEnd)
                            }
                        }
                        "line" -> if (inLine) {
                            inLine = false
                            val fullLine = lineText.toString().trim().ifEmpty { words.joinToString("") { it.text }.trim() }
                            val firstStart = words.firstOrNull()?.startMs
                            if (fullLine.isNotEmpty() && firstStart != null) {
                                out.append(stamp(firstStart)).append(fullLine).append('\n')
                                if (includeWords) {
                                    val cleanWords = words.mapNotNull { w ->
                                        val clean = w.text.trim().filterNot { it in WORD_SEPARATORS }
                                        if (clean.isEmpty()) null else WsyWord(clean, w.startMs, w.endMs)
                                    }
                                    if (cleanWords.isNotEmpty()) {
                                        cleanWords.joinTo(out, separator = "|", prefix = "<", postfix = ">\n") { w ->
                                            "${w.text}:${w.startMs / 1000.0}:${w.endMs / 1000.0}"
                                        }
                                    }
                                }
                            }
                        }
                    }
                    tag = null
                }
            }
            event = parser.next()
        }
        out.toString().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun decodeBase64Utf8(b64: String): String? = runCatching {
        Base64.decode(b64.trim(), Base64.DEFAULT).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun stamp(ms: Long): String =
        String.format(Locale.US, "[%02d:%02d.%03d]", ms / 60_000, (ms / 1000) % 60, ms % 1000)

    private fun splitArtists(artists: List<String>): List<String> =
        artists.flatMap { it.split(ARTIST_SPLIT_REGEX) }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val API_URL = "https://p1.petitlyrics.com/api/GetPetitLyricsData.php"
        const val CLIENT_APP_ID = "p1110417"
        const val USER_AGENT = "MochyExt-PetitLyrics/1.0"
        const val WORD_SEPARATORS = ":|<>"
        val ARTIST_SPLIT_REGEX = Regex("""\s*(?:,|&|/|feat\.|ft\.|featuring|×)\s*""", RegexOption.IGNORE_CASE)
    }
}
