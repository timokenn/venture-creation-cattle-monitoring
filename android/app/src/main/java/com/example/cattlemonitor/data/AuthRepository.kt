package com.example.cattlemonitor.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.authDataStore by preferencesDataStore(name = "auth_prefs")

/** How many wrong passwords before the "too many attempts" message appears. */
const val MAX_LOGIN_ATTEMPTS = 5
/** Cooldown before the next try, in ms — mirrors Supabase's own rate limits. */
const val LOCKOUT_MS = 30_000L

/**
 * Email/password auth on the SHARED Supabase client (see ServiceLocator):
 * the same client performs all PostgREST/Realtime reads, so those requests
 * automatically carry the logged-in user's token (per-owner RLS).
 *
 * Login persistence: supabase-kt stores the session in encrypted DataStore
 * and restores it on process start — the app boots straight into the herd,
 * no re-login, until the user explicitly logs out.
 *
 * Failed-attempt lockout: GoTrue on the free tier has no per-account lockout
 * knob, so consecutive failures are counted locally (per install) and the
 * login button is held for a short cooldown after MAX_LOGIN_ATTEMPTS misses.
 * A successful login clears the counter; a "Forgot password" reset flow is
 * offered on the login screen.
 */
class AuthRepository(
    private val client: SupabaseClient,
    context: Context,
) {

    private val context = context.applicationContext

    private val attemptCount = intPreferencesKey("failed_login_attempts")
    private val lockedUntil = longPreferencesKey("locked_until_ms")

    private val auth get() = client.auth

    /**
     * Emits the resolved session state. `null` = still restoring from disk
     * (the splash waits for a decision), `true` = logged in, `false` = not.
     * Session restoration and token refresh are handled by supabase-kt.
     */
    val sessionState: Flow<Boolean?> = auth.sessionStatus.map { status ->
        when (status) {
            is SessionStatus.Authenticated -> true
            is SessionStatus.NotAuthenticated -> false
            else -> null // Initializing / RefreshFailure — don't decide yet
        }
    }

    val isLoggedIn: Flow<Boolean> = sessionState.map { it == true }

    val userEmail: String?
        get() = auth.currentUserOrNull()?.email

    val userId: String?
        get() = auth.currentUserOrNull()?.id

    suspend fun currentUser(): UserInfo? = auth.currentUserOrNull()

    /** Access token for the shared client's request headers (edge function calls). */
    fun currentAccessToken(): String? = auth.currentAccessTokenOrNull()

    suspend fun signIn(email: String, password: String): Result<Unit> = runCatching {
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        clearFailures()
    }

    suspend fun signUp(email: String, password: String): Result<Unit> = runCatching {
        auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        clearFailures()
    }

    /** Sends the "reset your password" email (Supabase GoTrue flow). */
    suspend fun sendPasswordReset(email: String): Result<Unit> = runCatching {
        auth.resetPasswordForEmail(email)
    }

    suspend fun signOut(): Result<Unit> = runCatching { auth.signOut() }

    // ------------------------------------------------------ lockout tracker

    /** Result of a failed sign-in: whether the attempt counter tripped a lock. */
    data class FailureState(val failedCount: Int, val lockedUntilEpochMs: Long)

    suspend fun registerFailure(): FailureState {
        // Single-user UI path — read-then-write is fine here (no concurrent writers).
        val current = context.authDataStore.data.first()
        val count = (current[attemptCount] ?: 0) + 1
        val until = if (count >= MAX_LOGIN_ATTEMPTS) {
            System.currentTimeMillis() + LOCKOUT_MS
        } else {
            current[lockedUntil] ?: 0L
        }
        context.authDataStore.edit { prefs ->
            prefs[attemptCount] = count
            prefs[lockedUntil] = until
        }
        return FailureState(count, until)
    }
    /** Milliseconds remaining on the lockout, 0 if none. */
    suspend fun lockoutRemainingMs(): Long =
        ((context.authDataStore.data.first()[lockedUntil] ?: 0L) - System.currentTimeMillis())
            .coerceAtLeast(0L)

    suspend fun failedAttempts(): Int =
        context.authDataStore.data.first()[attemptCount] ?: 0

    suspend fun clearFailures() {
        context.authDataStore.edit { prefs ->
            prefs.remove(attemptCount)
            prefs.remove(lockedUntil)
        }
    }
}
