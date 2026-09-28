package com.example.cattlemonitor

import android.app.Application

/**
 * Initializes ServiceLocator in Application.onCreate — before ANY component
 * (activity or FirebaseMessagingService) runs. FCM can wake the process for a
 * push when no activity exists; without this, onNewToken would crash trying
 * to reach an uninitialized context.
 */
class CattleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
