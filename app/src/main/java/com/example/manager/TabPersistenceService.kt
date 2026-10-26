package com.example.manager

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class TabPersistenceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("TabPersistenceService", "Service started to track task swipe removal")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("TabPersistenceService", "onTaskRemoved: App swiped away from recent apps")
        try {
            val settings = SettingsManager(applicationContext)
            if (settings.clearCookiesOnExit) {
                settings.clearPersistedTabs()
                try {
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                    Log.d("TabPersistenceService", "Cleared cookies, web storage, and persisted tabs on exit as configured")
                } catch (e: Exception) {
                    Log.w("TabPersistenceService", "Failed to clear cookies on exit: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("TabPersistenceService", "Failed to handle task removal cleanup", e)
        }
        stopSelf()
    }
}
