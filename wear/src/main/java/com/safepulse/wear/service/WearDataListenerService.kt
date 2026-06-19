package com.safepulse.wear.service

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.safepulse.wear.WearSafePulseApp

/**
 * Background listener service that receives data and messages from the phone app.
 * This runs even when the watch app UI is not active.
 */
class WearDataListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WearDataListener"
    }

    private val communicationManager
        get() = (application as WearSafePulseApp).communicationManager

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WearDataListenerService created")
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        Log.d(TAG, "Data changed: ${dataEvents.count} events")
        communicationManager.onDataChanged(dataEvents)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        Log.d(TAG, "Message received: ${messageEvent.path}")
        communicationManager.onMessageReceived(messageEvent.path, messageEvent.data)
    }
}
