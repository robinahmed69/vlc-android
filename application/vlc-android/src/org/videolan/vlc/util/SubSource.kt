package org.videolan.vlc.util

import android.net.Uri
import android.util.Log
import org.json.JSONObject
import org.videolan.vlc.gui.dialogs.State
import org.videolan.vlc.gui.dialogs.SubtitleItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

// SubSource API Key
const val SUBSOURCE_API_KEY = "sk_a19a276c61b62f24a483e49b3503dbaa797dfda9eea0e908081846d15019bfcb"

const val SUBSOURCE_ID_PREFIX = "ss_"
private const val SUBSOURCE_BASE = "https://api.subsource.net/api/v1"
private const val SUBSOURCE_LANGUAGE = "bengali"
private const val SUBSOURCE_LANGUAGE_CODE = "bn"
private const val SS_TAG = "SubSource"

object SubSource {

    fun isSubSourceItem(item: SubtitleItem) = item.idSubtitle.startsWith(SUBSOURCE_ID_PREFIX)

    private fun isKeySet() = SUBSOURCE_API_KEY.isNotBlank() && !SUBSOURCE_API_KEY.startsWith("PASTE")

    /**
     * Turns a file name like "The Paradise (2026) Hindi ESub [DDN].mkv" into "The Paradise"
     */
    fun cleanName(raw: String): String {
        var n = raw.replace(Regex("\\.(mkv|mp4|avi|webm|mov|m4v|ts|flv|wmv)$", RegexOption.IGNORE_CASE), "")
        n = n.replace(Regex("[\\[(].*?[\\])]"), " ")
        n = n.replace('.', ' ').replace('_', ' ')
        val cut = n.split(
            Regex(
                "\\b(19|20)\\d{2}\\b|\\b(480|720|1080|2160)p\\b|\\b(WEB|WEBRip|WEB-DL|BluRay|BRRip|HDRip|HDTV|DVDRip|HDCAM|CAMRip|x264|x265|HEVC|Hindi|English|Bengali|Dual|ESub)\\b",
                RegexOption.IGNORE_CASE
            )
        ).first().trim()
        val result = if (cut.isNotBlank()) cut else n.trim()
        return result.replace(Regex("\\s+"), " ")
    }

    private fun get(path: String): JSONObject? {
        val conn = URL("$SUBSOURCE_BASE$path").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("X-API-Key", SUBSOURCE_API_KEY)
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) {
                Log.w(SS_TAG, "HTTP ${conn.responseCode} for $path")
                null
            } else {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|#%]"), "_").trim()

    /**
     * Searches Bengali subtitles on SubSource. Blocking call, run it on an IO dispatcher.
     */
    fun searchBengali(mediaUri: Uri, rawName: String): List<SubtitleItem> {
        if (!isKeySet()) return emptyList()
        val query = cleanName(rawName)
        if (query.isBlank()) return emptyList()
        val movies = get("/movies/search?searchType=text&q=${URLEncoder.encode(query, "UTF-8")}")
            ?.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<SubtitleItem>()
        for (i in 0 until minOf(movies.length(), 2)) {
            val movieObj = movies.getJSONObject(i)
            val movieId = movieObj.optLong("movieId", -1L)
            if (movieId < 0) continue
            val movieTitle = movieObj.optString("title", query)
            val subs = get("/subtitles?movieId=$movieId&language=$SUBSOURCE_LANGUAGE")
                ?.optJSONArray("data") ?: continue
            for (j in 0 until subs.length()) {
                val s = subs.getJSONObject(j)
                val subId = s.optLong("subtitleId", -1L)
                if (subId < 0) continue
                val releases = s.optJSONArray("releaseInfo")
                // Subtitle objects have no "title" field; use the first release name (fallback: searched name)
                val firstRelease = releases?.optString(0, "").orEmpty()
                val title = movieTitle
                val displayName = safe(if (firstRelease.isNotBlank()) firstRelease else title)
                val rating = s.optJSONObject("rating")
                val good = rating?.optInt("good", 0) ?: 0
                val total = rating?.optInt("total", 0) ?: 0
                val stars = if (total > 0) good * 10f / total else 0f
                result.add(
                    SubtitleItem(
                        idSubtitle = "$SUBSOURCE_ID_PREFIX$subId",
                        fileId = subId,
                        mediaUri = mediaUri,
                        subLanguageID = SUBSOURCE_LANGUAGE_CODE,
                        movieReleaseName = displayName,
                        state = State.NotDownloaded,
                        zipDownloadLink = "$SUBSOURCE_BASE/subtitles/$subId/download",
                        hearingImpaired = s.optBoolean("hearingImpaired", false),
                        rating = stars,
                        downloadNumber = s.optLong("downloads", 0L),
                        fileName = safe("${title}_$subId") + ".srt"
                    )
                )
            }
        }
        return result.sortedByDescending { it.downloadNumber }
    }

    /**
     * Extracts the first subtitle file found in a SubSource zip.
     * @return the absolute path of the extracted file or null
     */
    fun extractSubtitle(zipPath: String, outDir: File, baseName: String): String? {
        val allowed = setOf("srt", "ass", "ssa", "sub", "vtt", "smi")
        return try {
            ZipInputStream(FileInputStream(zipPath)).use { zin ->
                var entry = zin.nextEntry
                var found: String? = null
                while (entry != null && found == null) {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (!entry.isDirectory && ext in allowed) {
                        val out = File(outDir, "$baseName.$ext")
                        FileOutputStream(out).use { zin.copyTo(it) }
                        found = out.absolutePath
                    } else {
                        entry = zin.nextEntry
                    }
                }
                found
            }
        } catch (e: Exception) {
            Log.w(SS_TAG, "Zip extraction failed: ${e.message}", e)
            null
        }
    }
}
