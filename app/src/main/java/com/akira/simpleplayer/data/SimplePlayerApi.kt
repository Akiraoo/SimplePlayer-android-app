package com.akira.simpleplayer.data

import com.akira.simpleplayer.AppExecutors
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Result of a full resync: the new server snapshot plus how many per-track downloads failed. */
data class SyncResult(
    val tracks: List<Track>,
    val playlists: Map<String, List<String>>,
    val failed: Int
)

class SimplePlayerApi(baseUrl: String, cacheDir: File) {
    private val base = baseUrl.trimEnd('/')
    private val apiCacheDir = File(cacheDir, "api").apply { mkdirs() }
    private val coverDir = File(cacheDir, "cover-files").apply { mkdirs() }

    /**
     * Public URL of the server (its `publicOrigin` setting), read from /api/config.
     * Empty for a pure-LAN deployment; share links then fall back to the API address.
     *
     * Filled by refreshServerConfig(). Callers must not mutate it directly.
     */
    @Volatile
    var publicOrigin: String = ""
        private set

    /** Web (player) port advertised by the server. -1 when unknown. */
    @Volatile
    var webPort: Int = -1
        private set

    init {
        // The app only talks to the server on resync, so keep the last /api/config answer
        // on disk; share links then stay correct after the app restarts.
        readCache("config.json")?.let { runCatching { applyServerConfig(it) } }
    }

    private fun urlFor(path: String) =
        URL(if (base.startsWith("http://") || base.startsWith("https://")) base + path else "http://$base$path")

    private suspend fun request(path: String, method: String = "GET", readTimeoutMs: Int = 10000): String = withContext(AppExecutors.io) {
        val url = urlFor(path)
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            if (method != "GET") {
                // Explicit empty body, same as the web player's fetch(..., {method:'POST'}).
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
        }
        try {
            if (method != "GET") c.outputStream.close()
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("HTTP $code: $text")
            text
        } finally { c.disconnect() }
    }

    /**
     * Reads /api/config and stores publicOrigin / webPort (used for share links).
     *
     * Never throws. A server without the endpoint (older build) simply keeps
     * the defaults set at construction.
     */
    suspend fun refreshServerConfig() {
        runCatching {
            val raw = request("/api/config")
            applyServerConfig(raw)
            writeCache("config.json", raw)
        }
    }

    private fun applyServerConfig(raw: String) {
        val o = JSONObject(raw)
        // An empty publicOrigin means the server is LAN-only: clear any value from an older answer.
        publicOrigin = o.optString("publicOrigin").trim().trimEnd('/')
        val wp = o.optInt("webPort", -1)
        if (wp > 0) {
            webPort = wp
        }
    }

    fun cachedLibrary(): List<Track>? = readCache("library.json")?.let(::parseLibrarySafe)

    fun cachedPlaylists(): Map<String, List<String>>? = readCache("playlists.json")?.let(::parsePlaylistsSafe)

    suspend fun metadata(id: String): Pair<Track?, List<LyricLine>> {
        val raw = request("/api/track/${enc(id)}/metadata")
        return withContext(AppExecutors.cpu) {
            writeCache(metadataFileName(id), raw)
            parseMetadata(raw)
        }
    }

    fun cachedMetadata(id: String): Pair<Track?, List<LyricLine>>? =
        readCache(metadataFileName(id))?.let(::parseMetadataSafe)

    // The server scans the music dir and reads tags of new/changed files before it answers,
    // so give it far more time than a normal request.
    suspend fun scan(): JSONObject = JSONObject(request("/api/scan", "POST", readTimeoutMs = 180_000))

    /** Local copy of the cover, or null when it has not been synced. */
    fun cachedCover(id: String): File? =
        File(coverDir, coverFileName(id)).takeIf { it.isFile && it.length() > 0 }

    private fun hasCachedMetadata(id: String) = File(apiCacheDir, metadataFileName(id)).isFile

    /**
     * Full resync. The only place that talks to the server for the library:
     *  1. read /api/config so share links use the server's public address
     *  2. fetch library + playlists
     *  3. download metadata / cover for new or changed tracks (and any that are missing locally)
     *  4. delete cached metadata / covers of tracks that no longer exist on the server
     *  5. only then replace library.json / playlists.json
     * If anything throws before step 5 the previous snapshot stays intact.
     */
    suspend fun sync(onProgress: (done: Int, total: Int) -> Unit): SyncResult {
        refreshServerConfig()

        val oldById = cachedLibrary().orEmpty().associateBy { it.id }

        val rawLibrary = request("/api/library")
        val rawPlaylists = request("/api/playlists")
        val (tracks, playlists) = withContext(AppExecutors.cpu) {
            parseLibrary(rawLibrary) to parsePlaylists(rawPlaylists)
        }

        data class Work(val track: Track, val metadata: Boolean, val cover: Boolean)

        val jobs = tracks.mapNotNull { t ->
            val changed = oldById[t.id] != t
            val needMetadata = changed || !hasCachedMetadata(t.id)
            val needCover = t.hasCover && (changed || cachedCover(t.id) == null)
            if (needMetadata || needCover) Work(t, needMetadata, needCover) else null
        }

        onProgress(0, jobs.size)

        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val gate = Semaphore(3)
        coroutineScope {
            jobs.map { job ->
                async {
                    gate.withPermit {
                        var ok = true
                        if (job.metadata) ok = runCatching { metadata(job.track.id) }.isSuccess && ok
                        if (job.cover) ok = runCatching { downloadCover(job.track.id) }.isSuccess && ok
                        if (!ok) failed.incrementAndGet()
                        onProgress(done.incrementAndGet(), jobs.size)
                    }
                }
            }.awaitAll()
        }

        withContext(AppExecutors.io) {
            prune(tracks)
            writeCache("library.json", rawLibrary)
            writeCache("playlists.json", rawPlaylists)
        }

        return SyncResult(tracks, playlists, failed.get())
    }

