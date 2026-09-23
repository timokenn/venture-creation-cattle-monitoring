package com.example.cattlemonitor.data

import androidx.annotation.VisibleForTesting
import com.example.cattlemonitor.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Order
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID

class DeviceInUseException : Exception("That device ID is already registered to another cow")
class CowNotFoundException : Exception("Cow no longer exists")
class SupabaseFunctionException(val code: Int, detail: String) :
    Exception("Function error $code: $detail")

/**
 * Single gateway to Supabase for the whole app: PostgREST reads, Edge
 * Function writes (the app has no direct write access by design), and FCM
 * token registration.
 *
 * Live updates use Realtime postgres_changes purely as an *invalidation
 * signal* — every emission is served by the same PostgREST read path, so
 * snapshot-shape parsing sits in exactly one place and a missed/undecodable
 * realtime event can at worst delay a refresh, never corrupt it. A periodic
 * poll fallback covers realtime outages or a missing publication.
 */
class CattleRepository(
    private val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
    ) {
        install(Postgrest)
        install(Realtime)
    },
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
                order("timestamp", Order.DESCENDING)
                limit(5000)
            }
            .decodeList<ReadingDto>()
            .map { it.toDomain() }

    private suspend fun fetchAlerts(cowId: String?): List<Alert> =
        client.postgrest.from("alerts")
            .select {
                order("timestamp", Order.DESCENDING)
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
                subscribed?.let { runCatching { client.removeChannel(it) } }
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
            !code.isSuccess() -> throw SupabaseFunctionException(code, body)
        }
        json.decodeFromString<CowIdResponse>(body).id
    }

    suspend fun updateCow(id: String, name: String?, deviceId: String?): Result<Unit> =
        runCatching {
            val payload: Map<String, String> = buildMap {
                put("id", id)
                if (!name.isNullOrBlank()) put("name", name)
                if (!deviceId.isNullOrBlank()) put("device_id", deviceId)
            }
            val (code, body) = invokeFunction("update-cow", json.encodeToString(payload))
            when {
                code == 409 -> throw DeviceInUseException()
                code == 404 -> throw CowNotFoundException()
                !code.isSuccess() -> throw SupabaseFunctionException(code, body)
            }
        }

    suspend fun deleteCow(id: String): Result<Unit> = runCatching {
        val (code, body) = invokeFunction("delete-cow", json.encodeToString(mapOf("id" to id)))
        when {
            code == 404 -> throw CowNotFoundException()
            !code.isSuccess() -> throw SupabaseFunctionException(code, body)
        }
    }

    suspend fun registerFcmToken(token: String): Result<Unit> = runCatching {
        val (code, body) = invokeFunction("register-token", json.encodeToString(FcmTokenBody(token)))
        if (!code.isSuccess()) throw SupabaseFunctionException(code, body)
    }

    // ------------------------------------------------------------- internals

    private suspend fun invokeFunction(name: String, payloadJson: String): Pair<Int, String> {
        val response = http.post("${BuildConfig.SUPABASE_URL}/functions/v1/$name") {
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            bearerAuth(BuildConfig.SUPABASE_ANON_KEY)
            contentType(ContentType.Application.Json)
            setBody(payloadJson)
        }
        return response.status.value to response.bodyAsText()
    }
}
