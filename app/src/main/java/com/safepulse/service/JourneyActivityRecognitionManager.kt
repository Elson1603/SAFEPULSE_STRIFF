package com.safepulse.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import kotlinx.coroutines.tasks.await

class JourneyActivityRecognitionManager(private val context: Context) {
    private val client = ActivityRecognition.getClient(context)

    suspend fun start() {
        if (!hasPermission()) {
            Log.w(TAG, "Activity recognition permission missing; journey phase updates will use location/risk only")
            return
        }

        runCatching {
            client.requestActivityUpdates(DETECTION_INTERVAL_MS, pendingIntent()).await()
        }.onFailure {
            Log.e(TAG, "Unable to start activity recognition", it)
        }
    }

    suspend fun stop() {
        runCatching {
            client.removeActivityUpdates(pendingIntent()).await()
        }.onFailure {
            Log.e(TAG, "Unable to stop activity recognition", it)
        }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, JourneyActivityReceiver::class.java)
        val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }

        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag
        )
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        private const val TAG = "JourneyActivity"
        private const val REQUEST_CODE = 5301
        private const val DETECTION_INTERVAL_MS = 30_000L
    }
}
