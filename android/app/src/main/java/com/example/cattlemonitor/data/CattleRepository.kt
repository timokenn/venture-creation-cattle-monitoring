package com.example.cattlemonitor.data

import androidx.annotation.VisibleForTesting
import com.example.cattlemonitor.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID

class DeviceInUseException : Exception("That device ID is already registered to another cow")
class CowNotFoundException : Exception("Cow no longer exists")
class SupabaseFunctionException(val code: Int, detail: String) :
    Exception("Function error $code: $detail")


/** One aggregated time bucket from readings_downsample() (#10). */
data class Bucket(
    val bucketStart: Date,
    val tempAvg: Double?,
    val activityAvg: Double?,
    val samples: Long,
)

/**
 * Single gateway to Supabase for the whole app: PostgREST reads, Edge
 * Function writes (the app has no direct write access by design), and FCM
 * token registration.
 *
 * Uses the SHARED app client (with the Auth plugin installed) — see
 * ServiceLocator. That way every PostgREST/Realtime request automatically
 * carries the logged-in user's access token, which per-owner RLS
 * (cows.owner_id = auth.uid()) requires: users only ever see their own cows.
 */
class CattleRepository(
    private val client: SupabaseClient,
    private val tokenProvider: () -> String? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient()

    /** How often a live query refreshes even with no realtime event (safety net). */
    @VisibleForTesting
    internal var pollFallbackMs: Long = 60_000

    // ------------------------------------------------------------ live reads

    fun observeCows(): Flow<List<Cow>> = liveQuery("cows") { fetchCows() }

    fun observeCow(cowId: String): Flow<Cow?> = liveQuery("cows") { fetchCow(cowId) }

    fun observeReadings(cowId: String, since: Date): Flow<List<Reading>> =
        liveQuery("readings") { fetchReadings(cowId, since) }

    /** Herd-wide alert feed, newest first, optionally scoped to one cow. */
    fun observeAlerts(cowId: String? = null): Flow<List<Alert>> =
        liveQuery("alerts") { fetchAlerts(cowId) }

    /**
     * RLS returns an EMPTY list (HTTP 200) when a request carries a missing or
     * expired user token — the UI then shows a phantom "0 sapi" herd with no
     * error. This happens after the app idles in the background long enough
     * for the 1-hour access token to lapse (Android freezes the process, so
     * the auto-refresher stalls too).
     *
     * Guard: before every live fetch, check the session locally; if the token
     * is expired or about to expire, force a refresh first. If an ALREADY
     * expired token can't be refreshed (offline), throw so the liveQuery
     * backoff loop retries instead of fetching with the dead token.
     */
    private suspend fun ensureFreshUserToken() {
        val session = client.auth.currentSessionOrNull() ?: return
        val expiresMs = session.expiresAt.toEpochMilliseconds()
        if (expiresMs - System.currentTimeMillis() > 60_000) return // still fresh
        runCatching { client.auth.refreshCurrentSession() }.onFailure {
            if (expiresMs <= System.currentTimeMillis()) throw it // expired & unrefreshable → retry
        }
    }

    private suspend fun fetchCows(): List<Cow> =
        client.postgrest.from("cows")
            .select()
            .decodeList<CowDto>()
            .map { it.toDomain() }

    private suspend fun fetchCow(cowId: String): Cow? =
        client.postgrest.from("cows")
            .select { filter { eq("id", cowId) } }
            .decodeList<CowDto>()
            .firstOrNull()?.toDomain()

    private suspend fun fetchReadings(cowId: String, since: Date): List<Reading> =
        client.postgrest.from("readings")
            .select {
                filter {
                    eq("cow_id", cowId)
                    gt("timestamp", since.toInstant().toString())
                }
                order("timestamp", order = Order.DESCENDING)
                limit(5000)
            }
            .decodeList<ReadingDto>()
            .map { it.toDomain() }

    private suspend fun fetchAlerts(cowId: String?): List<Alert> =
        client.postgrest.from("alerts")
            .select {
                order("timestamp", order = Order.DESCENDING)
                limit(500)
            }
            .decodeList<AlertDto>()
            .mapNotNull { it.toDomain() }
            .let { list -> if (cowId == null) list else list.filter { it.cowId == cowId } }

    /**
     * Fetch-on-start, refetch on any realtime change to [table] OR when the
     * poll interval elapses (whichever comes first — so data stays fresh even
     * if realtime is down or the table isn't in the realtime publication).
     * Network hiccups retry with exponential backoff capped at 30 s.
     */
    private fun <T> liveQuery(table: String, fetch: suspend () -> T): Flow<T> = flow {
        coroutineScope {
            val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
            var subscribed: RealtimeChannel? = null
            val realtimeJob = launch {
                runCatching {
                    val ch = client.channel("cattle-$table-${UUID.randomUUID()}")
                    ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                        this.table = table
                    }.onEach { updates.tryEmit(Unit) }.launchIn(this@coroutineScope)
                    ch.subscribe()
                    subscribed = ch
                } // failure → polling fallback still keeps data fresh
            }
            try {
                while (true) {
                    var backoffMs = 2_000L
                    while (true) {
                        try {
                            ensureFreshUserToken()
                            emit(fetch())
                            break
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            delay(backoffMs)
                            backoffMs = (backoffMs * 2).coerceAtMost(30_000)
                        }
                    }
                    withTimeoutOrNull(pollFallbackMs) { updates.firstOrNull() }
                }
            } finally {
                realtimeJob.cancel()
                subscribed?.let { runCatching { client.realtime.removeChannel(it) } }
            }
        }
    }

    // ----------------------------------------------------------- write paths

    suspend fun addCow(name: String, deviceId: String): Result<String> = runCatching {
        val (code, body) = invokeFunction(
            "register-cow",
            json.encodeToString(RegisterCowRequest(name, deviceId)),
        )
        when {
            code == 409 -> throw DeviceInUseException()
            !(code in 200..299) -> throw SupabaseFunctionException(code, body)
        }
        json.decodeFromString<CowIdResponse>(body).id
    }

    suspend fun updateCow(
        id: String,
        name: String? = null,
        deviceId: String? = null,
        imageUrl: String? = null,
        removePhoto: Boolean = false,
    ): Result<Unit> =
        runCatching {
            // Explicit JsonObject: a Map<String, Any> would compile but THROW at
            // runtime ("Serializer for class 'Any' is not found") — rename and
            // photo updates silently failed that way once a boolean joined the
            // string-only payload.
            val payload = buildJsonObject {
                put("id", JsonPrimitive(id))
                if (!name.isNullOrBlank()) put("name", JsonPrimitive(name))
                if (!deviceId.isNullOrBlank()) put("device_id", JsonPrimitive(deviceId))
                if (!imageUrl.isNullOrBlank()) put("image_url", JsonPrimitive(imageUrl))
                if (removePhoto) put("remove_photo", JsonPrimitive(true))
            }
            val (code, body) = invokeFunction("update-cow", payload.toString())
            when {
                code == 409 -> throw DeviceInUseException()
                code == 404 -> throw CowNotFoundException()
                !(code in 200..299) -> throw SupabaseFunctionException(code, body)
            }
        }

    suspend fun deleteCow(id: String): Result<Unit> = runCatching {
        val (code, body) = invokeFunction("delete-cow", json.encodeToString(mapOf("id" to id)))
        when {
            code == 404 -> throw CowNotFoundException()
            !(code in 200..299) -> throw SupabaseFunctionException(code, body)
        }
    }

    suspend fun registerFcmToken(token: String): Result<Unit> = runCatching {
        val (code, body) = invokeFunction("register-token", json.encodeToString(FcmTokenBody(token)))
        if (code !in 200..299) throw SupabaseFunctionException(code, body)
    }

    /** Raw wire rows for CSV export (#5): exact timestamps + quality flags. */
    @Serializable
    data class ExportRow(val timestamp: String, val temperature: Double, val activity: Double?, val quality: String?)

    @Serializable
    internal data class BucketDto(
        @SerialName("bucket_start") val bucketStart: String,
        @SerialName("temp_avg") val tempAvg: Double? = null,
        @SerialName("activity_avg") val activityAvg: Double? = null,
        @SerialName("samples") val samples: Long = 0,
    )

    internal fun BucketDto.toDomain() = Bucket(
        bucketStart = isoToDate(bucketStart) ?: Date(0),
        tempAvg = tempAvg,
        activityAvg = activityAvg,
        samples = samples,
    )

    suspend fun fetchReadingsForExport(cowId: String, since: Date): List<ExportRow> =
        client.postgrest.from("readings")
            .select {
                filter {
                    eq("cow_id", cowId)
                    gt("timestamp", since.toInstant().toString())
                }
                order("timestamp", order = Order.ASCENDING)
                limit(20_000)
            }
            .decodeList<ReadingDto>()
            .map { ExportRow(it.timestamp, it.temperature, it.activityIndex, it.dataQuality) }

    /**
     * Downsampled buckets for the 7d view (#10) — server-side time-bucket
     * aggregation via readings_downsample(), so a week of 20s readings
     * (~30k rows) arrives as a few hundred buckets instead of truncating at
     * the 5,000-row raw cap.
     */
    suspend fun fetchDownsampled(cowId: String, since: Date, bucketSeconds: Int = 600): List<Bucket> = runCatching {
        val params = buildJsonObject {
            put("p_cow_id", JsonPrimitive(cowId))
            put("p_since", JsonPrimitive(since.toInstant().toString()))
            put("p_bucket_seconds", JsonPrimitive(bucketSeconds))
        }
        val buckets: List<Bucket> = client.postgrest.rpc("readings_downsample", params)
            .decodeList<BucketDto>()
            .map { it.toDomain() }
        buckets
    }.getOrElse { emptyList() } // RPC missing (pre-migration) → fall back to raw path

    // ------------------------------------------------------------- internals

    private suspend fun invokeFunction(name: String, payloadJson: String): Pair<Int, String> {
        val response = http.post("${BuildConfig.SUPABASE_URL}/functions/v1/$name") {
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            // Prefer the logged-in user's token: register/update/delete-cow
            // stamp and enforce ownership from it. Fall back to the publishable
            // key (pre-login) — those functions then answer 401.
            bearerAuth(tokenProvider() ?: BuildConfig.SUPABASE_ANON_KEY)
            contentType(ContentType.Application.Json)
            setBody(payloadJson)
        }
        return response.status.value to response.bodyAsText()
    }
}
