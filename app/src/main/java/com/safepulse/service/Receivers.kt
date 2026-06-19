package com.safepulse.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.safepulse.data.prefs.UserPreferences
import com.safepulse.utils.SafetyConstants
import com.safepulse.worker.SafetyCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Receiver for cancelling SOS from notification action
 */
class SOSCancelReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SafetyConstants.ACTION_CANCEL_SOS) {
            SafetyForegroundService.getInstance()?.cancelEmergencyCountdown()
        }
    }
}

/**
 * Receiver for restarting service after device boot
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val settings = UserPreferences(context).userSettingsFlow.first()
                    if (settings.serviceEnabled && settings.autoStartOnBoot) {
                        SafetyForegroundService.start(context)
                        SafetyCheckWorker.schedule(context)
                        Log.i("BootReceiver", "Safety monitoring restarted after boot")
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to restore safety monitoring after boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
