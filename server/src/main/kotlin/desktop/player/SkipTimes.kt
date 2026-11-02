package desktop.player

import desktop.db.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Opening/ending times from AniSkip (api.aniskip.com), by MyAnimeList id and episode number. Results are cached in
 * SQLite without AniSkip's length filter, and matched to the playing file's length here, so one fetch serves every
 * link of an episode. "Not found" is cached for a day.
 */
class SkipTimes(private val db: Database, private val http: OkHttpClient = defaultClient) {
    private val log = LoggerFactory.getLogger(SkipTimes::class.java)
    private val malIds = ConcurrentHashMap<Int, Int>()

    /** Skip ranges for an episode of [duration] seconds, or none when AniSkip doesn't know it. */
    suspend fun forEpisode(malId: Int?, aniListId: Int?, episode: Int, duration: Double): List<SkipRange> {
        val mal = malId ?: aniListId?.let { malIdFor(it) } ?: return emptyList()
        return matching(results(mal, episode), duration)
    }

    private suspend fun results(mal: Int, episode: Int): String {
        val cached = db.query("SELECT json, fetched FROM skip_times WHERE mal_id = ? AND episode = ?", mal, episode) {
            it.getString(1) to it.getLong(2)
        }.firstOrNull()
        val now = System.currentTimeMillis()
        if (cached != null) {
            val found = parse(cached.first).isNotEmpty()
            if (now - cached.second < if (found) FOUND_TTL else NOT_FOUND_TTL) return cached.first
        }
        val url = "$API/skip-times/$mal/$episode?types=op&types=ed&types=mixed-op&types=mixed-ed&types=recap&episodeLength=0"
        val body = try {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                    // AniSkip answers "not found" with a 404 and a normal JSON body.
                    if (!res.isSuccessful && res.code != 404) throw IllegalStateException("AniSkip answered ${res.code}")
                    res.body.string()
                }
            }
        } catch (e: Exception) {
            log.info("AniSkip lookup for MAL {} episode {} failed: {}", mal, episode, e.message)
            return cached?.first ?: "{}"
        }
        db.update(
            "INSERT INTO skip_times(mal_id, episode, json, fetched) VALUES(?,?,?,?) ON CONFLICT(mal_id, episode) DO UPDATE SET json = excluded.json, fetched = excluded.fetched",
            mal, episode, body, now,
        )
        return body
    }

    /** MyAnimeList id for an AniList id, through AniList's GraphQL API. */
    private suspend fun malIdFor(aniListId: Int): Int? {
        malIds[aniListId]?.let { return it }
        val query = buildJsonObject {
            put("query", "query(\$id:Int){Media(id:\$id,type:ANIME){idMal}}")
            putJsonObject("variables") { put("id", aniListId) }
        }
        return try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url("https://graphql.anilist.co")
                    .post(query.toString().toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use { res ->
                    Json.parseToJsonElement(res.body.string()).jsonObject["data"]?.jsonObject?.get("Media")?.jsonObject
                        ?.get("idMal")?.jsonPrimitive?.intOrNull
                }
            }?.also { malIds[aniListId] = it }
        } catch (e: Exception) {
            log.info("AniList id {} → MAL lookup failed: {}", aniListId, e.message)
            null
        }
    }

    companion object {
        private const val API = "https://api.aniskip.com/v2"
        private const val FOUND_TTL = 7 * 86_400_000L
        private const val NOT_FOUND_TTL = 86_400_000L

        /** Submissions are timed against one cut of the episode; ones for a cut this far off in length are dropped. */
        private const val LENGTH_TOLERANCE = 15.0

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        private class Result(val type: String, val start: Double, val end: Double, val length: Double)

        private fun parse(json: String): List<Result> = runCatching {
            val results = (Json.parseToJsonElement(json) as? JsonObject)?.get("results")?.jsonArray ?: return emptyList()
            results.mapNotNull { r ->
                val o = r.jsonObject
                val interval = o["interval"]?.jsonObject ?: return@mapNotNull null
                val type = when (o["skipType"]?.jsonPrimitive?.contentOrNull) {
                    "op", "mixed-op" -> "op"
                    "ed", "mixed-ed" -> "ed"
                    "recap" -> "recap"
                    else -> return@mapNotNull null
                }
                Result(
                    type,
                    interval["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                    interval["endTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                    o["episodeLength"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                )
            }
        }.getOrDefault(emptyList())

        /**
         * The best range of each type for a file of [duration] seconds: AniSkip lists results best first, so take the
         * first of each type whose cut matches the file's length (or that has no length), clamped to the file.
         */
        fun matching(json: String, duration: Double): List<SkipRange> =
            parse(json)
                .filter { it.length <= 0 || duration <= 0 || kotlin.math.abs(it.length - duration) <= LENGTH_TOLERANCE }
                .groupBy { it.type }.values.map { it.first() }
                .map { SkipRange(it.type, it.start.coerceAtLeast(0.0), if (duration > 0) it.end.coerceAtMost(duration) else it.end) }
                .filter { it.end - it.start >= 5 }
                .sortedBy { it.start }
    }
}
