package com.example.cattlemonitor

import android.content.Context
import com.example.cattlemonitor.data.AuthRepository
import com.example.cattlemonitor.data.CattleRepository
import com.example.cattlemonitor.data.ConnectivityObserver
import com.example.cattlemonitor.data.CowPhotoUploader
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

/** Tiny manual service locator — no DI framework needed at this scale. */
object ServiceLocator {

    private var appContext: Context? = null

    /** Call once from MainActivity.onCreate, before anything touches these. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val context: Context
        get() = appContext ?: error("ServiceLocator.init(context) was not called")

    /** Application context for one-shot helpers (photo upload temp work). */
    fun appContext(): Context = context

    /**
     * ONE Supabase client for the whole app. With the Auth plugin installed on
     * the same client, PostgREST and Realtime requests automatically carry the
     * logged-in user's access token — which is exactly what the per-owner RLS
     * policies (cows.owner_id) need. supabase-kt persists the session in
     * encrypted DataStore by default, so login survives full app restarts.
     */
    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Postgrest)
            install(Realtime)
            install(Auth)
        }
    }

    val auth: AuthRepository by lazy { AuthRepository(client, context) }
    val repository: CattleRepository by lazy {
        CattleRepository(client) { auth.currentAccessToken() }
    }

    /** App-scoped online/offline tracker for the offline screen. */
    val connectivity: ConnectivityObserver by lazy { ConnectivityObserver(context) }

    /** Compresses + uploads cow profile photos to Storage (user-token authed). */
    val photoUploader: CowPhotoUploader by lazy {
        CowPhotoUploader(tokenProvider = { auth.currentAccessToken() })
    }
}