    /** Delete cached files that do not belong to any track in [tracks]. */
    private fun prune(tracks: List<Track>) {
        val keepApi = tracks.mapTo(HashSet()) { metadataFileName(it.id) }.apply {
            add("library.json")
            add("playlists.json")
            add("config.json")
        }
        apiCacheDir.listFiles()?.forEach { if (it.name !in keepApi) it.delete() }

        val keepCovers = tracks.filter { it.hasCover }.mapTo(HashSet()) { coverFileName(it.id) }
        coverDir.listFiles()?.forEach { if (it.name !in keepCovers) it.delete() }
    }

    private suspend fun downloadCover(id: String) = withContext(AppExecutors.io) {
        val target = File(coverDir, coverFileName(id))
        val tmp = File(coverDir, coverFileName(id) + ".tmp")
        val c = (urlFor("/api/track/${enc(id)}/cover").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 30000
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) error("HTTP $code")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() == 0L) error("empty cover")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
        } finally {
            c.disconnect()
            tmp.delete()
        }
        Unit
    }

    fun streamUrl(id: String) = "$base/stream/${enc(id)}"

    fun webShareUrl(id: String) = webBase() + "/s/${enc(id)}"
    fun webDirectUrl(id: String) = webBase() + "/d/${enc(id)}"
    fun webPlaylistUrl(name: String) = webBase() + "/p/${enc(name)}"

    private fun parseLibrary(raw: String): List<Track> {
        val root = JSONObject(raw)
        val a = root.optJSONArray("tracks") ?: JSONArray()
        return (0 until a.length()).map { parseTrack(a.getJSONObject(it)) }
    }

    private fun parseLibrarySafe(raw: String): List<Track>? = runCatching { parseLibrary(raw) }.getOrNull()

    private fun parsePlaylists(raw: String): Map<String, List<String>> {
        val o = JSONObject(raw)
        return o.keys().asSequence().associateWith { key ->
            val a = o.optJSONArray(key) ?: JSONArray()
            (0 until a.length()).map { a.getString(it) }
        }
    }

    private fun parsePlaylistsSafe(raw: String): Map<String, List<String>>? = runCatching { parsePlaylists(raw) }.getOrNull()

    private fun parseMetadata(raw: String): Pair<Track?, List<LyricLine>> {
        val o = JSONObject(raw)
        return parseTrack(o) to parseLyrics(o.optJSONArray("lyrics"))
    }

    private fun parseMetadataSafe(raw: String): Pair<Track?, List<LyricLine>>? = runCatching { parseMetadata(raw) }.getOrNull()

    private fun parseTrack(o: JSONObject): Track = Track(
        id = o.optString("id"), name = o.optString("name"),
        title = o.optString("title", o.optString("name")), artist = o.optString("artist"),
        album = o.optString("album"), folder = o.optString("folder"), ext = o.optString("ext"),
        size = o.optLong("size"), mtime = o.optLong("mtime"), hasCover = o.optBoolean("hasCover")
    )

    private fun parseLyrics(a: JSONArray?): List<LyricLine> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val raw = if (o.has("time") && !o.isNull("time")) o.optDouble("time", Double.NaN) else Double.NaN
            LyricLine(if (raw.isFinite()) raw else null, o.optString("text"))
        }
    }

    private fun readCache(name: String): String? = runCatching {
        val file = File(apiCacheDir, name)
        if (!file.isFile) null else file.readText()
    }.getOrNull()

    private fun writeCache(name: String, text: String) {
        runCatching {
            val target = File(apiCacheDir, name)
            val tmp = File(apiCacheDir, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        }
    }

    private fun metadataFileName(id: String) = "metadata_${cacheKey(id)}.json"
    private fun coverFileName(id: String) = "${cacheKey(id)}.img"

    private fun cacheKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Base URL used for share / download links.
     *
     *  - The server's publicOrigin (from /api/config) wins, so shared links work outside the LAN.
     *  - An API address without a port (e.g. https://music.example.com behind a reverse proxy)
     *    is used as-is: the proxy already serves the web player there.
     *  - Otherwise use the web port advertised by /api/config,
     *    or the legacy convention API 8788 -> web 8787, or the same port.
     */
    private fun webBase(): String {
        if (publicOrigin.isNotBlank()) return publicOrigin

        val u = runCatching {
            URL(if (base.startsWith("http://") || base.startsWith("https://")) base else "http://$base")
        }.getOrNull() ?: return base

        if (u.port == -1) return URL(u.protocol, u.host, -1, "").toString().trimEnd('/')

        val resolvedPort = when {
            webPort > 0 -> webPort
            u.port == 8788 -> 8787
            else -> u.port
        }
        return URL(u.protocol, u.host, resolvedPort, "").toString().trimEnd('/')
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}