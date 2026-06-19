package com.safepulse.wear.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.safepulse.wear.data.WearPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores watch-side monitoring after reboot or app update.
 */
class WearBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val appContext = context.applicationContext
                val preferences = WearPreferences(appContext)
                val shouldStart = preferences.serviceEnabledFlow.first() ||
                        preferences.backgroundVoiceSOSEnabledFlow.first()

                if (shouldStart) {
                    WearSafetyService.start(appContext)
                }
            } catch (e: Exception) {
                Log.e("WearBootReceiver", "Failed to restore wear safety service", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
